# AI Agent 指导提示词：分析框架项目并生成脚手架平台配置（v3）

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
  - **顺带记录框架父 POM**：那个被各子模块 `<parent>` 引用、或集中声明 `dependencyManagement` 的 POM，其 GAV + version 填入第一节 `frameworkParent`（生成的项目将继承它，由它统一管理 Boot BOM 与内部依赖版本）
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

对每个候选提取：GAV 坐标、版本、分类、功能描述、需要用户编写的配置类、需追加进 application.yml 的配置片段、可继承的抽象类、**是否必选**。识别结果先列一张"候选清单"（模块/能力 → GAV → 判定依据 → **是否必选 + 必选依据** → **依赖哪些能力（requires）**），确认无遗漏再产出 YAML。

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

产出时：先在候选清单标注每个能力是否必选**及依据**，再据此在 metadata 的 `starters.<id>` 上写 `required: true`（默认省略即为可选）。

### 依赖联动判定（`requires`）

`requires` 表达"**选了 A 就必须有 B**"：选中 A 时平台自动带上 B（按传递闭包），取消 A 后不再被其他选中项需要的 B 自动释放。
前端会把这类连带项标记为「由 A 引入」并在被依赖期间禁止取消，后端生成时同样求闭包补齐。

判定标准（须有代码依据，满足任一）：

1. **A 的代码直接引用 B**：A 的装配类/自动配置 import 了 B 的类型，或用了 `@ConditionalOnBean(B 的类型)` / `@ConditionalOnClass`
2. **A 的文档/注释明确声明依赖 B**（如"服务发现依赖 Feign 通信"，而 Feign 基座由 core 提供）
3. **A 离开 B 会运行时报错**：`NoClassDefFoundError` / `NoSuchBeanDefinitionException`

不应声明为依赖：

- 只是"通常一起用"，无代码级依赖 → 保持独立可选
- 依赖方向写反：B 用到 A，应写 `B.requires: [A]`，不是 `A.requires: [B]`
- 循环依赖（A→B 且 B→A）：平台发布期直接拒绝，正确做法是抽出公共基座 C，改为 `A.requires: [C]`、`B.requires: [C]`

**方向口诀：被依赖的基础能力写在后面**——`dal.requires: [core]`，不是 `core.requires: [dal]`。

### 版本与互斥

- 版本兼容：框架适配了哪些 Spring Boot 版本，这些 Boot 版本各支持哪些 Java 版本（多模块项目注意不同分支/模块可能面向不同 Boot 线，分别记录）
- 互斥识别：两个能力若注册同一类 Bean（缓存管理器、RPC 客户端、事务管理器等，通过装配类中的 `@Bean` / `@ConditionalOnMissingBean` 分析），生成冲突规则

## 第一节：元数据（kind = `metadata`）

```yaml
frameworkParent:                   # 可选：框架统一父 POM（生成项目 pom.xml 的 <parent>）
  groupId: com.yourcompany
  artifactId: your-framework-parent
  version: "1.0.0-SNAPSHOT"        # 支持 ${revision} 等 Maven 属性占位写法
# 不配置时生成器回退 org.springframework.boot:spring-boot-starter-parent:<bootVersion>
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
    starters: ["web", "your-starter-id"]   # 该 Boot 版本可选的 starter id 全集
starters:
  your-starter-id:                  # key 即 starter id
    label: "前端展示名"
    required: true                  # 可选：true = 必选依赖（默认 false）
    requires: [other-starter-id]    # 可选：依赖联动，选中本项自动带上所列 starter（默认 []）
    dependency:
      groupId: com.yourcompany
      artifactId: your-capability-module   # 取实际承载能力的模块 GAV，非命名推断
      # version: "1.2.0"            # 仅内部 starter 写；官方 starter / 由 BOM 管理的版本禁止写
```

规则：

