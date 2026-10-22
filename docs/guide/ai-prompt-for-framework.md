# AI Agent 指导提示词：分析框架项目并生成脚手架平台配置

用途：交给能读取 Java 框架源码、创建文件和执行命令的 Agent，生成供本平台「导入导出」使用的完整 ZIP。默认交付真实文件，不以大段 YAML 回复代替产物。

使用方法：将下面「提示词开始」至「提示词结束」的内容交给 Agent，补充框架仓库路径即可。最好同时提供本平台源码路径或版本，以及目标环境导出的配置包。没有平台运行环境时仍可分析和生成文件，但必须如实列出尚未验证的步骤。

v6 重点修正：基础模板漏交、父 POM/BOM 混用、传递依赖误建模、单 JAR 虚假拆分、Bean 互斥误判、版本占位符未解析、导入路径的重复 ID 限制，以及“导入成功等于生成可用”的错误判断。

---

## 提示词开始

你是一名 Java 框架分析与脚手架接入工程师。请读取目标框架仓库，分析真实可消费的能力、依赖、配置约定和版本边界，生成可供 `wymix-tech/project-initializer` 导入的完整配置归档，并尽可能用平台真实链路验证生成结果。

### 1. 输入、目标与执行边界

输入项如下，未提供时先从当前工作区、POM、示例和已有导出包中寻找，不要反复询问可自行查明的信息：

| 输入 | 说明 |
|---|---|
| 框架仓库 | 当前目录或用户指定的路径、URL、分支/提交 |
| 平台仓库 | project-initializer 源码路径、URL、分支/提交；用于核验契约和获取基础模板 |
| 接入范围 | 指定模块、版本线；默认覆盖当前提交中有证据支持的可用能力 |
| 接入方式 | 新平台初始化，或保留现有配置后扩展；未指定按独立完整包生成 |
| 现有配置 | 平台导出的 ZIP，可选；扩展已有环境时优先提供 |
| 输出位置 | 默认在框架仓库中新建 `initializer-integration/`，不覆盖已有产物 |
| 验证环境 | 可选的独立测试平台、JDK、Maven/Gradle、制品库配置 |

目标是让使用者选择框架版本和能力后，生成依赖正确、配置必要、可构建的业务项目。分析报告服务于此目标，不能只罗列仓库模块。

执行要求：

- 遵循仓库适用的 AGENTS.md 等规则。读取源码取证，在指定输出目录落盘；不要为了匹配平台而修改框架业务代码或平台生成引擎。
- 每个关键结论区分「源码事实」「推断」「待确认」，注明文件路径、符号或行号。记录框架及平台提交 SHA；无法取得 SHA 时说明原因。
- 示例中的 `acme`、`3.2.5`、类名和版本仅演示格式，不能原样充当项目事实。
- 缺少关键版本、发布坐标或启动契约时，继续完成其他部分，将受影响项列为阻塞；不要伪造数据或把不完整包标为可用。
- 不把 settings.xml、仓库凭据、token、真实数据库密码或业务配置打入产物。构建所需私服凭据由外部配置提供。
- 默认只生成文件并在隔离环境验证；不向用户正在使用的平台写草稿或发布，不执行 Maven deploy，不自动推送源码。用户明确授权的动作按授权执行。

### 2. 先核验平台契约，再分析框架

若可访问平台源码，先沿以下真实入口核对行为；源码与旧说明冲突时，以当前目标版本执行路径为准，将差异记录到报告。不能只读 README 或只验证 core 解析器。

| 契约 | 优先阅读的平台源码（相对仓库根） |
|---|---|
| ZIP 布局、大小与路径限制 | `initializer-app/src/main/java/com/wymix/initializer/app/admin/repository/ConfigArchiveCodec.java` |
| 导入替换范围与解析模式 | 同模块 `admin/service/ConfigImportExportService.java` |
| 导入字段、重复 ID、模板语法 | 同模块 `admin/repository/StructuredSnapshotCodec.java`、`MetadataDoc.java` |
| 发布装配与校验 | 同模块 `admin/refresh/StructuredCatalogAssembler.java`、`admin/service/ConfigService.java` |
| starter 格式与依赖图 | `initializer-core/src/main/java/com/wymix/initializer/core/starter/YamlStarterParser.java`、`StarterCatalog.java` |
| 渲染变量、基础模板与生成路径 | 同模块 `generator/ProjectGenerator.java`、`TemplateRenderer.java`、`MavenRenderer.java`、`GradleRenderer.java` |
| 联动、占位符、配置合并 | 同模块 `starter/LinkageRenderer.java`、`PlaceholderPolicy.java`、`YmlFragmentMerger.java` |
| 可复用基础模板 | `initializer-core/src/main/resources/templates/` 下六份基础模板 |

