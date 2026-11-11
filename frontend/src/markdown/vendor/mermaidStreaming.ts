/**
 * 流式输出期间的 Mermaid 代码块兜底处理（纯字符串，不渲染）。
 *
 * 解决的问题：SSE 流式输出时，```mermaid 代码块可能只到一半——只有开头的 ```mermaid
 * 而没有结尾的 ```。此时交给 markdown 解析器，会把后面所有正文都当成代码块内容，
 * 导致整段文字被吞进代码块（样式错乱、正文消失）。
 *
 * 本模块只负责判断「块是否完整」以及「把未闭合块替换成占位符」，等流结束后再由缓存注入
 * 已渲染好的 SVG。真正的 Mermaid 渲染不在本模块内。
 *
 * 移植自 WeKnora 前端 utils/mermaidStreaming.ts，逻辑与源站逐字一致，仅注释与品牌名不同。
 */

export type CachedMermaidSvgHtml = string | readonly (string | null | undefined)[] | null | undefined;

const MERMAID_FENCE_START = '```mermaid';
const COMPLETE_MERMAID_FENCE_RE = /```mermaid[\s\S]*?```/;

/** 把末尾那个未闭合的 mermaid 代码块整段替换成占位符（其后没有内容可保留）。 */
export const replaceIncompleteMermaidWithPlaceholder = (
  content: string,
  placeholder: string,
): string => {
  if (!content) return '';

  const start = content.lastIndexOf(MERMAID_FENCE_START);
  if (start < 0) return content;

  const tail = content.slice(start + MERMAID_FENCE_START.length);
  if (tail.includes('```')) return content;

  return content.slice(0, start) + placeholder;
};

/** Keep every mermaid fence as a stable placeholder; render SVG via cache injection. */
export const maskMermaidBlocksForStreaming = (content: string, placeholder: string): string => {
  if (!content) return '';
  const masked = content.replace(/```mermaid[\s\S]*?```/g, placeholder);
  return replaceIncompleteMermaidWithPlaceholder(masked, placeholder);
};

export const hasCompleteMermaidBlock = (content: string): boolean => {
  return COMPLETE_MERMAID_FENCE_RE.test(content);
};

export const prepareStreamingMermaidMarkdown = (
  content: string,
  placeholder: string,
): string => {
  if (!content) return '';

  if (hasCompleteMermaidBlock(content)) {
    return maskMermaidBlocksForStreaming(content, placeholder);
  }

  return replaceIncompleteMermaidWithPlaceholder(content, placeholder);
};

export const extractMermaidCodes = (content: string): string[] => {
  if (!content) return [];
  const codes: string[] = [];
  const re = /```mermaid([\s\S]*?)```/g;
  let match: RegExpExecArray | null;
  while ((match = re.exec(content)) !== null) {
    const code = match[1].trim();
    if (code) codes.push(code);
  }
  return codes;
};

export const extractFirstMermaidCode = (content: string): string | null => {
  return extractMermaidCodes(content)[0] || null;
};

export function normalizeCachedMermaidSvgs(cached: CachedMermaidSvgHtml): string[] {
  if (!cached) return [];
  if (typeof cached === 'string') return cached ? [cached] : [];
  return cached.map((svg) => svg || '');
}

// Require the loading modifier so a rendered/cached mermaid block cannot be
// paired with a later skeleton (the previous `chat-mermaid-block[^"]*` pattern
// swallowed the first diagram and everything after it).
const STREAMING_MERMAID_LOADING_RE =
  /<div class="chat-mermaid-block chat-mermaid-block--loading"[^>]*>[\s\S]*?<div class="streaming-mermaid-loading"[^>]*>[\s\S]*?<\/div>\s*<\/div>/g;

const MERMAID_UNRENDERED_CANVAS_RE =
  /<pre class="chat-mermaid-block__canvas mermaid"[^>]*data-mermaid="false"[^>]*>[\s\S]*?<\/pre>/g;

function replaceUnrenderedCanvas(
  match: string,
  svg: string,
): string {
  const openEnd = match.indexOf('>');
  if (openEnd < 0) return match;
  const openTag = match.slice(0, openEnd + 1).replace(/data-mermaid="false"/, 'data-mermaid="cached"');
  return `${openTag}${svg}</pre>`;
}

/** Inject trusted Mermaid SVG after DOMPurify, 1:1 with complete mermaid fences. */
export const injectCachedMermaidSvg = (
  html: string,
  cachedSvgHtml: CachedMermaidSvgHtml,
  buildBlock: (innerHtml: string, preAttrs?: string) => string,
): string => {
  if (!html) return html;
  const svgs = normalizeCachedMermaidSvgs(cachedSvgHtml);
  if (!svgs.some(Boolean)) return html;

  const index = { i: 0 };
  const withLoading = html.replace(STREAMING_MERMAID_LOADING_RE, (match) => {
    const svg = svgs[index.i++];
    return svg ? buildBlock(svg, 'data-mermaid="cached"') : match;
  });
  if (index.i >= svgs.length) return withLoading;
  return withLoading.replace(MERMAID_UNRENDERED_CANVAS_RE, (match) => {
    const svg = svgs[index.i++];
    return svg ? replaceUnrenderedCanvas(match, svg) : match;
  });
};
