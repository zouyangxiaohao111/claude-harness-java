package com.nexusai.wechatlog.key.elevation;

import java.util.List;

/**
 * Windows 命令行参数的拼装/转义。
 *
 * <p>{@code ShellExecuteEx} 的 {@code lpParameters} 是一整条字符串，不是参数数组，
 * 因此路径里的空格与引号必须按 Windows 的规则转义（与 MSVC 运行时一致）。
 * 单独抽出来是为了可单测：这类逻辑写错的表现是「UAC 弹了但子进程立刻退出」，
 * 很难从现象反推。
 */
public final class WindowsCommandLine {

    private WindowsCommandLine() {
    }

    /** 把参数列表拼成 {@code ShellExecuteEx} 的 {@code lpParameters}。 */
    public static String join(List<String> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(quote(args.get(i)));
        }
        return sb.toString();
    }

    /**
     * 单个参数的 Windows 转义。
     *
     * <p>规则（与 MSVC {@code parsing_command_line} 对应）：
     * 无空格/制表符/引号且非空 ⇒ 原样；否则整体加双引号；
     * 连续反斜杠后跟引号时反斜杠翻倍；参数末尾的反斜杠若整体被引号包裹也翻倍。
     */
    public static String quote(String arg) {
        String s = arg == null ? "" : arg;
        boolean needQuote = s.isEmpty()
                || s.indexOf(' ') >= 0
                || s.indexOf('\t') >= 0
                || s.indexOf('"') >= 0;
        if (!needQuote) {
            return s;
        }

        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        int backslashes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                for (int k = 0; k < backslashes * 2 + 1; k++) {
                    sb.append('\\');
                }
                sb.append('"');
                backslashes = 0;
            } else {
                for (int k = 0; k < backslashes; k++) {
                    sb.append('\\');
                }
                sb.append(c);
                backslashes = 0;
            }
        }
        for (int k = 0; k < backslashes * 2; k++) {
            sb.append('\\');
        }
        sb.append('"');
        return sb.toString();
    }
}
