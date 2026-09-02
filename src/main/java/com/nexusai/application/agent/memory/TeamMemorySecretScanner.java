package com.nexusai.application.agent.memory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Team Memory 客户端 secret 扫描器 · 对齐 CC {@code Open-ClaudeCode/src/services/teamMemorySync/secretScanner.ts}.
 *
 * <p>CC 真源（2026-08-06 grep -n 自验）：{@code SECRET_RULES} secretScanner.ts:48-224（36 条规则，
 * {@code grep -c 'id:'} = 36）；{@code scanForSecrets} :277-295（按 ruleId 去重，不返回命中文本 ——
 * 永不记录/展示 secret 值）；{@code ruleIdToLabel} :243-268（特殊大小写 map + capitalize）；
 * {@code getSecretLabel} :301-303；{@code redactSecrets} :312-324（仅替换捕获组为 [REDACTED]，边界字符存活）。
 *
 * <p>PSR M22174：扫描内容以防凭据离开用户机器。仅收录 gitleaks 高置信度、前缀独特的子集
 * （带明显前缀的规则；泛化 keyword-context 规则剔除）。
 *
 * <p><b>大小写语义</b>（本任务对齐点）：CC 规则默认大小写敏感（无 flags），仅 slack-app-token /
 * private-key 两条带 {@code i}。旧 Java 实现全部 CASE_INSENSITIVE 是偏差。已逐条搬运 CC source + flags，
 * 不简化（规则三）。
 *
 * <p><b>\s Unicode 空白</b>（IMP-MV2-01 · B3 △-2）：CC JS 正则 {@code \s} = ECMAScript WhiteSpace ∪
 * LineTerminator（含 U+FEFF、不含 U+0085 NEL）。Java {@code (?U:\s)}（UNICODE_CHARACTER_CLASS）
 * 为 Unicode White_Space 属性（含 NEL、不含 FEFF）——两端集合不同，FEFF 边界漏检（上传泄露方向）、
 * NEL 边界误检（B3 EV-15/16 双端实测矩阵）→ 规则中 {@code \s} 出现的字符类（BOUNDARY +
 * azure-ad-client-secret 两处）改为显式 ECMAScript 集合（{@link #ECMA_WS}）；{@code \w}/{@code \d}
 * 保持 ASCII（与 JS 一致，不加全局 UNICODE_CHARACTER_CLASS，防 \w/\d 过度放宽）。
 */
public final class TeamMemorySecretScanner {

    /** 一条命中规则. */
    public record SecretMatch(String ruleId, String label) {}

    /** 规则定义（CC SecretRule：id/source/flags）。 */
    private record SecretRule(String id, String source, String flags) {}

    /**
     * Anthropic API key 前缀运行时拼接（CC :46）· 避免外部 bundle 中出现字面字节序列（excluded-strings check）。
     * join 不被 minifier 常量折叠。
     */
    private static final String ANT_KEY_PFX = String.join("-", "sk", "ant", "api");

    private TeamMemorySecretScanner() {}

    /**
     * ECMAScript WhiteSpace ∪ LineTerminator（JS {@code \s}）· 显式字符集合（ES2022 §7.2/§7.3）：
     * TAB/LF/VT/FF/CR（U+0009-U+000D）、SP（U+0020）、NBSP（U+00A0）、BOM（U+FEFF）、OGHAM（U+1680）、
     * U+2000-U+200A、LS/PS（U+2028/U+2029）、NNBSP（U+202F）、MMSP（U+205F）、IDEOGRAPHIC（U+3000）。
     * <p>与 Java {@code (?U:\s)}（Unicode White_Space 属性）的集合差异：ECMAScript 含 U+FEFF、
     * 不含 U+0085 NEL，Java Unicode 属性恰好相反（B3 △-2 E4 矩阵 EV-15/16）→ 字符类显式写死以匹配
     * JS {@code \s}（IMP-MV2-01）。写成 Java 字符串时转义为 {@code \\uXXXX}（正则引擎解释）。
     */
    private static final String ECMA_WS =
        "\\u0009-\\u000D\\u0020\\u00A0\\uFEFF\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";

    /**
     * 规则尾边界（gitleaks 模式中的 trailing boundary）· CC source 中重复出现的
     * {@code (?:[\x60'"\s;]|\\[nr]|$)}。反斜杠 n/r（换行转义）也作边界。
     * <p>{@code \s} 用显式 ECMAScript 集合（{@link #ECMA_WS}，含 U+FEFF 不含 U+0085 NEL）对齐 CC JS
     * \s；{@code $} 同样按 JS 语义显式化：JS {@code $}（无 m flag）仅匹配输入末尾，不匹配最终
     * {@code \n}/{@code \r}/U+2028/U+2029 之前（ECMA-262 §22.2.2.2 Multiline=false 仅 e=InputLength，
     * 双引擎实测见 TeamMemorySyncTest）；Java {@code $} 认 U+0085（NEL 非 JS LineTerminator）→ NEL
     * 尾误检（中间态实测仅换字符类仍命中）→ 零宽断言 {@code (?=\z|[\n\r\u2028\u2029]\z)}：比 JS
     * {@code $} 宽但可观察等价（终止符分支位置被字符类分支先行命中，77/77 矩阵；更忠实移植
     * {@code (?=\z)} 亦等价）。{@code \b}/{@code \w} 保持 ASCII（与 JS 一致，B3 △-2，IMP-MV2-01）。
     */
    private static final String BOUNDARY =
        "(?:(?:[`'\"" + ECMA_WS + ";])|\\\\[nr]|(?=\\z|[\\n\\r\\u2028\\u2029]\\z))";

    /**
     * 精选规则 · CC {@code SECRET_RULES}（secretScanner.ts:48-224）逐条搬运（gitleaks 高置信度子集）。
     * 按开发者团队内容中出现的可能性粗略排序。
     */
    private static final List<SecretRule> SECRET_RULES = List.of(
        // — Cloud providers —
        new SecretRule("aws-access-token",
            "\\b((?:A3T[A-Z0-9]|AKIA|ASIA|ABIA|ACCA)[A-Z2-7]{16})\\b", ""),
        new SecretRule("gcp-api-key",
            "\\b(AIza[\\w-]{35})" + BOUNDARY, ""),
        new SecretRule("azure-ad-client-secret",
            "(?:^|(?:[\\\\'\"`" + ECMA_WS + ">=:(,)]))"
                + "([a-zA-Z0-9_~.]{3}\\dQ~[a-zA-Z0-9_~.-]{31,34})"
                + "(?:(?=\\z|[\\n\\r\\u2028\\u2029]\\z)|(?:[\\\\'\"`" + ECMA_WS + "<),]))", ""),
        new SecretRule("digitalocean-pat",
            "\\b(dop_v1_[a-f0-9]{64})" + BOUNDARY, ""),
        new SecretRule("digitalocean-access-token",
            "\\b(doo_v1_[a-f0-9]{64})" + BOUNDARY, ""),

        // — AI APIs —
        new SecretRule("anthropic-api-key",
            "\\b(" + ANT_KEY_PFX + "03-[a-zA-Z0-9_\\-]{93}AA)" + BOUNDARY, ""),
        new SecretRule("anthropic-admin-api-key",
            "\\b(sk-ant-admin01-[a-zA-Z0-9_\\-]{93}AA)" + BOUNDARY, ""),
        new SecretRule("openai-api-key",
            "\\b(sk-(?:proj|svcacct|admin)-(?:[A-Za-z0-9_-]{74}|[A-Za-z0-9_-]{58})T3BlbkFJ"
                + "(?:[A-Za-z0-9_-]{74}|[A-Za-z0-9_-]{58})\\b|sk-[a-zA-Z0-9]{20}T3BlbkFJ[a-zA-Z0-9]{20})"
                + BOUNDARY, ""),
        new SecretRule("huggingface-access-token",
            "\\b(hf_[a-zA-Z]{34})" + BOUNDARY, ""),

        // — Version control —
        new SecretRule("github-pat",
            "ghp_[0-9a-zA-Z]{36}", ""),
        new SecretRule("github-fine-grained-pat",
            "github_pat_\\w{82}", ""),
        new SecretRule("github-app-token",
            "(?:ghu|ghs)_[0-9a-zA-Z]{36}", ""),
        new SecretRule("github-oauth",
            "gho_[0-9a-zA-Z]{36}", ""),
        new SecretRule("github-refresh-token",
            "ghr_[0-9a-zA-Z]{36}", ""),
        new SecretRule("gitlab-pat",
            "glpat-[\\w-]{20}", ""),
        new SecretRule("gitlab-deploy-token",
            "gldt-[0-9a-zA-Z_\\-]{20}", ""),

        // — Communication —
        new SecretRule("slack-bot-token",
            "xoxb-[0-9]{10,13}-[0-9]{10,13}[a-zA-Z0-9-]*", ""),
        new SecretRule("slack-user-token",
            "xox[pe](?:-[0-9]{10,13}){3}-[a-zA-Z0-9-]{28,34}", ""),

        // — Communication (cont.) —
        new SecretRule("slack-app-token",
            "xapp-\\d-[A-Z0-9]+-\\d+-[a-z0-9]+", "i"),
        new SecretRule("twilio-api-key",
            "SK[0-9a-fA-F]{32}", ""),
        new SecretRule("sendgrid-api-token",
            "\\b(SG\\.[a-zA-Z0-9=_\\-.]{66})" + BOUNDARY, ""),

        // — Dev tooling —
        new SecretRule("npm-access-token",
            "\\b(npm_[a-zA-Z0-9]{36})" + BOUNDARY, ""),
        new SecretRule("pypi-upload-token",
            "pypi-AgEIcHlwaS5vcmc[\\w-]{50,1000}", ""),
        new SecretRule("databricks-api-token",
            "\\b(dapi[a-f0-9]{32}(?:-\\d)?)" + BOUNDARY, ""),
        new SecretRule("hashicorp-tf-api-token",
            "[a-zA-Z0-9]{14}\\.atlasv1\\.[a-zA-Z0-9\\-_=]{60,70}", ""),
        new SecretRule("pulumi-api-token",
            "\\b(pul-[a-f0-9]{40})" + BOUNDARY, ""),
        new SecretRule("postman-api-token",
            "\\b(PMAK-[a-fA-F0-9]{24}-[a-fA-F0-9]{34})" + BOUNDARY, ""),

        // — Observability —
        new SecretRule("grafana-api-key",
            "\\b(eyJrIjoi[A-Za-z0-9+/]{70,400}={0,3})" + BOUNDARY, ""),
        new SecretRule("grafana-cloud-api-token",
            "\\b(glc_[A-Za-z0-9+/]{32,400}={0,3})" + BOUNDARY, ""),
        new SecretRule("grafana-service-account-token",
            "\\b(glsa_[A-Za-z0-9]{32}_[A-Fa-f0-9]{8})" + BOUNDARY, ""),
        new SecretRule("sentry-user-token",
            "\\b(sntryu_[a-f0-9]{64})" + BOUNDARY, ""),
        new SecretRule("sentry-org-token",
            "\\bsntrys_eyJpYXQiO[a-zA-Z0-9+/]{10,200}"
                + "(?:LCJyZWdpb25fdXJs|InJlZ2lvbl91cmwi|cmVnaW9uX3VybCI6)"
                + "[a-zA-Z0-9+/]{10,200}={0,2}_[a-zA-Z0-9+/]{43}", ""),

        // — Payment / commerce —
        new SecretRule("stripe-access-token",
            "\\b((?:sk|rk)_(?:test|live|prod)_[a-zA-Z0-9]{10,99})" + BOUNDARY, ""),
        new SecretRule("shopify-access-token",
            "shpat_[a-fA-F0-9]{32}", ""),
        new SecretRule("shopify-shared-secret",
            "shpss_[a-fA-F0-9]{32}", ""),

        // — Crypto —
        new SecretRule("private-key",
            "-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY(?: BLOCK)?-----[\\s\\S-]{64,}?"
                + "-----END[ A-Z0-9_-]{0,100}PRIVATE KEY(?: BLOCK)?-----", "i")
    );

    // ─── Lazily compiled pattern cache ─────────────────────────────
    // CC getCompiledRules()（secretScanner.ts:229-237）：首次 scan 时编译一次。
    private static volatile List<CompiledRule> compiledRules = null;

    private record CompiledRule(String id, Pattern pattern) {}

    private static List<CompiledRule> getCompiledRules() {
        List<CompiledRule> local = compiledRules;
        if (local == null) {
            synchronized (TeamMemorySecretScanner.class) {
                local = compiledRules;
                if (local == null) {
                    List<CompiledRule> built = new ArrayList<>(SECRET_RULES.size());
                    for (SecretRule r : SECRET_RULES) {
                        int flags = (r.flags() != null && r.flags().contains("i"))
                            ? Pattern.CASE_INSENSITIVE : 0;
                        built.add(new CompiledRule(r.id(), Pattern.compile(r.source(), flags)));
                    }
                    compiledRules = built;
                    local = built;
                }
            }
        }
        return local;
    }

    // ─── ruleIdToLabel · :243-268 ───────────────────────────────────

    /** 规范大小写与 title case 不同的单词 · CC specialCase map（secretScanner.ts:245-263）。 */
    private static final Map<String, String> SPECIAL_CASE = Map.ofEntries(
        Map.entry("aws", "AWS"),
        Map.entry("gcp", "GCP"),
        Map.entry("api", "API"),
        Map.entry("pat", "PAT"),
        Map.entry("ad", "AD"),
        Map.entry("tf", "TF"),
        Map.entry("oauth", "OAuth"),
        Map.entry("npm", "NPM"),
        Map.entry("pypi", "PyPI"),
        Map.entry("jwt", "JWT"),
        Map.entry("github", "GitHub"),
        Map.entry("gitlab", "GitLab"),
        Map.entry("openai", "OpenAI"),
        Map.entry("digitalocean", "DigitalOcean"),
        Map.entry("huggingface", "HuggingFace"),
        Map.entry("hashicorp", "HashiCorp"),
        Map.entry("sendgrid", "SendGrid"));

    /** gitleaks rule ID（kebab-case）转人类可读 label · CC original: {@code ruleIdToLabel}（:243-268）。 */
    private static String ruleIdToLabel(String ruleId) {
        StringBuilder sb = new StringBuilder();
        for (String part : ruleId.split("-")) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            String special = SPECIAL_CASE.get(part);
            sb.append(special != null ? special : capitalize(part));
        }
        return sb.toString();
    }

    /**
     * capitalize · CC utils/stringUtils capitalize（stringUtils.ts:20-22）：{@code str.charAt(0).toUpperCase()
     * + str.slice(1)} —— 首字母大写，其余原样保留（JSDoc 明示：Unlike lodash capitalize, this does NOT
     * lowercase the remaining characters）。
     * <p>旧实现其余 toLowerCase(Locale.ROOT) 是偏差（B3 △-1，IMP-MV2-02）→ 已对齐。36 个 ruleId 全小写
     * kebab-case，规则集内 capitalize 输入均为纯小写段 → 规则集内 label 输出不变（无行为变化）；公共 API
     * {@code getSecretLabel} 对含大写输入对齐 CC：{@code getSecretLabel("MyToken")} = {@code "MyToken"}。
     */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ─── scanForSecrets · :277-295 ─────────────────────────────────

    /**
     * 扫描字符串中的潜在 secret · CC original: {@code scanForSecrets}（secretScanner.ts:277-295）。
     * 每条 rule 返回一个 match（按 ruleId 去重）。命中文本有意不返回 —— 永不记录/显示 secret 值。
     */
    public static List<SecretMatch> scanForSecrets(String content) {
        List<SecretMatch> matches = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (content == null || content.isEmpty()) {
            return matches;
        }
        for (CompiledRule rule : getCompiledRules()) {
            if (seen.contains(rule.id())) {
                continue;
            }
            if (rule.pattern().matcher(content).find()) {
                seen.add(rule.id());
                matches.add(new SecretMatch(rule.id(), ruleIdToLabel(rule.id())));
            }
        }
        return matches;
    }

    /** 获取 gitleaks rule ID 的人类可读 label · CC original: {@code getSecretLabel}（:301-303）。 */
    public static String getSecretLabel(String ruleId) {
        return ruleIdToLabel(ruleId);
    }

    // ─── redactSecrets · :312-324 ───────────────────────────────────

    /** redact 规则缓存（含 g flag · 全部出现替换）。 */
    private static volatile List<Pattern> redactRules = null;

    private static List<Pattern> getRedactRules() {
        List<Pattern> local = redactRules;
        if (local == null) {
            synchronized (TeamMemorySecretScanner.class) {
                local = redactRules;
                if (local == null) {
                    List<Pattern> built = new ArrayList<>(SECRET_RULES.size());
                    for (SecretRule r : SECRET_RULES) {
                        int flags = (r.flags() != null && r.flags().contains("i"))
                            ? Pattern.CASE_INSENSITIVE : 0;
                        built.add(Pattern.compile(r.source(), flags));
                    }
                    redactRules = built;
                    local = built;
                }
            }
        }
        return local;
    }

    /**
     * 将命中的 secret 原位替换为 [REDACTED] · CC original: {@code redactSecrets}（secretScanner.ts:312-324）。
     * 与 scanForSecrets 不同，返回内容（含替换后的 span），周围文本可安全写盘。
     * 仅替换捕获组（第 1 组），模式中的边界字符（空格/引号/;）必须存活。
     */
    public static String redactSecrets(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String result = content;
        for (Pattern re : getRedactRules()) {
            Matcher m = re.matcher(result);
            StringBuffer sb = new StringBuffer();
            boolean replaced = false;
            while (m.find()) {
                replaced = true;
                String match = m.group();
                String replacement;
                if (m.groupCount() >= 1 && m.group(1) != null) {
                    // 仅替换捕获组 span（边界字符存活）
                    int start = m.start(1) - m.start();
                    int end = m.end(1) - m.start();
                    replacement = match.substring(0, start) + "[REDACTED]" + match.substring(end);
                } else {
                    replacement = "[REDACTED]";
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            if (replaced) {
                m.appendTail(sb);
                result = sb.toString();
            }
        }
        return result;
    }
}
