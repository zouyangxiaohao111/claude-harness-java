package com.nexusai.infra.util;

/**
 * CliArgParser · 对齐 CC utils/cliArgs.ts.
 *
 * <p>L1 语义: 在 Commander.js 之前,eager 解析单个 CLI flag (--flag value 或 --flag=value)。
 * 主要给 init() 之前的 flags 用 (如 --settings 影响配置加载)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: eagerParseCliFlag(flagName, argv)→String|null + extractArgsAfterDoubleDash(commandOrValue, args)</li>
 *   <li><b>A2 Golden Trace</b>: --flag=value → value;--flag value → next arg;缺失 → null;'--' 后跟随 cmd 提取</li>
 *   <li><b>A3 纯函数</b>: 同 input → 同 output</li>
 *   <li><b>A4 边界</b>: empty argv → null;flag 不存在 → null</li>
 *   <li><b>A5 业务场景</b>: init() 前读 --settings 路径;Commander.js passThrough 修正 '--' 后命令</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS array.indexOf + slice → Java loop + substring;
 * TS destructuring → Java conditional index check;
 * TS process.argv fallback → Java String[] 默认 + null check。
 */
public final class CliArgParser {

    private CliArgParser() {}

    /**
     * Find the value of {@code flagName} in {@code argv}, supporting both
     * {@code --flag=value} and {@code --flag value} syntax.
     *
     * @param flagName full flag including dashes (e.g. "--settings")
     * @param argv     argv array; null-safe (defaults to empty)
     * @return flag value or null
     */
    public static String eagerParseCliFlag(String flagName, String[] argv) {
        if (flagName == null || flagName.isEmpty() || argv == null) return null;
        String eqPrefix = flagName + "=";
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (arg == null) continue;
            if (arg.startsWith(eqPrefix)) {
                return arg.substring(eqPrefix.length());
            }
            if (arg.equals(flagName) && i + 1 < argv.length) {
                return argv[i + 1];
            }
        }
        return null;
    }

    /**
     * Handle Unix {@code --} separator convention. When Commander.js sees
     * {@code cmd --opt value name -- subcmd --flag arg}, the {@code --} leaks
     * as a positional. This function extracts the actual command from {@code args}.
     *
     * @param commandOrValue parsed positional that may be "--"
     * @param args            remaining args
     * @return object with corrected command and args
     */
    public static ParsedArgs extractArgsAfterDoubleDash(String commandOrValue, String[] args) {
        if ("--".equals(commandOrValue) && args != null && args.length > 0) {
            return new ParsedArgs(args[0], copyOfRange(args, 1, args.length));
        }
        return new ParsedArgs(commandOrValue, args == null ? new String[0] : args);
    }

    private static String[] copyOfRange(String[] src, int from, int to) {
        int len = Math.max(0, Math.min(to, src.length) - from);
        String[] dst = new String[len];
        for (int i = 0; i < len; i++) dst[i] = src[i + from];
        return dst;
    }

    public record ParsedArgs(String command, String[] args) {}
}
