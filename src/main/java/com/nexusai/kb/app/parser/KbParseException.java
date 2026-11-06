package com.nexusai.kb.app.parser;

/**
 * 解析失败 · 解析层不写库、不返回半成品：无法给出可信文本时抛本异常，由上层编排决定入库状态。
 *
 * <p><b>WHY 独立异常</b>：解析是纯函数，唯一的失败出口必须能被上层区分于「解析成功但内容为空」。
 * 若用 {@code IllegalArgumentException} 之类的通用异常，上层无法把「文件损坏」与「调用方传错参数」
 * 分开处理（前者应落 {@code parse_status=failed}，后者是编程错误应 500）。
 */
public class KbParseException extends RuntimeException {

    public KbParseException(String message) {
        super(message);
    }

    public KbParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
