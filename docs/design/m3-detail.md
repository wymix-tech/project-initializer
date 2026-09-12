# M3 详细设计（子任务 M3.1~M3.9）

> 上游：[M3 概要设计](./m3-overview.md)。评审通过后按 M3.1 → M3.2 → M3.3 → M3.4/M3.5（可并行）顺序推进；
> M3.6~M3.9 为后续迭代追加（必选 starter、requires 校验、强制依赖/互斥、候选项标识）。
> **存储层已随 T1（配置结构化入库）重构，见下方 M3.1 数据模型。**

## M3.1 管理后台 API + 权限

**数据模型**（MyBatis-Plus，H2 兼容 DDL 见 `initializer-app/src/main/resources/db/schema.sql`）：

> ⚠️ **T1 起已由「一 kind 一行 YAML 文本」改为「15 张 `cfg_*` 关系表 + 头表 + 草稿元信息」**，
> 详见 [配置驱动教程 §6](../guide/configuration.md#6-升级说明yaml-文本--结构化关系表t1)。
> 旧 `config_draft.content_yaml` 与 `config_release.*_yaml` 已废弃。

```
-- 草稿元信息（一 kind 一行，乐观锁 version；kind: METADATA/STARTER/STARTER_SYNC/TEMPLATE/CONFLICT_RULE）
config_draft_meta(kind, version, updated_by, updated_at)

-- 发布头（只保留头信息；行级内容以 release_id 作用域落在各 cfg_* 表中）
config_release(id, released_by, released_at, remark)
config_audit(id, action, target_kind, operator, detail, created_at)

-- 15 张 cfg_* 关系表（每张都带 release_id：0=草稿工作区，>0=发布快照）
cfg_java_version / cfg_boot_version / cfg_boot_java / cfg_boot_starter_ref
cfg_starter / cfg_starter_dependency / cfg_starter_require_group / cfg_starter_require_item
cfg_starter_boot_version / cfg_starter_linkage_template / cfg_starter_linkage_yml / cfg_starter_linkage_abstract
cfg_conflict_rule / cfg_conflict_rule_starter / cfg_template
```

- **`release_id` 作用域铁律**：`0` 为草稿工作区，`>0` 为不可变发布快照；同一张表承载两者，
  所有唯一约束必须带 `release_id`（漏一处会导致「发布改写草稿」）。
- **发布** = 把草稿行复制为新 `release_id` + 写发布头 + 切换快照（同一事务）。
- **读取禁止 N+1**：一律按 `release_id` 批量查 `selectByReleaseId(...)` 后在内存分组装配。
- **`cfg_starter.hidden`**（M3.8 引入）：`BOOLEAN NOT NULL DEFAULT FALSE`，作为一等配置项落库；
  schema 末尾带幂等 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 增量升级段。

**API 设计**（`/api/admin/**`，均需 `X-Admin-Token` 头，`AdminAuthFilter` 校验）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/config/{kind}` | 读当前草稿（含乐观锁 version） |
| PUT | `/api/admin/config/{kind}` | 更新草稿（`If-Match` 乐观锁） |
| POST | `/api/admin/config/publish` | 发布：全量校验 → 快照构建 → 原子切换 → 审计落库 |
| POST | `/api/admin/releases/{id}/rollback` | 回滚到历史版本（重新走发布校验） |
| POST | `/api/admin/sync` | 手动触发制品库同步（M3.3） |
| GET | `/api/admin/releases` | 发布历史 |
| GET | `/api/admin/audits` | 审计日志 |
| GET | `/api/admin/metadata` | 结构化：读元数据草稿（T3） |
| PUT | `/api/admin/metadata` | 结构化：整体保存元数据草稿（T3） |
| GET/PUT | `/api/admin/starters` | 结构化：读/整体保存手工 starter 草稿（T3） |
| GET/PUT | `/api/admin/conflict-rules` | 结构化：读/整体保存冲突规则草稿（T3） |
| GET | `/api/admin/templates` | 结构化：模板**列表**（不含 `content`，T3） |
| GET/PUT/DELETE | `/api/admin/templates/{name}` | 结构化：模板详情/upsert/删除（T3） |
| GET | `/api/admin/config/export` | 导出草稿为 ZIP（T4） |
| POST | `/api/admin/config/import` | 上传 ZIP 解析入库（T4） |

**发布流程**（T2 起结构化）：读草稿结构行（`release_id = 0`）→ `StructuredCatalogAssembler` 装配 + 装载期校验
→ 成功则 `copyAsRelease(0 → 新 release_id)` 写发布头并 `AtomicReference.set(snapshot)`；
失败返回 4002 + 明细清单，草稿保留可继续编辑。

**权限**：`AdminAuthFilter` 校验 `X-Admin-Token`（SHA-256 常量时间比较），未授权 404 + 4004（不暴露管理端存在性）；业务接口（metadata/generate/validate）不受影响。token 配置项 `initializer.admin.token`，生产环境必须用 `INITIALIZER_ADMIN_TOKEN` 环境变量覆盖默认值。

## M3.2 清单热刷新

- `CatalogSnapshot`（不可变对象）：聚合 `MetadataCatalog` + `StarterCatalog` + 冲突规则 + 模板文本，构建期完成全部装载期校验（复用 M1/M2 既有校验器，零重复代码）
- **T2 起装配入口**：`StructuredCatalogAssembler.assemble(releaseId)` ——
  按 `release_id` 批量查 15 张 `cfg_*` 子表 → 内存分组 → 装配 `MetadataCatalog` / `StarterCatalog` /
  冲突规则 / 模板集 → `merge` → 构建快照。**不再经 YAML 反编译**（`StructuredYamlCodec` 仅保留给导入导出）。
  禁止 N+1：查询条数应 ≤ 表数量（有断言测试锁定）。
- **T2 起发布**：`ConfigService.doPublish` = 写发布头 + `copyAsRelease(草稿 release_id=0 → 新 release_id)`，
  **同一事务、快照切换置末尾**；沿用 `publishLock` 与 `noRollbackFor` 失败审计。
- **启动装载**：`CatalogSnapshotBootstrap` 从最新 release 装表；非 `--demo` 且表空 → 空快照。
- **失败语义**：任一步抛 `BusinessException(4002)` + 明细；快照引用不变，生成服务无感；失败原因写入审计供管理端展示。
- **回滚**：读目标 release 的结构行校验后复制为新 release（不绕过校验通道，链路可追溯）。
  ⚠️ 可回滚基线 = 迁移后发布的版本；升级前仅有头信息的历史版本不可回滚（见教程 §6.3）。
- **验证用例**（对应验收标准 2）：注入一条悬空引用的非法配置发布 → 发布被拒 + 旧快照继续服务（生成请求成功）

## M3.3 制品库同步适配器

```java
public interface CatalogSyncProvider {
    SyncResult fetch();          // 拉取制品库 starter 元数据
    String providerId();         // 来源标识，写入 maintainer 元信息
}
```

- 首期实现：`MockCatalogSyncProvider`（读本地 fixture）+ `RemoteCatalogSyncProvider`（HTTP 拉取，接口路径/鉴权配置化，未配置时禁用）
- 同步结果归一化为 starter yml 同构模型 → 存「同步层」（STARTER_SYNC 草稿，多文档以 `---` 分隔）
- **合并策略**：按 starter id 归并，`id` 相同 → 手工配置优先（PRD 已确认）；仅同步层存在的条目自动进入清单；同名冲突发布期整体拒绝，绝不静默覆盖手工配置
- `CatalogSyncJob`：定时（间隔配置化，默认关闭）+ 手动触发（管理端按钮）；拉取失败记审计不阻断服务（错误码 4005）

## M3.4 搜索与分类

- `StarterQueryEngine`（core，纯内存）：对快照中 starter 清单按名称/描述/分类过滤
- `/api/metadata/starters` 扩展参数：`?q=关键字`（ID/展示名/描述 contains，忽略大小写）、`?category=web|data|middleware|tool`（精确匹配，未知分类返回空列表不报错）
- 响应字段补充 `category` / `description` / `maintainer` —— 前端按分类分组展示 + 搜索框防抖过滤
- 无新增错误码；搜索基于当前生效快照，管理端发布后即时热生效

## M3.5 依赖冲突提示

**冲突规则模型**（配置化，随清单发布，`CONFLICT_RULE` 草稿）：

```yaml
rules:
  - id: redis-vs-cache-impl
    type: pair                 # pair（id 对互斥）/ group（组内互斥）
    starters: [xxx-redis-spring-boot-starter, spring-boot-starter-cache]
    message: 两者均注册缓存管理器，可能引发 Bean 覆盖冲突
```

- `ConflictRuleEngine`（core）：输入已选 starter id 集合 → 输出触发的冲突列表（规则 id + 涉及 starter + message）
- **两个落点**：
  1. 前端提示（软提示）：`/api/metadata/conflict-rules` 返回规则集，前端选择依赖时本地即时计算提示
  2. 生成时兜底（硬校验）：`POST /api/generate` 服务端复算，命中规则 → 422 + 4007 `DEPENDENCY_CONFLICT` + 明细（防绕过前端直调）
- 规则装载期校验：引用的 starter id 必须存在于清单（悬空引用拒绝发布）

## 测试与门禁

- 三道门禁延续：UT 成功率 100% + 行/分支覆盖 ≥ 80%（Java JaCoCo）、PIT 杀死率 ≥ 70%、注释一致性。
- **前端门禁（T6 起）**：`vue-tsc --noEmit` 类型检查 + `vitest run --coverage` 行/分支 ≥ 80% +
  `stryker run` 突变分数 ≥ 70%。
  > ⚠️ 前端突变门禁依赖仓库内补丁 `patches/@stryker-mutator+instrumenter+8.7.1.patch`
  > （`patch-package`，随 `npm ci` 的 postinstall 或 `npm run test:mutation` 前置应用）。
  > 原因：instrumenter 会把**动态 `import()` 的模块说明符**当字符串字面量变异，产出
  > `import(cond ? "" : (cov, spec))` 这种 Vite 无法静态解析的表达式，导致**引用该模块的测试文件
  > 整体加载失败**（用例不注册、`afterAll` 不触发、覆盖率归因全灭）。补丁让 `isValidParent`
  > 额外排除 `import()` 实参（与既有 `require(...)` 排除同口径）。详见教程 §13。
- 重点测试对象：`StructuredCatalogAssembler`（装配/N+1 断言）、`ConfigService`（乐观锁/发布互斥/回滚）、
  导入导出（含恶意 ZIP：路径穿越/条目数/单文件大小）、存量迁移（`LegacyYamlMigration` 预检/直迁/`LegacyCutoverDrillTest` 全链路演练）、`ConflictRuleEngine`（pair/group/悬空 id）、
  15 张 `cfg_*` 表的 mapper 层（H2）。
- 快照测试扩展：管理端发布合法配置 → 生成产物与既有 fixture 一致（证明发布链路无回归）。

## M3.6 必选 starter 自动补齐

- **模型**：starter 清单条目可声明 `required: true`（框架必选项），随清单发布。
- **core**：`MetadataCatalog.requiredStarters(bootVersionId)` 返回该 Boot 版本下的必选清单。
- **生成期**：`ProjectGenerator` 在渲染前把必选项合并进请求（必选项在前、去重保序），
  请求未携带也自动补齐，**不报错**（这是 M3.6 与 M3.7 的边界：必选 = 服务端补齐，
  requires = 校验已选项的依赖是否满足）。
- **前端**：必选项默认勾选且不可取消（`lockedStarters` 中的「框架必选」部分）。

## M3.7 requires 依赖校验

- **模型**：starter 条目可声明 `requires` 分组（AND-of-OR）：外层分组之间是「都要满足」（AND），
  分组内成员是「任一满足即可」（OR）。
- **core**：`MetadataCatalog.validateRequirements(selectedIds, bootVersionId)` 校验每个已选
  starter 的 requires 分组均有成员被选中，否则抛 `BusinessException(4007)` +
  `DEPENDENCY_NOT_SATISFIED` 明细。
- **与 M3.6 的关键差异**：**不再自动把 requires 依赖拉进选中集合**——依赖由用户显式选择满足，
  服务端只做校验；避免「静默塞入用户没选的组件」。
- **装载期校验**：requires 引用的 starter id 必须存在（悬空引用拒绝发布）。

## M3.8 强制依赖自动锁定与互斥二选一

- **`hidden` 字段**：starter 清单新增 `hidden`（一等配置项），直落 `cfg_starter.hidden`。
  语义为「仅隐藏前端依赖选择列表的展示」，**被已选组件依赖时强制展示**。
  T6 起结构化表单显式提供「隐藏」开关，**不经 YAML 往返**（PR #44 已堵住 `hidden: true`
  被往返静默丢弃的缺口）。
- **强制执行**：`requires` 的**单元素分组**（无 OR）= 强制依赖：
  选中父组件时自动勾选依赖并**锁定**（不可单独取消），取消父组件时联动释放。
- **互斥二选一**：冲突规则涉及「强制依赖」或已触发（≥2 个成员被选）时，双方标记为互斥，
  可成对高亮并「二选一」切换。前端 `wizard.ts` 以 `manualStarters`（意图源）+
  `syncSelection()`（派生闭包）实现自动选中/释放，`mutexInfo` / `lockedStarters` 提供 UI 标记。
- **OR 分组**：多元素分组仅在提示层提示，不自动勾选。

## M3.10 存量迁移与切换通道

- **背景**：T1 起配置正文改为关系表存储，旧版 `config_draft.content_yaml` / `config_release.*_yaml`
  的存量数据必须迁移（详见教程 §14）。
- **通道 A｜导出 → 导入**：旧版 jar `GET /api/admin/config/{kind}` 导出 YAML 留档，
  新版经 `POST /api/admin/config/import` 回灌草稿（ZIP 归档，M3.4 通道）。
- **通道 B｜库内直迁**：`POST /api/admin/config/migrate-legacy?operator=` 直读旧 `config_draft` 表
  → 结构化草稿行；预检 `GET /api/admin/config/migrate-legacy` 返回旧表是否存在 / 各 kind 行数 /
  是否需迁移。两条通道都**只写草稿、不发布、失败整体回滚**，成功后写审计（`MIGRATE_LEGACY`）。
- **回退基线**：升级前历史发布仅有头信息、无法逐行还原，**可回滚基线 = 迁移后发布的版本**。
- **外部目录**：`initializer.catalog.dir` 保留为兼容通道（启动 WARN 提示已被 ZIP 导入取代），
  `validation.yml` 明确留在 classpath、不入库、不参与归档（见教程 §14.4 / §14.5）。

## M3.9 待选候选项标识

- **场景**：某已选组件的 OR 分组**尚未满足**时，把该分组内的候选成员标记为「待二选一」。
- **表现**：候选项以淡黄底 + id 前红色 `*` 标识；选中其一后撤销。
- 单元素分组（强制依赖）**不进入**待选候选项标记（它已被 M3.8 自动锁定）。
- 候选项不在当前检索结果时，从 `starter` 注册表（M3.6 起的全量注册表）补入。
