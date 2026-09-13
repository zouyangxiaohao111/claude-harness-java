package com.nexusai.infra.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [provider-custom-headers 任务 6 · 步骤 6b + 任务 7 补 OpenAI 侧] <b>接线级护栏</b>：
 * 两个 SDK provider 解析 {@code sessionId} 的方法体内不得出现 MDC 兜底
 * {@code RequestContext.sessionId()}，且必须「显式传参 + 经共享判据 + 用共享注入器」。
 *
 * <h2>WHY 必须单独存在（类内守卫管不到调用点）</h2>
 * {@link SessionIdResolver} 类自身「刻意不读 MDC」有回归测试守着，但那守卫是<b>方法局部的</b>。
 * 它管不到<b>调用点</b>：接线者若「以同文件最近的先例为准」，就会照抄
 * {@code AnthropicSdkProvider.consumePostCompactionAtApiSuccess} 里紧邻的一句
 *
 * <pre>{@code
 * String sessionId = SessionIdResolver.fromHistory(history);
 * if (sessionId == null) { sessionId = RequestContext.sessionId(); }   // ← 属 consumePostCompaction 链路，勿照抄
 * }</pre>
 *
 * 此时 {@link SessionIdResolverTest} <b>全绿</b>（它只测类自身），而规范 §6.3 的
 * 「静默把 A 会话的亲和 id 发给 B 请求」原样复发 —— 残留 MDC 是真实的
 * （{@code MemoryController:143} / {@code TaskController:145} / {@code TeamController:95}
 * 三处 setSession 均无 clear）。本用例是这条防线唯一的自动化守卫。
 * <b>已实证</b>：把调用点改成 {@code RequestContext.sessionId()} 后，{@link SessionIdResolverTest}
 * 仍 6/6 全绿，本类才转红。
 *
 * <h2>切窗：以<b>成员声明</b>为锚（不再依赖排版巧合）</h2>
 * <p>初版用「{@code "\n    }\n"}（恰 4 空格的收尾括号）」切窗 —— <b>那是错的，且已实测出事故</b>：
 * {@code AnthropicSdkProvider.java:1171} 有一个<b>既存的 5 空格</b>收尾括号（全文件唯一不合
 * 「顶层方法收尾恰 4 空格」排版不变量处，是 {@code extractAssistantText} 的收尾），
 * 于是第 5 个调用点（{@code chatWithOptionsMessage}）的窗口实测为 {@code [1144..1247]}，
 * 把 {@code extractAssistantText}（{@code :1155-1171}）整个吞了进来。
 * 今天无后果（该方法恰未用 MDC），但它是<b>假红陷阱</b>：将来任何紧邻调用点、<b>合法</b>使用
 * {@code RequestContext.sessionId()} 的方法都会被误判成「照抄了 MDC 兜底」。
 *
 * <p>现改为<b>声明锚</b>：先扫出所有顶层成员声明的行号，窗口 = [本成员声明行, 下一个成员声明行)
 * 再去掉尾部属于下一个成员的 javadoc/空行。这样窗口 == 完整成员体的<b>构造性保证</b>，
 * 不再依赖任何排版巧合（上面那个 5 空格括号现在无影响：它落在窗口内，只是普通一行）。
 *
 * <p><b>自检</b>（{@link #assertWindowsSelfCheck}）：每个窗口必须「首行是成员声明行」且
 * 「窗口内成员声明行恰好 1 条」。这把「窗口被切短/被撑长 → 断言悄悄恒真或误红」转成响亮的红
 * —— 例如退回旧的排版切窗时，第 5 个窗口会含 2 条声明行，自检当场失败。
 *
 * <p><b>判据取「所有 {@code buildClient(config} 调用点所在成员」而非「{@code SessionIdResolver.}
 * 调用点所在方法」</b>（比任务书要求宽）：① 与「header 注入链」语义等价；② 于是<b>不需要任何
 * 豁免名单</b> —— 既存的 consumePostCompaction 方法不调 {@code buildClient}，结构性落在守卫外
 * （见 {@link #legacyConsumePostCompactionMethod_isOutsideClientBuildScope()}）；
 * ③ 两个 provider 的新调用点自动纳入。
 *
 * <p><b>硬中断线按「字面量」判，不剥注释</b>（刻意从严）：连<b>注释里</b>的照抄模板都不许留在注入链上 ——
 * 它就是被「以同文件最近的先例为准」照抄的来源，留在方法体内即隐患。代价是注释里提该调用形态也会
 * 转红，此时把注释改写成 {@code MDC（RequestContext.sessionId）}（去括号）即可，不需要放松本护栏。
 *
 * <p>2026-09-12 建（任务 6）· 2026-09-13 补 OpenAI 侧三条不变量 + 换声明锚（任务 7 暴露的护栏缺口）。
 */
class ProviderSessionIdWiringGuardTest {

    private static final String ANTHROPIC_PATH =
        "src/main/java/com/nexusai/infra/llm/AnthropicSdkProvider.java";
    private static final String OPENAI_PATH =
        "src/main/java/com/nexusai/infra/llm/OpenAiSdkProvider.java";

    /** client 构造调用点标记。<b>刻意不含声明形态</b>：声明是 {@code buildClient(ProviderConfig}。 */
    private static final String CLIENT_BUILD_CALL = "buildClient(config";

    /** 真实调用点的接线形态（显式传 sessionId）。 */
    private static final String WIRED_CALL = "buildClient(config, sessionId)";

    /** 单参重载内的降级安全网（占位符落常量，但 header 不整体丢）。 */
    private static final String DEGRADED_CALL = "buildClient(config, null)";

    /** 裸单参调用形态（★含收尾括号，故不匹配上面两者）—— 新出现即「主链失去缓存亲和」。 */
    private static final String BARE_SINGLE_ARG_CALL = "buildClient(config)";

    /** 共享判据（两个 provider 必须同源解析，不得各写一套）。 */
    private static final String RESOLVER_REF = "SessionIdResolver.";

    /** 共享注入器（两个 provider 必须调它，不得内联各写一份合并/过滤）。 */
    private static final String INJECTOR_APPLY = "ProviderHeaderInjector.apply(";

    /** 硬中断线：header 注入链上出现这个串即「照抄了 MDC 兜底」。 */
    private static final String FORBIDDEN_MDC = "RequestContext.sessionId()";

    // ─────────────────────── Anthropic（任务 6 交付的 5 个调用点） ───────────────────────

    @Test
    @DisplayName("接线级护栏：Anthropic 侧 buildClient 调用点所在方法内不得出现 RequestContext.sessionId()（MDC 兜底勿照抄）")
    void anthropic_clientBuildCallSites_haveNoMdcFallback() throws IOException {
        String source = readSource(ANTHROPIC_PATH);
        List<String> windows = clientBuildWindows(source);
        assertWindowsSelfCheck(windows);

        // 非空性：1 个单参重载内的降级调用 + 5 个真实调用点 = 6。
        // （若有人改了接入形态导致切窗失效，这里先红，不会静默退化成空跑。）
        assertThat(windows)
            .as("Anthropic 侧 buildClient(config(...) 调用点数量 = 1（单参降级网）+ 5（真实调用点）")
            .hasSize(6);

        for (String window : windows) {
            assertThat(window)
                .as("header 注入链上的方法体内不得出现 MDC 兜底 RequestContext.sessionId()"
                    + "（残留 MDC 是别会话的 id → 静默串号；见 SessionIdResolver 类 javadoc）")
                .doesNotContain(FORBIDDEN_MDC);
        }
    }

    @Test
    @DisplayName("接线级护栏：Anthropic 侧 5 个真实调用点必须逐个显式传 sessionId + 经 SessionIdResolver"
        + "（不得默认走单参重载，否则白白丢缓存亲和）")
    void anthropic_allRealCallSites_passExplicitSessionId() throws IOException {
        String source = readSource(ANTHROPIC_PATH);
        assertCounts(source, "Anthropic", 5);

        // 5 个方法必须各自经 SessionIdResolver（防「换成从别处取值」——含照抄 MDC 的变体）。
        long windowsWithResolver = clientBuildWindows(source).stream()
            .filter(w -> w.contains(RESOLVER_REF))
            .count();
        assertThat(windowsWithResolver)
            .as("5 个真实调用点所在方法必须统一经 SessionIdResolver 解析（单点判据，不得各写一套）")
            .isEqualTo(5);
    }

    // ─────────────────────── OpenAI（任务 7 交付的 4 个调用点） ───────────────────────

    @Test
    @DisplayName("接线级护栏：OpenAI 侧 buildClient 调用点所在方法内不得出现 RequestContext.sessionId()")
    void openAi_clientBuildCallSites_haveNoMdcFallback() throws IOException {
        // 注意：OpenAI 窗口内**合法存在** RequestContext.requestId()（非 sessionId 语义，见
        // OpenAiSdkProvider 的 requestId 兜底链路），故硬中断线必须是 RequestContext.sessionId()
        // 而非 RequestContext —— 用后者会当场误红。
        String source = readSource(OPENAI_PATH);
        List<String> windows = clientBuildWindows(source);
        assertWindowsSelfCheck(windows);

        assertThat(windows)
            .as("OpenAI 侧 buildClient(config(...) 调用点数量 = 1（单参降级网）+ 4（真实调用点）")
            .hasSize(5);

        for (String window : windows) {
            assertThat(window)
                .as("OpenAI 侧 header 注入链上的方法体内同样不得出现 MDC 兜底"
                    + "（两个 provider 共守一条判据，不允许只有 Anthropic 侧干净）")
                .doesNotContain(FORBIDDEN_MDC);
        }
    }

    @Test
    @DisplayName("接线级护栏：OpenAI 侧 4 个真实调用点必须逐个显式传 sessionId + 经 SessionIdResolver")
    void openAi_allRealCallSites_passExplicitSessionId() throws IOException {
        String source = readSource(OPENAI_PATH);
        assertCounts(source, "OpenAI", 4);

        long windowsWithResolver = clientBuildWindows(source).stream()
            .filter(w -> w.contains(RESOLVER_REF))
            .count();
        assertThat(windowsWithResolver)
            .as("4 个真实调用点所在方法必须统一经 SessionIdResolver 解析（与 Anthropic 侧同一判据）")
            .isEqualTo(4);
    }

    // ─────────────────────── 两 provider 共守：同一注入器 + 同一判据 ───────────────────────

    @Test
    @DisplayName("接线级护栏：两个 provider 的 buildClient 方法体都必须调共享 ProviderHeaderInjector.apply（不得内联各写一份）")
    void bothProviders_clientBuildMethods_useSharedInjector() throws IOException {
        // WHY：本仓有「同一能力两套判据」的 R7 前科；注入侧的展开/敏感头过滤必须单点。
        // 判据落在 **buildClient 成员窗口内**，而不是全文件计数 —— ProviderHeaderInjector 在本文件的
        // javadoc 里也有 {@link} 引用，全文件计数 ≥1 会被 javadoc 单独满足（假绿）。
        for (String path : List.of(ANTHROPIC_PATH, OPENAI_PATH)) {
            String source = readSource(path);
            String window = memberWindowByDeclNeedle(source, "buildClient(ProviderConfig config, String sessionId)");
            assertThat(window)
                .as("%s 的 buildClient(config, sessionId) 方法体必须真实调用 ProviderHeaderInjector.apply( "
                    + "（注入侧单点判据；javadoc 里的 {@link} 不算）", path)
                .contains(INJECTOR_APPLY);
        }
    }

    @Test
    @DisplayName("接线级护栏：既存 consumePostCompaction 的 MDC 兜底结构性落在守卫范围外（无需豁免名单）")
    void legacyConsumePostCompactionMethod_isOutsideClientBuildScope() throws IOException {
        String source = readSource(ANTHROPIC_PATH);
        // 本用例只断言「范围划分」这一条不变量，**刻意不** pin 那句 MDC 兜底本身的存在——
        // 它将来若被合法清理，本护栏不应跟着误红（豁免名单为空，删掉即无需任何同步）。
        int legacyCallIdx = source.indexOf("SessionIdResolver.fromHistory(history)");
        assertThat(legacyCallIdx)
            .as("既存唯一调用点 SessionIdResolver.fromHistory(history) 必须仍在（本护栏的对照锚点）")
            .isGreaterThan(-1);
        String legacyWindow = enclosingMemberWindow(source, legacyCallIdx);
        assertThat(legacyWindow)
            .as("该方法不构造 SDK client → 按本护栏的切窗规则自动落在范围外"
                + "（这正是「用 buildClient 作判据」而非「用方法名豁免名单」的理由）")
            .doesNotContain(CLIENT_BUILD_CALL);
    }

    // ─────────────────────── helpers ───────────────────────

    private static String readSource(String path) throws IOException {
        // CRLF → LF：本仓工作区是 CRLF（git autocrlf），行/列切分必须先归一。
        return Files.readString(Path.of(path)).replace("\r\n", "\n");
    }

    /**
     * 显式传参三项计数 + 调用点总数。（Anthropic / OpenAI 共用，只是期望值不同。）
     *
     * <p>为什么要断言<b>裸单参计数 == 0</b>（而不只是「接线数 == N」）：{@code buildClient(config, sessionId)}
     * 与 {@code buildClient(config, null)} 的计数只说明「当前有 N 处接线」，**发现不了将来新增的第 N+1 个
     * 单参调用点**（它既不加接线数、也不加降级数）。裸单参（含收尾括号，故不与前两者混淆）从 0 变 1
     * 就说明有人新开了「默认走单参」的口子 —— 主链会白白失去缓存亲和。三者合起来才是完备的。
     */
    private static void assertCounts(String source, String provider, int expectedWired) {
        assertThat(countOccurrences(source, CLIENT_BUILD_CALL))
            .as("%s 侧 buildClient(config 调用点总数 = 1（单参降级网）+ %d（真实调用点）；"
                + "新增任何调用点都必须显式接入并更新本护栏", provider, expectedWired)
            .isEqualTo(expectedWired + 1);
        assertThat(countOccurrences(source, WIRED_CALL))
            .as("%s 侧 %d 个真实调用点必须逐个显式传 sessionId（规范 §6.3 接线单位；改回单参即失去缓存亲和）",
                provider, expectedWired)
            .isEqualTo(expectedWired);
        assertThat(countOccurrences(source, DEGRADED_CALL))
            .as("%s 侧只允许 1 处恒 null —— 单参重载内的降级安全网（占位符落常量，但 header 不丢）", provider)
            .isEqualTo(1);
        assertThat(countOccurrences(source, BARE_SINGLE_ARG_CALL))
            .as("%s 侧裸单参 buildClient(config) 必须 0 次（新增即为「主链失去缓存亲和」的回归）", provider)
            .isZero();
    }

    /**
     * 自检：每个窗口必须「首行是成员声明行」且「窗口内成员声明行恰好 1 条」。
     *
     * <p>WHY：切窗一旦退化（被切短 → 断言恒真；被撑长 → 误红），本护栏就失去意义。最容易发生的退化是
     * 「退回排版切窗」——那时跨方法的窗口会含 2 条声明行，此处当场转红。
     */
    private static void assertWindowsSelfCheck(List<String> windows) {
        assertThat(windows)
            .as("切窗结果非空（否则下面的断言全部空跑 = 假绿）")
            .isNotEmpty();
        for (String window : windows) {
            String[] lines = window.split("\n", -1);
            assertThat(isMemberDeclLine(lines[0]))
                .as("窗口首行必须是成员声明行（声明锚的构造性保证）：[%s]", lines[0])
                .isTrue();
            assertThat(countMemberDeclLines(window))
                .as("窗口内成员声明行恰好 1 条（2 条 = 窗口把相邻成员吞进来了，见过 AnthropicSdkProvider:1171 "
                    + "的 5 空格收尾括号事故）")
                .isEqualTo(1);
        }
    }

    /** 所有 {@code CLIENT_BUILD_CALL} 所在成员的窗口。 */
    private static List<String> clientBuildWindows(String source) {
        List<String> windows = new ArrayList<>();
        int idx = source.indexOf(CLIENT_BUILD_CALL);
        while (idx > -1) {
            windows.add(enclosingMemberWindow(source, idx));
            idx = source.indexOf(CLIENT_BUILD_CALL, idx + 1);
        }
        return windows;
    }

    /** 按「成员声明里含 {@code declNeedle}」找该成员窗口（declNeedle 必须唯一命中）。 */
    private static String memberWindowByDeclNeedle(String source, String declNeedle) {
        String[] lines = source.split("\n", -1);
        List<Integer> decls = memberDeclLineIndices(lines);
        Integer hit = null;
        for (int d : decls) {
            if (lines[d].contains(declNeedle)) {
                if (hit != null) {
                    throw new AssertionError("声明锚 [" + declNeedle + "] 命中多处：" + (hit + 1) + " 与 " + (d + 1));
                }
                hit = d;
            }
        }
        assertThat(hit)
            .as("必须存在含 [%s] 的成员声明（锚点漂移会让本护栏静默失效，故 fail loud）", declNeedle)
            .isNotNull();
        return memberWindow(lines, memberDeclLineIndices(lines), hit);
    }

    /** 取包含字符下标 {@code idx} 的成员的窗口。 */
    private static String enclosingMemberWindow(String source, int idx) {
        String[] lines = source.split("\n", -1);
        int lineIdx = source.substring(0, idx).split("\n", -1).length - 1;
        List<Integer> decls = memberDeclLineIndices(lines);
        Integer found = null;
        for (int d : decls) {
            if (d > lineIdx) {
                break;
            }
            found = d;
        }
        assertThat(found)
            .as("字符下标 %d（第 %d 行）必须落在某个成员声明的范围内（锚点漂移 → fail loud）", idx, lineIdx + 1)
            .isNotNull();
        return memberWindow(lines, decls, found);
    }

    /**
     * 成员窗口 = [本成员声明行, 下一个成员声明行)，再去掉尾部属于<b>下一个</b>成员的空行/注释块
     * （javadoc 与 {@code //} 分隔注释）。去尾后窗口 == 本成员体（含声明行），相邻成员的一行都不含。
     */
    private static String memberWindow(String[] lines, List<Integer> decls, int declLineIdx) {
        int next = lines.length;
        for (int d : decls) {
            if (d > declLineIdx) {
                next = d;
                break;
            }
        }
        int end = next;
        while (end - 1 > declLineIdx && isInterMemberFiller(lines[end - 1])) {
            end--;
        }
        return String.join("\n", List.of(lines).subList(declLineIdx, end));
    }

    /** 两个成员之间的空行 / javadoc 块 / 行注释（属于下一个成员的先导文本，不属于本成员体）。 */
    private static boolean isInterMemberFiller(String line) {
        String t = line.trim();
        return t.isEmpty() || t.startsWith("*") || t.startsWith("/*") || t.startsWith("//");
    }

    /** 顶层成员声明的行号（按出现顺序）。 */
    private static List<Integer> memberDeclLineIndices(String[] lines) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (isMemberDeclLine(lines[i])) {
                idx.add(i);
            }
        }
        return idx;
    }

    private static int countMemberDeclLines(String window) {
        int count = 0;
        for (String line : window.split("\n", -1)) {
            if (isMemberDeclLine(line)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 是否「顶层成员声明行的首行」。判据（本仓排版不变量）：
     * <ul>
     *   <li>缩进<b>恰好 4 空格</b>（类体成员才在 4；方法体内的语句缩进 ≥8，天然排除）；</li>
     *   <li>不是注解 {@code @...}、不是 javadoc/块注释 {@code /*}、不是行注释 {@code //}；</li>
     *   <li>不是裸括号行 {@code &#123;}/{@code &#125;}；</li>
     *   <li>含 {@code (}（方法/构造器必有形参表）<b>且不含 {@code =}</b>（排除字段初始化式，
     *       如 {@code private static final Logger log = LoggerFactory.getLogger(...)}）。</li>
     * </ul>
     * 已知不覆盖：无 {@code (} 的成员（如 {@code static class StreamState &#123;}、纯字段声明）不被计为锚
     * —— 对本护栏无影响（调用点不会落在它们内部），且「窗口内声明行恰好 1 条」的自检也不受影响。
     */
    private static boolean isMemberDeclLine(String line) {
        if (line.length() < 6 || !line.startsWith("    ") || line.charAt(4) == ' ') {
            return false;
        }
        String t = line.substring(4);
        if (t.startsWith("@") || t.startsWith("*") || t.startsWith("/")) {
            return false;
        }
        if (t.equals("{") || t.equals("}")) {
            return false;
        }
        if (!t.contains("(") || t.contains("=")) {
            return false;
        }
        return true;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx > -1) {
            count++;
            idx = haystack.indexOf(needle, idx + 1);
        }
        return count;
    }
}
