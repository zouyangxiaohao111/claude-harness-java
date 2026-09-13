package com.nexusai.application.agent.tool.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.ForkSubagent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [SEC-FAIL-LOUD + R7 单点判据] 两处 {@code resolvePermissionMode} 的解析语义测试。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：{@code agentDefinition.permissionMode()} 是**未校验的原始
 * String**（{@code AgentDefinition.java:35 Optional<String>}），markdown 加载路径
 * {@code loadAgentsDir.java:610-612} 直接 {@code pmObj.toString()} 落库、不做任何校验。
 * 故这两处 resolver 是 agent 声明值的唯一解释点，解释错了就是「声明了却不生效」。
 *
 * <p><b>三件事必须同时成立，缺一即是线上事故</b>：
 * <ol>
 *   <li><b>CC 合法串必须真生效</b>（核心断言）：旧实现 {@code PermissionMode.valueOf(mode.toUpperCase())}
 *       因枚举名带下划线认不出 {@code acceptEdits} / {@code bypassPermissions} / {@code dontAsk}
 *       → 三个**合法**串静默折叠 DEFAULT（同一能力两套判据 = R7）。CC 真源
 *       {@code permissionModeFromString}（PermissionMode.ts:112-116）对这三个串分别返回
 *       acceptEdits / bypassPermissions / dontAsk。</li>
 *   <li><b>真非法串必须留痕</b>：{@code catch → return DEFAULT} 零日志时，「为什么这个 agent 的
 *       permissionMode 没生效」在线上无迹可查（用户铁律：默认吞掉异常对后期修复不友好）。</li>
 *   <li><b>'bubble' 不能被折叠</b>：fork agent 定义程序化赋 {@code permissionMode='bubble'}
 *       （{@code ForkSubagentAgentDefinition}），CC 消费侧按原值用（runAgent.ts:424/443）；
 *       Java 只能靠这两处 resolver 还原。若照搬 CC 的「可寻址集合不含 bubble」而直接丢弃，
 *       fork 子 agent 冒泡失效（{@code resolveShouldAvoidPermissionPrompts} /
 *       {@code resolveEffectiveForkMode} 依赖 BUBBLE）—— 这是本次换判据最大的回归风险。</li>
 * </ol>
 *
 * <p><b>两个落点都要测</b>：SubagentExecutor 的实例方法与 SubagentTool 的私有静态方法是两份
 * 同语义拷贝（SubagentTool javadoc 自述「同一语义的第二拷贝」）——只测一处会让另一处静默
 * 回退无人发现（本仓「声称守护 X 实际只覆盖一侧」失效模式）。
 */
@DisplayName("[SEC-FAIL-LOUD/R7] resolvePermissionMode: 合法串真生效 + 真非法串 WARN + bubble 不丢")
class ResolvePermissionModeFailLoudTest {

    /** 既非 CC 合法串、也非 'bubble' —— 保证落入"真非法"分支。 */
    private static final String ILLEGAL_MODE = "totallyBogusMode";

    private Logger executorLogger;
    private Logger toolLogger;
    private ListAppender<ILoggingEvent> executorAppender;
    private ListAppender<ILoggingEvent> toolAppender;