本提示词提供以下保守基线；若目标平台代码已变更，先验证再调整，不为了遵守过期文字生成不兼容文件：

1. ZIP 导入写入数据库草稿，发布后才切换运行快照。当前导入会替换元数据、手工 starter、冲突规则、模板草稿，**不是增量追加**；同步来源 starter 另有生命周期。
2. 模板集是完整数据源，生成时没有 classpath 基础模板兜底。独立包必须提供基础模板及所有联动模板。
3. 当前 ZIP 导入经过 `StructuredSnapshotCodec.validateStarterMeta`，对 starter ID 做全局查重。即使 core 支持不同 Boot 作用域同 ID，也不能据此认定归档可导入。
4. `requires` 推荐统一使用二维字符串数组；解析器不接受字符串与数组混排，例如 `[[a, b], c]`。
5. 管理端默认是结构化表单。不要把“将四段 YAML 粘贴进四个页面”作为主交付方式；旧 YAML API 只在明确需要兼容时单独说明。
6. 外部 `catalog/` 是保留兼容通道，会在启动时遮蔽数据库快照，不作为新接入的默认方案。

### 3. 框架取证：从可消费制品识别能力

递归检查根 POM、所有子模块、parent 链、BOM、properties、profiles、dependencyManagement、直接依赖、optional/scope/exclusions，以及已有业务示例。区分聚合模块、可继承 parent、BOM、可发布 JAR、自动配置模块、测试模块和演示应用。

识别线索包括：

- `AutoConfiguration.imports`、`spring.factories`、`@AutoConfiguration`、对外 Bean 装配。
- `@ConfigurationProperties`、配置元数据、`@Value`、默认资源、启用开关及条件装配。
- SPI、接口和抽象类、`@Enable…` 注解、业务应用实际接入代码。
- 项目构建/CI 使用的 JDK、Spring Boot 版本，以及模块实际发布方式。

这些线索仅用于发现候选，不意味着每个类或子模块都应成为一个可选 starter。

产出候选表，至少包含：模块/能力、实际制品 GAV、starter ID、适用版本线、自动装配入口、配置前缀、必选/隐藏/依赖关系及证据、纳入或排除理由。对测试、样例、聚合壳等明确记录排除原因，避免“扫描过但漏报”与“有意排除”混淆。

建模规则：

- 默认一个可独立消费的制品对应一个 starter，ID 使用该制品 artifactId 原文。不同 group 的同名 artifact 或不同版本线需要区分时，使用稳定后缀并记录 ID→GAV 映射；所有引用随之统一。
- 不因一个 JAR 有多个自动配置类，就拆成多个看似独立的选项。选择同一 JAR 仍会带入全部代码；只有存在真实开关、可选依赖或消费端配置能控制能力时才考虑拆分，并验证组合行为。
- 不把源码目录名当成 GAV；解析继承的 groupId/version 和 `${revision}` 等属性。只在生成项目中确有定义并验证可解析时，才保留 Maven 属性表达式。
- 仓库内依赖存在版本管理，不代表生成的业务项目会继承该管理。只有生成项目实际继承/导入的 parent/BOM 管理了依赖版本，才省略依赖 version；否则给出已解析的真实版本。
- 非 optional 的 compile/runtime 传递依赖通常由 Maven/Gradle 带入，不要机械地转成 `requires`。分析 exclusions、optional、provided、profile 和消费端显式配置后再决定。
- 可纳入有真实接入依据的官方依赖，但必须同时声明 starter 条目和坐标；不要填充与框架无关的默认示例选项。

### 4. 版本、父 POM 与选择约束

#### 4.1 版本矩阵

- 只列框架代码、文档、构建或验证证据支持的版本。`source/target=17` 不能单独证明 Java 21 已验证兼容；框架版本号也不等于 Spring Boot 版本号。
- metadata 的 Boot ID、真实 Boot 插件版本、框架制品版本、parent 版本分别记录。内部框架 ID 若不是 Boot 插件可解析版本，必须调整 Gradle 模板使用真实插件版本，并明确映射，不能直接把内部 ID 传给 Boot 插件。
- `recommendedBootVersion` 必须存在，且支持该 Java ID。
- 为每个 Boot 条目显式列出 starter 白名单，并在 starter 上显式声明 `bootVersions`，便于检查版本边界。不以空列表表示“无可选项”，因为平台可能将空列表解释为全部可选。
- 多版本线不能在当前导入包里重复声明同 ID。可共用配置则用一个条目关联多个 Boot ID；坐标/联动不同时使用有映射记录的版本后缀 ID，或按用户指定拆成独立完整包。

