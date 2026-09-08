# AI Agent 指导提示词：分析框架项目并生成脚手架平台配置（v4）

> 用途：将本提示词交给 AI Agent，让其通读一个框架项目代码库，产出可接入本脚手架平台的四段 YAML 配置（metadata / starter / conflict-rule / template）。

## 任务背景

你是一个脚手架配置生成专家。我有一个**框架项目**（组织或个人对 Spring Boot 的二次封装，封装了若干能力模块，可能带有配置类、集成抽象类、统一 yml 配置约定），**布局不固定**：可能是单模块，也可能是父子多模块，能力模块的命名也不一定是 `*-spring-boot-starter`。你的任务是**通读框架代码库，按特征而非命名识别能力模块**，产出四段 YAML 配置，用于接入"项目脚手架生成平台"。产出结果将直接复制进平台管理后台发布，因此必须严格符合下述格式，**一个字段都不能编造**。

## 第〇步：先判结构，再识别模块

### 结构判定（父子多模块优先）

先看根目录是否有聚合 POM（`packaging: pom` 且含 `<modules>`）：

- **父子多模块**：根 POM 是聚合壳，各能力散落在子模块中。此时：
  - 逐个子模块排查，一个子模块可能承载一个或多个能力（如 `xxx-spring-boot` 里同时有自动装配 + 多个 `@ConfigurationProperties`）
  - GAV 以**实际承载能力的子模块**为准（artifactId 取该子模块自己的 `<artifactId>`，不要取根 POM 的）
  - 注意 `dependencyManagement` / BOM：子模块依赖版本可能由根 POM 或父 POM 统一管理，此时依赖处不写 version，需在 metadata 中确认由哪个 Boot 版本管理
  - 多层嵌套（父 → 中间父 → 子）也按同样方式递归下探
  - **顺带记录框架父 POM**：那个被各子模块 `<parent>` 引用、或集中声明 `dependencyManagement` 的 POM，其 GAV + version 填入第一节各 `bootVersions[].frameworkParent`（生成的项目将继承它，由它统一管理 Boot BOM 与内部依赖版本；不同 Boot 版本可挂不同父 POM）
- **单模块**：整个仓库就是一个能力包，按"能力"而不是"模块"切分 starter——同一模块内如果有多组独立可用的自动装配（各自的 `@ConfigurationProperties` + `@AutoConfiguration`），应拆成多个 starter

### 能力模块识别特征（命名仅作线索，不作为依据）

以下特征**满足任意一条即视为候选能力模块**，不能只按 `*-spring-boot-starter` 后缀找：

1. `META-INF/spring/...AutoConfiguration.imports` 或 `META-INF/spring.factories` 存在自动装配登记
2. 存在 `@AutoConfiguration` / `@Configuration` 装配类并对外提供 Bean
3. 存在 `@ConfigurationProperties` 属性类（或 `@ConfigurationPropertiesScan` 扫描范围）
4. 存在 `@EnableConfigurationProperties` 指向的属性类
5. 对外暴露可继承/实现的抽象类、接口、SPI 扩展点
6. 提供 `application.yml` / `additional-spring-configuration-metadata.json` 约定的默认配置
7. 存在 `provide` / `requires` 式 SPI 文件（`META-INF/services/`）

对每个候选提取：GAV 坐标、版本、分类、功能描述、需要用户编写的配置类、需追加进 application.yml 的配置片段、可继承的抽象类、**是否必选**。识别结果先列一张"候选清单"（模块/能力 → 子模块 `<artifactId>` → **清单 id（= artifactId）** → GAV → 判定依据 → **是否必选 + 必选依据** → **依赖哪些能力（requires）**），确认无遗漏再产出 YAML。

### starter id 取值规则（**一律取模块 artifactId**）

starter 清单的 `id` **直接取"实际承载能力的子模块"的 `<artifactId>` 原文**，不缩写、不翻译、不自造、不加减前后缀。

