package com.wymix.initializer.app.admin.repository;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * YAML 文本渲染工具（T1 过渡层）：统一块风格与缩进设置，避免各处重复配置
 * {@link DumperOptions} 导致导入导出与内部语义视图的文本格式漂移。
 *
 * <p>安全说明：只做**输出**渲染（{@code dump}），不涉及解析，故无 SafeConstructor 约束；
 * 反向解析一律复用 initializer-core 的解析器（其已强制 SafeConstructor）。
 *
 * <p>线程安全性：无状态工具类（每次调用新建 Yaml 实例），线程安全。
 */
public final class YamlTexts {

    private YamlTexts() {
    }

    /**
     * 以块风格渲染对象为 YAML 文本（含块标量保留换行）。
     *
     * @param value 待渲染对象（映射 / 列表 / 标量）
     * @return YAML 文本
     */
    public static String dump(Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        return new Yaml(options).dump(value);
    }
}