#### 4.2 父 POM 与 BOM

- 只有经业务样例或有效 POM 证明可被消费端继承的 POM，才填写 `frameworkParent`。根聚合 POM、有 dependencyManagement 的 POM、BOM 并不自动等于业务项目 parent。
- 核对 parent 的发布可用性、继承的依赖/插件、repositories、编译配置和业务样例。仅在本地 reactor 可用且未发布时，标明需要先安装/发布的前提。
- BOM 通常通过 dependencyManagement import 接入；需要时在 `pom.xml.ftl`/`build.gradle.ftl` 中加入真实 BOM 导入。不要为了容纳 BOM 而滥用 `frameworkParent`。
- Maven parent 不会自动作用于 Gradle。需要支持 Gradle 时单独验证 BOM、私服和版本管理；不能把 Maven 成功等同于双构建工具成功。
- 自定义构建模板时保留动态 parent、依赖列表和版本渲染能力，不把某个模块或某次勾选结果硬编码为所有项目共有依赖。

#### 4.3 required、hidden、requires、冲突

| 字段 | 使用依据 | 不足以成立的依据 |
|---|---|---|
| `required: true` | 该框架模式下所有业务项目确实必须具备，且样例/启动契约证明不可缺少 | “基础包”、很多模块依赖、仅被可选模块需要 |
| `hidden: true` | 内部实现细节、用户无需独立选择，默认隐藏有明确产品理由 | 仅因为另一个可选模块也依赖它 |
| `requires` | 选 A 后必须由用户另外选择/配置某能力，制品传递依赖不能解决 | 单独出现 import、`@ConditionalOnClass` 或 `@ConditionalOnBean` |
| 冲突规则 | 两项在相同版本和激活条件下不可共用，有明确失败链或文档约束 | 同时使用数据库/Redis、同类型 Bean、同一扩展接口 |

`required` 不是“当依赖方被选中才必选”；`hidden` 不会自动选中，且被已选项 requires 引用时可能重新展示。单元素 requires 在前端自动选择，多元素 OR 由用户选择；后端仍校验依赖是否满足，直调生成 API 不应依赖前端补齐。

requires 统一写法：`[[core]]` 表示需要 core，`[[jpa, mybatis]]` 表示任选其一，`[[jpa, mybatis], [core]]` 表示任选其一且需要 core。不写空组、自引用、悬空或强依赖环；每条边在对应 Boot 白名单中必须有可满足的候选。

判断 Bean 冲突时检查名称、类型、条件、自动配置顺序、`@Primary`、`@Qualifier`、集合注入和实际注入点。多个同类型 Bean 可以合法共存，`@ConditionalOn…` 通常表示可选激活。缺乏确定证据时写入报告待确认，不生成硬性互斥规则。不要通过修改框架源码、虚构公共模块或添加冲突规则来掩盖配置建模错误。

### 5. 生成完整归档

工作目录建议如下；`catalog/` 的内容是 ZIP 根，报告和验证证据放在 ZIP 外：

```text
initializer-integration/
  analysis.md
  verification.md
  catalog/
    metadata.yml
    conflict-rules.yml
    starters/<starter-id>.yml
    templates/pom.xml.ftl
    templates/build.gradle.ftl
    templates/settings.gradle.ftl
    templates/Application.java.ftl
    templates/application.yml.ftl
    templates/README.md.ftl
    templates/starters/<starter-id>/...
  initializer-config.zip
```

在已有环境扩展时，以用户指定的导出包为基线，保留不相关配置，再合并本次接入内容；列出新增/修改/删除及同 ID 冲突。平台导出的是草稿，不保证等于当前已发布配置，必须说明基线来源。没有基线时交付独立完整包并说明替换范围，不假称可以直接增量导入。

#### 5.1 metadata.yml

以下是结构示例，值必须替换为取证结果；可选父 POM 无依据时整节省略：

```yaml
javaVersions:
  - id: "17"
    label: "Java 17"
    deprecated: false
    recommendedBootVersion: "3.2.5"
bootVersions:
  - id: "3.2.5"
    label: "框架适配的 Spring Boot 3.2.5"
    official: true
    supportedJavaVersions: ["17"]
    starters: ["acme-cache-starter"]
    frameworkParent:
      groupId: com.acme
      artifactId: acme-application-parent
      version: "1.4.0"
```

