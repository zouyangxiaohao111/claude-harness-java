package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

/**
 * 渠道权限请求的外发载荷（[P1.4] 四字段 + 脱敏）。
 *
 * <p>对齐 CC 2.1.330 exe（偏移 354,919,099 窗口）：出站参数是
 * {@code {request_id, tool_name, description, input_preview}}（本仓暂不带 mcp_server/mcp_skill 扩展），
 * 其中 description 会做截断、input_preview 走预算分档。
 *
 * <p>⛔ <b>必须脱敏</b>：渠道消息会落到第三方平台（飞书/企微/微信），把用户输入的
 * api_key / bearer token / 长 base64 原样发出去等于二次泄露。
 */
public final class ChannelPermissionPayload {

    /** description 上限（码点）· 对齐 CC 的 3500。 */
    public static final int DESCRIPTION_MAX_CODEPOINTS = 3500;

    /** description 超限时保留的头/尾码点数 · 对齐 CC（头 2000 + 尾 1500）。 */
    public static final int DESCRIPTION_HEAD_CODEPOINTS = 2000;
    public static final int DESCRIPTION_TAIL_CODEPOINTS = 1500;

    /** input_preview 预算（字符）· 对齐 CC 的 15000 分档截断。 */
    public static final int INPUT_PREVIEW_BUDGET = 15_000;

    /** 常见密钥前缀（AWS / OpenAI / GitHub / Slack / Bearer）。 */
    private static final Pattern SECRET_PREFIX = Pattern.compile(
        "(?i)(sk-[a-z0-9_-]{8,}|AKIA[0-9A-Z]{12,}|gh[pousr]_[A-Za-z0-9]{10,}"
            + "|xox[baprs]-[A-Za-z0-9-]{10,}|bearer\\s+[A-Za-z0-9._-]{10,})");

    /** 长串候选（>=32）—— 是否打码由高熵判定决定（见 looksSecret）。 */
    private static final Pattern LONG_RUN = Pattern.compile("[A-Za-z0-9+/=_.-]{32,}");

    /** 无论熵值高低都必须打码的形态：JWT 三段 / URL userinfo / query 键含 token/secret/key/password /
     *  Authorization（含 Basic/Bearer 前缀）头值。 */
    private static final Pattern HARD_SECRET = Pattern.compile(
        "(?i)(eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+"
            + "|://[^/\\s:@]+:[^/\\s:@]+@"
            + "|[?&]([a-z0-9_-]*(token|secret|key|password|passwd|auth)[a-z0-9_-]*)=[^&\\s]+"
            + "|\\b(proxy-)?authorization\\s*[:=]\\s*(bearer|basic|digest)?\\s*[A-Za-z0-9._~+/=-]{8,})");

