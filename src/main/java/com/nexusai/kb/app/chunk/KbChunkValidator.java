package com.nexusai.kb.app.chunk;

import java.util.List;

/**
 * 分块结果校验 · 对齐 WeKnora {@code internal/infrastructure/chunker/validator.go:19-75}。
 *
 * <p><b>⭐ 这是「三级回退」（heading → heuristic → legacy）的唯一开关</b>
 * （源侧注释：{@code validator.go decides whether it is good enough to ship or whether
 * the strategy chain should fall through to the next tier}）。
 * 目标侧目前只移植了 legacy 这一级，所以本类暂时不驱动回退；
 * 但判据必须先落地 —— 等 heading / heuristic 补齐时直接接上，
 * 否则会出现「三级链只有一级」的静默降级。
 *
 * <p><b>设计取向（源侧明确）</b>：<b>故意宽松</b>。只拦「明显坏了」的输出，
 * 对合理的形态差异放行，避免策略在三级之间来回震荡。
 *
 * <p><b>五条判据（逐条逐字对齐）</b>：
 * <table>
 *   <tr><th>#</th><th>条件</th><th>理由文案</th><th>为什么</th></tr>
 *   <tr><td>1</td><td>{@code chunks.isEmpty()}</td><td>{@code no chunks produced}</td>
 *       <td>一个块都没有 = 分块器根本没工作</td></tr>
 *   <tr><td>2</td><td>{@code size==1 && totalChars > 2*chunkSize}</td>
 *       <td>{@code single chunk for large document}</td>
 *       <td>大文档只出 1 块 ⇒ 该策略没真正切分</td></tr>
 *   <tr><td>3</td><td>非末块中 {@code runeLen < 50} 的个数 {@code > size/4} 且 {@code > 2}</td>
 *       <td>{@code too many tiny chunks}</td>
 *       <td><b>末块允许很小</b> —— 尾部残余是正常现象</td></tr>
 *   <tr><td>4</td><td>{@code maxLen < chunkSize/4 && totalChars > chunkSize}</td>
 *       <td>{@code all chunks far below target size}</td>
 *       <td>没有任何块达到目标的 25% ⇒ 切得太碎，不可用</td></tr>
 *   <tr><td>5</td><td>{@code maxLen > 2*chunkSize && chunkSize > 0}</td>
 *       <td>{@code chunk exceeds 2x target size}</td>
 *       <td>超出两倍预算 ⇒ 分块器无视了大小限制</td></tr>
 * </table>
 *
 * <p><b>⚠️ 长度口径</b>：源侧用 {@code len([]rune(content))} ⇒ <b>rune 数</b>，
 * Java 对应 {@link String#codePointCount(int, int)}。⛔ 不能用 {@code String#length()}
 * （UTF-16 code unit，遇到 emoji 会算多）。
 */
public final class KbChunkValidator {

    /** 小于该长度的非末块视为「碎片」。 */
    static final int TINY_CHUNK_THRESHOLD = 50;

    private KbChunkValidator() {
    }

    /**
     * 校验结论。
     *
     * @param ok     是否可用（true = 该级输出可以出货）
     * @param reason 不可用原因（{@code ok=true} 时为空串）
     */
    public record ValidationResult(boolean ok, String reason) {
        /** 通过。 */
        public static final ValidationResult PASS = new ValidationResult(true, "");
    }

    /**
     * 校验。
     *
     * @param chunks     待校验的分块
     * @param totalChars 原文字符数（rune）
     * @param chunkSize  目标块大小
     * @return 结论
     */
    public static ValidationResult validate(List<KbChunk> chunks, int totalChars, int chunkSize) {
        if (chunks == null || chunks.isEmpty()) {
            return new ValidationResult(false, "no chunks produced");
        }
        int size = chunks.size();

        // 判据 2：大文档只产出一块
        if (size == 1 && totalChars > 2 * chunkSize) {
            return new ValidationResult(false, "single chunk for large document");
        }

        int maxLen = 0;
        for (KbChunk chunk : chunks) {
            int len = runeLength(chunk.content());
            if (len > maxLen) {
                maxLen = len;
            }
        }

        // 判据 3：非末块的碎片过多（末块允许很小）
        int tinyCount = 0;
        for (int i = 0; i < size - 1; i++) {
            if (runeLength(chunks.get(i).content()) < TINY_CHUNK_THRESHOLD) {
                tinyCount++;
            }
        }
        if (tinyCount > size / 4 && tinyCount > 2) {
            return new ValidationResult(false, "too many tiny chunks");
        }

        // 判据 4：所有块都远低于目标
        if (maxLen < chunkSize / 4 && totalChars > chunkSize) {
            return new ValidationResult(false, "all chunks far below target size");
        }

        // 判据 5：超过两倍预算
        if (maxLen > 2 * chunkSize && chunkSize > 0) {
            return new ValidationResult(false, "chunk exceeds 2x target size");
        }

        return ValidationResult.PASS;
    }

    /**
     * rune 数（对齐 Go {@code len([]rune(s))}）。
     *
     * @param s 字符串（null 视为空）
     * @return code point 数
     */
    static int runeLength(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        return s.codePointCount(0, s.length());
    }
}
