package com.markagent.auth;

import com.markagent.config.MarkAgentProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

/**
 * JWT HS256 签发/校验（jjwt 0.12 API）。
 * secret 未配置（MARKAGENT_JWT_SECRET 为空）时启动期生成随机密钥：重启后旧 token 失效（单机自保安全语义）。
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final SecretKey key;
    private final long ttlMillis;

    public JwtTokenProvider(MarkAgentProperties props) {
        String secret = props.security().jwt().secret();
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            secret = Base64.getEncoder().encodeToString(random);
            log.warn("MARKAGENT_JWT_SECRET 未配置或过短（<32字节），已生成随机会话密钥（服务重启后已签发 token 全部失效）");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = props.security().jwt().ttlHours() * 3600_000L;
    }

    /** 签发：subject = userId */
    public String issue(String userId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key)
                .compact();
    }

    /** 校验并返回 userId；无效/过期返回 null */
    public String verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
