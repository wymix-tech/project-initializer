package com.wymix.initializer.app.admin.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link AdminTokenWarningRunner} 单元测试：默认 token 与覆盖 token 均不阻断启动，
 * 常量与 application.yml 默认值一致。
 */
class AdminTokenWarningRunnerTest {

    @Test
    void defaultTokenConstant_matchesApplicationYaml() {
        assertThat(AdminTokenWarningRunner.DEFAULT_DEV_TOKEN).isEqualTo("admin-dev-token");
    }

    @Test
    void runner_withDefaultToken_doesNotThrow() throws Exception {
        ApplicationRunner runner = new AdminTokenWarningRunner()
                .adminTokenSecurityChecker(AdminTokenWarningRunner.DEFAULT_DEV_TOKEN);
        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }

    @Test
    void runner_withOverriddenToken_doesNotThrow() throws Exception {
        ApplicationRunner runner = new AdminTokenWarningRunner()
                .adminTokenSecurityChecker("prod-secret-token");
        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }
}
