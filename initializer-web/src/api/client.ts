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
  ValidationResult,
} from '@/types/api'

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
}
