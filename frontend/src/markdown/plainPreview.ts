import { extractMarkdownPlainText } from './plain-text.ts'

/**
 * 有界预览投影：**先按字符截断源文、再做纯文本投影**（顺序不可换）。
 *
 * 顺序为什么不可换：`extractMarkdownPlainText` 内部会对入参跑一次全量 parseGfm。
 * 若先投影再截断，等于对**全文** parse —— 而调用方用它恰恰是为了「不 parse 超长正文」
 * （见 ContentGuard 的存在理由）。三个落点（ContentGuard 折叠预览 / TraceView 轨迹 /
 * DialogOpsModal 列表预览）共用本函数，顺序约定在此单点固定并被测试钉住。
 *
 * 已知边界（继承自 plain-text，见其头注释）：逐行 trim 会抹平代码块缩进；
 * 用 parseGfm（不跑 rescue）故粘连标记与行内 $$ 保持字面；整片为 thematicBreak /
 * definition 时投影结果可为空串 —— 调用方若需要兜底请自行处理。
 */
export function projectPreview(source: string, maxChars: number): string {
  return extractMarkdownPlainText(source.slice(0, maxChars))
}

/** 预览源文窗口（字符）：与 harness 的 PREVIEW_SOURCE_CHARACTERS 同值
 *  （deepseek-harness packages/client/ui-trajectory/src/client/trajectory-preview.ts:5）。
 *  各落点的预览 helper 一律引用本常量 —— 三个落点共用一个「窗口多大算够」的约定，
 *  不要在下游再写字面量（改一处即可全局生效）。 */
export const PREVIEW_SOURCE_CHARACTERS = 2_048
