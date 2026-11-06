/**
 * 知识图谱演示页（原型 · MOCK 数据）。
 *
 * <p><b>用途</b>：在接后端 API 之前，先让用户确认图谱的<b>视觉与手感</b>
 * （银河观感、细线、缩放平移、点击选中）。
 *
 * <p><b>⚠️ 数据是 MOCK</b>（见 {@link ./mockGraphData}）。后端
 * {@code POST /knowledge-bases/{id}/graph-search} 落地后，替换数据源即可。
 *
 * <p>独立路由：{@code #/knowledge-graph-demo}（见 {@code main.tsx} 分流）。
 */

import { useMemo, useState } from 'react'
import { KnowledgeGraphCanvas } from './KnowledgeGraphCanvas'
import { buildMockGraph } from './mockGraphData'
import type { GraphNodeInput } from './graphTypes'

/** 演示页。 */
export function KnowledgeGraphDemo() {
  const [docCount, setDocCount] = useState(6)
  const graph = useMemo(() => buildMockGraph(docCount), [docCount])
  const [selected, setSelected] = useState<GraphNodeInput | null>(null)

  return (
    <div className="kg-demo">
      <header className="kg-demo-head">
        <div className="kg-demo-brand">
          <span className="kg-demo-mark">✦</span>
          <div>
            <div className="kg-demo-title">知识星图 · 银河版</div>
            <div className="kg-demo-sub">MOCK 数据 · 连线已调细 · 尘埃带随镜头移动</div>
          </div>
        </div>
        <div className="kg-demo-actions">
          <label className="kg-demo-label">
            文档数
            <input
              type="range"
              min={2}
              max={12}
              value={docCount}
              onChange={(e) => setDocCount(Number(e.target.value))}
            />
            <span>{docCount}</span>
          </label>
          <span className="kg-demo-count">
            {graph.nodes.length} 节点 · {graph.edges.length} 边
          </span>
        </div>
      </header>

      <div className="kg-demo-stage">
        <KnowledgeGraphCanvas
          nodes={graph.nodes}
          edges={graph.edges}
          onSelect={setSelected}
        />
      </div>

      <footer className="kg-demo-foot">
        {selected ? (
          <span>
            选中：<strong>{selected.name}</strong>（{selected.type}）
          </span>
        ) : (
          <span>滚轮缩放 · 拖拽平移 · 拖节点重排 · 单击选中</span>
        )}
      </footer>
    </div>
  )
}
