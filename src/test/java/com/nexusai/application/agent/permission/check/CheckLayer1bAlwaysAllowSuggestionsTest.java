package com.nexusai.application.agent.permission.check;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.McpServerInfo;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [批 A4d · P2 甲-2] 1b whole-tool ask 命中时「始终允许」档的工具守卫 —— 严格对齐 CC。
 *
 * <h2>被钉住的 CC 事实（逐字）</h2>
 * <ol>
 *   <li>CC 1b whole-tool ask 命中返回体<b>不带</b> {@code suggestions}：
 *       {@code utils/permissions/permissions.ts:1216-1225}
 *       <pre>if (!canSandboxAutoAllow) { return { behavior: 'ask', decisionReason: ..., message: ... } }</pre></li>
 *   <li><b>Bash</b> 弹窗的 always-allow 档要求 suggestions 非空：
 *       {@code components/permissions/BashPermissionRequest/bashToolUseOptions.tsx:105}
 *       <pre>} else if (suggestions.length &gt; 0) {</pre>
 *       （:92 的 editable-prefix 分支条件亦含 {@code suggestions.length > 0}）</li>
 *   <li><b>PowerShell</b> 与 Bash <b>同构</b>：
 *       {@code components/permissions/PowerShellPermissionRequest/powershellToolUseOptions.tsx:52}
 *       <pre>if (shouldShowAlwaysAllowOptions() &amp;&amp; suggestions.length &gt; 0) {</pre>
 *       ⚠️ 只写死 "Bash" 会漏掉本行 —— 本仓反复栽的「只覆盖一侧」。</li>
 *   <li>其余工具<b>不</b>依赖 suggestions（只受 {@code shouldShowAlwaysAllowOptions()} 门控，
 *       {@code utils/permissions/permissionsLoader.ts:42-44}）：
 *       Fallback {@code FallbackPermissionRequest.tsx:118/:128}（MCP + 未登记/默认工具）、
 *       Skill {@code SkillPermissionRequest.tsx:63}、
 *       WebFetch {@code WebFetchPermissionRequest.tsx:54}、
 *       Monitor {@code MonitorPermissionRequest.tsx:44}；
 *       文件类（Edit/Write/NotebookEdit/Glob/Grep/Read）档位来自固定选项表
 *       {@code FilePermissionDialog/permissionOptions.tsx:77-179}，与 suggestions 无关。</li>
 * </ol>
 *
 * <p><b>⇒ 甲-2</b>：本层对 {@link ToolNameConstants#ALWAYS_ALLOW_REQUIRES_SUGGESTIONS}
 * 里的工具<b>不产</b> suggestions（前端由此不渲染第三档），其余工具<b>照旧产</b>。
 *
 * <p><b>WHY 需要本测试</b>：前端 {@code PermissionBubble} 仅在
 * {@code suggestions} 非空时渲染第三档（{@code permissionSuggestionLabels.ts:253} 判空），
 * 所以「后端产不产 suggestions」就是「第三档出不出」的唯一开关 —— 本测试即钉死这个开关的
 * 工具边界，防三个方向的回归：① 把 Bash 写成唯一守卫（漏 PowerShell）；② 守卫扩散到
 * Fallback 类工具（把 CC 本来就有的档砍掉）；③ **抑制路径的留痕被降级/删除**
 * （见 §6：项目红线「本就不需要 ⇒ 可跳过但 ≥WARN，⛔ 禁只 DEBUG」—— 留痕若只到 DEBUG，
 * 生产 root=INFO 下等于静默失效；该用例用级别精确探针把 WARN→DEBUG 变异钉红）。
 */
@DisplayName("[批 A4d·P2 甲-2] 1b ask 命中：始终允许档的工具守卫（CC Bash/PowerShell 依赖 suggestions）")
class CheckLayer1bAlwaysAllowSuggestionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 桩工具：checkPermissions 缺省 Allow（1b 不调用它，只要 name 可指定）。 */
    private static final class StubTool implements Tool {
        private final String name;
        private final McpServerInfo mcpInfo;

        StubTool(String name) {
            this(name, null);
        }

        StubTool(String name, McpServerInfo mcpInfo) {
            this.name = name;
            this.mcpInfo = mcpInfo;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public com.nexusai.application.agent.tool.AgentToolResult<?> execute(ToolUseBlock call) {
            return null;
        }

        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public JsonNode inputSchema() {
            return JSON.createObjectNode();
        }

        @Override
        public McpServerInfo mcpInfo() {
            return mcpInfo;
        }
    }

    private static ToolPermissionContext askRuleCtx(String ruleToolName) {
        Map<PermissionRuleSource, Set<PermissionRule>> ask = new EnumMap<>(PermissionRuleSource.class);
        ask.put(PermissionRuleSource.USER_SETTINGS,
            Set.of(new PermissionRule(PermissionRuleSource.USER_SETTINGS,
                PermissionBehavior.ASK, PermissionRuleValue.wholeTool(ruleToolName))));
        return ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(), Map.of(), ask, Map.of());
    }

    private static ToolUseContext ctx(ToolPermissionContext permCtx) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-a4d", permCtx.mode(),
            List.of(), "", AbortController.NOOP, List.of(), permCtx, permCtx.mode());
    }

    /** 跑 1b 层：whole-tool ask 规则命中，返回 Ask 结果。 */
    private static PermissionResult.Ask run1b(Tool tool, ToolPermissionContext permCtx) {
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "echo hi");
        CheckLayer1b_AskRule layer = new CheckLayer1b_AskRule();
        PermissionResult r = layer.check(tool,
            new ToolUseBlock(UUID.randomUUID().toString(), tool.name(), input),
            input, ctx(permCtx), permCtx);
        assertThat(r)
            .as("sandboxManager=null ⇒ canSandboxAutoAllow 恒 false ⇒ 必为 Ask（不 fall-through）")
            .isInstanceOf(PermissionResult.Ask.class);
        return (PermissionResult.Ask) r;
    }

    // ── 留痕探针（口径与 PermissionPersistFailureFailLoudTest / LocalSettingsGitignoreTest 同一套）──
    //    ⚠️ 刻意把 logger 调到 DEBUG 再挂 appender：这样「WARN 被降级成 DEBUG」时事件**仍然被捕获**，
    //    于是 hasAtLeast(WARN) 会因级别不足而变红 —— 而不是因为「压根没日志」而变红。
    //    两种红都算抓住，但前者能把「静默降级」与「完全没打」区分开（诊断价值）。

    private static ListAppender<ILoggingEvent> attach(Class<?> target) {
        Logger logger = (Logger) LoggerFactory.getLogger(target);
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(Class<?> target, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(target)).detachAppender(appender);
        appender.stop();
    }

    private static boolean hasAtLeast(ListAppender<ILoggingEvent> appender, Level level, String needle) {
        return appender.list.stream()
            .anyMatch(e -> e.getLevel().isGreaterOrEqual(level) && e.getFormattedMessage().contains(needle));
    }

    /** 诊断用：命中 needle 的日志级别列表（红时把 `[DEBUG]` 直接摆在报告里）。 */
    private static List<Level> levelsMentioning(ListAppender<ILoggingEvent> appender, String needle) {
        return appender.list.stream()
            .filter(e -> e.getFormattedMessage().contains(needle))
            .map(ILoggingEvent::getLevel)
            .toList();
    }

    // ───────────────────────── 1. 集合本身钉死（防漏 PowerShell / 防扩散）─────────────────────────

    @Test
    @DisplayName("守卫集合逐字 = {Bash, PowerShell}（CC 真源推导 · 多一个少一个都红）")
    void alwaysAllowRequiresSuggestions_isExactlyBashAndPowerShell() {
        assertThat(ToolNameConstants.ALWAYS_ALLOW_REQUIRES_SUGGESTIONS)
            .as("CC bashToolUseOptions.tsx:105 + powershellToolUseOptions.tsx:52 —— 只有这两个弹窗"
                + "的 always-allow 档被 suggestions.length > 0 把守；⛔ 漏 PowerShell = 只覆盖一侧")
            .containsExactlyInAnyOrder(
                ToolNameConstants.BASH_TOOL_NAME,
                ToolNameConstants.POWER_SHELL_TOOL_NAME);
        assertThat(ToolNameConstants.ALWAYS_ALLOW_REQUIRES_SUGGESTIONS)
            .as("PowerShell 必须在内（与 Bash 同构，powershellToolUseOptions.tsx:52）")
            .contains(ToolNameConstants.POWER_SHELL_TOOL_NAME);
    }

    // ───────────────────────── 2. 抑制侧：Bash / PowerShell ─────────────────────────

    @Test
    @DisplayName("Bash 命中 whole-tool ask ⇒ suggestions 为空（CC bashToolUseOptions.tsx:105）")
    void bashAskRuleHit_producesNoSuggestions() {
        PermissionResult.Ask ask = run1b(
            new StubTool(ToolNameConstants.BASH_TOOL_NAME),
            askRuleCtx(ToolNameConstants.BASH_TOOL_NAME));

        assertThat(ask.suggestions())
            .as("CC 1b ask 命中返回体不带 suggestions（permissions.ts:1216-1225）"
                + "且 Bash 档条件为 suggestions.length > 0 ⇒ 必须为空，前端不渲染第三档")
            .isEmpty();
    }

    @Test
    @DisplayName("PowerShell 命中 whole-tool ask ⇒ suggestions 为空（与 Bash 同构 · tsx:52）")
    void powerShellAskRuleHit_producesNoSuggestions() {
        PermissionResult.Ask ask = run1b(
            new StubTool(ToolNameConstants.POWER_SHELL_TOOL_NAME),
            askRuleCtx(ToolNameConstants.POWER_SHELL_TOOL_NAME));

        assertThat(ask.suggestions())
            .as("powershellToolUseOptions.tsx:52 与 Bash :105 同构 ⇒ 必须同样为空"
                + "（本用例就是「只覆盖一侧」的回归网）")
            .isEmpty();
    }

    // ───────────────────────── 3. 保留侧：Fallback 类（未登记工具 / MCP）─────────────────────────

    @Test
    @DisplayName("未登记工具（Fallback 类）命中同样 ask ⇒ 仍有 whole-tool allow suggestions")
    void unregisteredToolAskRuleHit_stillProducesSuggestions() {
        String toolName = "SomeUnregisteredTool";
        PermissionResult.Ask ask = run1b(new StubTool(toolName), askRuleCtx(toolName));

        assertThat(ask.suggestions())
            .as("FallbackPermissionRequest.tsx:118/:128 该档只受 shouldShowAlwaysAllowOptions() 门控"
                + "（签名里无 suggestions 参数）⇒ 本层须照旧产，否则砍掉 CC 本来就有的档")
            .hasSize(1);
        assertThat(ask.suggestions().get(0))
            .isInstanceOf(PermissionUpdate.AddRules.class);
        PermissionUpdate.AddRules addRules = (PermissionUpdate.AddRules) ask.suggestions().get(0);
        assertThat(addRules.destination())
            .as("落盘目标仍是 USER_SETTINGS（本批不改 destination）")
            .isEqualTo(PermissionUpdate.Destination.USER_SETTINGS);
        assertThat(addRules.rules())
            .as("whole-tool allow 规则，toolName = 该工具")
            .hasSize(1)
            .allSatisfy(r -> {
                assertThat(r.ruleBehavior()).isEqualTo(PermissionBehavior.ALLOW);
                assertThat(r.ruleValue().toolName()).isEqualTo(toolName);
                assertThat(r.ruleValue().ruleContent()).as("whole-tool（无 ruleContent）").isNull();
            });
    }

    @Test
    @DisplayName("MCP 工具（Fallback 类）命中 whole-server ask ⇒ 仍有 suggestions")
    void mcpToolAskRuleHit_stillProducesSuggestions() {
        String mcpFullName = "mcp__srv__DoThing";
        StubTool mcpTool = new StubTool("DoThing", new McpServerInfo("srv", "DoThing"));
        assertThat(RuleQuery.getToolNameForPermissionCheck(mcpTool))
            .as("前置：MCP 工具在 rule 匹配里的身份是全名")
            .isEqualTo(mcpFullName);

        PermissionResult.Ask ask = run1b(mcpTool, askRuleCtx(mcpFullName));

        assertThat(ask.suggestions())
            .as("MCP 走 FallbackPermissionRequest ⇒ 该档不依赖 suggestions ⇒ 照旧产")
            .hasSize(1);
    }

    // ───────────────────────── 4. 保留侧：Skill / 文件类（档位来自固定选项表）─────────────────────────

    @Test
    @DisplayName("Skill / Edit / Glob / 文件类工具命中 ask ⇒ 仍有 suggestions（档位与 suggestions 无关）")
    void nonShellBuiltinTools_stillProduceSuggestions() {
        for (String toolName : List.of("Skill", "Edit", "Write", "Read", "Glob", "Grep",
                "NotebookEdit", "WebFetch", "Monitor", "Workflow")) {
            PermissionResult.Ask ask = run1b(new StubTool(toolName), askRuleCtx(toolName));
            assertThat(ask.suggestions())
                .as("CC 里 %s 的 always-allow 档不依赖 suggestions（SkillPermissionRequest.tsx:63 / "
                    + "FilePermissionDialog/permissionOptions.tsx:77-179 固定选项表 / "
                    + "WebFetchPermissionRequest.tsx:54 / MonitorPermissionRequest.tsx:44）"
                    + "⇒ 本层照旧产", toolName)
                .hasSize(1);
        }
    }

    // ───────────────────────── 5. 未命中 ask 规则 ⇒ 不产（回归：守卫不影响未命中路径）─────────────────────────

    @Test
    @DisplayName("未命中 ask 规则 ⇒ 返回 null（含 Bash · 守卫不改未命中路径）")
    void noAskRule_returnsNull_includingBash() {
        CheckLayer1b_AskRule layer = new CheckLayer1b_AskRule();
        ToolPermissionContext permCtx = ToolPermissionContext.of(
            PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "echo hi");

        for (String toolName : List.of(ToolNameConstants.BASH_TOOL_NAME,
                ToolNameConstants.POWER_SHELL_TOOL_NAME, "SomeUnregisteredTool")) {
            assertThat(layer.check(new StubTool(toolName),
                new ToolUseBlock(UUID.randomUUID().toString(), toolName, input),
                input, ctx(permCtx), permCtx))
                .as("无 ask rule ⇒ 未命中 ⇒ null（%s）", toolName)
                .isNull();
        }
    }

    // ───────────────── 6. 留痕级别：抑制必须 ≥WARN（禁只 DEBUG），且只对守卫工具触发 ─────────────────

    @Test
    @DisplayName("抑制必须 ≥WARN 留痕（禁只 DEBUG）· 且不得对非守卫工具误报")
    void suppression_isLoggedAtLeastWarn_andOnlyForGuardedTools() {
        ListAppender<ILoggingEvent> appender = attach(CheckLayer1b_AskRule.class);
        try {
            // ① 守卫命中侧：Bash 被抑制 ⇒ 必须有一条 ≥WARN 的留痕
            run1b(new StubTool(ToolNameConstants.BASH_TOOL_NAME),
                askRuleCtx(ToolNameConstants.BASH_TOOL_NAME));

            // ⛔ 关键判据：用 isGreaterOrEqual(WARN) 而不是「有日志」——
            //    生产 root=INFO ⇒ DEBUG 不可见 ⇒ **DEBUG 不算留痕**。
            //    「只断言有日志」正是「把 ≥WARN 降成 DEBUG」能溜过去的原因。
            assertThat(hasAtLeast(appender, Level.WARN, "不产 suggestions"))
                .as("项目红线「不许静默失效：本就不需要 ⇒ 可跳过但 ≥WARN（⛔ 禁只 DEBUG）」"
                    + " —— 生产 root=INFO 下 DEBUG 不落盘，等于静默。"
                    + " 实测命中「不产 suggestions」的日志级别 = %s"
                    + "（若此处为 [DEBUG] 即发生了 WARN→DEBUG 静默降级）",
                    levelsMentioning(appender, "不产 suggestions"))
                .isTrue();
            assertThat(hasAtLeast(appender, Level.WARN, ToolNameConstants.BASH_TOOL_NAME))
                .as("留痕必须点名被抑制的工具（否则运维无法定位是哪条规则被跳过）")
                .isTrue();

            // ② 非守卫侧（工具维度的反向断言）：未登记工具照旧产 suggestions ⇒ **不得**有抑制留痕
            //    （防「守卫扩散到全工具」这类过度抑制，同时防留痕变成噪声）
            int before = appender.list.size();
            PermissionResult.Ask other = run1b(new StubTool("SomeUnregisteredTool"),
                askRuleCtx("SomeUnregisteredTool"));
            assertThat(other.suggestions()).as("前置：该工具不应被抑制").hasSize(1);
            assertThat(appender.list.subList(before, appender.list.size()).stream()
                    .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                    .filter(e -> e.getFormattedMessage().contains("不产 suggestions"))
                    .count())
                .as("未登记工具照旧产 suggestions ⇒ 不得打出「不产 suggestions」的 WARN（误报即噪声）")
                .isZero();
        } finally {
            detach(CheckLayer1b_AskRule.class, appender);
        }
    }
}