```yaml
# 子模块 POM 中：<artifactId>acme-dynamic-datasource</artifactId>
id: acme-dynamic-datasource     # ✅ 与 <artifactId> 字面完全一致
# id: dynamic-datasource          # ❌ 截断，回查不到模块
# id: acme-dynamic-ds             # ❌ 缩写，与 artifactId 对不上
# id: AcmeDynamicDatasource       # ❌ 改了命名风格，不再是 artifactId
```

为什么这样规定：

- **可追溯**：看到 id 就能在框架仓库里直接 grep 到对应模块，人工复核与后续维护零成本
- **零歧义**：父子多模块下不同子模块可能有相似能力，artifactId 天然唯一（多 Boot 线再用 `bootVersions` 区分）
- **免映射表**：`metadata.bootVersions[].starters`、`requires`、`conflict-rule.starters`、模板路径 `starters/{id}/…` 全部复用同一字面值，不需要维护"展示名 ↔ 模块名"的第二张表

执行要点：

- 大小写、中划线、下划线**照抄** artifactId，不要改成驼峰或点分
- 父子多模块取**承载该能力的子模块** `<artifactId>`，**不要**取根 POM 或中间父 POM 的（与上面"结构判定"的 GAV 口径一致）
- 单模块仓库按能力拆分出多个 starter 时，id 仍以 artifactId 为前缀并加能力后缀（如 `acme-framework-core-web`），须在候选清单注明拆分依据
- **同一 id 在四段配置中必须字面一致**：清单 `id` ↔ `metadata.bootVersions[].starters` ↔ `requires` ↔ `conflict-rule.starters` ↔ 模板 `name` 中的 `starters/{id}/` 路径
- 官方 starter（`web` / `data-jpa` / `data-redis` / `actuator` 等）不受此规则约束，但**框架未提供时不得引用**

### 必选能力判定（`required: true`）

必选项 = 用户生成项目时**默认勾选且禁止取消**的依赖（后端也会兜底自动补齐，绕过前端直调 API 一样会加上）。
因为它无法被用户去掉，判定必须**保守**：**只有确凿证据才标，不确定就不标**。

判定为必选（满足任一，且须能在代码中指出依据）：

1. **框架核心/基础包**：其他能力模块都依赖它，或它提供全局基础设施（`ApplicationContextHolder`、统一响应体、全局异常处理、统一配置加载等），缺了其他能力无法工作
2. **缺了起不来**：没有它会导致 Spring 上下文启动失败（启动期强依赖的装配、全局前置初始化）
3. **框架父 POM 的 `<dependencies>` 中直接声明的模块**（注意：是 `<dependencies>`，不是 `dependencyManagement`）——项目继承父 POM 后本来就一定有

不应标必选：

- 独立可选能力：缓存、动态数据源、监控、服务发现、数据迁移、测试支撑等
- 只是"推荐搭配"或"多数项目会用到"，但去掉仍能正常启动的能力
- 在代码里找不到明确依据的能力

产出时：先在候选清单标注每个能力是否必选**及依据**，再据此在**第二节 starter 清单**对应文档上写 `required: true`（默认省略即为可选；metadata 已无 `starters` 节，不要写在那边）。

### 依赖联动判定（`requires`）

`requires` 表达"**选了 A 就必须有 B**"。采用 **AND-of-OR-groups** 结构：每一项是一个分组，分组内任一被选即满足该分组，全部分组都满足才算 A 的依赖被满足。

```yaml
# 以下 id 均为各能力模块的 <artifactId>
requires: [acme-framework-core, acme-security]        # 需要 core 和 security（扁平写法，每项各成一个分组）
requires: [[acme-jpa-repository, acme-mybatis-repository]]   # 需要 jpa 或 mybatis —— 二者互斥/二选一时用这种
requires: [[acme-jpa-repository, acme-mybatis-repository], acme-framework-core]  # 需要 (jpa 或 mybatis) 且 core
```

