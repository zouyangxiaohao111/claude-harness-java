import { useCallback, useEffect, useMemo, useState } from 'react'
import type { CSSProperties } from 'react'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import {
  deleteMemoryEntry,
  getMemoryConfig,
  getMemoryEntry,
  listMemoryEntries,
  listMemoryFiles,
  saveMemoryEntry,
  saveMemoryFile,
  updateMemoryConfig,
} from '@/api/memory'
import type { MemoryConfig, MemoryEntrySummary, MemoryFileEntry } from '@/api/memory'
import { ApiError } from '@/api/rest'

interface MemoryEditorModalProps {
  onClose: () => void
  showToast: (msg: string, type?: 'success' | 'info') => void
  sessionId?: string
}

/* 继承复位 A —— 旧 `.settings-modal`（`globals.css:4219`）带 `border / backdrop-filter /
   resize:both`，kb 的 `.settings-modal-shell .settings-modal` 只覆盖尺寸/背景/圆角/阴影，
   **不声明**这三条 ⇒ 会继续生效：
   · `border` 在白底上叠一圈白边（设计稿无边框）；
   · `backdrop-filter` 让该元素成为 fixed 子元素的包含块（面板内浮层会错位）；
   · `resize:both` 允许拖拽改尺寸（设计稿固定 1080×780）。
   ⛔ 不改 globals.css（多会话共享），只在本弹窗元素上行内复位。 */
const SETTINGS_MODAL_RESET: CSSProperties = {
  border: 0,
  backdropFilter: 'none',
  WebkitBackdropFilter: 'none',
  resize: 'none',
}

/* 继承复位 B —— 旧 `.settings-content`（`globals.css:4331`）带 `padding:30px 40px` + 灰底，
   kb 那条（`kb-shared.css:678`）只声明 flex/display/min-width/min-height/overflow/background-color，
   **不声明 padding** ⇒ 两层规则同时命中会叠加内边距。设计稿真源 padding 在
   `.content-wrapper`（22px 32px 28px）上。⛔ 同上，行内复位。 */
const SETTINGS_CONTENT_RESET: CSSProperties = {
  padding: 0,
  background: 'transparent',
  overflow: 'hidden',
}

/* 继承复位 C —— 类名撞车：`kb-orphan.css:58` 有源站文档编辑器的
   `.kb-scope .editor-pane{…;overflow:hidden;background:#fff}`（(0,2,0) 特异性），
   会盖掉 globals 里本仓 `.editor-pane{overflow-y:auto;background:--mem-surface-2}`（(0,1,0)）
   ⇒ 编辑区变成不可滚动、底色变白。本条只把这两个属性拿回本仓原值。
   （`.preview-pane` 在 kb 里无同名规则，无需处理。） */
const EDITOR_PANE_RESET: CSSProperties = {
  overflowY: 'auto',
  background: 'var(--mem-surface-2)',
}

type ViewMode = 'edit' | 'preview' | 'split'

const VIEW_TABS: { id: ViewMode; label: string }[] = [
  { id: 'edit', label: '编辑' },
  { id: 'preview', label: '预览' },
  { id: 'split', label: '分屏' },
]

/** type 分组顺序（下拉 optgroup）：User / Project / Managed（只读） */
const TYPE_GROUPS: MemoryFileEntry['type'][] = ['User', 'Project', 'Managed']

/**
 * 记忆条目的四个内容分类（= 条目文件 frontmatter 的 `type:` 值）。
 *
 * 🔴 它们**不是目录**：本机实测 492 个 .md 全部平铺在 `memory/` 根下、零子目录。
 * ⇒ 左栏的真实层级是「用户级 / 项目级」，这四类只是项目级内部的**筛选器**。
 * 未知/缺失 type 的旧文件归「未分类」（`MemoryType.fromString` 对未知返回 null），⛔ 不丢。
 */
const ENTRY_CATEGORIES: { type: string; label: string }[] = [
  { type: 'user', label: '关于你' },
  { type: 'feedback', label: '偏好与反馈' },
  { type: 'project', label: '项目背景' },
  { type: 'reference', label: '参考资料' },
]

