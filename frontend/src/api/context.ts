/**
 * /context analyze REST 端点封装
 * 对应 nexusai-backend ContextAnalyzeController（POST /api/v1/context/analyze）
 */
import { api } from './rest'
import type { ContextAnalyzeRequest, ContextAnalyzeResponse } from './types'

export const contextApi = {
  /** 上下文分析（system/memory/tools 计数分类展示） */
  analyze: (req?: ContextAnalyzeRequest) =>
    api<ContextAnalyzeResponse>('/context/analyze', { method: 'POST', body: req ?? undefined }),
}
