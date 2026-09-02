/**
 * Database connection REST 端点封装
 * 对应 nexusai-backend Phase C3 端点
 */
import { api } from './rest'
import type {
  DatabaseConnection,
  CreateDatabaseRequest,
  UpdateDatabaseRequest,
  TestConnectionResponse,
} from './types'

export const databaseApi = {
  list: () => api<DatabaseConnection[]>('/databases'),
  create: (req: CreateDatabaseRequest) =>
    api<DatabaseConnection>('/databases', { method: 'POST', body: req }),
  update: (id: string, req: UpdateDatabaseRequest) =>
    api<DatabaseConnection>(`/databases/${encodeURIComponent(id)}`, { method: 'PATCH', body: req }),
  remove: (id: string) =>
    api<void>(`/databases/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  test: (id: string) =>
    api<TestConnectionResponse>(`/databases/${encodeURIComponent(id)}/test`, { method: 'POST' }),
}
