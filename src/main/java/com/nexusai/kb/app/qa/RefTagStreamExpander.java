package com.nexusai.kb.app.qa;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式「引用句柄展开器」· 对齐 WeKnora {@code modelcontext/citations.go} 的 stream expander。
 *
 * <h2>为什么需要它</h2>
 * 源站提示词让模型输出请求内句柄 {@code <ref id="chunk://N"/>}，由系统在生成后替换成真实来源。
 * 逐块推流时标签会被切成两半（{@code "<ref id=\"chu"} 先到），前端会先渲染成裸文本 ——
 * 源站用一个有状态的 expander「扣住不完整的标签，直到闭合再发」。
 *
 * <h2>本仓的落点</h2>
 * 本仓的正文展示用 {@code [N]}（与 citations 的 {@code ref_index} 同号），所以这里把完整标签
 * 直接换成 {@code [N]} 再推；未闭合的尾巴留在缓冲里等下一个 delta。
 */
final class RefTagStreamExpander {

    private static final Pattern REF_TAG =
        Pattern.compile("<ref\\s+id=\"chunk://(\\d+)\"\\s*/?>");

    private final StringBuilder buf = new StringBuilder();

    /** 送入一块 delta，返回可以立即推给前端的文本（可能为空 = 全被扣住）。 */
    String accept(String delta) {
        if (delta == null || delta.isEmpty()) {
            return "";
        }
        buf.append(delta);
        return drain(false);
    }

    /** 生成结束：把缓冲里剩下的都吐出来（半截标签按原文输出，不吞字）。 */
    String flush() {
        return drain(true);
    }

    private String drain(boolean all) {
        if (buf.length() == 0) {
            return "";
        }
        int keep = 0;
        if (!all) {
            int lt = buf.lastIndexOf("<");
            // 尾部有 '<' 但还没等到 '>' ⇒ 可能是被切断的标签，先扣住
            if (lt >= 0 && buf.indexOf(">", lt) < 0) {
                keep = buf.length() - lt;
            }
        }
        int end = buf.length() - keep;
        if (end <= 0) {
            return "";
        }
        String head = buf.substring(0, end);
        buf.delete(0, end);
        Matcher m = REF_TAG.matcher(head);
        return m.replaceAll(mr -> "[" + mr.group(1) + "]");
    }
}
