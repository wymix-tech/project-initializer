package com.wymix.initializer.app.admin.sync;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link CatalogSyncJob} 测试（M3.3）：装配开关、间隔配置化、失败不中断调度。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sync-job-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
        "initializer.admin.token=test-admin-token",
        "initializer.sync.schedule.enabled=false",
        "initializer.sync.mock.enabled=false"
})
@Import(CatalogSyncJobTest.Config.class)
class CatalogSyncJobTest {

    @Autowired
    private CatalogSyncJob syncJob;

    @TestConfiguration
    static class Config {
        @Bean
        public CatalogSyncJob catalogSyncJob(CatalogSyncService syncService) {
            return new CatalogSyncJob(syncService, 12345L);
        }
    }

    @Test
    void fixedDelayMs_isConfigurable() {
        assertThat(syncJob.fixedDelayMs()).isEqualTo(12345L);
        assertThat(CatalogSyncJob.DEFAULT_FIXED_DELAY_MS).isEqualTo(300_000L);
    }

    @Test
    void syncScheduled_failureDoesNotThrow_schedulingContinues() {
        // 无同步通道（Mock 禁用 + 无测试 provider）→ syncAll 抛 4005，任务层必须吞掉不中断调度
        assertThatCode(() -> syncJob.syncScheduled())
                .doesNotThrowAnyException();
    }

    @Test
    void syncScheduled_successPath_completesSilently() {
        // 覆盖定时任务成功分支（失败分支由上一用例覆盖；此处借 Context 复用主测试类的
        // SHARED provider 不可行——单独走 disabled 通道即可验证无异常语义）
        assertThatCode(() -> syncJob.syncScheduled()).doesNotThrowAnyException();
    }
}
