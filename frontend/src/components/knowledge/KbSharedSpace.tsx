import { useMemo, useState } from 'react'
import type { KnowledgeBase } from '@/api/knowledge'

interface Props {
  bases: KnowledgeBase[]
  showToast: (msg: string, type?: 'success' | 'info') => void
}

/** 一个共享空间（组织）。 */
interface SpaceItem {
  id: string
  name: string
  description: string
  isOwner: boolean
  members: number
  knowledgeCount: number
  agentCount: number
}

type Tab = 'all' | 'created' | 'joined'

/**
 * 共享空间 · 逐项对齐源侧 `views/organization/OrganizationList.vue`（见用户给的两张截图）。
 *
 * <h2>⭐ 版式（截图 1 / 截图 2）</h2>
 * <ol>
 *   <li>标题「共享空间」+ 图标（∞ 形）+ 副标题「创建或加入共享空间，让多个空间互相协作，共享知识库与智能体」</li>
 *   <li>右上：`排序 · 最近更新` 下拉 + `加入共享空间` + `创建共享空间`（绿色主按钮）</li>
 *   <li>分类胶囊：`全部 N / 我创建的 N / 我加入的 N`（激活项绿字白底）</li>
 *   <li>
 *     全部视图按分组渲染：`我创建的` 分组标题（人形图标 + 名称 + 数量 + chevron），
 *     下接卡片；`我加入的` 同理，**空分组显示空态**（截图 2）：
 *     灰圆角 ∞ 图标 →「您还没有加入任何共享空间」→「通过邀请码加入现有共享空间」→
 *     `加入共享空间`（描边）+ `创建共享空间`（绿色）
 *   </li>
 *   <li>卡片：头像 + 名称 + 「暂无描述」+ 底部统计条 `N 成员 / N 知识库 / N 智能体`</li>
 * </ol>
 *
 * <h2>⚠️ 后端边界（用户 2026-11-06 裁定：后端组织先不做，前端先对齐）</h2>
 * 本仓没有 organization 表/接口 ⇒ 这里的数据是<b>本地演示态</b>：
 * 默认只有「我创建的」一条（对应本机唯一空间），「我加入的」为空 ——
 * 与截图 1/2 的形态一致。创建/加入点了会明确提示「后端未接入」，⛔ 不假装成功。
 */
