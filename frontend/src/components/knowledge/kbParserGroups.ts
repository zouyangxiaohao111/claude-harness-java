import type { ParserEngineInfo } from '@/api/knowledge'

/**
 * 解析引擎的「文件类型分组 + 引擎选项」纯函数集。
 *
 * <p><b>为什么抽成独立模块</b>：这套逻辑（12 固定组 + 动态扩展名组、默认引擎判定、
 * 下拉选项生成）原本只写在 `KbCreateModal.tsx` 里。知识库设置弹窗的「解析引擎」节
 * 需要**完全一样**的行为 —— 复制一份必然漂移（源站两处也共用同一组函数）。
 *
 * <p>⛔ 本文件是**纯函数**（无 React、无状态、无 I/O）：
 * 两个弹窗各自持有 `engineRules` 状态与后端清单，只共享这里的算法。
 *
 * <p>所有语义与「为什么这么写」的注释随代码一起搬来，逐字未改 ——
 * 它们记录的是对源站 `KBParserSettings.vue` 的逐行对照结论，删掉就等于丢掉依据。
 */

/**
 * 源站 `KBParserSettings.vue:135-186` 的 12 个文件类型分组。
 *
 * <p><b>分组 / 文案 / 图标是前端硬编码，只有下拉选项来自后端</b>（源站注释原话：
 * "Keep the UI driven by the backend registry"）。⛔ 不要改成从前端推导。
 *
 * <p>⚠️ 与源站的唯一差异：源站先算 `allFileTypes`（所有引擎声明扩展名的并集），
 * 再 `['docx','doc'].filter(e => ft.has(e))` —— **后端没有的组整行不渲染**。
 * 本仓后端只有 3 个解析器，直接照抄会只剩 8 组（电子书/网页归档/图片/音频 静默消失）。
 * 这里保留 12 组恒定渲染，缺引擎的组走「无可用引擎」只读态 —— 用户能看见
 * 「本仓不支持这类格式」，而不是以为漏了。派单书也明确要求 12 组齐全。
 */
export const PARSER_GROUPS: Array<{ key: string; label: string; icon: string; extensions: string[] }> = [
  { key: 'pdf', label: 'PDF 文档', icon: 'file-pdf', extensions: ['pdf'] },
  { key: 'office', label: 'Word 文档', icon: 'file-word', extensions: ['docx', 'doc'] },
  { key: 'ppt', label: '演示文稿', icon: 'file-powerpoint', extensions: ['pptx', 'ppt'] },
  { key: 'excel', label: 'Excel 表格', icon: 'file-excel', extensions: ['xlsx', 'xls'] },
  { key: 'ebook', label: '电子书', icon: 'file', extensions: ['epub'] },
  { key: 'webarchive', label: '网页归档', icon: 'file', extensions: ['mhtml'] },
  { key: 'csv', label: 'CSV 文件', icon: 'file-excel', extensions: ['csv'] },
  { key: 'markdown', label: 'Markdown', icon: 'file-code', extensions: ['md', 'markdown'] },
  { key: 'text', label: '纯文本', icon: 'file', extensions: ['txt'] },
  { key: 'json', label: 'JSON 文件', icon: 'file-code', extensions: ['json'] },
  { key: 'image', label: '图片', icon: 'image', extensions: ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'tiff', 'webp'] },
  { key: 'audiovisual', label: '音频', icon: 'sound', extensions: ['mp3', 'wav', 'm4a', 'flac', 'ogg'] },
]

/**
 * 源站 `KBParserSettings.vue:222-228` 的 `simpleFileTypes` —— 「简单格式」。
 * 后端对这批扩展名默认走 `simple` 引擎（进程内读取器，不需要文档模型）。
 */
export const SIMPLE_FILE_TYPES = new Set([
  'md', 'markdown', 'txt', 'text', 'csv', 'json',
  'jpg', 'jpeg', 'png', 'gif', 'bmp', 'tiff', 'webp',
  'mp3', 'wav', 'm4a', 'flac', 'ogg',
])

/** 源站 `KBParserSettings.vue:231` —— `anydoc` 没有 PDF 文档模型，后端永不把 PDF 路由给它。 */
export const NO_ANYDOC_FILE_TYPES = new Set(['pdf'])

/** 源站 `KBParserSettings.vue:233-236` —— 这几个扩展名的「钉死引擎」。
 *  ⚠️ 它在 `anydoc` 判定<strong>之后</strong>才检查，故 anydoc 可用时不会生效（源站真实顺序）。 */
const PINNED_ENGINE_BY_FILE_TYPE: Record<string, string> = {
  ppt: 'markitdown',
  pptx: 'markitdown',
}

