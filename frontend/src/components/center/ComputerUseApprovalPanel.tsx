import { useMemo } from 'react'
import type { ComputerUseApp } from '@/api/types'
import type { PermissionRequestItem } from '@/stores/chatStore'

/**
 * [桌面控制审批面板] 后端一次算好推给前端的应用清单 + 授权范围文案。
 *
 * <p><b>WHY 前端不自己翻译</b>：授权范围（只能查看 / 可以点击和滚动 / 可以完全操作）是
 * **业务语义**（后端按应用分类表决定），前端另写一份映射必然漂移 ⇒ 后端给 `tierLabel`，
 * 前端原样渲染。
 *
 * <p><b>授权语义 = 整组授予</b>（点「允许」= 该批应用全部按其档位授权 + 被请求的额外能力全部授予；
 * 点「拒绝」= 全部拒绝）。与上游一致：授权位由**工具入参**决定，后端在工具执行时落库，
 * ⇒ 前端**不需要**新增任何上行字段（走既有的 allow / deny 即可）。
 *
 * <p><b>文案纪律</b>：本面板只出现大白话中文 + 应用名，**不出现**任何内部术语
 * （不出现 computer-use / MCP / 档位机器值）。
 */

/**
 * 桌面控制工具族前缀。
 *
 * <p>后端唯一的授权申请工具名 = `mcp__computer-use__request_access`
 * （后端 `ComputerUseRequestAccessTool.TOOL_NAME`；CCB 要求该族工具名固定为
 * `mcp__computer-use__<tool>`）。这里用**前缀**判定而非全等：将来该族新增「带授权语义」的
 * 工具时，凡是「没带授权信息」的请求一律走 fail-loud（拒绝放行）而不是盲授权 ——
 * 判据取宽的那一侧，误伤的表现是「拒绝」（安全侧），漏判的表现是「盲授权」（危险侧）。
 *
 * <p>⚠️ 同族的 `screenshot` / `left_click` 是**动作**工具（后端 checkPermissions 直接 Allow，
 * 免弹窗）⇒ 不会走到本判据；只有 `request_access` 会触发审批。
 */
export const COMPUTER_USE_TOOL_PREFIX = 'mcp__computer-use__'

/** 该工具名是否是桌面控制工具族的一员（fail-loud 判据用）。 */
export function isComputerUseTool(toolName: string | null | undefined): boolean {
  return !!toolName && toolName.startsWith(COMPUTER_USE_TOOL_PREFIX)
}

/** 应用行（渲染用 · 已把机器值与展示文案分离）。 */
interface AppRow {
  key: string
  name: string
  /** 授权范围的大白话文案（后端给）· 空 ⇒ 后端未给（留空不展示） */
  tierLabel: string
  alreadyGranted: boolean
  notInstalled: boolean
}

/**
 * 应用清单 → 渲染行。
 *
 * <p>⚠️ `tierLabel` 缺失时**留空**（不回落成机器值 read/click/full —— 那是内部术语），
 * 并记一条 warn 留痕（不静默降级）。
 */
function toAppRows(apps: ComputerUseApp[] | null | undefined): AppRow[] {
  return (apps ?? []).map((a, i) => {
    if (!a.tierLabel) {
      console.warn('[perm] 桌面控制应用缺少授权范围文案，已留空展示', a.appId)
    }
    return {
      key: a.appId || `app-${i}`,
      name: a.displayName || a.appId || '未命名应用',
      tierLabel: a.tierLabel ?? '',
      alreadyGranted: a.alreadyGranted === true,
      notInstalled: a.notInstalled === true,
    }
  })
}

/** 被请求的额外能力 → 展示文案列表（**只渲染被请求的**）。 */
function toFlagLabels(
  requestedFlags: string[] | null | undefined,
  flagLabels: Record<string, string> | null | undefined,
): string[] {
  return (requestedFlags ?? []).map((flag) => {
    const label = flagLabels?.[flag]
    if (!label) {
      // 后端把「被请求的位」与「位的中文标签」成对产出 ⇒ 缺标签 = 契约破损。
      // 不回落成机器名（clipboardRead 等是内部术语）⇒ 用中性兜底 + warn 留痕。
      console.warn('[perm] 桌面控制额外能力缺少展示文案，已用兜底文案', flag)
      return '额外权限'
    }
    return label
  })
}

