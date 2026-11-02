package com.livemcq.live;

import com.livemcq.core.Scope;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** সংযোগ খোলা/বন্ধ হলে LiveHub-কে জানায়. ক্লায়েন্ট থেকে আসা বার্তা এখন শুধু "ping" এর জবাব. */
@Component
public class LiveSocketHandler extends TextWebSocketHandler {
    private final LiveHub hub;

    public LiveSocketHandler(LiveHub hub) {
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        boolean staff = Boolean.TRUE.equals(session.getAttributes().get("staff"));
        Scope scope = (Scope) session.getAttributes().get("scope");
        Long classId = (Long) session.getAttributes().get("classId");
        hub.add(session, staff, scope, classId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if ("ping".equals(message.getPayload())) session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        hub.remove(session.getId());
    }
}