- `bootVersions[].supportedJavaVersions` 只能引用已声明的 `javaVersions[].id`
- `bootVersions[].starters` **只能列出本框架实际提供的 starter id**（即 `starters:` 节的 key）。框架未提供官方 starter 时，不得出现 `web` / `data-jpa` / `data-redis` / `actuator` 等官方 id（这些 id 不是默认必填项，列了而框架没有就是错的）
- `bootVersions[].starters` 与 `starters:` 节的 key 必须**完全一致**（数量与 id 双向校验）：既不能有"清单列了但 starters 节未登记"的悬空 id（发布直接失败），也不能有"starters 节登记了但清单未列出"的遗漏 id（该 starter 将永远不出现在可选清单中）
- Java id 必须带引号写成字符串
- `starters.<id>.required`（可选，默认 false）：设为 `true` 即**必选依赖**——前端默认勾选且禁止取消，后端生成时兜底自动补齐（绕过前端直调 API 也会加上）。框架中"不选就跑不起来"的能力（如核心包 core、统一配置/鉴权基础模块）应标为必选；其余能力保持可选
- `starters.<id>.requires`（可选，默认 `[]`）：**依赖联动**——选中本 starter 时自动带上所列 starter（平台求传递闭包），取消后不再被其他选中项需要的连带项自动释放。校验规则：引用的 id 必须已在 `starters` 节登记（悬空即拒绝）、不得引用自身、不得成环（发布期报`循环依赖`并拒绝）。方向为「上层能力 → 基础能力」，如 `dal.requires: [core]`
- `frameworkParent`（**强烈建议填写**）：若框架仓库存在统一父 POM / BOM（聚合根 POM、`*-parent`、`*-dependencies`、或集中 `dependencyManagement` 的 POM），必须在此声明，生成的项目才继承该父 POM 并由它统一管理版本；三项（groupId/artifactId/version）必须齐全，缺一发布失败。确实没有统一父 POM 时整节省略，生成器回退官方 `spring-boot-starter-parent`
- 版本取自框架父 POM 真实 `<version>`（如 `3.0.0-SNAPSHOT`）；若父 POM 用 `${revision}` 之类的属性占位，照抄该占位写法即可

## 第二节：starter 清单（kind = `starter`，多文档 YAML）

一个能力一段文档，用 `---` 分隔，可合并为一份多文档 YAML 提交：

```yaml
---
id: your-starter-id                # 必须与文档名/metadata 中引用一致
category: middleware               # web / middleware / tool ...
maintainer: internal               # official / internal
description: 一句话描述功能
dependencies:
  - groupId: com.yourcompany
    artifactId: your-capability-module
    version: "1.2.0"               # official 或由 BOM 管理时可省略
linkages: {}                       # 无联动时写 {}
```

**内部能力（`maintainer: internal`）带联动时**，`linkages` 可含四类：

```yaml
linkages:
  config-package: "{basePackage}.config"          # 配置类生成到的包
  templates:
    - source: "starters/{starter-id}/XxxConfig.java.ftl"   # 相对 templates/ 目录
      target: "src/main/java/{packagePath}/config/XxxConfig.java"
  application-yml-fragments:
    - source: "starters/{starter-id}/application-xxx.yml"
      merge-strategy: append       # 目前仅支持 append
  abstract-classes:
    - fqcn: "com.yourcompany.xxx.XxxSupport"      # 框架中的集成抽象类全限定名
      alias: xxxSupport
```

**占位符白名单（强制）**：yml 中只允许 `{basePackage}` `{packagePath}` `{artifact}` `{group}` `{bootVersion}` `{javaVersion}` 这六个，出现其他占位符发布时直接拒绝。

## 第三节：冲突规则（kind = `conflict-rule`）

```yaml
rules:
  - id: your-a-vs-your-b            # 发布清单内唯一
    type: pair                      # pair（恰好 2 个）或 group（≥2 个，组内互斥）
    starters: [your-starter-a, your-starter-b]   # 只能引用 metadata 已声明的 starter id
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

- `rules[].starters` 只能引用**第一节 metadata 已声明的 starter id**，禁止引用未声明的官方 id（`web`/`data-jpa`/`data-redis`/`actuator` 等）凑数；引用未声明 id 发布即失败
- 每条规则必须有第 1/2 条判定依据（同名或同类型 Bean），并在 `message` 中给出 Bean 名/类型 + 源码位置
- 纯"共用底层资源"的组合不产出规则，可在候选清单里标注为依赖关系即可

## 第四节：生成模板（kind = `template`，多文档 YAML）

每份模板一个文档，`name` 为相对 `templates/` 目录的路径，`content` 为模板全文（用块标量承载）：

```yaml
name: starters/your-starter-id/YourConfig.java.ftl
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
name: starters/your-starter-id/application-xxx.yml
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

### 四处引用一致性（拆分为三条独立约束）

