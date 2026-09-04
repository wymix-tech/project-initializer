package com.wymix.initializer.app.admin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.api.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 管理端鉴权过滤器（M3.1）：拦截 {@code /api/admin/**}，校验 {@code X-Admin-Token} 请求头。
 *
 * <p>轻量实现（概要设计决策 4）：两角色 ADMIN / USER 首期仅落地 ADMIN（token 匹配内置
 * 管理员凭据），基于请求头的过滤器而非重型安全框架；业务接口（metadata/generate/validate）
 * 不受影响。token 比对使用常量时间比较，避免时序侧信道。
 *
 * <p>未授权响应：404 + 4004（不暴露管理端存在性，404 语义）。
 *
 * <p>线程安全性：无状态单例（token 摘要不可变），线程安全。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminAuthFilter extends OncePerRequestFilter {

    /** 管理端 token 请求头。 */
    public static final String TOKEN_HEADER = "X-Admin-Token";

    private final byte[] expectedToken;
    private final ObjectMapper objectMapper;

    /**
     * @param adminToken   内置管理员 token（明文配置，启动期完成摘要；生产可外置到环境变量）
     * @param objectMapper JSON 序列化（错误响应体输出）
     */
    public AdminAuthFilter(@Value("${initializer.admin.token:admin-dev-token}") String adminToken,
                           ObjectMapper objectMapper) {
        this.expectedToken = sha256(adminToken);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅拦截管理端路径；业务接口零感知
        return !request.getRequestURI().startsWith("/api/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader(TOKEN_HEADER);
        if (token == null || !MessageDigest.isEqual(expectedToken, sha256(token))) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    ErrorResponse.of(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage()));
            return;
        }
        chain.doFilter(request, response);
    }

    /** SHA-256 摘要（HexFormat 小写十六进制）。 */
    private static byte[] sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
                    .getBytes(StandardCharsets.US_ASCII);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