export function KbSharedSpace({ bases, showToast }: Props) {
  const [tab, setTab] = useState<Tab>('all')
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({})
  const [createdOpen, setCreatedOpen] = useState(false)
  const [joinOpen, setJoinOpen] = useState(false)
  const [inviteCode, setInviteCode] = useState('')
  const [newName, setNewName] = useState('')

  /** 我创建的：本机唯一空间（知识库数 = 实际库数，智能体数暂按 0）。 */
  const created: SpaceItem[] = useMemo(() => [
    {
      id: 'local',
      name: '本机空间',
      description: '',
      isOwner: true,
      members: 1,
      knowledgeCount: bases.length,
      agentCount: 0,
    },
  ], [bases.length])

  /** 我加入的：后端无组织体系 ⇒ 恒空（截图 2 就是空态）。 */
  const joined: SpaceItem[] = useMemo(() => [], [])

  const counts = {
    all: created.length + joined.length,
    created: created.length,
    joined: joined.length,
  }

  const toggle = (k: string) => setCollapsed((p) => ({ ...p, [k]: !p[k] }))

  // 后端未接入时统一提示（⛔ 不假装成功）
  const notWired = (what: string) =>
    showToast(`${what}：后端组织接口尚未接入，本版仅完成界面`, 'info')

  return (
    <div className="kb-page kb-space-page">
      {/* ── 页头 ───────────────────────────────────────────── */}
      <div className="kb-header">
        <div className="kb-title-row">
          <h2 className="kb-h2">
            <span className="kb-space-logo" aria-hidden="true">∞</span>
            共享空间
          </h2>
          <div className="kb-header-actions">
            <button type="button" className="kb-sort-trigger" title="排序">
              排序 · 最近更新
            </button>
            <button type="button" className="kb-btn ghost" onClick={() => setJoinOpen(true)}>
              ↳ 加入共享空间
            </button>
            <button type="button" className="kb-btn primary" onClick={() => setCreatedOpen(true)}>
              ∞ 创建共享空间
            </button>
          </div>
        </div>
        <p className="kb-subtitle">创建或加入共享空间，让多个空间互相协作，共享知识库与智能体</p>
      </div>

      {/* ── 分类胶囊 + 搜索 ─────────────────────────────────── */}
      <div className="kb-toolbar">
        <div className="kb-cat-tabs" role="tablist" aria-label="共享空间分类">
          {([
            ['all', '全部', counts.all],
            ['created', '我创建的', counts.created],
            ['joined', '我加入的', counts.joined],
          ] as const).map(([key, label, n]) => (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={tab === key}
              className={`kb-cat${tab === key ? ' on' : ''}`}
              onClick={() => setTab(key)}
            >
              {label}<span className="kb-cat-n">{n}</span>
            </button>
          ))}
        </div>
        <input
          className="kb-input kb-space-search"
          placeholder="搜索共享空间…"
          style={{ marginLeft: 'auto' }}
        />
      </div>

      {/* ── 内容：分组 + 卡片 ───────────────────────────────── */}
      <div className="kb-space-main">
        {/* 我创建的 */}
        {(tab === 'all' || tab === 'created') && created.length > 0 && (
          <>
            <div
              className="kb-space-group"
              role="button"
              tabIndex={0}
              aria-expanded={!collapsed.created}
              onClick={() => toggle('created')}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle('created') } }}
            >
              <span className="kb-space-group-icon">⊙</span>
              <span className="kb-space-group-name">我创建的</span>
              <span className="kb-space-group-count">{created.length}</span>
              <span className={`kb-space-group-chev${collapsed.created ? '' : ' is-open'}`}>⌄</span>
            </div>
            {!collapsed.created && (
              <div className="kb-card-wrap kb-space-cards">
                {created.map((s) => <SpaceCard key={s.id} item={s} />)}
              </div>
            )}
          </>
        )}

        {/* 我加入的 */}
        {(tab === 'all' || tab === 'joined') && (
          joined.length > 0 ? (
            <>
              <div
                className="kb-space-group"
                role="button"
                tabIndex={0}
                aria-expanded={!collapsed.joined}
                onClick={() => toggle('joined')}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle('joined') } }}
              >
                <span className="kb-space-group-icon">⊙</span>
                <span className="kb-space-group-name">我加入的</span>
                <span className="kb-space-group-count">{joined.length}</span>
                <span className={`kb-space-group-chev${collapsed.joined ? '' : ' is-open'}`}>⌄</span>
              </div>
              {!collapsed.joined && (
                <div className="kb-card-wrap kb-space-cards">
                  {joined.map((s) => <SpaceCard key={s.id} item={s} />)}
                </div>
              )}
            </>
          ) : (
            /* 空态（截图 2）*/
            <div className="kb-space-empty">
              <div className="kb-space-empty-badge">∞</div>
              <div className="kb-space-empty-title">您还没有加入任何共享空间</div>
              <div className="kb-space-empty-sub">通过邀请码加入现有共享空间</div>
              <div className="kb-space-empty-actions">
                <button type="button" className="kb-btn" onClick={() => setJoinOpen(true)}>
                  ↳ 加入共享空间
                </button>
                <button type="button" className="kb-btn primary" onClick={() => setCreatedOpen(true)}>
                  ∞ 创建共享空间
                </button>
              </div>
            </div>
          )
        )}
      </div>

      {/* 创建共享空间（后端未接入 ⇒ 如实提示）*/}
      {createdOpen && (
        <div className="kb-mini-modal" role="dialog" aria-modal="true" aria-label="创建共享空间">
          <div className="kb-mini-head">
            <strong>创建共享空间</strong>
            <button type="button" className="kb-mini-x" onClick={() => setCreatedOpen(false)}>✕</button>
          </div>
          <div className="kb-mini-body">
            <label className="kb-mini-label">空间名称</label>
            <input
              className="kb-input"
              placeholder="例如：农污治理团队"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              autoFocus
            />
          </div>
          <div className="kb-mini-foot">
            <button type="button" className="kb-btn ghost" onClick={() => setCreatedOpen(false)}>取消</button>
            <button
              type="button"
              className="kb-btn primary"
              disabled={!newName.trim()}
              onClick={() => { setCreatedOpen(false); notWired('创建共享空间') }}
            >
              创建
            </button>
          </div>
        </div>
      )}

      {/* 加入共享空间（邀请码）*/}
      {joinOpen && (
        <div className="kb-mini-modal" role="dialog" aria-modal="true" aria-label="加入共享空间">
          <div className="kb-mini-head">
            <strong>加入共享空间</strong>
            <button type="button" className="kb-mini-x" onClick={() => setJoinOpen(false)}>✕</button>
          </div>
          <div className="kb-mini-body">
            <label className="kb-mini-label">邀请码</label>
            <input
              className="kb-input"
              placeholder="粘贴管理员给你的邀请码"
              value={inviteCode}
              onChange={(e) => setInviteCode(e.target.value)}
              autoFocus
            />
            <p className="kb-mini-tip">加入后会出现在「我加入的」分组里。</p>
          </div>
          <div className="kb-mini-foot">
            <button type="button" className="kb-btn ghost" onClick={() => setJoinOpen(false)}>取消</button>
            <button
              type="button"
              className="kb-btn primary"
              disabled={!inviteCode.trim()}
              onClick={() => { setJoinOpen(false); notWired('加入共享空间') }}
            >
              加入
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

/** 空间卡片（截图 1：头像 + 名称 + 暂无描述 + 统计条）。 */
function SpaceCard({ item }: { item: SpaceItem }) {
  return (
    <div className="kb-card kb-space-card" role="link" tabIndex={0} title={item.name}>
      <div className="kb-card-header">
        <span className="kb-space-avatar">∞</span>
        <span className="kb-card-title">
          <span className="kb-card-title-text">{item.name}</span>
        </span>
      </div>
      <div className="kb-card-content">
        {item.description || '暂无描述'}
      </div>
      <div className="kb-space-stats">
        <span className="kb-space-stat"><i>⊙</i>{item.members} 成员</span>
        <span className="kb-space-stat"><i>▤</i>{item.knowledgeCount} 知识库</span>
        <span className="kb-space-stat"><i>◉</i>{item.agentCount} 智能体</span>
      </div>
    </div>
  )
}