前端**不再自动勾选、也不锁定**这些依赖项，只在卡片上提示「需要 b 或 c」；未满足时在依赖区给出告警，生成期后端以 `2004 缺少必需的依赖 starter` 兜底拒绝。这样 A 依赖 B 或 C 时用户只勾一个即可，B 与 C 互斥也不会被强制同选。

判定标准（须有代码依据，满足任一）：

1. **A 的代码直接引用 B**：A 的装配类/自动配置 import 了 B 的类型，或用了 `@ConditionalOnBean(B 的类型)` / `@ConditionalOnClass`
2. **A 的文档/注释明确声明依赖 B**（如"服务发现依赖 Feign 通信"，而 Feign 基座由 core 提供）
3. **A 离开 B 会运行时报错**：`NoClassDefFoundError` / `NoSuchBeanDefinitionException`

**"或"关系的判定**：若 A 只需 B、C 中任意一个即可工作（例如 A 依赖"某个 ORM 仓储实现"，`acme-jpa-repository` 与 `acme-mybatis-repository` 二选一），写 `requires: [[acme-jpa-repository, acme-mybatis-repository]]`；若两者都缺才报错，不要拆成两个分组（那会变成"两个都要"）。

不应声明为依赖：

- 只是"通常一起用"，无代码级依赖 → 保持独立可选
- 依赖方向写反：B 用到 A，应写 `B.requires: [A]`，不是 `A.requires: [B]`
- 循环依赖（A→B 且 B→A，且两组都是单选项）：平台发布期直接拒绝，正确做法是抽出公共基座 C，改为 `A.requires: [C]`、`B.requires: [C]`；若某分组是多选项（可换分支打破环），平台不再判环

**方向口诀：被依赖的基础能力写在后面**——`acme-dal.requires: [acme-framework-core]`，不是 `acme-framework-core.requires: [acme-dal]`。

### 版本与互斥

- 版本兼容：框架适配了哪些 Spring Boot 版本，这些 Boot 版本各支持哪些 Java 版本（多模块项目注意不同分支/模块可能面向不同 Boot 线，分别记录）
- 互斥识别：两个能力若注册同一类 Bean（缓存管理器、RPC 客户端、事务管理器等，通过装配类中的 `@Bean` / `@ConditionalOnMissingBean` 分析），生成冲突规则

## 第一节：元数据（kind = `metadata`）

```yaml
javaVersions:
  - id: "17"                        # 字符串！兼容矩阵引用此值
    label: "Java 17"
    deprecated: false
    recommendedBootVersion: "3.2.5"
bootVersions:
  - id: "3.2.5"
    label: "Spring Boot 3.2.5"
    official: true                  # false = 公司定制版 Boot
    supportedJavaVersions: ["17", "21"]
    # 只能列本框架实际提供的 id；每个 id = 对应能力模块的 <artifactId>
    starters: ["acme-framework-core", "acme-dynamic-datasource"]
    frameworkParent:                # 可选：该 Boot 版本生成项目挂接的框架父 POM（pom.xml 的 <parent>）
      groupId: com.yourcompany      # 不同 Boot 版本可声明不同父 POM
      artifactId: your-framework-parent
      version: "1.0.0-SNAPSHOT"     # 支持 ${revision} 等 Maven 属性占位写法
  - id: "2.7.18"                    # 仅当框架确实适配了多个 Boot 版本时才添加此条目
    label: "Spring Boot 2.7.18"
    official: true
    supportedJavaVersions: ["8"]
    starters: ["acme-framework-core"]
# frameworkParent 未配置（且顶层也未声明全局默认）时，生成器回退 org.springframework.boot:spring-boot-starter-parent:<bootVersion>
# 注意：metadata 已不再维护 starters 节——必选（required）与依赖联动（requires）
# 全部在第二节 starter 清单里声明，展示名与坐标也来自清单，此处无需（也不应）重复写。
```

规则：

