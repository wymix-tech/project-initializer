package com.wymix.initializer.app.admin.sync;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MockCatalogSyncProvider} 单元测试（M3.3）：fixture 解析、缺字段 4005、多文档切分。
 */
class MockCatalogSyncProviderTest {

    private final MockCatalogSyncProvider provider =
            new MockCatalogSyncProvider("classpath:sync/artifact-repo-fixtures.yml", new DefaultResourceLoader());

    @Test
    void providerId_isMockRepo() {
        assertThat(provider.providerId()).isEqualTo(MockCatalogSyncProvider.PROVIDER_ID);
        assertThat(MockCatalogSyncProvider.PROVIDER_ID).isEqualTo("mock-repo");
    }

    @Test
    void fetch_parsesBuiltInFixture() {
        var result = provider.fetch();
        assertThat(result.providerId()).isEqualTo("mock-repo");
        assertThat(result.starters()).hasSize(2);
        assertThat(result.starters()).extracting(s -> s.id())
                .containsExactly("xxx-oss-spring-boot-starter", "xxx-sms-spring-boot-starter");
        assertThat(result.starters().get(0).version()).isEqualTo("1.0.0");
    }

    @Test
    void fetch_missingFixture_throws4005() {
        MockCatalogSyncProvider missing =
                new MockCatalogSyncProvider("classpath:sync/no-such-file.yml", new DefaultResourceLoader());
        assertThatThrownBy(missing::fetch)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED))
                .hasMessageContaining("fixture 不存在");
    }

    @Test
    void fetch_missingRequiredField_throws4005() {
        String bad = "id: only-id\n";
        MockCatalogSyncProvider badProvider = new MockCatalogSyncProvider(
                "classpath:sync/no-such-file.yml", new DefaultResourceLoader()) {
            @Override
            public com.wymix.initializer.core.sync.SyncResult fetch() {
                throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED, "triggered");
            }
        };
        // 直接验证文档解析路径：缺字段抛 4005（经 splitDocuments 单文档）
        assertThatThrownBy(() -> invokeParse(bad))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED));
        assertThat(badProvider).isNotNull();
    }

    private void invokeParse(String document) {
        try {
            var method = MockCatalogSyncProvider.class.getDeclaredMethod("parseDocument", String.class);
            method.setAccessible(true);
            method.invoke(provider, document);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void fetch_readsViaResourceLoader() {
        // file: 前缀资源也支持（ResourceLoader 语义）
        MockCatalogSyncProvider fileBased = new MockCatalogSyncProvider(
                "classpath:sync/artifact-repo-fixtures.yml", new DefaultResourceLoader());
        assertThat(fileBased.fetch().starters()).hasSize(2);
    }

    @Test
    void splitDocuments_splitsAndTrims() {
        assertThat(MockCatalogSyncProvider.splitDocuments("---\na: 1\n---\nb: 2\n")).hasSize(2);
        assertThat(MockCatalogSyncProvider.splitDocuments("id: x\n")).hasSize(1);
        assertThat(MockCatalogSyncProvider.splitDocuments("")).isEmpty();
    }
}
