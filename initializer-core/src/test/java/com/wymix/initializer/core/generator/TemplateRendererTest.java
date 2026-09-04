package com.wymix.initializer.core.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TemplateRenderer 单元测试：渲染成功 / 模板缺失 / 渲染异常。
 */
class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    private Map<String, Object> model() {
        Map<String, Object> model = new HashMap<>();
        model.put(TemplateRenderer.K_BASE_PACKAGE, "com.example.demo");
        model.put(TemplateRenderer.K_PACKAGE_PATH, "com/example/demo");
        model.put(TemplateRenderer.K_ARTIFACT, "demo-app");
        model.put(TemplateRenderer.K_GROUP, "com.example");
        model.put(TemplateRenderer.K_BOOT_VERSION, "3.2.5");
        model.put(TemplateRenderer.K_JAVA_VERSION, "17");
        model.put(TemplateRenderer.K_STARTERS, List.of("web"));
        return model;
    }

    @Test
    void pomShouldRenderStarterDependenciesFromModel() {
        // starter 依赖坐标由模型驱动（K_STARTER_DEPENDENCIES），不再依赖模板内硬编码分支
        Map<String, Object> m = model();
        m.put(TemplateRenderer.K_STARTER_DEPENDENCIES, List.of(
                Map.of("groupId", "org.springframework.boot", "artifactId", "spring-boot-starter-web")));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        renderer.render("pom.xml.ftl", m, out);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("<groupId>org.springframework.boot</groupId>")
                .contains("<artifactId>spring-boot-starter-web</artifactId>");
    }

    @Test
    void shouldRenderTemplate() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        renderer.render("application.yml.ftl", model(), out);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("name: demo-app");
    }

    @Test
    void overrideTemplate_takesPrecedenceOverClasspath() {
        TemplateRenderer overridden = new TemplateRenderer(Map.of(
                "application.yml.ftl", "spring:\n  application:\n    name: overridden-name\n"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        overridden.render("application.yml.ftl", model(), out);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("name: overridden-name");
    }

    @Test
    void overrideTemplate_uncoveredFallsBackToClasspath() {
        // 覆盖映射只含 README：pom.xml 仍走 classpath 内置模板（兜底语义）
        TemplateRenderer partial = new TemplateRenderer(Map.of("README.md.ftl", "x"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        partial.render("application.yml.ftl", model(), out);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("name: demo-app");
    }

    @Test
    void overrideTemplate_emptyMap_equivalentToDefault() {
        TemplateRenderer empty = new TemplateRenderer(Map.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        empty.render("application.yml.ftl", model(), out);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("name: demo-app");
    }

    @Test
    void validate_badSyntax_throws3001() {
        TemplateRenderer renderer = new TemplateRenderer();
        assertThatThrownBy(() -> renderer.validate("bad.ftl", "<#if unclosed>"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_RENDER_FAILED))
                .hasMessageContaining("bad.ftl");
    }

    @Test
    void validate_goodSyntax_passes() {
        TemplateRenderer renderer = new TemplateRenderer();
        renderer.validate("ok.ftl", "hello ${name}");
    }

    @Test
    void missingTemplateShouldThrow3001() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> renderer.render("no-such.ftl", model(), out))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_RENDER_FAILED));
    }

    @Test
    void brokenModelShouldThrow3001() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 缺少 artifact 键，模板引用未定义变量 → RETHROW 渲染失败
        Map<String, Object> bad = new HashMap<>(model());
        bad.remove(TemplateRenderer.K_ARTIFACT);
        assertThatThrownBy(() -> renderer.render("application.yml.ftl", bad, out))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_RENDER_FAILED));
    }
}
