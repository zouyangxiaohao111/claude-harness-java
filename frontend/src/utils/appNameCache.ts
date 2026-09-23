/**
 * 自有根目录名（appName）的**极薄缓存** —— 只服务「后端还没起来、拿不到 GET /settings」的启动期界面。
 *
 * <p><b>为什么需要它</b>：`LaunchGate` 的错误卡出现在<b>后端起不来</b>的时刻，那一刻
 * `GET /api/v1/settings` 本来就连不上（这个页面的职责就是显示后端失败）⇒ 它<b>结构上拿不到</b>
 * appName。可提示里的日志路径要写对（`~/.{appName}/logs/backend.log`），所以把上次成功加载到的
 * appName 落一份，专供那一刻拼路径。
 *
 * <p><b>两层</b>：内存（同一次运行内，App 加载完 settings 后立刻可用）+ localStorage（跨进程重启）。
 * 首装即失败时<b>两层都空</b> ⇒ 回落 {@link DEFAULT_APP_NAME} 拼出与今日逐字相同的文案 ——
 * 这是已登记的已知边界（无法在「第一运行就失败」时知道 appName），不是缺陷。
 *
 * <p>⛔ 本模块<b>不是</b> appName 的真源：真源 = 后端 GET /settings 的只读 `appName`
 * （`NexusaiPaths.getAppName()` ← `spring.application.name`）。这里只保存「拿不到时的最近值」，
 * 故任何读取都<b>只回落到默认值</b>，绝不猜、不编造。
 */

/** localStorage 键（导出供测试播种跨进程场景）。 */
export const APP_NAME_STORAGE_KEY = 'nexusai.appName'

/** 回落目录名（与后端 NexusaiPaths.DEFAULT_APP_NAME 同值）。 */
export const DEFAULT_APP_NAME = 'nexusai'

/** 同进程内存层：App 每次成功加载 settings 后写入。 */
let memory: string | null = null

/**
 * 记下本次成功加载到的 appName（App 每次 GET /settings 成功后调用）。
 * 空白/非字符串值<b>不覆盖</b>既有缓存（宁可留旧值，也不写进空串让路径变成 `~/.`）。
 */
export function rememberAppName(name?: string | null): void {
  const trimmed = typeof name === 'string' ? name.trim() : ''
  if (!trimmed) return
  memory = trimmed
  try {
    localStorage.setItem(APP_NAME_STORAGE_KEY, trimmed)
  } catch {
    // 隐私模式 / 无 localStorage ⇒ 只留内存（本次运行内够用）
  }
}

/** 当前已知 appName：内存 → localStorage → 默认值（只回落，不猜）。 */
function cachedAppName(): string {
  if (memory) return memory
  try {
    const stored = localStorage.getItem(APP_NAME_STORAGE_KEY)
    if (stored && stored.trim()) return stored.trim()
  } catch {
    // 同上：读不到就回落默认
  }
  return DEFAULT_APP_NAME
}

/**
 * 自有配置根目录名（含前导点，如 `.nexusai` / `.nexusai-scene`）。
 *
 * <p>⭐ 不变量：<b>无缓存时返回 `.nexusai`</b> ⇒ LaunchGate 的提示文案与落地前逐字相同。
 */
export function cachedConfigDirLabel(): string {
  return `.${cachedAppName()}`
}
