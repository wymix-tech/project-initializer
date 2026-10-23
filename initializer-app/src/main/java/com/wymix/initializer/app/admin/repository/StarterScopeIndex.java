package com.wymix.initializer.app.admin.repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 单次写入的 Starter 身份检查，供结构化表单、YAML 和 ZIP 导入共享。 */
public final class StarterScopeIndex {
    private final Set<String> globalIds = new LinkedHashSet<>();
    private final Map<String, Set<String>> scopedIds = new LinkedHashMap<>();

    /**
     * 登记一条身份；全局与版本专用条目允许并存，重复全局或重叠专用范围拒绝。
     * @param id Starter 业务 ID
     * @param bootVersions 非空的 Boot 版本集合，空集合表示全局
     * @throws IllegalArgumentException 身份冲突，调用方转换为对应通道的业务错误
     */
    public void add(String id, Collection<String> bootVersions) {
        if (bootVersions.isEmpty()) {
            if (!globalIds.add(id)) {
                throw new IllegalArgumentException("starter ID 重复: " + id + "（全局条目）");
            }
        } else {
            Set<String> occupied = scopedIds.computeIfAbsent(id, key -> new LinkedHashSet<>());
            for (String boot : new LinkedHashSet<>(bootVersions)) {
                if (!occupied.add(boot)) {
                    throw new IllegalArgumentException("starter ID 重复: " + id + "（Boot " + boot + " 范围重叠）");
                }
            }
        }
    }
}