- `bootVersions[].supportedJavaVersions` 只能引用已声明的 `javaVersions[].id`
- `bootVersions[].starters` **只能列出本框架实际提供的 starter id**（即第二节 starter 清单各文档的 `id`，也就是各能力模块的 `<artifactId>`）。框架未提供官方 starter 时，不得出现 `web` / `data-jpa` / `data-redis` / `actuator` 等官方 id（这些 id 不是默认必填项，列了而框架没有就是错的）；此处 id 必须与清单 `id` **字面完全一致**（同一 artifactId，不缩写、不改名）
- **starter 的一切信息只在第二节 starter 清单写一次**：`description` → 展示名，`dependencies[0]` → 坐标 + version，`required` → 是否必选，`requires` → 依赖联动。第一节 metadata **不再有 `starters` 节**，平台发布时自动把清单合并进元数据
- `bootVersions[].starters`（**保留，重要**）：列出该 Boot 版本**可引用**的 starter id 白名单（不同 Boot 版本可用不同 starter）。**可整体省略**——省略或为空即该版本对全部清单条目可选；列出的 id 必须在第二节有清单条目，否则该 starter 不可见
- Java id 必须带引号写成字符串
- 必选与依赖联动的写法见第二节（`required` / `requires` 字段），不要在 metadata 里写
- `bootVersions[].frameworkParent`（**强烈建议填写**）：若框架仓库存在统一父 POM / BOM（聚合根 POM、`*-parent`、`*-dependencies`、或集中 `dependencyManagement` 的 POM），必须**在每个 Boot 版本条目内**声明（不同 Boot 版本可挂不同父 POM，例如 2.x 线与 3.x 线分别由不同框架父 POM 管理）；生成的项目才继承该父 POM 并由它统一管理版本。也可在 metadata.yml 顶层声明一次 `frameworkParent` 作为全局默认，条目内未声明时继承。三项（groupId/artifactId/version）必须齐全，缺一发布失败。确实没有统一父 POM 时整节省略，生成器回退官方 `spring-boot-starter-parent`
- 版本取自框架父 POM 真实 `<version>`（如 `3.0.0-SNAPSHOT`）；若父 POM 用 `${revision}` 之类的属性占位，照抄该占位写法即可

## 第二节：starter 清单（kind = `starter`，多文档 YAML）

**这一节是 starter 的唯一数据源**：展示名（`description`）、依赖坐标 + version（`dependencies`）、
分类、维护方、联动规则，**以及是否必选（`required`）与依赖联动（`requires`）**全部在此声明。
第一节 metadata 不再有 `starters` 节，发布时平台自动把清单合并进元数据。

一个能力一段文档，用 `---` 分隔，可合并为一份多文档 YAML 提交：

```yaml
---
id: acme-dynamic-datasource        # 【必填】= 承载本能力的子模块 <artifactId> 原文，见第〇步 id 规则
category: middleware               # web / middleware / tool ...
maintainer: internal               # official / internal
description: 一句话描述功能（即前端展示名）
required: true                     # 可选：true = 必选依赖（默认 false）
# 可选：依赖联动（AND-of-OR-groups），默认 []
# 下方 = 需要 jpa 或 mybatis（二者互斥时只选其一即可），不是"两个都要"
requires: [[acme-jpa-repository, acme-mybatis-repository]]
bootVersions: ["3.2.5"]            # 可选：本条目关联的 Boot 版本（不写 = 对全部 Boot 版本可用）
dependencies:
  - groupId: com.yourcompany
    artifactId: acme-dynamic-datasource   # 与 id 同源：同一子模块的 <artifactId>
    version: "1.2.0"               # official 或由 BOM 管理时可省略
linkages: {}                       # 无联动时写 {}
```

约束字段说明（原 metadata `starters` 节能力，全部迁移至此）：

