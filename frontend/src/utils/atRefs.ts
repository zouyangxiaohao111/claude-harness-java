/**
 * [Phase3 @引用文件] 从用户消息 content 里解析 @引用 token（展示「引用文件」chips）。
 *
 * <p>语法对齐 CC extractAtMentionedFiles：{@code @path} 或 {@code @"path with space"}，可选
 * 行区间 {@code #L10-20}（仅定位用，取文件时不带）。与后端 {@code ChatService.appendReferencedFiles}
 * 解析规则保持一致（同一引用文本 → 同一批路径注入模型上下文）。</p>
 *
 * @param text 用户消息/输入内容
 * @return 去重后的相对路径列表（无引用 → []）
 */
export function extractAtRefs(text: string | null | undefined): string[] {
  if (!text || !text.includes('@')) return []
  const set = new Set<string>()
  // 捕获 '@' 前缀限定在行首或空白/中文标点/左括号/引号后（避免 @中间 误命中）
  const re = /(?:^|[\s，。、；：（(【“"'“])@("[^"]+"|[^\s，。、；：（()）“”"'“]+)/g
  let m: RegExpExecArray | null
  while ((m = re.exec(text)) !== null) {
    let raw = m[1]
    if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw = raw.slice(1, -1)
    const hashL = raw.indexOf('#L')
    if (hashL >= 0) raw = raw.slice(0, hashL)
    if (raw && raw !== '.' && raw !== '..') set.add(raw)
  }
  return [...set]
}

/** 提取行内最后一个 @token（输入框补全触发探测用 · 与 Composer 端锚定逻辑等价）。 */
export function atTailToken(text: string): { segStartAbs: number; prefix: string } | null {
  const lastNl = text.lastIndexOf('\n')
  const line = lastNl >= 0 ? text.slice(lastNl + 1) : text
  const seg = Math.max(
    line.lastIndexOf(' '), line.lastIndexOf('，'), line.lastIndexOf('。'),
    line.lastIndexOf('：'), line.lastIndexOf('（'), line.lastIndexOf('('),
  )
  const tail = line.slice(seg + 1)
  if (!tail.startsWith('@')) return null
  return { segStartAbs: (lastNl >= 0 ? lastNl + 1 : 0) + seg, prefix: tail.slice(1) }
}
