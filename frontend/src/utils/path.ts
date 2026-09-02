/**
 * 项目路径工具：绝对路径判定 + 归一化。
 *
 * 背景：后端 ProjectService.register 已校验路径（转绝对 + 目录必须存在），
 * 前端创建项目 / 绑定会话时**必须传绝对路径**（如 D:/code/ai_project/nexusai-backend），
 * 杜绝「抓包流程」这类相对路径/目录名——它们会污染会话 cwd，导致工具（Bash/Glob/Read）全部失败。
 */

/** 判定绝对路径：Windows 盘符（D:/）、UNC（//server/share）、POSIX（/xxx）。null/空/相对 → false */
export function isAbsolutePath(p: string | null | undefined): boolean {
  const s = (p ?? '').trim().replace(/\\/g, '/')
  if (!s) return false
  return /^[a-zA-Z]:\//.test(s) || s.startsWith('//') || s.startsWith('/')
}

/** 归一化项目路径：反斜杠 → 正斜杠，去首尾空白与尾部斜杠；空 → '' */
export function normalizePath(p: string | null | undefined): string {
  return (p ?? '').trim().replace(/\\/g, '/').replace(/\/+$/, '')
}