    /** JSON 里这些键的值一律打码（键名大小写不敏感）。 */
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
        "(?i)\"(api[_-]?key|token|access[_-]?token|secret|password|passwd|authorization|credential)\""
            + "\\s*:\\s*\"[^\"]*\"");

    private ChannelPermissionPayload() {
    }

    /**
     * 构造渠道外发文本（IM 消息体）。
     *
     * @param requestId   短请求 id（回执时用户要带它）
     * @param toolName    工具名
     * @param description 弹窗描述（可 null）
     * @param input       工具入参（可 null）
     * @return 多行纯文本（含「yes/no + 短 id」指引）
     */
    public static String toText(String requestId, String toolName, String description, JsonNode input) {
        // [B2] 正文是**标题句**：先折叠成单行（清洗换行/控制空白），再按 CC 的
        //   「超 3500 码点 ⇒ 头 2000 + 尾 1500」截断。参数细节只走 input_preview。
        String desc = com.nexusai.application.agent.tool.ToolCallSummary.singleLine(description, 0);
        desc = truncateDescription(desc);
        String preview = truncateBudget(maskSecrets(input == null ? "" : input.toString()), INPUT_PREVIEW_BUDGET);
        StringBuilder sb = new StringBuilder();
        sb.append("[权限确认] ").append(toolName == null ? "tool" : toolName).append('\n');
        if (!desc.isBlank()) {
            sb.append(desc).append('\n');
        }
        sb.append("输入预览: ").append(preview).append('\n');
        sb.append("回复 \"yes ").append(requestId).append("\" 允许，\"no ")
            .append(requestId).append("\" 拒绝");
        return sb.toString();
    }

    /** 按码点截断（emoji 等代理对不会被切成半个）。 */
    public static String truncateCodepoints(String s, int maxCodepoints) {
        if (s == null) {
            return "";
        }
        if (s.codePointCount(0, s.length()) <= maxCodepoints) {
            return s;
        }
        int end = s.offsetByCodePoints(0, maxCodepoints);
        return s.substring(0, end) + "…";
    }

    /**
     * description 的 CC 截断口径：≤3500 码点原样；超了取**头 2000 + 尾 1500**（中间省略）。
     *
     * <p>⛔ 这个上限只属于 description；{@code input_preview} 是另一套（15000 总预算），别混用。
     * 省略号本身不计入头/尾配额 ⇒ 结果最多 3501 码点。
     */
    public static String truncateDescription(String s) {
        if (s == null) {
            return "";
        }
        int codepoints = s.codePointCount(0, s.length());
        if (codepoints <= DESCRIPTION_MAX_CODEPOINTS) {
            return s;
        }
        int headEnd = s.offsetByCodePoints(0, DESCRIPTION_HEAD_CODEPOINTS);
        int tailStart = s.offsetByCodePoints(0, codepoints - DESCRIPTION_TAIL_CODEPOINTS);
        return s.substring(0, headEnd) + "…" + s.substring(tailStart);
    }

    /** 预算截断：超预算时保留头尾各一半（尾部常含结论/路径），中间标截断。 */
    public static String truncateBudget(String s, int budget) {
        if (s == null) {
            return "";
        }
        if (s.length() <= budget) {
            return s;
        }
        int half = Math.max(0, (budget - 12) / 2);
        return s.substring(0, half) + "…[截断]…" + s.substring(s.length() - half);
    }

    /** 高熵判定：字符类 >=3，或香农熵 >=3.5 bits/char（不确定时打码）。 */
    static boolean looksSecret(String token) {
        boolean upper = token.chars().anyMatch(Character::isUpperCase);
        boolean lower = token.chars().anyMatch(Character::isLowerCase);
        boolean digit = token.chars().anyMatch(Character::isDigit);
        int classes = (upper ? 1 : 0) + (lower ? 1 : 0) + (digit ? 1 : 0);
        if (classes >= 3) {
            return true;
        }
        java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
        for (int i = 0; i < token.length(); i++) {
            freq.merge((int) token.charAt(i), 1, Integer::sum);
        }
        double entropy = 0;
        for (int c : freq.values()) {
            double pr = (double) c / token.length();
            entropy -= pr * (Math.log(pr) / Math.log(2));
        }
        return entropy >= 3.5;
    }

    /** 路径样长串：保留末两段（可读），但末两段自身像密钥时仍打码。 */
    static String maskPathTail(String token) {
        String[] parts = token.split("/");
        if (parts.length < 2) {
            return looksSecret(token) ? "***" : token;
        }
        int n = parts.length;
        String tail = parts[n - 2] + "/" + parts[n - 1];
        if (looksSecret(parts[n - 1]) || looksSecret(parts[n - 2])) {
            return "***";
        }
        return "…/" + tail;
    }

    /**
     * 脱敏：敏感键值整体打码 + 已知密钥前缀 + 硬规则（JWT/URL 凭据/query token）+ 高熵长串。
     *
     * <p>顺序有意义：先按键名打码（最准确），再按形态兜底（键名不规范的场景）。
     */
    public static String maskSecrets(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String out = SENSITIVE_KEY.matcher(s).replaceAll("\"$1\":\"***\"");
        out = SECRET_PREFIX.matcher(out).replaceAll("***");
        out = HARD_SECRET.matcher(out).replaceAll("***");
        // 长串：只打高熵（宁可多打：字符类>=3 或熵>=3.5）；纯路径保末两段可读
        java.util.regex.Matcher m = LONG_RUN.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String tok = m.group();
            // [P6] 路径一律走「保留末两段」（即使整条路径熵高）—— 否则普通长路径会被整段打掉；
            //   末两段自身像密钥时 maskPathTail 仍会打码。非路径串才按高熵判定。
            String rep = tok.indexOf('/') >= 0
                ? maskPathTail(tok)
                : (looksSecret(tok) ? "***" : tok);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
