import { api } from './rest'
import type { SessionDto } from './types'
import type { Project } from '@/types'

/** 对齐后端 ProjectDto（nexusai-backend ProjectController + ProjectService） */
export interface ProjectDto {
  id: string
  name: string
  path: string | null
  branch: string | null
  dirty: number | null
  agents: number | null
  lastIndexedAt: string | null
  bound: boolean
}
/** POST /projects 请求 · 后端 name/path 均必填（@NotBlank） */
export interface ProjectCreateRequest { name: string; path: string }
export interface ProjectBindRequest { projectId: string }

/** 项目文件树节点（GET /api/v1/projects/{id}/files · IDE 项目结构视图） */
export interface FileNode {
  name: string
  path: string
  type: 'dir' | 'file'
  children: FileNode[] | null
}

/** 项目文件内容（GET /api/v1/projects/{id}/file?path=） */
export interface FileContent {
  path: string
  content: string
  size: number
}

/** ProjectDto → UI Project（branch/dirty/agents/path 可能为 null，填默认值） */
export function toProject(d: ProjectDto): Project {
  return {
    id: d.id,
    name: d.name,
    branch: d.branch ?? '',
    dirty: d.dirty ?? 0,
    agents: d.agents ?? 0,
    path: d.path ?? '',
  }
}

export const projectApi = {
  list: () => api<ProjectDto[]>('/projects'),
  create: (req: ProjectCreateRequest) => api<ProjectDto>('/projects', { method: 'POST', body: req }),
  remove: (id: string) => api<void>(`/projects/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  bind: (sessionId: string, req: ProjectBindRequest) =>
    api<SessionDto>(`/sessions/${encodeURIComponent(sessionId)}/project`, { method: 'PUT', body: req }),
  unbind: (sessionId: string) =>
    api<void>(`/sessions/${encodeURIComponent(sessionId)}/project`, { method: 'DELETE' }),
  /** 项目文件树（IDE 结构 · git ls-files） */
  files: (id: string) => api<FileNode[]>(`/projects/${encodeURIComponent(id)}/files`),
  /** 项目文件内容（点击文件查看真实内容） */
  file: (id: string, path: string) =>
    api<FileContent>(`/projects/${encodeURIComponent(id)}/file?path=${encodeURIComponent(path)}`),
  /** 项目文件写入（Monaco 编辑保存 · PUT body 复用 FileContent.content） */
  write: (id: string, path: string, content: string) =>
    api<FileContent>(`/projects/${encodeURIComponent(id)}/file?path=${encodeURIComponent(path)}`, { method: 'PUT', body: { content } }),
}
