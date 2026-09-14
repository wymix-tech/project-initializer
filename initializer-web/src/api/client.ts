import type {
  BootVersion,
  ConflictRule,
  DerivePackageResponse,
  ErrorResponse,
  GenerateRequest,
  JavaVersion,
  Starter,
  AdminAudit,
  AdminConfigKind,
  AdminDraftView,
  AdminPublishResult,
  AdminRelease,
  AdminSyncOutcome,
  ValidationResult,
} from '@/types/api'
import type {
  ConflictRuleUpsertRequest,
  ConflictRuleView,
  DraftSaveResult,
  ImportView,
  MetadataUpsertRequest,
  MetadataView,
  StarterCatalogUpsertRequest,
  StarterCatalogView,
  TemplateCatalogUpsertRequest,
  TemplateDetail,
  TemplateListView,
} from '@/types/admin'

/**
 * 后端错误：携带统一 ErrorResponse，供 toast 明细展示。
 */
export class ApiError extends Error {
  readonly code: number
  readonly detail: string

  /**
   * @param error 后端统一错误体
   */
  constructor(error: ErrorResponse) {
    super(`[${error.code}] ${error.message}`)
    this.name = 'ApiError'
    this.code = error.code
    this.detail = error.message
  }
}

/** 统一响应处理：非 2xx 抛 ApiError（含后端明细）。 */
async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let errorResponse: ErrorResponse = {
      code: res.status,
      message: `请求失败（HTTP ${res.status}）`,
      timestamp: Date.now(),
    }
    try {
      errorResponse = await res.json()
    } catch {
      // 响应体非 JSON 时保留默认错误体
    }
    throw new ApiError(errorResponse)
  }
  return res.json() as Promise<T>
}

function queryString(params: Record<string, string>): string {
  return new URLSearchParams(params).toString()
}

