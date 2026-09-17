import { Component, type ErrorInfo, type ReactNode } from 'react'
import { reportFrontendError } from '@/utils/frontLog'

/**
 * 渲染错误边界（OBS2）· 防御性兜底。
 *
 * <p><b>WHY</b>：React 18 起「未捕获的渲染错误会卸载整棵子树」—— 一处 render 抛错就是整屏空白/静止，
 * 而 2026-09-17 的「前端整屏停止更新」复盘时无法排除「渲染链被一个坏节点带崩」这条路径（当时前端
 * 零日志，只能靠 F5 现象反推）。本组件做两件事：
 * <ol>
 *   <li>把错误<b>送进 frontLog</b>（含 componentStack）—— 让「渲染炸了」在日志里有确切位置；</li>
 *   <li>把爆炸范围<b>限制在最小的子树</b>：顶层一个（保整页壳），消息区每行一个（保其余消息）。</li>
 * </ol>
 *
 * <p>⛔ 本组件<b>不是</b>本批的根因修复（根因是 STOMP 通道无心跳、半开连接不可察），是防御性兜底。
 *
 * <p>⚠️ 兜底 UI 文案铁律：只向用户说明现象 + 提供出路，<b>不得出现内部术语</b>（「对齐 CC」这类词
 * 一律禁止出现在 UI 文案里）。
 */
interface Props {
  /** 错误上报标签 + 日志定位线索（如 `MessageList.row` / `Root`）。 */
  tag: string
  /** 兜底 UI 的两行说明（现象 + 影响范围）。 */
  title: string
  hint: string
  /** 紧凑版（消息区每行用）：只占一行高度，避免一条坏消息撑满整屏。 */
  compact?: boolean
  children: ReactNode
}

interface State {
  /** 非空 = 该子树已崩，渲染兜底 UI。 */
  error: Error | null
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  /** React 要求：捕获后先切兜底 UI（这一步不能有副作用，故上报放在 componentDidCatch）。 */
  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    reportFrontendError(this.props.tag, error, info.componentStack ?? '(无 componentStack)')
  }

  /** 「重试」= 清掉边界自己的错误态重新渲染子树（不改动任何业务状态）。 */
  private readonly retry = (): void => {
    this.setState({ error: null })
  }

  render(): ReactNode {
    const { error } = this.state
    if (!error) return this.props.children
    return (
      <div className={this.props.compact ? 'err-boundary compact' : 'err-boundary'} role="alert">
        <div className="err-boundary-title">{this.props.title}</div>
        <div className="err-boundary-hint">{this.props.hint}</div>
        <button type="button" className="err-boundary-retry" onClick={this.retry}>
          重试
        </button>
      </div>
    )
  }
}
