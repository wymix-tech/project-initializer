package com.wymix.initializer.app.admin.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AdminTokenWarningRunner} 单元测试：默认 token 在非生产环境仅告警不阻断；
 * 生产 profile 下使用默认 token 阻断启动；已覆盖 token 一律放行。
 */
class AdminTokenWarningRunnerTest {

    private static StandardEnvironment environment(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        if (profiles.length > 0) {
            environment.setActiveProfiles(profiles);
        }
        return environment;
    }

    @Test
    void defaultTokenConstant_matchesApplicationYaml() {
        assertThat(AdminTokenWarningRunner.DEFAULT_DEV_TOKEN).isEqualTo("admin-dev-token");
    }

    @Test
    void runner_withDefaultToken_doesNotThrow() throws Exception {
        ApplicationRunner runner = new AdminTokenWarningRunner()
                .adminTokenSecurityChecker(AdminTokenWarningRunner.DEFAULT_DEV_TOKEN, environment("dev"));
        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }

    @Test
    void runner_withOverriddenToken_doesNotThrow() throws Exception {
        ApplicationRunner runner = new AdminTokenWarningRunner()
                .adminTokenSecurityChecker("prod-secret-token", environment());
        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }

    @Test
    void runner_withDefaultTokenUnderProdProfile_failsFast() {
        ApplicationRunner runner = new AdminTokenWarningRunner()
                .adminTokenSecurityChecker(AdminTokenWarningRunner.DEFAULT_DEV_TOKEN, environment("prod"));
        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INITIALIZER_ADMIN_TOKEN");
    }

    @Test
    void runner_withOverriddenTokenUnderProdProfile_doesNotThrow() throws Exception {
        ApplicationRunner runner = new AdminTokenWarningRunner()
                .adminTokenSecurityChecker("prod-secret-token", environment("prod"));
        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }

    @Test
    void isProduction_recognizesProductionProfiles() {
        assertThat(AdminTokenWarningRunner.isProduction(environment("PROD"))).isTrue();
        assertThat(AdminTokenWarningRunner.isProduction(environment("production"))).isTrue();
        assertThat(AdminTokenWarningRunner.isProduction(environment("prd"))).isTrue();
        assertThat(AdminTokenWarningRunner.isProduction(environment("dev"))).isFalse();
        assertThat(AdminTokenWarningRunner.isProduction(environment())).isFalse();
    }
}
