package com.nexusai.kb.app.chunk;

import java.util.regex.Matcher;

/**
 * Markdown 标题栈 → 面包屑 · 对齐 WeKnora {@code chunker.HeadingHierarchy}
 * （{@code heading_hierarchy.go:1-111}）。
 *
 * <p><b>为什么不用 header_tracker 那套 hook</b>（源侧 {@code heading_hierarchy.go:5-9} 原文）：
 * 表格有显式结束标记（空行 / 非表格行），而<b>标题没有结束标记</b> ——
 * 它只被「下一同级或更浅的标题」终结。hook 抽象表达不了这个语义，所以用显式的<b>层级栈</b>。
 *
 * <p><b>入栈语义</b>：压入 level-N 标题时，level &gt;= N 的槽位全部清空
 * （上一个同级及其所有后代都出作用域了）。
 *
 * <p><b>两种输出</b>：
 * <ul>
 *   <li>{@link #breadcrumb()} → {@code "Chapter 1 > Section 2"}（给人看）</li>
 *   <li>{@link #breadcrumbWithHashes()} → {@code "# Chapter 1\n## Section 2"}
 *       —— 这是落进 {@code kb_chunk.context_header} 的形态（源侧 {@code :83-100}）</li>
 * </ul>
 *
 * <p>⚠️ 本类<b>不</b>识别围栏代码块内的伪标题 —— 调用方必须自己跳过代码块
 * （源侧 {@code :31-33} 明文；标题分块器与画像都各自持有 fence 状态）。
 */
final class KbHeadingHierarchy {

    /** stack[i] 存 level i+1 的标题文本；未激活的槽位为 null。 */
    private final String[] stack = new String[6];

    /** 当前最深激活层级（0 = 没有激活标题）。 */
    private int depth;

    /**
     * 观察一行文本；若是 Markdown 标题则更新栈。
     *
     * <p>源侧返回 {@code (level, headingText)}；Java 端只回 {@code level}
     * （{@code 0} = 不是标题）—— 标题文本已进栈，调用方无需再拿一份副本。
     *
     * @param line 一行文本
     * @return 标题层级 1..6；不是标题 0
     */
    int observe(String line) {
        Matcher m = KbDocProfile.MARKDOWN_HEADING.matcher(line == null ? "" : line);
        if (!m.find()) {
            return 0;
        }
        int level = m.group(1).length();
        if (level < 1 || level > 6) {
            return 0;
        }
        String heading = m.group(2) == null ? "" : m.group(2).strip();
        // 替换当前层级并清空更深层 —— 上一标题的同级/后代都出作用域了
        stack[level - 1] = heading;
        for (int i = level; i < 6; i++) {
            stack[i] = null;
        }
        if (level > depth) {
            depth = level;
        } else {
            // 可能变浅：重算 depth
            depth = 0;
            for (int i = 0; i < 6; i++) {
                if (stack[i] != null) {
                    depth = i + 1;
                }
            }
        }
        return level;
    }

    /**
     * 面包屑（用 {@code " > "} 连接）· 对齐源侧 {@code Breadcrumb}（{@code :67-78}）。
     *
     * @return 面包屑；无激活标题时空串
     */
    String breadcrumb() {
        if (depth == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (stack[i] == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(stack[i]);
        }
        return sb.toString();
    }

    /**
     * 带井号前缀的面包屑 · 对齐源侧 {@code BreadcrumbWithHashes}（{@code :83-100}）。
     *
     * <p>形如：
     * <pre>
     *   # Chapter 1
     *   ## Section 2
     *   ### Subsection a
     * </pre>
     * 这是 {@code ContextHeader} 的正式形态 —— 直接拼进 embedding 文本时
     * 不会被误读成「正文里的标题」。
     *
     * @return 面包屑；无激活标题时空串
     */
    String breadcrumbWithHashes() {
        if (depth == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            if (stack[i] == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("#".repeat(i + 1)).append(' ').append(stack[i]);
        }
        return sb.toString();
    }

    /** 当前最深激活层级（源侧 {@code Depth}，{@code :103}）。 */
    int depth() {
        return depth;
    }

    /**
     * 深拷贝（源侧直接按值复制 struct {@code sectionStart := *hierarchy}，{@code heading_splitter.go:73}）。
     *
     * <p><b>为什么需要拷贝</b>：切一个超长章节时，要先把「章节起点时的栈」冻结下来，
     * 再拿它去推每一段子块的子标题面包屑 —— 不拷贝就会在推导过程中污染主栈。
     *
     * @return 独立副本
     */
    KbHeadingHierarchy copy() {
        KbHeadingHierarchy c = new KbHeadingHierarchy();
        System.arraycopy(stack, 0, c.stack, 0, stack.length);
        c.depth = depth;
        return c;
    }
}
