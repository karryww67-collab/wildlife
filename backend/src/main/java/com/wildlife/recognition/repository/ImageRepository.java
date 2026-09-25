package com.wildlife.recognition.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wildlife.recognition.entity.RecognitionImage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ImageRepository extends BaseMapper<RecognitionImage> {

    /** 图像终态：识别成功。 */
    String STATUS_SUCCESS = "SUCCESS";

    /** 图像终态：识别失败（也是"任务已取消"时未完成图像的收尾状态 —— 枚举里没有 CANCELED）。 */
    String STATUS_FAILED = "FAILED";

    /** recognition_image.error_message 是 VARCHAR(500)，写入前截断，保证写库不失败。 */
    int ERROR_MESSAGE_MAX_LENGTH = 500;

    /**
     * 把某任务下所有还没出结果的图像一次性收尾为 FAILED。
     *
     * <pre>
     * UPDATE recognition_image
     *    SET status = 'FAILED',
     *        error_message = ?
     *  WHERE task_id = ?
     *    AND status NOT IN ('SUCCESS', 'FAILED')
     * </pre>
     *
     * <p>两个调用场景，语义完全一致 —— "这批图不会再有人回来认领了，就地落定"：
     * <ul>
     *   <li><b>任务整批失败</b>：AI 引擎抛异常后不会再回写任何结果；</li>
     *   <li><b>任务被取消</b>：取消后迟到的逐张结果会被 {@code RedisSubscriberService.markImage}
     *       的任务状态守卫挡下，同样没人能把它们推进终态，放任下去就会永远停在"待识别 / 识别中"。</li>
     * </ul>
     * 因此这条 SQL 只写一份、放在图像表自己的仓储里，避免两个 Service 各写一份将来会漂移的实现。
     *
     * <p>注意它**不做任务状态判断**：调用方负责在正确时机调用（取消成功后 / 确认整批失败后），
     * 并自带"不影响已是 SUCCESS 的图"这条底线 —— 已完成图像的识别结果与人工复核结论都被保留。
     *
     * @param taskId 任务主键
     * @param reason 写入 {@code error_message} 的原因（超长按列宽截断）
     * @return 受影响行数：被收尾的图像张数
     */
    default int failPendingImages(Long taskId, String reason) {
        String text = reason == null ? "" : reason;
        if (text.length() > ERROR_MESSAGE_MAX_LENGTH) {
            text = text.substring(0, ERROR_MESSAGE_MAX_LENGTH);
        }

        UpdateWrapper<RecognitionImage> wrapper = new UpdateWrapper<>();
        wrapper.set("status", STATUS_FAILED)
                .set("error_message", text)
                .eq("task_id", taskId)
                .notIn("status", STATUS_SUCCESS, STATUS_FAILED);
        return update(null, wrapper);
    }

    // ── 批量复制（⑨-C：retry 派生新任务） ────────────────────────────────────────

    /** 单条批量 INSERT 最多携带的行数，与 {@code ImageService.ATTACH_BATCH_SIZE} 同量级。 */
    int INSERT_BATCH_SIZE = 1000;

    /**
     * 真正的批量插入：一条 SQL 带多行 VALUES。
     *
     * <pre>
     * INSERT INTO recognition_image (task_id, file_name, ..., create_time)
     * VALUES (?, ?, ..., ?), (?, ?, ..., ?), ...
     * </pre>
     *
     * <p>语句由 MyBatis-Plus 官方的
     * {@code com.baomidou.mybatisplus.extension.injector.methods.InsertBatchSomeColumn}
     * 注入（见 {@code MybatisPlusConfig#sqlInjector()}），因此方法名必须与它写死的
     * {@code methodName} 完全一致，否则启动即报 Invalid bound statement。列清单由实体注解推导。
     *
     * <p>主键 {@code RecognitionImage.id} 是 {@code IdType.AUTO} ⇒ 该列不会出现在生成的 INSERT 里，
     * 传进来的实体上 {@code id} 是什么都无所谓（⑨-C 复制时显式置 {@code null} 只是表意）。
     *
     * <p>⚠️ <b>不回填自增主键</b> —— 插完必须按 {@code task_id} 回查才能拿到新 imageId。
     *
     * @param list 待插入的图像行（非空集合）
     * @return 实际插入的行数
     */
    int insertBatchSomeColumn(@Param("list") List<RecognitionImage> list);

    /**
     * 分批调用 {@link #insertBatchSomeColumn}，避免 10 万张图拼成单条超长 SQL。
     *
     * @param images 待插入的图像行（空集合直接返回 0）
     * @return 累计插入行数
     */
    default int insertBatchInChunks(List<RecognitionImage> images) {
        if (images == null || images.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (int start = 0; start < images.size(); start += INSERT_BATCH_SIZE) {
            int end = Math.min(start + INSERT_BATCH_SIZE, images.size());
            inserted += insertBatchSomeColumn(images.subList(start, end));
        }
        return inserted;
    }

    // ── 文件引用计数（⑨-C：物理文件共享保护） ────────────────────────────────────

    /**
     * 统计「除某一行之外」还有多少行引用同一个 {@code file_path}。
     *
     * <pre>
     * SELECT COUNT(*) FROM recognition_image WHERE file_path = ? AND id &lt;&gt; ?
     * </pre>
     *
     * <p>⑨-C 起 retry 派生新任务会<b>复用</b>原图像的 {@code file_path}（磁盘文件不复制），
     * 于是同一物理文件可能被多行引用。{@code ImageService} 删行前用它判断"这个文件还有别的行在用吗"，
     * 只有 0 才允许物理删除 —— 否则删掉一个任务会让另一个仍在引用它的任务图像全部悬空。
     *
     * <p>零 DDL：只按既有列 {@code file_path} 做一次 COUNT。
     *
     * @param filePath       物理路径
     * @param excludeImageId 即将被删除的那一行（{@code null} = 不排除任何行）
     * @return 仍引用该路径的其它行数
     */
    default long countOtherRefs(String filePath, Long excludeImageId) {
        if (filePath == null || filePath.isBlank()) {
            return 0L;
        }
        QueryWrapper<RecognitionImage> wrapper = new QueryWrapper<>();
        wrapper.eq("file_path", filePath);
        if (excludeImageId != null) {
            wrapper.ne("id", excludeImageId);
        }
        Long refs = selectCount(wrapper);   // MP 3.5.7 起 selectCount 返回 Long
        return refs == null ? 0L : refs;
    }
}
