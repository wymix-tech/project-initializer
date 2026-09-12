# 配置驱动完整教程

本教程面向需要维护版本清单、接入 starter、调整校验规则或通过管理后台热更新配置的同学。读完即可**零代码**扩展平台能力。

平台所有可变部分均由配置驱动，代码只在新增"能力类型"时才需要改动。配置分四块：

| 配置 | 位置 | 作用 |
|---|---|---|
| `metadata.yml` | `initializer-core/src/main/resources/catalog/` | Java / Boot 版本、兼容矩阵、starter 选择约束（可选） |
| `starters/*.yml` | `catalog/starters/` | 单 starter 描述：**坐标 + 展示名的唯一数据源** + 联动规则 |
| `validation.yml` | `catalog/` | Group / Artifact / Package 命名校验规则 |
| 管理后台草稿 | 数据库（H2 文件，可换 JDBC 数据源） | 在线编辑以上配置，发布后热生效 |

> **部署推荐**：清单配置文件不必放在 classpath 内。把上述 yml 放到**外部清单目录**即可生效
> （配置项 `initializer.catalog.dir`，未配置时默认取 **jar 所在目录下的 `catalog/`**），
> 详见 [0. 外部清单目录](#0-外部清单目录推荐部署方式)。

---

## 0. 外部清单目录（推荐部署方式）

启动时从 jar 外的目录装载清单，改完重启即生效，无需重新打包、无需在管理后台发布。

```
catalog/                              # 目录位置：initializer.catalog.dir，默认 <jar 所在目录>/catalog
├── metadata.yml                      # 版本 + 兼容矩阵 + starter 坐标（可选）
├── validation.yml                    # 命名校验规则（可选）
├── conflict-rules.yml                # 依赖冲突规则（可选）
├── starters/*.yml                    # starter 清单（可选；可递归子目录，单文件支持多文档）
└── templates/**                      # 模板覆盖（可选；键为相对 templates/ 的路径，未覆盖走内置模板）
```

- **目录定位**：`initializer.catalog.dir=<绝对路径/相对路径/~/路径>`；留空时用 jar 所在目录下的 `catalog/`。
- **装载口径**：格式正确即生效，**不做与内置 `metadata.yml` 的一致性校验**；`starters/` 里的条目
  不必在 `metadata.yml` 的 `starters` 节登记（自动补位为全部 Boot 版本可选）。
- **容错**：单个文件格式非法只跳过该文件并打印告警日志，不阻断启动；目录不存在则回退内置清单 / 管理端发布配置。
- **优先级**：外部清单目录 >（`--demo`）classpath 内置清单 > 数据库最新已发布快照。

---

## 1. 目录结构总览

```
initializer-core/src/main/resources/
├── catalog/                          # 配置驱动清单（classpath 根）
│   ├── metadata.yml                  # 版本 + 兼容矩阵 + starter 坐标
│   ├── validation.yml                # 元信息校验规则
│   └── starters/                     # 一 starter 一 yml
│       ├── web-starter.yml
│       └── xxx-redis-spring-boot-starter.yml
└── templates/                        # FreeMarker 生成模板
    ├── pom.xml.ftl / build.gradle.ftl / settings.gradle.ftl
    ├── Application.java.ftl / application.yml.ftl / README.md.ftl
    └── starters/xxx-redis/           # starter 联动模板
        ├── RedisConfig.java.ftl
        └── application-redis.yml
```

---

## 2. metadata.yml：版本清单与兼容矩阵

三个关键节，联动关系为：`javaVersions` →（`bootVersions.supportedJavaVersions` 反向引用）→ `bootVersions.starters` →（`starters` 坐标映射）。

### 2.1 javaVersions

```yaml
javaVersions:
  - id: "17"            # 对外 ID，兼容矩阵引用此值，请用字符串
    label: "Java 17"    # 前端展示名
    deprecated: false   # true 时前端标记为弃用
    recommendedBootVersion: "3.2.5"   # 前端默认推荐的 Boot 版本
```

### 2.2 bootVersions

```yaml
bootVersions:
  - id: "3.2.5"
    label: "Spring Boot 3.2.5"
    official: true      # false = 公司定制版
    supportedJavaVersions: ["17", "21"]   # 兼容矩阵：决定联动过滤
    starters: ["web", "xxx-redis-spring-boot-starter"]  # 该 Boot 版本可选的 starter
    frameworkParent:    # 可选。该版本生成项目挂接的框架父 POM（不同 Boot 版本可不同）
      groupId: com.yourcompany
      artifactId: your-framework-parent
      version: "1.0.0-SNAPSHOT"   # 支持 ${revision} 等 Maven 属性占位写法
```

**联动规则**：选择 Java 8 → 仅显示 `supportedJavaVersions` 包含 `"8"` 的 Boot 版本；选定 Boot 版本 → 仅显示其 `starters` 列表中的 starter。

**框架父 POM（`frameworkParent`，可选）**：写入生成项目 `pom.xml` 的 `<parent>`。
声明位置为每个 `bootVersions[]` 条目内（不同 Boot 版本可挂不同父 POM）；也可在 metadata.yml 顶层声明一次作为全局默认，条目内未声明时继承。
三项（groupId/artifactId/version）任缺其一即装载失败；整节省略时回退官方 `org.springframework.boot:spring-boot-starter-parent:<bootVersion>`。

### 2.3 starters 节已移除（必选/联动迁至 starter 清单）

`metadata.yml` **不再维护 `starters` 节**。starter 的展示名、依赖坐标、**是否必选（`required`）**
与**依赖联动（`requires`）**全部在 `catalog/starters/*.yml` 里声明（见第 3 节）：
平台在装载/发布时通过 `MetadataCatalog.merge(StarterCatalog)` 自动联动合并，
`bootVersions[].starters` 省略或为空时该版本对**全部清单条目**可选。

```yaml
# catalog/starters/framework-core.yml
id: framework-core
category: tool
maintainer: internal
description: 框架核心（必选）
required: true            # 必选：前端默认勾选且禁止取消，生成时后端兜底补齐
hidden: false             # 可选。true = 前端依赖选择列表默认隐藏，被已选组件依赖时强制展示
requires: []              # 依赖联动（AND-of-OR-groups）：单元素分组为强制依赖（自动选中并锁定），
                          #   多元素分组为 OR（仅提示不自动勾选）；详见 3.5
dependencies:
  - groupId: com.company
    artifactId: framework-core
    version: "1.0.0"
linkages: {}
```

- `required` / `requires` 的悬空引用、自引用、成环由 **starter 清单装载期**校验（9002）。
- `bootVersions[].starters` 保留，用于声明「该 Boot 版本可引用哪些 starter」；列出的 id 必须在清单中存在，否则不可见。
- **Boot 版本关联（`bootVersions` 字段）**：清单条目可声明自己属于哪些 Boot 版本（值取 metadata 的 `bootVersions[].id`），
  不声明即对全部版本可用。**同一 Boot 版本内 starter ID 不允许重复（重复即 9002），不同 Boot 版本之间允许重复**——
  多 Boot 线（如 2.x / 3.x）共用同一个 id 时，写成多条文档各自声明 `bootVersions`，平台按用户选中的 Boot 版本取对应条目
  （依赖坐标、version、联动规则一并生效）：

```yaml
# catalog/starters/xxx-redis-spring-boot-starter.yml（多文档）
id: xxx-redis-spring-boot-starter
bootVersions: ["2.7.18"]        # 本条只在 2.x 生效
category: middleware
maintainer: internal
description: 内部 Redis 封装 starter（2.x 线）
dependencies:
  - groupId: com.company
    artifactId: xxx-redis-spring-boot-starter
    version: "1.1.0"
linkages: {}
---
id: xxx-redis-spring-boot-starter
bootVersions: ["3.2.5"]         # 同名 id 在 3.x 线另一份坐标
category: middleware
maintainer: internal
description: 内部 Redis 封装 starter（3.x 线）
dependencies:
  - groupId: com.company
    artifactId: xxx-redis-spring-boot-starter
    version: "2.0.0"
linkages: {}
```
- 声明了 `version` 的依赖会显式输出到生成项目的 pom/gradle（内部 starter 不在 Boot BOM 管理范围时必填）。
- 官方 starter 无需 `version`（由 Boot parent BOM 管理）。

**新增一个 Boot 版本只需**：在 `bootVersions` 追加一项，填好兼容矩阵与 starter 列表。

---

## 3. starters/*.yml：新增 starter（核心场景）

单个 starter 一个描述文件，含**依赖坐标 + 联动规则**。文件名即 starter id（保持一致便于追溯）。

### 3.1 最简形态（无联动，如官方 starter）

```yaml
id: web
category: web            # 前端分类浏览：web / middleware / tool ...
maintainer: official     # official / internal
description: Spring MVC + 内嵌 Tomcat
dependencies:
  - groupId: org.springframework.boot
    artifactId: spring-boot-starter-web
linkages: {}             # 无联动规则
```

### 3.2 带联动（内部 starter 全量示例）

```yaml
id: xxx-redis-spring-boot-starter
category: middleware
maintainer: internal
description: 内部 Redis 封装 starter（含集成抽象类 RedisSupport）
dependencies:
  - groupId: com.company
    artifactId: xxx-redis-spring-boot-starter
    version: "1.2.0"       # 内部 starter 显式声明版本
linkages:
  config-package: "{basePackage}.config"     # 配置类生成包名，占位符替换
  templates:                                  # 联动生成的代码文件
    - source: "starters/xxx-redis/RedisConfig.java.ftl"
      target: "src/main/java/{packagePath}/config/RedisConfig.java"
  application-yml-fragments:                  # application.yml 配置片段
    - source: "starters/xxx-redis/application-redis.yml"
      merge-strategy: append
  abstract-classes:                           # 供用户业务代码继承的抽象类
    - fqcn: "com.company.redis.RedisSupport"
      alias: redisSupport
```

**linkages 字段说明**：

| 字段 | 说明 |
|---|---|
| `config-package` | 联动生成的配置类所在包，`{basePackage}` 占位符替换为用户填写的 Package name |
| `templates[].source` | 模板相对 `templates/` 目录的路径（FreeMarker `.ftl` 或纯 yml） |
| `templates[].target` | 生成项目内的目标路径，`{packagePath}` 替换为包名转路径（`com.example.demo` → `com/example/demo`） |
| `application-yml-fragments[].merge-strategy` | `append`：片段追加进生成项目的 `application.yml` |
| `abstract-classes[]` | 生成代码中引用的集成抽象类全限定名，前端展示别名 `alias` |

### 3.3 模板编写

联动模板支持 FreeMarker 语法，可使用生成上下文变量：

- `${group}` / `${artifact}` / `${bootVersion}` / `${javaVersion}`：用户输入与所选版本
- `${configPackage}` / `${packagePath}`：来自 linkages 或包名推导
- 示例（`RedisConfig.java.ftl`）：

```java
package ${configPackage};

import com.company.redis.RedisSupport;
// ...
@Configuration
public class RedisConfig {
    @Bean
    public RedisSupport redisSupport() {
        return new RedisSupport() {};
    }
}
```

配置片段（`application-redis.yml`）支持环境变量占位：

```yaml
spring:
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
```

### 3.4 完整接入步骤（新增 starter）

1. 新建 `catalog/starters/{starter-id}.yml`，按 3.2 编写
2. （如有联动）在 `templates/` 下创建对应模板/片段文件
3. 在目标 `bootVersions[].starters` 列表中加入该 starter id（省略即该版本对全部清单条目可选）
4. 发布（文件方式直接重启生效；管理后台方式见第 5 节，无需重启）

**生成器代码零改动。**

### 3.5 starter 清单字段与依赖联动语义

**顶层字段一览**：

| 字段 | 必填 | 说明 |
|---|---|---|
| `id` | 是 | starter 唯一标识，与 `metadata.yml` 的 `bootVersions[].starters` 引用对齐 |
| `category` | 是 | 分类：`web` / `data` / `middleware` / `tool` |
| `maintainer` | 是 | 维护方：`official` / `internal` |
| `description` | 否 | 展示名与检索关键字，缺省取 `id` |
| `hidden` | 否 | `true` 时前端依赖选择列表默认隐藏（默认 `false`） |
| `required` | 否 | `true` 时前端默认勾选且禁止取消，生成时后端兜底补齐 |
| `requires` | 否 | 依赖联动分组（AND-of-OR-groups，见下） |
| `bootVersions` | 否 | 关联的 Boot 版本 id 清单，缺省对所有版本可用 |
| `dependencies` | 是 | 写入构建脚本的依赖坐标清单 |
| `linkages` | 是 | 联动规则，无规则写 `{}` |

**`hidden`：前端依赖列表隐藏**

- `hidden: true` 的条目在**前端依赖选择列表**中默认不展示。
- 后端检索接口 `GET /api/metadata/starters` **仍正常返回**该条目，且照常参与依赖、互斥与生成校验——
  隐藏仅影响前端展示，不会因隐藏而漏校验。
- 若隐藏条目被**已选中组件**的 `requires` 引用，前端会**强制展示**它并可见其选中状态。

**`requires`：依赖联动与前端行为**

`requires` 为 **AND-of-OR-groups**：数组元素是分组，分组之间取 AND，分组内取 OR。

```yaml
requires: []              # 无依赖
requires: [b, c]          # 扁平写法（历史语义）：需要 b 和 c，等价于 [[b], [c]]
requires: [[b, c]]        # 需要 b 或 c（OR 分组）
requires: [[b, c], d]     # 需要（b 或 c）且 d
```

- **单元素分组（无 OR）= 强制依赖**：选中该组件时，其依赖**默认选中且不可取消**（行内标「依赖锁定」、勾选框禁用）；
  取消父组件后，若该依赖不再被其他已选组件需要，则**自动取消并解锁**（随父组件联动）。
- **多元素分组（OR）**：前端**不自动勾选**；该分组未被满足时，组内各候选条目整行淡黄底 + id 前红色 `*` 高亮提示；
  命中分组内任一项即满足该分组。
- **互斥例外**：当强制依赖与已选组件（同组依赖或用户已选项）命中冲突规则（`conflict-rules.yml`）时，
  互斥双方都**不锁定**，前端以整行右侧橙色竖线标记互斥（悬停整行可看互斥对象与规则文案），并默认只自动选中其中一方；
  用户可随时在二者间**二选一切换**（选中一方自动取消另一方）。
- 列表**行内不再展示**「需 X」「互斥：X」等依赖文案（仅保留上述视觉标记 + 底部未满足告警）。
- 生成期仍由后端按 `requires` 与冲突规则做最终校验，前端联动仅为即时引导。

---

## 4. validation.yml：命名校验规则

```yaml
rules:
  group:
    pattern: "^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"   # 正则
    tooShortMessage: "groupId 至少需要两段，如 com.example"
    defaultMessage: "groupId 不符合 Maven 规范：..."
```

- 三个字段：`group` / `artifact` / `package`，均支持 `pattern` + 两条错误消息
- `pattern` 是 Java 正则（yml 中注意 `\.` 转义写法）
- 即时校验走 `GET /api/validate?field=group&value=...`，生成前走 POST `/api/generate` 全量校验

---

## 5. 管理后台：在线维护与热生效（M3）

无需改文件重启，直接通过 REST API 在线维护配置。流程：**草稿 → 发布 → 热生效**，刷新失败自动回滚旧配置。

### 5.1 鉴权

所有 `/api/admin/**` 请求需带请求头 `X-Admin-Token`，token 来自环境变量 `INITIALIZER_ADMIN_TOKEN`（默认 `admin-dev-token`，仅限开发，生产必须覆盖，启动时默认 token 会输出 WARNING）。

### 5.2 API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/config/{kind}` | 读取草稿（`kind` = `metadata` / `starter` / `template` / `conflict-rule` / `starter-sync`） |
| PUT | `/api/admin/config/{kind}` | 保存草稿（乐观锁，可带 `If-Match: {version}`） |
| POST | `/api/admin/config/publish` | 发布：全量校验 → 快照构建 → 原子切换 |
| POST | `/api/admin/releases/{releaseId}/rollback` | 回滚到指定历史发布 |
| POST | `/api/admin/sync` | 手动触发制品库同步 |
| GET | `/api/admin/releases` | 发布历史 |
| GET | `/api/admin/audits` | 审计日志 |

### 5.3 典型操作：在线新增 starter

```bash
# 1. 读取当前 starter 草稿（version 用于乐观锁）
curl -H "X-Admin-Token: $TOKEN" http://localhost:8080/api/admin/config/starter

# 2. 保存草稿（contentYaml 为完整配置内容）
curl -X PUT -H "X-Admin-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"contentYaml": "<完整 starter yml>", "operator": "alice"}' \
  http://localhost:8080/api/admin/config/starter

# 3. 发布（全量校验 + 原子切换，失败自动回滚旧快照）
curl -X POST -H "X-Admin-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"operator": "alice", "remark": "接入 xxx-oss starter"}' \
  http://localhost:8080/api/admin/config/publish
```

发布后快照热生效，无需重启；GET 草稿返回的 `version` 在并发编辑时通过 `If-Match` 乐观锁防止覆盖。

### 5.4 制品库自动同步（M3.3）

配置环境变量后，可从内部制品库自动同步 starter 清单：

| 环境变量 | 说明 |
|---|---|
| `INITIALIZER_SYNC_REMOTE_ENDPOINT` | 制品库 remote 接口地址（未配置时仅 Mock 通道生效） |
| `INITIALIZER_SYNC_REMOTE_TOKEN` | remote 接口鉴权 token |
| `INITIALIZER_SYNC_SCHEDULE_ENABLED` | 定时同步开关（默认 `false`） |
| `INITIALIZER_SYNC_SCHEDULE_DELAY` | 同步间隔毫秒数（默认 `300000`） |

同步行为：拉取 → 合并（**同名条目手工配置优先**）→ 同步层草稿（`starter-sync` kind，只读贡献者，不触碰手工草稿）→ 快照增量重建热生效。

---

### 5.5 starter 配置引导向导（傻瓜化接入）

不想手写 yml？管理后台 starter 草稿页提供「向导式接入」模式，4 步表单填完即可写草稿/发布：

1. **基本信息**：starter id、分类、维护方(official/internal)、描述——id 即时校验命名规范与草稿查重
2. **依赖坐标**：GAV 坐标清单（可多条）；官方 starter version 留空（走 Boot BOM），内部 starter 显式声明 version
3. **联动规则（可选）**：config-package、模板(source→target)、application.yml 片段、集成抽象类；占位符仅允许 `{basePackage}` `{packagePath}` `{artifact}` `{group}` `{bootVersion}` `{javaVersion}`，越界即时提示
4. **预览与发布**：实时预览生成的 starter yml → 写入草稿（带乐观锁 `If-Match`，追加为多文档）→ 发布（全量校验 + 热切换）

**后端零改动**：向导复用 `PUT /api/admin/config/starter` + `POST /api/admin/config/publish`，生成的 yml 与 `catalog/starters/*.yml` 同构，直接追加进 starter 草稿。高级用户可随时切回「YAML 直编」模式。

## 6. 升级说明：YAML 文本 → 结构化关系表（T1）

> 本节对应「配置结构化入库」改造的第一阶段（T1，持久层）。**上线前必读**，尤其是已在生产
> 发布过配置的环境。

### 6.1 存储模型变化

`config_draft.content_yaml` / `config_release.*_yaml` 的「YAML 文本一列存到底」模型已替换为
**关系表 + 版本作用域**模型：

| 旧模型 | 新模型 |
|---|---|
| `config_draft(id, kind, content_yaml, version, …)` | `config_draft_meta(id, kind, version, …)` 只存 kind 级乐观锁；正文按 kind 拆到 15 张 `cfg_*` 表 |
| `config_release(id, metadata_yaml, starter_yaml, template_yaml, conflict_yaml, …)` | `config_release(id, released_by, released_at, remark)` 只存发布头；正文在 `cfg_*` 表按 `release_id` 隔离 |
| 草稿与快照靠「不同表」区分 | 草稿与快照**同表**，靠 `release_id` 区分 |

**版本作用域约定**（理解这张表就理解了全部数据）：

| `release_id` | 含义 | 可写 |
|---|---|---|
| `0` | 当前草稿工作区（管理端编辑的就是它） | 是 |
| `> 0` | 某次发布的不可变快照（值等于 `config_release.id`） | 否（发布时整体复制生成） |

**所有 `cfg_*` 表的唯一约束都带 `release_id`**。这是硬约束：漏一处，发布复制行为新
`release_id` 时就会撞上草稿行，出现「发布静默改写草稿」——这也是 T1 全部 DDL 都要过一遍
`UNIQUE (release_id, …)` 复核的原因。

### 6.2 关系表清单（15 张）

| 分组 | 表 | 说明 |
|---|---|---|
| 元数据 | `cfg_java_version` | Java 版本 |
| | `cfg_boot_version` | Boot 版本（含 `fp_group`/`fp_artifact`/`fp_version` 框架父 POM） |
| | `cfg_boot_java` | Boot ↔ Java 兼容矩阵（存 **Java 版本业务 ID**，非主键） |
| | `cfg_boot_starter_ref` | Boot 版本可选 starter 白名单 |
| starter | `cfg_starter` | starter 主行（`required` 必选、`hidden` 前端默认隐藏；`source` = `MANUAL` / `SYNC`） |
| | `cfg_starter_dependency` | 依赖坐标 GAV |
| | `cfg_starter_require_group` | 依赖联动 OR 分组（AND-of-OR 第一层） |
| | `cfg_starter_require_item` | 联动组内项（AND-of-OR 第二层） |
| | `cfg_starter_boot_version` | starter ↔ Boot 版本关联 |
| | `cfg_starter_linkage_template` | `linkages.templates` |
| | `cfg_starter_linkage_yml` | `linkages.application-yml-fragments` |
| | `cfg_starter_linkage_abstract` | `linkages.abstract-classes` |
| 冲突规则 | `cfg_conflict_rule` | 规则主行（`rule_type` = `PAIR` / `GROUP`） |
| | `cfg_conflict_rule_starter` | 规则涉及的 starter 多选 |
| 模板 | `cfg_template` | 模板名 + 全文（`content` 为 CLOB，列表查询不取） |

另有 `config_draft_meta`（草稿种类乐观锁）、`config_release`（发布头）、`config_audit`（审计）。

> `cfg_boot_java` 存 Java **业务 ID** 而非主键是刻意设计：草稿态必须能把「引用了拼错/尚未
> 添加的 Java 版本」原样存下来，发布期才能给出 `Boot 版本 X 引用了未定义的 Java 版本 Y`
> 的精确 4002 明细。若强绑主键，非法输入在保存阶段就会被丢弃，用户看不到问题所在。

**增量列升级（重要）**：`schema.sql` 建表用 `CREATE TABLE IF NOT EXISTS`，**已存在的库不会
因改列而重建**。因此新增列统一在 `schema.sql` 末尾的「增量升级（幂等）」段补
`ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...`，配置 `spring.sql.init.mode: always` 下
每次启动自动执行、可重复执行。目前该段含：

- `cfg_starter.hidden BOOLEAN NOT NULL DEFAULT FALSE`（T1 补丁：starter 清单 `hidden` 字段落库）

> 若你的数据源方言不支持 `ADD COLUMN IF NOT EXISTS`（如部分 MySQL 版本），请按
> 「先判断 information_schema 再 ALTER」的方式改写这一句；生产替换数据源时一并复核。

### 6.3 存量数据迁移

两条路径，按能否停机选择：

**路径 A｜导出 → 导入（推荐，有现网数据时）**

1. 用**旧版** jar 启动一次，逐个导出四类草稿全文：

   ```bash
   for k in metadata starter conflict-rule template; do
     curl -H "X-Admin-Token: $TOKEN" http://localhost:8080/api/admin/config/$k \
       | jq -r .contentYaml > legacy-$k.yml
   done
   ```

2. 升级到新版本，重启（首次启动会自动建表）。
3. 逐个回填：`PUT /api/admin/config/{kind}`，body 用上一步导出的 YAML。
4. 在管理端**发布一版**作为回滚基线。

**路径 B｜库内直迁（可停机窗口，旧 `config_draft` 表仍在时）**

用 `LegacyYamlMigration` 直接把旧表 YAML 转成结构行，无需人工导出导入：

```java
if (migration.legacyDraftTableExists()) {   // 旧表存在才需要迁
    migration.migrateFromLegacyDraftTable();
}
```

> **执行前务必备份数据库**。整体在单事务内执行，任一 kind 失败则全部回滚，
> 重复执行结果一致（按 kind 先清后写）。

**⚠️ 发布历史的回滚边界（重要）**

旧 `config_release` 的正文列在新 schema 中已移除；旧发布数据**没有行级作用域信息**，
无法逐行还原为「当时的历史快照」。因此：

- 历史发布记录**只保留头信息**（发布人 / 时间 / 备注）
- **可回滚的基线是「迁移后发布的版本」**，不是升级前的历史版本
- 若确需保留旧版本内容作为回滚点，请在升级前用旧版 jar 导出四类 YAML 并留档，
  需要时走「路径 A」回填后再发布

### 6.4 行为差异（升级后需知）

| 场景 | 差异 |
|---|---|
| `GET /api/admin/config/{kind}` 返回的 `contentYaml` | 语义等价，但**文本格式可能不同**（统一块风格、缩进 2 格、字符串引号按需添加）。按 YAML 语义比较，不要做文本 diff |
| 保存草稿 | 正文落结构行；**结构非法的 YAML 在保存阶段即被拒绝**（4002），不再等到发布期。图合法性（悬空引用、ID 重复）仍留到发布期校验，草稿可逐步补全 |
| 并发编辑 | 乐观锁版本仍在 `GET` 响应的 `version` 字段，`If-Match` 语义不变 |
| 发布 | 同一事务内：写发布头 → 复制草稿行为新 `release_id` → 切换快照；失败整体回滚，旧快照继续服务 |
| 回滚 | 读目标 `release_id` 的结构行重新校验后复制为新 release；历史快照零改写 |
| `starter-sync` | `ConfigKind.STARTER_SYNC` **保留**；正文改为 `cfg_starter.source = 'SYNC'` 的行，同名手工（`MANUAL`）优先语义不变 |
| starter `hidden` / `required` | 落 `cfg_starter` 列并在 `GET` 渲染回 YAML 时按需写回（`hidden` / `required` 仅 true 时输出，false 省略）；`hidden=false` 与未声明等价 |

## 7. 装配链路：表行直装快照（T2）

> 本节对应「配置结构化入库」改造的第二阶段（T2，快照装配）。

T2 起，**发布 / 回滚 / 启动装表**三条链路不再把表行渲染成 YAML 再解析，而是由
`StructuredCatalogAssembler` 直接「表行 → 核心模型 → 快照」装配：

```
发布：草稿(release_id=0) 结构行 ──readScope(1 次批量查 15 表)──▶ Assembler ──▶ CatalogSnapshot ──swap──▶ 生效
回滚：历史(release_id=N) 结构行 ──readScope──▶ Assembler（复校验）──▶ 复制为 release_id=N+1 + 头 + swap
启动：最新 release 结构行 ────────readScope──▶ Assembler ──▶ seed 快照
```

要点：

- **口径与 YAML 通道一致**：装配器复用 core 的装载期校验
  （`MetadataCatalog.of` / `StarterCatalog.of` / `ConflictRule` 构造 + 模板语法预校验），
  装配失败一律收敛为 **4002** 并给出精确明细（如 `Boot 版本 X 引用了未定义的 Java 版本 Y`）。
- **禁 N+1**：`ConfigRepository.readScope(releaseId)` 每表只发一条 `selectByReleaseId`，
  装配全程数据库交互恰为 1 次 `readScope`（15 条查询），其余在内存按父键分组。
  已有断言测试锁定「查询条数 ≤ 表数量」。
- **`hidden` 直取**：`cfg_starter.hidden` 由装配器直接读取，不经 YAML 反编译（PR #40 语义端到端）。
- **草稿态仍宽松**：草稿读写仍走 `StructuredYamlCodec` / `StructuredDraftWriter`（YAML 文本契约），
  允许悬空引用与重复 ID、可边补边存；严格校验只在发布/回滚/启动装表发生。
  这两个组件将在 T3（表单化 API）与 T4（ZIP 导入导出）后收缩为导入导出专用。
- **启动兜底**：非 `--demo` 且 DB 无发布记录（或最新作用域为空）时播种**空快照**，
  读路径始终可用；启动装配失败只告警不阻塞启动，管理端可重新发布修复。

模板映射（`CatalogSnapshot.templateOverrides`）语义在 T2 收敛为
**「数据库全量模板集」**；过渡期映射为空时 `TemplateRenderer` 仍保留 classpath 兜底，
该兜底在 T5（模板改为 DB 唯一数据源）一并摘除。

---

## 8. 管理端结构化 API：分模块表单化读写（T3）

> 本节对应「配置结构化入库」改造的第三阶段（T3，管理端结构化 API）。

T3 起，管理端提供**分模块结构化契约**，表单化前端直接读写 `cfg_*` 草稿行
（`release_id = 0`），不再需要「拼 YAML → 工具解析」；同时**保留** T1 的 YAML 契约
（`/api/admin/config/{kind}`）供导入通道（T4）与旧客户端使用。两套契约读写同一批结构行，
权威来源唯一，可混用。

### 8.1 端点

| 方法与路径 | 语义 | 请求/响应要点 |
|---|---|---|
| `GET /api/admin/metadata` | 读元数据草稿 | Java 版本 + Boot 版本（含兼容矩阵、starter 白名单、框架父 POM） |
| `PUT /api/admin/metadata` | 整体保存元数据草稿 | 全量提交 `javaVersions` / `bootVersions` |
| `GET /api/admin/starters` | 读手工 starter 清单草稿 | 含依赖 / requires 分组 / Boot 关联 / 联动；`hidden` / `required` 直落列 |
| `PUT /api/admin/starters` | 整体保存手工 starter 草稿 | 只写 `source = MANUAL` 行，同步层零改动 |
| `GET /api/admin/conflict-rules` | 读冲突规则草稿 | 规则 + 涉及 starter 多选 |
| `PUT /api/admin/conflict-rules` | 整体保存冲突规则草稿 | 类型仅 `pair` / `group` |
| `GET /api/admin/templates` | 模板**列表** | **只回 name / 更新时间 / 顺序，不含 `content` CLOB** |
| `GET /api/admin/templates/{name}` | 模板**详情** | 含 `content` 全文，按需单独取 |
| `PUT /api/admin/templates/{name}` | 单模板 upsert | 按 name 幂等，保留其余模板 |
| `PUT /api/admin/templates` | 模板集整体替换 | 全量提交 |
| `DELETE /api/admin/templates/{name}` | 单模板删除 | `?operator=` 必填，保留其余模板 |

- 鉴权仍为 `X-Admin-Token`；乐观锁仍为 `If-Match`（值为 `GET` 返回的版本）。
  结构化端点的保存响应为 `{kind, rows, version, summary}`（`summary` 只含规模，**不含模板全文**）。
- 结构化端点与 YAML 端点共享同一 `config_draft_meta.version`，两者版本可混用。

### 8.2 契约边界

- **整体提交**：`PUT` 语义是「提交该模块的完整结构」，写入按「先清该 kind 草稿行 → 整体写」实现，
  天然无孤儿行；空清单 = 清空该模块草稿。
- **草稿宽松 / 发布严格**：结构化保存只做字段级与结构级校验（必填、`pair/group` 取值、
  条目数 ≤ 512、结构行 ≤ 20000、单模板 ≤ 512KB）；跨模块引用（Boot ↔ Java、冲突规则 ↔ starter、
  requires 悬空）与图合法性仍统一在**发布期**收敛为 4002 明细，支持边补边存。
- **CLOB 隔离**：模板列表查询只取 `name/sort/updated_at`，不搬运 `content`；
  全文按需走 `GET /templates/{name}`。
- **审计**：结构化变更写 `UPDATE_STRUCTURED` 动作，明细记录规模
  （如 `starter 3 项 / 依赖 5 条 / 联动 2 条`、`模板 8 个`、`规则 2 条 / 涉及 starter 5 项`），
  **不打印模板全文与 token**（超长统一截断）。

### 8.3 制品库同步（T3 变更）

`CatalogSyncService` 的同步结果**直写结构化 starter 行**
（`cfg_starter` 等表、`source = SYNC`），不再拼 YAML：
拉取 → `CatalogMerger` 合并（保留集 = 手工 ID + 当前同步层 ID，同名手工优先）→
同步层草稿整体重建。`ConfigKind.STARTER_SYNC` 取值、审计动作（`SYNC` / `SYNC_FAILED`）、
「同名手工优先」语义均不变。

> **T4 起 schema 变更**：`cfg_starter` 的唯一约束由 `(release_id, starter_id)` 改为
> `(release_id, source, starter_id)`。原口径与「两通道并存」语义冲突——同一 release 作用域内
> 两个通道各持一条同名 starter 是合法状态（同名优先由发布期合并保留集保证，而非靠唯一约束互斥）。
> `schema.sql` 末尾有幂等 `ALTER`，旧库重启自动补齐。

---

## 9. 配置导入导出：ZIP 归档双向通道（T4）

> 本节对应「配置结构化入库」改造的第四阶段（T4，yaml-import-export）。

数据库与 YAML 文件之间的双向通道：**一键导出 ZIP 下载**、**上传 ZIP 解析入库**。
归档目录与原外部清单目录一致，因此「数据库导出 → 手工编辑 → 再导入」是自然的工作流，
也覆盖了历史 YAML 数据的迁移路径（旧版本导出 → 新版本导入）。

### 9.1 端点

| 方法与路径 | 语义 | 请求/响应要点 |
|---|---|---|
| `GET /api/admin/config/export` | 导出草稿为 ZIP 下载 | `Content-Disposition: attachment`，附件名 `initializer-config.zip` |
| `POST /api/admin/config/import?operator=<人>` | 上传 ZIP 解析入库 | `multipart/form-data`，文件字段名 `file`；可选 `If-Match` |

- 鉴权同其余管理端端点（`X-Admin-Token`）。
- **导入只写草稿**（`release_id = 0`），**不发布、不切换快照**；导入后仍需人工核对并 `POST /config/publish`。
- 导入响应：`{rows, versions, summary}`；`versions` 为四类 kind 的版本映射，
  `summary` 形如 `metadata[java=2,boot=1,bootJava=2,whitelist=2] starters[3,deps=5,requireItems=2] conflictRules[1] templates[8]`（**不含模板全文与 token**）。
- 导入成功写审计 `IMPORT`；失败写 `IMPORT_FAILED`（`noRollbackFor` 保证失败明细落库），草稿保持原状。

### 9.2 归档布局

```
initializer-config.zip
├── metadata.yml                    # 元数据：Java/Boot 版本、兼容矩阵、starter 白名单、框架父 POM
├── starters/<id>                   # 一 starter 一文件（内容即单文档 starter yml，含 hidden）
├── conflict-rules.yml              # 冲突规则（可缺省 = 空规则集）
└── templates/<相对路径>            # 模板全文（路径即模板名，可缺省 = 空模板集）
```

- 条目名不带 `.yml` 扩展名也一并接受（导出侧写的是 `starters/<id>`）。
- 归档未包含的节 = **空集语义**：`starters/` 缺失即清空手工 starter 清单草稿。
- **导出只含手工通道（`source = MANUAL`）**：制品库同步层（`source = SYNC`）由同步链路机器写入、
  可随时重建，不属于人工维护资产；混入会在往返后污染手工清单并破坏「同名手工优先」。
- `templates/**` 是**纯模板全文**（不是 `{name, content}` YAML 文档），路径即模板名。

### 9.3 安全加固（导入侧）

任一项不满足即整体拒绝（**4008**，明细指出违规条目与限值）：

| 约束 | 取值 |
|---|---|
| 路径穿越 | 拒绝任何 `..` 段（含 `a\..\b` 反斜杠伪装）、绝对路径 `/x` 与盘符 `C:/x` |
| 条目数 | ≤ 2048 |
| 单文件解压后 | ≤ 2 MiB |
| 解压总量 | ≤ 32 MiB（防压缩炸弹） |
| 目录层级 | ≤ 16 层 |
| 条目名长度 | ≤ 512 字符 |
| 编码 | 严格 UTF-8（非法字节序列拒绝，不静默替换为 `U+FFFD`） |

### 9.4 校验口径

导入侧解析**复用 initializer-core 的既有解析器**（`MetadataCatalog` 语义、`YamlStarterParser`、
`ConflictRuleParser`、`TemplateRenderer` 语法校验），并额外做一次**归档整体自洽性**断言
（`allowDangling = false`）：metadata 兼容矩阵引用的 Java 版本必须已定义、Boot 版本 ID 不得重复、
starter 清单必须已在 metadata 的 `bootVersions[].starters` 白名单登记、`requires` 不得悬空。
—— 草稿态的「边补边存」宽松语义仍只属于表单端点；**导入进草稿的是自洽的整体**。

条目数与结构行上限与表单端一致（条目 ≤ 512、结构行 ≤ 20000、单模板 ≤ 512 KB）。

### 9.5 幂等承诺

「导出 → 直接再导入」对手工清单**严格幂等**：同一份草稿导出后导入，读回内容与归档一次导出内容一致
（已有测试锁定 `unzip(导出₂) == unzip(导出₁)`，并断言 `hidden` 列往返不丢）。

### 9.6 错误码

| 错误码 | 含义 |
|---|---|
| 4008 `CONFIG_IMPORT_ARCHIVE_INVALID` | 归档不可读 / 条目名违规 / 超限 / 非法 UTF-8 / 重复条目 / 空归档 |
| 4009 `CONFIG_IMPORT_VALIDATION_FAILED` | 归档内容结构或字段非法、跨档一致性失败、模板语法非法 |
| 4010 `CONFIG_EXPORT_FAILED` | 导出侧归档序列化失败 |

---

## 10. 验证清单

配置变更后建议按序验证：

```bash
mvn verify        # 单测 + JaCoCo 覆盖率门禁（配置合法性由加载期校验兜底）
```

1. 启动后 `curl http://localhost:8080/api/metadata/java-versions` 确认新版本可见
2. `curl "http://localhost:8080/api/metadata/boot-versions?java=17"` 确认联动过滤正确
3. `curl "http://localhost:8080/api/metadata/starters?boot=3.2.5"` 确认 starter 清单
4. `POST /api/generate` 生成一次，检查 zip 内依赖坐标、联动文件与 yml 片段
5. 校验失败场景：提交不合法 group/artifact，确认返回 422 + 错误明细
6. `hidden` 往返冒烟：`PUT /api/admin/config/starter` 提交带 `hidden: true` 的条目 →
   `GET /api/admin/config/starter` 读回，确认 `hidden: true` 仍在（不被静默丢弃）
7. 结构化装配冒烟：发布一版 → 重启（非 `--demo`）→ `GET /api/metadata/*` 应还原发布内容；
   构造含非法引用的草稿发布应被 4002 拒绝且旧快照继续服务
8. 结构化 API 冒烟：`PUT /api/admin/starters` 提交含 `hidden: true` 的条目 →
   `GET /api/admin/starters` 读回确认 `hidden` 仍在；再 `GET /api/admin/templates` 确认**响应不含 `content`**，
   `GET /api/admin/templates/{name}` 才返回全文
9. 契约同源冒烟：结构化写入后 `GET /api/admin/config/starter` 应能读到同一份内容（YAML 视图），
   反之亦然
10. 导入导出冒烟：`GET /api/admin/config/export` 下载 ZIP →
    `POST /api/admin/config/import?operator=admin -F file=@initializer-config.zip` 回灌，
    确认响应 `summary` 规模与原草稿一致、`hidden` 未被丢弃，随后 `POST /config/publish` 成功
11. 导入安全冒烟：构造含 `templates/../../x.ftl` 的 ZIP 上传，应返回 **4008 + 路径穿越明细**，
    且草稿未被改动（`GET /api/admin/config/metadata` 仍是导入前内容）
