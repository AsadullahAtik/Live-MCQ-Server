package com.livemcq.live;

import com.livemcq.domain.AppUser;
import com.livemcq.core.Scope;
import com.livemcq.repo.UserRepo;
import com.livemcq.service.AccessService;
import com.livemcq.service.JwtService;
import com.livemcq.web.ApiException;
import com.livemcq.web.AuthUser;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

/** WebSocket সংযোগের আগেই JWT যাচাই: header "Authorization: Bearer ..." অথবা ?token=... */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {
    private final JwtService jwt;
    private final UserRepo users;
    private final AccessService access;

    public JwtHandshakeInterceptor(JwtService jwt, UserRepo users, AccessService access) {
        this.access = access;
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Map<String, Object> attrs) {
        String token = null;
        String header = req.getHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) token = header.substring(7);
        if (token == null) token = UriComponentsBuilder.fromUri(req.getURI()).build().getQueryParams().getFirst("token");
        if (token == null) {
            res.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        try {
            AuthUser me = jwt.parse(token);
            AppUser u = users.findById(me.id()).orElse(null);
            if (u == null) {
                res.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            attrs.put("staff", me.isStaff());
            // অ্যাডমিন: সব শ্রেণি; শিক্ষক: নির্ধারিত শ্রেণি (সংযোগের সময়ের হিসাব; বদলালে আবার সংযোগ লাগে)
            attrs.put("scope", me.isStaff() ? access.scope(me) : Scope.of(java.util.Set.of()));
            attrs.put("classId", u.classId);
            return true;
        } catch (ApiException e) {
            res.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Exception ex) {
        // কিছু করার নেই
    }
}
