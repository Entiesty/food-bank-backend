package com.foodbank.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ServerEndpoint("/ws/sos/{userId}") // 🚨 这里的路径要和前端对应
public class WebSocketServer {

    // 存放全网所有在线的用户 Session
    private static final ConcurrentHashMap<Long, Session> sessionMap = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("userId") Long userId) {
        sessionMap.put(userId, session);
        log.info("[WebSocket] 用户[{}] 已连接 当前在线: {}", userId, sessionMap.size());
    }

    @OnClose
    public void onClose(@PathParam("userId") Long userId) {
        sessionMap.remove(userId);
        log.info("[WebSocket] 用户[{}] 已断开", userId);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("WebSocket 发生错误", error);
    }

    /**
     * 接收前端心跳 Ping，回复 Pong 防止防火墙/NAT 静默断连
     */
    @OnMessage
    public void onMessage(String message, Session session) {
        if (message != null && message.contains("\"type\":\"PING\"")) {
            try {
                session.getBasicRemote().sendText("{\"type\":\"PONG\"}");
            } catch (Exception e) {
                log.error("回复心跳失败", e);
            }
        }
    }

    // 🚀 核心大招：向指定求助者定向发射弹窗信号！
    public static void sendMessageToUser(Long userId, String message) {
        Session session = sessionMap.get(userId);
        if (session != null && session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
                log.info("[推送] 向用户[{}] 推送成功", userId);
            } catch (Exception e) {
                log.error("推送消息给用户 [{}] 失败", userId, e);
            }
        } else {
            log.warn("[推送] 用户[{}] 不在线，推送失败", userId);
        }
    }

    // 🚀 核心大招2：全网广播（通知指挥中心大屏）
    public static void broadcast(String message) {
        for (Session session : sessionMap.values()) {
            if (session != null && session.isOpen()) {
                try {
                    session.getBasicRemote().sendText(message);
                } catch (Exception e) {
                    log.error("全网广播消息失败", e);
                }
            }
        }
    }
}