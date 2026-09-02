import { api } from './rest'

/**
 * 附件 API · 附件双模式（local-read：前后端同机 >5MB 附件传本地 path 由后端读盘，不 upload）。
 *
 * <p>前端启动拉取 {@link #config()} 决定大文件附件走「传 path」还是「multipart upload」：
 * 后端 nexusai.attachments.local-read 配置（application.yml · 默认 false=远程 upload）。
 */
export const attachmentApi = {
  /** 附件模式配置 → { localRead }（true = 本地桌面：>5MB 拖拽附件传 path） */
  config: () => api<{ localRead: boolean }>('/attachments/config'),
}
