package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StarterCatalog} 单元测试：装载校验与查询。
 */
class StarterCatalogTest {

    private static final String BASE = "{basePackage}.config";

    private static StarterCatalogEntry entry(String id) {
        return new StarterCatalogEntry(id, "web", Maintainer.OFFICIAL, "desc",
                List.of(new DependencyCoord("g", id, null)),
                new LinkageSpec(BASE, List.of(), List.of(), List.of()));
    }

    @Test
    void of_registersAndQueriesById() {
        StarterCatalog catalog = StarterCatalog.of(List.of(entry("web"), entry("data-redis")));
        assertThat(catalog.size()).isEqualTo(2);
        assertThat(catalog.contains("web")).isTrue();
        assertThat(catalog.contains("ghost")).isFalse();
        assertThat(catalog.entry("data-redis").id()).isEqualTo("data-redis");
        assertThat(catalog.all()).extracting(StarterCatalogEntry::id)
                .containsExactly("web", "data-redis");
    }

    @Test
    void of_preservesHiddenFlagAndCompatConstructorDefaultsFalse() {
        StarterCatalogEntry hidden = new StarterCatalogEntry("ghost", "tool", Maintainer.INTERNAL, "隐藏条目",
                List.of(new DependencyCoord("g", "ghost", null)),
                new LinkageSpec(null, List.of(), List.of(), List.of()),
                false, List.of(), List.of(), true);
        StarterCatalog catalog = StarterCatalog.of(List.of(hidden, entry("web")));
        assertThat(catalog.entry("ghost").hidden()).isTrue();
        // 旧签名兼容构造默认不隐藏
        assertThat(catalog.entry("web").hidden()).isFalse();
    }

    @Test
    void of_duplicateId_throws9002() {
        assertThatThrownBy(() -> StarterCatalog.of(List.of(entry("web"), entry("web"))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID);
                    assertThat(e.getMessage()).contains("ID 重复");
                });
    }

    @Test
    void of_blankId_throws9002() {
        assertThatThrownBy(() -> StarterCatalog.of(List.of(entry(" "))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID));
    }

    @Test
    void of_missingDependencies_throws9002() {
        StarterCatalogEntry noDeps = new StarterCatalogEntry("web", "web", Maintainer.OFFICIAL, "desc",
                List.of(), new LinkageSpec(null, List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> StarterCatalog.of(List.of(noDeps)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("dependencies"));
    }

    @Test
    void of_dependencyMissingGroupId_throws9002() {
        StarterCatalogEntry badDep = new StarterCatalogEntry("web", "web", Maintainer.OFFICIAL, "desc",
                List.of(new DependencyCoord("", "artifact", null)),
                new LinkageSpec(null, List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> StarterCatalog.of(List.of(badDep)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("groupId"));
    }

    @Test
    void of_missingCategory_throws9002() {
        StarterCatalogEntry noCategory = new StarterCatalogEntry("web", null, Maintainer.OFFICIAL, "desc",
                List.of(new DependencyCoord("g", "a", null)),
                new LinkageSpec(null, List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> StarterCatalog.of(List.of(noCategory)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("category"));
    }

    @Test
    void of_missingMaintainer_throws9002() {
        StarterCatalogEntry noMaintainer = new StarterCatalogEntry("web", "web", null, "desc",
                List.of(new DependencyCoord("g", "a", null)),
                new LinkageSpec(null, List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> StarterCatalog.of(List.of(noMaintainer)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("maintainer"));
    }

    @Test
    void of_missingLinkages_throws9002() {
        StarterCatalogEntry noLinkages = new StarterCatalogEntry("web", "web", Maintainer.OFFICIAL, "desc",
                List.of(new DependencyCoord("g", "a", null)), null);
        assertThatThrownBy(() -> StarterCatalog.of(List.of(noLinkages)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("linkages"));
    }

    @Test
    void of_templateMissingSource_throws9002() {
        StarterCatalogEntry badTemplate = new StarterCatalogEntry("web", "web", Maintainer.OFFICIAL, "desc",
                List.of(new DependencyCoord("g", "a", null)),
                new LinkageSpec(null, List.of(new TemplateLinkage("", "target")), List.of(), List.of()));
        assertThatThrownBy(() -> StarterCatalog.of(List.of(badTemplate)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("source/target"));
    }

    @Test
    void of_templateTargetWithUnknownPlaceholder_throws9002() {
        StarterCatalogEntry badTarget = new StarterCatalogEntry("web", "web", Maintainer.OFFICIAL, "desc",
                List.of(new DependencyCoord("g", "a", null)),
                new LinkageSpec(null, List.of(new TemplateLinkage("a.ftl", "src/{customVar}/A.java")),
                        List.of(), List.of()));
        assertThatThrownBy(() -> StarterCatalog.of(List.of(badTarget)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID);
                    assertThat(e.getMessage()).contains("{customVar}");
                });
    }

    @Test
    void of_configPackageWithUnknownPlaceholder_throws9002() {
        StarterCatalogEntry badPkg = new StarterCatalogEntry("web", "web", Maintainer.OFFICIAL, "desc",
                List.of(new DependencyCoord("g", "a", null)),
                new LinkageSpec("{whatEver}.config", List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> StarterCatalog.of(List.of(badPkg)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("{whatEver}"));
    }

    @Test
    void entry_unknownId_throws9002() {
        StarterCatalog catalog = StarterCatalog.of(List.of(entry("web")));
        assertThatThrownBy(() -> catalog.entry("ghost"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.STARTER_CATALOG_INVALID));
    }

    @Test
    void all_isImmutable() {
        StarterCatalog catalog = StarterCatalog.of(List.of(entry("web")));
        assertThatThrownBy(() -> catalog.all().add(entry("x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
