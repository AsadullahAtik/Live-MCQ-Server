package com.livemcq.live;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** ws://localhost:8080/ws?token=... */
@Configuration
@EnableWebSocket
public class WsConfig implements WebSocketConfigurer {
    private final LiveSocketHandler handler;
    private final JwtHandshakeInterceptor interceptor;

    public WsConfig(LiveSocketHandler handler, JwtHandshakeInterceptor interceptor) {
        this.handler = handler;
        this.interceptor = interceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // ডেভেলপমেন্টের জন্য খোলা; আসল ব্যবহারে নির্দিষ্ট origin দিন
        registry.addHandler(handler, "/ws").addInterceptors(interceptor).setAllowedOriginPatterns("*");
    }
}
