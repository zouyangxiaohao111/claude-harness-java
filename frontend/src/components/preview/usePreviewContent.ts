import { useEffect, useState } from 'react'
import { BASE_URL } from '@/api/rest'
import type { StandalonePayload } from '@/utils/standalonePreview'

export interface PreviewContent {
  /** html 内联内容（type=html） */
  code?: string
  /** 可直接播放/展示的源 url（后端 / blob objectURL / dataURL / 项目 raw 端点） */
  url?: string
  /** 字节（docx/xlsx 解析阶段，fetch/读盘/decode 产物） */
  bytes?: Uint8Array | null
  loading: boolean
  error?: string | null
}

const backendUrl = (u: string) => (u.startsWith('http') ? u : `${BASE_URL}${u}`)

/** 项目文件 raw 端点（项目内 docx/xlsx/pdf/image/视频等二进制走此端点取字节/源）。 */
export function projectRawUrl(id: string, path: string): string {
  return `${BASE_URL}/projects/${id}/raw?path=${encodeURIComponent(path)}`
}

/** base64 字符串 → Uint8Array（docx/xlsx 附件解码用）。 */
function base64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  return bytes
}

/**
 * 独立预览内容 resolver · 统一「附件三态 + 项目端点」→ 独立窗可渲染的 code/url/bytes。
 *
 * <ul>
 *   <li>type=html → code 直传</li>
 *   <li>docx/xlsx → 解析字节（附件 path 读盘 / base64 decode / url fetch；项目走 raw 端点）</li>
 *   <li>pdf/image/video/audio → 可播放 url：附件按 path→Blob objectURL（带 revoke）/
 *       base64→dataURL / url→后端；项目 → 项目 raw 端点 url（pdf 组件内部 fetch）</li>
 *   <li>file / 无源 → error 文案</li>
 * </ul>
 * 全程 cancelled 守卫 + objectURL revoke（React.StrictMode dev 双执行安全）。
 */
export function usePreviewContent(payload: StandalonePayload | null): PreviewContent {
  const [code, setCode] = useState<string | undefined>(undefined)
  const [url, setUrl] = useState<string | undefined>(undefined)
  const [bytes, setBytes] = useState<Uint8Array | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    let objUrl: string | null = null
    setError(null)
    setLoading(false)
    setBytes(null)
    setUrl(undefined)
    if (!payload) { setCode(undefined); return }

    if (payload.type === 'html') { setCode(payload.code); return }

    const item = payload.item
    const project = payload.project
    // 字节源：附件 path/base64/url 或项目 raw 端点
    const readBytes = async (): Promise<Uint8Array> => {
      if (item) {
        if (item.path) {
          const { readFile } = await import('@tauri-apps/plugin-fs')
          return readFile(item.path)
        }
        if (item.base64) return base64ToBytes(item.base64)
        if (item.url) {
          const res = await fetch(backendUrl(item.url))
          if (!res.ok) throw new Error(`HTTP ${res.status}`)
          return new Uint8Array(await res.arrayBuffer())
        }
        throw new Error('附件内容不可用（缺少 url/path/base64 源）')
      }
      if (project) {
        const res = await fetch(projectRawUrl(project.id, project.path))
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        return new Uint8Array(await res.arrayBuffer())
      }
      throw new Error('缺少内容源')
    }

    const isByteKind = payload.type === 'docx' || payload.type === 'xlsx'
    setLoading(true)
    ;(async () => {
      try {
        if (item) {
          if (isByteKind) {
            setBytes(await readBytes())
          } else {
            let u: string
            if (item.path) {
              const b = await readBytes()
              objUrl = URL.createObjectURL(new Blob([b.slice()], { type: item.mediaType ?? 'application/octet-stream' }))
              u = objUrl
            } else if (item.base64) {
              u = `data:${item.mediaType || 'application/octet-stream'};base64,${item.base64}`
            } else if (item.url) {
              u = backendUrl(item.url)
            } else {
              throw new Error('附件内容不可用（缺少 url/path/base64 源）')
            }
            if (!cancelled) setUrl(u)
          }
        } else if (project) {
          if (isByteKind) {
            setBytes(await readBytes())
          } else {
            if (!cancelled) setUrl(projectRawUrl(project.id, project.path))
          }
        } else {
          throw new Error('暂不支持预览该内容')
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => { cancelled = true; if (objUrl) URL.revokeObjectURL(objUrl) }
  }, [payload])

  return { code, url, bytes, loading, error }
}
