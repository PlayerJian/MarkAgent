package com.markagent.config;

import com.markagent.auth.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Boot 4 / Security 7 配置：JWT 无状态认证。
 * 放行：/api/v1/auth/**、/api/v1/status、/error；其余需 Bearer token。
 * SSE 用 fetch-event-source 消费（可带 Authorization 头，无需放行）。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 未认证一律 401（前端 client 按 401 跳登录），与 ApiError 信封一致
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"未登录或 token 已过期\"}");
                }))
                .authorizeHttpRequests(auth -> auth
                        // SSE 流完成/超时（emitter.complete 由 agent 线程触发）会走 ASYNC 再分发，
                        // 该阶段 Security 7 的 AuthorizationFilter 默认仍会执行授权检查，而安全上下文
                        // 未被恢复（匿名）→ authenticated() 拒绝 → 响应已提交时异常无法兜底。
                        // 异步续跑是同一逻辑请求的延续（授权已在 REQUEST 阶段完成），放行是标准做法。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        // 页面壳与静态资源放行（数据仍由 /api/** 的 JWT 认证门控）
                        .requestMatchers("/", "/index.html", "/favicon.svg", "/icons.svg",
                                "/pwa-*.png", "/manifest.webmanifest", "/sw.js", "/workbox-*.js",
                                "/assets/**", "/login", "/tasks", "/tasks/*", "/memory", "/skills",
                                "/mcp", "/docs", "/settings",
                                // 登录/注册放行；/auth/me 需认证（前端启动校验 token 用，
                                // 无效 token → 401 → 前端自动跳登录）
                                "/api/v1/auth/login", "/api/v1/auth/register",
                                "/api/v1/status", "/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
