import { useMemo, useState } from 'react'
import type { KnowledgeBase } from '@/api/knowledge'

interface Props {
  /**
   * 本机知识库列表（**当前不参与渲染**）。
   *
   * <p>⚠️ 曾经用它给一张假的「本机空间」卡片凑统计数，那是错的（见下方 `created` 的注释）。
   * 现在组织数据必须来自真实的 organization 接口 —— 本仓还没有那条接口，
   * 所以这个 prop 暂时只是占位。接组织接口后它应当被删掉，⛔ 不要再用它凑统计。
   */
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
 * 共享空间 · 逐项对齐源侧 `views/organization/OrganizationList.vue`。
 *
 * <h2>⭐ 版式（截图 1 / 截图 2）</h2>
 * <ol>
 *   <li>标题「共享空间」+ 图标 + 副标题「创建或加入共享空间，让多个空间互相协作，共享知识库与智能体」</li>
 *   <li>右上：`排序 · 最近更新` 下拉 + `加入共享空间` + `创建共享空间`（绿色主按钮）</li>
 *   <li>分类胶囊：`全部 N / 我创建的 N / 我加入的 N`（激活项绿字白底）</li>
 *   <li>
 *     全部视图按分组渲染：`我创建的` 分组标题（人形图标 + 名称 + 数量 + chevron），
 *     下接卡片；`我加入的` 同理，**空分组显示空态**（截图 2）：
 *     圆角图标 →「您还没有加入任何共享空间」→「通过邀请码加入现有共享空间」→
 *     `加入共享空间`（描边）+ `创建共享空间`（绿色）
 *   </li>
 *   <li>卡片：头像 + 名称 + 「暂无描述」+ 底部统计条 `N 成员 / N 知识库 / N 智能体`</li>
 * </ol>
 *
 * <h2>⚠️ 后端边界（用户 2026-11-06 裁定：后端组织先不做，前端先对齐）</h2>
 * 本仓没有 organization 表/接口 ⇒ 这里的数据是<b>本地演示态</b>：
 * 默认只有「我创建的」一条（对应本机唯一空间），「我加入的」为空 ——
 * 与截图 1/2 的形态一致。创建/加入点了会明确提示「后端未接入」，⛔ 不假装成功。
 *
 * <h2>类名依据（见报告 §3/§5）</h2>
 * 源站本页 DOM = `.header > .header-title > .title-row > (h2 + .header-actions)`、
 * 工具条来自 `components/ResourceListToolbar.vue`（`.resource-toolbar > .scope-filters >
 * .category-tabs > button + .scope-count`）、卡片来自 `resource-card.less` 的 `resource-card()`
 * 混入（`.kb-card` 同构）。分组标题复用 KB 列表的 `.kb-section-header`
 * （`KnowledgeBaseList.vue:71`，其子元素按 `[class$=-section-count]` /
 * `[class$=-section-toggle]` 命中），空态复用 `components/EmptyState.vue` 的 `.wk-empty-state`。
 * ⛔ 设计稿里的 `.space-*` / `.cat-tabs` / `.page-head` 在源站源码与 `styles/kb/*.css` 中**均为 0 命中**，
 * 为零样式的稿内自造类，已按源站真类名替换。
 */
export function KbSharedSpace({ showToast }: Props) {
  const [tab, setTab] = useState<Tab>('all')
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({})
  const [createdOpen, setCreatedOpen] = useState(false)
  const [joinOpen, setJoinOpen] = useState(false)
  const [inviteCode, setInviteCode] = useState('')
  const [newName, setNewName] = useState('')

  /**
   * 我创建的 / 我加入的 —— **都必须来自真实的组织接口**。
   *
   * <p>⭐⭐ 这里原来写死了一张「本机空间」卡片（`id: 'local'`），把本机知识库数当成
   * 某个共享空间的统计量。那是**自造数据**：源侧 `OrganizationList.vue:676-679`
   * 的两组计数是 `organizations.filter(o => o.is_owner)` / `!is_owner`，
   * 即只统计用户真实创建或加入的组织。源站**没有「本机空间」这个概念**
   * （grep `internal/` 无任何 default/seed 组织）。
   *
   * <p>本仓没有 organization 表与接口 ⇒ 真实答案就是「零个组织」，
   * 两组都走空态（与源站新装用户的界面一致）。
   * ⛔ 不要再用「本机知识库数」凑一张卡片 —— 那会让用户以为已经有一个共享空间，
   * 点进去却什么也管不了。
   */
  const created: SpaceItem[] = useMemo(() => [], [])
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
    /* 根容器：源侧 `OrganizationList.vue:2-3` 是 `.org-list-container > .org-list-content`，
       而这两个类的声明就来自 `@import (reference) '@/components/css/resource-card.less'`
       的 `.resource-list-content()` 混入（见 OrganizationList.vue:1085-1087）。
       该混入已被提取成 `.kb-list-container` / `.kb-list-content`（kb-01.css，逐字同值），
       而 `.org-list-container` / `.org-list-content` 在 `styles/kb/*.css` 里 0 条规则
       （org 页的 scoped less 没被抓进 bundle）⇒ 用有真规则的等价类，DOM 层级不变。 */
    <div className="kb-list-container">
      <div className="kb-list-content">
        {/* ── 页头（源侧 OrganizationList.vue:4-32 —— .header > .header-title > .title-row）── */}
        <div className="header">
          <div className="header-title">
            <div className="title-row">
              <h2>
                {/* 源侧是 <ResourceIcon type="organization" :size="24"/>（遮罩填色）。
                    本仓无该组件 ⇒ 真 <svg> + `.resource-icon`，并把 mask 用的
                    `background-color:currentColor` 清掉（否则 24×24 成实心色块）。 */}
                <svg className="resource-icon" width={24} height={24} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" style={{ backgroundColor: 'transparent' }} aria-hidden="true">
                  <path d="M7 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z" />
                  <path d="M17 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z" />
                  <path d="M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z" />
                  <path d="M11 21v-2a4 4 0 0 1 8 0v2M1 21v-2a4 4 0 0 1 5-3.9" />
                </svg>
                共享空间
              </h2>
              <div className="header-actions">
                {/* 源侧 ResourceSortControl.vue:20 —— .resource-sort-trigger */}
                <button type="button" className="resource-sort-trigger" title="排序">
                  <span className="resource-sort-trigger__label">排序 · 最近更新</span>
                </button>
                {/* 源侧 OrganizationList.vue:12-29 —— 两个 t-button 都带 .header-action-btn */}
                <button
                  type="button"
                  className="t-button t-button--variant-text t-button--theme-default t-size-s header-action-btn"
                  onClick={() => setJoinOpen(true)}
                >
                  <span className="t-button__text">↳ 加入共享空间</span>
                </button>
                <button
                  type="button"
                  className="t-button t-button--variant-text t-button--theme-default t-size-s header-action-btn"
                  onClick={() => setCreatedOpen(true)}
                >
                  <span className="t-button__text">∞ 创建共享空间</span>
                </button>
              </div>
            </div>
            <p className="header-subtitle">创建或加入共享空间，让多个空间互相协作，共享知识库与智能体</p>
          </div>
        </div>

        {/* ── 分类胶囊 + 搜索（源侧 ResourceListToolbar.vue:2-22）──────────── */}
        <div className="resource-toolbar">
          <div className="scope-filters">
            <div className="category-tabs" role="group" aria-label="共享空间分类">
              {([
                ['all', '全部', counts.all],
                ['created', '我创建的', counts.created],
                ['joined', '我加入的', counts.joined],
              ] as const).map(([key, label, n]) => (
                <button
                  key={key}
                  type="button"
                  aria-pressed={tab === key}
                  onClick={() => setTab(key)}
                >
                  {label}<span className="scope-count">{n}</span>
                </button>
              ))}
            </div>
          </div>
          {/* 搜索：源侧 `t-input class="search-input"`（自带 margin-left:auto，见 kb-orphan.css）*/}
          <div className="t-input__wrap search-input">
            <div className="t-input t-input--prefix">
              <span className="t-input__prefix t-input__prefix-icon" aria-hidden="true">⌕</span>
              <input className="t-input__inner" placeholder="搜索共享空间…" aria-label="搜索共享空间" />
            </div>
          </div>
        </div>

        {/* ── 内容：分组 + 卡片 ───────────────────────────────── */}
        <div className="kb-list-main org-list-main">
          {/* ⚠️ 分组标题必须与卡片同处一个 `.kb-card-wrap`（grid）里：
              源侧 `OrganizationList.vue:59-86` 就是这样写的，
              `.kb-section-header{grid-column:1 / -1}` 只有落在 grid 容器内才生效；
              放在 grid 外面它只是一行普通流式文本（无全宽跨列语义）。 */}
          {(tab === 'all' || tab === 'created') && created.length > 0 && (
            <div className="kb-card-wrap org-card-wrap">
              {/* 分组标题：源侧 `.org-section-header`；视觉规则复用 KB 列表的 `.kb-section-header`
                  （子元素由 `[class$=-section-count]` / `[class$=-section-toggle]` 命中）*/}
              <div
                className="kb-section-header org-section-header"
                role="button"
                tabIndex={0}
                aria-expanded={!collapsed.created}
                onClick={() => toggle('created')}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle('created') } }}
              >
                <span aria-hidden="true">⊙</span>
                <span>我创建的</span>
                <span className="org-section-count">{created.length}</span>
                <span className={`org-section-toggle${collapsed.created ? '' : ' is-open'}`}>⌄</span>
              </div>
              {!collapsed.created && created.map((s) => <SpaceCard key={s.id} item={s} />)}
            </div>
          )}

          {/* 我创建的：空态 —— **只在「我创建的」页签显示**。
              ⛔ 「全部」页签下不显示它：否则会和下面「我加入的」空态各带一个
              「创建共享空间」按钮，同一个页面出现两个同名主按钮（用户实测截图指出）。
              源站「全部」页签下也只有「我加入的」那一个空态（见设计稿 07）。 */}
          {tab === 'created' && created.length === 0 && (
            <div className="wk-empty-state">
              <div className="wk-empty-state__icon">⊙</div>
              <div className="wk-empty-state__title">你还没有创建任何共享空间</div>
              <div className="wk-empty-state__desc">创建一个共享空间，把知识库与智能体共享给其他成员</div>
              <div className="wk-empty-state__actions">
                <button
                  type="button"
                  className="t-button t-button--variant-base t-button--theme-primary t-size-m"
                  onClick={() => setCreatedOpen(true)}
                >
                  <span className="t-button__text">∞ 创建共享空间</span>
                </button>
              </div>
            </div>
          )}

          {/* 我加入的：「全部」与「我加入的」两个页签共用。
              ⭐ 为什么「全部」页签下只留这一个空态：两个空态各带一个「创建共享空间」主按钮
              ⇒ 同一屏出现两个同名主按钮（用户实测截图指出，是错的）。
              设计稿 07 的「全部」页签下也确实只有这一个空态。 */}
          {(tab === 'all' || tab === 'joined') && (
            joined.length > 0 ? (
              <div className="kb-card-wrap org-card-wrap">
                <div
                  className="kb-section-header org-section-header"
                  role="button"
                  tabIndex={0}
                  aria-expanded={!collapsed.joined}
                  onClick={() => toggle('joined')}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle('joined') } }}
                >
                  <span aria-hidden="true">⊙</span>
                  <span>我加入的</span>
                  <span className="org-section-count">{joined.length}</span>
                  <span className={`org-section-toggle${collapsed.joined ? '' : ' is-open'}`}>⌄</span>
                </div>
                {!collapsed.joined && joined.map((s) => <SpaceCard key={s.id} item={s} />)}
              </div>
            ) : (
              /* 空态（截图 2）—— 源侧是 components/EmptyState.vue 的 `.wk-empty-state` 四段；
                 按钮是 t-button（加入=outline / 创建=primary，Org 列表页同款）*/
              <div className="wk-empty-state">
                <div className="wk-empty-state__icon">∞</div>
                <div className="wk-empty-state__title">您还没有加入任何共享空间</div>
                <div className="wk-empty-state__desc">通过邀请码加入现有共享空间</div>
                <div className="wk-empty-state__actions">
                  <button
                    type="button"
                    className="t-button t-button--variant-outline t-button--theme-default t-size-m"
                    onClick={() => setJoinOpen(true)}
                  >
                    <span className="t-button__text">↳ 加入共享空间</span>
                  </button>
                  <button
                    type="button"
                    className="t-button t-button--variant-base t-button--theme-primary t-size-m"
                    onClick={() => setCreatedOpen(true)}
                  >
                    <span className="t-button__text">∞ 创建共享空间</span>
                  </button>
                </div>
              </div>
            )
          )}
        </div>
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