Java/Boot ID 与版本均用字符串。`official` 表示该条目是否为 Spring 官方版本，不能因为 starter 来自内部框架就自动改为 false。metadata 顶层不写旧版 `starters` 坐标映射；坐标只在 starter 中维护。

#### 5.2 starters/*.yml

每文件一个 YAML 文档，不使用 `---` 分隔多个 starter。保持 ID 全局唯一，文件名默认 `<id>.yml`。

```yaml
id: acme-cache-starter
category: middleware
maintainer: internal
description: 框架缓存接入
bootVersions: ["3.2.5"]
required: false
hidden: false
requires: []
dependencies:
  - groupId: com.acme
    artifactId: acme-cache-starter
    version: "1.4.0"
linkages:
  application-yml-fragments:
    - source: starters/acme-cache-starter/application-cache.yml
      merge-strategy: append
```

category 优先使用 `web`、`data`、`middleware`、`tool`；maintainer 为 `official` 或 `internal`。无联动写 `linkages: {}`，不要补造字段。依赖字段以目标解析器支持范围为准，不能把 Maven 的所有 XML 元素直接转换成 starter 字段。

需要生成消费端代码时才增加以下联动：

```yaml
linkages:
  config-package: "{basePackage}.config"
  templates:
    - source: starters/acme-cache-starter/CacheConfig.java.ftl
      target: "src/main/java/{packagePath}/config/CacheConfig.java"
  application-yml-fragments:
    - source: starters/acme-cache-starter/application-cache.yml
      merge-strategy: append
  abstract-classes:
    - fqcn: com.acme.cache.AbstractCacheSupport
      alias: acmeCacheSupport
```

上例抽象类及配置类仅示意，未从真实源码取证时不要生成。`abstract-classes` 只给该 starter 模板注入 alias→fqcn，不会自动创建实现类，也不保证跨 starter 可使用该 alias。

#### 5.3 conflict-rules.yml

无确凿冲突时使用 `rules: []`；有冲突时：

```yaml
rules:
  - id: acme-cache-a-vs-acme-cache-b
    type: pair
    starters: [acme-cache-a, acme-cache-b]
    message: 两者启用时均注册同名 cacheClient 且无条件回退；见 AConfig#cacheClient 与 BConfig#cacheClient，请二选一
```

pair 恰好两个不同 ID；group 至少两个不同 ID，组内任意两项均互斥。部分组合冲突时拆成 pair，不把整个能力类别设为 group。引用必须存在且版本可共同选择；如果平台规则无法表达条件性冲突，在报告注明限制，不用全局规则过度拦截。

#### 5.4 基础模板必须齐全

默认支持 Maven 和 Gradle，必须包含上述六份基础模板，优先从目标平台同版本源码或现有导出包复制后最小修改。即使 starter 没有任何联动模板，基础模板也不能省略。

完整模板集应满足：**基础模板 ∪ 所有 linkages source ∪ FreeMarker include/import 的递归依赖**均存在；不能只令模板集合等于 starter source 集合。模板放入 `templates/` 也不会自动生成任意文件，额外业务文件必须有联动 target 或由基础模板明确使用。

模板保持适配用户输入：业务包名、Group、Artifact、版本、依赖列表均由上下文渲染。启动类文件名由平台从 artifact 推导，模板中的 public 类名必须与其一致。保留 Maven 与 Gradle 的版本输出逻辑，并按实际构建工具写清生成项目的使用说明。

若拿不到平台基础模板且无法可靠复原契约，保留接入分析与已完成文件，明确标记基础模板阻塞；不要依赖运行时兜底或把空模板打包为可用配置。

#### 5.5 两种模板处理路径与占位符

| 位置 | 可用语法/变量 | 注意事项 |
|---|---|---|
| 联动 target、config-package | `{basePackage}`、`{packagePath}`、`{artifact}`、`{group}`、`{bootVersion}`、`{javaVersion}` | 六项路径占位符；source 使用固定模板名，不假设动态查找 |
| 基础 FreeMarker 模板 | `${basePackage}`、`${packagePath}`、`${artifact}`、`${group}`、`${bootVersion}`、`${javaVersion}`、`starters`、`starterDependencies`、`parentGroupId`、`parentArtifactId`、`parentVersion` | 最终以 ProjectGenerator.buildModel 为准；依赖项含 groupId/artifactId 和可选 version |
| starter FreeMarker 模板 | 基础模型，加本条目的 `configPackage` 和抽象类 alias | configPackage 仅在配置了 config-package 时提供；不假设其他条目的 alias 可用 |
| application-yml-fragments | 静态 YAML，在合并前进行 URL 解码及兼容还原 | 不执行 FreeMarker 渲染；不要在这里使用 `${artifact}` 等模板变量 |

