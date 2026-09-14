package com.nexusai.application.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>[S1-T19 + T19-2] ambient 归因载体「已彻底移除」守卫 —— 源码扫描 + 编译产物断言，
 * <u>不是</u>行为测试</b>。
 *
 * <h2>⚠️ 先读这段：本类守护的是什么，不是什么</h2>
 * <p><b>本类不做任何运行时行为断言</b>。它读 {@code backend/src/main/java/**}{@code /*.java} 的
 * <b>文本</b>（含注释），并对 {@link AgentContext} 的<b>编译产物</b>做反射枚举。
 * 它<b>不证明</b>任何运行期语义。<b>⛔ 不得把本类当作行为守护引用</b> —— 本仓铁律
 * 「不许把源码扫描冒充行为守护」的直接适用对象（同类手法见
 * {@code com.nexusai.common.DeadSymbolReferenceGuardTest}，其类 javadoc 亦明示纯扫描性）。
 *
 * <h2>WHY 需要（两批根因）</h2>
 * <ol>
 *   <li><b>[S1-T7]</b> 17 个生产读点从 ambient（plain ThreadLocal）改为显式载体
 *       （{@code RunRequest.agentContext} → base {@code ToolUseContext.agentContext()} → 工具形参 / provider 载荷）。</li>
 *   <li><b>[S1-T7-2]</b> 载体本体（{@code STORAGE} ThreadLocal + 读取方法 + 线程包裹 + 三个生产零调用的
 *       ambient 便捷重载）<b>整体删除</b>。动机（实测）：该 ThreadLocal 已退化为<b>只写不读</b>
 *       （唯一写点 = 子代理 query loop 的线程包裹，零读点），而 plain ThreadLocal <b>不跨线程继承</b>，
 *       归因消费点大量跑在 commonPool / tool-exec 池 / STREAM_EXECUTOR 虚拟线程上 ⇒ ambient 读恒 null。
 *       用户铁律：<b>会话态一律不得经 ThreadLocal/MDC 读；回放不算合规</b>。</li>
 * </ol>
 *
 * <h2>门 1 的豁免规则（裁定 (a)：给 CC 源引用豁免，<u>不</u>为过门而抹掉 CC 符号名）</h2>
 * <p>{@code backend/CLAUDE.md} 明文约定「每个字段 JavaDoc 标注 <b>CC 原名 + 行号</b>」，目的是
 * <b>未来审计无需重跑</b>。若为了「零字符串命中」而抹掉 CC 符号名，是拿<b>文档可追溯性</b>资产去换
 * 一个更严的字符串门 —— 代价不对称。而风险为零：<b>Java 的真读点不可能藏在注释里</b>
 * （真调用必须用 Java 符号出现在代码里）⇒ 豁免不会给任何真读点开门。
 * <p>故：命中行若<b>同时携带 CC 源引用标记</b>（{@code foo.ts:NN} / {@code CC :NN} /
 * {@code claude-code-best} / {@code Open-ClaudeCode}）⇒ 豁免；<b>不带</b>标记的提及仍须零命中
 * （那才是「注释里还在教人用」）。
 * <p>豁免本身有两道自证：{@link #gate1b_ccExemption_isNonVacuous()} 断言豁免**确实被用上**
 * （否则规则是死代码 / 标记写错也无人知），{@link #gate4_scanner_classifiesWithAndWithoutCcCitation}
 * 在合成树上双向证明分类精确。
 *
 * <h2>门</h2>
 * <table border="1">
 *   <caption>五门</caption>
 *   <tr><th>门</th><th>守什么</th><th>判据</th></tr>
 *   <tr><td><b>门 1</b></td><td>载体符号「不带 CC 引用时零出现」</td>
 *       <td>{@code STORAGE} / {@code getAgentContext} / {@code runWithAgentContext} /
 *           {@code getSubagentLogName} 在 {@code src/main} 全文本命中，<b>非豁免（无 CC 标记）部分必须为空</b></td></tr>
 *   <tr><td><b>门 1b</b></td><td>豁免非 vacuous</td>
 *       <td>豁免集合非空（若为 0 ⇒ 规则失效 / 标记写错 ⇒ 门 1 沦为「全豁免」或「全红」）</td></tr>
 *   <tr><td><b>门 2</b></td><td>编译产物面（<b>不受豁免影响</b>）</td>
 *       <td>{@link AgentContext} 方法表零 ambient 入口；{@code consumeInvokingRequestId} 全重载恰 1 参、
 *           {@code attachInvokingRequestEdge} 恰 2 参；无 ThreadLocal 类型字段</td></tr>
 *   <tr><td><b>门 3</b></td><td>边界面</td>
 *       <td>{@code apis/**} 与 {@code eventbus/**} 零命中；{@code TeammateContext} 三方法零命中</td></tr>
 *   <tr><td><b>门 4</b></td><td>扫描器自检（<b>反向实验内建</b>）</td>
 *       <td>合成树：空树=空集 · 注释提及=命中 · <b>无 CC 标记 ⇒ 非豁免；带 {@code CC x.ts:NN} ⇒ 豁免</b></td></tr>
 * </table>
 */
class AgentContextAmbientReadInventoryTest {

    /** 「已移除」的载体符号。前三个 = T19-2 施工单点名；第四个 = Java 侧方法已删 ⇒ 任何提及都是死引用。 */
    private static final List<String> CARRIER_TOKENS = List.of(
        "STORAGE",
        "getAgentContext",
        "runWithAgentContext",
        "getSubagentLogName"
    );

    /**
     * CC 源引用标记（命中行<b>同时</b>含其一 ⇒ 豁免）。三种形态：
     * <ol>
     *   <li>{@code foo.ts:123} / {@code Bar.tsx:45} —— 最常用（CC file:line）</li>
     *   <li>{@code CC :375} —— 同文件上下文续引（CC 简写行号）</li>
     *   <li>{@code claude-code-best} / {@code Open-ClaudeCode} —— 源仓名（用于「整文件零命中」这类无行号的陈述）</li>
     * </ol>
     */
    private static final List<Pattern> CC_SOURCE_MARKERS = List.of(
        Pattern.compile("\\.tsx?:\\d"),
        Pattern.compile("\\bCC\\s*:"),
        Pattern.compile("claude-code-best|Open-ClaudeCode")
    );

    /** 只在这些相对子树里断言「零命中（连豁免都没有）」（边界面）。 */
    private static final List<String> ZERO_HIT_SUBTREES = List.of(
        "src/main/java/com/nexusai/apis",
        "src/main/java/com/nexusai/eventbus"
    );

    /** 轨 I 已删除的 {@code TeammateContext} 载体三方法 —— main 必须零命中。 */
    private static final List<String> TEAMMATE_CONTEXT_TOKENS = List.of(
        "TeammateContext.getTeammateContext",
        "runWithTeammateContext",
        "TeammateContext.isInProcessTeammate"
    );

    /** 扫描器自检锚：该类型名必然大量出现在 main（证明「扫对目录且真读了文件」）。 */
    private static final String SCANNER_ANCHOR = "AgentContext";

    /** 一条命中：{@code relPath:line} + 该行原文。 */
    private record Hit(String where, String line) {
        boolean hasCcCitation() {
            return CC_SOURCE_MARKERS.stream().anyMatch(p -> p.matcher(line).find());
        }
    }

    // ──────────────────────────── 门 1 ────────────────────────────

    @Test
    @DisplayName("门 1：不带 CC 源引用的载体提及在 src/main 零命中（带 CC file:line 的引用豁免）")
    void gate1_carrierTokens_withoutCcCitation_areAbsent() {
        Path main = backendRoot().resolve("src/main/java");

        List<Hit> allHits = new ArrayList<>();
        for (String token : CARRIER_TOKENS) {
            allHits.addAll(rawTextHits(main, token));
        }
        List<Hit> bare = allHits.stream().filter(h -> !h.hasCcCitation()).toList();

        assertThat(bare)
            .as("ambient 归因载体已整体移除；**不带 CC 源引用**的提及必须零命中 —— "
                + "那正是「注释里还在教人用」的形态。带 `CC x.ts:NN` / `CC :NN` / 源仓名的引用豁免"
                + "（裁定 (a)：保留 CC 符号名与行号，供未来审计；真读点不可能藏在注释里）。"
                + "实际非豁免命中：%s",
                bare.stream().map(h -> h.where() + "  |  " + h.line().trim()).toList())
            .isEmpty();
    }

    /**
     * 豁免规则<b>非 vacuous</b>：若豁免集合为空，说明标记正则写错（或所有 CC 引用都被误改），
     * 此时门 1 要么变成「全红」要么变成「全豁免」—— 两者都不是本轮要的判据。
     */
    @Test
    @DisplayName("门 1b：CC 引用豁免确实被用上（非 vacuous；防标记正则写错）")
    void gate1b_ccExemption_isNonVacuous() {
        Path main = backendRoot().resolve("src/main/java");
        List<Hit> allHits = new ArrayList<>();
        for (String token : CARRIER_TOKENS) {
            allHits.addAll(rawTextHits(main, token));
        }
        List<Hit> exempted = allHits.stream().filter(Hit::hasCcCitation).toList();

        assertThat(exempted)
            .as("带 CC 源引用（含符号名 + file:line）的引用必须存在 —— 它承载本仓约定的 "
                + "「CC 原名 + 行号」可追溯性；为 0 说明标记正则失效或 CC 引用被误改")
            .isNotEmpty();
        // 每条豁免都必须真的能被标记匹配（自证分类不是「碰巧」）
        assertThat(exempted)
            .as("豁免项必须逐条匹配至少一个 CC 源引用标记")
            .allSatisfy(h -> assertThat(h.hasCcCitation()).isTrue());
    }

    // ──────────────────────────── 门 2 ────────────────────────────

    @Test
    @DisplayName("门 2（编译产物级，不受豁免影响）：AgentContext 方法表零 ambient 入口；显式 API 参数数固定")
    void gate2_compiledSurface_hasNoAmbientEntryPoints() {
        List<String> methodNames = Arrays.stream(AgentContext.class.getDeclaredMethods())
            .map(Method::getName)
            .distinct()
            .toList();

        for (String forbidden : List.of("getAgentContext", "runWithAgentContext", "getSubagentLogName")) {
            assertThat(methodNames)
                .as("AgentContext 的方法表上不得存在 %s（ambient 入口已随载体删除；"
                    + "注释豁免管不到这里 —— 方法表上不存在就是不存在）", forbidden)
                .doesNotContain(forbidden);
        }

        List<Integer> consumeArities = Arrays.stream(AgentContext.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("consumeInvokingRequestId"))
            .map(Method::getParameterCount)
            .sorted()
            .toList();
        assertThat(consumeArities)
            .as("consumeInvokingRequestId 只允许「恰 1 个显式上下文参数」的重载（0 参 ambient 版已删）")
            .isNotEmpty()
            .containsOnly(1);

        List<Integer> attachArities = Arrays.stream(AgentContext.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("attachInvokingRequestEdge"))
            .map(Method::getParameterCount)
            .sorted()
            .toList();
        assertThat(attachArities)
            .as("attachInvokingRequestEdge 只允许「恰 2 个参数（eventAttrs, context）」的重载（1 参 ambient 版已删）")
            .isNotEmpty()
            .containsOnly(2);

        assertThat(Arrays.stream(AgentContext.class.getDeclaredFields())
            .map(f -> f.getType().getName())
            .toList())
            .as("AgentContext 不得再持有 ThreadLocal 类型字段")
            .noneMatch(t -> t.contains("ThreadLocal"));
    }

    // ──────────────────────────── 门 3 ────────────────────────────

    @Test
    @DisplayName("门 3：apis/** 与 eventbus/** 零命中（连豁免都不允许）；TeammateContext 三方法零命中")
    void gate3_outboundAndEventbus_andTeammateCarrier_absent() {
        for (String sub : ZERO_HIT_SUBTREES) {
            Path subRoot = backendRoot().resolve(sub);
            assertThat(Files.isDirectory(subRoot))
                .as("边界面目录必须存在（fail loud：路径漂移不许静默变成空扫）: %s", subRoot)
                .isTrue();
            for (String token : CARRIER_TOKENS) {
                assertThat(rawTextHits(subRoot, token))
                    .as("%s 下不得出现载体符号 %s（HTTP/STOMP 面不得成为第三条读路径；此处不设 CC 豁免）", sub, token)
                    .isEmpty();
            }
        }

        Path main = backendRoot().resolve("src/main/java");
        for (String token : TEAMMATE_CONTEXT_TOKENS) {
            assertThat(rawTextHits(main, token))
                .as("TeammateContext 载体（轨 I 已删类）的 %s 在 main 必须零命中"
                    + "（身份载体 = ToolUseContext.teammateIdentity）", token)
                .isEmpty();
        }
    }

    // ──────────────────────────── 门 4（扫描器自检）────────────────────────────

    /**
     * 反向实验（内建）：同一个扫描器 + 同一个分类器，在 {@link TempDir} 合成树上必须
     * <b>先空、再命中、且能区分带/不带 CC 引用</b> —— 证明门 1 的红/绿与豁免都由真实文本决定。
     */
    @Test
    @DisplayName("门 4：合成树（空树=空集 · 注释提及=命中 · 无 CC 标记⇒非豁免 · 带 CC x.ts:NN⇒豁免）")
    void gate4_scanner_classifiesWithAndWithoutCcCitation(@TempDir Path tmp) throws IOException {
        Path srcRoot = tmp.resolve("src/main/java/com/nexusai/probe");
        Files.createDirectories(srcRoot);

        assertThat(rawTextHits(tmp.resolve("src/main/java"), "STORAGE"))
            .as("空目录必须零命中")
            .isEmpty();

        // ① 不带 CC 引用 ⇒ 必须被判为非豁免（这条是「门 1 会红」的等价物）
        // ② 带 CC file:line ⇒ 必须被判为豁免（裁定 (a)）
        Path probe = srcRoot.resolve("Probe.java");
        Files.writeString(probe, String.join("\n",
            "package com.nexusai.probe;",
            "class Probe {",
            "    // ① 裸提及（无 CC 引用）：runWithAgentContext —— 必须非豁免",
            "    // ② 引用 CC 真源：对齐 CC agentContext.ts:108-110 runWithAgentContext —— 必须豁免",
            "}"));

        List<Hit> hits = rawTextHits(tmp.resolve("src/main/java"), "runWithAgentContext");
        List<Hit> bare = hits.stream().filter(h -> !h.hasCcCitation()).toList();
        List<Hit> cited = hits.stream().filter(Hit::hasCcCitation).toList();

        assertThat(hits)
            .as("注释里的提及必须被计入（否则门 1 是空头承诺）")
            .hasSize(2);
        assertThat(bare)
            .as("① 不带 CC 引用的那一行必须落在**非豁免**（⇒ 门 1 会红）")
            .hasSize(1)
            .allSatisfy(h -> assertThat(h.line()).contains("裸提及"));
        assertThat(cited)
            .as("② 带 `CC x.ts:NN` 的那一行必须落在**豁免**（裁定 (a)：保留 CC 符号名）")
            .hasSize(1)
            .allSatisfy(h -> assertThat(h.line()).contains("引用 CC 真源"));
    }

    /** 扫描覆盖自检：真实树必须读到足量 .java（防「扫错目录 ⇒ 空集恒绿」）。 */
    @Test
    @DisplayName("门 4b：真实树扫描覆盖量 + 锚命中（防扫错目录的假绿）")
    void gate4b_realTreeScan_isNotEmpty() {
        Path main = backendRoot().resolve("src/main/java");
        assertThat(javaFiles(main))
            .as("src/main/java 下 .java 文件数（覆盖量自检；远小于真实值即说明扫错目录）")
            .hasSizeGreaterThan(500);
        assertThat(rawTextHits(main, SCANNER_ANCHOR))
            .as("锚 %s 必然大量出现在 main 里 —— 若为 0 说明扫描器/目录已失效"
                + "（此时门 1 的『零命中』是假绿）", SCANNER_ANCHOR)
            .isNotEmpty();
    }

    // ──────────────────────────── 扫描器 ────────────────────────────

    /**
     * {@code srcRoot} 下所有 .java 的<b>原始文本</b>命中（⛔ 刻意不剥注释：门 1 要查的正是
     * 「任何形式的提及，含注释」）。
     */
    private static List<Hit> rawTextHits(Path srcRoot, String token) {
        if (!Files.isDirectory(srcRoot)) {
            throw new IllegalStateException("扫描根不存在（fail loud：不许静默空扫）: " + srcRoot);
        }
        Set<String> seen = new TreeSet<>();
        List<Hit> hits = new ArrayList<>();
        for (Path f : javaFiles(srcRoot)) {
            String[] lines = readText(f).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(token)) {
                    String where = relativize(f) + ":" + (i + 1);
                    if (seen.add(where)) {
                        hits.add(new Hit(where, lines[i]));
                    }
                }
            }
        }
        return hits;
    }

    private static List<Path> javaFiles(Path srcRoot) {
        if (!Files.isDirectory(srcRoot)) {
            throw new IllegalStateException("扫描根不存在（fail loud：不许静默空扫）: " + srcRoot);
        }
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("扫描失败: " + srcRoot, e);
        }
    }

    /** 相对键：从 {@code src/main/java} 段之后起算，前缀固定为 {@code src/main/java}。 */
    private static String relativize(Path f) {
        String unix = f.toString().replace('\\', '/');
        int idx = unix.lastIndexOf("/src/main/java/");
        if (idx >= 0) {
            return "src/main/java/" + unix.substring(idx + "/src/main/java/".length());
        }
        Path parent = f.getParent();
        return (parent != null && parent.getFileName() != null ? parent.getFileName() + "/" : "")
            + f.getFileName();
    }

    private static String readText(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new UncheckedIOException("读取源码失败: " + f, e);
        }
    }

    /**
     * backend 根：从 <b>code source 位置</b>上溯（不依赖 cwd · 抄
     * {@code DeadSymbolReferenceGuardTest#backendRoot()} 手法）。
     *
     * <p>⛔ 刻意不回落 cwd —— 回落会静默扫错目录，属「不许静默失效」禁止项。
     */
    private static Path backendRoot() {
        Path anchor = null;
        try {
            anchor = Path.of(AgentContextAmbientReadInventoryTest.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | RuntimeException ignored) {
            // 落到下面的 fail loud 分支
        }
        for (Path p = anchor; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("src/main/java"))) {
                return p;
            }
        }
        throw new IllegalStateException(
            "无法从 code source [" + anchor + "] 上溯定位 backend 根（须含 src/main/java）。"
                + "⛔ 本护栏刻意不回落 cwd —— 回落会静默扫错目录，属『不许静默失效』禁止项。");
    }
}