/**
 * 空间卡片（截图 1：头像 + 名称 + 暂无描述 + 统计条）。
 *
 * <p>结构照源侧 `resource-card.less` 的 `resource-card()` 混入 + `OrganizationList.vue:85-160`：
 * `.kb-card > .card-header > (.card-header-left > .kb-type-avatar + .card-title-block > .card-title)
 * + .card-content > .card-description + .card-bottom > .bottom-left > .feature-badges >
 * .feature-badge.is-count > .badge-count`。
 */
function SpaceCard({ item }: { item: SpaceItem }) {
  return (
    <div className="kb-card org-card" role="link" tabIndex={0} title={item.name}>
      <div className="card-header">
        <div className="card-header-left">
          <span className="kb-type-avatar is-document" aria-hidden="true">
            <span style={{ fontSize: 14, color: 'var(--td-brand-color)' }}>∞</span>
          </span>
          <div className="card-title-block">
            <span className="card-title" title={item.name}><span className="card-title-text">{item.name}</span></span>
          </div>
        </div>
      </div>
      <div className="card-content">
        <div className={`card-description${item.description ? '' : ' is-empty'}`}>
          {item.description || '暂无描述'}
        </div>
      </div>
      <div className="card-bottom">
        <div className="bottom-left">
          <div className="feature-badges">
            <div className="feature-badge is-count stat-member"><span className="badge-count">{item.members} 成员</span></div>
            <div className="feature-badge is-count stat-kb"><span className="badge-count">{item.knowledgeCount} 知识库</span></div>
            <div className="feature-badge is-count stat-agent"><span className="badge-count">{item.agentCount} 智能体</span></div>
          </div>
        </div>
      </div>
    </div>
  )
}
