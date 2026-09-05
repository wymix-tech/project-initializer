package com.wymix.initializer.core.conflict;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 依赖冲突规则（M3.5）：配置化的 starter 互斥约束，随清单发布进快照。
 *
 * <p>两种规则类型（概要设计 M3.5）：
 * <ul>
 *   <li>{@code PAIR} —— id 对互斥：规则内两个 starter 不可同时选中</li>
 *   <li>{@code GROUP} —— 组内互斥：规则内任意两个及以上 starter 不可同时选中</li>
 * </ul>
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public final class ConflictRule {

    /** 规则类型。 */
    public enum Type {
        /** id 对互斥：恰好两个 starter，二者同时选中即冲突 */
        PAIR,
        /** 组内互斥：任意两个及以上同时选中即冲突 */
        GROUP;

        /**
         * 宽松解析规则类型字符串（大小写不敏感）。
         *
         * @param raw 原始字符串
         * @return 规则类型
         * @throws IllegalArgumentException 取值非法
         */
        public static Type parse(String raw) {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** 规则 ID（发布清单内唯一）。 */
    private final String id;
    /** 规则类型。 */
    private final Type type;
    /** 规则涉及的 starter ID（PAIR 恰好 2 个，GROUP 至少 2 个，配置顺序保留）。 */
    private final List<String> starters;
    /** 冲突提示文案（前端展示与生成兜底错误明细）。 */
    private final String message;

    /**
     * @param id       规则 ID（禁空）
     * @param type     规则类型（禁 null）
     * @param starters 涉及 starter ID（PAIR 恰好 2 个，GROUP 至少 2 个）
     * @param message  提示文案（禁空）
     * @throws IllegalArgumentException 结构非法（装载期校验，发布即拒绝）
     */
    public ConflictRule(String id, Type type, List<String> starters, String message) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("冲突规则缺少必需字段: id");
        }
        if (type == null) {
            throw new IllegalArgumentException("冲突规则 " + id + " 缺少必需字段: type");
        }
        if (starters == null || starters.isEmpty()) {
            throw new IllegalArgumentException("冲突规则 " + id + " 缺少必需字段: starters");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("冲突规则 " + id + " 缺少必需字段: message");
        }
        if (type == Type.PAIR && starters.size() != 2) {
            throw new IllegalArgumentException("冲突规则 " + id + " 为 pair 类型，starters 必须恰好 2 个，实际 "
                    + starters.size() + " 个");
        }
        if (type == Type.GROUP && starters.size() < 2) {
            throw new IllegalArgumentException("冲突规则 " + id + " 为 group 类型，starters 至少 2 个，实际 "
                    + starters.size() + " 个");
        }
        this.id = id;
        this.type = type;
        this.starters = List.copyOf(starters);
        this.message = message;
    }

    /** @return 规则 ID */
    public String id() {
        return id;
    }

    /** @return 规则类型 */
    public Type type() {
        return type;
    }

    /** @return 涉及的 starter ID（配置顺序，不可变） */
    public List<String> starters() {
        return starters;
    }

    /** @return 冲突提示文案 */
    public String message() {
        return message;
    }

    /**
     * 判定：给定已选 starter 集合是否触发本规则。
     *
     * <p>PAIR：两个成员同时入选即触发；GROUP：两个及以上成员入选即触发。
     *
     * @param selectedStarterIds 已选 starter ID 集合（禁 null）
     * @return 触发返回 true
     */
    public boolean isTriggeredBy(java.util.Set<String> selectedStarterIds) {
        Objects.requireNonNull(selectedStarterIds, "selectedStarterIds");
        long hits = starters.stream().filter(selectedStarterIds::contains).count();
        return type == Type.PAIR ? hits == 2 : hits >= 2;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ConflictRule other
                && id.equals(other.id) && type == other.type
                && starters.equals(other.starters) && message.equals(other.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, starters, message);
    }

    @Override
    public String toString() {
        return "ConflictRule{id='" + id + "', type=" + type + ", starters=" + starters + "}";
    }
}
