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
    void shouldRenderTemplate() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        renderer.render("application.yml.ftl", model(), out);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("name: demo-app");
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
