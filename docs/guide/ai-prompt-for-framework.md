# AI Agent 指导提示词：分析框架项目并生成脚手架平台配置（v2）

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

对每个候选提取：GAV 坐标、版本、分类、功能描述、需要用户编写的配置类、需追加进 application.yml 的配置片段、可继承的抽象类。识别结果先列一张"候选清单"（模块/能力 → GAV → 判定依据），确认无遗漏再产出 YAML。

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
    starters: ["web", "your-starter-id"]   # 该 Boot 版本可选的 starter id 全集
starters:
  your-starter-id:                  # key 即 starter id
    label: "前端展示名"
    dependency:
      groupId: com.yourcompany
      artifactId: your-capability-module   # 取实际承载能力的模块 GAV，非命名推断
      # version: "1.2.0"            # 仅内部 starter 写；官方 starter / 由 BOM 管理的版本禁止写
```

规则：

- `bootVersions[].supportedJavaVersions` 只能引用已声明的 `javaVersions[].id`
- `bootVersions[].starters` 只能引用已声明的 `starters` key（官方 `web`/`data-redis`/`data-jpa`/`actuator` 等 id 固定为：web、data-jpa、data-redis、actuator）
- Java id 必须带引号写成字符串

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
    starters: [your-starter-a, your-starter-b]   # 必须是 starter 清单中存在的 id
    message: 两者均注册了 XxxManager，请二选一
```

四字段 `id` / `type` / `starters` / `message` 全部必填，缺一即发布失败。框架没有互斥关系时输出 `rules: []` 或说明"无冲突规则"。

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
      host: ${XXX_HOST:localhost}   # 这是 Spring 环境变量占位，该文件不经过 FreeMarker 渲染
```

规则：

- `.ftl` 模板可用 FreeMarker 变量：`${group}` `${artifact}` `${bootVersion}` `${javaVersion}` `${configPackage}` `${packagePath}`
- `application-yml-fragments` 引用的片段**不要**用 `.ftl` 后缀，其 `${}` 由 Spring 环境变量解析，写 `${ENV:default}` 形式
- 配置类模板应引用框架中的集成抽象类（与 `abstract-classes[].fqcn` 对应）
- `name` 不得重复；模板必须能通过 FreeMarker 语法预校验（tag 闭合、指令合法）
- 第一节里 `starters` 的 key、第二节每个文档的 `id`、第三节 `starters` 列表、第四节 `source` 路径，五处引用必须互相一致

## 输出格式要求

按以下顺序输出，每段用一级标题分隔，便于我分别复制到管理后台对应草稿页：

0. `## 候选清单`（父子结构必须给出：模块/能力 → GAV → 判定依据，供人工复核）
1. `## metadata`
2. `## starter`（多文档）
3. `## conflict-rule`
4. `## template`（多文档）

## 输出前自检

- [ ] **模块识别按特征而非命名**：每个 starter 都有第〇步特征依据，候选清单无遗漏
- [ ] **父子结构 GAV 取自实际承载能力的子模块**，未误用根 POM 或中间父 POM 坐标
- [ ] **BOM/dependencyManagement 管理的依赖未写 version**（内部 starter 仅在版本自持时写）
- [ ] 兼容矩阵闭环：Java → Boot → starter 引用无悬空 id
- [ ] 占位符仅在白名单六项内
- [ ] 冲突规则 id 唯一，starters 数量符合 type（pair=2，group≥2）
- [ ] 模板 name 相对 templates/，与 starter yml 的 source 完全一致
- [ ] YAML 可被 SnakeYAML SafeConstructor 解析（禁止锚点/自定义 tag/任意类实例化）
- [ ] 所有 GAV 坐标、抽象类 fqcn 均取自框架仓库真实代码，未编造
