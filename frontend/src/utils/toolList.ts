/**
 * 工具清单归一化：后端两处 `/test` 返回的形状不同。
 *
 * - 连接器 `POST /api/market/connectors/{id}/test` → `{ name, description }[]`
 * - MCP `POST /api/mcp/{id}/test` → `string[]`（只有工具名）
 *
 * <p>展示层（连接器抽屉工具清单、MCP 行测试结果）统一用 {@link ToolItem}，避免两套渲染。
 */
export interface ToolItem {
  name: string
  description?: string | null
}

export function normalizeToolList(
  tools?: Array<string | { name?: string; description?: string | null }> | null,
): ToolItem[] {
  const out: ToolItem[] = []
  for (const t of tools ?? []) {
    if (typeof t === 'string') {
      if (t.trim()) out.push({ name: t })
    } else if (t && typeof t === 'object' && t.name && t.name.trim()) {
      out.push({ name: t.name, description: t.description ?? null })
    }
  }
  return out
}
