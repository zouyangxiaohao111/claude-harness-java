package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.sandbox.SandboxManager;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseContext;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-B2 Bash 安全语义（组 1-4 B）聚焦测试。
 *
 * <p>覆盖验收标准（04-implementation-plan IMP-B2 §2）：
 * <ul>
 *   <li>危险命令走 CC 判定路径（恒 ask + path/read-only），非 execute 级 DANGEROUS 正则硬阻断
 *       （TR-B1-⊕1/⊕2 删除后保护不回归）</li>
 *   <li>SandboxManager.canSandbox 硬编码黑名单删除 + settings.sandbox.excludedCommands 迁移
 *       （DEL-B2-001）——curl 等网络命令不再被硬编码拒绝，excludedCommands 命中才不沙箱化</li>
 *   <li>per-input dangerouslyDisableSandbox（CC shouldUseSandbox.ts:136-141，非全局标志）</li>
 *   <li>git 只读守卫 RO-16（-c/--exec-path/--config-env）与 RO-17（bare-repo/git-internal）</li>
 * </ul>
 */
@DisplayName("IMP-B2 Bash 安全语义（黑名单删除 / excludedCommands / per-input sandbox / git 只读守卫）")
class BashToolPermissionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    // ────────────────────────────────────────────────────────────────────
    // 构造辅助
    // ────────────────────────────────────────────────────────────────────

    private static ObjectNode bashInput(String command) {
        ObjectNode input = JSON.createObjectNode();
        input.put("command", command);
        return input;
    }

    private static ToolPermissionContext emptyCtx() {
        return ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static ToolUseContext toolCtx() {
        return ToolUseContext.of(AGENT, SESSION, PermissionMode.DEFAULT, List.of(), "",
            com.nexusai.application.agent.tool.AbortController.NOOP, List.of(), emptyCtx(),
            PermissionMode.DEFAULT);
    }

    private static JsonNode inputWith(String command, String key, boolean value) {
        ObjectNode in = bashInput(command);
        in.put(key, value);
        return in;
    }

    // ────────────────────────────────────────────────────────────────────
    // IMP-4 AST 决策链辅助（deny/allow 规则构造 · 对齐 BashToolCheckPermissionsTest 模式）
    // ────────────────────────────────────────────────────────────────────

    private static PermissionRule rule(PermissionBehavior behavior, String content) {
        return new PermissionRule(PermissionRuleSource.USER_SETTINGS, behavior,
            PermissionRuleValue.withContent("Bash", content));
    }

    private static ToolPermissionContext ctx(PermissionMode mode,
            Set<PermissionRule> allow, Set<PermissionRule> deny, Set<PermissionRule> ask) {
        Map<PermissionRuleSource, Set<PermissionRule>> allowMap = new EnumMap<>(PermissionRuleSource.class);
        Map<PermissionRuleSource, Set<PermissionRule>> denyMap = new EnumMap<>(PermissionRuleSource.class);
        Map<PermissionRuleSource, Set<PermissionRule>> askMap = new EnumMap<>(PermissionRuleSource.class);
        allowMap.put(PermissionRuleSource.USER_SETTINGS, allow);
        denyMap.put(PermissionRuleSource.USER_SETTINGS, deny);
        askMap.put(PermissionRuleSource.USER_SETTINGS, ask);
        return ToolPermissionContext.of(mode, allowMap, denyMap, askMap, Map.of());
    }

    private static ToolUseContext toolCtx(ToolPermissionContext permCtx) {
        return ToolUseContext.of(AGENT, SESSION, permCtx.mode(),
            List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP, List.of(),
            permCtx, permCtx.mode());
    }

    // ════════════════════════════════════════════════════════════════════
    // 1. dangerous[] 黑名单删除后：危险命令仍经 CC 路径 Ask（path/read-only）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("dangerous[] 删除后 rm -rf / 仍 Ask(Other)（经 path 约束层，非黑名单）")
    void dangerousCommand_stillAskedViaPathConstraint() {
        // WHY: IMP-B2 删除 dangerous[] 字符串黑名单（TR-B1-⊕2）。保护不回归——`rm -rf /`
        //      由 BashPathValidator 危险删除路径（pathValidation.ts isDangerousRemovalPath）
        //      承接 → Ask(Other)，与 CC bashSecurity 恒 ask + path/read-only 语义一致。
        BashTool tool = new BashTool();

        PermissionResult result = tool.checkPermissions(bashInput("rm -rf /"), toolCtx());

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .isInstanceOf(PermissionDecisionReason.Other.class);
    }

    @Test
    @DisplayName("G1 对齐 CC：execute 不再硬阻断命令替换，权限链负责 Ask（批准后执行）")
    void executeNoLongerBlocksCommandSubstitution_g1() {
        // WHY: G1（DEL-TR-B1-01）删除 execute 层 parseForSecurity 硬阻断。CC 真源 parseForSecurity
        //      仅用于 preparePermissionMatcher（BashTool.tsx:451），execute/call 路径不调用；
        //      危险命令（命令替换/进程替换/eval 等）判定归 checkPermissions（bashSecurity 恒 ask +
        //      AST 决策链 fail-safe），用户批准后正常执行。`echo $(rm -rf /)`（裸参数位命令替换）
        //      由权限链 Ask(Other)，不再返回 "Dangerous command blocked"。
        BashTool tool = new BashTool();

        PermissionResult result = tool.checkPermissions(bashInput("echo $(rm -rf /)"), toolCtx());

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // 2. SandboxManager.canSandbox 硬编码黑名单删除 + excludedCommands 迁移（DEL-B2-001）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("canSandbox 黑名单删除：无 excludedCommands 时 curl 可沙箱化（旧硬编码黑名单拒绝）")
    void curlNowSandboxable_hardcodedBlacklistDeleted() {
        // WHY: DEL-B2-001 删除 SandboxManager.canSandbox 硬编码网络/root 黑名单（curl/wget/sudo…）。
        //      CC shouldUseSandbox.ts 真源 = settings.sandbox.excludedCommands（:54），无硬编码黑名单。
        //      旧实现 `curl https://evil` 命中 canSandbox 网络正则 → 不沙箱化；删除后无 excludedCommands
        //      → 应沙箱化（EV-B2-020/⊕-1 行为对齐）。
        SandboxManager sm = new SandboxManager(true, true);

        assertThat(sm.shouldUseSandbox("Bash", bashInput("curl https://evil.example"))).isTrue();
    }

    @Test
    @DisplayName("excludedCommands prefix 模式 curl:* 命中 → 不沙箱化（CC bashPermissionRule prefix）")
    void excludedCommands_prefixPattern_notSandboxed() {
        // WHY: settings.sandbox.excludedCommands 迁移消费——用户配置 `curl:*`（prefix）→
        //      curl 带参命令不沙箱化。CC shouldUseSandbox.ts:107-110 prefix 语义：
        //      cand === prefix || cand.startsWith(prefix + ' ') → "curl https://evil.example" 命中。
        SandboxManager sm = new SandboxManager(true, true);
        sm.setExcludedCommands(List.of("curl:*"));

        assertThat(sm.shouldUseSandbox("Bash", bashInput("curl https://evil.example"))).isFalse();
    }

    @Test
    @DisplayName("excludedCommands 裸 curl（exact）不匹配 curl 带参 → 仍沙箱化（CC exact 语义）")
    void excludedCommands_exactDoesNotMatchArgs_stillSandboxed() {
        // WHY: CC parsePermissionRule（shellRuleMatching.ts:159-184）——裸 `curl` 无 `:*` 无 `*`
        //      → exact 类型，仅匹配整命令 "curl"。CC shouldUseSandbox.ts:112-114 exact：
        //      cand === rule.command → "curl https://evil.example" 不等于 "curl" → 不排除（仍沙箱化）。
        //      排除带参命令须用 `curl:*`（prefix）或 `curl *`（wildcard）。
        SandboxManager sm = new SandboxManager(true, true);
        sm.setExcludedCommands(List.of("curl"));

        assertThat(sm.shouldUseSandbox("Bash", bashInput("curl https://evil.example"))).isTrue();
    }

    @Test
    @DisplayName("excludedCommands 复合命令逐子命令 prefix 匹配 → 不沙箱化（CC :60-69 splitCommand）")
    void excludedCommands_compoundSubcommandMatch_notSandboxed() {
        // WHY: CC shouldUseSandbox.ts:60-69 —— 复合命令拆子命令逐条检查，防 `docker ps && curl evil.com`
        //      首子命令不匹配即放行后段逃逸。`curl evil.com` 子命令命中 `curl:*` prefix → 不沙箱化。
        SandboxManager sm = new SandboxManager(true, true);
        sm.setExcludedCommands(List.of("curl:*"));

        assertThat(sm.shouldUseSandbox("Bash", bashInput("docker ps && curl evil.com"))).isFalse();
    }

    @Test
    @DisplayName("excludedCommands wildcard 模式命中 → 不沙箱化（CC bashPermissionRule wildcard）")
    void excludedCommands_wildcardPattern_notSandboxed() {
        // WHY: CC shouldUseSandbox.ts:103-124 规则三型匹配（bashPermissionRule）——
        //      `git status *` wildcard 命中 `git status foo` → 不沙箱化。
        SandboxManager sm = new SandboxManager(true, true);
        sm.setExcludedCommands(List.of("git status *"));

        assertThat(sm.shouldUseSandbox("Bash", bashInput("git status foo"))).isFalse();
    }

    @Test
    @DisplayName("excludedCommands 未命中 → 仍沙箱化")
    void excludedCommands_noMatch_stillSandboxed() {
        SandboxManager sm = new SandboxManager(true, true);
        sm.setExcludedCommands(List.of("curl"));

        assertThat(sm.shouldUseSandbox("Bash", bashInput("git status"))).isTrue();
    }

    @Test
    @DisplayName("excludedCommands 空 → 不沙箱化 false（CC shouldUseSandbox.ts:143-145 无 command）")
    void excludedCommands_emptyCommand_notSandboxed() {
        SandboxManager sm = new SandboxManager(true, true);
        sm.setExcludedCommands(List.of("curl"));

        assertThat(sm.shouldUseSandbox("Bash", JSON.createObjectNode())).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 3. per-input dangerouslyDisableSandbox（CC shouldUseSandbox.ts:136-141）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("per-input dangerouslyDisableSandbox + allowUnsandboxedCommands → 不沙箱化")
    void perInputDangerouslyDisableSandbox_notSandboxed() {
        // WHY: CC shouldUseSandbox.ts:136-141 —— dangerouslyDisableSandbox 是<b>输入字段</b>（per-input），
        //      非旧 Java 全局 volatile 标志。命中且 allowUnsandboxedCommands=true（默认）→ 不沙箱化。
        SandboxManager sm = new SandboxManager(true, true);

        assertThat(sm.shouldUseSandbox("Bash", inputWith("git status", "dangerouslyDisableSandbox", true)))
            .isFalse();
    }

    @Test
    @DisplayName("per-input dangerouslyDisableSandbox 但 allowUnsandboxedCommands=false → 仍沙箱化")
    void perInputDangerouslyDisableSandbox_unsandboxedNotAllowed_stillSandboxed() {
        // WHY: CC shouldUseSandbox.ts:137-140 —— 仅当 areUnsandboxedCommandsAllowed() 为真时
        //      dangerouslyDisableSandbox 才生效（sandbox-adapter.ts:474-477 默认 ?? true）。
        //      策略锁定 allowUnsandboxedCommands=false → 即使输入置位也仍沙箱化。
        SandboxManager sm = new SandboxManager(true, true, false);

        assertThat(sm.shouldUseSandbox("Bash", inputWith("git status", "dangerouslyDisableSandbox", true)))
            .isTrue();
    }

    @Test
    @DisplayName("非 Bash 工具不沙箱化（Java toolName 门控）")
    void nonBashTool_notSandboxed() {
        SandboxManager sm = new SandboxManager(true, true);

        assertThat(sm.shouldUseSandbox("PowerShell", bashInput("Get-Process"))).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // 4. git 只读守卫 RO-16/17（readOnlyValidation.ts）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("RO-16: git -c 危险 flag → 非只读（readOnlyValidation.ts:1726）")
    void gitCflag_notReadOnly() {
        // WHY: EV-B2-029 已确认缺失——git -c 可注入任意 config（core.fsmonitor/diff.external/core.gitProxy）
        //      执行任意命令。对齐 CC readOnlyValidation.ts:1726 /\s-c[\s=]/ → 不只读放行（交权限链 ask）。
        BashTool tool = new BashTool();

        assertThat(tool.isReadOnly(bashInput("git -c core.fsmonitor=true status"))).isFalse();
    }

    @Test
    @DisplayName("RO-16: git --exec-path 危险 flag → 非只读（readOnlyValidation.ts:1734）")
    void gitExecPath_notReadOnly() {
        BashTool tool = new BashTool();

        assertThat(tool.isReadOnly(bashInput("git --exec-path=/tmp status"))).isFalse();
    }

    @Test
    @DisplayName("RO-16: git --config-env 危险 flag → 非只读（readOnlyValidation.ts:1744）")
    void gitConfigEnv_notReadOnly() {
        BashTool tool = new BashTool();

        assertThat(tool.isReadOnly(bashInput("git --config-env=core.fsmonitor=evil status"))).isFalse();
    }

    @Test
    @DisplayName("RO-16 基线: 纯 git status 非只读（Java fail-closed，git 不在只读 allowlist）")
    void gitPlain_notReadOnly_baseline() {
        // WHY: 基线不变量——git 不在 BASH_READONLY_COMMAND_NAMES（Java 偏保守 fail-closed），
        //      isReadOnly=false。RO-16/17 守卫不改此基线（不回退更宽松）。
        BashTool tool = new BashTool();

        assertThat(tool.isReadOnly(bashInput("git status"))).isFalse();
    }

    @Test
    @DisplayName("RO-16 无回归: cat 仍只读（只读 allowlist 未被守卫破坏）")
    void readOnlyRegression_cat_stillReadOnly() {
        BashTool tool = new BashTool();

        assertThat(tool.isReadOnly(bashInput("cat file.txt"))).isTrue();
    }

    @Test
    @DisplayName("RO-17a: bare-repo cwd 判定（utils/git.ts:876-908 isCurrentDirectoryBareGitRepo）")
    void bareRepo_isCurrentDirectoryBareGitRepo(@TempDir Path tempDir) throws Exception {
        // WHY: RO-17 bare-repo 守卫——cwd 无有效 .git/HEAD 但含裸仓库指示物（HEAD/objects/refs）
        //      时 git 会把 cwd 当 gitdir 执行恶意 hooks。对私有静态方法反射验证（对齐
        //      readOnlyValidation.ts:1930-1936 isCurrentDirectoryBareGitRepo）。
        //      正常目录（含 .git/HEAD）→ false；裸仓库指示物 → true。
        Method m = BashTool.class.getDeclaredMethod("isCurrentDirectoryBareGitRepo", Path.class);
        m.setAccessible(true);

        Path normalRepo = tempDir.resolve("normal");
        Files.createDirectories(normalRepo.resolve(".git"));
        Files.writeString(normalRepo.resolve(".git/HEAD"), "ref: refs/heads/master\n");
        assertThat((boolean) m.invoke(null, normalRepo)).as("正常 repo 非 bare").isFalse();

        Path bare = tempDir.resolve("bare");
        Files.createDirectories(bare.resolve("objects"));
        Files.createDirectories(bare.resolve("refs"));
        Files.writeString(bare.resolve("HEAD"), "ref: refs/heads/master\n");
        assertThat((boolean) m.invoke(null, bare)).as("裸仓库指示物判定").isTrue();
    }

    @Test
    @DisplayName("RO-17b: git-internal 路径写入复合命令 → 非只读（readOnlyValidation.ts:1943-1949）")
    void gitInternalPathWrite_compound_notReadOnly() {
        // WHY: `mkdir -p hooks && echo 'malicious' > hooks/pre-commit && git status` 创建 git-internal
        //      文件后跑 git → git 执行恶意 hooks。对齐 CC commandWritesToGitInternalPaths → 不只读。
        BashTool tool = new BashTool();

        assertThat(tool.isReadOnly(bashInput("mkdir -p hooks && echo 'x' > hooks/pre-commit && git status")))
            .as("写 git-internal 路径 + git 复合命令不只读放行")
            .isFalse();
    }

    @Test
    @DisplayName("RO-16 只读 Allow 链: git -c 命令不自动放行（checkPermissions 非 Allow）")
    void gitCflag_notAutoAllowedInPermissionChain() {
        // WHY: 只读 auto-allow 链（BASH_COMMAND_ALLOWLIST + bashFlagsReadOnly）对 git -c 危险命令
        //      不得放行。Java git 不在 allowlist → Passthrough/Ask（非 Allow），守卫保证将来入表不回归。
        BashTool tool = new BashTool();

        PermissionResult result = tool.checkPermissions(bashInput("git -c core.fsmonitor=true status"), toolCtx());

        assertThat(result).isNotInstanceOf(PermissionResult.Allow.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP-4 AST 决策链（too-complex / simple / parse-unavailable 三态）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("IMP-4: too-complex 命令替换 → Ask(Other)（AST 三态决策链补实现）")
    void astTooComplex_commandSubstitution_asked() {
        // WHY: OPD-WF4-01-R3 拍板补 AST 三态决策链。CC bashToolHasPermission :1741-1769
        //      —— 命令含不可静态分析结构（$(...) 裸参数位）→ too-complex → checkEarlyExitDeny
        //      （无 deny 命中）→ Ask(type:'other', reason)。Java checkPermissions 此前无 AST，
        //      `echo $(rm -rf /)` 权限决策层落 Passthrough（execute 层才 fail-closed 阻断）；
        //      补链后决策层即 Ask，对齐 CC 可观测行为。
        BashTool tool = new BashTool();

        PermissionResult result = tool.checkPermissions(bashInput("echo $(rm -rf /)"), toolCtx());

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .isInstanceOf(PermissionDecisionReason.Other.class);
    }

    @Test
    @DisplayName("IMP-4: too-complex + prefix deny → Deny（deny 不降级为 ask）")
    void astTooComplex_prefixDeny_denied() {
        // WHY: CC checkEarlyExitDeny :1407-1412 —— matchingRulesForInput 'prefix'
        //      matchingDenyRules[0] 命中 → deny。用户 Bash(echo:*) deny 对 `echo $(x)`
        //      必须保持 deny，不得降级为 ask（CC :1420-1424）。Java 此前 `echo $(rm -rf /)`
        //      权限决策层不查 deny 桶 → ask（deny 降级）；补 checkEarlyExitDeny 后 Deny。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(rule(PermissionBehavior.DENY, "echo:*")), Set.of());

        PermissionResult result = tool.checkPermissions(bashInput("echo $(rm -rf /)"), toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("IMP-4: simple + eval-like builtin → Ask(Other)（checkSemantics）")
    void astSimple_evalBuiltin_asked() {
        // WHY: CC :1771-1806 —— simple AST + checkSemantics(commands) 命中 EVAL_LIKE_BUILTINS
        //      （eval 求值参数为代码）→ checkSemanticsDeny（无 deny）→ Ask(type:'other',
        //      reason:'eval evaluates arguments as shell code')。Java 此前 `eval "rm -rf /"`
        //      权限决策层无语义门（execute parseForSecurity 首词 eval 才拦）；补链后决策层 Ask。
        BashTool tool = new BashTool();

        PermissionResult result = tool.checkPermissions(bashInput("eval \"rm -rf /\""), toolCtx());

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .isInstanceOf(PermissionDecisionReason.Other.class);
    }

    @Test
    @DisplayName("IMP-4: simple + eval 在管道中 → 逐子命令 prefix deny（checkSemanticsDeny）")
    void astSimple_evalInPipeline_denyRespected() {
        // WHY: CC checkSemanticsDeny :1431-1453 —— 每个 SimpleCommand .text span 前缀 deny。
        //      `echo foo | eval rm` 的 filterRulesByContentsMatchingInput compound guard 在
        //      整命令上会阻止 Bash(eval:*) 前缀匹配；逐子命令 span（单命令）guard 不触发 →
        //      eval deny 命中 → Deny（用户 deny 不降级为 ask）。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(rule(PermissionBehavior.DENY, "eval:*")), Set.of());

        PermissionResult result = tool.checkPermissions(bashInput("echo foo | eval rm"), toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("IMP-4: too-complex + exact allow → Allow（exact 覆盖，CC :1026-1035）")
    void astTooComplex_exactAllow_allow() {
        // WHY: CC checkEarlyExitDeny :1395-1401 —— bashToolCheckExactMatchPermission 非
        //      passthrough 即返回。用户对该具体命令做过 conscious choice（exact allow）→
        //      覆盖 Ask，对齐 CC :1195-1196 exact 早退。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "rm -rf /tmp/`id`")), Set.of(), Set.of());

        PermissionResult result = tool.checkPermissions(bashInput("rm -rf /tmp/`id`"), toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("IMP-4: simple-clean 命令不被 AST 门拦截（rm -rf / 仍走 path 约束 Ask）")
    void astSimple_cleanCommand_notIntercepted() {
        // WHY: simple + 语义通过 → 落 legacy 链。`rm -rf /` 语义 ok（rm 非 eval-like）→
        //      3.4 path 约束危险删除 → Ask(Other)。证明 AST 门不吞干净命令（无双重 ask）。
        BashTool tool = new BashTool();

        PermissionResult result = tool.checkPermissions(bashInput("rm -rf /"), toolCtx());

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .isInstanceOf(PermissionDecisionReason.Other.class);
    }

    @Test
    @DisplayName("IMP-4: simple + 双引号内 $() 内层 eval 提取 → Ask（innerCommands 检查）")
    void astSimple_innerCommandSubstitution_evalAsked() {
        // WHY: CC walkString :1593-1605 —— 双引号内 $() 递归提取内层命令（simple 仍成立，
        //      solo-placeholder 才 too-complex）；checkSemantics 对提取的内层命令同样生效。
        //      `echo "hello $(eval x)"` → 内层 'eval x' 语义失败 → Ask（无 deny 时）。
        BashTool tool = new BashTool();

        PermissionResult result = tool.checkPermissions(bashInput("echo \"hello $(eval x)\""), toolCtx());

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
    }
}
