package com.foodbank.module.common.controller.websocket;

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
        log.info("📡 用户 [{}] 已接入调度通讯网络，当前全网在线人数: {}", userId, sessionMap.size());
    }

    @OnClose
    public void onClose(@PathParam("userId") Long userId) {
        sessionMap.remove(userId);
        log.info("📡 用户 [{}] 离开了通讯网络", userId);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("WebSocket 发生错误", error);
    }

    // 🚀 核心大招：向指定求助者定向发射弹窗信号！
    public static void sendMessageToUser(Long userId, String message) {
        Session session = sessionMap.get(userId);
        if (session != null && session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
                log.info("✅ 成功向用户 [{}] 推送紧急响应弹窗", userId);
            } catch (Exception e) {
                log.error("推送消息给用户 [{}] 失败", userId, e);
            }
        } else {
            log.warn("⚠️ 用户 [{}] 当前不在线，弹窗信号未能送达", userId);
        }
    }
}