- `id`（**必填**）：取承载本能力的子模块 `<artifactId>` 原文，**不缩写、不翻译、不自造**（理由与例外见第〇步「starter id 取值规则」）。同一 id 必须在 `metadata.bootVersions[].starters`、`requires`、`conflict-rule.starters`、模板 `name` 的 `starters/{id}/` 路径中**字面一致**
- `required`（可选，默认 false）：设为 `true` 即**必选依赖**——前端默认勾选且禁止取消，后端生成时兜底自动补齐（绕过前端直调 API 也会加上）。框架中"不选就跑不起来"的能力（如核心包、统一配置/鉴权基础模块）应标为必选；其余能力保持可选
- `requires`（可选，默认 `[]`）：**依赖联动（AND-of-OR-groups）**——分组内任一被选即满足该分组，全部分组满足才算依赖满足。前端**不自动勾选、不锁定**这些项，只提示「需要 X 或 Y」并在未满足时告警；后端生成期兜底拒绝（`2004 缺少必需的依赖 starter`）。校验规则：引用的 id 必须在**本清单**中登记（悬空即拒绝）、不得引用自身、不得成环（多选项分组可换分支打破环，不判环；单选项成环发布期报`循环依赖`并拒绝）。方向为「上层能力 → 基础能力」，如 `acme-dal.requires: [acme-framework-core]`
- `bootVersions`（**Boot 版本关联**）：声明本条目属于哪些 Boot 版本（值取 metadata 的 `bootVersions[].id`）。**不写即对全部 Boot 版本可用**。
  框架对多个 Boot 线（如 2.x / 3.x）提供不同坐标或版本的能力模块时，**用同一个 id 写多条文档、各自声明 `bootVersions`**——
  **同一 Boot 版本内 id 不允许重复（重复发布即拒绝），不同 Boot 版本之间允许重复**，平台按当前选中的 Boot 版本取对应条目（坐标、version、联动规则一并生效）

**内部能力（`maintainer: internal`）带联动时**，`linkages` 可含四类：

```yaml
linkages:
  config-package: "{basePackage}.config"          # 配置类生成到的包
  templates:
    # 路径中的 acme-dynamic-datasource 必须与本条目的 id（= artifactId）字面一致
    - source: "starters/acme-dynamic-datasource/DynamicDataSourceConfig.java.ftl"   # 相对 templates/ 目录
      target: "src/main/java/{packagePath}/config/DynamicDataSourceConfig.java"
  application-yml-fragments:
    - source: "starters/acme-dynamic-datasource/application-dynamic-datasource.yml"
      merge-strategy: append       # 目前仅支持 append
  abstract-classes:
    - fqcn: "com.yourcompany.xxx.XxxSupport"      # 框架中的集成抽象类全限定名
      alias: xxxSupport
```

**占位符白名单（强制）**：yml 中只允许 `{basePackage}` `{packagePath}` `{artifact}` `{group}` `{bootVersion}` `{javaVersion}` 这六个，出现其他占位符发布时直接拒绝。

## 第三节：冲突规则（kind = `conflict-rule`）

```yaml
rules:
  - id: acme-jpa-repository-vs-acme-mybatis-repository   # 发布清单内唯一（建议用两个 artifactId 拼接，便于定位）
    type: pair                      # pair（恰好 2 个）或 group（≥2 个，组内互斥）
    starters: [acme-jpa-repository, acme-mybatis-repository]   # 必须逐字照抄清单 id（= artifactId）
    message: 两者均注册 CacheManager（A: XxxCacheConfiguration#cacheManager，B: YyyCacheConfiguration#cacheManager），请二选一
```

四字段 `id` / `type` / `starters` / `message` 全部必填，缺一即发布失败。框架没有互斥关系时输出 `rules: []` 或说明"无冲突规则"。

### 互斥判定标准（**避免误判，这一节最容易出错**）

只有**会导致应用启动失败**的组合才算冲突，须满足以下两条之一：

1. **同名 Bean 冲突**：两个 starter 注册了**同名**的 `@Bean`（同名 BeanDefinition 冲突/覆盖）
2. **同类型 Bean 冲突**：两个 starter 注册了**同一类型**的 Bean，且没有 `@ConditionalOnMissingBean` 兜底（例如两个 `CacheManager`、两个 `DataSource`、两个 `PlatformTransactionManager`）

