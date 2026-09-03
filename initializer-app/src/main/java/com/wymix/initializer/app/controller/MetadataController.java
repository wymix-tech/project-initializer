package com.wymix.initializer.app.controller;

import com.wymix.initializer.app.metadata.MetadataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 元数据查询接口：Java 版本、Boot 版本联动、starter 清单。
 *
 * <p>错误语义：未知版本 ID 由 {@link com.wymix.initializer.app.exception.GlobalExceptionHandler}
 * 映射为 422 + 错误码 2002（METADATA_NOT_FOUND）。
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
     * 指定 Boot 版本下可用的 starter 清单。
     *
     * @param boot Boot 版本 ID，如 {@code 3.2.5}
     * @return starter 清单
     */
    @GetMapping("/starters")
    public List<MetadataService.StarterDto> starters(@RequestParam("boot") String boot) {
        return metadataService.listStarters(boot);
    }
}