1. **metadata 内部双向一致**：`starters:` 节的 key ↔ `bootVersions[].starters`，数量与 id 必须完全相等
2. **冲突规则引用不越界**：`conflict-rule` 的 `rules[].starters` ⊆ metadata 已声明的 starter id
3. **模板与声明严格对齐**：`template` 的 `name` 集合 == 各 starter `linkages` 声明的 source 集合（**数量 + 路径双一致**，无多余、无缺失）

## 输出格式要求

按以下顺序输出，每段用一级标题分隔，便于我分别复制到管理后台对应草稿页：

0. `## 候选清单`（父子结构必须给出：模块/能力 → GAV → 判定依据 → **必选标记与依据** → **依赖的能力（requires）**，供人工复核）
1. `## metadata`
2. `## starter`（多文档）
3. `## conflict-rule`
4. `## template`（多文档）

## 平台行为约定（影响产出写法）

- **发布即热生效**：管理端发布后快照原子切换，生成接口立即可用；重启服务时会从数据库还原最近一次发布，无需重新发布
- **`.ftl` 与配置文件均可在管理端维护**：发布后覆盖 classpath 同名模板/片段，新增或修改模板不需要改代码、不需要重新打 jar
- **配置文件必须 `%24` 转义 `$`**：否则发布期 FreeMarker 预校验拒绝（详见第四节）
- **必选项（`required: true`）双重保障**：前端默认勾选且禁止取消，后端生成时兜底自动补齐，绕过前端直调 API 也会带上；仅当该 starter 属于当前 Boot 版本可选清单时才强制
- **依赖联动（`requires`）双重保障**：前端选中即自动带上依赖并标记「由 X 引入」（被依赖期间禁止取消，取消依赖方后自动释放），后端生成时同样求闭包补齐；仅当依赖属于当前 Boot 版本可选清单时才生效
- **依赖图必须是有向无环图**：`requires` 悬空引用 / 自引用 / 成环会在**发布期**直接拒绝（不会等到生成才报错）
- **首发必须带 metadata**：空 metadata 发布会被拒（报 `metadata 配置缺失`），四段配置中 metadata 必须先有内容

## 输出前自检

- [ ] **模块识别按特征而非命名**：每个 starter 都有第〇步特征依据，候选清单无遗漏
- [ ] **父子结构 GAV 取自实际承载能力的子模块**，未误用根 POM 或中间父 POM 坐标
- [ ] **BOM/dependencyManagement 管理的依赖未写 version**（内部 starter 仅在版本自持时写）
- [ ] **框架统一父 POM 已填入 `frameworkParent`**（或明确说明框架无统一父 POM），三项齐全
- [ ] 兼容矩阵闭环：Java → Boot → starter 引用无悬空 id
- [ ] 占位符仅在白名单六项内
- [ ] 冲突规则 id 唯一，starters 数量符合 type（pair=2，group≥2）
- [ ] **① metadata 内部双向一致**：`starters:` 节 key ↔ `bootVersions[].starters`，数量与 id 完全相等
- [ ] **② 冲突规则引用不越界**：`rules[].starters` ⊆ metadata 已声明的 starter id，无官方 id 凑数
- [ ] **③ 模板与声明严格对齐**：`template` 的 `name` 集合 == 各 starter `linkages` 声明的 source 集合（数量 + 路径双一致）
- [ ] **互斥判定无误判**：每条冲突规则都有「同名 Bean / 同类型 Bean」依据，且 `message` 写明 Bean 名或类型 + 源码位置；不存在"仅共用底层资源"却被判为冲突的规则
- [ ] **必选项保守且有依据**：标了 `required: true` 的 starter 都能指出核心包/缺了起不来/父 POM 直接依赖之一的证据，并已在候选清单注明；不存在"只是推荐搭配"却被标成必选项（必选无法取消，宁少勿多）
- [ ] **依赖联动闭环**：`requires` 引用的 id 均已登记（无悬空）、无自引用、无循环；方向为「上层能力 → 基础能力」；不存在"通常一起用"的伪依赖
- [ ] **配置文件模板（`application-*.yml`）中的 `$` 已全部写成 `%24`**，无裸写 `${`
- [ ] YAML 可被 SnakeYAML SafeConstructor 解析（禁止锚点/自定义 tag/任意类实例化）
- [ ] 所有 GAV 坐标、抽象类 fqcn 均取自框架仓库真实代码，未编造
