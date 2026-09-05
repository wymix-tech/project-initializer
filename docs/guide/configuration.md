# 配置驱动完整教程

本教程面向需要维护版本清单、接入 starter、调整校验规则或通过管理后台热更新配置的同学。读完即可**零代码**扩展平台能力。

平台所有可变部分均由配置驱动，代码只在新增"能力类型"时才需要改动。配置分四块：

| 配置 | 位置 | 作用 |
|---|---|---|
| `metadata.yml` | `initializer-core/src/main/resources/catalog/` | Java / Boot 版本、兼容矩阵、starter 坐标映射 |
| `starters/*.yml` | `catalog/starters/` | 单 starter 描述：坐标 + 联动规则 |
| `validation.yml` | `catalog/` | Group / Artifact / Package 命名校验规则 |
| 管理后台草稿 | 数据库（H2 文件，可换 JDBC 数据源） | 在线编辑以上配置，发布后热生效 |

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
```

**联动规则**：选择 Java 8 → 仅显示 `supportedJavaVersions` 包含 `"8"` 的 Boot 版本；选定 Boot 版本 → 仅显示其 `starters` 列表中的 starter。

### 2.3 starters（坐标映射）

```yaml
starters:
  web:
    label: "Spring Web"
    dependency:
      groupId: org.springframework.boot
      artifactId: spring-boot-starter-web
```

- 声明了 `version` 的依赖会显式输出到生成项目的 pom/gradle（适用于不在 Boot BOM 管理范围的内部 starter，版本来源优先取 starter yml，见第 3 节）。
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
3. 在 `metadata.yml` 的 `starters:` 节补充坐标映射（前端展示 label + 坐标）
4. 在目标 `bootVersions[].starters` 列表中加入该 starter id
5. 发布（文件方式直接重启生效；管理后台方式见第 5 节，无需重启）

**生成器代码零改动。**

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

## 6. 验证清单

配置变更后建议按序验证：

```bash
mvn verify        # 单测 + JaCoCo 覆盖率门禁（配置合法性由加载期校验兜底）
```

1. 启动后 `curl http://localhost:8080/api/metadata/java-versions` 确认新版本可见
2. `curl "http://localhost:8080/api/metadata/boot-versions?java=17"` 确认联动过滤正确
3. `curl "http://localhost:8080/api/metadata/starters?boot=3.2.5"` 确认 starter 清单
4. `POST /api/generate` 生成一次，检查 zip 内依赖坐标、联动文件与 yml 片段
5. 校验失败场景：提交不合法 group/artifact，确认返回 422 + 错误明细