    @BeforeEach
    void attachAppenders() {
        executorLogger = (Logger) LoggerFactory.getLogger(SubagentExecutor.class);
        toolLogger = (Logger) LoggerFactory.getLogger(SubagentTool.class);
        executorAppender = new ListAppender<>();
        toolAppender = new ListAppender<>();
        executorAppender.start();
        toolAppender.start();
        executorLogger.addAppender(executorAppender);
        toolLogger.addAppender(toolAppender);
        executorLogger.setLevel(Level.WARN);
        toolLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void detachAppenders() {
        executorLogger.detachAppender(executorAppender);
        toolLogger.detachAppender(toolAppender);
        executorAppender.stop();
        toolAppender.stop();
    }

    private static AgentDefinition agentWith(String permissionMode) {
        return AgentDefinition.BuiltInAgentDefinition.builder(
                "test-agent", "when to use", (ctx, dirs) -> "system prompt")
            .permissionMode(permissionMode)
            .build();
    }

    /** SubagentExecutor.resolvePermissionMode 是实例方法 · 其余构造参数本用例不用 → null。 */
    private static SubagentExecutor executor() {
        return new SubagentExecutor(null, null, null, null, null, "model", "system-prompt");
    }

    /** SubagentTool.resolvePermissionMode 是 private static → 反射调用（本仓既有惯例）。 */
    private static PermissionMode viaSubagentTool(AgentDefinition def) throws Exception {
        Method m = SubagentTool.class.getDeclaredMethod("resolvePermissionMode", AgentDefinition.class);
        m.setAccessible(true);
        return (PermissionMode) m.invoke(null, def);
    }

    private static List<String> warnMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
            .filter(ev -> ev.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    // ─────────────── ① 核心：CC 合法串必须真生效（两落点都断言）───────────────

    @Test
    @DisplayName("SubagentExecutor: 5 个 CC external 串 + auto 解析为各自真实模式（旧实现折叠 3 个）")
    void executor_ccLegalModes_resolveToRealModes() {
        SubagentExecutor ex = executor();

        // 旧实现能过的两个（枚举名恰为大写化后同名）——保留作对照，证明本用例不是靠"全改"通过
        assertThat(ex.resolvePermissionMode(agentWith("default")))
            .as("'default' → DEFAULT").isEqualTo(PermissionMode.DEFAULT);
        assertThat(ex.resolvePermissionMode(agentWith("plan")))
            .as("'plan' → PLAN（ExitPlanMode 放行分支依赖）").isEqualTo(PermissionMode.PLAN);

        // 旧实现静默折叠为 DEFAULT 的三个 —— 本修复的核心价值（权限放宽，用户 2026-09-13 已裁定接受）
        assertThat(ex.resolvePermissionMode(agentWith("acceptEdits")))
            .as("'acceptEdits' → ACCEPT_EDITS（CC EXTERNAL_PERMISSION_MODES 成员；"
                + "旧实现 valueOf(\"ACCEPTEDITS\") 抛 IAE → 折叠 DEFAULT）")
            .isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(ex.resolvePermissionMode(agentWith("bypassPermissions")))
            .as("'bypassPermissions' → BYPASS_PERMISSIONS（旧实现折叠 DEFAULT）")
            .isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(ex.resolvePermissionMode(agentWith("dontAsk")))
            .as("'dontAsk' → DONT_ASK（旧实现折叠 DEFAULT）")
            .isEqualTo(PermissionMode.DONT_ASK);

        // 'auto'：CC 运行时集合成员（types/permissions.ts:34-41）；nexusai loadAgentsDir 的
        // agent schema 只收 5 个 external（不含 auto）而 markdown 路径不校验 → 此处按 CC 语义解析。
        assertThat(ex.resolvePermissionMode(agentWith("auto")))
            .as("'auto' → AUTO（CC INTERNAL_PERMISSION_MODES 成员）").isEqualTo(PermissionMode.AUTO);

        assertThat(warnMessages(executorAppender))
            .as("合法串不得产生 WARN —— 否则 WARN 变成噪音，真非法时失去信号价值")
            .isEmpty();
    }

    @Test
    @DisplayName("SubagentTool: 同一组合法串解析结果必须与 SubagentExecutor 一致（第二拷贝不得漂移）")
    void subagentTool_ccLegalModes_resolveToRealModes() throws Exception {
        assertThat(viaSubagentTool(agentWith("default"))).isEqualTo(PermissionMode.DEFAULT);
        assertThat(viaSubagentTool(agentWith("plan"))).isEqualTo(PermissionMode.PLAN);
        assertThat(viaSubagentTool(agentWith("acceptEdits")))
            .as("第二拷贝同样必须解析 acceptEdits → ACCEPT_EDITS")
            .isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(viaSubagentTool(agentWith("bypassPermissions")))
            .as("第二拷贝同样必须解析 bypassPermissions → BYPASS_PERMISSIONS")
            .isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
        assertThat(viaSubagentTool(agentWith("dontAsk")))
            .as("第二拷贝同样必须解析 dontAsk → DONT_ASK")
            .isEqualTo(PermissionMode.DONT_ASK);
        assertThat(viaSubagentTool(agentWith("auto"))).isEqualTo(PermissionMode.AUTO);

        assertThat(warnMessages(toolAppender))
            .as("合法串不得产生 WARN")
            .isEmpty();
    }

    // ─────────────── ② 大小写敏感（对齐 CC 精确 includes）───────────────

    @Test
    @DisplayName("大小写敏感：'PLAN'/'AcceptEdits' 不是 CC 串 → DEFAULT + WARN（对齐 CC 精确匹配）")
    void caseSensitivity_isCcExactMatch() {
        // WHY: CC permissionModeFromString 用 `PERMISSION_MODES.includes(str)` —— 精确、大小写敏感
        // （PermissionMode.test.ts:56-58 明确断言 'PLAN'/'Default' → 'default'）。旧实现的
        // toUpperCase() 反而**放宽**了匹配；换成 fromString 后 'PLAN' 属真非法串，必须留痕。
        SubagentExecutor ex = executor();
        assertThat(ex.resolvePermissionMode(agentWith("PLAN")))
            .as("'PLAN' 非 CC 串（CC 大小写敏感）→ DEFAULT").isEqualTo(PermissionMode.DEFAULT);
        assertThat(ex.resolvePermissionMode(agentWith("AcceptEdits")))
            .as("'AcceptEdits' 非 CC 串 → DEFAULT").isEqualTo(PermissionMode.DEFAULT);

        assertThat(warnMessages(executorAppender))
            .as("大小写不符属真非法串，必须 WARN 留痕（写明原始值）")
            .hasSize(2)
            .allMatch(msg -> msg.contains("PLAN") || msg.contains("AcceptEdits"));
    }

    // ─────────────── ③ fork 'bubble' 不得被折叠（本次换判据最大回归风险）───────────────

    @Test
    @DisplayName("SubagentExecutor: fork 的 'bubble' → BUBBLE 且零 WARN（CC 靠类型直传，Java 靠此处还原）")
    void executor_forkBubble_preserved() {
        // WHY: ForkSubagentAgentDefinition 程序化设 permissionMode="bubble"（ForkSubagent.PERMISSION_MODE）。
        // CC 消费侧按原值比较（runAgent.ts:443 `agentPermissionMode === 'bubble'`），类型即 PermissionMode
        // 故无需解析；Java 的 AgentDefinition.permissionMode() 是 String，只能靠这两处 resolver 还原。
        // 若照搬"CC 可寻址集合不含 bubble"而丢弃 → resolveShouldAvoidPermissionPrompts(BUBBLE,...)=false
        // 失效 → 异步 fork 子 agent 的权限弹窗被自动拒绝、不再冒泡到父终端。
        assertThat(executor().resolvePermissionMode(agentWith(ForkSubagent.PERMISSION_MODE)))
            .as("fork 'bubble' 必须解析为 BUBBLE（否则 fork 冒泡链路断裂）")
            .isEqualTo(PermissionMode.BUBBLE);
        assertThat(warnMessages(executorAppender))
            .as("'bubble' 是已知的 fork 内部模式，不是配置错误 → 不得 WARN")
            .isEmpty();
    }

    @Test
    @DisplayName("SubagentTool: 'bubble' 同样 → BUBBLE 且零 WARN（第二拷贝不得漏映射）")
    void subagentTool_forkBubble_preserved() throws Exception {
        assertThat(viaSubagentTool(agentWith(ForkSubagent.PERMISSION_MODE)))
            .as("第二拷贝同样必须保留 'bubble' → BUBBLE")
            .isEqualTo(PermissionMode.BUBBLE);
        assertThat(warnMessages(toolAppender)).isEmpty();
    }

    // ─────────────── ④ 真非法串 → DEFAULT + WARN（写明原始值）───────────────

    @Test
    @DisplayName("SubagentExecutor: 真非法串 → DEFAULT，且 WARN 写明原始非法值")
    void executor_illegalMode_degradesLoudly() {
        PermissionMode resolved = executor().resolvePermissionMode(agentWith(ILLEGAL_MODE));

        assertThat(resolved)
            .as("真非法串保留降级为 DEFAULT（CC PermissionMode.ts:112-116 同向），但不得静默")
            .isEqualTo(PermissionMode.DEFAULT);

        // 意图断言：日志必须能回答「哪个 agent 的哪个非法值被折叠了」——这是本修复的全部价值。
        assertThat(warnMessages(executorAppender))
            .as("必须 WARN 留痕且含 agentType + 原始非法值")
            .anyMatch(msg -> msg.contains("test-agent") && msg.contains(ILLEGAL_MODE));
    }

    @Test
    @DisplayName("SubagentTool: 真非法串 → DEFAULT + WARN（第二拷贝不得漏改）")
    void subagentTool_illegalMode_degradesLoudly() throws Exception {
        assertThat(viaSubagentTool(agentWith(ILLEGAL_MODE))).isEqualTo(PermissionMode.DEFAULT);
        assertThat(warnMessages(toolAppender))
            .as("第二拷贝必须同样 WARN 留痕——只修 SubagentExecutor 会让本处继续静默吞值")
            .anyMatch(msg -> msg.contains("test-agent") && msg.contains(ILLEGAL_MODE));
    }

    // ─────────────── ⑤ 未声明 = 正常缺省，不是错误 ───────────────

    @Test
    @DisplayName("SubagentExecutor: 未声明 permissionMode → DEFAULT 且零 WARN（缺省不是错误）")
    void executor_absentMode_noWarn() {
        // WHY: 「没有声明」与「声明了非法值」是两回事。前者是正常缺省（CC optional），
        // 若也发 WARN 就是假警报——把两类情形混同正是「静默兜底」修复最容易犯的过头错误。
        assertThat(executor().resolvePermissionMode(agentWith(null)))
            .isEqualTo(PermissionMode.DEFAULT);
        assertThat(warnMessages(executorAppender))
            .as("未声明 permissionMode 是正常缺省，不得 WARN")
            .isEmpty();
    }
}
