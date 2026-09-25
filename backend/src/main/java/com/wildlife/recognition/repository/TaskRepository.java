package com.wildlife.recognition.repository;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wildlife.recognition.entity.RecognitionTask;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TaskRepository extends BaseMapper<RecognitionTask> {

    /** 排队中：刚建好 / 重试后还没出过任何结果。 */
    String STATUS_PENDING = "PENDING";

    /** 进行中：已经出过结果、还没收尾。 */
    String STATUS_PROCESSING = "PROCESSING";

    /**
     * 允许被逐张结果推进计数的任务状态（非终态）。终态一律冻结，见 {@link #advanceCounters}。
     *
     * <p>同一个集合也是「任务还活着吗」的判据：{@link #cancelIfActive} / {@link #markFailedIfActive}
     * 只允许改非终态任务；{@code RedisSubscriberService} 的跨表任务守卫也按它拼子查询。
     * 口径只有这一处，改状态取值只需要改这里。
     */
    List<String> ADVANCE_ALLOWED_STATUS = List.of(STATUS_PENDING, STATUS_PROCESSING);

    /**
     * 原子推进任务计数 —— 把「读出来 → Java 加一 → 整行写回」换成一条条件 UPDATE。
     *
     * <p>改造前每次回写都是 <b>read-modify-write</b>：先 {@code selectById} 出整个实体，
     * 在 Java 侧把 {@code processed/success/failed/progress/status/start_time/finish_time} 算好，
     * 再 {@code updateById} 整行写回。真库抓到的那条 SQL 带 <b>9–10 列</b>，
     * 其中 {@code task_name / user_id / model_id / create_time} 与计数毫无关系；
     * 更麻烦的是 {@code WHERE} 只有 {@code id}，
     * 于是 {@code cancelTask}（HTTP 线程）与它（调度线程）会互相覆盖 —— CANCELED 被翻回 PROCESSING、
     * 或者并发的增量被陈旧快照踏掉。10 万张图就是 10 万次这样的整行写。
     *
     * <p>现在改写为单条 UPDATE，增量由数据库自己做，只写该写的 7 列：
     * <pre>
     * UPDATE recognition_task
     *    SET success_count   = success_count + ?,
     *        failed_count    = failed_count  + ?,
     *        progress        = CASE WHEN total_count &gt; 0
     *                               THEN LEAST(ROUND((processed_count + 1) * 100.0 / total_count, 2), 100.00)
     *                               ELSE 0.00 END,
     *        start_time      = COALESCE(start_time, NOW()),
     *        status          = CASE WHEN total_count &gt; 0 AND processed_count + 1 &gt;= total_count THEN 'COMPLETED'
     *                               WHEN status = 'PENDING' THEN 'PROCESSING'
     *                               ELSE status END,
     *        finish_time     = CASE WHEN total_count &gt; 0 AND processed_count + 1 &gt;= total_count
     *                               THEN COALESCE(finish_time, NOW()) ELSE finish_time END,
     *        processed_count = processed_count + 1
     *  WHERE id = ?
     *    AND status IN ('PENDING', 'PROCESSING')
     * </pre>
     *
     * <p><b>① {@code processed_count = processed_count + 1} 必须放在 SET 的最后</b>，
     * 这不是排版偏好。MySQL 单表 UPDATE 的列赋值<b>从左到右求值，且后面的赋值看得到前面刚写入的值</b>
     * （MySQL 手册 13.2.13：单表 UPDATE 的赋值一般按从左到右求值，后面的赋值引用前面已更新的列时用的是新值）。
     * 若把 {@code processed_count = processed_count + 1} 放在最前，那么后面三个
     * {@code processed_count + 1} 读到的就是"旧值 + 1"，再 +1 变成"旧值 + 2" —— 进度会整轮多算一格。
     * 已用真库 TEMPORARY TABLE 实测钉死（`docker/mysql` 8.0.46，探针脚本见
     * `.workbuddy/tmp/r9a_leftright_probe.sql`）：
     * <pre>
     * 同一行 total_count=3, processed_count=0, 第 1 张图落地
     *   processed_count 放最前 → progress = 66.67  ❌（应为 33.33）
     *   processed_count 放最后 → progress = 33.33  ✅
     * </pre>
     * 放在最后还有一种好处：无论 MySQL 用"左到右可见新值"还是 SQL 标准的"全部读旧值"语义，
     * 结果都相同（其余表达式的 RHS 读到的必然是本次更新前的值），是<b>语义无关</b>的写法。
     *
     * <p><b>② 状态转换与计数更新在同一条 UPDATE 里完成</b>，不拆成两条：
     * {@code PENDING → PROCESSING}（首次出结果开工）与 {@code PROCESSING → COMPLETED}（计数达标收尾）
     * 都由 {@code status} 的 CASE 派生，{@code COMPLETED} 分支同时补 {@code finish_time}。
     *
     * <p><b>③ {@code WHERE} 的状态守卫</b>是这次改动的核心收益：只有 {@code PENDING/PROCESSING} 的任务能被推进，
     * {@code COMPLETED / FAILED / CANCELED} 一律冻结（受影响行数 0），迟到的结果不会再改写终态任务。
     * ⑨-C 起 {@code retryTask} <b>不再把 FAILED/CANCELED 退回 PENDING</b>（它改为派生一个拥有新 taskId 的新任务），
     * 因此终态任务一旦落定就<b>永远</b>不会再被放行 —— 旧任务的迟到结果一律 {@code affected == 0}。
     * 这正是 retry 轮次隔离的机制来源：不靠轮次字段，靠「旧任务 id 恒为终态」。
     * （⑨-C 之前这里写的是「retryTask 会把 FAILED/CANCELED 退回 PENDING，退回后守卫照常放行」，
     * 那条路径已经不存在，注释随之更正；SQL 与签名一个字都没动。）
     *
     * <p><b>④ {@code progress} 口径</b>已与 Java 侧原 {@code round2(Math.min(...))} 逐值对过：
     * {@code total=6} 时 {@code 16.67 / 33.33 / 50.00 / 66.67 / 83.33 / 100.00}，
     * {@code total=0} 落在 {@code ELSE 0.00}（除零保护），
     * {@code LEAST(..., 100.00)} 保证超调时也不越 100；目标列 {@code progress DECIMAL(7,2) NOT NULL} 可安全落库。
     *
     * @param taskId       任务主键
     * @param successDelta 成功数增量（0 或 1）
     * @param failedDelta  失败数增量（0 或 1）
     * @return 受影响行数：1 = 本次推进成功；0 = 任务已是终态或不存在（调用方据此短路，不再推送"已完成"）
     */
    default int advanceCounters(Long taskId, int successDelta, int failedDelta) {
        UpdateWrapper<RecognitionTask> wrapper = new UpdateWrapper<>();
        wrapper.setSql("success_count = success_count + {0}", successDelta)
                .setSql("failed_count = failed_count + {0}", failedDelta)
                .setSql("progress = CASE WHEN total_count > 0"
                        + " THEN LEAST(ROUND((processed_count + 1) * 100.0 / total_count, 2), 100.00)"
                        + " ELSE 0.00 END")
                .setSql("start_time = COALESCE(start_time, NOW())")
                .setSql("status = CASE WHEN total_count > 0 AND processed_count + 1 >= total_count THEN 'COMPLETED'"
                        + " WHEN status = 'PENDING' THEN 'PROCESSING' ELSE status END")
                .setSql("finish_time = CASE WHEN total_count > 0 AND processed_count + 1 >= total_count"
                        + " THEN COALESCE(finish_time, NOW()) ELSE finish_time END")
                // ⚠️ 这一列必须留在 SET 的最后，理由见方法注释 ①（MySQL 左到右赋值语义）
                .setSql("processed_count = processed_count + 1")
                .eq("id", taskId)
                .in("status", ADVANCE_ALLOWED_STATUS);
        return update(null, wrapper);
    }

    // ── 任务生命周期闸门（⑨-B）：一次状态流转 = 一条只写必要列的条件 UPDATE ──────────
    //
    // 下面三个方法解决的是同一类缺陷：改状态时把「selectById 读出来的整行」写回去。
    // 真库抓到的整行写法带 ≥9 列，WHERE 只有 id，于是：
    //   · 与调度线程的 advanceCounters 并发时，Java 侧那份陈旧计数会把新计数踏掉（丢更新）；
    //   · 与彼此并发时，迟到的任务级消息能把刚 CANCELED 的任务翻回 PROCESSING / FAILED。
    // 关键区别在于**写权限**：取消 / 失败只拥有 status + finish_time，开工只拥有 status + start_time，
    // 计数列一律不碰 —— 陈旧值没有机会被写回。
    //
    // ⚠️ 三个方法的 WHERE 都是「状态守卫」，affected == 0 是**正常返回值**而不是异常：
    // 它表示"库里那个状态的转换已经由别人抢先完成了"，调用方据此短路，不再做后续副作用。

    /**
     * 取消任务 —— 只写 {@code status} + {@code finish_time} 的条件 UPDATE。
     *
     * <pre>
     * UPDATE recognition_task
     *    SET status = 'CANCELED',
     *        finish_time = ?
     *  WHERE id = ?
     *    AND status IN ('PENDING', 'PROCESSING')
     * </pre>
     *
     * <p>两个直接收益：
     * <ul>
     *   <li><b>消灭丢更新</b>：不再携带 {@code processed_count / success_count / failed_count / progress}，
     *       取消线程与调度线程并发时不可能把陈旧计数盖回去；</li>
     *   <li><b>天然幂等</b>：重复取消（或并发取消）第二次 {@code affected == 0}，
     *       已 CANCELED / COMPLETED / FAILED 的任务不会被二次覆盖（临时表探针 D2 实测）。</li>
     * </ul>
     *
     * @param taskId     任务主键
     * @param finishTime 结束时间，作为参数绑定（不写 {@code NOW()}）以保证返回给前端的快照与库值一字不差
     * @return 1 = 本次取消生效；0 = 任务已是终态或不存在
     */
    default int cancelIfActive(Long taskId, LocalDateTime finishTime) {
        UpdateWrapper<RecognitionTask> wrapper = new UpdateWrapper<>();
        wrapper.set("status", "CANCELED")
                .set("finish_time", finishTime)
                .eq("id", taskId)
                .in("status", ADVANCE_ALLOWED_STATUS);
        return update(null, wrapper);
    }

    /**
     * 任务级失败 —— 同样只写 {@code status} + {@code finish_time}，且带状态守卫。
     *
     * <pre>
     * UPDATE recognition_task
     *    SET status = 'FAILED',
     *        finish_time = ?
     *  WHERE id = ?
     *    AND status IN ('PENDING', 'PROCESSING')
     * </pre>
     *
     * <p>没有守卫时，一条迟到的任务级 FAILED 就能把用户刚取消的任务从 CANCELED 翻成 FAILED
     * （临时表探针 B1 复现，B2 证明带守卫后 {@code affected == 0}、状态保持 CANCELED）。
     * 它同样不写计数列，所以"整批失败"不会顺手把已经推进的计数抹掉。
     *
     * @return 1 = 本次失败生效；0 = 任务已是终态（含已取消）或不存在，调用方应跳过收尾与推送
     */
    default int markFailedIfActive(Long taskId, LocalDateTime finishTime) {
        UpdateWrapper<RecognitionTask> wrapper = new UpdateWrapper<>();
        wrapper.set("status", "FAILED")
                .set("finish_time", finishTime)
                .eq("id", taskId)
                .in("status", ADVANCE_ALLOWED_STATUS);
        return update(null, wrapper);
    }

    /**
     * 开工 —— {@code PENDING → PROCESSING} 的条件 UPDATE，只写 {@code status} + {@code start_time}。
     *
     * <pre>
     * UPDATE recognition_task
     *    SET status = 'PROCESSING',
     *        start_time = ?
     *  WHERE id = ?
     *    AND status = 'PENDING'
     * </pre>
     *
     * <p>守卫比 {@link #cancelIfActive} 更严（只认 PENDING），因为这就是一次状态转换本身。
     * 换成条件 UPDATE 而不是整行 {@code updateById}，是因为整行回写有两个真实后果：
     * <ul>
     *   <li>{@code selectById} 之后若用户取消成功，这条写会把 CANCELED <b>复活</b>成 PROCESSING ——
     *       而 {@link #advanceCounters} 的状态守卫正是按 PROCESSING 放行的，越权的计数推进被重新打开；</li>
     *   <li>任务级 STARTED 可能重复到达（大任务按 500 张拆多条消息，每条都带一次 STARTED），
     *       重试轮次里迟到的 STARTED 会把 Java 侧那份"读出来时还是 0"的计数与进度整行盖回去。</li>
     * </ul>
     *
     * @return 1 = 本次开工生效；0 = 任务已不是 PENDING（已开工 / 已取消 / 已结束）
     */
    default int markProcessingIfPending(Long taskId, LocalDateTime startTime) {
        UpdateWrapper<RecognitionTask> wrapper = new UpdateWrapper<>();
        wrapper.set("status", STATUS_PROCESSING)
                .set("start_time", startTime)
                .eq("id", taskId)
                .eq("status", STATUS_PENDING);
        return update(null, wrapper);
    }
}
