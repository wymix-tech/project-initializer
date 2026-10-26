package com.wymix.initializer.core.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台内置的构建脚本模板（Maven {@code pom.xml} / Gradle {@code build.gradle} + {@code settings.gradle}）。
 *
 * <p>设计约定：这三份是生成链路的契约性骨架，不受「DB 模板集为唯一数据源」约束——
 * 发布快照缺失时以内置内容兜底；用户在管理端保存的同名模板视为覆盖，优先级高于内置版本。
 * 因此管理端只能「在内置模板上改」或「恢复内置」，不能删除。
 *
 * <p>内容来自 core 的 classpath 资源 {@code /templates/}，与生成引擎同属一个制品，
 * 版本随平台发布，不需要（也不允许）在运行时从库里凭空补齐其它模板。
 *
 * <p>线程安全性：无状态，读取结果每次新建，线程安全。
 */
public final class BuiltInBuildTemplates {

    /** Maven 主脚本模板名。 */
    public static final String POM = MavenRenderer.BUILD_TEMPLATE;
    /** Gradle 主脚本模板名。 */
    public static final String GRADLE_BUILD = GradleRenderer.BUILD_TEMPLATE;
    /** Gradle 设置脚本模板名。 */
    public static final String GRADLE_SETTINGS = GradleRenderer.SETTINGS_TEMPLATE;

    /** classpath 中模板资源的根目录。 */
    private static final String RESOURCE_BASE = "/templates/";

    /** 内置构建脚本模板名（顺序固定）。 */
    public static final List<String> NAMES = List.of(POM, GRADLE_BUILD, GRADLE_SETTINGS);

    private BuiltInBuildTemplates() {
    }

    /**
     * 是否为内置构建脚本模板。
     *
     * @param name 模板名
     * @return 命中内置清单时为 {@code true}
     */
    public static boolean isBuiltIn(String name) {
        return name != null && NAMES.contains(name);
    }

    /**
     * 读取内置模板全文。
     *
     * @param name 内置模板名
     * @return 模板全文
     * @throws BusinessException 名称非内置（1002）或资源读取失败（9001）
     */
    public static String contentOf(String name) {
        if (!isBuiltIn(name)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "非内置构建脚本模板: " + name);
        }
        String resource = RESOURCE_BASE + name;
        try (InputStream in = BuiltInBuildTemplates.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "内置构建脚本模板缺失: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "内置构建脚本模板读取失败: " + resource, e);
        }
    }

    /**
     * 读取全部内置构建脚本模板。
     *
     * @return 模板名 → 全文（顺序同 {@link #NAMES}）
     */
    public static Map<String, String> loadAll() {
        Map<String, String> templates = new LinkedHashMap<>();
        for (String name : NAMES) {
            templates.put(name, contentOf(name));
        }
        return templates;
    }

    /**
     * 用内置模板补齐缺失项（已有条目视为用户覆盖，保持不变）。
     *
     * @param templates 待补齐的模板集（会被就地修改）
     */
    public static void fillMissing(Map<String, String> templates) {
        for (String name : NAMES) {
            templates.putIfAbsent(name, contentOf(name));
        }
    }
}
