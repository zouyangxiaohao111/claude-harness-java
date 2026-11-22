/**
 * 任务页 ·「系统任务」区块 + 扫描任务配置抽屉（消息扫描）。
 *
 * <p><b>设计权威</b>：{@code front/docs/im-tasks-redesign.html}（屏 1 系统任务区块 / 屏 2 配置抽屉）。
 * 数值与文案都按稿抄，不自行改设计。
 *
 * <p><b>为什么单独一个文件</b>：系统任务是**非 AgentTool 任务**（只搬数据、不跑 AI），
 * 它的生命周期与 AI 任务（{@link SchedulesPage}）完全不同 —— 没有 create/delete，
 * 只有「扫哪些渠道」一个**覆盖式**配置。混在同一文件里，两套语义会互相污染
 * （例如把「不能删」的心智误带到 AI 任务上）。
 *
 * <p><b>任务级暂停</b>：{@code im_scan_task.paused}（V318）—— 暂停只停「跑不跑」，
 * **渠道勾选原样保留**（恢复后不用重新挑）；与渠道级 {@code enabled} 相互独立。
 * 仍<b>没有</b>新建/删除：任务本身是系统内置的。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ApiError } from '@/api/rest'
import { imApi, type ImPlatform } from '@/api/im'

/** 后端返回的扫描配置（类型从 api 直接推导，避免手抄一份后端 DTO 后漂移）。 */
type ScanChannelsResp = Awaited<ReturnType<typeof imApi.listScanChannels>>
type ScanChannelRow = ScanChannelsResp['channels'][number]

/**
 * 平台中文名 / 徽标字。
 *
 * <p>⚠️ 与 {@code ImChannelPage.tsx} 里的同名常量一致，但那边**没有导出**，
 * 而该文件正被另一个会话改动（本轮不许动它）⇒ 这里各留一份。
 */
const PLATFORM_LABEL: Record<ImPlatform, string> = {
  wechat: '微信',
  feishu: '飞书',
  wecom: '企业微信',
  dingtalk: '钉钉',
}
const PLATFORM_CHAR: Record<ImPlatform, string> = {
  wechat: '微',
  feishu: '飞',
  wecom: '企',
  dingtalk: '钉',
}

/** 扫描时间展示：今天只显示时分，更早的带月日（稿里的「上次扫描 21:45」）。 */
function scanTimeLabel(v: string | null | undefined): string {
  if (!v) return '—'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return v
  const now = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  const hm = `${p(d.getHours())}:${p(d.getMinutes())}`
  const sameDay =
    d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate()
  return sameDay ? hm : `${d.getMonth() + 1}-${p(d.getDate())} ${hm}`
}

/** 取一组渠道里最新的「上次成功扫描」时间（后端失败时不推进 last_scan_at ⇒ 它就是上次成功的时间）。 */
function latestScanAt(rows: ScanChannelRow[]): string | null {
  let latest: number | null = null
  let raw: string | null = null
  for (const r of rows) {
    if (!r.lastScanAt) continue
    const t = new Date(r.lastScanAt).getTime()
    if (Number.isNaN(t)) continue
    if (latest === null || t > latest) {
      latest = t
      raw = r.lastScanAt
    }
  }
  return raw
}

/** 求和（null/undefined 当 0 —— 后端对「从没配过的渠道」不下发这些列）。 */
function sumOf(rows: ScanChannelRow[], key: 'lastChatCount' | 'lastNewCount'): number {
  let n = 0
  for (const r of rows) n += r[key] ?? 0
  return n
}

interface ScanTaskPanelProps {
  showToast: (msg: string, type?: 'success' | 'info') => void
}

/**
 * 「系统任务」区块（只有消息扫描一行）+ 它的配置抽屉。
 *
 * <p>数据源只有一个：{@code imApi.listScanChannels()} —— 它把「实例目录」和
 * 「哪些渠道在扫」合在一起返回了，⛔ 不要再去拉一遍 {@code listInstances()}。
 */
