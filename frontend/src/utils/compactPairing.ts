import type { ChatMessageDto } from '@/api/types'

/** compact 边界 ↔ 压缩摘要正文 的配对表（栈式配对 · key = boundary 消息 id，value = 摘要正文）。
 *
 *  <p><b>为什么需要配对</b>：boundary 消息自身<b>不含</b>摘要正文 —— 后端
 *  {@code CompactBoundaryMessage.toCompactMetadataMap} 从不 put summary 字段。摘要正文是紧随其后的
 *  {@code isCompactSummary === true} 的 user 消息（CC original: compact.ts:643-650 /
 *  compact.ts:1068-1077；后端 {@code CompactConversation.buildCompactSummaryMessage}）。二者之间没有
 *  显式外键，只能靠<b>消息先后顺序</b>关联。
 *
 *  <p><b>为什么「从 boundary 往后扫、遇到下一个 boundary 就停」是错的</b>：{@code direction=from} 的
 *  partial compact 产出顺序是 {@code [boundary_B, ...messagesToKeep, ...summaryMessages, ...]}，
 *  而 from 的 messagesToKeep 是「切片前缀、只滤 progress」→ <b>刻意保留</b>旧的 boundary_A 与旧的
 *  summary_A。DB(seq) 顺序于是是 {@code [B2, m0, …, B1, S1, …, S2]}：从 B2 往后第一个遇到的 boundary
 *  是 B1 → 立刻停 → B2 拿不到 S2 → 「已压缩」标记详情回落英文常量。
 *
 *  <p><b>栈式配对</b>：一次前向扫，遇 compact_boundary 压栈；遇 isCompactSummary 认领给<b>栈顶</b>并弹栈
 *  （后出现的摘要归属「最近的、尚未配到摘要的」边界）。正确性推演：
 *  <ul>
 *    <li>全量 / up_to：{@code [B, S, keep…]} → 压 B、S 配给 B ✅</li>
 *    <li>from（本 bug）：{@code [B2, …, B1, S1, …, S2]} → 压 B2、压 B1、S1 配 B1 弹栈、S2 配 B2 弹栈 ✅</li>
 *    <li>嵌套（先 C1 再 C2，C2 的 keep 段含 B1/S1）：{@code [B2, S2, …, B1, S1]} → B2/S2 配对、B1/S1 配对 ✅</li>
 *  </ul>
 *
 *  <p><b>已知边界情形（如实说明）</b>：有界窗口在头部截断时，某 boundary 的摘要可能不在 messages 里
 *  （如 {@code [B2, m0, B1]} 缺 S1/S2）→ 栈只堆不弹 → 该 boundary 在表里查不到 → 调用方回落 boundary
 *  自身 content（与旧实现一致，不更坏）。对称地，若摘要先于其 boundary 出现（boundary 被截断掉），
 *  该摘要无人认领 → 丢弃。
 *
 *  <p>microcompact_boundary 没有摘要正文（只作居中标记行渲染），<b>不参与</b>配对，也<b>不中断</b>扫描。
 */
export function pairCompactSummaries(messages: ChatMessageDto[]): Map<string, string> {
  const pairs = new Map<string, string>()
  /** 尚未配到摘要的 compact_boundary 栈（后进先出 → 后出现的摘要归属最近的边界） */
  const pending: ChatMessageDto[] = []
  for (const m of messages) {
    if (m.subtype === 'compact_boundary') {
      pending.push(m)
      continue
    }
    if (m.isCompactSummary === true) {
      const owner = pending.pop()
      // 栈空 = 该摘要的 boundary 不在本批消息里（窗口截断）→ 丢弃，不误配给别的边界
      if (owner !== undefined) pairs.set(owner.id, m.content ?? '')
    }
  }
  return pairs
}
