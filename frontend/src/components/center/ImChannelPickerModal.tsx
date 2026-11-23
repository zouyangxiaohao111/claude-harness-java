import { useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { imApi, type ImInstanceIdentity, type ImPlatform } from '@/api/im'
import { PLATFORM_CHAR, PLATFORM_LABEL } from './ImChannelPage'

/** 弹窗第一栏的渠道条目（App 传进来的已接入实例） */
export interface PickerInstance {
  id: string
  label: string
  platform?: ImPlatform
  status?: 'connected' | 'disconnected' | 'error'
}

/** 弹窗第二栏的聊天条目（V305 聊天级接线） */
export interface PickerChat {
  id: string
  name: string
  kind: 'group' | 'p2p'
  instanceId: string
  boundSessionId?: string | null
  boundSessionTitle?: string | null
  /**
   * V335 · 机器人是否在该会话里。
   * false = 已被移出该群（仍显示，否则解绑不了）；null/undefined = 尚未判定。
   */
  botPresent?: boolean | null
  /** 名字只是平台 id（缺昵称/群名）—— 用于提示「对方未命名」而不是让人盯一串 oc_… */
  unnamed?: boolean
}

interface Props {
  instances: PickerInstance[]
  chats: PickerChat[]
  /** 已经接到**当前会话**的 chat id */
  boundChatIds: string[]
  /**
   * [A 预热提示] 需要激活但还没激活的 chat id（微信：没收到过对方消息 ⇒ 通知发不出去）。
   * 只用于标状态，⛔ 不禁用「接到此会话」按钮。
   */
  inactiveChatIds?: string[]
  allowUserIdentity?: boolean
  onToggleUserIdentity?: () => void
  onBind: (chatId: string) => void
  onUnbind: (chatId: string) => void
  /** 当前会话 id（快速绑定把它传给后端） */
  sessionId?: string
  /** 快速绑定：把该渠道的机器人默认会话接到当前会话 */
  onQuickBind?: (instanceId: string) => void
  onOpenChannels: () => void
  onClose: () => void
  /** [V329] 由无人值守入口触发的「请先配置渠道」引导（只解释，不新增第二个开关） */
  guide?: boolean
}

/** 机器人 id 压缩展示（保留头尾，能辨认即可）。 */
function shortBotId(id?: string): string {
  if (!id) return ''
  return id.length <= 10 ? id : `${id.slice(0, 6)}…${id.slice(-4)}`
}

/** 会话 id 压缩展示（飞书 oc_/ou_ 很长；保留头尾便于对照）。 */
function shortChatId(id: string): string {
  return id.length <= 14 ? id : `${id.slice(0, 8)}…${id.slice(-4)}`
}

/**
 * [机器人身份] 一行文案：飞书给机器人名 + 可见群数；企微给 Bot + 授权人；
 * 微信说明「登录账号即机器人」。取不到就给后端那句话，⛔ 不编造。
 */
function identityText(idn: ImInstanceIdentity | null | undefined): string | null {
  if (!idn) return null
  if (idn.botName) {
    return `机器人：${idn.botName}${idn.chatVisible ? ` · 群 ${idn.chatVisible}` : ''}`
  }
  if (idn.platform === 'wecom' && idn.botId) {
    return `Bot ${shortBotId(idn.botId)}${idn.authorizerId ? ` · 授权人 ${idn.authorizerId}` : ''}`
  }
  if (idn.platform === 'wechat') return '登录账号即机器人 · 需对方先发消息'
  return idn.message ?? null
}

/**
 * IM 渠道选择弹窗（独立居中模态）。
 *
 * <p>为什么不再是锚在「渠道」按钮上的浮层：
 * ① 浮层会被输入框挤在窄缝里，渠道一多就得下钻/返回，来回两层；
 * ② 「并列展示」才能一眼看清「这个渠道下有多少聊天、哪个已经接到本会话」。
 * 所以改成和「浏览器」面板同一套 {@code .fm-backdrop / .fm-modal} 壳，
 * 内部左「渠道」右「聊天」两栏并列，点渠道只切右栏，⛔ 不再有返回上一级。
 */
export function ImChannelPickerModal({
  instances,
  chats,
  boundChatIds,
  inactiveChatIds,
  allowUserIdentity,
  onToggleUserIdentity,
  onBind,
  onUnbind,
  sessionId,
  onQuickBind,
  onOpenChannels,
  onClose,
  guide,
}: Props) {
  const bound = useMemo(() => new Set(boundChatIds), [boundChatIds])
  const inactive = useMemo(() => new Set(inactiveChatIds ?? []), [inactiveChatIds])

  // [机器人身份] 每个渠道探测一次（后端缓存 10 分钟）；失败静默，不挡任何操作。
  const [identities, setIdentities] = useState<Record<string, ImInstanceIdentity | null>>({})
  const identityRequested = useRef<Set<string>>(new Set())
  const instanceIds = instances.map((i) => i.id).join('|')
  useEffect(() => {
    for (const inst of instances) {
      if (identityRequested.current.has(inst.id)) continue
      identityRequested.current.add(inst.id)
      void imApi.instanceIdentity(inst.id)
        .then((r) => setIdentities((prev) => ({ ...prev, [inst.id]: r })))
        .catch(() => setIdentities((prev) => ({ ...prev, [inst.id]: null })))
    }
    // 只在渠道集合变化时探测（identities 变化不应触发重新请求）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [instanceIds])

  /** 默认落在「有已接聊天」的渠道，否则第一个有聊天的，再否则第一个 */
  const initialId = useMemo(() => {
    const withBound = instances.find((i) => chats.some((c) => c.instanceId === i.id && bound.has(c.id)))
    const withChats = instances.find((i) => chats.some((c) => c.instanceId === i.id))
    return (withBound ?? withChats ?? instances[0])?.id ?? null
    // 只在挂载时决定一次：之后用户点哪个就是哪个
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const [activeId, setActiveId] = useState<string | null>(initialId)

  // Esc 关闭（与工具面板同语义：弹窗最外层直接关）
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const activeInst = instances.find((i) => i.id === activeId) ?? null
  /** 当前渠道下的聊天：已接的置顶，其余按名称 */
  const activeChats = useMemo(() => {
    const list = chats.filter((c) => c.instanceId === activeId)
    return list.slice().sort((a, b) => {
      const ab = bound.has(a.id) ? 1 : 0
      const bb = bound.has(b.id) ? 1 : 0
      return bb - ab || a.name.localeCompare(b.name, 'zh-Hans-CN')
    })
  }, [chats, activeId, bound])
  const activeBoundCount = activeChats.filter((c) => bound.has(c.id)).length

  const handleBind = (c: PickerChat) => {
    // 已被**别的会话**占用 ⇒ 先说清会顶掉谁（与改绑前的旧行为一致，不能静默抢）
    if (c.boundSessionId) {
      const who = c.boundSessionTitle ?? c.boundSessionId
      const okd = window.confirm(
        `这个聊天现在接在「${who}」上。\n\n接到当前会话后，它收到的消息就不再进「${who}」了。\n确认改绑吗？`)
      if (!okd) return
    }
    onBind(c.id)
  }

  return createPortal(
    <div className="fm-backdrop chanm-backdrop" onClick={onClose}>
      <div
        className="fm-modal chanm-modal"
        role="dialog"
        aria-modal="true"
        aria-label="IM 渠道"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="fm-header chanm-head">
          <svg className="chanm-head-ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M21 12a8 8 0 01-11.5 7.2L4 21l1.8-5.5A8 8 0 1121 12z" />
          </svg>
          <div className="fm-title">IM 渠道</div>
          <span className="fm-subtitle">消息收发走这些渠道</span>
          <button className="chanm-x" onClick={onClose} title="关闭" aria-label="关闭">×</button>
        </div>

        {/* [V329] 无人值守入口未配渠道时的引导：本弹窗只做渠道配置，开关在输入框「渠道」右侧。 */}
        {guide && instances.length === 0 && (
          <div className="chan-guide">
            请先配置渠道：接入一个渠道并把它接到本会话，即可在输入框「渠道」右侧打开无人值守。
          </div>
        )}

        <div className="chanm-body">
          {/* ── 左栏：已接入的渠道（点一下只切右栏） ── */}
          <aside className="chanm-side">
            <div className="chanm-side-h">
              <span>已接入的渠道</span>
              <span className="chanm-side-c">{instances.length}</span>
            </div>
            {instances.length === 0 ? (
              <div className="chanm-empty-sm">
                还没有接入渠道
                <br />
                <span>点下面「管理渠道」去添加</span>
              </div>
            ) : (
              instances.map((inst) => {
                const list = chats.filter((c) => c.instanceId === inst.id)
                const n = list.filter((c) => bound.has(c.id)).length
                const sel = inst.id === activeId
                const botLine = identityText(identities[inst.id])
                return (
                  <div
                    key={inst.id}
                    className={`chanm-inst${sel ? ' sel' : ''}`}
                    role="button"
                    tabIndex={0}
                    aria-current={sel}
                    onClick={() => setActiveId(inst.id)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        setActiveId(inst.id)
                      }
                    }}
                  >
                    <span className={`imc-plat sm ${inst.platform ?? 'none'}`}>
                      {inst.platform ? PLATFORM_CHAR[inst.platform] : '渠'}
                    </span>
                    <span className="chanm-inst-body">
                      <span className="chanm-inst-name">{inst.label}</span>
                      <span className="chanm-inst-sub">
                        {inst.platform ? PLATFORM_LABEL[inst.platform] : '渠道'} · {list.length} 个聊天
                        {n > 0 && <> · 已接 {n}</>}
                      </span>
                      {botLine && (
                        <span
                          className="chanm-inst-bot"
                          data-testid={`chanm-bot-${inst.id}`}
                          title={identities[inst.id]?.message ?? botLine}
                        >
                          {botLine}
                        </span>
                      )}
                    </span>
                    {onQuickBind && (
                      <button
                        type="button"
                        className="chanm-quickbind"
                        title="把这个渠道的机器人默认会话接到当前会话"
                        disabled={!sessionId}
                        onClick={(e) => {
                          e.stopPropagation()
                          onQuickBind(inst.id)
                        }}
                      >
                        快速绑定
                      </button>
                    )}
                    <svg className="chanm-inst-arrow" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M4 2l4 4-4 4" />
                    </svg>
                  </div>
                )
              })
            )}
          </aside>

          {/* ── 右栏：该渠道下的聊天（直接在这一栏操作绑定/解绑） ── */}
          <section className="chanm-main">
            <div className="chanm-main-h">
              <span className="chanm-main-t">{activeInst?.label ?? '聊天'}</span>
              {activeInst && (
                <span className="chanm-main-c">
                  {activeChats.length} 个聊天
                  {activeBoundCount > 0 && <> · 已接 <b>{activeBoundCount}</b></>}
                </span>
              )}
            </div>
            <div className="chanm-list">
              {!activeInst ? (
                <div className="chanm-empty">先在左边选一个渠道</div>
              ) : activeChats.length === 0 ? (
                <div className="chanm-empty">
                  这个渠道还没有能接到本会话的聊天
                  <br />
                  <span>
                    列表只显示<b>现在能发消息</b>的会话：微信群聊不支持群发、微信单聊需对方先发一句；
                    飞书群需机器人已加入。先在「IM 频道」里启动连接，再私聊机器人发一句话。
                  </span>
                </div>
              ) : (
                activeChats.map((c) => {
                  const on = bound.has(c.id)
                  return (
                    <div className={`chanm-chat${on ? ' on' : ''}`} key={c.id}>
                      <span className={`chanm-avatar ${c.kind}`}>{c.kind === 'group' ? '群' : '人'}</span>
                      <div className="chanm-chat-body">
                        <div className="chanm-chat-name" title={c.name}>
                          {c.unnamed
                            ? `${c.kind === 'group' ? '未命名群聊' : '未命名私聊'} · ${shortChatId(c.name)}`
                            : c.name}
                        </div>
                        <div className="chanm-chat-sub">
                          {c.kind === 'group' ? '群聊' : (c.unnamed ? '私聊 · 对方未命名' : '私聊')}
                          {inactive.has(c.id) && (
                            <span
                              className="chanm-inactive"
                              data-testid={`chanm-inactive-${c.id}`}
                              title="微信需先给对方发一条消息激活，否则权限通知发不出去"
                            >
                              未激活
                            </span>
                          )}
                          {!on && c.boundSessionId && (
                            <span className="chanm-occupied" title={`现在接在「${c.boundSessionTitle ?? c.boundSessionId}」上`}>
                              已被「{c.boundSessionTitle ?? c.boundSessionId}」占用
                            </span>
                          )}
                          {/* V335 · 机器人不在该群：已接线的仍显示（要能解绑），但必须让人知道收不到事件 */}
                          {c.botPresent === false && (
                            <span
                              className="chanm-unreachable"
                              title="机器人已被移出该群：群里 @ 它不会推送事件，历史消息也拉不到"
                            >
                              机器人不在该群
                            </span>
                          )}
                        </div>
                      </div>
                      {on ? (
                        <>
                          <span className="chanm-on-badge">已绑定</span>
                          <button
                            type="button"
                            className="chanm-unbind"
                            onClick={() => onUnbind(c.id)}
                          >解绑</button>
                        </>
                      ) : (
                        <button
                          type="button"
                          className="chanm-bind"
                          onClick={() => handleBind(c)}
                        >接到此会话</button>
                      )}
                    </div>
                  )
                })
              )}
            </div>
          </section>
        </div>

        <div className="fm-footer chanm-foot">
          <button className="fm-btn" onClick={() => { onOpenChannels(); onClose() }}>
            ＋ 管理渠道
          </button>
          <span className="spacer" />
          {onToggleUserIdentity && (
            <>
              <span
                className="chanm-sw-lbl"
                title="开启后 AI 可以以你的名义在 IM 里发言（对方看到的是你本人说的）"
              >
                允许以我的名义发消息
              </span>
              <button
                type="button"
                className={`imc-sw${allowUserIdentity ? ' on' : ''}`}
                role="switch"
                aria-checked={!!allowUserIdentity}
                aria-label="允许以我的名义发消息"
                onClick={onToggleUserIdentity}
              >
                <i />
              </button>
            </>
          )}
        </div>
      </div>
    </div>,
    document.body,
  )
}
