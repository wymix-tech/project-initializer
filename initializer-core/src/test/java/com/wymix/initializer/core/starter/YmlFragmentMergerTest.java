package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * YmlFragmentMerger 合并矩阵单元测试（M2.2）：
 * 树递归合并 / 数组 append 与 override / 冲突显式报错 / 同值跳过 / 类型不一致冲突。
 */
class YmlFragmentMergerTest {

    private static YmlFragmentMerger.Fragment fragment(String yml,
            YmlFragmentLinkage.MergeStrategy strategy, String source) {
        return YmlFragmentMerger.Fragment.parse(
                new ByteArrayInputStream(yml.getBytes(StandardCharsets.UTF_8)), source, strategy);
    }

    @Test
    void disjointMapsShouldMergeRecursively() {
        Map<String, Object> result = YmlFragmentMerger.merge(null, List.of(
                fragment("spring:\n  redis:\n    host: h1\n", YmlFragmentLinkage.MergeStrategy.APPEND, "a.yml"),
                fragment("server:\n  port: 8080\n", YmlFragmentLinkage.MergeStrategy.APPEND, "b.yml")));
        assertThat(result.get("server")).isEqualTo(Map.of("port", 8080));
        assertThat(((Map<?, ?>) result.get("spring")).get("redis")).isNotNull();
    }

    @Test
    void nestedMapsShouldMergeNotReplace() {
        Map<String, Object> result = YmlFragmentMerger.merge(null, List.of(
                fragment("spring:\n  redis:\n    host: h1\n", YmlFragmentLinkage.MergeStrategy.APPEND, "a.yml"),
                fragment("spring:\n  redis:\n    port: 6379\n", YmlFragmentLinkage.MergeStrategy.APPEND, "b.yml")));
        Map<?, ?> spring = (Map<?, ?>) result.get("spring");
        Map<?, ?> redis = (Map<?, ?>) spring.get("redis");
        assertThat(redis.get("host")).isEqualTo("h1");
        assertThat(redis.get("port")).isEqualTo(6379);
    }

    @Test
    void sameScalarValueShouldSkip() {
        Map<String, Object> result = YmlFragmentMerger.merge(null, List.of(
                fragment("spring:\n  redis:\n    host: h1\n", YmlFragmentLinkage.MergeStrategy.APPEND, "a.yml"),
                fragment("spring:\n  redis:\n    host: h1\n", YmlFragmentLinkage.MergeStrategy.APPEND, "b.yml")));
        Map<?, ?> redis = (Map<?, ?>) ((Map<?, ?>) result.get("spring")).get("redis");
        assertThat(redis.get("host")).isEqualTo("h1");
    }

    @Test
    void scalarConflictShouldThrow3004WithDetail() {
        assertThatThrownBy(() -> YmlFragmentMerger.merge(null, List.of(
                fragment("spring:\n  redis:\n    host: h1\n", YmlFragmentLinkage.MergeStrategy.APPEND, "a.yml"),
                fragment("spring:\n  redis:\n    host: h2\n", YmlFragmentLinkage.MergeStrategy.APPEND, "b.yml"))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.YML_MERGE_CONFLICT);
                    assertThat(e.getMessage()).contains("spring.redis.host").contains("h1").contains("h2");
                });
    }

    @Test
    void listAppendShouldDeduplicate() {
        Map<String, Object> result = YmlFragmentMerger.merge(null, List.of(
                fragment("include: [a, b]\n", YmlFragmentLinkage.MergeStrategy.APPEND, "a.yml"),
                fragment("include: [b, c]\n", YmlFragmentLinkage.MergeStrategy.APPEND, "b.yml")));
        assertThat(result.get("include")).isEqualTo(List.of("a", "b", "c"));
    }

    @Test
    void listOverrideShouldReplaceWhole() {
        Map<String, Object> result = YmlFragmentMerger.merge(null, List.of(
                fragment("include: [a, b]\n", YmlFragmentLinkage.MergeStrategy.APPEND, "a.yml"),
                fragment("include: [x]\n", YmlFragmentLinkage.MergeStrategy.OVERRIDE, "b.yml")));
        assertThat(result.get("include")).isEqualTo(List.of("x"));
    }

    @Test
    void typeMismatchShouldConflict() {
        assertThatThrownBy(() -> YmlFragmentMerger.merge(null, List.of(
                fragment("key:\n  a: 1\n", YmlFragmentLinkage.MergeStrategy.APPEND, "a.yml"),
                fragment("key: scalar\n", YmlFragmentLinkage.MergeStrategy.APPEND, "b.yml"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.YML_MERGE_CONFLICT));
    }

    @Test
    void emptyFragmentAndNullBaseShouldBeTolerated() {
        Map<String, Object> result = YmlFragmentMerger.merge(null, List.of(
                fragment("", YmlFragmentLinkage.MergeStrategy.APPEND, "empty.yml")));
        assertThat(result).isEmpty();
    }

    @Test
    void baseShouldNotBeMutated() {
        Map<String, Object> base = YamlTestSupport.mapOf("spring", YamlTestSupport.mapOf("redis", "x"));
        YmlFragmentMerger.merge(base, List.of(
                fragment("spring:\n  other: 1\n", YmlFragmentLinkage.MergeStrategy.APPEND, "a.yml")));
        assertThat(base.get("spring")).isEqualTo(YamlTestSupport.mapOf("redis", "x"));
    }

    @Test
    void multipleConflictsShouldListAll() {
        assertThatThrownBy(() -> YmlFragmentMerger.merge(null, List.of(
                fragment("a: 1\nb: 2\n", YmlFragmentLinkage.MergeStrategy.APPEND, "a.yml"),
                fragment("a: 9\nb: 8\n", YmlFragmentLinkage.MergeStrategy.APPEND, "b.yml"))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getMessage()).contains("2 处").contains("a").contains("b");
                });
    }

    @Test
    void toYamlShouldRoundTrip() {
        Map<String, Object> merged = YmlFragmentMerger.merge(null, List.of(
                fragment("spring:\n  redis:\n    host: h1\n    port: 6379\n",
                        YmlFragmentLinkage.MergeStrategy.APPEND, "a.yml")));
        String yaml = YmlFragmentMerger.toYaml(merged);
        Map<String, Object> reparsed = YmlFragmentMerger.Fragment.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "roundtrip",
                YmlFragmentLinkage.MergeStrategy.APPEND).parsed();
        Map<?, ?> redis = (Map<?, ?>) ((Map<?, ?>) reparsed.get("spring")).get("redis");
        assertThat(redis.get("host")).isEqualTo("h1");
        assertThat(redis.get("port")).isEqualTo(6379);
    }

    @Test
    void invalidFragmentYamlShouldThrow3004() {
        assertThatThrownBy(() -> fragment("key: [unclosed", YmlFragmentLinkage.MergeStrategy.APPEND, "bad.yml"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.YML_MERGE_CONFLICT));
    }

    @Test
    void nonMapFragmentRootShouldThrow3004() {
        assertThatThrownBy(() -> fragment("- just\n- a list\n", YmlFragmentLinkage.MergeStrategy.APPEND, "list.yml"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.YML_MERGE_CONFLICT));
    }

    /** 测试辅助：构造不可变单层 Map。 */
    static final class YamlTestSupport {
        private YamlTestSupport() {
        }

        static Map<String, Object> mapOf(Object... kv) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                map.put((String) kv[i], kv[i + 1]);
            }
            return map;
        }
    }
}
