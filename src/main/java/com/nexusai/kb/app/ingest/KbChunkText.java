package com.nexusai.kb.app.ingest;

import java.util.regex.Pattern;

/**
 * 分块「是否有可用文字」的判定 · 对齐源侧
 * {@code knowledge_post_process.go:739 chunkHasExtractableText}。
 *
 * <p><b>⭐⭐ 为什么不能直接判 {@code content.isBlank()}</b>：
 * 扫描件解析出来的块只有图片 Markdown：{@code ![image](http://.../p1.png)}。
 * 这一行有几十个非空白字符 ⇒ {@code isBlank()=false}，
 * 于是问题生成 / 图谱抽取 / Wiki 都会拿它去调模型 —— 模型看到的"内容"其实是一串 URL，
 * 要么凭空编造，要么把 few-shot 示例原样复述回来。
 * ⇒ 必须先<b>剔除图片语法</b>再看剩下多少真文字。
 *
 * <p>同时剔除纯链接语法（{@code [text](url)} 里的 URL 部分保留 text，
 * 因为链接文字往往就是正文）。
 *
 * <p><b>⭐ 为什么 public</b>：{@code graph} 包（图谱抽取）与 {@code ingest} 包
 * （后处理）都要用同一条判据。复制一份的实现迟早会分叉 ——
 * 那会导致「问句生成跳过了图片块、图谱抽取却没跳过」，而两边都不报错。
 */
public final class KbChunkText {

    /** 图片语法 {@code ![alt](url)} —— 整段剔除。 */
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");

    /** 协议 URL —— 图片语法已剔除，这里兜底裸链接。 */
    private static final Pattern BARE_URL = Pattern.compile("https?://\\S+");

    /** 判定阈值：剔除后还剩多少个非空白 code point 才算「有文字」。 */
    private static final int MIN_CHARS = 10;

    private KbChunkText() {
    }

    /**
     * 剔除图片与裸链接后剩余的可见长度（code point 计）。
     *
     * @param content 分块正文（可 null）
     * @return 剩余非空白字符数
     */
    public static int visibleLength(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        String stripped = strip(content);
        int count = 0;
        for (int i = 0; i < stripped.length(); ) {
            int cp = stripped.codePointAt(i);
            if (!Character.isWhitespace(cp)) {
                count++;
            }
            i += Character.charCount(cp);
        }
        return count;
    }

    /**
     * 剔除图片语法与裸链接。
     *
     * @param content 原文
     * @return 剔除后的文本
     */
    public static String strip(String content) {
        if (content == null) {
            return "";
        }
        String s = IMAGE.matcher(content).replaceAll(" ");
        return BARE_URL.matcher(s).replaceAll(" ");
    }

    /** 是否包含可抽取的文字（供问题生成 / 图谱抽取筛块用）。 */
    public static boolean hasExtractableText(String content) {
        return visibleLength(content) >= MIN_CHARS;
    }
}
