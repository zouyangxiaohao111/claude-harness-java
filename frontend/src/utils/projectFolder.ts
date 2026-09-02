import { open } from '@tauri-apps/plugin-dialog'

/**
 * 本地项目文件夹选择（用户需求：像本地打开文件一样选项目目录）。
 *
 * <p>Tauri 桌面端：调用系统原生文件夹选择框（tauri-plugin-dialog open directory）。
 * 浏览器 dev 模式：Tauri API 不可用 → 用 <input type=file webkitdirectory> 选文件夹
 * （webkit 支持读取目录名），拿目录名返回（无完整路径，标注 browser 模式）。
 *
 * @returns 选中文件夹的绝对路径；取消/失败返回 null
 */
export async function selectProjectFolder(): Promise<{ path: string; isTauri: boolean } | null> {
  // Tauri 环境（__TAURI_INTERNALS__ 存在）
  if (typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window) {
    try {
      const selected = await open({ directory: true, multiple: false, title: '选择项目文件夹' })
      if (typeof selected === 'string' && selected) {
        return { path: selected, isTauri: true }
      }
      return null // 用户取消
    } catch (e) {
      // dialog 插件未注册/不可用 → 降级浏览器
      console.warn('[projectFolder] Tauri dialog 不可用，降级浏览器模式:', e)
    }
  }
  // 浏览器 fallback：webkitdirectory 选目录
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.setAttribute('webkitdirectory', '')
    input.setAttribute('directory', '')
    input.onchange = () => {
      const file = input.files?.[0]
      if (file && file.webkitRelativePath) {
        // 目录名 = 相对路径第一段
        const dir = file.webkitRelativePath.split('/')[0]
        resolve({ path: dir, isTauri: false })
      } else {
        resolve(null)
      }
    }
    input.oncancel = () => resolve(null)
    input.click()
  })
}
