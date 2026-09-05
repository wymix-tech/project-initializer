package com.wymix.initializer.core.conflict;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 冲突规则目录（M3.5）：不可变规则集合，随快照发布，供前端提示与生成兜底校验消费。
 *
 * <p>装载期校验（构造即校验，发布期拦截）：规则 ID 重复即失败——
 * 规则 ID 是触发提示与审计定位的唯一键，重复意味着配置歧义。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public final class ConflictCatalog {

    /** 空目录（无任何冲突规则的默认态）。 */
    private static final ConflictCatalog EMPTY = new ConflictCatalog(List.of());

    private final Map<String, ConflictRule> rules;

    /**
     * @param rules 规则清单（禁 null）
     * @throws IllegalArgumentException 规则 ID 重复
     */
    public ConflictCatalog(List<ConflictRule> rules) {
        Map<String, ConflictRule> map = rules.stream()
                .collect(Collectors.toMap(ConflictRule::id, Function.identity(),
                        (a, b) -> {
                            throw new IllegalArgumentException("冲突规则 ID 重复: " + a.id());
                        }));
        this.rules = Map.copyOf(map);
    }

    /** @return 空目录（无规则） */
    public static ConflictCatalog empty() {
        return EMPTY;
    }

    /** @return 全部规则（不可变） */
    public List<ConflictRule> all() {
        return List.copyOf(rules.values());
    }

    /** @return 规则数量 */
    public int size() {
        return rules.size();
    }

    /**
     * 输入已选 starter 集合，输出触发的冲突清单（规则保持配置顺序语义，按 ID 排序稳定输出）。
     *
     * @param selectedStarterIds 已选 starter ID 集合（禁 null）
     * @return 触发的冲突列表（未触发为空列表，不可变）
     */
    public List<TriggeredConflict> evaluate(java.util.Set<String> selectedStarterIds) {
        return rules.values().stream()
                .filter(rule -> rule.isTriggeredBy(selectedStarterIds))
                .map(rule -> new TriggeredConflict(rule.id(), rule.starters(), rule.message()))
                .toList();
    }

    /** 触发的冲突条目：规则 ID + 涉及 starter + 提示文案。 */
    public record TriggeredConflict(String ruleId, List<String> starters, String message) {
    }
}