/** 元数据与生成相关接口封装。 */
export const api = {
  /** Java 版本清单（含推荐 Boot 版本）。 */
  listJavaVersions(): Promise<JavaVersion[]> {
    return fetch('/api/metadata/java-versions').then(handleResponse<JavaVersion[]>)
  },

  /** 指定 Java 版本下兼容的官方 Boot 版本。 */
  listBootVersions(java: string): Promise<BootVersion[]> {
    return fetch(`/api/metadata/boot-versions?${queryString({ java })}`).then(
      handleResponse<BootVersion[]>,
    )
  },

  /** 指定 Boot 版本下可用 starter 清单（M3.4：q/category 可选过滤）。 */
  listStarters(boot: string, q?: string, category?: string): Promise<Starter[]> {
    const params: Record<string, string> = { boot }
    if (q) params.q = q
    if (category) params.category = category
    return fetch(`/api/metadata/starters?${queryString(params)}`).then(
      handleResponse<Starter[]>,
    )
  },

  /** 冲突规则清单（M3.5）：前端选择依赖时本地即时计算提示的数据源。 */
  listConflictRules(): Promise<ConflictRule[]> {
    return fetch('/api/metadata/conflict-rules').then(handleResponse<ConflictRule[]>)
  },

  /** Group/Artifact/Package 即时校验。 */
  validate(group: string, artifact: string, packageName: string): Promise<ValidationResult> {
    return fetch('/api/validate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ group, artifact, packageName }),
    }).then(handleResponse<ValidationResult>)
  },

  /** 由 Group + Artifact 推导默认包名。 */
  derivePackage(group: string, artifact: string): Promise<DerivePackageResponse> {
    return fetch(`/api/derive-package?${queryString({ group, artifact })}`).then(
      handleResponse<DerivePackageResponse>,
    )
  },

  /**
   * 生成项目骨架 zip 并触发浏览器下载。
   *
   * @throws ApiError 后端返回 422/5xx 时抛出，错误体含 code/message 明细
   */
  async generateProject(request: GenerateRequest): Promise<void> {
    const res = await fetch('/api/generate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    })
    if (!res.ok) {
      let errorResponse: ErrorResponse = {
        code: res.status,
        message: `生成失败（HTTP ${res.status}）`,
        timestamp: Date.now(),
      }
      try {
        errorResponse = await res.json()
      } catch {
        // 非 JSON 错误体时保留默认
      }
      throw new ApiError(errorResponse)
    }
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${request.artifact}.zip`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
  },
}

/** 管理端 token 请求头名（与后端 AdminAuthFilter.TOKEN_HEADER 一致）。 */
export const ADMIN_TOKEN_HEADER = 'X-Admin-Token'

function adminHeaders(token: string): Record<string, string> {
  return { [ADMIN_TOKEN_HEADER]: token, 'Content-Type': 'application/json' }
}

function adminErrorFallback(status: number): ErrorResponse {
  return { code: status, message: `管理端请求失败（HTTP ${status}）`, timestamp: Date.now() }
}

/** 管理端接口封装（M3 评审遗留项 2：/admin 可视化维护页面数据源）。 */
export const adminApi = {
  /** 读取指定种类当前草稿。 */
  getDraft(kind: AdminConfigKind, token: string): Promise<AdminDraftView> {
    return fetch(`/api/admin/config/${kind}`, { headers: adminHeaders(token) }).then(
      handleResponse<AdminDraftView>,
    )
  },

  /** 保存草稿（乐观锁：携带 GET 返回的 version）。 */
  async putDraft(kind: AdminConfigKind, token: string, contentYaml: string, version: number | null): Promise<void> {
    const headers = adminHeaders(token)
    if (version !== null) headers['If-Match'] = String(version)
    const res = await fetch(`/api/admin/config/${kind}`, {
      method: 'PUT',
      headers,
      body: JSON.stringify({ contentYaml, operator: 'admin' }),
    })
    if (!res.ok) {
      let errorResponse: ErrorResponse = adminErrorFallback(res.status)
      try {
        errorResponse = await res.json()
      } catch {
        // 非 JSON 错误体时保留默认
      }
      throw new ApiError(errorResponse)
    }
  },

  /** 发布：全量校验 → 快照原子切换。校验失败抛 ApiError（4002 + 明细）。 */
  publish(token: string): Promise<AdminPublishResult> {
    return fetch('/api/admin/config/publish', {
      method: 'POST',
      headers: adminHeaders(token),
      body: JSON.stringify({ operator: 'admin' }),
    }).then(handleResponse<AdminPublishResult>)
  },

  /** 发布历史。 */
  listReleases(token: string): Promise<AdminRelease[]> {
    return fetch('/api/admin/releases', { headers: adminHeaders(token) }).then(
      handleResponse<AdminRelease[]>,
    )
  },

  /** 回滚到指定发布（重新走完整校验通道）。 */
  rollback(token: string, releaseId: number): Promise<AdminPublishResult> {
    return fetch(`/api/admin/releases/${releaseId}/rollback`, {
      method: 'POST',
      headers: adminHeaders(token),
      body: JSON.stringify({ operator: 'admin' }),
    }).then(handleResponse<AdminPublishResult>)
  },

  /** 审计日志。 */
  listAudits(token: string): Promise<AdminAudit[]> {
    return fetch('/api/admin/audits', { headers: adminHeaders(token) }).then(
      handleResponse<AdminAudit[]>,
    )
  },

  /** 手动触发制品库同步（M3.3）：拉取 → 合并 → 同步层草稿重建 → 热生效。 */
  sync(token: string, operator = 'admin'): Promise<AdminSyncOutcome> {
    return fetch('/api/admin/sync', {
      method: 'POST',
      headers: adminHeaders(token),
      body: JSON.stringify({ operator }),
    }).then(handleResponse<AdminSyncOutcome>)
  },
}

/**
 * 结构化管理端接口（T6）：分模块表单化读写 + 发布/回滚/审计 + ZIP 导入导出。
 *
 * 与 {@link adminApi} 的 YAML 契约读写同一批 `release_id = 0` 草稿行，
 * 共享同一 `config_draft_meta.version`（`If-Match` 可在两套契约间混用）。
 */
export const adminStructuredApi = {
  /**
   * 读取草稿乐观锁版本（结构化 GET 不回版本，版本统一取自草稿 meta）。
   *
   * 两套契约共享同一 `config_draft_meta.version`，故走 YAML 契约的轻量 GET 即可；
   * 草稿不存在（首次维护）时返回 {@code null}，等价于「无 If-Match」。
   *
   * <p><b>版本是 kind 级的</b>（{@code config_draft_meta} 一 kind 一行），必须按目标模块取：
   * 跨 kind 复用会把 metadata 的版本当作 starter / template / conflict-rule 的期望版本，
   * 导致这些模块在 metadata 保存过之后首次保存即 4003（服务端 0 vs 请求 N）。
   *
   * @param token 管理端 token
   * @param kind  目标配置种类（版本按 kind 独立计数，不可跨模块复用）
   * @returns 当前 version，未保存过时为 null
   */
  async readDraftVersion(token: string, kind: AdminConfigKind): Promise<number | null> {
    const view = await adminApi.getDraft(kind, token)
    return view.version ?? null
  },

  /** 读元数据草稿。 */
  getMetadata(token: string): Promise<MetadataView> {
    return fetch('/api/admin/metadata', { headers: adminHeaders(token) }).then(
      handleResponse<MetadataView>,
    )
  },

  /** 整体保存元数据草稿（乐观锁：携带 GET 返回的 version）。 */
  putMetadata(token: string, req: Omit<MetadataUpsertRequest, 'operator'>, version: number | null): Promise<DraftSaveResult> {
    return putStructured(token, '/api/admin/metadata', req, version)
  },

  /** 读手工 starter 清单草稿。 */
  getStarters(token: string): Promise<StarterCatalogView> {
    return fetch('/api/admin/starters', { headers: adminHeaders(token) }).then(
      handleResponse<StarterCatalogView>,
    )
  },

  /** 整体保存手工 starter 清单草稿（只写 source = MANUAL 行）。 */
  putStarters(token: string, req: Omit<StarterCatalogUpsertRequest, 'operator'>, version: number | null): Promise<DraftSaveResult> {
    return putStructured(token, '/api/admin/starters', req, version)
  },

  /** 读冲突规则草稿。 */
  getConflictRules(token: string): Promise<ConflictRuleView> {
    return fetch('/api/admin/conflict-rules', { headers: adminHeaders(token) }).then(
      handleResponse<ConflictRuleView>,
    )
  },

  /** 整体保存冲突规则草稿。 */
  putConflictRules(token: string, req: Omit<ConflictRuleUpsertRequest, 'operator'>, version: number | null): Promise<DraftSaveResult> {
    return putStructured(token, '/api/admin/conflict-rules', req, version)
  },

  /** 模板列表（**不含 content CLOB**）。 */
  listTemplates(token: string): Promise<TemplateListView> {
    return fetch('/api/admin/templates', { headers: adminHeaders(token) }).then(
      handleResponse<TemplateListView>,
    )
  },

  /** 模板详情（含全文，按需单独取）。 */
  getTemplate(token: string, name: string): Promise<TemplateDetail> {
    return fetch(`/api/admin/templates/${encodeURIComponent(name)}`, {
      headers: adminHeaders(token),
    }).then(handleResponse<TemplateDetail>)
  },

  /** 单模板 upsert（按 name 幂等，保留其余模板）。 */
  putTemplate(
    token: string,
    name: string,
    content: string,
    version: number | null,
    sort: number | null = null,
    operator = 'admin',
  ): Promise<DraftSaveResult> {
    return putStructured(
      token,
      `/api/admin/templates/${encodeURIComponent(name)}`,
      { name, content, operator, sort },
      version,
    )
  },

  /** 模板集整体替换（全量）。 */
  putTemplates(token: string, req: Omit<TemplateCatalogUpsertRequest, 'operator'>, version: number | null): Promise<DraftSaveResult> {
    return putStructured(token, '/api/admin/templates', req, version)
  },

  /** 删除单个模板（按 name，保留其余模板）。 */
  async deleteTemplate(
    token: string,
    name: string,
    version: number | null,
    operator = 'admin',
  ): Promise<DraftSaveResult> {
    const headers = adminHeaders(token)
    if (version !== null) headers['If-Match'] = String(version)
    const res = await fetch(
      `/api/admin/templates/${encodeURIComponent(name)}?${queryString({ operator })}`,
      { method: 'DELETE', headers },
    )
    return handleStructured<DraftSaveResult>(res)
  },
}

/**
 * 结构化 PUT：带 token / If-Match / JSON 体，统一错误包装。
 *
 * @param token   管理端 token
 * @param url     目标地址
 * @param body    请求体（operator 由本函数补齐）
 * @param version 乐观锁期望版本（null = 不携带 If-Match）
 */
async function putStructured<T>(
  token: string,
  url: string,
  body: unknown,
  version: number | null,
): Promise<T> {
  const headers = adminHeaders(token)
  if (version !== null) headers['If-Match'] = String(version)
  const res = await fetch(url, {
    method: 'PUT',
    headers,
    body: JSON.stringify({ ...(body as Record<string, unknown>), operator: 'admin' }),
  })
  return handleStructured<T>(res)
}

/** 结构化响应处理：非 2xx 抛 ApiError（含后端 code/message 明细）。 */
async function handleStructured<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let errorResponse: ErrorResponse = adminErrorFallback(res.status)
    try {
      errorResponse = await res.json()
    } catch {
      // 非 JSON 错误体时保留默认
    }
    throw new ApiError(errorResponse)
  }
  const text = await res.text()
  return (text === '' ? null : JSON.parse(text)) as T
}

/** 归档导入导出接口（T4 契约）。 */
export const adminArchiveApi = {
  /** 导出当前草稿为 ZIP 并触发浏览器下载。 */
  async exportArchive(token: string): Promise<string> {
    const res = await fetch('/api/admin/config/export', { headers: { [ADMIN_TOKEN_HEADER]: token } })
    if (!res.ok) {
      let errorResponse: ErrorResponse = adminErrorFallback(res.status)
      try {
        errorResponse = await res.json()
      } catch {
        // 非 JSON 错误体时保留默认
      }
      throw new ApiError(errorResponse)
    }
    const blob = await res.blob()
    const fileName = fileNameOf(res, 'initializer-config.zip')
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = fileName
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
    return fileName
  },

  /**
   * 上传 ZIP 归档导入到草稿（**只写草稿，不发布**）。
   *
   * @param token    管理端 token
   * @param file     归档文件
   * @param version  可选整体乐观锁期望版本
   * @param operator 操作人
   */
  async importArchive(
    token: string,
    file: File,
    version: number | null = null,
    operator = 'admin',
  ): Promise<ImportView> {
    const form = new FormData()
    form.append('file', file)
    const headers: Record<string, string> = { [ADMIN_TOKEN_HEADER]: token }
    if (version !== null) headers['If-Match'] = String(version)
    const res = await fetch(`/api/admin/config/import?${queryString({ operator })}`, {
      method: 'POST',
      headers,
      body: form,
    })
    return handleStructured<ImportView>(res)
  },
}

/** 从 Content-Disposition 解析附件文件名（缺失时回退默认）。 */
export function fileNameOf(res: Response, fallback: string): string {
  const disposition = res.headers?.get?.('Content-Disposition') ?? null
  const match = disposition ? /filename="?([^";]+)"?/.exec(disposition) : null
  return match ? match[1] : fallback
}
