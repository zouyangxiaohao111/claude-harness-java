/**
 * Monaco 共享配置（FileViewModal / DiffModal 用）·
 * Vite + Tauri 集成：worker 必须配置，否则 Monaco 回退主线程、功能降级。
 *
 * <p>仅注册 editorWorker（代码查看/编辑场景无需 css/json/html/ts 语言服务 worker ——
 * 我们只用语法高亮 + 编辑，不依赖语言智能提示）。
 */

import * as monaco from 'monaco-editor'
// monaco-editor 0.56 exports `"./*.js" → "./esm/vs/*.js"` 对 esm/vs/ 子路径产生双重路径 bug，
// 裸模块子路径（含 ?worker）Vite resolve 失败 → 用相对路径直指实际文件绕过 exports
import editorWorker from '../../node_modules/monaco-editor/esm/vs/editor/editor.worker.js?worker'

;(self as unknown as { MonacoEnvironment: { getWorker: () => Worker } }).MonacoEnvironment = {
  getWorker: () => new editorWorker(),
}

/** 文件扩展名 → Monaco 语言 id（全量 monaco-editor 已内置 basic-languages） */
export const EXT_TO_MONACO_LANG: Record<string, string> = {
  java: 'java', yml: 'yaml', yaml: 'yaml', md: 'markdown', markdown: 'markdown',
  php: 'php', js: 'javascript', mjs: 'javascript', cjs: 'javascript',
  ts: 'typescript', tsx: 'typescript', jsx: 'javascript',
  py: 'python', go: 'go', rs: 'rust', sh: 'shell', bash: 'shell',
  json: 'json', xml: 'xml', html: 'html', htm: 'html',
  css: 'css', scss: 'scss', sql: 'sql',
  properties: 'properties', conf: 'ini', ini: 'ini',
  txt: 'plaintext',
}

/** 按文件路径取 Monaco 语言 id（未知扩展回落 plaintext） */
export function monacoLangOf(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase() ?? ''
  return EXT_TO_MONACO_LANG[ext] ?? 'plaintext'
}

/** Monaco 默认编辑选项（FileViewModal / DiffModal 共用） */
export const MONACO_COMMON_OPTS: monaco.editor.IStandaloneEditorConstructionOptions = {
  theme: 'vs',
  fontSize: 12.5,
  minimap: { enabled: false },
  automaticLayout: true,
  scrollBeyondLastLine: false,
  tabSize: 2,
}

export { monaco }
