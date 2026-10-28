#!/usr/bin/env node
/**
 * 构建前生成 src-tauri/src/error_report_config.rs。
 *
 * 为什么不靠环境变量：Tauri CLI 调 cargo 时 NEXUSAI_ERROR_REPORT_CONFIG 传不进
 * 构建（2026-10-28 实测：直接 cargo build 能拿到，npm run tauri build 拿不到），
 * 会把内网包静默打成「未配置上报」。这里直接写源文件，cargo 必然读到。
 *
 * 内网构建判定：环境变量 NEXUSAI_ERROR_REPORT_CONFIG 或标记文件
 *   src-tauri/.error-report-internal（构建脚本创建，打包后删除）。
 * GitHub 构建两者都没有 ⇒ 写 None。
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const FRONT = resolve(__dirname, '..')
const OUT = join(FRONT, 'src-tauri/src/error_report_config.rs')
const LOCAL = join(FRONT, 'src-tauri/error-report.local.json')
const MARKER = join(FRONT, 'src-tauri/.error-report-internal')

let cfg = process.env.NEXUSAI_ERROR_REPORT_CONFIG || ''
const internal = Boolean(cfg.trim()) || existsSync(MARKER)
if (internal && !cfg.trim() && existsSync(LOCAL)) cfg = readFileSync(LOCAL, 'utf-8')

if (internal) {
  if (!cfg.trim()) {
    throw new Error('[error-report] 内网构建缺少上报配置：NEXUSAI_ERROR_REPORT_CONFIG 与 error-report.local.json 都为空')
  }
  const parsed = JSON.parse(cfg)
  for (const k of ['endpoint', 'bucket', 'accessKey', 'secretKey']) {
    if (!parsed[k]) throw new Error(`[error-report] 配置缺少 ${k}`)
  }
  writeFileSync(OUT, `pub const CONFIG_JSON: Option<&str> = Some(${JSON.stringify(cfg)});\n`)
  console.log('[error-report] 已注入内网只写 key 配置')
} else {
  writeFileSync(OUT, 'pub const CONFIG_JSON: Option<&str> = None;\n')
  console.log('[error-report] 未配置上报（GitHub 包）')
}
