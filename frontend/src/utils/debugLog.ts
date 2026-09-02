import { writeTextFile } from '@tauri-apps/plugin-fs'

/**
 * 调试日志写桌面文件（nexusai-debug.log · 联调排障用）。
 * 用绝对路径 + writeTextFile({append:true}) 追加——天然追加不覆盖，无 read+write 竞态丢历史。
 * Promise chain 串行化避免并发写交叠。浏览器/dev 失败静默。
 */
const file = 'D:/code/ai_project/nexusai/src-tauri/nexusai-debug.log'
let chain: Promise<void> = Promise.resolve()

export function debugLog(msg: string): Promise<void> {
  chain = chain.then(async () => {
    try {
      await writeTextFile(file, `[${new Date().toISOString()}] ${msg}\n`, { append: true })
    } catch {
      /* 静默 */
    }
  })
  return chain
}
