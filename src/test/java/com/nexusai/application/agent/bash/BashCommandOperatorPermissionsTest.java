package com.nexusai.application.agent.bash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BashCommandOperatorPermissions 行为回归测试 · 验证 WHY=bash 复合命令必须走 CC 安全分支.
 *
 * <p>WHY 本测试存在: 本 session 对齐 CC bashCommandHelpers.ts:208-265（checkCommandOperatorPermissions）
 * 的 5 个安全不变量，任何一个回归都会造成权限绕过或错误弹窗：
 * <ol>
 *   <li>subshell {@code (cmd)} / command group {@code { cmd; }} → ask（CC :216-240），
 *       但 {@code $(...)} / {@code <(...)} / {@code ((...))} / brace expansion 非复合 → 不得误判；</li>
 *   <li>pipe 段剥离重定向须保引号（CC :163-174），{@code echo "a>b"} 不得因 {@code >} 被截断；</li>
 *   <li>multi-cd 检查在重定向剥离之后（CC :253-256 → :31-47 时序）；</li>
 *   <li>跨段 cd+git 防裸仓库绕过，每段再拆子命令（CC :49-81）；</li>
 *   <li>deny 全段求值后统一返回，不逐段早退（CC :98-116）。</li>
 * </ol>
 */
class BashCommandOperatorPermissionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BashCommandOperatorPermissions op = new BashCommandOperatorPermissions();

    /** 与 BashTool.isNormalizedCdCommand/isNormalizedGitCommand 同语义（bashPermissions.ts:2567-2611）。 */
    private static BashCommandOperatorPermissions.CommandIdentityCheckers checkers() {
        return new BashCommandOperatorPermissions.CommandIdentityCheckers(
            s -> s != null && (s.equals("cd") || s.startsWith("cd ") || s.startsWith("cd\t")
                || s.equals("pushd") || s.startsWith("pushd ") || s.startsWith("pushd\t")
                || s.equals("popd") || s.startsWith("popd ") || s.startsWith("popd\t")),
            s -> s != null && (s.equals("git") || s.startsWith("git ") || s.startsWith("git\t")));
    }

    /** 记录每段命令并全部返回 Allow。返回 (fn, seen)。 */
    private record RecordingAllow(List<String> seen) implements BashCommandOperatorPermissions.HasPermissionFn {
        @Override
        public PermissionResult check(Map<String, Object> input) {
            seen.add(String.valueOf(input.get("command")));
            return new PermissionResult.Allow(
                JSON.createObjectNode().put("command", String.valueOf(input.get("command"))),
                new PermissionDecisionReason.Other("test-allow"), null, false, null, List.of());
        }
    }

    @Test
    @DisplayName("subshell (ls) → Ask（CC bashCommandHelpers.ts:216-240，不含管道也须 ask）")
    void subshellAsks() {
        // WHY: CC tsAnalysis.compoundStructure.hasSubshell → ask。若漏判，子shell 里的写命令
        //      会绕过权限管线（父 shell 只看到 (ls) 外表）。
        PermissionResult r = op.checkCommandOperatorPermissions("(ls)", alwaysAllow(), checkers());
        assertAskWithOtherReason(r, "This command uses shell operators that require approval for safety");
    }

    @Test
    @DisplayName("command group { rm -rf x; } → Ask（CC compound_statement.hasCommandGroup）")
    void commandGroupAsks() {
        // WHY: CC compoundStructure.hasCommandGroup → ask。命令组与子shell 同为复合结构，
        //      内层命令必须逐段走权限检查而非整组放行。
        PermissionResult r = op.checkCommandOperatorPermissions("{ rm -rf x; }", alwaysAllow(), checkers());
        assertAskWithOtherReason(r, "This command uses shell operators that require approval for safety");
    }

    @Test
    @DisplayName("$(…) / <(…) / ((…)) / {a,b} 非复合 → 不得误判为 unsafe-compound")
    void compoundExclusionsPassthrough() {
        // WHY: tree-sitter 里 command_substitution / process_substitution / arithmetic_expansion /
        //      brace_expansion 都不是 subshell / compound_statement 节点。Java 结构扫描须排除
        //      这些常见写法，否则 echo $(ls) 这类只读命令会被误拦弹窗。
        assertInstanceOf(PermissionResult.Passthrough.class,
            op.checkCommandOperatorPermissions("echo $(ls)", alwaysAllow(), checkers()));
        assertInstanceOf(PermissionResult.Passthrough.class,
            op.checkCommandOperatorPermissions("cat <(echo hi)", alwaysAllow(), checkers()));
        assertInstanceOf(PermissionResult.Passthrough.class,
            op.checkCommandOperatorPermissions("echo $((1+1))", alwaysAllow(), checkers()));
        assertInstanceOf(PermissionResult.Passthrough.class,
            op.checkCommandOperatorPermissions("echo {a,b}", alwaysAllow(), checkers()));
    }

    @Test
    @DisplayName("echo \"a>b\" | wc -l → 引号内 > 是字面量，段剥离不截断（CC :163-174）")
    void quotedGreaterThanNotTruncated() {
        // WHY: CC 用 ParsedCommand.withoutOutputRedirections 保引号。旧 indexOf('>') 死码会把
        //      echo "a>b" 截成 echo "a，导致权限检查对象错误。段必须原样为 echo "a>b"。
        RecordingAllow fn = new RecordingAllow(new ArrayList<>());
        PermissionResult r = op.checkCommandOperatorPermissions("echo \"a>b\" | wc -l", fn, checkers());
        PermissionResult.Allow allow = assertInstanceOf(PermissionResult.Allow.class, r);
        assertEquals("echo \"a>b\"", allow.updatedInput().get("command").asText());
        assertEquals(List.of("echo \"a>b\"", "wc -l"), fn.seen());
    }

    @Test
    @DisplayName("multi-cd 在重定向剥离之后检查：cd /tmp > out.txt 仍识别为 cd → Ask（CC :253-256 → :31-47）")
    void multiCdCheckedAfterRedirectionStrip() {
        // WHY: CC 先 buildSegmentWithoutRedirections 再 segmentedCommandPermissionResult 查 multi-cd。
        //      若顺序颠倒，cd /tmp > out.txt 带重定向尾巴会被 isNormalizedCdCommand 漏判 → 绕过。
        PermissionResult r = op.checkCommandOperatorPermissions(
            "cd sub | cd /tmp > out.txt", alwaysAllow(), checkers());
        assertAskWithOtherReason(r,
            "Multiple directory changes in one command require approval for clarity");
    }

    @Test
    @DisplayName("单 cd + 重定向剥离后无第二 cd → 不误报 multi-cd（剥离保真控制组）")
    void singleCdAfterStripNotMultiCd() {
        // WHY: echo hi > x 剥离后非 cd，只有 cd sub 一个 cd → 不得误 ask multi-cd。
        RecordingAllow fn = new RecordingAllow(new ArrayList<>());
        PermissionResult r = op.checkCommandOperatorPermissions("echo hi > x | cd sub", fn, checkers());
        assertInstanceOf(PermissionResult.Allow.class, r);
        assertEquals(List.of("echo hi", "cd sub"), fn.seen());
    }

    @Test
    @DisplayName("跨段 cd+git → Ask（CC :49-81，防裸仓库 fsmonitor 绕过）")
    void crossSegmentCdGitAsks() {
        // WHY: cd sub | git status 分属不同 pipe 段，单段权限检查看不到 cd+git 组合；
        //      CC 必须在本函数跨段检测。
        PermissionResult r = op.checkCommandOperatorPermissions(
            "cd sub | git status", alwaysAllow(), checkers());
        assertAskWithOtherReason(r,
            "Compound commands with cd and git require approval to prevent bare repository attacks");
    }

    @Test
    @DisplayName("段内复合再拆子命令：cd sub && echo | git status 也命中 cd+git（CC :58-60）")
    void crossSegmentCdGitSplitSubcommands() {
        // WHY: 每段自身可复合（cd sub && echo），CC 用 splitCommand_DEPRECATED 逐段再拆，
        //      防 "cd sub && echo | git status" 绕检。Java 用 BashParser.splitCommands 等价。
        PermissionResult r = op.checkCommandOperatorPermissions(
            "cd sub && echo | git status", alwaysAllow(), checkers());
        assertAskWithOtherReason(r,
            "Compound commands with cd and git require approval to prevent bare repository attacks");
    }

    @Test
    @DisplayName("deny 全段求值后统一返回，不逐段早退（CC :84-116，decisionReason=SubcommandResults）")
    void denyEvaluatedAfterAllSegments() {
        // WHY: CC 先把所有段求值进 segmentResults，再统一查 deny。若逐段早退，
        //      首段 deny 时第二段根本不会被权限检查 → 审计归因 (subcommandResults) 不完整。
        AtomicInteger calls = new AtomicInteger();
        BashCommandOperatorPermissions.HasPermissionFn fn = input -> {
            calls.incrementAndGet();
            String cmd = String.valueOf(input.get("command"));
            if (cmd.equals("rm x")) {
                return new PermissionResult.Deny("denied rm x",
                    new PermissionDecisionReason.Other("test-deny"), null);
            }
            return new PermissionResult.Allow(
                JSON.createObjectNode().put("command", cmd),
                new PermissionDecisionReason.Other("test-allow"), null, false, null, List.of());
        };
        PermissionResult r = op.checkCommandOperatorPermissions("rm x | ls", fn, checkers());
        PermissionResult.Deny deny = assertInstanceOf(PermissionResult.Deny.class, r);
        // 全段求值：即使首段已 deny，第二段 ls 仍被检查
        assertEquals(2, calls.get());
        PermissionDecisionReason.SubcommandResults sr =
            assertInstanceOf(PermissionDecisionReason.SubcommandResults.class, deny.reason());
        assertEquals(2, sr.reasons().size());
        assertTrue(sr.reasons().containsKey("rm x"));
        assertTrue(sr.reasons().containsKey("ls"));
    }

    @Test
    @DisplayName("全段 allow → Allow，updatedInput=首段近似（CC :118-131）")
    void allAllowedReturnsAllow() {
        RecordingAllow fn = new RecordingAllow(new ArrayList<>());
        PermissionResult r = op.checkCommandOperatorPermissions("cat f | grep x", fn, checkers());
        PermissionResult.Allow allow = assertInstanceOf(PermissionResult.Allow.class, r);
        assertEquals("cat f", allow.updatedInput().get("command").asText());
        assertInstanceOf(PermissionDecisionReason.SubcommandResults.class, allow.reason());
    }

    @Test
    @DisplayName("无管道单段 → Passthrough（CC :246-251 'No pipes found'）")
    void singleSegmentPassthrough() {
        // WHY: 无管道命令交给上层通用权限管线，operator 层不表态。
        PermissionResult r = op.checkCommandOperatorPermissions("mkdir foo", alwaysAllow(), checkers());
        PermissionResult.Passthrough p = assertInstanceOf(PermissionResult.Passthrough.class, r);
        assertEquals("No pipes found in command", p.message());
    }

    // ── GAP-2 子命令建议规则聚合（CC bashPermissions.ts:2472-2556）─────────────────

    @Test
    @DisplayName("GAP-2 复合命令 ask 聚合各子命令 addRules 建议规则并去重（CC :2485-2490）")
    void compoundAskAggregatesAndDedupsSubcommandRules() {
        // WHY: CC 对每个 ask/passthrough 子命令结果 extractRules 抽 addRules 规则，
        //      按 permissionRuleValueToString 去重。若两端建议同一条 Read 规则，
        //      去重后应仅剩 1 条——否则用户弹窗会出现重复建议。
        PermissionRule readRule = new PermissionRule(PermissionRuleSource.LOCAL_SETTINGS,
            PermissionBehavior.ALLOW, PermissionRuleValue.withContent("Read", "/data/**"));
        PermissionUpdate readSuggestion = new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.LOCAL_SETTINGS, List.of(readRule), PermissionBehavior.ALLOW);

        BashCommandOperatorPermissions.HasPermissionFn fn = input ->
            new PermissionResult.Ask("need approval", new PermissionDecisionReason.Other("test-ask"),
                List.of(readSuggestion), null, null, null, false, null, List.of());

        PermissionResult r = op.checkCommandOperatorPermissions("cat a | grep x", fn, checkers());
        PermissionResult.Ask ask = assertInstanceOf(PermissionResult.Ask.class, r);
        // 打包为单个 addRules(localSettings, allow)
        assertEquals(1, ask.suggestions().size());
        PermissionUpdate.AddRules addRules =
            assertInstanceOf(PermissionUpdate.AddRules.class, ask.suggestions().get(0));
        assertEquals(PermissionUpdate.Destination.LOCAL_SETTINGS, addRules.destination());
        assertEquals(PermissionBehavior.ALLOW, addRules.behavior());
        assertEquals(1, addRules.rules().size());
        assertEquals("Read", addRules.rules().get(0).ruleValue().toolName());
    }

    @Test
    @DisplayName("GAP-2 ask 无规则且非 rule 归因 → suggestionForExactCommand 兜底（CC :2499-2510 GH#28784）")
    void compoundAskNoRulesFallsBackToExactSuggestion() {
        // WHY: 安全类 ask（compound-cd+write 等）无 suggestions，若只聚合规则会漏掉链式命令，
        //      CC GH#28784 兜底 suggestionForExactCommand(subcommand) 让 UI 显示被阻断命令。
        BashCommandOperatorPermissions.HasPermissionFn fn = input ->
            new PermissionResult.Ask("need approval", new PermissionDecisionReason.Other("test-ask"),
                List.of(), null, null, null, false, null, List.of());

        PermissionResult r = op.checkCommandOperatorPermissions("npm publish | cat out", fn, checkers());
        PermissionResult.Ask ask = assertInstanceOf(PermissionResult.Ask.class, r);
        PermissionUpdate.AddRules addRules =
            assertInstanceOf(PermissionUpdate.AddRules.class, ask.suggestions().get(0));
        // npm publish:* 与 cat out:* 两条前缀规则
        assertEquals(2, addRules.rules().size());
        assertEquals("Bash", addRules.rules().get(0).ruleValue().toolName());
    }

    @Test
    @DisplayName("GAP-2 ask 且 rule 归因 → 不兜底 suggestionForExactCommand（CC :2502 type==='rule' 跳过）")
    void compoundAskRuleReasonSkipsFallback() {
        // WHY: 显式 ask 规则是用户刻意"每次复查"，合成 Bash(exact) 建议会破坏该意图，
        //      CC 显式跳过 decisionReason.type === 'rule' 的子命令。
        PermissionRule askRule = new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ASK,
            PermissionRuleValue.withContent("Bash", "npm publish:*"));
        BashCommandOperatorPermissions.HasPermissionFn fn = input ->
            new PermissionResult.Ask("ask rule", new PermissionDecisionReason.Rule(askRule),
                List.of(), null, null, null, false, null, List.of());

        PermissionResult r = op.checkCommandOperatorPermissions("npm publish | cat out", fn, checkers());
        PermissionResult.Ask ask = assertInstanceOf(PermissionResult.Ask.class, r);
        assertTrue(ask.suggestions().isEmpty());
    }

    @Test
    @DisplayName("GAP-2 聚合建议规则 capped 到 5（CC :110 MAX_SUGGESTED_RULES_FOR_COMPOUND GH#11380）")
    void compoundAskCapsAggregatedRulesToFive() {
        // WHY: 复合命令子命令可无限多，CC GH#11380 把建议规则 capped 到 5 防止弹窗爆炸；
        //      保序取最左 N（子命令顺序）。
        BashCommandOperatorPermissions.HasPermissionFn fn = input ->
            new PermissionResult.Ask("need approval", new PermissionDecisionReason.Other("test-ask"),
                List.of(), null, null, null, false, null, List.of());

        PermissionResult r = op.checkCommandOperatorPermissions(
            "cat a | grep b | head c | tail d | wc e | sort f", fn, checkers());
        PermissionResult.Ask ask = assertInstanceOf(PermissionResult.Ask.class, r);
        PermissionUpdate.AddRules addRules =
            assertInstanceOf(PermissionUpdate.AddRules.class, ask.suggestions().get(0));
        assertEquals(5, addRules.rules().size());
    }

    // ---- helpers ----

    private static BashCommandOperatorPermissions.HasPermissionFn alwaysAllow() {
        return new RecordingAllow(new ArrayList<>());
    }

    private static void assertAskWithOtherReason(PermissionResult r, String expectedReason) {
        PermissionResult.Ask ask = assertInstanceOf(PermissionResult.Ask.class, r);
        PermissionDecisionReason.Other other =
            assertInstanceOf(PermissionDecisionReason.Other.class, ask.reason());
        assertEquals(expectedReason, other.reason());
    }
}
