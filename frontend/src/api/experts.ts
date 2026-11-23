import { api } from './rest'

/**
 * 内置专家目录（随安装包提供、默认不激活）REST 客户端。
 *
 * <p>契约：后端 `apis/expert/BundledExpertController.java`，前缀 `/api/v1/experts/bundled`。
 * <ul>
 *   <li>`GET /` → {@link BundledExpert}[]（可按 division / query 过滤）</li>
 *   <li>`POST /install` body `{slug}`（可带 `?overwrite=true`）→ {@link BundledInstallResponse}</li>
 * </ul>
 *
 * <p>`installed` 由后端按目标文件是否存在计算；前端不做本地乐观置位
 * （安装成功才依据响应改状态，409 不伪造成功）。
 */
export interface BundledExpert {
  slug: string
  division: string
  name: string
  description: string
  emoji?: string | null
  color?: string | null
  installed: boolean
}

export type BundledInstallStatus = 'INSTALLED' | 'OVERWRITTEN' | 'UNCHANGED'

export interface BundledInstallResponse {
  status: BundledInstallStatus
  slug: string
  path: string
}

export const expertsApi = {
  /** 内置专家目录 · GET /api/v1/experts/bundled */
  listBundled: (division?: string, query?: string) => {
    const params = new URLSearchParams()
    if (division) params.set('division', division)
    if (query) params.set('query', query)
    const qs = params.toString()
    return api<BundledExpert[]>(`/experts/bundled${qs ? `?${qs}` : ''}`)
  },
  /** 安装单个内置专家 · POST /install（目标已存在且内容不同 → 409，除非 overwrite=true） */
  installBundled: (slug: string, overwrite = false) =>
    api<BundledInstallResponse>(
      `/experts/bundled/install${overwrite ? '?overwrite=true' : ''}`,
      { method: 'POST', body: { slug } },
    ),
}
