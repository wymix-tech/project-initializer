package com.wymix.initializer.app.controller;

import com.wymix.initializer.app.metadata.MetadataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 元数据查询接口：Java 版本、Boot 版本联动、starter 清单（含搜索/分类过滤）。
 *
 * <p>错误语义：未知版本 ID 由 {@link com.wymix.initializer.app.exception.GlobalExceptionHandler}
 * 映射为 422 + 错误码 2002（METADATA_NOT_FOUND）。
 *
 * <p>M3.4：{@code /api/metadata/starters} 新增 {@code ?q=}（关键字，匹配 ID/展示名）
 * 与 {@code ?category=}（分类过滤）参数，两者可组合；未传时返回全量清单，
 * 未知分类返回空列表（前端友好，不报错）。
 *
 * <p>线程安全性：无状态单例，线程安全。
 */
@RestController
@RequestMapping("/api/metadata")
public class MetadataController {

    private final MetadataService metadataService;

    /**
     * @param metadataService 元数据应用服务
     */
    public MetadataController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    /**
     * Java 版本清单。
     *
     * @return 全量 Java 版本（含推荐 Boot 版本，供前端默认选中）
     */
    @GetMapping("/java-versions")
    public List<MetadataService.JavaVersionDto> javaVersions() {
        return metadataService.listJavaVersions();
    }

    /**
     * 指定 Java 版本下兼容的官方 Boot 版本（联动过滤）。
     *
     * @param java Java 版本 ID，如 {@code 17}
     * @return 兼容 Boot 版本清单
     */
    @GetMapping("/boot-versions")
    public List<MetadataService.BootVersionDto> bootVersions(@RequestParam("java") String java) {
        return metadataService.listCompatibleBootVersions(java);
    }

    /**
     * 指定 Boot 版本下可用的 starter 清单，支持搜索与分类过滤（M3.4）。
     *
     * @param boot     Boot 版本 ID，如 {@code 3.2.5}
     * @param q        关键字（可选）：匹配 starter ID/描述/展示名，忽略大小写
     * @param category 分类（可选）：web / data / middleware / tool；未知分类返回空列表
     * @return starter 清单（含分类与描述标注）
     */
    @GetMapping("/starters")
    public List<MetadataService.StarterDto> starters(@RequestParam("boot") String boot,
                                                     @RequestParam(value = "q", required = false) String q,
                                                     @RequestParam(value = "category", required = false) String category) {
        return metadataService.listStarters(boot, q, category);
    }
}