export function ScanTaskPanel({ showToast }: ScanTaskPanelProps) {
  const [data, setData] = useState<ScanChannelsResp | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  /** 抽屉是否打开 */
  const [open, setOpen] = useState(false)
  /** 抽屉里的勾选状态（未保存前的草稿） */
  const [picked, setPicked] = useState<string[]>([])
  const [saving, setSaving] = useState(false)
  /** 暂停/恢复请求在途（防连点） */
  const [pausing, setPausing] = useState(false)

  /**
   * 首次加载才显示「加载中…」，之后刷新原地更新。
   *
   * <p>为什么：这是任务页顶部的一行，每次保存/刷新都整块替换成「加载中…」
   * 会让页面闪一下；而刷新拿的是同一份配置的新状态，应该就地变而不是重画。
   */
  const loadedOnceRef = useRef(false)

  const load = useCallback(async () => {
    if (!loadedOnceRef.current) {
      setLoading(true)
    }
    setLoadError(null)
    try {
      setData(await imApi.listScanChannels())
      loadedOnceRef.current = true
    } catch (e) {
      setLoadError(e instanceof ApiError ? e.userMessage() : String(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  // ⛔ 不在这里监听 Esc：全站约定是「Esc 回对话页」（App.tsx 统一处理），
  // 抽屉跟 IM 频道页一样只提供 × / 取消 / 点遮罩三种关闭方式。

  const channels = useMemo(() => data?.channels ?? [], [data])
  const enabledRows = useMemo(() => channels.filter((c) => c.enabled), [channels])
  const enabledCount = data?.enabledCount ?? 0
  /** 任务级暂停（V318）· 与「一个渠道都没选」是两回事 */
  const paused = data?.paused ?? false

  /**
   * 页面那一行的状态三态。
   *
   * <p>⚠️ 顺序要紧：<b>任务级暂停</b>最先判 —— 暂停时即使勾了渠道也不会扫；
   * 其次是 {@code enabledCount === 0}（新装默认，一个渠道都没选）⇒ **已暂停**，
   * 绝不能落到「异常」——否则用户第一次进来就看到红色报错。
   * <p>「异常」只认**在扫的渠道**上的 lastError：没勾的渠道不参与扫描，
   * 它上次的陈旧错误不该让整个任务看起来坏了（稿：汇总只统计已勾选的渠道）。
   */
  const rowState = useMemo<{ dot: 'run' | 'err' | 'pause' | 'paused'; text: string }>(() => {
    if (!data && loading) return { dot: 'pause', text: '—' }
    // 用户主动暂停用独立的 'paused'（琥珀色、醒目）——不与「一个渠道都没选」的灰态混同
    if (paused) return { dot: 'paused', text: '已暂停' }
    if (enabledCount === 0) return { dot: 'pause', text: '已暂停' }
    if (enabledRows.some((c) => !!c.lastError)) return { dot: 'err', text: '异常' }
    return { dot: 'run', text: '运行中' }
  }, [data, loading, paused, enabledCount, enabledRows])

  /** 那一行右侧的信息：上次扫描时间 · 会话数 · 新增条数（异常时把原因也带上，别藏）。 */
  const rowMeta = useMemo(() => {
    if (!data) return loading ? '加载中…' : '—'
    if (paused) return '已暂停 · 扫描不会运行（渠道配置保留）'
    if (enabledCount === 0) return '未选择渠道 · 扫描不会运行'
    const t = latestScanAt(enabledRows)
    if (!t) return '尚未扫描过'
    const chats = sumOf(enabledRows, 'lastChatCount')
    const news = sumOf(enabledRows, 'lastNewCount')
    const err = enabledRows.find((c) => !!c.lastError)?.lastError
    const base = `上次扫描 ${scanTimeLabel(t)} · ${chats} 个会话 · 新增 ${news} 条`
    return err ? `${base} · ${err}` : base
  }, [data, loading, paused, enabledCount, enabledRows])

  /** 抽屉里的勾选集合（按渠道过滤，保证汇总只统计已勾选的）。 */
  const pickedRows = useMemo(
    () => channels.filter((c) => picked.includes(c.instanceId)),
    [channels, picked],
  )

  const summary = useMemo(() => {
    const t = latestScanAt(pickedRows)
    const err = pickedRows.find((c) => !!c.lastError)?.lastError ?? null
    return {
      time: t ? scanTimeLabel(t) : '—',
      chats: sumOf(pickedRows, 'lastChatCount'),
      news: sumOf(pickedRows, 'lastNewCount'),
      err,
    }
  }, [pickedRows])

  const openDrawer = () => {
    // 打开时用**已保存**的状态做草稿，不是上次没保存完的草稿
    setPicked(channels.filter((c) => c.enabled).map((c) => c.instanceId))
    setOpen(true)
  }

  const toggle = (instanceId: string) => {
    setPicked((prev) => (prev.includes(instanceId)
      ? prev.filter((x) => x !== instanceId)
      : [...prev, instanceId]))
  }

  const save = async () => {
    setSaving(true)
    try {
      await imApi.setScanChannels(picked)
      showToast('已保存', 'success')
      setOpen(false)
      await load()
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setSaving(false)
    }
  }

  /**
   * 暂停 / 恢复整个扫描任务（任务级，V318）。
   *
   * <p>⛔ 不碰渠道勾选：暂停只停「跑不跑」，恢复后原来的渠道照旧。
   * <p>保存后重拉一次（以服务端为准），失败时提示原文、本地状态不变。
   */
  const togglePause = async () => {
    if (!data || pausing) return
    const next = !data.paused
    setPausing(true)
    try {
      await imApi.setScanPaused(next)
      await load()
      showToast(next ? '已暂停消息扫描' : '已恢复消息扫描', 'success')
    } catch (e) {
      showToast(e instanceof ApiError ? e.userMessage() : String(e), 'info')
    } finally {
      setPausing(false)
    }
  }

  return (
    <>
      <section className="sch-sys-block">
        <div className="sch-sys-blk-h">
          <span className="sch-sys-blk-ic sys">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round">
              <rect x="4.6" y="10" width="14.8" height="10" rx="2.4" />
              <path d="M8.2 10V7.4a3.8 3.8 0 0 1 7.6 0V10" />
            </svg>
          </span>
          <span className="sch-sys-blk-t">系统任务</span>
          <span className="sch-sys-blk-c">1</span>
          <span className="sch-sys-tag">内置 · 不可新建 · 不可删除</span>
          <span className="sch-sys-blk-sub">只搬数据，不跑 AI</span>
        </div>

        <div className={`sch-sys-box${paused ? ' paused' : ''}`}>
          <div className="sch-row sch-sys-row">
            <span className={`sch-sys-dot ${rowState.dot}`} />
            <div className="sch-sys-namecol">
              <div className="sch-sys-name">消息扫描</div>
              <div className="sch-sys-sub">把指定渠道的新消息同步到本地，供 AI 查询；不会自动回复</div>
            </div>
            <span className="sch-sys-chip">每 5 分钟</span>
            <span className="sch-sys-meta" title={rowMeta}>{rowMeta}</span>
            <span className={`sch-sys-state ${rowState.dot}`}>{rowState.text}</span>
            {/* 恢复用主色按钮：暂停态下「怎么恢复」要一眼可见 */}
            <button
              type="button"
              className={`sch-sys-btn sm ${paused ? 'pri' : 'ghost'}`}
              disabled={pausing || !data}
              onClick={() => void togglePause()}
            >
              {paused ? '恢复' : '暂停'}
            </button>
            {/* 配置：决定「扫哪些渠道」；新建/删除任务本身仍然没有 */}
            <button type="button" className="sch-sys-btn sm" onClick={openDrawer}>配置</button>
          </div>
          <div className="sch-sys-hint">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round">
              <circle cx="12" cy="12" r="9" />
              <path d="M12 16v-4M12 8h.01" />
            </svg>
            <span>
              {loadError
                ? `读取扫描配置失败：${loadError}`
                : '不会主动发消息。这个任务由系统提供，不能新增也不能删除——你能改的是「扫哪些渠道」，以及随时暂停/恢复它。'}
            </span>
          </div>
        </div>
      </section>

      {open && (
        <>
          <div className="sch-sys-scrim" onClick={() => setOpen(false)} />
          <aside className="sch-sys-drawer" role="dialog" aria-modal="true" aria-label="消息扫描配置">
            <div className="sch-sys-dh">
              <h4>消息扫描 · 配置</h4>
              <button type="button" className="sch-sys-x" onClick={() => setOpen(false)} aria-label="关闭">×</button>
            </div>

            <div className="sch-sys-db">
              <div className="sch-sys-scroll">
                <div className="sch-sys-sync">
                  <div className="sch-sys-sb">
                    <div className="sch-sys-sl1">把指定渠道的新消息同步到本地</div>
                    <div className="sch-sys-sl2">每 5 分钟跑一次。只入库供 AI 查询，不唤醒 AI、不发消息。</div>
                  </div>
                </div>

                {/* 暂停态必须在这里说清楚：勾了渠道保存 ≠ 恢复扫描 */}
                {paused && (
                  <div className="sch-sys-sync warn sch-sys-empty">
                    <div className="sch-sys-sb">
                      <div className="sch-sys-sl1">这个任务当前已暂停</div>
                      <div className="sch-sys-sl2">保存渠道不会自动恢复扫描；要恢复请点任务行上的「恢复」。</div>
                    </div>
                  </div>
                )}

                <div className="sch-sys-dw-h">
                  <div className="sch-sys-sec-t">
                    要扫描的渠道 <span className="sch-sys-sec-c">{picked.length} / {channels.length}</span>
                  </div>
                  <div className="sch-sys-dw-tools">
                    <button type="button" className="sch-sys-btn sm ghost" onClick={() => setPicked([])}>全部取消</button>
                    <button
                      type="button"
                      className="sch-sys-btn sm ghost"
                      onClick={() => setPicked(channels.map((c) => c.instanceId))}
                    >
                      全选
                    </button>
                  </div>
                </div>

                <div className="sch-sys-list">
                  {channels.length === 0 && (
                    /* 一个 IM 实例都没有（新装 / 还没接入机器人）：说清楚原因，
                       否则下面那句「没勾选任何渠道」会让人以为是自己的勾选操作出了问题 */
                    <div className="sch-sys-none">还没有接入任何渠道 —— 先到左栏「IM 频道」添加机器人</div>
                  )}
                  {channels.map((c) => {
                    const sel = picked.includes(c.instanceId)
                    const scanned = !!c.lastScanAt
                    return (
                      <button
                        type="button"
                        key={c.instanceId}
                        className={`sch-sys-pick${sel ? ' sel' : ''}`}
                        role="checkbox"
                        aria-checked={sel}
                        onClick={() => toggle(c.instanceId)}
                      >
                        <span className="sch-sys-cb">{sel ? '✓' : ''}</span>
                        <span className={`sch-sys-plat sm ${c.platform}`}>{PLATFORM_CHAR[c.platform]}</span>
                        <span className="sch-sys-pb">
                          <span className="sch-sys-pn1">{c.displayName}</span>
                          <span className="sch-sys-pn2">
                            {PLATFORM_LABEL[c.platform]} · {scanned ? `上次扫 ${c.lastChatCount ?? 0} 个会话` : '从未扫描'}
                          </span>
                          {/* ⛔ lastError 不隐藏：用户要知道「为什么没扫到」 */}
                          {c.lastError && <span className="sch-sys-err">上次扫描失败：{c.lastError}</span>}
                        </span>
                      </button>
                    )
                  })}
                </div>

                {/* 一个渠道都没勾（新装默认态）：扫描根本不会跑，得说清楚 */}
                {picked.length === 0 && (
                  <div className="sch-sys-sync warn sch-sys-empty">
                    <div className="sch-sys-sb">
                      <div className="sch-sys-sl1">还没有选择任何渠道，扫描不会运行</div>
                      <div className="sch-sys-sl2">勾选至少一个渠道后，每 5 分钟才会去拉取新消息。</div>
                    </div>
                  </div>
                )}
              </div>

              <div className="sch-sys-lastline">
                {picked.length === 0 ? (
                  <>
                    <span>所选渠道上次扫描</span>
                    <span>·</span>
                    <span>—</span>
                    <span>·</span>
                    <span>勾选渠道后这里会显示上次扫描结果</span>
                  </>
                ) : (
                  <>
                    <span>所选渠道上次扫描 <b>{summary.time}</b></span>
                    <span>·</span>
                    <span>{summary.chats} 个会话</span>
                    <span>·</span>
                    <span>新增 {summary.news} 条</span>
                    <span>·</span>
                    {summary.err
                      ? <span className="sch-sys-bad">{summary.err}</span>
                      : <span className="sch-sys-ok">所选渠道无错误</span>}
                  </>
                )}
              </div>
            </div>

            <div className="sch-sys-df">
              <button type="button" className="sch-sys-btn ghost" onClick={() => setOpen(false)}>取消</button>
              <button type="button" className="sch-sys-btn pri" disabled={saving} onClick={() => void save()}>
                {saving ? '保存中…' : '保存'}
              </button>
            </div>
          </aside>
        </>
      )}
    </>
  )
}
