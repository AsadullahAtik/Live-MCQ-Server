package com.livemcq.service;

import com.livemcq.domain.AppUser;
import com.livemcq.web.ApiException;
import com.livemcq.web.AuthUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${app.jwt.secret}") String secret, @Value("${app.jwt.ttl-hours:12}") long hours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofHours(hours);
    }

    public String create(AppUser u) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(u.id))
                .claim("role", u.role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public AuthUser parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return new AuthUser(Long.parseLong(c.getSubject()), c.get("role", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(401, "সেশন শেষ হয়েছে, আবার লগইন করুন");
        }
    }
}
