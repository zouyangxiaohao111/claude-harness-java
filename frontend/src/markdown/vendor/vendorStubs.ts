/**
 * 移植件的外部依赖桩。
 *
 * 用途：`./sandboxArtifactRefs.ts` 是从 WeKnora 前端移植过来的模块，它在源站
 * 依赖三个内部实现（security.ts / artifactFileIcon.ts / @/api/chat 的
 * downloadArtifact），本仓没有对应能力，这里用最小桩顶上，保证移植件能编译、
 * 能跑单测。
 *
 * 每个桩都只实现「行为等价物」的最小子集，**不是**安全边界，也不做真业务。
 */

/**
 * HTML 转义（源站 security.ts:escapeHTML 的行为等价物，只做转义不做净化）。
 * 来源：WeKnora frontend/src/utils/security.ts 的同名函数语义。
 *
 * ⚠️ 这不是安全边界 —— 真正的 DOMPurify 净化在最终渲染前做，不在这里。
 */
export function escapeHTML(s: string): string {
  if (!s || typeof s !== 'string') return '';
  const map: Record<string, string> = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#x27;',
    '/': '&#x2F;',
    '`': '&#x60;',
    '=': '&#x3D;',
  };
  return s.replace(/[&<>"'`=/]/g, (ch) => map[ch]);
}

/**
 * 产物文件图标（占位）。本仓暂无沙箱产物体系 ⇒ 返回固定图标。
 * 源站实现在 artifactFileIcon.ts（依赖 files.ts + filePreview.ts 共 506 行）。
 *
 * 注意：返回的 SVG **不插值文件名**（源站只插值白名单化的扩展名标签），这里
 * 连扩展名都不取，直接写死 FILE。
 */
export function renderArtifactFileIcon(_fileName: string): string {
  return (
    '<svg class="artifact-file-icon-svg" viewBox="0 0 32 38" fill="none" aria-hidden="true">'
    + '<path class="file-sheet" d="M6 1.5h13L28.5 11v22A3.5 3.5 0 0 1 25 36.5H6A3.5 3.5 0 0 1 2.5 33V5A3.5 3.5 0 0 1 6 1.5Z" />'
    + '<path class="file-fold" d="M19 1.5V8a3 3 0 0 0 3 3h6.5" />'
    + '<path class="file-lines" d="M8 14h9M8 18h14" />'
    + '<rect x="5" y="23" width="24" height="11" rx="3" fill="currentColor" />'
    + '<text x="17" y="30.8" text-anchor="middle">FILE</text></svg>'
  );
}

/**
 * 产物字节下载（占位）。源站为 `@/api/chat` 的 downloadArtifact（axios 传输，
 * 按 会话/消息/下标 取 blob）。
 *
 * 本仓暂无沙箱产物下载接口 ⇒ 恒返回 null：调用方据此保持占位图、不发起任何
 * 网络请求。移植件里原本的惰性 import 因此改为静态引用本桩。
 */
export async function downloadArtifactStub(
  _sessionId: string,
  _messageId: string,
  _index: number,
): Promise<Blob | null> {
  return null;
}