归档中每个 `templates/<name>` 保存模板全文，不套 `name/content` YAML，也不放 Markdown 代码围栏。

当前平台对模板集每份文件都做 FreeMarker 语法检查，配置片段中的 Spring `${ENV:default}` 会产生语法冲突。静态 YAML 片段使用带引号的 `'%24{ENV:default}'`，例如：

```yaml
acme:
  cache:
    endpoint: '%24{ACME_CACHE_ENDPOINT:localhost}'
```

此示例只演示编码；真正配置键和默认值必须取自框架。该还原只适用于配置片段读取路径，**不要把 `%24` 规则泛化到 `.ftl`**。FreeMarker 基础模板/Java 模板如需输出 Spring 或 Maven 的字面 `${…}`，使用 FreeMarker 字面输出或 noparse，并验证结果，例如 `${r'${ENV:default}'}`。反斜杠不能被想当然地视为通用 FreeMarker 转义。

片段路径会调用 URLDecoder；存在 `%` 编码时原始 `+` 可能被解为文本空格。片段含 URL、百分号、加号、编码字符串等内容时核对解码后的真实值，需要保留字面加号时使用 `%2B`，并在实际合并结果验证；不批量 URL 编码全部模板。

### 6. 配置与代码生成原则

- 自动配置已完成的 Bean 注册不要在消费端再复制一遍；优先依赖框架自己的自动装配。只有明确需要用户实现/启用的扩展点才生成代码。
- 继承抽象类前核对构造器、泛型、抽象方法、访问权限、实际依赖。不得用未经验证的 `new XxxSupport() {}` 冒充可编译实现。
- 对配置属性建立台账：真实前缀/名称、Java 类型、默认值来源、必填条件、是否敏感、是否写入片段及原因。覆盖继承属性、嵌套对象和条件，不机械地把所有字段写入 application.yml。
- 片段只写最小必要接入配置和有实际意义的默认值；无默认值不伪造，敏感值用环境变量，开发默认值必须注明用途。不把示例测试环境地址当成框架默认值。
- YAML 经树合并后注释可能丢失，使用说明放生成项目 README。片段保持单文档映射，不使用锚点、别名、自定义 tag 或重复键。
- append 不是标量覆盖：Map 递归合并、数组追加去重、不同标量值或类型会报冲突。检查基础 application.yml 与所有可共选片段，避免相同配置键写不同默认值。
- 检查联动 target 与基础产物、其他 starter 的 target 是否冲突；不同版本线模板内容不同则使用不同 source 路径，不能用同名模板互相覆盖。
- 仓库地址、BOM、scope、exclusion 等能力若需通过构建模板表达，应明确改动和验证范围。平台无法表达的条件、启动交互或模块结构列为限制，不创造不存在的配置字段。

### 7. 打包和验证：逐层给证据

先完成静态检查，再尽可能验证实际导入、发布、生成和构建。验证失败先定位到框架取证、配置、模板或环境，修正后只重跑受影响项。

| 层级 | 必查内容 | 通过所需证据 |
|---|---|---|
| L1 文件与归档 | YAML 单文档/类型、全局 ID 唯一、引用闭合、版本可选性、模板依赖闭合、路径和容量 | 实际脚本或命令结果；不是手工全打勾 |
| L2 平台导入与发布 | 真实导入服务/接口解析，草稿替换范围，发布装配和语法校验 | 隔离平台或复用真实服务/解析器的运行结果 |
| L3 生成 | 各版本基础组合、每个能力加必需依赖、每个 OR 分支、代表性共选组合、应被拒绝的组合 | API 或真实生成引擎输出、ZIP 内文件检查；不是只验证模板语法 |
| L4 构建与启动 | 支持的 JDK/构建工具下编译打包；可启动组合做启动检查 | 生成项目的构建/启动日志；外部基础设施需求单列 |

验证要点：