**以下不是冲突，不要生成规则**：两个能力只是"消费同一底层资源"，但并未各自注册同名/同类型 Bean。
例如都使用 `DataSource`、都依赖 Redis 连接、都用同一个线程池——这属于**依赖关系**，可以同时选中。

每条规则必须在 `message` 中写明**具体冲突的 Bean 名或 Bean 类型**以及**源码依据**（类名 + 方法名），
禁止只写"两者都使用了 Xxx"这类无法定位的模糊描述。

规则：

- `rules[].starters` 只能引用**已声明的 starter id**，且必须与第二节清单 `id`（= 子模块 `<artifactId>`）**字面完全一致**，禁止缩写或改写成能力简称，也禁止引用未声明的官方 id（`web`/`data-jpa`/`data-redis`/`actuator` 等）凑数；引用未声明 id 发布即失败
- 每条规则必须有第 1/2 条判定依据（同名或同类型 Bean），并在 `message` 中给出 Bean 名/类型 + 源码位置
- 纯"共用底层资源"的组合不产出规则，可在候选清单里标注为依赖关系即可

## 第四节：生成模板（kind = `template`，多文档 YAML）

每份模板一个文档，`name` 为相对 `templates/` 目录的路径，`content` 为模板全文（用块标量承载）：

```yaml
name: starters/acme-dynamic-datasource/DynamicDataSourceConfig.java.ftl   # 路径中的 id 必须逐字照抄清单 id
content: |
  package ${configPackage};

  import com.yourcompany.xxx.XxxSupport;
  import org.springframework.context.annotation.Bean;
  import org.springframework.context.annotation.Configuration;

  @Configuration
  public class XxxConfig {
      @Bean
      public XxxSupport xxxSupport() {
          return new XxxSupport() {};
      }
  }
---
name: starters/acme-dynamic-datasource/application-dynamic-datasource.yml
content: |
  spring:
    xxx:
      # %24 是 $ 的 URL 编码：必须用它代替 $，见下方"配置文件模板的 $ 转义"规则
      host: %24{XXX_HOST:localhost}
      port: %24{XXX_PORT:6379}
```

### 配置文件模板的 `$` 转义（**强制，否则发布失败**）

管理后台发布模板草稿时，**每一份 content（含 `.yml`）都会做 FreeMarker 语法预校验**。
配置文件里的 Spring 环境变量占位 `${ENV:default}` 中的 `${` 会被 FreeMarker 当成插值起始，
而 `:` 在 FreeMarker 表达式里非法，发布直接被拒（报 `模板语法非法 ... Encountered ":"`）。

因此 **`application-*.yml` 等配置文件模板中，一律用 `%24` 代替 `$`**：

| 你想表达的 | 必须写成 | 原因 |
|---|---|---|
| `${XXX_HOST:localhost}` | `%24{XXX_HOST:localhost}` | `%24` 是 `$` 的 URL 编码；发布期 FreeMarker 看不到 `${` 序列，校验通过；生成期合并前自动 URL 解码，还原为 `${XXX_HOST:localhost}` 写进生成项目的 application.yml，由 Spring 解析 |

等价备选写法是 `\${XXX_HOST:localhost}`（FreeMarker 转义），生成期同样会还原；**首选 `%24`**。
普通配置值（不含 `${`）无需转义，照常书写。

规则：

- `.ftl` 模板可用 FreeMarker 变量：`${group}` `${artifact}` `${bootVersion}` `${javaVersion}` `${configPackage}` `${packagePath}`
- `application-yml-fragments` 引用的片段**不要**用 `.ftl` 后缀；片断内容由管理端维护即可生效（发布后覆盖 classpath 同名文件，无需改代码、无需重新打包），合并进生成项目的 application.yml
- 配置文件模板里的 `${}`（Spring 环境变量占位）**必须用 `%24{` 书写**，禁止裸写 `${`，否则发布期 FreeMarker 预校验失败
- 配置类模板应引用框架中的集成抽象类（与 `abstract-classes[].fqcn` 对应）
- `name` 不得重复；模板必须能通过 FreeMarker 语法预校验（tag 闭合、指令合法）

