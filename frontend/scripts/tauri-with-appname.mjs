#!/usr/bin/env node
/**
 * 场景版 Tauri CLI 包装入口（仅 feat/scene-fusion 分支使用）。
 *
 * 背景：front/src-tauri/src/backend.rs 的 app_name() 用 option_env!("NEXUSAI_APP_NAME")，
 * 变量未设置时回落通用版 `nexusai` ⇒ 数据根写回 ~/.nexusai。pack.sh / pack.bat 已导出
 * NEXUSAI_APP_NAME=nexusai-scene，但绕过 pack 脚本直接 `npm run tauri build` 时该变量缺失，
 * 会把场景版误打成通用版数据根。
 *
 * 本脚本只在变量缺失/纯空白时注入场景版默认值，随后把全部参数原样透传给 @tauri-apps/cli。
 * 通用版（master）不引用本文件，Rust 侧的默认值仍是 `nexusai`，语义不变。
 *
 * 已知残留：绕开 npm script 直接 `npx tauri build` 不会经过本包装。
 */
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';

const DEFAULT_APP_NAME = 'nexusai-scene';

const current = process.env.NEXUSAI_APP_NAME;
if (current === undefined || current.trim() === '') {
  process.env.NEXUSAI_APP_NAME = DEFAULT_APP_NAME;
  console.error(`[tauri-with-appname] NEXUSAI_APP_NAME 未设置，注入 ${DEFAULT_APP_NAME}`);
}

const args = process.argv.slice(2);

// 跨平台解析 CLI 入口：直接走 node + tauri.js，避免 Windows 下 spawn .bin/tauri(.cmd)
// 需要 shell 的问题（shell 会二次解析含引号的参数）。
function resolveTauriCli() {
  try {
    const require = createRequire(import.meta.url);
    const cliMain = require.resolve('@tauri-apps/cli');
    const entry = path.join(path.dirname(cliMain), 'tauri.js');
    if (fs.existsSync(entry)) return entry;
  } catch {
    // 落到下面的显式失败
  }
  return null;
}

const cliEntry = resolveTauriCli();
if (!cliEntry) {
  console.error('[tauri-with-appname] 未找到 @tauri-apps/cli，请先在 front 目录执行 npm install');
  process.exit(1);
}

const result = spawnSync(process.execPath, [cliEntry, ...args], {
  stdio: 'inherit',
  env: process.env,
});

if (result.error) {
  console.error(`[tauri-with-appname] 启动 tauri 失败：${result.error.message}`);
  process.exit(1);
}
if (typeof result.status === 'number') {
  process.exit(result.status);
}
console.error(`[tauri-with-appname] tauri 被信号终止：${result.signal ?? 'unknown'}`);
process.exit(1);
