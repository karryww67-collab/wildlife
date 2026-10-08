package com.wildlife.recognition.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wildlife.recognition.entity.DetectionResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface DetectionResultRepository extends BaseMapper<DetectionResult> {

    /**
     * 真正的批量插入：一条 SQL 带多行 VALUES。
     *
     * <pre>
     * INSERT INTO detection_result (image_id, model_id, ..., create_time)
     * VALUES (?, ?, ..., ?), (?, ?, ..., ?), ...
     * </pre>
     *
     * 与 BaseMapper#{@code insert} 的区别：{@code insert} 每行一条 SQL，
     * 本方法把整个 list 拼成一条 SQL，N 行结果 = 1 次数据库往返。
     *
     * 语句由 MyBatis-Plus 官方的
     * {@code com.baomidou.mybatisplus.extension.injector.methods.InsertBatchSomeColumn}
     * 注入（见 {@code MybatisPlusConfig#sqlInjector()}），
     * 因此方法名必须与它写死的 methodName 完全一致，否则启动即报
     * Invalid bound statement。列清单由实体注解推导，不需要手写列名。
     *
     * @param list 待插入的结果行（非空集合，且每行 imageId 已填好）
     * @return 实际插入的行数
     */
    int insertBatchSomeColumn(@Param("list") List<DetectionResult> list);

    // ── 检出率（模块⑤统计报表） ────────────────────────────────────────────────

    /**
     * 有至少一个检出结果的图像数 —— 检出率的<b>分子</b>。
     *
     * <pre>
     * SELECT COUNT(DISTINCT d.image_id)
     *   FROM detection_result d
     *   JOIN recognition_image i ON i.id = d.image_id
     *  WHERE i.status = 'SUCCESS'
     *    [AND i.create_time &gt;= ?] [AND i.create_time &lt;= ?]
     * </pre>
     *
     * <p>三个要点：
     * <ul>
     *   <li><b>DISTINCT 不能省</b> —— 一张图常检出多个目标（本批次 406 张图共 803 个框），
     *       不 DISTINCT 就把"目标数"当成了"图像数"，检出率会超过 100%；</li>
     *   <li><b>JOIN recognition_image 是为了口径一致</b> —— 只统计 SUCCESS 图像，
     *       与分母 {@code ImageRepository#countSuccessImages} 用同一个 WHERE，
     *       否则分子可能落在分母之外；</li>
     *   <li><b>时间过滤走 i.create_time 而非 d.create_time</b> —— 结果是在识别完成后写入的，
     *       与图像上传时间可能跨天。按结果自己的时间戳分桶，会把某天的图算到另一天，
     *       导致单日检出率出现没有意义的 &gt;100% 或除零。</li>
     * </ul>
     *
     * @param start 起始时间（含），{@code null} = 不限
     * @param end   结束时间（含），{@code null} = 不限
     * @return 有检出的图像数
     */
    @Select("""
            <script>
            SELECT COUNT(DISTINCT d.image_id)
              FROM detection_result d
              JOIN recognition_image i ON i.id = d.image_id
             WHERE i.status = 'SUCCESS'
            <if test="start != null"> AND i.create_time &gt;= #{start}</if>
            <if test="end != null">   AND i.create_time &lt;= #{end}</if>
            </script>
            """)
    Long countDetectedImages(@Param("start") LocalDateTime start,
                             @Param("end") LocalDateTime end);

    /**
     * 按天 / 小时分桶的有检出图像数，供「检出率趋势」提供分子序列。
     *
     * <pre>
     * SELECT &lt;分桶表达式&gt; AS time_slot, COUNT(DISTINCT d.image_id) AS cnt
     *   FROM detection_result d
     *   JOIN recognition_image i ON i.id = d.image_id
     *  WHERE i.status = 'SUCCESS'
     *  GROUP BY time_slot ORDER BY time_slot
     * </pre>
     *
     * <p>分桶表达式由 {@code hourly} 在 SQL 内部二选一（MyBatis {@code <choose>}，
     * <b>不是 Java 字符串拼接</b>，因此没有注入面）。它必须与
     * {@code ImageRepository#countSuccessImagesByBucket} 用完全相同的表达式，
     * 否则两条序列的 key 对不上，趋势图会画成两条互不相交的折线。
     *
     * @param hourly true = 按小时（{@code yyyy-MM-dd HH:00}）；false = 按天（{@code yyyy-MM-dd}）
     * @param start  起始时间（含），{@code null} = 不限
     * @param end    结束时间（含），{@code null} = 不限
     * @return 每行 {@code {time_slot=时间串, cnt=图像数}}，按时间升序
     */
    @Select("""
            <script>
            SELECT
            <choose>
              <when test="hourly">DATE_FORMAT(i.create_time, '%Y-%m-%d %H:00')</when>
              <otherwise>DATE(i.create_time)</otherwise>
            </choose>
            AS time_slot, COUNT(DISTINCT d.image_id) AS cnt
              FROM detection_result d
              JOIN recognition_image i ON i.id = d.image_id
             WHERE i.status = 'SUCCESS'
            <if test="start != null"> AND i.create_time &gt;= #{start}</if>
            <if test="end != null">   AND i.create_time &lt;= #{end}</if>
             GROUP BY time_slot
             ORDER BY time_slot
            </script>
            """)
    List<Map<String, Object>> countDetectedImagesByBucket(@Param("hourly") boolean hourly,
                                                          @Param("start") LocalDateTime start,
                                                          @Param("end") LocalDateTime end);
}