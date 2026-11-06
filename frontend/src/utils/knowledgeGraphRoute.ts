/**
 * 知识图谱演示路由 · 独立入口判定。
 *
 * <p>与 {@link ./standalonePreview} 同族：靠 {@code location.hash} 前缀判定，
 * {@code main.tsx} 据此渲染演示页而<b>跳过</b> {@code LaunchGate}（后端就绪门）
 * 与整 App —— 图谱原型不依赖后端，能独立打开。
 *
 * <p>路由：{@code #/knowledge-graph-demo}
 */

/** 演示路由常量。 */
export const KNOWLEDGE_GRAPH_DEMO_ROUTE = '#/knowledge-graph-demo'

/** 是否为知识图谱演示路由。 */
export function isKnowledgeGraphDemoRoute(): boolean {
  try {
    return window.location.hash.startsWith(KNOWLEDGE_GRAPH_DEMO_ROUTE)
  } catch {
    return false
  }
}