interface Props {
  request: PermissionRequestItem
  onDecision: (requestId: string, decision: 'allow' | 'deny') => void
  onAbort?: () => void
}

/**
 * 桌面控制授权面板（后端带 `computerUse` 时渲染 · 插在普通 allow/deny 分支之前）。
 */
export function ComputerUseApprovalPanel({ request, onDecision, onAbort }: Props) {
  const cu = request.computerUse
  const appRows = useMemo(() => toAppRows(cu?.apps), [cu?.apps])
  const flagLabels = useMemo(
    () => toFlagLabels(cu?.requestedFlags, cu?.flagLabels),
    [cu?.requestedFlags, cu?.flagLabels],
  )

  return (
    <div className="permission-bubble">
      <div className="pb-title">需要你授权控制电脑上的应用</div>
      {request.warning && <div className="pb-warning">{request.warning}</div>}
      {request.isLeaderInbox && (
        <div className="pb-swarm-badge" style={request.workerBadgeColor ? { background: request.workerBadgeColor } : undefined}>
          swarm · 队友请求
        </div>
      )}
      {request.isLeaderInbox && (
        <div className="pb-worker">{request.workerName ? `来自 ${request.workerName}` : '来自队友（名称待后端补字段）'}</div>
      )}
      {cu?.reason && <div className="pb-desc">{cu.reason}</div>}
      {appRows.length > 0 && (
        <div className="pb-cu-list">
          {appRows.map((row) => (
            <div className="pb-cu-app" key={row.key}>
              <span className="pb-cu-app-name">{row.name}</span>
              {row.tierLabel && <span className="pb-cu-tier">{row.tierLabel}</span>}
              {row.alreadyGranted && <span className="pb-cu-note">已授权</span>}
              {row.notInstalled && <span className="pb-cu-note">未安装</span>}
            </div>
          ))}
        </div>
      )}
      {flagLabels.length > 0 && (
        <div className="pb-cu-flags">
          <div className="pb-cu-flags-title">还需要这些额外权限</div>
          {flagLabels.map((label, i) => (
            <div className="pb-cu-flag" key={`${label}-${i}`}>{label}</div>
          ))}
        </div>
      )}
      <div className="pb-desc">点「允许」将按上面标注的权限范围，一次性授权全部列出的应用；点「拒绝」则全部不授权。</div>
      <div className="pb-actions">
        {onAbort && <button className="pb-abort" onClick={onAbort}>中止</button>}
        <button onClick={() => onDecision(request.requestId, 'deny')}>拒绝</button>
        <button className="primary" onClick={() => onDecision(request.requestId, 'allow')}>允许</button>
      </div>
    </div>
  )
}

interface ErrorProps {
  request: PermissionRequestItem
  onDecision: (requestId: string, decision: 'allow' | 'deny') => void
  onAbort?: () => void
}

/**
 * ⛔ fail-loud：桌面控制的授权请求**没带授权信息** ⇒ 拒绝放行 + 显示错误。
 *
 * <p>WHY 不能回落普通 allow/deny 分支：普通分支只显示工具名，用户在**看不到「要控制哪些应用、
 * 能控制到什么程度」**的情况下点「允许」⇒ 变成**盲授权**（后端按工具入参落库，授权范围照跑）。
 * ⇒ 这里**只给「拒绝」**（不放「允许」按钮），把决定权交回用户。
 */
export function ComputerUseApprovalError({ request, onDecision, onAbort }: ErrorProps) {
  console.warn('[perm] 桌面控制授权请求缺少授权信息，已阻止放行', request.requestId)
  return (
    <div className="permission-bubble">
      <div className="pb-title">无法确认这次要授权的范围</div>
      <div className="pb-cu-error">
        这次请求没有带来说明「要控制哪些应用、能控制到什么程度」的信息，为了避免误授权，已阻止放行。
        请拒绝后重试，或检查服务端是否正常下发了授权信息。
      </div>
      <div className="pb-actions">
        {onAbort && <button className="pb-abort" onClick={onAbort}>中止</button>}
        <button onClick={() => onDecision(request.requestId, 'deny')}>拒绝</button>
      </div>
    </div>
  )
}