### 模板完整性（**漏文件会直接导致生成失败**）

模板文档必须**逐一覆盖**第二节每个 starter `linkages` 中声明的全部 source：

- `linkages.templates[].source` —— 每一条都要有对应的 `.ftl` 模板文档
- `linkages.application-yml-fragments[].source` —— 每一条都要有对应的 `.yml` 片段文档

只要声明了 source 却没有同名 `name` 的模板文档，选中该 starter 生成项目时就会报
`模板不存在` / `yml 片段不存在` 并失败。所以：**声明即必须提供，宁可不声明也不要漏文件**。

带 `@ConfigurationProperties(prefix = "...")` 的 starter，**默认提供一份 `application-*.yml` 片段模板**，
在片段中列出该前缀下的**全部属性及框架真实默认值**（属性名/默认值取自框架代码，不得编造），并遵循 `%24` 转义规则。

### 引用一致性（拆分为四条独立约束）

1. **starter 单一数据源 + Boot 版本关联**：每个 starter 在第二节清单声明（id / description / dependencies / required / requires / bootVersions），不得在 metadata 里重复登记；`bootVersions[].starters` 列出的 id 必须有对应清单条目且该条目关联本 Boot 版本；**同一 Boot 版本内 id 不重复**（多 Boot 线共用 id 时用 `bootVersions` 拆成多条文档）
2. **id 字面一致（= artifactId）**：清单 `id` == 子模块 `<artifactId>`；且 `metadata.bootVersions[].starters`、`requires`、`conflict-rule.starters`、模板 `name` 的 `starters/{id}/` 路径中出现的同一 starter，**字面值完全相同**（不缩写、不改命名风格）
3. **冲突规则引用不越界**：`conflict-rule` 的 `rules[].starters` ⊆ 清单已声明的 starter id
4. **模板与声明严格对齐**：`template` 的 `name` 集合 == 各 starter `linkages` 声明的 source 集合（**数量 + 路径双一致**，无多余、无缺失）

## 输出格式要求

按以下顺序输出，每段用一级标题分隔（既可直接落盘到外部清单目录，也可分别复制到管理后台对应草稿页发布）：

0. `## 候选清单`（必须给出：模块/能力 → 子模块 `<artifactId>` → **清单 id（= artifactId）** → GAV → 判定依据 → **必选标记与依据** → **依赖的能力（requires，注明是"和"还是"或"）**，供人工复核）
1. `## metadata`
2. `## starter`（多文档）
3. `## conflict-rule`
4. `## template`（多文档）

## 平台行为约定（影响产出写法）

- **两条生效路径（任选其一）**：
  1. **外部清单目录（推荐给运维）**：把 `metadata.yml`、`starters/*.yml`、`conflict-rules.yml`、`templates/**` 直接放进清单目录
     （配置项 `initializer.catalog.dir`，未配置时默认 **jar 同级的 `catalog/`**），**重启即生效**——
     不需要在管理端保存草稿、不需要点发布、不与内置 metadata.yml 做一致性校验，格式正确即可用
  2. **管理端发布（热生效）**：发布后快照原子切换，生成接口立即可用；重启服务时会从数据库还原最近一次发布，无需重新发布
  （两者同时存在时，**外部清单目录优先级更高**；管理端再次发布会覆盖内存快照）
