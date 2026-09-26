package com.nexusai.infra.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>[C · provider-hdr 2026-09-24] 源码级护栏：{@code src/main/java} 里禁止再出现「2 参
 * {@code new ProviderConfig(...)}」的生产构造点。</b>
 *
 * <h2>为什么必须有这条护栏（被修的缺陷）</h2>
 * <p>{@code ProviderConfig} 的 2 参便捷构造器落 {@code Map.of()}
 * （{@link ProviderConfig#ProviderConfig(String, String)}）⇒ {@link ProviderHeaderInjector#apply}
 * 的空值早返回（ProviderHeaderInjector.java:102）⇒ <b>该出口一条自定义 header 都不发</b>
 * （不是发常量）。实测两处 2 参生产构造点（{@code ToolRegistrationConfig} 的 fork 路由 +
 * {@code configSupplier}）令 <b>fork 家族（SESSION_MEMORY / EXTRACT_MEMORIES / AUTO_DREAM，每轮
 * post-sampling hook 触发）+ 压缩摘要 + away-summary</b> 恒零 header ⇒ opencode 这类强制要求
 * 请求头的 provider 每轮 400，而主链聊天正常（用户报的「经常丢失 sessionId」）。
 *
 * <p><b>为什么不能只靠行为测试</b>：本缺陷的形态是「<b>某个调用点</b>漏带第 3 参」——行为测试
 * 只能覆盖它认识的那几个调用点；新加的第 N+1 个 2 参调用点不会有任何测试变红。本护栏把判据钉在
 * 「全仓源码里 {@code new ProviderConfig(...)} 的实参个数」上 ⇒ 未来谁再写一个 2 参生产构造点，
 * 本类当场转红（并由 {@link ProviderHeaderObservabilityTest} 在运行期给出 hdrs=0 / 空 Map warn）。
 *
 * <p>模式照抄同包 <code>ProviderSessionIdWiringGuardTest</code>（读源码 + 计数断言 + 非空性自检 +
 * 显式白名单），⛔ 不另起范式。
 *
 * <h2>三条不变量</h2>
 * <ol>
 *   <li><b>白名单外一律 3 参</b>：任何非白名单构造点的实参数必须恰为 3；</li>
 *   <li><b>白名单必须被命中</b>且命中数恰为 1（否则白名单条目失效/漂移 ⇒ 静默放宽）；</li>
 *   <li><b>已知发送链路必须在册</b>：4 个真实发送链路的构造点必须仍存在且为 3 参
 *       （非空性自检：避免扫描器失效后本类退化成恒绿空跑）。</li>
 * </ol>
 *
 * <h2>词法口径（刻意从严，与既有护栏同款）</h2>
 * <ul>
 *   <li><b>不剥注释</b>：注释里写出 {@code new ProviderConfig(x, y)} 形态也算命中 —— 它正是被
 *       「以最近先例为准」照抄的来源。命中时把注释改写掉即可，不需要放松护栏。</li>
 *   <li><b>区分定义与调用点</b>：{@code ProviderConfig} 的 2 参构造器<b>声明</b>
 *       （{@code public ProviderConfig(String baseUrl, String apiKey)}）不含 {@code new} ⇒
 *       扫描器天然不匹配它（本类另有一条自检把这点钉住）。</li>
 *   <li>实参切分按「顶层逗号」算：括号/方括号/花括号深度 &gt; 0 内的逗号不算，跳过字符串/字符
 *       字面量与注释 ⇒ 跨行实参（如 {@code ChatService.java:2219-2220}）也数得准。</li>
 * </ul>
 *
 * <p>纯 JUnit：⛔ 无 Spring / ⛔ 无 {@code @SpringBootTest} / ⛔ 无真 API / 无真 DB（只读源码文件）。
 */
@DisplayName("[C 护栏] src/main/java 里不得再出现 2 参 new ProviderConfig(...) 生产构造点")
class ProviderConfigConstructionGuardTest {

    /** 扫描根：surefire 的工作目录 = backend 模块目录（与既有源码护栏相同口径）。 */
    private static final Path MAIN_ROOT = Path.of("src/main/java");

    /** 被守护的构造形态（含全限定名写法 {@code new com.nexusai.infra.llm.ProviderConfig(}）。 */
    private static final Pattern NEW_PROVIDER_CONFIG =
        Pattern.compile("new\\s+(?:[A-Za-z_$][\\w$]*\\.)*ProviderConfig\\s*\\(");

    /** 定义文件（2 参构造器声明所在处；其内部允许 1 处白名单调用点）。 */
    private static final String DEFINITION_FILE_REL = "com/nexusai/infra/llm/ProviderConfig.java";

    /**
     * 显式白名单：唯一允许存在的 2 参生产调用点。键 = {@code 相对路径#实参原文}，值 = <b>必写</b>理由。
     */
    private static final Map<String, String> ARITY2_WHITELIST = Map.of(
        DEFINITION_FILE_REL + "#null, null",
        "定义文件自身的 ProviderConfig.empty() 工厂：apiKey 恒 null ⇒ isUsable()=false ⇒ 经 "
            + "LlmProviderFactory.getProvider 恒落 MockLlmProvider（LlmProviderFactory.java:48）"
            + "⇒ 结构性到不了 ProviderHeaderInjector（本类同文件自检 EMPTY_MAP 的判据说明同此），"
            + "即「不是发送链路的构造点」。清理 2 参构造器属后续项，本批不动它。");

    /**
     * 已知「真实发送链路」的 3 参构造点（非空性自检用：<b>不是</b>计数上限，只是「必须还在」）。
     *
     * <p>为什么锁这 4 个文件：它们是 header 真的上行的四条链路 ——
     * fork 路由 + configSupplier（ToolRegistrationConfig）、模型解析（ModelConfigResolver）、
     * 主链（ChatService）、ExecAgentHook 权限 hook。条目消失 = 有人删/搬了构造点，需人看。
     */
    private static final Map<String, Integer> KNOWN_WIRING_SITES = Map.of(
        "com/nexusai/application/agent/config/ToolRegistrationConfig.java", 2,
        "com/nexusai/infra/llm/ModelConfigResolver.java", 1,
        "com/nexusai/application/chat/ChatService.java", 1,
        "com/nexusai/application/agent/permission/hook/ExecAgentHook.java", 1);

    @Test
    @DisplayName("护栏：白名单以外的 new ProviderConfig(...) 生产调用点必须都是 3 参（新增 2 参即红）")
    void allProductionConstructionSites_areThreeArg() throws IOException {
        List<Site> sites = scanProductionSites();

        // 非空性自检：扫描器若失效（找不到任何构造点），下面的断言会变成恒绿空跑。
        assertThat(sites)
            .as("扫描器必须真的在 [%s] 下找到 ProviderConfig 构造点（否则本护栏恒绿空跑）", MAIN_ROOT)
            .isNotEmpty();

        // ③ 已知发送链路必须在册且为 3 参。
        Map<String, Integer> threeArgByFile = new LinkedHashMap<>();
        for (Site s : sites) {
            if (s.paramCount == 3) {
                threeArgByFile.merge(s.relPath, 1, Integer::sum);
            }
        }
        KNOWN_WIRING_SITES.forEach((file, expected) ->
            assertThat(threeArgByFile.getOrDefault(file, 0))
                .as("已知 header 发送链路的 3 参构造点 [%s] 必须至少 %d 处（少一个 = 该链路又回到零 header）",
                    file, expected)
                .isGreaterThanOrEqualTo(expected));

        // ② 白名单命中数恰为 1（白名单条目漂移 ⇒ 静默放宽，故 fail loud）。
        List<Site> whitelistHits = sites.stream()
            .filter(s -> ARITY2_WHITELIST.containsKey(s.whitelistKey()))
            .toList();
        assertThat(whitelistHits)
            .as("白名单条目必须恰命中 1 次（%s 的 empty() 工厂）——命中 0 次 = 条目已漂移失效，"
                + "命中 ≥2 次 = 该文件里新增了第二个 2 参调用点（必须逐个人工过一遍）",
                DEFINITION_FILE_REL)
            .hasSize(1);

        // ① 本体断言：非白名单构造点一律 3 参。
        List<Site> violations = sites.stream()
            .filter(s -> s.paramCount != 3)
            .filter(s -> !ARITY2_WHITELIST.containsKey(s.whitelistKey()))
            .toList();
        assertThat(violations)
            .as("src/main/java 里禁止 2 参（或其他非 3 参）ProviderConfig 生产构造点 —— "
                + "2 参便捷构造落 Map.of() ⇒ ProviderHeaderInjector.java:102 空值早返回 ⇒ "
                + "该出口一条自定义 header 都不发（opencode 强制要 header ⇒ 400）。"
                + "修法：改 3 参并带上 ProviderService.deserializeHeaders(provider.getExtraHeaders())"
                + "（先例 ModelConfigResolver.java:117 / ChatService.java:2219）。违规点：%s", violations)
            .isEmpty();
    }

    @Test
    @DisplayName("自检：2 参构造器的【声明】不被误判成调用点（定义 vs 调用点必须分开）")
    void constructorDeclaration_isNotTreatedAsCallSite() throws IOException {
        String defSource = readSource(MAIN_ROOT.resolve(DEFINITION_FILE_REL));
        // 声明形态：无 new ⇒ 扫描器不匹配「声明行」本身。
        assertThat(defSource)
            .as("被守护文件的 2 参构造器声明必须仍在（本用例的对照锚点；它被删掉则本自检失去意义）")
            .contains("public ProviderConfig(String baseUrl, String apiKey)");

        List<Site> defSites = scanProductionSites().stream()
            .filter(s -> s.relPath.equals(DEFINITION_FILE_REL))
            .toList();
        assertThat(defSites)
            .as("定义文件里的构造点只应有一处（empty() 工厂内的 new ProviderConfig(null, null)）——"
                + "若扫描器把「声明」也数进来这里会是 2 处，正是「定义/调用点混判」的假红形态")
            .hasSize(1);
        assertThat(defSites.get(0).argsText)
            .as("该唯一构造点的实参必须是 empty() 工厂的 null, null")
            .isEqualTo("null, null");
    }

    // ─────────────────────────────── 扫描器 ───────────────────────────────

    /** 一处构造点。 */
    private record Site(String relPath, int line, int paramCount, String argsText) {
        String whitelistKey() {
            return relPath + "#" + argsText;
        }

        @Override
        public String toString() {
            return relPath + ":" + line + " （" + paramCount + " 参：" + argsText + "）";
        }
    }

    private static List<Site> scanProductionSites() throws IOException {
        List<Site> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(MAIN_ROOT)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            for (Path p : files) {
                String source = readSource(p);
                String rel = MAIN_ROOT.relativize(p).toString().replace('\\', '/');
                Matcher m = NEW_PROVIDER_CONFIG.matcher(source);
                while (m.find()) {
                    int openParen = m.end() - 1;   // 正则末字符即 '('
                    List<String> args = splitTopLevelArgs(source, openParen);
                    String argsText = String.join(", ", args);
                    out.add(new Site(rel, lineOf(source, m.start()), args.isEmpty() ? 0 : args.size(), argsText));
                }
            }
        }
        return out;
    }

    /**
     * 切出实参（顶层逗号分隔）。跳过字符串/字符字面量与注释，括号类配对计数 ⇒ 跨行实参也准。
     */
    private static List<String> splitTopLevelArgs(String src, int openParenIdx) {
        List<String> args = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = openParenIdx; i < src.length(); i++) {
            char c = src.charAt(i);
            if (i == openParenIdx) {
                depth = 1;
                continue;
            }
            // 字面量（含转义）
            if (c == '"' || c == '\'') {
                int end = skipLiteral(src, i, c);
                if (depth == 1) {
                    cur.append(src, i, end);
                }
                i = end - 1;
                continue;
            }
            // 注释
            if (c == '/' && i + 1 < src.length() && (src.charAt(i + 1) == '/' || src.charAt(i + 1) == '*')) {
                int end = src.charAt(i + 1) == '/' ? src.indexOf('\n', i) : src.indexOf("*/", i);
                end = end < 0 ? src.length() : end + (src.charAt(i + 1) == '/' ? 0 : 2);
                i = end - 1;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
                if (depth == 1) {
                    continue;
                }
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    addIfNotBlank(args, cur);
                    return args;
                }
            } else if (c == ',' && depth == 1) {
                addIfNotBlank(args, cur);
                cur.setLength(0);
                continue;
            }
            if (depth >= 1) {
                cur.append(c);
            }
        }
        addIfNotBlank(args, cur);   // 源码截断（扫描异常）时也返回已收集内容，便于断言里看到实参原文
        return args;
    }

    private static void addIfNotBlank(List<String> args, StringBuilder cur) {
        String s = cur.toString().trim();
        if (!s.isEmpty()) {
            args.add(s);
        }
    }

    /** 跳过 {@code "..."} / {@code '...'}（含 {@code \\} 转义），返回结束引号后的下标。 */
    private static int skipLiteral(String src, int start, char quote) {
        for (int i = start + 1; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i + 1;
            }
        }
        return src.length();
    }

    private static int lineOf(String source, int idx) {
        return source.substring(0, idx).split("\n", -1).length;
    }

    private static String readSource(Path p) throws IOException {
        // CRLF → LF：本仓工作区是 CRLF（git autocrlf），行号/切分口径先归一（同既有护栏）。
        return Files.readString(p).replace("\r\n", "\n");
    }
}
