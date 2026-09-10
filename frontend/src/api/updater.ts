import { invoke } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'

/** Rust updater.rs UpdateInfo（available/version/notes/url/sha256/source） */
export interface UpdateInfo {
  available: boolean
  version: string
  notes: string
  published?: string | null
  url: string
  sha256: string
  source: string
}

/**
 * 默认源（与 Rust DEFAULT_SOURCES 一致：自建源占位符 + 公开 GitHub release 兜底）。
 * 自建源地址不入库；实际生效的是 Rust 侧解析：`update_check` 的 sources 参数，
 * 或运行环境变量 NEXUSAI_UPDATER_SOURCES（逗号分隔）。
 */
export const DEFAULT_UPDATER_SOURCES = [
  'https://your-update-host.example/nexusai/updater/latest.json',
  'https://github.com/zouyangxiaohao111/claude-harness-java/releases/latest/download/latest.json',
]

export function updateCheck(currentVersion: string, sources?: string[]): Promise<UpdateInfo> {
  return invoke<UpdateInfo>('update_check', {
    currentVersion,
    sources: sources && sources.length ? sources : null,
  })
}

export function updateDownload(info: UpdateInfo): Promise<string> {
  return invoke<string>('update_download', { info })
}

export function updateInstall(path: string): Promise<null> {
  return invoke<null>('update_install', { path })
}

/** 事件订阅：返回取消函数 */
export function onUpdateProgress(cb: (done: number, total?: number | null) => void): Promise<() => void> {
  return listen<{ done: number; total?: number | null }>('update://progress', (e) => cb(e.payload.done, e.payload.total))
}
export function onUpdateReady(cb: (path: string) => void): Promise<() => void> {
  return listen<{ path: string }>('update://ready', (e) => cb(e.payload.path))
}
export function onUpdateFound(cb: (info: UpdateInfo) => void): Promise<() => void> {
  return listen<UpdateInfo>('update://found', (e) => cb(e.payload))
}