- **`.ftl` 与配置文件均可在管理端维护**：发布后覆盖 classpath 同名模板/片段，新增或修改模板不需要改代码、不需要重新打 jar
- **配置文件必须 `%24` 转义 `$`**：否则发布期 FreeMarker 预校验拒绝（详见第四节）
- **必选项（`required: true`）双重保障**：前端默认勾选且禁止取消，后端生成时兜底自动补齐，绕过前端直调 API 也会带上；仅当该 starter 属于当前 Boot 版本可选清单时才强制
- **依赖联动（`requires`）不再自动带上**：前端只提示「需要 X 或 Y」、**不默认勾选、不锁定**依赖引入项（用户可自由取消，A 依赖 B 或 C 时只勾一个即可，B/C 互斥也不会被强制同选）；未满足时前端告警，后端生成期以 `2004 缺少必需的依赖 starter` 兜底拒绝；仅当依赖属于当前 Boot 版本可选清单时才参与校验
- **依赖图约束**：`requires` 悬空引用 / 自引用会在**发布期**直接拒绝；成环仅在**分组为单选项（强依赖）**时拒绝——多选项分组可通过换分支打破环，平台不判环（不会等到生成才报错）
- **首发必须带 metadata**：空 metadata 发布会被拒（报 `metadata 配置缺失`），四段配置中 metadata 必须先有内容

## 输出前自检

- [ ] **模块识别按特征而非命名**：每个 starter 都有第〇步特征依据，候选清单无遗漏
- [ ] **父子结构 GAV 取自实际承载能力的子模块**，未误用根 POM 或中间父 POM 坐标
- [ ] **每个 starter 的 `id` 就是该能力模块的 `<artifactId>` 原文**：未缩写、未翻译、未改命名风格、未自造；在框架仓库里能直接 grep 到对应模块
- [ ] **同一 id 全文字面一致**：清单 `id` ↔ `metadata.bootVersions[].starters` ↔ `requires` ↔ `conflict-rule.starters` ↔ 模板 `name` 的 `starters/{id}/` 路径，五处逐字相同
- [ ] **BOM/dependencyManagement 管理的依赖未写 version**（内部 starter 仅在版本自持时写）
- [ ] **框架统一父 POM 已填入各 `bootVersions[].frameworkParent`**（或顶层声明全局默认 / 明确说明框架无统一父 POM），三项齐全
- [ ] 兼容矩阵闭环：Java → Boot → starter 引用无悬空 id
- [ ] 占位符仅在白名单六项内
- [ ] 冲突规则 id 唯一，starters 数量符合 type（pair=2，group≥2）
- [ ] **① starter 单一数据源 + 版本关联**：每个 starter 只在第二节清单声明（坐标/展示名/必选/联动），metadata 未出现 `starters:` 节；`bootVersions[].starters` 的每个 id 都有清单条目且关联本 Boot 版本；**同一 Boot 版本内无重复 id**（多 Boot 线共用 id 时已用 `bootVersions` 拆成多条文档）
- [ ] **② 冲突规则引用不越界**：`rules[].starters` ⊆ 清单已声明的 starter id 且逐字照抄 artifactId，无官方 id 凑数
- [ ] **③ 模板与声明严格对齐**：`template` 的 `name` 集合 == 各 starter `linkages` 声明的 source 集合（数量 + 路径双一致）
- [ ] **互斥判定无误判**：每条冲突规则都有「同名 Bean / 同类型 Bean」依据，且 `message` 写明 Bean 名或类型 + 源码位置；不存在"仅共用底层资源"却被判为冲突的规则
- [ ] **必选项保守且有依据**：标了 `required: true` 的 starter 都能指出核心包/缺了起不来/父 POM 直接依赖之一的证据，并已在候选清单注明；不存在"只是推荐搭配"却被标成必选项（必选无法取消，宁少勿多）
- [ ] **依赖联动闭环**：`requires` 引用的 id 均已登记（无悬空）、无自引用、单选项分组无循环；方向为「上层能力 → 基础能力」；不存在"通常一起用"的伪依赖
- [ ] **`requires` 的"和/或"表达正确**：确为二选一/互斥的能力写成了单分组 `[[a, b]]`，而非拆成 `[[a], [b]]`（后者含义是"两个都要"）
- [ ] **配置文件模板（`application-*.yml`）中的 `$` 已全部写成 `%24`**，无裸写 `${`
- [ ] YAML 可被 SnakeYAML SafeConstructor 解析（禁止锚点/自定义 tag/任意类实例化）
- [ ] 所有 GAV 坐标、抽象类 fqcn 均取自框架仓库真实代码，未编造
