import { create } from 'zustand'
import type { ChatMessageDto } from '../api/types'

export type AttachmentItem = NonNullable<ChatMessageDto['userAttachments']>[number]

/** 右侧覆盖预览内容：附件（PDF/docx/视频/音频）或 HTML 运行结果 */
export interface PreviewTab {
  kind: 'attachment' | 'html'
  title: string
  /** attachment：内容源（path 本地读 / base64 / url 后端） */
  item?: AttachmentItem
  /** html：代码块原文（sandbox iframe 运行） */
  code?: string
}

interface PreviewState {
  /** 当前覆盖右栏的预览（null = 不预览，显示原 tabs/body） */
  preview: PreviewTab | null
  open: (tab: PreviewTab) => void
  close: () => void
}

export const usePreviewStore = create<PreviewState>()((set) => ({
  preview: null,
  open: (tab) => set({ preview: tab }),
  close: () => set({ preview: null }),
}))