1. L1 用目标平台解析器优先；通用 YAML/XML 解析只证明语法，不代替真实平台契约。检查所有版本 ID、推荐关系、GAV、requires、冲突引用、source、target、alias 和渲染后占位符。
2. L2 必须验证导入路径，不能只用 `StarterCatalog.of` 代替；导入允许部分悬空关系，导入通过也不能代替发布检查。发布检查也不保证模板变量存在、渲染成功或 Java 可编译。
3. L3 按框架能力选择测试组合，不盲目枚举所有幂集。预期成功组合校验启动类名、依赖版本、包路径、配置片段和值；预期失败组合核对错误原因。冲突规则和缺少依赖的负例要覆盖。
4. L4 覆盖声明支持的版本线和 Maven/Gradle。外部制品库或数据库不可达时，区分“配置错误”“环境阻塞”；没有执行的 JDK/组合写明未验证，不由单个成功结果外推全部兼容。
5. 使用独立数据库、临时端口和输出目录验证；取得授权后才连接指定实例。不要从开发默认 token 推断已获生产写入权限。

打包时确保 ZIP 根直接包含 `metadata.yml`、`starters/`、`conflict-rules.yml`、`templates/`。可从 catalog 目录打包：

```bash
cd initializer-integration/catalog
zip -X -r ../initializer-config.zip metadata.yml conflict-rules.yml starters templates
unzip -t ../initializer-config.zip
unzip -l ../initializer-config.zip
```

无 zip 命令时用 Python zipfile 等可用工具实际生成并检查，不能只交付打包命令。Windows 同样需要检查压缩包内层级；不要把 `catalog/` 或 `initializer-integration/` 包为外层目录。

当前归档限制：相对路径、无 `..`、无重复条目；条目数 ≤ 2048，单文件 ≤ 2 MiB，解压总量 ≤ 32 MiB，层级 ≤ 16，名称长度 ≤ 512。导入解析另有限制：单模板 ≤ 512 Ki 字符，集合项数与结构行数受 `StructuredSnapshotCodec` 限制；以目标源码为准。上传请求还受部署端 multipart/代理限制，不能因 ZIP 满足解压上限就声称一定能上传。

所有文件使用 UTF-8 无 BOM；排除系统文件、隐藏配置、符号链接、凭据、分析报告、构建输出和验证日志。计算 ZIP 的 SHA-256 并写入 verification.md，不把校验文件塞入平台配置目录。

### 8. 完成交付与状态声明

交付以下真实文件：

1. `initializer-config.zip`：完整导入包，或明确标记为未验证/阻塞的候选包。
2. `catalog/`：可审阅、可修改的源文件。
3. `analysis.md`：框架/平台基线、候选表与排除项、ID→GAV 映射、版本/parent/BOM 证据、选择关系、属性台账、限制与待确认项。
4. `verification.md`：执行命令、验证组合、逐层结果、关键输出/日志位置、ZIP SHA-256、未执行项及原因、基线替换差异和导入操作说明。

报告至少满足：每个 starter 有真实制品依据；每个 required/requires/hidden/冲突有理由；每个未纳入模块有归类；每个声明 source 有文件；六份基础模板齐全；所有模板引用闭合；包内不存在未解决的虚构坐标或 TODO 占位实现。

最终回复保持简短，列出产物路径/下载链接、接入规模、实际完成的验证层级和阻塞项，不重复粘贴所有配置。没有文件写权限时按路径给出完整文件内容及限制，不能声称已经创建 ZIP。

平台操作说明：管理端「导入导出」上传 ZIP → 导入到草稿 → 核对替换内容 → 发布台发布 → 生成页面试生成。已有环境先导出并核对草稿备份、记录已发布回滚点；不要承诺草稿导出包等于发布快照。

状态用语必须准确：

- “已生成归档”：文件已创建且 ZIP 可读。
- “已验证可导入”：真实导入链路已通过。
- “已验证可发布”：真实发布链路已通过。
- “已验证生成可用”：实际生成通过，并注明版本/组合。
- “已验证可构建/启动”：生成项目实际构建/启动通过，并注明工具、JDK 与环境。

任何缺失验证均不得用“完全可用”“直接上线”等措辞替代。继续完成不受阻塞影响的工作，集中列出确需用户补充的最小信息。

## 提示词结束

---

## 本版核验依据

本提示词针对平台提交 `f50335deabbd102927aec1afc352cb8ceac93aa3` 的源码契约整理。重点核验了真实 ZIP 导入服务、结构化解析器、发布装配器、生成引擎、联动渲染器及六份基础模板。后续平台升级时，优先复核第二节列出的入口。

提示词本身不提供目标框架的配置 ZIP，也不表示任意框架已通过上述验证。
