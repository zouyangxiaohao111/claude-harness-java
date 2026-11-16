/**
 * 运行时环境探测 REST 端点（只读，不安装）。
 *
 * 对应后端 `GET /api/v1/doctor/runtime`：Node / Python / uv 等的可用性、路径、
 * 版本与安装提示。判据是后端实际执行 `--version`（Windows Store 的 0 字节
 * python 别名会被排除，不会误报为已安装）。
 */
import { api } from './rest'

export interface RuntimeInfo {
  available: boolean
  /** 命中的可执行文件路径；未命中为 null */
  path: string | null
  /** `--version` 输出原文（如 `v20.11.1` / `Python 3.12.1`） */
  version: string | null
  /** 未命中原因 */
  error: string | null
  /** 安装命令（不自动执行，供用户复制或 Agent 经权限审批后执行） */
  installCommand?: string
  /** 官方下载页 */
  installUrl?: string
}

export interface RuntimeEnvResponse {
  platform: string
  runtimes: Record<string, RuntimeInfo>
  /** 影响 MCP stdio server 启动的缺失项（如 `node`、`python/uv`） */
  missingForMcp: string[]
}

export const doctorApi = {
  runtime: () => api<RuntimeEnvResponse>('/doctor/runtime'),
}
