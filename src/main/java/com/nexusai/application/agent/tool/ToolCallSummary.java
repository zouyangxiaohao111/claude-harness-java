package com.nexusai.application.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 权限推送正文的文本整形工具（单行化 + 取本次调用参数）。
 *
 * <p><b>WHY 单独一个出口</b>：工具注册时的 {@code description()} / {@code prompt()} 是给模型看的
 * 使用说明书（「Read the contents of a file. Path is resolved relative to the workspace root…」），
 * 整段发到手机既长又没信息量。CC 的渠道推送取的是
 * {@code tool.description(input, ctx)} —— 每个工具一句**固定标题**（Read →
 * "Read a file from the local filesystem."），参数细节只留在 {@code input_preview}。
 * 各工具在自己的 {@code description(input)} 里给出标题句；本类只负责单行化与取参，
 * ⛔ 不做「工具名 + 路径」这类自创摘要。
 */
public final class ToolCallSummary {

    private ToolCallSummary() {
    }

    /**
     * 单行化：换行/制表/连续空白折叠成一个空格，trim；超长截断加省略号。
     *
     * @param s   原文（可 null）
     * @param max 最大字符数（{@code <=0} = 不截断）
     */
    public static String singleLine(String s, int max) {
        if (s == null) {
            return "";
        }
        String line = s.replaceAll("\\s+", " ").trim();
        if (max > 0 && line.length() > max) {
            line = line.substring(0, max) + "…";
        }
        return line;
    }

    /** 按 {@code keys} 优先级取首个非空文本参数；都没有 ⇒ null。 */
    public static String firstText(JsonNode input, String... keys) {
        if (input == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String value = input.path(key).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
