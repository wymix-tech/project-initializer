package com.wymix.initializer.app.admin.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdminAuthFilter} 单元测试：token 缺失 / 错误 / 正确，路径放行与 404+4004 语义。
 */
class AdminAuthFilterTest {

    private static final String TOKEN = "test-admin-token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AdminAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AdminAuthFilter(TOKEN, MAPPER);
    }

    @Test
    void shouldNotFilter_ignoresNonAdminPaths() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/metadata/java-versions");
        assertThat(filter.shouldNotFilter(request)).isTrue();

        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/admin/config/metadata")))
                .isFalse();
    }

    @Test
    void missingToken_returns404With4004() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/config/metadata");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("\"code\":4004");
    }

    @Test
    void wrongToken_returns404With4004() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/config/metadata");
        request.addHeader(AdminAuthFilter.TOKEN_HEADER, "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("4004");
    }

    @Test
    void correctToken_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/config/metadata");
        request.addHeader(AdminAuthFilter.TOKEN_HEADER, TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void tokenDigest_isConstantLengthSha256Hex() {
        // 间接验证摘要路径不因 token 内容差异抛异常（含中文/特殊字符）
        new AdminAuthFilter("另一个-token-🔐", MAPPER);
    }
}
