import { useEffect, useState } from 'react'
import type { PivotCandidateDto } from '@/api/types'
import { PREVIEW_SOURCE_CHARACTERS, projectPreview } from '@/markdown/plainPreview'

interface Props {
  candidates: PivotCandidateDto[]
  loading: boolean
  error: string | null
  onRetry: () => void
  /** 初始 tab：双击 Esc → compact；用户可切换到 trim */
  initialTab?: 'compact' | 'trim'
  onCompact: (messageId: string, direction: 'from' | 'up_to') => void
  onTrim: (messageId: string) => void
  onClose: () => void
}

/** 列表项单行预览（markdown→纯文本投影 + 折叠空白 + 截 60 字）。
 *
 *  <p>previewSource 是服务端已截到 2048 字符（与 {@link PREVIEW_SOURCE_CHARACTERS} 同值）的正文前缀。
 *  <p>投影走 {@link projectPreview}：**先按字符截断源文、再投影**（顺序不可换，已由
 *  markdown/__tests__/plainPreview.test.ts 钉住 —— 反过来等于对全文 parse）。
 *  窗口大小用 {@link PREVIEW_SOURCE_CHARACTERS}（与 harness 同值的单点约定，见 plainPreview.ts）。
 *
 *  <p>投影用 parseGfm（流式臂：**无 math 扩展、不跑 rescue**），故行内 {@code $$x$$} 与粘连标记
 *  （{@code ##标题}，即 rescue 会补空格的那类）**在本预览里保持字面** —— 与展开态渲染可能不同
 *  （预览少剥、不藏内容，两者语法不同源）。投影还逐行 trim，**代码块缩进会被抹平**（已知取舍）。
 *
 *  <p>保留 {@code || '(空消息)'} 兜底：投影结果**可能为空串**（切片全是 thematicBreak / definition
 *  时），不兜底会渲染成一行空白项。
 *
 *  <p><b>与 CC 的有意偏离（规则七：显式暴露冲突、明确择一、标记另一处）</b>。
 *  CC 侧这个列表走的是**单行分支**：调用点传 {@code paddingRight={10}}
 *  （claude-code-best/src/components/MessageSelector.tsx:488-492）→ 落到 {@code :721}
 *  {@code truncate(messageText, columns - paddingRight, true)}；而 {@code truncate} 的第三参
 *  {@code singleLine=true} 是「**有换行就在首个换行处截断并补 …，丢掉后续行**」（无换行才按宽度截）——
 *  claude-code-best/src/utils/truncate.ts:142-153。（{@code :418} 那个无 paddingRight 的分支渲染的
 *  是「待恢复消息」头部、不是列表，别拿它当对照。）
 *  <br>CC 对用户 prompt **只做 stripDisplayTags（剥系统注入的 XML-like 标签）、不剥 markdown**
 *  （{@code :660} {@code const messageText = stripDisplayTags(rawMessageText)}）。
 *  <br>本仓两处都选择偏离：**剥 markdown** + **折空白（保留全部文字）**。理由：用户粘贴代码块 /
 *  标题时，不剥的预览在单行列表里可读性显著变差（{@code **}/{@code #}/围栏占掉本就紧张的 60 字
 *  窗口）；而本仓列表项是单行 HTML（CSS nowrap + ellipsis），折空白比 CC 的「丢后续行」信息更多。 */
function listPreview(c: PivotCandidateDto): string {
  const text = projectPreview(c.previewSource ?? '', PREVIEW_SOURCE_CHARACTERS).replace(/\s+/g, ' ').trim()
  return text.slice(0, 60) || '(空消息)'
}

/**
 * 合并「对话操作」弹窗（tab 区分 压缩 / 裁剪）。
 * - 压缩 tab：partial-compact 单选 pivot + from/up_to 方向（对齐 MessageSelector 交互）
 * - 裁剪 tab：设计稿「功能1：对话裁剪」——⚠️ 标题 + 操作后果 + diff-box 影响预览 + 橙渐变确认钮
 * 键盘：Esc 关闭 / Enter 确认当前 tab（capture 阶段拦截，对齐 TrimConfirmModal 的 keydown 处理）。
 */