/** 条目时间：同一天只显示时分，跨天显示月日（列表窄，省宽度）。 */
function fmtEntryTime(ms: number): string {
  if (!ms || ms <= 0) return '—'
  const d = new Date(ms)
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  const pad = (n: number) => String(n).padStart(2, '0')
  return sameDay
    ? `${pad(d.getHours())}:${pad(d.getMinutes())}`
    : `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 条目体积：1 KB 以下给字节，以上给 KB（499 条 × 几千字，行内必须压缩）。 */
function fmtEntrySize(bytes: number): string {
  if (bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  return `${(bytes / 1024).toFixed(1)} KB`
}

/** option 唯一键 = `type|file`（file 为相对路径，不再用绝对 path 作键） */
const entryKey = (f: MemoryFileEntry) => `${f.type}|${f.file}`

/* ── 设计稿 13/14 的元素样式（真源 = deliverables/kb-design/assets/weknora-settings.css） ────────
   WHY 行内：`.list-section` / `.list-toolbar` / `.list-title` / `.list-count` / `.memory-results` /
   `.memory-list` / `.memory-item` / `.memory-main` / `.memory-content` / `.memory-meta` /
   `.memory-actions` / `.intro` / `.intro-icon` / `.intro-title` / `.intro-desc` / `.notice`
   这 16 个类在 `src/styles/kb/*.css` 与 `styles/globals.css` 里**均 0 条规则**
   （实测：单模式 grep 逐个 0 命中）—— 只加类名会渲染成完全无样式的裸文本。
   本文件被限定为「只能改这一个文件」，⛔ 不能往 kb-13.css / kb-14.css 写规则，
   ⇒ 沿用本文件既有惯例（见上面 SETTINGS_MODAL_RESET 三条注释：**同值行内复位**，不是新造样式），
   把设计稿原值逐条搬进来。设计稿里 `var(--app-*)` / `var(--td-*)` 的值在 `.kb-scope` 上有定义
   （kb-scope.css），故这里保留 var() 引用而不写死像素。 */
const PAGE_STYLE: Record<string, CSSProperties> = {
  /* 设计稿 `.list-section{flex:1;min-height:0;display:flex;flex-direction:column;margin-top:16px}`。
     ⚠️ 偏离一处：flex 取 `0 0 auto` 而非 `1`。设计稿该页 settings-body 有确定高度、列表在内部滚动；
     本弹窗的 content-wrapper 高度由内容决定，`flex:1`（= `1 1 0%`）会把 basis 归零、在 auto 高度
     父容器里塌成 0。改成不伸缩，列表按内容高度铺开，整页由 settings-body 滚动。 */
  listSection: {} as CSSProperties,
  listToolbar: {} as CSSProperties,
  listTitle: {} as CSSProperties,
  listTitleH3: {} as CSSProperties,
  listCount: {} as CSSProperties,
  memoryResults: {} as CSSProperties,
  memoryList: {} as CSSProperties,
  memoryItem: {} as CSSProperties,
  /* `.memory-item:last-child{border-bottom:0}` 无 CSS 伪类可用 ⇒ 末行由 memoryItemLast 覆盖 */
  memoryItemLast: {} as CSSProperties,
  /* 选中行：设计稿无此态（源站条目点击直接开编辑浮层）。本仓是「选中后在下拉/编辑区展示」，
     用 `--td-brand-color-light`（kb-scope 已有的选中底色 token）标出，不新造色值。 */
  memoryItemSelected: {} as CSSProperties,
  memoryMain: {} as CSSProperties,
  memoryContent: {} as CSSProperties,
  memoryMeta: {} as CSSProperties,
  /* `.memory-meta>span:not(:last-child):after{content:"·";margin:0 6px}` —— 伪元素无 CSS 可用，
     分隔符改由 JSX 里的 `<span aria-hidden>·</span>` 逐个渲染（同值：左右各 6px） */
  metaSep: {} as CSSProperties,
  memoryActions: {} as CSSProperties,
  memoryReadonly: {} as CSSProperties,
  intro: {} as CSSProperties,
  introIcon: {} as CSSProperties,
  introTitle: {} as CSSProperties,
  introDesc: {} as CSSProperties,
  notice: {} as CSSProperties,
  settingHint: {} as CSSProperties,
}

/** 可写的记忆配置键（`PUT /memory/config` 的两个布尔字段，见 api/memory.ts MemoryConfig） */
type ConfigKey = 'autoMemoryEnabled' | 'autoDreamEnabled'

/** 记忆编辑器 · 对齐设计稿 960px 弹窗（mem-* 类）。工具栏下拉按 type 分组选文件，编辑/预览/分屏三视图，
 *  底部保存走 PUT type 语义覆盖写（无 upsert；editable=false 只读禁用保存）。 */
export function MemoryEditorModal({ onClose, showToast, sessionId }: MemoryEditorModalProps) {
  const [files, setFiles] = useState<MemoryFileEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selectedKey, setSelectedKey] = useState('')
  const [draft, setDraft] = useState('')
  const [mode, setMode] = useState<ViewMode>('split')
  const [saving, setSaving] = useState(false)
  /** 上次载入/保存的正文基线（判「有没有未保存改动」）。 */
  const [savedSnapshot, setSavedSnapshot] = useState('')

  /* ── 记忆条目（memory/ 下平铺的单条 .md）────────────────────────────────────
     ⚠️ 与上面的 `files`（三档指令文件 Managed/User/Project）**是两套数据**：
     · files   → CLAUDE.md 这类指令文件，整份编辑
     · entries → 项目记忆目录里一条一个 .md，按 frontmatter type 分类
     🔴 四类（关于你/偏好与反馈/项目背景/参考资料）**不是目录**，只是条目文件里的
        frontmatter `type:` 标签；本机实测 492 个 .md 全部平铺在 memory/ 根下，零子目录。
     ⇒ UI 里「用户级 / 项目级」才是真实层级，四类只是项目级内部的筛选器。 */
  const [entries, setEntries] = useState<MemoryEntrySummary[]>([])
  const [entriesLoading, setEntriesLoading] = useState(true)
  const [entriesError, setEntriesError] = useState('')
  /** 当前选中的条目文件名；null = 选的是三档指令文件 */
  const [selectedEntryFile, setSelectedEntryFile] = useState<string | null>(null)
  /** 左栏当前筛选的记忆类（null = 全部） */
  const [entryFilter, setEntryFilter] = useState<string | null>(null)
  const [entryActionError, setEntryActionError] = useState('')
  /* ── 设计稿 14「长期记忆」开关（真接 GET/PUT /memory/config） ────────────────────────────
     `config` = 服务端最近一次返回的完整视图（含 dreamStatus / lastConsolidatedAtMs）；
     `configError` 非空 = 读失败，此时**开关降级为只读展示**（⛔ 不臆造 true/false 默认值：
     后端 5 级门控里「未配置 → 默认 true」，前端拿不到时画成 true 就是骗人）。
     `pendingKey` = 正在提交的开关（并发/重复点击闸门）。 */
  const [config, setConfig] = useState<MemoryConfig | null>(null)
  const [configLoading, setConfigLoading] = useState(true)
  const [configError, setConfigError] = useState('')
  const [pendingKey, setPendingKey] = useState<ConfigKey | null>(null)



  // 挂载/切换会话时拉取记忆文件列表（type 三档视图）；失败 fail loud（error 态 + 关闭按钮仍可用）
  // [批 3a] 无活动会话 → 不发请求（后端 sessionId 必填，缺值 400），直接显式 error 态。
  const load = useCallback(() => {
    let cancelled = false
    setLoading(true)
    setError('')
    if (!sessionId) {
      setFiles([])
      setSelectedKey('')
      setDraft('')
      setError('无活动会话：记忆文件需带当前会话（后端 sessionId 必填）')
      setLoading(false)
      return
    }
    listMemoryFiles(sessionId)
      .then((list) => {
        if (cancelled) return
        setFiles(list)
        // 默认选第一个已存在的文件载入 draft；全为缺失槽位则取首项
        const first = list.length > 0 ? (list.find((f) => f.exists) ?? list[0]) : null
        setSelectedKey(first ? entryKey(first) : '')
        setDraft(first?.content ?? '')
      })
      .catch((e) => {
        if (cancelled) return
        setError(e instanceof ApiError ? e.userMessage() : String(e))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [sessionId])

  useEffect(() => load(), [load])

  /* 设计稿 14 开关读链：GET /memory/config?sessionId=（sessionId 必填，后端缺值 400）。
     与 load 同款：无会话 → 不发请求，显式记 configError，开关渲染为只读。
     ⚠️ 与文件列表**分两条 effect**：config 是空间级设置，files 是文件内容；一条失败不该拖垮另一条。 */
  useEffect(() => {
    let cancelled = false
    setConfigLoading(true)
    setConfigError('')
    if (!sessionId) {
      setConfig(null)
      setConfigError('无活动会话：记忆开关需带当前会话（后端 sessionId 必填）')
      setConfigLoading(false)
      return
    }
    getMemoryConfig(sessionId)
      .then((c) => {
        if (cancelled) return
        console.info(
          `[记忆设置] GET /memory/config 成功 autoMemory=${c.autoMemoryEnabled}`
          + ` autoDream=${c.autoDreamEnabled} dreamStatus=${c.dreamStatus}`,
        )
        setConfig(c)
      })
      .catch((e) => {
        if (cancelled) return
        const msg = e instanceof ApiError ? e.userMessage() : String(e)
        console.warn(`[记忆设置] GET /memory/config 失败 error=${msg}`)
        setConfig(null)
        setConfigError(msg)
      })
      .finally(() => {
        if (!cancelled) setConfigLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [sessionId])

  /* 设计稿 14 开关写链：PUT /memory/config?sessionId=（部分更新，只传改动的那一个键）。
     失败一律 fail loud —— 回滚到前一个服务端真值 + toast 真实错误，⛔ 不做乐观假成功。

     ⭐ 「写入成功」≠「生效」：GET /memory/config 的 autoMemoryEnabled 是**有效值**
     （`BundledSkillEnabledGates.isAutoMemoryEnabled()` 5 级链：env 禁用 → env 显式开 →
     bare 模式 → remote 无 memory dir → settings），而 PUT 只写第 5 级的 DB/文件。
     ⇒ 若前 4 级中任一级强制关闭，PUT 200 之后回读仍是 false。此时若照旧弹「已开启」，
     用户看到开关弹回去 + 提示「已开启」= 自相矛盾的假成功。故必须**比对回读值**，
     不一致就明说「已保存，但被环境配置覆盖，当前仍为关闭」。 */
  const toggleConfig = useCallback(async (key: ConfigKey, next: boolean) => {
    if (!sessionId || !config || pendingKey) return
    const prev = config[key]
    const label = key === 'autoMemoryEnabled' ? '长期记忆' : '自动整合'
    setPendingKey(key)
    setConfig({ ...config, [key]: next })   // 乐观置位：只为一个 300ms 内的视觉反馈
    try {
      const updated = await updateMemoryConfig(sessionId, { [key]: next })
      const effective = updated[key]
      console.info(
        `[记忆设置] PUT /memory/config 返回 key=${key} 请求=${prev}→${next}`
        + ` 服务端有效值=${effective} autoMemory=${updated.autoMemoryEnabled}`
        + ` autoDream=${updated.autoDreamEnabled}`,
      )
      setConfig(updated)   // 一律以服务端回读为准（含未生效的情况）
      if (effective === next) {
        showToast(`${next ? '已开启' : '已关闭'}${label}`, 'success')
      } else {
        // 保存成功但有效值没变 ⇒ 被更高优先级的环境门控覆盖（-bare / CLAUDE_CODE_* env）
        showToast(`设置已保存，但${label}当前仍为${effective ? '开启' : '关闭'}：被环境配置覆盖`, 'info')
      }
    } catch (e) {
      const msg = e instanceof ApiError ? e.userMessage() : String(e)
      console.warn(`[记忆设置] PUT /memory/config 失败 key=${key} ${prev}→${next} error=${msg}`)
      setConfig((c) => (c ? { ...c, [key]: prev } : c))
      showToast(`记忆开关保存失败：${msg}`, 'info')
    } finally {
      setPendingKey(null)
    }
  }, [sessionId, config, pendingKey, showToast])

  // 选中项（按 type|file 键反查）；只读判定：Managed 恒只读（契约明示）；User 主文件恒可编辑（用户拍板）；
  // Project 依赖后端 editable
  const selected = files.find((f) => entryKey(f) === selectedKey) ?? null
  const readOnly = !!selected && (selected.type === 'Managed' || (selected.type === 'Project' && !selected.editable))

  // 预览 = marked 渲染 + DOMPurify 清洗（防 XSS）；marked.parse 同步路径返回 string，cast 兼容 async 联合类型
  const previewHtml = useMemo(() => DOMPurify.sanitize(marked.parse(draft) as string), [draft])

  /** 左栏条目：按四类筛选（null = 全部；`__none__` = 未分类的旧文件）。 */
  const filteredEntries = useMemo(() => {
    if (entryFilter === null) return entries
    if (entryFilter === '__none__') return entries.filter((e) => !e.type)
    return entries.filter((e) => e.type === entryFilter)
  }, [entries, entryFilter])

  /** 四类计数（左栏筛选器上的数字，含总数与未分类）。 */
  const entryCounts = useMemo(() => {
    const m: Record<string, number> = { __all__: entries.length, __none__: 0 }
    for (const e of entries) {
      const k = e.type ?? '__none__'
      m[k] = (m[k] ?? 0) + 1
    }
    return m
  }, [entries])

  /** 左栏按真实层级分组：用户级（跨项目）/ 项目级（当前项目）/ 系统内置（只读）。 */
  const filesByLayer = useMemo(
    () => ({
      User: files.filter((f) => f.type === 'User'),
      Project: files.filter((f) => f.type === 'Project'),
      Managed: files.filter((f) => f.type === 'Managed'),
    }),
    [files],
  )



  /** 条目清单加载。`sessionId` 缺失 → 空集（后端要 per-project 记忆目录）。 */
  const loadEntries = useCallback(() => {
    if (!sessionId) {
      setEntries([])
      setEntriesError('')
      setEntriesLoading(false)
      return () => {}
    }
    let alive = true
    setEntriesLoading(true)
    setEntriesError('')
    listMemoryEntries(sessionId)
      .then((list) => {
        if (alive) setEntries(list)
      })
      .catch((e) => {
        if (!alive) return
        setEntries([])
        setEntriesError(e instanceof ApiError ? e.userMessage() : String(e))
      })
      .finally(() => {
        if (alive) setEntriesLoading(false)
      })
    return () => {
      alive = false
    }
  }, [sessionId])

  useEffect(() => loadEntries(), [loadEntries])

  /** 有未保存改动时二次确认（切条目 / 删条目都走这里）。true = 允许丢弃草稿继续。 */
  const confirmDiscard = useCallback((): boolean => {
    if (draft === savedSnapshot) return true
    return window.confirm('当前改动尚未保存，确定放弃吗？')
  }, [draft, savedSnapshot])

  /** 选中并载入一条记忆条目（列表不带正文，点击才拉）。 */
  const pickMemoryEntry = useCallback(
    (filename: string) => {
      if (!sessionId || filename === selectedEntryFile) return
      if (!confirmDiscard()) return
      setEntryActionError('')
      getMemoryEntry(sessionId, filename)
        .then((d) => {
          setSelectedEntryFile(filename)
          setSelectedKey('')
          setDraft(d.content)
          setSavedSnapshot(d.content)
        })
        .catch((e) => {
          setEntryActionError(e instanceof ApiError ? e.userMessage() : String(e))
        })
    },
    [sessionId, selectedEntryFile, confirmDiscard],
  )

  /** 删除一条记忆条目（⛔ 不删 MEMORY.md 里指向它的那一行 —— 索引由模型维护）。 */
  const handleDeleteEntry = useCallback(async () => {
    if (!sessionId || !selectedEntryFile) return
    const ok = window.confirm(
      '确定删除「' +
        selectedEntryFile +
        '」？\n\n注意：MEMORY.md 索引里指向它的那一行不会被自动删除，需要另行处理。',
    )
    if (!ok) return
    try {
      await deleteMemoryEntry(sessionId, selectedEntryFile)
      showToast('记忆已删除', 'success')
      setSelectedEntryFile(null)
      setSelectedKey('')
      setDraft('')
      setSavedSnapshot('')
      loadEntries()
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    }
  }, [sessionId, selectedEntryFile, showToast, loadEntries])

  /** 选中某个记忆文件（列表行点击与下拉 change 共用同一条路径，避免两处状态分叉） */
  const pickEntry = useCallback((key: string) => {
    if (key !== selectedKey && !confirmDiscard()) return
    setSelectedKey(key)
    setSelectedEntryFile(null)
    const file = files.find((f) => entryKey(f) === key)
    setDraft(file?.content ?? '')
    setSavedSnapshot(file?.content ?? '')
  }, [files, selectedKey, confirmDiscard])

  const handlePick = (e: React.ChangeEvent<HTMLSelectElement>) => {
    pickEntry(e.target.value)
  }

  const handleSave = async () => {
    if (saving) return
    /* ① 选中的是「记忆条目」（memory/ 下平铺的单条 .md）→ PUT /memory/entries。
       ⚠️ 保存后**不关窗**：条目是列表里的一条，关窗会让用户以为整段管理结束。
       ⛔ 只写这一个 .md，不动 MEMORY.md（索引由模型维护）。 */
    if (selectedEntryFile) {
      if (!sessionId) return
      setSaving(true)
      try {
        await saveMemoryEntry(sessionId, selectedEntryFile, draft)
        setSavedSnapshot(draft)
        showToast('记忆已保存', 'success')
        loadEntries()
      } catch (e) {
        showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
      } finally {
        setSaving(false)
      }
      return
    }
    /* ② 选中的是「三档指令文件」→ 原有 PUT /memory/files（逻辑零改动） */
    if (!selected || readOnly) return
    setSaving(true)
    try {
      await saveMemoryFile({ type: selected.type, file: selected.file, content: draft, sessionId })
      setSavedSnapshot(draft)
      showToast('记忆已保存', 'success')
      onClose()
    } catch (e) {
      // 403/404 均 fail loud：Managed 只读 / exists=false 槽位无 upsert → 后端错误直接提示
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setSaving(false)
    }
  }

  return (
    /* 设计稿 13/14 外壳（源侧 SettingsModalShell.vue:4-11）：
       `.settings-modal-shell.settings-overlay` + `.settings-modal` + `.close-btn` +
       `.settings-container > .settings-content > .settings-body > .content-wrapper`。
       `.kb-scope` 是 kb-*.css 的作用域根：这些规则全写成 `.kb-scope .settings-*`（**后代**选择器），
       ⇒ 它必须是祖先；`display:contents` 包裹层不产生布局盒子。
       ⚠️ 本仓这个文件只有「markdown 编辑器」一块，设计稿 13 的记忆列表 / 14 的长期记忆开关
       本仓**没有对应数据与接口** ⇒ 不虚构，只换能对上的外壳与头部。 */
    <div className="kb-scope" style={{ display: 'contents' }}>
    <div className="settings-modal-shell settings-overlay" style={{ zIndex: 500 }} onClick={onClose}>
      <div className="settings-modal" style={SETTINGS_MODAL_RESET} onClick={(e) => e.stopPropagation()}>
        <button type="button" className="close-btn" onClick={onClose} aria-label="关闭">✕</button>
        <div className="settings-container">
          <div className="settings-content" style={SETTINGS_CONTENT_RESET}>
            <div className="settings-body">
              {/* ⚠️ 高度策略（本轮新增两块内容后变更）：原实现 `.content-wrapper` 被改成纵向 flex，
                  并靠 `.section{flex:1;min-height:0}` 让编辑器吃满弹窗剩余高度。
                  现在本弹窗是三段拼接（设计稿 14 开关块 + 设计稿 13 文件列表 + 原有编辑器），
                  总高度已超 780px 弹窗，`flex:1`（basis 归零）会把编辑器压到接近 0。
                  ⇒ 恢复 `.content-wrapper` 的默认块级 + `overflow-y:auto`（kb-shared.css:103 原值），
                  整页滚动；编辑器高度由 `.mem-textarea{min-height:400px}`（globals.css:9790）保证。
                  行内只覆盖 flex（kb-shared.css:823 那条 `.content-wrapper{flex:1;overflow-y:auto}`
                  会让它吃满已定高的父级，而弹窗里需要的是内容自然高度）。 */}
              <div className="content-wrapper" style={{ display: 'block', flex: '0 0 auto' }}>
                <div className="section" style={{ display: 'flex', flexDirection: 'column', flex: '0 0 auto', marginBottom: 0 }}>
                  {/* 设计稿 13 的 de-scope 复位（`13-我的记忆.html:50-67` 原文：
                      `.memory-settings .section-header{display:block;flex-shrink:0;position:static;
                       top:auto;margin:0 0 12px;padding:0 32px 0 0;border-bottom:0;background:none;z-index:auto}`
                      ＋ `.section-header-titlewrap h2{margin:0}`）。
                      WHY 必须在本仓落地：`kb-shared.css:717` 的 `.kb-scope .section-header` 是知识库
                      「吸顶页头」（position:sticky / top:-24px / margin:-24px -32px 16px / 下边框），
                      照搬过来会把本弹窗标题顶出容器 24px 并多出一条灰线。
                      设计稿用 `.memory-settings` 作用域复位，本仓不能改 CSS ⇒ 用同值行内复位，
                      ⛔ 不是新造样式，是照抄设计稿自己的复位值。 */}
                  <div
                    className="section-header"
                    style={{ display: 'block', flexShrink: 0, position: 'static', top: 'auto', margin: '0 0 12px', padding: '0 32px 0 0', borderBottom: 0, background: 'none', zIndex: 'auto' }}
                  >
                    <div className="section-header-text">
                      {/* 顶层标题 = 本弹窗总标题（它现在同时承载 14 的开关 + 13 的文件 + 编辑器三块，
                          所以不再叫「记忆编辑」—— 那只是第三块的名字）。 */}
                      <h2 style={{ margin: 0 }}>记忆 <span className="mem-badge">.md</span></h2>
                      <p className="section-description">长期记忆开关 · 记忆文件（三档）· Markdown 编辑</p>
                    </div>
                  </div>

                  {/* ── 设计稿 14「长期记忆」开关区（真接 GET/PUT /memory/config）────────────────
                      结构照设计稿 14 的 `.intro` + `.setting-row`（`14-长期记忆.html:256-274`）。
                      ⚠️ 与设计稿的两处**有意偏离**（都在下方文案里体现）：
                        ① 设计稿 intro 标题写「默认关闭，需要你显式开启」，本仓后端
                           `BundledSkillEnabledGates.isAutoMemoryEnabled()` 第 5 级是
                           「settings 未配置 → 默认 true（CC paths.ts:54）」⇒ 那句是假话，改写成
                           「默认开启，可随时关闭」。⛔ 不许把设计稿的示例文案当本仓事实照抄。
                        ② 设计稿 10 个条件配置行里，本仓只有 2 个键能真写：
                           autoMemoryEnabled / autoDreamEnabled。其余 8 项（记忆写入方式 /
                           提炼模型 / 挖掘延迟 / 最小间隔 / 语义召回 / Embedding 模型 /
                           参与检索 / 长期关注次数 / 自定义挖掘规则 / 每人上限）
                           `PUT /memory/config` 的 `MemoryConfigUpdate` 只收两个布尔字段
                           （`MemoryController:845`）⇒ 一行都不做，见报告 §8。 */}
                  <div className="memory-workspace-settings" style={{ flexShrink: 0, marginBottom: 20 }}>
                    <div className="section-header" style={{ display: 'block', position: 'static', top: 'auto', margin: '0 0 12px', padding: '0 32px 0 0', borderBottom: 0, background: 'none', zIndex: 'auto' }}>
                      <div className="section-header-text">
                        <h2 style={{ margin: 0 }}>长期记忆</h2>
                        <p className="section-description">让助手跨会话记住你说过的内容。</p>
                      </div>
                    </div>

                    <div className="intro" style={PAGE_STYLE.intro}>
                      <span className="intro-icon" style={PAGE_STYLE.introIcon} aria-hidden="true">◇</span>
                      <div>
                        <p className="intro-title" style={PAGE_STYLE.introTitle}>默认开启，可随时关闭</p>
                        <p className="intro-desc" style={PAGE_STYLE.introDesc}>
                          开启后，助手会把跨会话仍然有用的信息记录到记忆文件里。关闭后本会话不再读取或写入记忆，
                          已有记忆文件会保留。改动只影响当前空间。
                        </p>
                      </div>
                    </div>

                    {configError && (
                      <div className="notice" style={PAGE_STYLE.notice} role="status">
                        <span aria-hidden="true">ⓘ</span>
                        <span>读取记忆开关失败，以下开关为只读：{configError}</span>
                      </div>
                    )}

                    <div className="settings-group" style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
                      {[
                        {
                          key: 'autoMemoryEnabled' as ConfigKey,
                          label: '在本空间启用长期记忆',
                          desc: '关闭后本空间的所有会话都不会读取或写入记忆。',
                        },
                        {
                          key: 'autoDreamEnabled' as ConfigKey,
                          label: '自动整合记忆',
                          desc: '会话结束后在后台整理记忆文件（dream）。关闭后只在会话中被动写入。',
                        },
                      ].map((row, i, arr) => {
                        /* 三个真实状态，⛔ 不合并：加载中 / 读失败（只读）/ 有值（可切换） */
                        const value = config ? config[row.key] : null
                        const disabled = configLoading || !!configError || !config || pendingKey !== null
                        const checked = value === true
                        return (
                          <div
                            key={row.key}
                            className="setting-row"
                            style={{
                              display: 'flex',
                              alignItems: 'flex-start',
                              justifyContent: 'space-between',
                              gap: 24,
                              padding: '20px 0',
                              margin: 0,
                              border: 0,
                              borderBottom: i === arr.length - 1 ? 'none' : '1px solid var(--td-component-stroke)',
                              borderRadius: 0,
                              background: 'none',
                              boxShadow: 'none',
                            }}
                          >
                            <div className="setting-info" style={{ flex: 1, minWidth: 0, maxWidth: '65%', paddingRight: 0 }}>
                              <label style={{ display: 'block', margin: '0 0 4px', fontSize: 'var(--app-text-lg)', fontWeight: 500, lineHeight: 1.4, color: 'var(--td-text-color-primary)' }}>
                                {row.label}
                              </label>
                              <p className="desc" style={{ margin: 0, fontSize: 'var(--app-text-md)', lineHeight: 1.5, color: 'var(--td-text-color-secondary)' }}>
                                {row.desc}
                              </p>
                              {pendingKey === row.key && (
                                <p className="desc" style={PAGE_STYLE.settingHint}>保存中…</p>
                              )}
                            </div>
                            <div className="setting-control" style={{ flex: '0 0 auto', display: 'flex', flexDirection: 'row', alignItems: 'center', justifyContent: 'flex-end', gap: 8, minWidth: 0 }}>
                              {/* t-switch 结构与 KbSettingsModal 的 Switch 同款（handle + content，
                                  选中态 t-is-checked），样式来自 kb-tdesign.css:2188-2229。
                                  用 button 而不是 input[type=checkbox]：只读态要真的 disabled，
                                  且 aria-checked 只读时也要如实反映服务端值。 */}
                              <button
                                type="button"
                                className={`t-switch t-size-m${checked ? ' t-is-checked' : ''}${disabled ? ' t-is-disabled' : ''}`}
                                role="switch"
                                aria-checked={checked}
                                aria-label={row.label}
                                disabled={disabled}
                                onClick={() => void toggleConfig(row.key, !checked)}
                              >
                                <span className="t-switch__handle" />
                                <span className="t-switch__content t-size-m" />
                              </button>
                            </div>
                          </div>
                        )
                      })}
                    </div>

                    {/* dream 状态只读行：GET /memory/config 的 dreamStatus + lastConsolidatedAtMs
                        是**真实字段**，不加开关，只如实展示（`never` = 从未整合过）。 */}
                    {config && (
                      <div className="setting-row" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 24, padding: '20px 0 0', margin: 0, border: 0, borderBottom: 'none' }}>
                        <div className="setting-info" style={{ flex: 1, minWidth: 0, maxWidth: '65%', paddingRight: 0 }}>
                          <span style={{ display: 'block', fontSize: 'var(--app-text-lg)', fontWeight: 500, lineHeight: 1.4, color: 'var(--td-text-color-primary)' }}>
                            上次整合时间
                          </span>
                        </div>
                        <div className="setting-control" style={{ flex: '0 0 auto', display: 'flex', justifyContent: 'flex-end' }}>
                          <span style={{ fontSize: 'var(--app-text-md)', color: 'var(--td-text-color-secondary)' }}>
                            {config.dreamStatus === 'never' || config.lastConsolidatedAtMs <= 0
                              ? '从未整合'
                              : new Date(config.lastConsolidatedAtMs).toLocaleString()}
                          </span>
                        </div>
                      </div>
                    )}
                  </div>

                  {/* ── 左栏（真实层级：用户级 / 项目级）──────────────────────────────────────
                      🔴 用户明确纠正过：四类（关于你/偏好与反馈/项目背景/参考资料）**不是目录**，
                      只是条目 frontmatter 的 `type:` 标签（本机 492 个 .md 全平铺、零子目录）。
                      ⇒ 层级 = 用户级 / 项目级；四类是项目级内部的筛选器。
                      ⛔ 不把四类画成可展开的容器。 */}
                  <div className="mem-layout">
                    <aside className="mem-side">
                      <div className="mem-side-head">
                        <span className="mem-side-title">记忆</span>
                        <span className="mem-side-count">
                          {entriesLoading ? '加载中…' : entriesError ? '—' : `共 ${entries.length} 条`}
                        </span>
                      </div>

                      <div className="mem-side-scroll">
                        {/* 用户级：跨所有项目的指令文件 */}
                        <div className="mem-side-group">
                          <div className="mem-side-group-title">用户级<span>全部项目</span></div>
                          <ul className="mem-side-list">
                            {filesByLayer.User.length === 0 ? (
                              <li className="mem-side-empty">未发现</li>
                            ) : (
                              filesByLayer.User.map((f) => {
                                const key = entryKey(f)
                                return (
                                  <li key={key}>
                                    <button
                                      type="button"
                                      className={'mem-side-row' + (key === selectedKey ? ' is-on' : '')}
                                      onClick={() => pickEntry(key)}
                                    >
                                      <span className="mem-side-name">{f.file}</span>
                                      <span className="mem-side-meta">
                                        {f.exists ? '已存在' : '待创建'} · 指令
                                      </span>
                                    </button>
                                  </li>
                                )
                              })
                            )}
                          </ul>
                        </div>

                        {/* 项目级：当前项目的指令文件 + 记忆条目（四类筛选） */}
                        {filesByLayer.Project.length > 0 && (
                          <div className="mem-side-group">
                            <div className="mem-side-group-title">项目级<span>当前项目 · 指令</span></div>
                            <ul className="mem-side-list">
                              {filesByLayer.Project.map((f) => {
                                const key = entryKey(f)
                                return (
                                  <li key={key}>
                                    <button
                                      type="button"
                                      className={'mem-side-row' + (key === selectedKey ? ' is-on' : '')}
                                      onClick={() => pickEntry(key)}
                                    >
                                      <span className="mem-side-name">{f.file}</span>
                                      <span className="mem-side-meta">
                                        {f.exists ? '已存在' : '待创建'}
                                        {f.type === 'Project' && !f.editable ? ' · 只读' : ' · 可编辑'}
                                      </span>
                                    </button>
                                  </li>
                                )
                              })}
                            </ul>
                          </div>
                        )}

                        {/* 记忆条目：四类筛选器（标签，不是目录） */}
                        <div className="mem-side-group">
                          <div className="mem-side-group-title">
                            项目级<span>当前项目 · 记忆条目</span>
                          </div>
                          <ul className="mem-side-list">
                            <li>
                              <button
                                type="button"
                                className={'mem-side-row mem-side-cat' + (entryFilter === null ? ' is-on' : '')}
                                onClick={() => setEntryFilter(null)}
                              >
                                <span className="mem-side-name">全部</span>
                                <span className="mem-side-num">{entryCounts.__all__ ?? 0}</span>
                              </button>
                            </li>
                            {ENTRY_CATEGORIES.map((c) => (
                              <li key={c.type}>
                                <button
                                  type="button"
                                  className={'mem-side-row mem-side-cat' + (entryFilter === c.type ? ' is-on' : '')}
                                  onClick={() => setEntryFilter(c.type)}
                                >
                                  <span className="mem-side-name">{c.label}</span>
                                  <span className="mem-side-num">{entryCounts[c.type] ?? 0}</span>
                                </button>
                              </li>
                            ))}
                            {(entryCounts.__none__ ?? 0) > 0 && (
                              <li>
                                <button
                                  type="button"
                                  className={'mem-side-row mem-side-cat' + (entryFilter === '__none__' ? ' is-on' : '')}
                                  onClick={() => setEntryFilter('__none__')}
                                >
                                  <span className="mem-side-name">未分类</span>
                                  <span className="mem-side-num">{entryCounts.__none__}</span>
                                </button>
                              </li>
                            )}
                          </ul>
                        </div>

                        {/* 筛选结果：一条一项 */}
                        <div className="mem-side-group">
                          <ul className="mem-side-list mem-side-entries">
                            {entriesLoading ? (
                              <li className="mem-side-empty">加载中…</li>
                            ) : entriesError ? (
                              <li className="mem-side-empty">
                                读取失败：{entriesError}
                                <button type="button" className="mem-side-retry" onClick={loadEntries}>
                                  重试
                                </button>
                              </li>
                            ) : filteredEntries.length === 0 ? (
                              <li className="mem-side-empty">这一类下没有记忆条目</li>
                            ) : (
                              filteredEntries.map((e) => (
                                <li key={e.filename}>
                                  <button
                                    type="button"
                                    className={
                                      'mem-side-row mem-side-item' +
                                      (e.filename === selectedEntryFile ? ' is-on' : '')
                                    }
                                    onClick={() => pickMemoryEntry(e.filename)}
                                    title={e.description ?? e.filename}
                                  >
                                    <span className="mem-side-name mem-side-name-2">{e.name}</span>
                                    <span className="mem-side-meta">
                                      {fmtEntryTime(e.mtimeMs)} · {fmtEntrySize(e.sizeBytes)}
                                    </span>
                                  </button>
                                </li>
                              ))
                            )}
                          </ul>
                        </div>

                        {entryActionError && (
                          <div className="mem-side-err" role="status">
                            {entryActionError}
                          </div>
                        )}
                      </div>
                    </aside>
                    <div className="mem-main">
                  {/* ── 编辑器块（原有 markdown 编辑/预览/分屏，逻辑零改动）────────────────────
                      给一个块级小标题，否则它接在文件列表后面会被读成列表的一部分。 */}
                  <div className="section-header" style={{ display: 'block', position: 'static', top: 'auto', margin: '24px 0 12px', padding: '0 32px 0 0', borderBottom: 0, background: 'none', zIndex: 'auto' }}>
                    <div className="section-header-text">
                      <h3 style={{ margin: 0, fontSize: 'var(--app-text-xl)', fontWeight: 600, color: 'var(--td-text-color-primary)' }}>
                        {selectedEntryFile ? '编辑记忆条目' : '编辑记忆文件'}
                      </h3>
                    </div>
                  </div>

                  <div className="mem-toolbar">
                    <select
                      className="mem-file-picker"
                      value={selectedKey}
                      onChange={handlePick}
                      disabled={files.length === 0}
                      aria-label="选择记忆文件"
                    >
                      {/* 选中「记忆条目」时 selectedKey 为空串 ⇒ 补一个 option，否则下拉显示空白 */}
                      {selectedEntryFile && <option value="">记忆条目 · {selectedEntryFile}</option>}
                      {TYPE_GROUPS.map((type) => {
                        const group = files.filter((f) => f.type === type)
                        if (group.length === 0) return null
                        return (
                          <optgroup key={type} label={type}>
                            {group.map((f) => (
                              <option key={entryKey(f)} value={entryKey(f)}>
                                {f.file}
                                {f.exists ? '' : ' (new)'}
                                {f.type === 'Managed' || (f.type === 'Project' && !f.editable) ? ' (只读)' : ''}
                              </option>
                            ))}
                          </optgroup>
                        )
                      })}
                    </select>
                    <div className="view-tabs">
                      {VIEW_TABS.map((t) => (
                        <button
                          key={t.id}
                          className={`view-tab${mode === t.id ? ' active' : ''}`}
                          onClick={() => setMode(t.id)}
                        >
                          {t.label}
                        </button>
                      ))}
                    </div>
                    <span className="mem-toolbar-info">
                      {selectedEntryFile
                        ? `记忆条目 · ${selectedEntryFile}`
                        : readOnly
                          ? '只读文件 · 保存已禁用'
                          : '支持 Markdown 格式'}
                    </span>
                  </div>

                  <div className="mem-body">
                    {loading ? (
                      <div className="mem-state">加载中…</div>
                    ) : error ? (
                      <div className="mem-state mem-state-error">
                        <span>加载失败：{error}</span>
                        <button className="mem-retry-btn" onClick={load}>重试</button>
                      </div>
                    ) : files.length === 0 && !selectedEntryFile ? (
                      <div className="mem-state">未发现记忆文件</div>
                    ) : (
                      <>
                        <div className={`editor-pane${mode === 'preview' ? ' hidden' : ''}`} style={EDITOR_PANE_RESET}>
                          <textarea
                            className="mem-textarea"
                            value={draft}
                            onChange={(e) => setDraft(e.target.value)}
                            spellCheck={false}
                            disabled={readOnly}
                          />
                        </div>
                        <div className={`preview-pane${mode === 'edit' ? ' hidden' : ''}`}>
                          <div className="md-preview" dangerouslySetInnerHTML={{ __html: previewHtml }} />
                        </div>
                      </>
                    )}
                  </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            {/* 源侧 SettingsModalShell.vue:45-50 —— footer 是 .settings-content 的并列子块，不是 .settings-body 的子节点 */}
            <div className="settings-footer">
              <p className="settings-footer-note">💾 修改将持久化到本地数据库</p>
              <div className="settings-footer-actions">
                {/* 删除只对「记忆条目」开放（三档指令文件有 Managed/白名单约束，删不得）。
                    ⚠️ 删的只是这一个 .md：MEMORY.md 里指向它的那一行**不会**跟着消失（索引由模型维护），
                    弹窗二次确认里已明确写了这一点。 */}
                {selectedEntryFile && (
                  <button className="mem-delete-btn" onClick={handleDeleteEntry} disabled={saving}>
                    删除这条
                  </button>
                )}
                <button className="mem-cancel-btn" onClick={onClose}>取消</button>
                <button
                  className="mem-save-btn"
                  disabled={saving || (!selectedEntryFile && (!selected || readOnly))}
                  onClick={handleSave}
                >
                  保存修改
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
    </div>
  )
}
