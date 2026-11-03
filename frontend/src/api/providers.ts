/**
 * Provider / Model REST 端点封装
 * 对应 nexusai-backend Phase 3 端点
 */
import { api } from './rest'
import type {
  Provider,
  CreateProviderRequest,
  UpdateProviderRequest,
  Model,
  CreateModelRequest,
  UpdateModelRequest,
  TestConnectionResponse,
  RemoteModelsResponse,
} from './types'

export const providerApi = {
  // ---- Provider CRUD ----
  list: () => api<Provider[]>('/providers'),

  get: (id: string) => api<Provider>(`/providers/${encodeURIComponent(id)}`),

  create: (req: CreateProviderRequest) =>
    api<Provider>('/providers', { method: 'POST', body: req }),

  update: (id: string, req: UpdateProviderRequest) =>
    api<Provider>(`/providers/${encodeURIComponent(id)}`, { method: 'PATCH', body: req }),

  remove: (id: string) =>
    api<void>(`/providers/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  test: (id: string) =>
    api<TestConnectionResponse>(`/providers/${encodeURIComponent(id)}/test`, { method: 'POST' }),

  /**
   * 拉取提供商的**远端**模型清单（`GET {baseUrl}/models`）。
   *
   * <p>⚠️ 与 {@link providerApi.listModels} 语义不同：后者是**库里已配置**的模型（持久化结果），
   * 本方法是**提供商当前支持**的清单（外部事实，供导入用）。路径用 `/models/remote` 区分。
   */
  remoteModels: (id: string) =>
    api<RemoteModelsResponse>(`/providers/${encodeURIComponent(id)}/models/remote`),

  // ---- Model CRUD ----
  listModels: (providerId: string) =>
    api<Model[]>(`/providers/${encodeURIComponent(providerId)}/models`),

  createModel: (providerId: string, req: CreateModelRequest) =>
    api<Model>(`/providers/${encodeURIComponent(providerId)}/models`, { method: 'POST', body: req }),

  updateModel: (id: string, req: UpdateModelRequest) =>
    api<Model>(`/models/${encodeURIComponent(id)}`, { method: 'PATCH', body: req }),

  removeModel: (id: string) =>
    api<void>(`/models/${encodeURIComponent(id)}`, { method: 'DELETE' }),
}