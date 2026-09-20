package com.nexusai.application.agent;

import com.nexusai.application.agent.subagent.AgentContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>[S1-T7 · 链 B / B2b] {@code RunRequest.agentContext} 显式通道</b>。
 *
 * <h2>WHY 必须先有本通道（T7 不可施工的根因）</h2>
 * <p>设计原稿要求 {@code buildBaseToolUseContext} 的 agentContext「从上游显式传入」，
 * 但 {@code LlmAgentLoop.doRun(RunRequest)} 的入参只有 {@link RunRequest}，
 * 而 {@code RunRequest} 原本<b>既无 {@code agentContext} 也无 {@code toolUseContext} 组件</b>
 * ⇒ 「显式传入」<b>没有承载体</b>（实测：{@code grep -n "ToolUseContext" RunRequest.java}
 * 仅命中 javadoc）。本类把「承载体存在且语义正确」钉死。
 *
 * <h2>断言构成（★ = 行为断言，○ = 源码扫描断言）</h2>
 * <ul>
 *   <li>★ {@link #component_exists_asLastComponent()} —— record 组件 {@code agentContext} 存在且在第
 *       20 位（在 {@code boundProject} 之后；[批 A4e] 末位追加 {@code permissionModeSource} 后仍为
 *       第 20 位）：保证各 canonical {@code new RunRequest(...)} 只需<b>追加</b>一个实参即可编译，
 *       而不是插入式改签名（插入会大面积破坏既有实参位置）。</li>
 *   <li>★ {@link #withAgentContext_sameValueShortCircuitsToThisInstance()} ——
 *       {@code withAgentContext(同一实例)} / {@code (null)} 返回 {@code this}（引用相等）。
 *       这是 sparse-edge 语义（{@code invocationEmitted} 同一 {@code AtomicBoolean}「只发一次」）
 *       的<b>前置闩锁</b>：若每次 with 都新建 record，同一次 invocation 会被多个实例承载
 *       ⇒ 发射 N 条边（偏离 CC agentContext.ts:159-173）。</li>
 *   <li>★ {@link #withAgentContext_carriesExactInstance()} —— 非空且不同实例时，副本携带的
 *       <b>就是同一个引用</b>（⛔ 不许 new SubagentContext(...) 重建）。</li>
 *   <li>★ {@link #withBoundProject_preservesAgentContext()} —— 「加字段不改语义」：
 *       {@code withBoundProject} 必须透传 agentContext（本仓已多次出现「新增副本方法漏透传字段」）。</li>
 *   <li>○ {@link #llmAgentLoop_wiringPoints_areExplicit()} —— 两个接线点的源码文本
 *       （{@code buildBaseToolUseContext(..., params.agentContext())} 与
 *       {@code params.toolUseContext().agentContext()}）。<b>这是源码扫描，不是行为测试</b>。</li>
 *   <li>○ {@link #allCanonicalConstructors_passAgentContext()} —— {@code RunRequest.java} 内
 *       {@code new RunRequest(} 调用点数 == 18（canonical 构造点全部补齐；少一处会编译不过，
 *       多一处说明新加了工厂而未同步本计数 —— 两者都必须人工确认）。</li>
 * </ul>
 */
class RunRequestAgentContextChannelTest {

    // ──────────────────────────── ★ 行为断言 ────────────────────────────

    @Test
    @DisplayName("★ RunRequest 组件 agentContext 存在，且为第 20 位（[A4e] permissionModeSource 末位追加后仍紧随 boundProject）")
    void component_exists_asLastComponent() {
        var components = RunRequest.class.getRecordComponents();
        assertThat(components)
            .as("RunRequest 组件数（S1-T7 新增 agentContext 后为 20；批 A4e 末位追加 permissionModeSource 后为 21）")
            .hasSize(21);
        // [批 A4e] 末位现为 permissionModeSource（同样的「末位追加」约定，避免插入式改签名）
        var last = components[components.length - 1];
        assertThat(last.getName())
            .as("[批 A4e] permissionModeSource 必须末位追加（同 agentContext 的约定）")
            .isEqualTo("permissionModeSource");
        assertThat(last.getType())
            .isEqualTo(com.nexusai.application.agent.permission.PermissionModeSource.class);
        // agentContext 仍在第 20 位、紧随 boundProject（本批未插入式改动既有实参位置）
        assertThat(components[components.length - 2].getName())
            .as("agentContext 必须仍紧随 boundProject（agentContext 未被插入式改动）")
            .isEqualTo("agentContext");
        assertThat(components[components.length - 3].getName())
            .as("boundProject 必须仍紧随 batchUserPrompts")
            .isEqualTo("boundProject");
        assertThat(components[components.length - 2].getType()).isEqualTo(AgentContext.class);
    }

    @Test
    @DisplayName("★ withAgentContext(null) / (同一实例) 返回 this（sparse-edge 同实例闩锁）")
    void withAgentContext_sameValueShortCircuitsToThisInstance() {
        RunRequest base = RunRequest.forTest("hi", "m", null);

        assertThat(base.withAgentContext(null))
            .as("null = 无归因上下文 ⇒ 不得新建实例（主线程路径零新实例、零行为变化）")
            .isSameAs(base);

        AgentContext ctx = new AgentContext.SubagentContext(
            "a0123456789abcdef", "sess-1", "Explore", true, "req-1", "spawn");
        RunRequest stamped = base.withAgentContext(ctx);
        assertThat(stamped).as("首次盖章必然产生新实例").isNotSameAs(base);
        assertThat(stamped.withAgentContext(ctx))
            .as("同值短路：已携带同一实例时不得再新建（否则 sparse-edge 的 invocationEmitted "
                + "会被复制成多个 AtomicBoolean ⇒ 同一次 invocation 发射多条边）")
            .isSameAs(stamped);
    }

    @Test
    @DisplayName("★ withAgentContext 携带的是同一引用（⛔ 不许重建 SubagentContext）")
    void withAgentContext_carriesExactInstance() {
        AgentContext ctx = new AgentContext.SubagentContext(
            "a0123456789abcdef", "sess-1", "Explore", true, "req-1", "spawn");
        RunRequest r = RunRequest.forTest("hi", "m", null).withAgentContext(ctx);

        assertThat(r.agentContext())
            .as("必须是同一引用 —— invocationEmitted（AtomicBoolean）的『只发一次』靠同一实例承载")
            .isSameAs(ctx);
    }

    @Test
    @DisplayName("★ withBoundProject 透传 agentContext（副本方法不得丢新字段）")
    void withBoundProject_preservesAgentContext() {
        AgentContext ctx = new AgentContext.SubagentContext(
            "a0123456789abcdef", "sess-1", "Explore", true, "req-1", "spawn");
        RunRequest r = RunRequest.forTest("hi", "m", null)
            .withAgentContext(ctx)
            .withBoundProject("/tmp/proj");

        assertThat(r.boundProject()).isEqualTo("/tmp/proj");
        assertThat(r.agentContext()).as("withBoundProject 必须透传 agentContext").isSameAs(ctx);
    }

    // ──────────────────────────── ○ 源码扫描断言 ────────────────────────────

    @Test
    @DisplayName("○（源码扫描）LlmAgentLoop 两个接线点显式：doRun→baseTuc 传参 · loop()→TUC 字段")
    void llmAgentLoop_wiringPoints_areExplicit() {
        List<String> code = codeLines(backendRoot().resolve(
            "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java"));

        assertThat(code)
            .as("接线点 1：doRun 构造 base TUC 时必须把 RunRequest.agentContext() 作为实参显式传入"
                + "（原为 buildBaseToolUseContext 内部读 AgentContext.getAgentContext() ambient）")
            .anyMatch(l -> l.replaceAll("\\s+", " ").contains("params.agentContext());"));

        assertThat(code)
            .as("接线点 2：loop() 内的模型请求必须从 base TUC 的显式字段取（单一来源）"
                + "（原为 AgentContext.getAgentContext() —— 绕过 TUC 的第二套平行载体）")
            .anyMatch(l -> l.replaceAll("\\s+", " ").contains("params.toolUseContext().agentContext()"));
    }

    @Test
    @DisplayName("○（源码扫描）canonical new RunRequest( 调用点 == 18（原 17 + A4e sessionFromSessionOverride 1）")
    void allCanonicalConstructors_passAgentContext() {
        Path f = backendRoot().resolve("src/main/java/com/nexusai/application/agent/RunRequest.java");
        String src = readText(f);
        List<String> code = codeLines(f);

        // ── 1. 构造点计数 ──
        int canonical = 0;
        for (String l : code) {
            canonical += countOccurrences(l, "new RunRequest(");
        }
        assertThat(canonical)
            .as("canonical 构造点数 = S1-T7 后的 17 处 **+ 1 处**（批 A4e 新增的 "
                + "sessionFromSessionOverride 工厂 = 会话 override 槽专用来源标注）= 18。"
                + "⚠️ 少一处会编译不过；多/少一处都说明工厂集变了 —— 必须人工确认后同步本计数"
                + "（本断言刻意对计数敏感：它是「构造点是否全补齐」的机械落点）")
            .isEqualTo(18);

        // ── 2. 每个构造点都必须是 21 个顶层实参（= 组件数）──
        List<Integer> arities = canonicalArities(src, "new RunRequest(");
        assertThat(arities)
            .as("每个 canonical new RunRequest(...) 的顶层实参数（应恒为 21 = record 组件数）；"
                + "若某处少一个 ⇒ 说明新增组件时漏补该构造点")
            .hasSize(18)
            .allSatisfy(a -> assertThat(a).isEqualTo(21));

        // ── 3. 两处副本方法必须**透传** agentContext（而不是补 null）──
        //    [批 A4e] 组件 permissionModeSource 末位追加后，两处副本方法的实参表尾变成
        //    `, agentContext, permissionModeSource);` —— 用「尾两参连写」作判据，
        //    同时证明「agentContext 未漏」且「新组件未漏」（任一被补成 null ⇒ 计数 ≠ 2 ⇒ 红）。
        assertThat(countOccurrences(src, ", agentContext, permissionModeSource);"))
            .as("withBoundProject / withAgentContext 两处副本方法必须**同时**透传 agentContext 与"
                + " permissionModeSource（本仓多次出现「新增副本方法漏透传字段」；批 A4e 新增组件同款风险）")
            .isEqualTo(2);

        // ── 4. [批 A4e] 两处副本方法必须**透传** permissionModeSource（同款「漏透传新字段」风险）──
        assertThat(countOccurrences(src, ", permissionModeSource);"))
            .as("withBoundProject / withAgentContext 两处副本方法都必须透传 permissionModeSource"
                + "（批 A4e 新增组件；漏透传 ⇒ 副本会把来源标注静默丢掉）")
            .isEqualTo(2);
    }

    /**
     * 抽取所有 {@code needle} 调用点的<b>顶层实参数</b>（只数深度 0 的逗号 + 1）。
     * 引号内与括号内不计 —— 用最小状态机而非正则以避免「嵌套括号贪婪回溯」类假绿。
     */
    private static List<Integer> canonicalArities(String src, String needle) {
        List<Integer> out = new ArrayList<>();
        int i = 0;
        while (true) {
            int j = src.indexOf(needle, i);
            if (j < 0) {
                break;
            }
            int k = j + needle.length();
            int depth = 1;
            int commas = 0;
            while (depth > 0) {
                char c = src.charAt(k);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == '"') {
                    k++;
                    while (src.charAt(k) != '"') {
                        if (src.charAt(k) == '\\') {
                            k++;
                        }
                        k++;
                    }
                } else if (c == ',' && depth == 1) {
                    commas++;
                }
                k++;
            }
            out.add(commas + 1);
            i = k;
        }
        return out;
    }

    // ──────────────────────────── helper ────────────────────────────

    private static List<String> codeLines(Path f) {
        List<String> out = new ArrayList<>();
        boolean inBlock = false;
        for (String raw : readText(f).split("\n", -1)) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < raw.length()) {
                if (inBlock) {
                    int end = raw.indexOf("*/", i);
                    if (end < 0) {
                        i = raw.length();
                    } else {
                        inBlock = false;
                        i = end + 2;
                    }
                    continue;
                }
                if (raw.startsWith("//", i)) {
                    break;
                }
                if (raw.startsWith("/*", i)) {
                    inBlock = true;
                    i += 2;
                    continue;
                }
                sb.append(raw.charAt(i));
                i++;
            }
            out.add(sb.toString().replaceAll("\\s+", " ").trim());
        }
        return out;
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static String readText(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new UncheckedIOException("读取源码失败: " + f, e);
        }
    }

    private static Path backendRoot() {
        Path anchor = null;
        try {
            anchor = Path.of(RunRequestAgentContextChannelTest.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | RuntimeException ignored) {
            // fail loud below
        }
        for (Path p = anchor; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("src/main/java"))) {
                return p;
            }
        }
        throw new IllegalStateException("无法从 code source [" + anchor + "] 上溯定位 backend 根。");
    }
}
