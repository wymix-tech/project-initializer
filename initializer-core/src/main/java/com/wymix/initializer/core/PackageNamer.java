package com.wymix.initializer.core;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;

import java.util.Locale;

/**
 * 包名推导工具：按 Group + Artifact 推导默认 Package name。
 *
 * <p>规则（PRD 已确认）：
 * <ol>
 *   <li>取 group 的合法标识段，倒序拼接（如 com.wymix → com.wymix）</li>
 *   <li>追加 artifact（去非法字符，中划线段转驼峰）</li>
 *   <li>推导结果可被用户显式传入的 package 覆盖（本工具不负责覆盖，仅负责推导）</li>
 * </ol>
 *
 * <p>线程安全性：无状态，线程安全。
 */
public final class PackageNamer {

    private PackageNamer() {
    }

    /**
     * 由 group 与 artifact 推导默认包名。
     *
     * @param group    Maven groupId，如 {@code com.wymix.top}
     * @param artifact Maven artifactId，如 {@code demo-app}
     * @return 推导出的包名，如 {@code com.wymix.top.demoapp}
     * @throws BusinessException group 或 artifact 非法（错误码 {@link ErrorCode#INVALID_METADATA}）
     */
    public static String derive(String group, String artifact) {
        if (group == null || group.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_METADATA, "group 不能为空");
        }
        if (artifact == null || artifact.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_METADATA, "artifact 不能为空");
        }
        String normalizedGroup = normalizeSegments(group, ".");
        String normalizedArtifact = normalizeSegments(artifact, "");
        if (normalizedGroup.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_METADATA, "group 无合法标识段: " + group);
        }
        if (normalizedArtifact.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_METADATA, "artifact 无合法标识段: " + artifact);
        }
        return normalizedArtifact.isEmpty()
                ? normalizedGroup
                : normalizedGroup + "." + normalizedArtifact;
    }

    /**
     * 按分隔符切分输入，过滤非法字符并转小写。
     *
     * @param raw       原始字符串
     * @param delimiter 输出段之间的连接符（group 用 "."，artifact 用 ""）
     * @return 归一化后的字符串，可能为空串（不代表合法，调用方需校验）
     */
    private static String normalizeSegments(String raw, String delimiter) {
        String[] parts = raw.trim().toLowerCase(Locale.ROOT).split("[._\\-]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String cleaned = part.replaceAll("[^a-z0-9]", "");
            if (cleaned.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(delimiter);
            }
            sb.append(cleaned);
        }
        return sb.toString();
    }
}