/**
 * 只留「`FileTypes` 与这组扩展名有交集」的引擎（源站 `getEngineOptions` 第一步 `raw` 筛选）。
 *
 * <p>抽成函数是为了让「算默认引擎」与「算下拉选项」**用同一份候选集** ——
 * 源站 `getEngineOptions` 把筛过的 `raw` 传给 `pickDefaultEngineName`，
 * 外面那个 `getDefaultEngine` 也走 `getEngineOptions`；若两处各筛一次，
 * 将来有人改了筛选条件就会「标记的默认」与「显示的选项」对不上。
 */
export function enginesForExtensions(engines: ParserEngineInfo[], extensions: string[]): ParserEngineInfo[] {
  return engines.filter((e) => extensions.some((ext) => (e.FileTypes || []).includes(ext)))
}

/** 源站 `usable(name, ext)` 闭包：存在一个**可用**引擎，名字相符且声明了该扩展名。 */
export function usableEngine(engines: ParserEngineInfo[], name: string, ext: string): boolean {
  return engines.some(
    (e) => e.Available && e.Name === name && (e.FileTypes || []).includes(ext)
  )
}

/**
 * 源站 `defaultEngineForFileType(engines, ext)`（`KBParserSettings.vue:239-252`）逐字翻译。
 *
 * <p>三个判定的**顺序有语义**，⛔ 不要重排：
 * <ol>
 *   <li>非简单格式、非 PDF、且 `anydoc` 可用 ⇒ `anydoc`
 *       （Office/演示文稿/电子书/网页归档 优先给它）</li>
 *   <li>`pinnedEngineByFileType` 里有、且该引擎可用 ⇒ 用它。
 *       ⚠️ 实测：`ppt`/`pptx` 也会先命中第 1 条（它们既非简单格式也非 PDF），
 *       所以这一条<b>只在 anydoc 不可用时才生效</b> —— 这是源站真实行为
 *       （`preferAnydocWhenAvailable`），⛔ 不要因为「pinned 应该优先」就把它挪到前面。</li>
 *   <li>兜底：简单格式给 `simple`，其余给 `builtin`；都没有则返回空串</li>
 * </ol>
 *
 * <p>⚠️ 本仓 3 个解析器叫 `KbPdfParser` / `KbOfficeParser` / `KbTextParser`，
 * 与上面这批名字**一个都对不上** ⇒ 逐字照抄的结果是「任何组都不标 (默认)」。
 * 这是真实状态（源站那套默认路由在本仓不存在），⛔ 不要为了显示「(默认)」而编一个引擎名。
 */
export function defaultEngineForFileType(engines: ParserEngineInfo[], ext: string): string {
  const usable = (name: string) => usableEngine(engines, name, ext)
  if (!SIMPLE_FILE_TYPES.has(ext) && !NO_ANYDOC_FILE_TYPES.has(ext) && usable('anydoc')) {
    return 'anydoc'
  }
  const pinned = PINNED_ENGINE_BY_FILE_TYPE[ext]
  if (pinned && usable(pinned)) return pinned
  const unpinned = SIMPLE_FILE_TYPES.has(ext) ? 'simple' : 'builtin'
  return usable(unpinned) ? unpinned : ''
}

/**
 * 源站 `pickDefaultEngineName(engines, extensions)`（`KBParserSettings.vue:254-262`）。
 *
 * <p>一个下拉服务整组，只有当组内**每个扩展名**都算出同一个非空名字时才认它做默认；
 * 否则整组都不标 —— 否则会出现「显示 (默认) 但实际按另一种格式路由」的谎。
 */
export function defaultEngineForGroup(engines: ParserEngineInfo[], extensions: string[]): string {
  const perFileType = extensions.map((ext) => defaultEngineForFileType(engines, ext))
  const first = perFileType[0] ?? ''
  return perFileType.every((name) => name === first) ? first : ''
}

/**
 * 源站 `getEngineOptions(extensions)`（`KBParserSettings.vue:190-214`）。
 *
 * <p>① 先筛「`FileTypes` 与本组扩展名有交集」的引擎；② 只留 `Available === true`；
 * ③ 默认引擎的选项文案追加「 (默认)」。
 *
 * <p>选项文案用 `Description`（本仓承载的是展示名「内置 PDF 解析」），空则退回 `Name` ——
 * 与源站 `desc || name` 同义。
 */
