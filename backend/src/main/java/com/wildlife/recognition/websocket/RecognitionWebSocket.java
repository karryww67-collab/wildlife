package com.wildlife.recognition.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 识别流程实时推送。
 *
 * 前端（识别监控大屏、任务详情页、复核工作台）通过 <code>/ws/recognition</code> 建立长连接，
 * 服务端在三个节点主动推送：
 * <ol>
 *   <li><b>任务进度</b> —— 每处理完一张图像推一次</li>
 *   <li><b>识别完成</b> —— 一个任务的最后一张图像处理完毕</li>
 *   <li><b>审核状态</b> —— 人工复核提交后</li>
 * </ol>
 *
 * 消息格式：
 * <pre>
 * 任务进度 / 识别完成：
 * {"taskId":10001,"processedCount":38291,"totalCount":100000,"progress":38.29,"status":"PROCESSING"}
 *
 * 审核状态：
 * {"type":"REVIEW","resultId":123,"imageId":45,"className":"野猪","correctedClass":"小麂",
 *  "reviewStatus":"CORRECTED","reviewer":"admin","reviewTime":"2026-09-20 21:56:00"}
 * </pre>
 */
@ServerEndpoint("/ws/recognition")
@Component
@Slf4j
public class RecognitionWebSocket {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final CopyOnWriteArraySet<RecognitionWebSocket> WEB_SOCKET_SET = new CopyOnWriteArraySet<>();

    private static int onlineCount = 0;

    private Session session;

    // ── 连接生命周期 ────────────────────────────────────────────────────────

    @OnOpen
    public void onOpen(Session session) {
        this.session = session;
        WEB_SOCKET_SET.add(this);
        addOnlineCount();
        log.info("识别推送连接加入，当前在线数：{}", getOnlineCount());
    }

    @OnClose
    public void onClose() {
        WEB_SOCKET_SET.remove(this);
        subOnlineCount();
        log.info("识别推送连接关闭，当前在线数：{}", getOnlineCount());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        log.debug("收到客户端消息: {}", message);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("识别推送 WebSocket 发生错误", error);
    }

    // ── 三类业务推送 ────────────────────────────────────────────────────────

    /**
     * 任务进度：每处理完一张图像推送一次。
     *
     * @param taskId         任务 ID
     * @param processedCount 已处理图像数
     * @param totalCount     任务图像总数
     * @param progress       进度百分比（0-100）
     * @param status         任务状态 PENDING / PROCESSING / COMPLETED / FAILED / CANCELED
     */
    public static void sendTaskProgress(long taskId, int processedCount, int totalCount,
                                       double progress, String status) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("taskId", taskId);
            node.put("processedCount", processedCount);
            node.put("totalCount", totalCount);
            node.put("progress", progress);
            node.put("status", status);
            broadcast(MAPPER.writeValueAsString(node));
        } catch (Exception e) {
            log.warn("任务 {} 进度推送失败", taskId, e);
        }
    }

    /** 识别完成：一个任务的最后一张图像处理完毕。 */
    public static void sendTaskCompleted(long taskId, int processedCount, int totalCount) {
        sendTaskProgress(taskId, processedCount, totalCount, 100.0, "COMPLETED");
    }

    /**
     * 审核状态：人工复核提交后。
     *
     * @param resultId       识别结果 ID
     * @param imageId        所属图像 ID
     * @param className      原识别物种
     * @param correctedClass 修正后的物种（未修正时与原识别一致）
     * @param reviewStatus   复核状态 CONFIRMED / CORRECTED / REJECTED
     * @param reviewer       复核人账号
     */
    public static void sendReviewStatus(long resultId, long imageId, String className,
                                       String correctedClass, String reviewStatus, String reviewer) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("type", "REVIEW");
            node.put("resultId", resultId);
            node.put("imageId", imageId);
            node.put("className", className == null ? "" : className);
            node.put("correctedClass", correctedClass == null ? "" : correctedClass);
            node.put("reviewStatus", reviewStatus == null ? "" : reviewStatus);
            node.put("reviewer", reviewer == null ? "" : reviewer);
            node.put("reviewTime", LocalDateTime.now().format(TIME_FORMAT));
            broadcast(MAPPER.writeValueAsString(node));
        } catch (Exception e) {
            log.warn("结果 {} 复核状态推送失败", resultId, e);
        }
    }

    // ── 通用广播 ────────────────────────────────────────────────────────────

    /** 群发任意对象（序列化后广播），供临时调试或未来扩展使用。 */
    public static void sendInfo(Object messageObject) {
        try {
            broadcast(MAPPER.writeValueAsString(messageObject));
        } catch (Exception e) {
            log.error("消息序列化失败", e);
        }
    }

    /** 已建立的连接数。 */
    public static int getOnlineCount() {
        return onlineCount;
    }

    private static void broadcast(String message) {
        for (RecognitionWebSocket item : WEB_SOCKET_SET) {
            try {
                item.sendMessage(message);
            } catch (IOException e) {
                // 连接已失效，摘掉即可，不打断其余推送
                WEB_SOCKET_SET.remove(item);
                subOnlineCount();
            }
        }
    }

    private void sendMessage(String message) throws IOException {
        if (session != null && session.isOpen()) {
            synchronized (session) {
                session.getBasicRemote().sendText(message);
            }
        }
    }

    private static synchronized void addOnlineCount() {
        onlineCount++;
    }

    private static synchronized void subOnlineCount() {
        if (onlineCount > 0) {
            onlineCount--;
        }
    }
}
