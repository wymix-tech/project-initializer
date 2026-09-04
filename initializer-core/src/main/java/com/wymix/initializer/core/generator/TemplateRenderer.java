package com.wymix.initializer.core.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.StringTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * FreeMarker 模板渲染器：classpath {@code templates/} 目录加载模板集。
 *
 * <p>M3.2 模板配置化：支持以「模板名 → 模板文本」覆盖映射构造快照级渲染器，
 * 覆盖模板优先于 classpath 内置模板（内置兜底，未覆盖的模板仍走 classpath）。
 *
 * <p>线程安全性：{@link Configuration} 配置后不可变，线程安全。
 */
public class TemplateRenderer {

    private final Configuration cfg;

    /** 模板数据模型键：基础包名。 */
    public static final String K_BASE_PACKAGE = "basePackage";
    /** 模板数据模型键：包路径（点转斜杠）。 */
    public static final String K_PACKAGE_PATH = "packagePath";
    /** 模板数据模型键：artifactId。 */
    public static final String K_ARTIFACT = "artifact";
    /** 模板数据模型键：groupId。 */
    public static final String K_GROUP = "group";
    /** 模板数据模型键：Boot 版本。 */
    public static final String K_BOOT_VERSION = "bootVersion";
    /** 模板数据模型键：Java 版本。 */
    public static final String K_JAVA_VERSION = "javaVersion";
    /** 模板数据模型键：starter 清单。 */
    public static final String K_STARTERS = "starters";
    /** 模板数据模型键：starter 依赖坐标清单（元素含 groupId/artifactId，Gradle 模板还读取可选的 version）。 */
    public static final String K_STARTER_DEPENDENCIES = "starterDependencies";

    /** 模板集 classpath 根目录。 */
    public static final String TEMPLATE_BASE = "/templates/";

    /**
     * 构造渲染器，classpath {@code templates/} 目录装载全部模板。
     */
    public TemplateRenderer() {
        this(Map.of());
    }

    /**
     * M3.2 构造渲染器：模板覆盖映射优先，未覆盖的模板回退 classpath 内置（兜底语义）。
     *
     * <p>覆盖映射键为模板名（与渲染请求名一致，如 {@code README.md.ftl}、
     * {@code starters/xxx-redis/RedisConfig.java.ftl}），值为模板全文。
     *
     * @param overrides 模板覆盖映射（可空/空映射等价于默认构造）
     */
    public TemplateRenderer(Map<String, String> overrides) {
        this.cfg = new Configuration(Configuration.VERSION_2_3_32);
        if (overrides != null && !overrides.isEmpty()) {
            StringTemplateLoader memory = new StringTemplateLoader();
            for (Map.Entry<String, String> entry : overrides.entrySet()) {
                memory.putTemplate(entry.getKey(), entry.getValue());
            }
            cfg.setTemplateLoader(new MultiTemplateLoader(new TemplateLoader[]{
                    memory, new ClassTemplateLoader(TemplateRenderer.class, TEMPLATE_BASE)}));
        } else {
            cfg.setClassForTemplateLoading(TemplateRenderer.class, TEMPLATE_BASE);
        }
        cfg.setDefaultEncoding(StandardCharsets.UTF_8.name());
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
    }

    /**
     * 渲染模板到输出流。
     *
     * @param templateName 模板名（相对 templates/ 目录，如 {@code pom.xml.ftl}）
     * @param model        数据模型
     * @param out          输出流（调用方负责关闭；本方法不关闭）
     * @throws BusinessException 模板缺失（3001）或渲染失败（3001）
     */
    public void render(String templateName, Map<String, Object> model, java.io.OutputStream out) {
        try {
            Template template = cfg.getTemplate(templateName);
            Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            template.process(model, writer);
            writer.flush();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.TEMPLATE_RENDER_FAILED,
                    "模板不存在或读取失败: " + templateName, e);
        } catch (freemarker.template.TemplateException e) {
            throw new BusinessException(ErrorCode.TEMPLATE_RENDER_FAILED,
                    "模板渲染失败: " + templateName + ", 原因: " + e.getMessage(), e);
        }
    }

    /**
     * 发布期模板语法校验（M3.2）：只解析不渲染，语法非法即抛 3001（发布层收敛为 4002）。
     *
     * @param name    模板名（错误明细标识用）
     * @param content 模板全文
     * @throws BusinessException FreeMarker 语法非法（错误码 {@link ErrorCode#TEMPLATE_RENDER_FAILED}）
     */
    public void validate(String name, String content) {
        try (Reader reader = new StringReader(content)) {
            new Template(name, reader, cfg);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.TEMPLATE_RENDER_FAILED,
                    "模板语法非法: " + name + ", 原因: " + e.getMessage(), e);
        }
    }
}
