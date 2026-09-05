# M3 详细设计（子任务 M3.1~M3.5）

> 上游：[M3 概要设计](./m3-overview.md)。评审通过后按 M3.1 → M3.2 → M3.3 → M3.4/M3.5（可并行）顺序推进。

## M3.1 管理后台 API + 权限

**数据模型**（MyBatis-Plus，H2 兼容 DDL 见 `initializer-app/src/main/resources/db/schema.sql`）：

```
config_draft(id, kind, content_yaml, version, updated_by, updated_at)
   -- kind: METADATA / STARTER / TEMPLATE / CONFLICT_RULE / STARTER_SYNC
   -- 一 kind 一行，乐观锁 version
config_release(id, metadata_yaml, starter_yaml, template_yaml, conflict_yaml, released_by, released_at, remark)
config_audit(id, action, target_kind, operator, detail, created_at)
```

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

**发布流程**：读全部草稿 → `CatalogRefresher.validateAndBuild()` → 成功则 `AtomicReference.set(snapshot)` + 写 `config_release`；失败返回 4002 + 明细清单，草稿保留可继续编辑。

**权限**：`AdminAuthFilter` 校验 `X-Admin-Token`（SHA-256 常量时间比较），未授权 404 + 4004（不暴露管理端存在性）；业务接口（metadata/generate/validate）不受影响。token 配置项 `initializer.admin.token`，生产环境必须用 `INITIALIZER_ADMIN_TOKEN` 环境变量覆盖默认值。

## M3.2 清单热刷新

- `CatalogSnapshot`（不可变对象）：聚合 `MetadataCatalog` + `StarterCatalog` + 冲突规则 + 模板文本，构建期完成全部装载期校验（复用 M1/M2 既有校验器，零重复代码）
- `CatalogRefresher.refresh(drafts)`：
  1. 解析全部草稿（SnakeYAML SafeConstructor，拒绝 anchor/多文档以外的恶意结构）
  2. 逐项校验（ID 重复、悬空引用、占位符白名单越界、正则预编译、模板文件可达）
  3. 三通道合并：classpath 内置 ← 同步层 ← 手工配置（同名手工覆盖）
  4. 构建 `CatalogSnapshot` → `AtomicReference.set` 原子切换
- **失败语义**：任一步抛 `BusinessException(4006)` + 明细；快照引用不变，生成服务无感；失败原因写入审计供管理端展示
- **回滚**：取历史发布全文重新走完整发布校验，通过后切换为一次新发布（不绕过校验通道，链路可追溯）
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

- 三道门禁延续：UT 成功率 100% + 行/分支覆盖 ≥ 80%、PIT 杀死率 ≥ 70%、注释一致性
- 重点测试对象：`CatalogRefresher`（成功/各类失败/回滚/并发切换）、`ConfigService`（乐观锁/发布互斥）、合并策略（手工 vs 同名同步覆盖）、`ConflictRuleEngine`（pair/group/悬空 id）、MyBatis-Plus mapper 层（H2）
- 快照测试扩展：管理端发布合法配置 → 生成产物与既有 fixture 一致（证明发布链路无回归）
