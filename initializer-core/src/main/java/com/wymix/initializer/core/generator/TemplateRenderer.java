package com.wymix.initializer.core.generator;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import freemarker.cache.StringTemplateLoader;
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
 * FreeMarker 模板渲染器：以「模板名 → 模板文本」映射装载模板集（**内存模板加载器**）。
 *
 * <p><b>T5 模板改为 DB 唯一数据源</b>：模板集由调用方（快照 / 管理端草稿）显式传入，
 * 渲染器不再持有任何 classpath 兜底——<b>映射中没有的模板即渲染失败</b>（3001），
 * 不会回退到 classpath 内置 {@code templates/} 目录。
 * classpath 内置模板仅在 {@code --demo} 启动时作为**初始种子**灌入数据库（见
 * {@code DemoCatalogSeeder}），此后所有生成/校验读路径统一以 DB 模板集为准。
 *
 * <p>保留无参构造与 classpath 目录常量，仅服务于「模板语法校验
 * （{@link #validate(String, String)}）」与「demo 种子装载」两处，
 * 不再参与任何渲染路径。
 *
 * <p>线程安全性：{@link Configuration} 配置后不可变，线程安全。
 */
public class TemplateRenderer {

    private final Configuration cfg;
    /** 模板集（模板名 → 全文）：本渲染器的唯一模板来源，供联动渲染器等协作者读取同一份。 */
    private final Map<String, String> templates;

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

    /**
     * 模板集 classpath 根目录（T5 起仅用于 demo 种子装载与外部目录路径约定）。
     *
     * <p><b>不参与渲染</b>：渲染只走构造期传入的内存模板映射。
     */
    public static final String TEMPLATE_BASE = "/templates/";

    /**
     * 空模板集渲染器（等价 {@code new TemplateRenderer(Map.of())}）。
     *
     * <p>仅用于模板语法校验场景（{@link #validate(String, String)} 不依赖任何已装载模板）。
     */
    public TemplateRenderer() {
        this(Map.of());
    }

    /**
     * 构造渲染器：以传入映射为**唯一**模板来源（DB 全量模板集）。
     *
     * <p>映射键为模板名（与渲染请求名一致，如 {@code README.md.ftl}、
     * {@code starters/xxx-redis/RedisConfig.java.ftl}），值为模板全文。
     * 映射中不存在的模板名一律渲染失败（3001），无 classpath 兜底。
     *
     * @param templates 模板集映射（可空/空映射 = 无可用模板，任何渲染都失败）
     */
    public TemplateRenderer(Map<String, String> templates) {
        this.templates = templates == null ? Map.of() : Map.copyOf(templates);
        this.cfg = new Configuration(Configuration.VERSION_2_3_32);
        StringTemplateLoader memory = new StringTemplateLoader();
        for (Map.Entry<String, String> entry : this.templates.entrySet()) {
            memory.putTemplate(entry.getKey(), entry.getValue());
        }
        cfg.setTemplateLoader(memory);
        cfg.setDefaultEncoding(StandardCharsets.UTF_8.name());
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
    }

    /**
     * @return 本渲染器装载的模板集（不可变；即该渲染器的唯一模板来源）
     */
    public Map<String, String> templates() {
        return templates;
    }

    /**
     * 渲染模板到输出流。
     *
     * @param templateName 模板名（相对 templates/ 目录，如 {@code pom.xml.ftl}）
     * @param model        数据模型
     * @param out          输出流（调用方负责关闭；本方法不关闭）
     * @throws BusinessException 模板缺失（3001，DB 模板集中无该模板名）或渲染失败（3001）
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
