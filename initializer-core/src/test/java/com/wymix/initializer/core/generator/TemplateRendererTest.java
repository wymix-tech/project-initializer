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
 * TemplateRenderer 单元测试（T5：DB 模板集为唯一数据源，渲染器无 classpath 兜底）：
 * 渲染成功 / 模板缺失即 3001 / 渲染异常 / 模板集外模板不回退 classpath。
 */
class TemplateRendererTest {

    private final TemplateRenderer renderer = ClasspathTemplates.renderer();

    private Map<String, Object> model() {
        Map<String, Object> model = new HashMap<>();
        model.put(TemplateRenderer.K_BASE_PACKAGE, "com.example.demo");
        model.put(TemplateRenderer.K_PACKAGE_PATH, "com/example/demo");
        model.put(TemplateRenderer.K_ARTIFACT, "demo-app");
        model.put(TemplateRenderer.K_GROUP, "com.example");
        model.put(TemplateRenderer.K_BOOT_VERSION, "3.2.5");
        model.put(TemplateRenderer.K_JAVA_VERSION, "17");
        // pom.xml.ftl 顶部的 <parent> 为必填模型项（由 ProjectGenerator 按 Boot 版本注入）
        model.put("parentGroupId", "org.springframework.boot");
        model.put("parentArtifactId", "spring-boot-starter-parent");
        model.put("parentVersion", "3.2.5");
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
    void templateOutsideTemplateSet_shouldFailInsteadOfClasspathFallback() {
        // T5：模板集只含 README 时，classpath 明明存在 application.yml.ftl 也不得被使用
        TemplateRenderer partial = new TemplateRenderer(Map.of("README.md.ftl", "x"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> partial.render("application.yml.ftl", model(), out))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_RENDER_FAILED))
                .hasMessageContaining("application.yml.ftl");
    }

    @Test
    void emptyTemplateSet_shouldFailInsteadOfClasspathFallback() {
        // T5：空模板集 = 无可用模板，任何渲染都失败（不再等价于 classpath 默认渲染器）
        TemplateRenderer empty = new TemplateRenderer(Map.of());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> empty.render("application.yml.ftl", model(), out))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_RENDER_FAILED));
    }

    @Test
    void nullTemplateSet_shouldFailInsteadOfClasspathFallback() {
        TemplateRenderer nullSet = new TemplateRenderer(null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThatThrownBy(() -> nullSet.render("application.yml.ftl", model(), out))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEMPLATE_RENDER_FAILED));
    }

    @Test
    void loadedTemplateSet_shouldContainAllBuiltInTemplates() {
        // classpath 内置模板集（--demo 种子来源）必须覆盖 8 个内置模板：
        // 基础模板 6 个 + xxx-redis 联动模板/yml 片段 2 个（测试资源另含 e2e 夹具模板）
        Map<String, String> templates = ClasspathTemplates.load();
        assertThat(templates).containsKeys("pom.xml.ftl", "build.gradle.ftl", "settings.gradle.ftl",
                "Application.java.ftl", "application.yml.ftl", "README.md.ftl",
                "starters/xxx-redis/RedisConfig.java.ftl", "starters/xxx-redis/application-redis.yml");
    }

    @Test
    void render_shouldWriteUtf8AndFlushBeforeReturn() {
        // 配置项必须真实生效（PIT 突变防线）：
        // ①默认编码 UTF-8：模板含中文/emoji 时输出字节必须与 UTF-8 编码一致
        //   （若去掉 setDefaultEncoding，StringTemplateLoader 路径会退回平台默认编码）
        // ②异常处理器 RETHROW：模型缺键必须抛异常而非静默输出占位
        // ③Writer 在返回前必须 flush：OutputStreamWriter 有内部缓冲，
        //   若去掉 flush，小模板内容可能滞留在缓冲区里（表现为产物为空）
        TemplateRenderer renderer = new TemplateRenderer(
                Map.of("utf8.ftl", "中文内容-${artifact}-✅"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        renderer.render("utf8.ftl", model(), out);
        assertThat(out.toByteArray())
                .isEqualTo("中文内容-demo-app-✅".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void render_shouldFlushWriterSoCallerSeesCompleteContent() {
        // flush 突变防线：直接观察「OutputStreamWriter → 调用方流」的字节到达情况。
        // 用只记录 write(byte[],off,len) 的流 + 大模板（远超 Writer 缓冲），
        // 若 render 不 flush，尾部内容不会到达调用方流。
        byte[] payload = new byte[64 * 1024];
        java.util.Arrays.fill(payload, (byte) 'x');
        TemplateRenderer renderer = new TemplateRenderer(
                Map.of("big.ftl", new String(payload, StandardCharsets.UTF_8) + "-TAIL"));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        renderer.render("big.ftl", Map.of(), out);
        assertThat(out.size()).isEqualTo(payload.length + "-TAIL".length());
        assertThat(out.toString(StandardCharsets.UTF_8)).endsWith("-TAIL");
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
