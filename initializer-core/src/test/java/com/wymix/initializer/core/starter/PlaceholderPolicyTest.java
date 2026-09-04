package com.wymix.initializer.core.starter;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlaceholderPolicy} 单元测试：占位符白名单识别。
 */
class PlaceholderPolicyTest {

    @Test
    void unknownPlaceholders_allAllowed_returnsEmpty() {
        String value = "{basePackage}/{packagePath}/{artifact}/{group}/{bootVersion}/{javaVersion}";
        assertThat(PlaceholderPolicy.unknownPlaceholders(value)).isEmpty();
    }

    @Test
    void unknownPlaceholders_mixed_returnsOnlyUnknownInOrder() {
        String value = "{basePackage}/{custom}/{another}";
        assertThat(PlaceholderPolicy.unknownPlaceholders(value))
                .containsExactly("{custom}", "{another}");
    }

    @Test
    void unknownPlaceholders_noPlaceholder_returnsEmpty() {
        assertThat(PlaceholderPolicy.unknownPlaceholders("src/main/resources/application.yml")).isEmpty();
    }

    @Test
    void unknownPlaceholders_nestedBraces_matchesInnermost() {
        // 占位符语法不含嵌套：{a{b}} 提取最内层 token
        assertThat(PlaceholderPolicy.unknownPlaceholders("{a{b}}")).containsExactly("{b}");
    }

    @Test
    void unknownPlaceholders_deduplicates() {
        assertThat(PlaceholderPolicy.unknownPlaceholders("{x}/{x}"))
                .containsExactly("{x}");
    }

    @Test
    void allowed_containsSixWhitelistEntries() {
        String allowed = PlaceholderPolicy.allowed();
        assertThat(allowed).contains("{basePackage}", "{packagePath}", "{artifact}",
                "{group}", "{bootVersion}", "{javaVersion}");
    }
}