export function DialogOpsModal({
  candidates,
  loading,
  error,
  onRetry,
  initialTab = 'compact',
  onCompact,
  onTrim,
  onClose,
}: Props) {
  const [tab, setTab] = useState<'compact' | 'trim'>(initialTab)
  const [compactSel, setCompactSel] = useState<string | null>(null)
  const [direction, setDirection] = useState<'from' | 'up_to'>('from')
  const [trimSel, setTrimSel] = useState<string | null>(null)

  // capture 阶段拦截：阻止 App 层双击 Esc 与 Composer 的 Enter 触发
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        e.stopPropagation()
        onClose()
      } else if (e.key === 'Enter') {
        // 重试按钮的 Enter 激活是浏览器默认动作：capture 拦截会把它一并取消 → 放行
        if ((e.target as HTMLElement | null)?.closest?.('.dops-retry')) return
        e.preventDefault()
        e.stopPropagation()
        if (tab === 'compact') {
          if (compactSel) onCompact(compactSel, direction)
        } else if (trimSel) {
          onTrim(trimSel)
        }
      }
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [onClose, tab, compactSel, direction, trimSel, onCompact, onTrim])

  // 裁剪影响预览：口径 = 该消息及之后将被删除的非 meta 行数（含自身；后端 removedAfter）
  const trimSelItem = trimSel ? candidates.find((c) => c.id === trimSel) ?? null : null
  const trimCount = trimSelItem?.removedAfter ?? 0

  return (
    <div className="dops-backdrop" onClick={onClose}>
      <div className="dops-modal" onClick={(e) => e.stopPropagation()}>
        <div className="dops-head">
          <span>对话操作</span>
          <button className="dops-close" onClick={onClose} title="关闭 (Esc)">×</button>
        </div>
        <div className="dops-tabs">
          <button className={tab === 'compact' ? 'dops-tab active' : 'dops-tab'} onClick={() => setTab('compact')}>
            压缩
          </button>
          <button className={tab === 'trim' ? 'dops-tab active' : 'dops-tab'} onClick={() => setTab('trim')}>
            裁剪
          </button>
        </div>
        <div className="dops-body">
          {loading ? (
            <>
              <div className="dops-status">正在加载消息列表…</div>
              <div className="ms-actions">
                <button className="ms-cancel" onClick={onClose}>取消 <kbd>Esc</kbd></button>
              </div>
            </>
          ) : error ? (
            <>
              <div className="dops-status dops-error">
                <span>加载失败：{error}</span>
                <button className="dops-retry" onClick={onRetry}>重试</button>
              </div>
              <div className="ms-actions">
                <button className="ms-cancel" onClick={onClose}>取消 <kbd>Esc</kbd></button>
              </div>
            </>
          ) : candidates.length === 0 ? (
            <>
              <div className="dops-status">暂无可选消息</div>
              <div className="ms-actions">
                <button className="ms-cancel" onClick={onClose}>取消 <kbd>Esc</kbd></button>
              </div>
            </>
          ) : tab === 'compact' ? (
            <>
              <div className="ms-head">选择压缩切点</div>
              <div className="ms-list">
                {candidates.map((c) => (
                  <div
                    key={c.id}
                    className={compactSel === c.id ? 'ms-item active' : 'ms-item'}
                    onClick={() => setCompactSel(c.id)}
                  >
                    <span className="ms-role">你</span>
                    <span className="ms-text">{listPreview(c)}</span>
                  </div>
                ))}
              </div>
              <div className="ms-dir">
                {/* 方向语义（后端 PartialCompactRequest.Direction，CompactPrompt:24）：from=总结 pivot 之后（较晚）消息、
                    保留此前（头段）；up_to=总结 pivot 之前（较早）消息、保留此后（尾段）。2026-08-24 修正标签并加说明 */}
                <div className="ms-dir-title">压缩方向</div>
                <label className="ms-dir-opt">
                  <input type="radio" checked={direction === 'from'} onChange={() => setDirection('from')} />
                  <span>
                    <b>保留此前（from）</b>
                    <span className="ms-dir-desc">压缩所选消息之后的对话为摘要，此前的记录完整保留</span>
                  </span>
                </label>
                <label className="ms-dir-opt">
                  <input type="radio" checked={direction === 'up_to'} onChange={() => setDirection('up_to')} />
                  <span>
                    <b>保留此后（up_to）</b>
                    <span className="ms-dir-desc">压缩所选消息之前的对话为摘要，此后的记录完整保留</span>
                  </span>
                </label>
              </div>
              <div className="ms-actions">
                <button className="ms-cancel" onClick={onClose}>取消 <kbd>Esc</kbd></button>
                <button
                  className="ms-confirm"
                  disabled={!compactSel}
                  onClick={() => compactSel && onCompact(compactSel, direction)}
                >
                  压缩
                </button>
              </div>
            </>
          ) : (
            <>
              <div className="trim-head">
                <span className="trim-warn-icon">⚠️</span>
                <span>恢复到此前的对话与代码状态？</span>
              </div>
              <div className="trim-hint">
                选择一条消息作为恢复点，此消息及其之后的全部对话记录将被永久删除，模型上下文将回退到此消息之前。
              </div>
              <div className="trim-list">
                {candidates.map((c) => (
                  <div
                    key={c.id}
                    className={trimSel === c.id ? 'trim-item active' : 'trim-item'}
                    onClick={() => setTrimSel(c.id)}
                  >
                    <span className="trim-role">你</span>
                    <span className="trim-text">{listPreview(c)}</span>
                  </div>
                ))}
              </div>
              {trimSel &&
                (trimSelItem ? (
                  <>
                    <div className="trim-effects">
                      <div className="trim-effect">此消息及之后的对话记录将被永久删除</div>
                      <div className="trim-effect">模型上下文回退到此消息之前</div>
                      <div className="trim-effect warn">此操作无法撤销</div>
                    </div>
                    <div className="trim-preview">
                      <div className="trim-preview-title">影响预览 · 将删除 {trimCount} 条消息</div>
                      <div className="trim-preview-row">
                        <span className="trim-role">你</span>
                        <span className="trim-text">{listPreview(trimSelItem)}</span>
                      </div>
                      {trimCount > 1 && <div className="trim-preview-more">… 及其之后的对话共 {trimCount} 条</div>}
                    </div>
                  </>
                ) : null)}
              <div className="trim-actions">
                <button className="trim-cancel" onClick={onClose}>取消 <kbd>Esc</kbd></button>
                <button
                  className="trim-confirm"
                  disabled={!trimSel}
                  onClick={() => trimSel && onTrim(trimSel)}
                >
                  确认恢复 <kbd>Enter</kbd>
                </button>
              </div>
            </>
          )}
        </div>
      </div>
      <style>{`
        .dops-backdrop {
          position: fixed;
          inset: 0;
          background: rgba(20, 20, 19, 0.32);
          backdrop-filter: blur(4px);
          z-index: 500;
          display: flex;
          align-items: center;
          justify-content: center;
          animation: fadeIn 120ms var(--ease);
        }
        @keyframes dopsSlideUp {
          from { opacity: 0; transform: translateY(20px); }
          to { opacity: 1; transform: translateY(0); }
        }
        .dops-modal {
          width: 560px;
          max-width: 94vw;
          max-height: 84vh;
          display: flex;
          flex-direction: column;
          background: var(--surface-1);
          border: 1px solid var(--hairline-strong);
          border-radius: 16px;
          box-shadow: 0 24px 64px rgba(0,0,0,0.15), 0 2px 4px rgba(0,0,0,0.05);
          padding: 20px 24px 22px;
          animation: dopsSlideUp 200ms var(--spring);
        }
        .dops-head {
          display: flex;
          align-items: center;
          justify-content: space-between;
          font-size: 16px;
          font-weight: 700;
          color: var(--ink);
        }
        .dops-close {
          border: none;
          background: transparent;
          color: var(--ink-faint);
          font-size: 20px;
          line-height: 1;
          cursor: pointer;
          padding: 4px 8px;
          border-radius: 8px;
          transition: all 0.15s var(--ease);
        }
        .dops-close:hover { color: var(--ink); background: var(--surface-2); }
        .dops-tabs {
          display: flex;
          gap: 4px;
          margin-top: 16px;
          padding-bottom: 10px;
          border-bottom: 1px solid var(--hairline);
        }
        .dops-tab {
          border: 1px solid transparent;
          background: transparent;
          color: var(--ink-muted);
          padding: 6px 14px;
          border-radius: 8px;
          font-size: 13px;
          font-weight: 600;
          cursor: pointer;
          transition: all 0.15s var(--ease);
        }
        .dops-tab:hover { color: var(--ink); background: var(--surface-2); }
        .dops-tab.active {
          color: #FF7A3D;
          background: var(--accent-soft);
          border-color: rgba(255, 122, 61, 0.4);
        }
        .dops-body {
          margin-top: 14px;
          overflow-y: auto;
          min-height: 0;
        }
        .dops-status {
          display: flex;
          align-items: center;
          gap: 10px;
          padding: 24px 8px;
          font-size: 13px;
          color: var(--ink-muted);
          justify-content: center;
        }
        .dops-status.dops-error { color: var(--error); }
        .dops-retry {
          border: 1px solid var(--hairline-strong);
          background: transparent;
          color: var(--ink-muted);
          padding: 4px 12px;
          border-radius: 8px;
          font-size: 12.5px;
          font-weight: 600;
          cursor: pointer;
        }
        .dops-retry:hover { border-color: var(--ink-muted); color: var(--ink); }

        /* ---- 压缩 tab（ms-*）· 对齐 MessageSelector 交互 ---- */
        .ms-head { font-size: 13px; font-weight: 700; color: var(--ink); margin-bottom: 8px; }
        .ms-list, .trim-list {
          display: flex;
          flex-direction: column;
          gap: 4px;
          max-height: 240px;
          overflow-y: auto;
          border: 1px solid var(--hairline);
          border-radius: 10px;
          padding: 6px;
          background: var(--surface-2);
        }
        .ms-item, .trim-item {
          display: flex;
          align-items: center;
          gap: 8px;
          padding: 7px 10px;
          border: 1px solid transparent;
          border-radius: 8px;
          font-size: 12.5px;
          cursor: pointer;
          transition: all 0.12s var(--ease);
          color: var(--ink-muted);
          background: transparent;
        }
        .ms-item:hover, .trim-item:hover { background: var(--surface-1); color: var(--ink); }
        .ms-item.active, .trim-item.active {
          background: var(--accent-soft);
          border-color: #FF7A3D;
          color: var(--ink);
        }
        .ms-item .ms-role, .trim-item .trim-role {
          flex-shrink: 0;
          font-size: 11px;
          font-weight: 600;
          color: #FF7A3D;
          min-width: 30px;
        }
        .ms-item .ms-text, .trim-item .trim-text {
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
          min-width: 0;
        }
        .ms-dir { margin-top: 10px; font-size: 12.5px; color: var(--ink-muted); }
        .ms-dir-title { font-weight: 600; color: var(--ink); margin-bottom: 8px; font-size: 13px; }
        .ms-dir-opt { display: flex !important; align-items: flex-start !important; gap: 8px !important; padding: 6px 0; cursor: pointer; }
        .ms-dir-opt input { margin-top: 2px; accent-color: #FF7A3D; }
        .ms-dir-opt > span { display: flex; flex-direction: column; gap: 2px; }
        .ms-dir-opt b { color: var(--ink); font-weight: 600; }
        .ms-dir-desc { font-size: 11.5px; color: var(--ink-muted); line-height: 1.5; }

        /* ---- 裁剪 tab（trim-*）· 设计稿「功能1：对话裁剪」---- */
        .trim-head {
          display: flex;
          align-items: center;
          gap: 8px;
          font-size: 15px;
          font-weight: 700;
          color: var(--ink);
        }
        .trim-head .trim-warn-icon { color: #FF7A3D; font-size: 16px; }
        .trim-hint {
          margin-top: 8px;
          font-size: 12.5px;
          color: var(--ink-muted);
          line-height: 1.6;
          background: var(--surface-2);
          border: 1px solid var(--hairline);
          border-radius: 10px;
          padding: 8px 12px;
        }
        .trim-list { margin-top: 10px; }
        .trim-effects {
          margin-top: 12px;
          display: flex;
          flex-direction: column;
          gap: 6px;
          font-size: 13px;
          line-height: 1.6;
          color: var(--ink-muted);
          border: 1px solid var(--hairline);
          background: var(--surface-2);
          border-radius: 10px;
          padding: 10px 12px;
        }
        .trim-effect { margin: 0; }
        .trim-effect.warn { color: var(--error); font-weight: 600; }
        .trim-preview {
          margin-top: 10px;
          border: 1px dashed var(--hairline-strong);
          border-radius: 10px;
          padding: 10px 12px;
          background: var(--surface-2);
        }
        .trim-preview-title {
          font-size: 12px;
          font-weight: 700;
          color: #FF7A3D;
          margin-bottom: 6px;
        }
        .trim-preview-row {
          display: flex;
          align-items: center;
          gap: 8px;
          font-size: 12.5px;
          color: var(--ink-muted);
          overflow: hidden;
        }
        .trim-preview-row .trim-role { color: #FF7A3D; font-size: 11px; font-weight: 600; flex-shrink: 0; }
        .trim-preview-row .trim-text {
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
          min-width: 0;
        }
        .trim-preview-more { font-size: 11.5px; color: var(--ink-faint); margin-top: 4px; }

        /* ---- 共用底部操作（压缩 / 裁剪）---- */
        .ms-actions, .trim-actions {
          margin-top: 16px;
          display: flex;
          justify-content: flex-end;
          gap: 10px;
        }
        .ms-cancel, .trim-cancel {
          border: 1px solid var(--hairline-strong);
          background: transparent;
          color: var(--ink-muted);
          padding: 8px 16px;
          border-radius: 8px;
          font-size: 13px;
          font-weight: 600;
          cursor: pointer;
          transition: all 0.15s var(--ease);
        }
        .ms-cancel:hover, .trim-cancel:hover { border-color: var(--ink-muted); color: var(--ink); }
        .ms-confirm, .trim-confirm {
          background: linear-gradient(135deg, #FF9A5C 0%, #FF7A3D 55%, #E65C00 100%);
          color: #fff;
          border: none;
          padding: 8px 18px;
          border-radius: 8px;
          font-size: 13px;
          font-weight: 600;
          cursor: pointer;
          box-shadow: 0 4px 12px rgba(255, 122, 61, 0.2);
          transition: all 0.15s var(--ease);
        }
        .ms-confirm:hover, .trim-confirm:hover { background: #E65C00; transform: translateY(-1px); }
        .ms-confirm:disabled, .trim-confirm:disabled { opacity: 0.5; cursor: not-allowed; transform: none; box-shadow: none; }
        .ms-cancel kbd, .trim-cancel kbd, .ms-confirm kbd, .trim-confirm kbd {
          font-family: var(--font-mono);
          font-size: 9px;
          padding: 1px 5px;
          background: var(--surface-2);
          border: 1px solid var(--hairline);
          border-radius: 4px;
          color: var(--ink-faint);
          margin-left: 4px;
        }
        .ms-confirm kbd, .trim-confirm kbd {
          background: rgba(255,255,255,0.18);
          border-color: rgba(255,255,255,0.3);
          color: rgba(255,255,255,0.9);
        }
      `}</style>
    </div>
  )
}
