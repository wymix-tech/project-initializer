# M3 里程碑概要设计

> 来源：#6 里程碑 M3；依据 #1 需求原文、#2 PRD v0.2、#3 技术方案 v0.2。
> 评审记录见 Issue #6 评论（2026-09-04 评审通过）。

## 1. 目标与范围

- **平台配置后台（F6）**：管理员可视化维护 Java/Boot 版本清单、starter 清单、兼容矩阵、生成模板，支持「草稿 → 生效」发布流程与简单角色权限
- **清单热刷新**：配置发布后原子切换生效，刷新失败回滚，生成服务不中断
- **制品库同步**：starter 清单从内部制品库自动同步，与手工配置双通道并存（同名以手工为准，PRD 已确认）
- **搜索/分类 + 依赖冲突提示**：starter 清单检索过滤，选中冲突组合即时提示

**不做**：精细视觉设计、多语言/多框架、生成历史（PRD §5 已划出范围）。

## 2. 与 M1/M2 的关系（复用与改造）

**复用**：`MetadataCatalog` / `StarterCatalog` 加载与装载期校验逻辑、`PlaceholderPolicy` 占位符白名单、`ErrorCode` 错误码体系、`GlobalExceptionHandler`、前端 Vue3 + 组合式 API 框架、三道 CI 门禁（UT 100% + 覆盖 80/80 + PIT 70%）。

**改造点**：
1. Catalog 从「启动时一次性加载 classpath」升级为**可刷新的 CatalogManager**：内部持有不可变快照（`AtomicReference<CatalogSnapshot>`），读路径无锁
2. 清单来源从单一 classpath 扩展为三通道合并：**classpath 内置 → 手工配置 → 制品库同步**，优先级后者覆盖前者（同名以手工为准）
3. 新增持久化层：**MyBatis-Plus**（M1.1 已预置依赖管理，此时正式接线），存储手工配置与发布版本；启动类移除 `DataSourceAutoConfiguration` 排除项（M1.4 遗留点）
4. `MetadataController` / `GenerateController` 读路径改为从 CatalogManager 快照取数，接口契约不变（前端零感知）

## 3. 总体架构（增量视图）

```
管理端 SPA (/admin)
   │ CRUD + 发布 + 权限
   ▼
AdminApiController ──▶ ConfigService（草稿/发布/审计）
   │                      │ MyBatis-Plus
   ▼                      ▼
CatalogRefresher ───▶ config 表（草稿/生效版本/审计日志）
   │ 校验 + 原子切换
   ▼
CatalogSnapshot (不可变：MetadataCatalog + StarterCatalog + 冲突规则)
   ▲ 只读无锁
   │
MetadataController / GenerateController（业务读路径，契约不变）
   ▲ 定时/触发
CatalogSyncJob ──▶ CatalogSyncProvider SPI ──▶ 内部制品库
```

**核心原则**：

- **快照不可变**：每次刷新构建全新 `CatalogSnapshot`，校验全通过后 `AtomicReference.set` 原子切换；生成请求持有的旧快照在请求结束前始终有效，刷新不中断服务
- **失败回滚**：刷新流程 = 解析 → 装载期校验（复用 M1/M2 规则：ID 重复、悬空引用、占位符越界、正则非法）→ 快照构建 → 原子切换；任一步失败保留旧快照并记录失败原因，返回明确错误
- **持久化仅限管理配置**：延续 PRD「生成请求不落库」约束，生成流程仍无状态

## 4. 模块影响

| 模块 | 变更 |
|---|---|
| initializer-api | 管理端 DTO（草稿/发布/冲突规则）、新增错误码 4xxx/9xxx 段 |
| initializer-core | `CatalogSnapshot`、`CatalogRefresher`（校验+原子切换）、`CatalogSyncProvider` SPI、`ConflictRuleEngine`；`ProjectGenerator` 不变（吃快照） |
| initializer-app | MyBatis-Plus 接线（DataSource + Mapper）、`AdminApiController`、`ConfigService`、`CatalogSyncJob`、安全过滤（Admin 角色） |
| initializer-web | `/admin` 管理页面；依赖选择步骤接入冲突提示与搜索过滤 |
| initializer-app/resources | SQL 初始化脚本（config 表） |

## 5. 错误码扩展

| 码 | 名称 | 场景 |
|---|---|---|
| 4001 | CONFIG_DRAFT_NOT_FOUND | 草稿不存在 |
| 4002 | CONFIG_VALIDATION_FAILED | 发布前校验失败（明细含失败项） |
| 4003 | CONFIG_CONCURRENT_MODIFY | 乐观锁冲突，草稿已被他人修改 |
| 4004 | FORBIDDEN | 未授权访问管理接口 |
| 4005 | SYNC_PROVIDER_FAILED | 制品库拉取失败 |
| 4006 | CONFIG_REFRESH_FAILED | 热刷新失败（保留旧快照，明细含原因） |
| 4007 | DEPENDENCY_CONFLICT | 生成命中依赖冲突规则（422） |

## 6. 关键设计决策

1. **草稿-生效分离**：所有 CRUD 只写草稿；「发布」动作才触发全量校验 + 快照重建 + 切换。校验失败的发布请求整体拒绝，绝不半生效
2. **模板也配置化管理**：生成模板（.ftl）从 classpath 迁到配置存储，随草稿发布；快照持有模板文本，`TemplateRenderer` 按快照渲染。classpath 内置模板作为兜底
3. **制品库同步 = 只读贡献者**：同步结果写入「同步层」，不直接改手工配置；合并发生在快照构建期（同名条目手工覆盖同步）
4. **权限简单化**：两角色（ADMIN / USER），基于请求头 token 的轻量校验过滤器实现（`X-Admin-Token` + SHA-256 常量时间比较），不引入重型安全框架；生产环境必须通过 `INITIALIZER_ADMIN_TOKEN` 环境变量覆盖默认开发 token
5. **冲突规则配置化**：冲突规则作为清单的一部分（starter id 对/组），随快照发布；前端软提示 + 生成期硬校验（422/4007）双防线

## 7. 风险

- **首次引入持久化**：DataSource/MyBatis-Plus 接线改变启动行为，CI 门禁覆盖面新增 mapper/service 层测试
- **快照内存开销**：清单 + 模板全量驻留内存，当前体量小（KB 级）无压力，模板膨胀后需关注
- **制品库接口契约未知**：`CatalogSyncProvider` SPI 隔离差异，首期提供 Mock 实现保证架构落地，真实制品库对接时仅换实现类
- **并发发布**：乐观锁（version 字段）+ 发布互斥，避免双管理员交叉发布
