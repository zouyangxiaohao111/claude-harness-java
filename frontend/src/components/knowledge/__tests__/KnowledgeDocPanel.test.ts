import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  flattenFolders,
  folderCrumbs,
  findChildren,
  mergeTagIds,
  tagsFor,
} from '../KnowledgeDocPanel'
import type { KbFolderNode, KbTag, Knowledge } from '@/api/knowledge'

/**
 * 知识库文档区 · 对齐源侧 `KnowledgeBase.vue` documents tab 后的纯函数护栏。
 *
 * <h2>守三类静默缺陷</h2>
 * ① **标签是替换语义**：`PUT /knowledge/tags` 传的是最终集合。
 *    若客户端按「追加」想，直接发 `[新标签]` ⇒ 原有的全被解除，用户看到标签凭空消失。
 * ② **文件夹树是派生的**：从 `folder_path` 字符串算出来，空目录不存在。
 *    面包屑/子目录推导错了会让用户点进去看到空列表。
 * ③ **标签渲染必须读真字段**：曾经这里是 `return []`（因为后端不返回 tags），
 *    结果是「能打标签、永远看不见」。现在后端补了 tags，这里必须真读。
 */

function node(path: string, name: string, count = 0, children: KbFolderNode[] = []): KbFolderNode {
  return { path, name, document_count: count, children }
}

function tag(id: string, name: string, color: string | null = null): KbTag {
  return {
    id, knowledge_base_id: 'kb', name, color, sort_order: null, seq_id: null,
    knowledge_count: 0, created_at: null, updated_at: null,
  }
}

function doc(over: Partial<Knowledge> = {}): Knowledge {
  return {
    id: 'k1', knowledge_base_id: 'kb', type: 'file', channel: 'web',
    title: '规范.txt', description: null, source: '', parse_status: 'completed',
    enable_status: 'enabled', summary_status: 'none', pending_subtasks_count: 0,
    embedding_model_id: null, file_name: '规范.txt', file_type: 'txt',
    file_size: 100, file_path: null, file_hash: null, storage_size: 0,
    folder_path: '', metadata: null, custom_metadata: null, error_message: '',
    processed_at: null, created_at: null, updated_at: null,
    ...over,
  }
}

describe('mergeTagIds（⭐ 替换语义下的客户端合并）', () => {
  it('⭐ 加一个新标签 = 原有 + 新的（⛔ 不是只有新的）', () => {
    // 若这里返回 ['new']，后端会把它当最终集合 ⇒ 原有标签全被解除
    expect(mergeTagIds(['a', 'b'], 'new')).toEqual(['a', 'b', 'new'])
  })

  it('已存在则不重复追加', () => {
    expect(mergeTagIds(['a'], 'a')).toEqual(['a'])
  })

  it('空 id ⇒ 原样返回（不塞空串进标签集合）', () => {
    expect(mergeTagIds(['a'], '')).toEqual(['a'])
  })

  it('空集合 + 新标签 ⇒ 只有一个', () => {
    expect(mergeTagIds([], 'x')).toEqual(['x'])
  })
})

describe('folderCrumbs（路径 → 面包屑链）', () => {
  it('多级路径逐段累积', () => {
    expect(folderCrumbs('a/b/c')).toEqual([
      { path: 'a', name: 'a' },
      { path: 'a/b', name: 'b' },
      { path: 'a/b/c', name: 'c' },
    ])
  })

  it('空路径 ⇒ 空链（根目录没有面包屑）', () => {
    expect(folderCrumbs('')).toEqual([])
  })

  it('多余斜杠被忽略（不入空段）', () => {
    expect(folderCrumbs('/a//b/')).toEqual([
      { path: 'a', name: 'a' },
      { path: 'a/b', name: 'b' },
    ])
  })
})

describe('findChildren（按路径取直接子目录）', () => {
  const tree = [
    node('甲', '甲', 2, [node('甲/子', '子', 1)]),
    node('乙', '乙', 3),
  ]

  it('根 ⇒ 返回全部顶层', () => {
    expect(findChildren(tree, '').map((n) => n.path)).toEqual(['甲', '乙'])
  })

  it('找到指定路径 ⇒ 返回它的直接子级', () => {
    expect(findChildren(tree, '甲').map((n) => n.path)).toEqual(['甲/子'])
  })

  it('叶子节点 ⇒ 空（没有子目录）', () => {
    expect(findChildren(tree, '甲/子')).toEqual([])
  })

  it('不存在的路径 ⇒ 空（⛔ 不抛）', () => {
    expect(findChildren(tree, '不存在')).toEqual([])
  })
})

describe('flattenFolders', () => {
  it('先序展平（父在子前）', () => {
    const tree = [node('a', 'a', 0, [node('a/b', 'b')]), node('c', 'c')]
    expect(flattenFolders(tree).map((n) => n.path)).toEqual(['a', 'a/b', 'c'])
  })
})

describe('tagsFor（⭐⭐ 必须读后端返回的真字段）', () => {
  it('行上有 tags ⇒ 原样带出 name/color', () => {
    const row = doc({ tags: [{ id: 't1', name: '规范', color: '#f00' }] })
    const got = tagsFor(row, [])
    expect(got).toHaveLength(1)
    expect(got[0].name).toBe('规范')
    expect(got[0].color).toBe('#f00')
  })

  it('行上没 tags 字段 ⇒ 空数组（⛔ 不抛、不显示假标签）', () => {
    expect(tagsFor(doc(), [])).toEqual([])
  })

  it('行上有 tags 且库内也有 ⇒ 优先用库内完整对象（含 sort_order 等）', () => {
    const full = { ...tag('t1', '规范', '#0f0'), sort_order: 3 }
    const row = doc({ tags: [{ id: 't1', name: '旧名', color: null }] })
    const got = tagsFor(row, [full])
    expect(got[0].sort_order).toBe(3)
  })
})

describe('源码级接线（面板 → api）', () => {
  const PANEL = fileURLToPath(new URL('../KnowledgeDocPanel.tsx', import.meta.url))
  const src = readFileSync(PANEL, 'utf8').replace(/\r\n?/g, '\n')

  it('文件夹树走派生树端点（不是前端自己拼）', () => {
    expect(src).toContain('knowledgeApi.listFolders')
  })

  it('⭐ 打标签走 knowledgeApi.setKnowledgeTags 且经过 mergeTagIds', () => {
    expect(src).toContain('knowledgeApi.setKnowledgeTags')
    expect(src).toContain('mergeTagIds(')
  })

  it('批量删除/重解析调真实端点，且失败数要报出来', () => {
    expect(src).toContain('knowledgeApi.batchDelete')
    expect(src).toContain('knowledgeApi.batchReparse')
    // 后端返回的是「成功 id 列表 + 失败 map」⇒ 失败必须提示，不能只报成功
    expect(src).toContain('failedCount')
  })

  it('视图模式持久化到 localStorage（源侧同款）', () => {
    expect(src).toContain('localStorage.setItem(VIEW_MODE_KEY')
  })
})
