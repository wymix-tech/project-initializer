package com.wymix.initializer.core.starter;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void replace_shouldReplaceAllWhitelistPlaceholders() {
        String result = PlaceholderPolicy.replace("{basePackage}/{packagePath}/{artifact}/{group}",
                java.util.Map.of("basePackage", "com.example", "packagePath", "com/example",
                        "artifact", "demo-app", "group", "com.example"));
        assertThat(result).isEqualTo("com.example/com/example/demo-app/com.example");
    }

    @Test
    void replace_missingValueShouldThrow() {
        // 评审修复：白名单占位符缺取值是配置错误，立即抛异常而非原样保留（避免生成带 {name} 的路径）
        assertThatThrownBy(() -> PlaceholderPolicy.replace("src/main/java/{packagePath}/{bootVersion}/A.java",
                java.util.Map.of("packagePath", "com/example")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{bootVersion}").hasMessageContaining("缺少取值");
    }

    @Test
    void replace_noPlaceholder_shouldReturnAsIs() {
        assertThat(PlaceholderPolicy.replace("src/main/resources/application.yml", java.util.Map.of()))
                .isEqualTo("src/main/resources/application.yml");
    }

    @Test
    void replace_unknownPlaceholderShouldThrow() {
        assertThatThrownBy(() -> PlaceholderPolicy.replace("{custom}/x", java.util.Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越界占位符");
    }

    @Test
    void allowed_containsSixWhitelistEntries() {
        String allowed = PlaceholderPolicy.allowed();
        assertThat(allowed).contains("{basePackage}", "{packagePath}", "{artifact}",
                "{group}", "{bootVersion}", "{javaVersion}");
    }
}
