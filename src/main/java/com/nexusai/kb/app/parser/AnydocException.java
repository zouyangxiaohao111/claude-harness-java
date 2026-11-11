package com.nexusai.kb.app.parser;

/**
 * anydoc 原生库调用失败 · 携带 anydoc 的错误码（{@code ERR_*}）与可读原因。
 *
 * <p><b>为什么单独一类而不是直接抛 {@link KbParseException}</b>：调用方（{@link KbAnydocParser}）
 * 需要把错误码与原因<b>写进 metadata</b> 再降级，而不是让整份解析失败；错误码（尤其
 * {@code 7 PDF_NO_MODEL} / {@code 3 ENCRYPTED}）是排查「为什么这份文档没内容」的第一手证据，
 * 用受检异常携带比塞进 message 字符串更不容易在中间层丢失。
 *
 * <p><b>受检异常</b>：anydoc 不可用本来就是<b>预期状态</b>（非 Windows 未分发原生库），
 * 强制调用方处理，避免「忘了兜底」变成运行时炸裂。
 */
class AnydocException extends Exception {

    private static final long serialVersionUID = 1L;

    /** anydoc 的错误码；{@code -1} = 未走到原生层（库不可用 / 参数为空 / JNA 调用异常）。 */
    private final int code;

    AnydocException(int code, String message) {
        super(message);
        this.code = code;
    }

    AnydocException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** anydoc 错误码（{@code -1} = 未进入原生层）。 */
    int code() {
        return code;
    }
}