export function engineOptionsForGroup(
  engines: ParserEngineInfo[],
  extensions: string[]
): Array<{ value: string; label: string }> {
  const matched = enginesForExtensions(engines, extensions)
  const available = matched.filter((e) => e.Available !== false)
  const defaultName = defaultEngineForGroup(matched, extensions)

  /* ⭐ 本仓适配（对源站的有意偏离，原因写在下面）：
     源站的「默认」算法按**引擎名**匹配 `anydoc`/`simple`/`builtin`/`markitdown`；
     本仓引擎叫 `KbPdfParser`/`KbOfficeParser`/`KbTextParser`，逐字照抄的后果是
     **任何组都标不出 (默认)** —— 而每组实际上只有一个可用引擎，UI 会看起来像坏了。

     这里补一条语义等价的规则：某组**只有一个可用引擎**时，它必然就是解析器路由
     会选中的那个（注册表按 @Order 升序取第一个 supports() 命中的），所以标它 (默认)。
     多选项时仍严格走源站算法，⛔ 不臆造。 */
  const soleEngine = available.length === 1 ? available[0].Name : ''

  return available.map((e) => {
    const isDefault = defaultName !== '' ? e.Name === defaultName : e.Name === soleEngine
    return {
      value: e.Name,
      label: (e.Description || e.Name) + (isDefault ? ' (默认)' : ''),
    }
  })
}

/**
 * 「固定 12 组 + 动态扩展名组」· 对齐源站 `KBParserSettings.vue:173-179`。
 *
 * <p>源站注释原话：Keep the UI driven by the backend registry. New parser plugins
 * can expose file types without requiring another frontend release。
 * ⇒ 后端注册表出现新扩展名时，前端不用发版就能多出一行。
 *
 * <p>⚠️ 与源站的一处**必要差异**：源站每次先按 `allFileTypes` 过滤固定组
 * （`if (pdfExts.length) groups.push(...)`，后端没有的组整行不渲染），所以它的
 * `grouped` 只含「实际渲染出来的组」的扩展名。本仓固定 12 组**恒定渲染**
 * （见 `PARSER_GROUPS` 注释：缺引擎走「无可用引擎」只读态），因此 `grouped` 必须按
 * **常量全集**算 —— 否则 `image` / `audiovisual` 这类本仓暂时没有引擎的组，
 * 会把 `jpg` / `mp3` 等扩展名重复吐成动态行。
 *
 * @param engines 后端解析引擎清单
 * @returns 固定组在前、动态组在后（动态组按扩展名字典序）
 */
export function parserGroupsWithDynamic(
  engines: ParserEngineInfo[]
): Array<{ key: string; label: string; icon: string; extensions: string[] }> {
  /* 源站 `allFileTypes`（`KBParserSettings.vue:125-133`）：所有引擎声明扩展名的并集。
     ⚠️ 本仓用 `Map<小写, 原样>` 而不是 `Set<小写>`：**比较用小写、输出用原样**。
     若把扩展名本身改成小写，`engineOptionsForGroup` 的 `(e.FileTypes||[]).includes(ext)`
     是**大小写敏感**的 ⇒ 后端真回了 `Zip` 时动态行会算不出引擎（实测对照：小写版
     出 `ZIP` 且无下拉）。比较侧仍需小写归一，否则 `DOCX` 会与固定组的 `docx` 同时存在、
     漏判后重复吐一行（实测对照：改成小写归一后 `DOCX`/`Md` 均被正确吞掉）。 */
  const allFileTypes = new Map<string, string>()
  for (const e of engines) {
    for (const ft of e.FileTypes || []) {
      const lower = ft.toLowerCase()
      if (!allFileTypes.has(lower)) allFileTypes.set(lower, ft)
    }
  }
  /* 源站 `grouped`（`KBParserSettings.vue:176`）。⚠️ 取值来源见上面 javadoc 的差异说明。 */
  const grouped = new Set(
    PARSER_GROUPS.flatMap((g) => g.extensions.map((ext) => ext.toLowerCase()))
  )
  /* 源站循环体（`:177-179`）：未覆盖且非 `url` 的扩展名，字典序追加为紧凑动态行。
     `sort()` 是源站原话（默认字典序，不做 locale 比较）—— 这里显式比较「小写键」，
     与源站对全小写输入的默认 `sort()` 结果逐项相同，且对混合大小写也稳定。 */
  const dynamicGroups = [...allFileTypes.entries()]
    .filter(([lower]) => !grouped.has(lower) && lower !== 'url')
    .sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
    .map(([, ext]) => ({
      key: `dynamic-${ext}`,
      label: ext.toUpperCase(),
      icon: 'file-code',
      extensions: [ext],
    }))
  return [...PARSER_GROUPS, ...dynamicGroups]
}
