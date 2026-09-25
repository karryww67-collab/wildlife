package com.wildlife.recognition.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * WebSocket 配置。
 *
 * 识别流程的实时推送由 {@link com.wildlife.recognition.websocket.RecognitionWebSocket}
 * 以 JSR-356 端点（{@code @ServerEndpoint("/ws/recognition")}）的形式提供，
 * 由下文的 ServerEndpointExporter 负责注册，无需往 WebSocketHandlerRegistry 里挂。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig {

    /**
     * 注入ServerEndpointExporter，
     * 只有当使用 Spring Boot 且内置 Tomcat 的时候才需要此Bean
     */
    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}
