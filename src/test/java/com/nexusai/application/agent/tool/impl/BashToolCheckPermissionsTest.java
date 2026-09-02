package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionPipeline;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.sandbox.SandboxManager;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;

import java.lang.reflect.Field;
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
 * [S09] BashTool.checkPermissions 决策链测试 · WF-A 新链（bashToolCheckPermission）。
 *
 * <p>新链顺序（BashTool.checkPermissions :621-781，merge master 后）：
 * <ol>
 *   <li>危险命令 → <b>Ask</b>（IMP-B2 dangerous[] 黑名单已删除；判定由 3.3 BashSecurityValidator
 *       + 3.4 BashPathValidator 承接，对齐 CC bashSecurity 恒 ask + path/read-only，EV-B1-D2/D3）</li>
 *   <li>sandbox auto-allow → Allow（沙箱启用且命令可沙箱化，CC checkSandboxAutoAllow）</li>
 *   <li>content <b>allow</b> rule → Allow（RuleQuery.getAllowRuleByContentsForTool 只查 allow 桶；
 *       deny/ask 桶不在工具内求值，迁外层 PermissionPipeline 1a/1f）</li>
 *   <li>sed 约束 → Ask</li>
 *   <li>mode 分支（acceptEdits + 文件系统命令 → Allow(Mode)）</li>
 *   <li>operator（子shell/管道段/cd+git → Ask 或逐段判定）</li>
 *   <li>只读 flag 级表（BASH_COMMAND_ALLOWLIST：grep/sed/ps/date 等 24 键）+ echo 名字层
 *       简单形式 → Allow</li>
 *   <li>其余 → Passthrough（交外层规则）</li>
 * </ol>
 *
 * <p>与旧链（master BashRuleMatcher 前缀 allow/deny/ask 规则 + 危险命令→Ask + ls→Allow）的差异，
 * 本测试断言已全部更新为新链语义：
 * <ul>
 *   <li>危险命令 → <b>Ask</b>（非 Deny），对齐 CC bashSecurity 危险检测恒 ask 零 deny</li>
 *   <li>deny/ask 前缀规则匹配迁 <b>外层 PermissionPipeline 1a 层</b>——工具内只查 allow 桶
 *       （bashPermissions.ts step 4/5），故相关测试用 {@link #checkViaPipeline} 构造管线上下文</li>
 *   <li>只读放行仅限 flag 级表 + echo 名字层：ls/cat/head/tail/wc 维持 Passthrough
 *       （IMP-OPD-05 拍板：比 CC 严格，仅回退 echo）</li>
 *   <li>passthrough 不再携带内容建议（建议生成迁出工具，交外层管线/分类器路径）</li>
 * </ul>
 */
@DisplayName("[S09] BashTool.checkPermissions 决策链（WF-A 新链）")
class BashToolCheckPermissionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    // ────────────────────────────────────────────────────────────────────
    // 构造辅助
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

    private static ObjectNode bashInput(String command) {
        ObjectNode input = JSON.createObjectNode();
        input.put("command", command);
        return input;
    }

    /** 反射注入 BashTool 私有 sandboxManager 字段（BashTool 仅 @Autowired 无 setter）。 */
    private static void injectSandboxManager(BashTool tool, SandboxManager sandboxManager) {
        try {
            Field f = BashTool.class.getDeclaredField("sandboxManager");
            f.setAccessible(true);
            f.set(tool, sandboxManager);
        } catch (Exception e) {
            throw new IllegalStateException("反射注入 BashTool.sandboxManager 失败", e);
        }
    }

    private static PermissionResult check(BashTool tool, String command, ToolUseContext ctx) {
        return tool.checkPermissions(bashInput(command), ctx);
    }

    /**
     * 经外层 PermissionPipeline 判定（新链 deny/ask 前缀规则在此求值）。
     *
     * <p>WHY：WF-A 新链 BashTool.checkPermissions 只查 allow 桶（:686-701），
     * deny/ask 内容规则由管线 1a（content deny）求值（CheckLayer1a_DenyRule →
     * RuleQuery.getDenyRuleByContentsForTool → BashRuleMatcher deny/ask 剥离语义）。
     * 测试 deny 规则必须构造管线上下文，否则直接调 checkPermissions 只得 Passthrough。
     */
    private static PermissionResult checkViaPipeline(BashTool tool, String command,
            ToolPermissionContext permCtx) {
        JsonNode input = bashInput(command);
        ToolUseContext ctx = toolCtx(permCtx);
        ToolUseBlock call = new ToolUseBlock(UUID.randomUUID().toString(), tool.name(), input);
        return new PermissionPipeline().check(tool, call, input, ctx, permCtx);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1：env 前缀 deny/ask 匹配（防绕过）→ 外层 pipeline 1a
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("验收1: FOO=bar cmd env 前缀命中 deny 规则 → Deny（pipeline 1a，deny 桶剥任意 env）")
    void envPrefix_denyRuleMatches() {
        // WHY: CC bashPermissions.ts :826-853 —— deny 桶 stripAllLeadingEnvVars 剥任意
        //      env 前缀，防 `FOO=bar claude run` 绕过 Bash(claude:*) deny。旧链在工具内
        //      匹配；WF-A 新链迁外层 pipeline 1a。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(rule(PermissionBehavior.DENY, "claude:*")), Set.of());

        PermissionResult result = checkViaPipeline(tool, "FOO=bar claude run", permCtx);

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("验收1b: 白名单 env 前缀（NODE_ENV∈SAFE_ENV_VARS）命中 allow 规则 → Allow")
    void envPrefix_allowRuleMatchesWithSafeEnv() {
        // WHY: NODE_ENV 在 SAFE_ENV_VARS 白名单，stripSafeWrappers 剥它 → `npm run build`
        //      命中 Bash(npm run:*) allow（BashRuleMatcher allow 桶经 3.5 层）。非白名单
        //      env（如 NODE_OPTIONS=...）不剥 → allow 不命中（防 DOCKER_HOST=evil docker ps）。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "npm run:*")), Set.of(), Set.of());

        PermissionResult result = check(tool, "NODE_ENV=prod npm run build", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2：wrapper 剥离匹配
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("验收2: timeout 包装剥离后命中 deny 规则 → Deny（pipeline 1a）")
    void wrapperStrip_denyRuleMatches() {
        // WHY: CC bashPermissions.ts :604-612 —— 匹配前先剥 timeout 等安全 wrapper，
        //      `timeout 5 claude run` 不得绕过 Bash(claude:*) deny。原用例命令 rm -rf /tmp/x
        //      新链已被危险检测先行 Ask，改用非危险命令验证 wrapper 剥离语义。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(rule(PermissionBehavior.DENY, "claude:*")), Set.of());

        PermissionResult result = checkViaPipeline(tool, "timeout 5 claude run", permCtx);

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("验收2b: timeout flag 值白名单拒绝注入（timeout -k$(id) 不剥离 → 不命中）")
    void wrapperStrip_timeoutFlagValueWhitelist() {
        // WHY: CC TIMEOUT_FLAG_VALUE_RE —— `-k$(id)` 含注入字符，wrapper 不剥离 → `ls:*`
        //      allow 不命中 → 非 Allow（新链中该命令落 Passthrough）。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "ls:*")), Set.of(), Set.of());

        PermissionResult result = check(tool, "timeout -k$(id) 10 ls", toolCtx(permCtx));

        assertThat(result)
            .as("注入 flag 值不剥离 → ls:* allow 不命中 → 非 Allow")
            .isNotInstanceOf(PermissionResult.Allow.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3：compound guard
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("验收3a: cd x && evil 不得命中 prefix allow（Bash(cd:*)）")
    void compound_prefixAllowNotMatched() {
        // WHY: CC :883-893 —— allow 桶 prefix 规则不匹配复合命令，防 `cd /tmp && evil`
        //      被 Bash(cd:*) 放行。新链 allow 桶 BashRuleMatcher compound guard 仍生效。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "cd:*")), Set.of(), Set.of());

        PermissionResult result = check(tool, "cd /tmp && python3 evil.py", toolCtx(permCtx));

        assertThat(result)
            .as("CC compound guard：prefix allow 不匹配复合命令")
            .isNotInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("验收3b: compound 子命令 deny → 沙箱 auto-allow 预检 Deny（S01，主链逐子命令）")
    void compound_subcommandDenyMatches() {
        // WHY (WF-4 S01 + DEL-WF4-02): CC checkSandboxAutoAllow（bashPermissions.ts:1293-1336）
        //      对复合命令逐子命令查 deny（防 `echo hi && rm -rf /` 绕过 Bash(rm:*)）。
        //      [S09] filter 兜底已删（filterRulesByContentsMatchingInput 不再展开 compound），
        //      compound 子命令 deny 收敛于 BashTool 沙箱 auto-allow 前的 matchingDenyOrAskRule 预检。
        BashTool tool = new BashTool();
        injectSandboxManager(tool, new SandboxManager(true, true));
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(rule(PermissionBehavior.DENY, "evil:*")), Set.of());

        PermissionResult result = check(tool, "cd /tmp && evil", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("验收3c: 单命令 cd x 命中 prefix allow → Allow")
    void singleCommand_prefixAllowMatched() {
        // WHY: path 约束层(3.4)先于 content allow(3.5)（CC bashPermissions.ts:1106-1122 第 3 步
        //      先于第 4/5 步）。`cd /tmp` 越界 → path 约束(read 非 inWorkingDir)先行 Ask，
        //      allow 规则不再可达。改用工作目录内等价 `cd .`：path 约束(read inWorkingDir)放行后，
        //      才命中 content allow "cd:*" → Allow(Rule)。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "cd:*")), Set.of(), Set.of());

        PermissionResult result = check(tool, "cd .", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 4：xargs 前缀 + wildcard
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("验收4a: xargs 前缀命中 deny（Bash(grep:*) 阻断 xargs grep foo）→ pipeline 1a")
    void xargsPrefix_denyMatches() {
        // WHY: CC prefixMatchesCandidate :894-912 —— `xargs grep foo` 经 xargs 前缀命中
        //      Bash(grep:*)。新链 deny 桶求值在外层 pipeline 1a；工具内 xargs 属只读
        //      flag 级表（:615）反会 Allow，故必须走管线验证 deny 优先。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(rule(PermissionBehavior.DENY, "grep:*")), Set.of());

        PermissionResult result = checkViaPipeline(tool, "xargs grep foo", permCtx);

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("验收4b: wildcard allow 匹配（Bash(git *) → git status Allow）")
    void wildcard_allowMatches() {
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "git *")), Set.of(), Set.of());

        PermissionResult result = check(tool, "git status", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 5：SedValidation/BashModeValidation 接线（sed → mode → readOnly 顺序）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("验收5b: 安全 sed 命令 → Allow（sed -n '1p' 命中只读 flag 级表）")
    void sedConstraints_safeSedPassthrough() {
        // WHY: sed 约束通过后，read-only flag 级表（BASH_COMMAND_ALLOWLIST 含 sed，:621-623）
        //      flag 校验 -n∈safeFlags → Allow。旧链该命令 Passthrough；WF-A 新链只读放行更宽。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT, Set.of(), Set.of(), Set.of());

        PermissionResult result = check(tool, "sed -n '1p' access.log", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isInstanceOf(PermissionDecisionReason.Other.class);
    }

    @Test
    @DisplayName("验收5c: acceptEdits 模式文件系统命令 → Allow(Mode)（CC modeValidation.ts:38-50）")
    void modeValidation_wiredToAllow() {
        // WHY: path 约束层(3.4)先于 mode 分支(3.6)（CC bashPermissions.ts:1106-1122 第 3 步先于第 6 步）。
        //      旧命令 `mkdir /tmp/s09` 越界 → path 约束先行 Ask，mode 分支不可达；且本测试默认 cwd
        //      位于 .claude/worktrees/ 下，write/create 相对路径命中 auto-edit 危险目录(.claude)防护。
        //      改用干净的 effectiveCwd（临时目录，与生产正常项目 cwd 一致、不含危险目录）：
        //      `mkdir s09`（create inWorkingDir + ACCEPT_EDITS）经 path 约束放行后命中 mode 分支 → Allow(Mode)。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.ACCEPT_EDITS, Set.of(), Set.of(), Set.of());
        ToolUseContext tctx = ToolUseContext.of(AGENT, SESSION, permCtx.mode(),
            List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP, List.of(),
            permCtx, permCtx.mode(),
            Map.of(), false, "",
            java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "nexusai-mode-test"));

        PermissionResult result = check(tool, "mkdir s09", tctx);

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isEqualTo(new PermissionDecisionReason.Mode(PermissionMode.ACCEPT_EDITS));
    }

    @Test
    @DisplayName("验收5d: 非 acceptEdits 模式 mkdir 不自动放行")
    void modeValidation_defaultModeNotAllowed() {
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT, Set.of(), Set.of(), Set.of());

        PermissionResult result = check(tool, "mkdir /tmp/s09", toolCtx(permCtx));

        assertThat(result).isNotInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("验收5e: 只读放行——flag 级表 Allow / 名字层（ls/cat 等）Passthrough")
    void readOnly_commandAllowed() {
        // WHY: WF-A 新链只读放行仅两条路径：BASH_COMMAND_ALLOWLIST flag 级（grep/sed/ps/
        //      date 等）+ echo 名字层简单形式（:757）。ls/cat/head/tail/wc 仅在本名层
        //      BASH_READONLY_COMMAND_NAMES，checkPermissions 不查（IMP-OPD-05 拍板：比 CC
        //      严格，仅回退 echo），故维持 Passthrough。旧链 ls→Allow 断言已更新。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT, Set.of(), Set.of(), Set.of());
        ToolUseContext tctx = toolCtx(permCtx);

        // flag 级表命令 → Allow
        PermissionResult grep = check(tool, "grep -r foo .", tctx);
        assertThat(grep).as("grep 命中 flag 级表 BASH_COMMAND_ALLOWLIST → Allow")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) grep).reason())
            .isInstanceOf(PermissionDecisionReason.Other.class);

        // 名字层（无 echo 回退）→ Passthrough（比 CC 严格，IMP-OPD-05）
        // WHY: `ls /tmp` 越界 → path 约束(read 非 inWorkingDir)先行 Ask；改用 `ls ./`
        //      （read inWorkingDir）经 path 约束放行后，ls 仍不在 flag 级表 → Passthrough。
        assertThat(check(tool, "ls ./", tctx))
            .as("ls 不在 flag 级表（仅名字层）→ Passthrough（IMP-OPD-05）")
            .isInstanceOf(PermissionResult.Passthrough.class);
        assertThat(check(tool, "cat file.txt", tctx))
            .as("cat 不在 flag 级表 → Passthrough")
            .isInstanceOf(PermissionResult.Passthrough.class);
    }

    @Test
    @DisplayName("护栏: path 约束(3.4)先于 content allow(3.5)——sed 写文件即使有 sed:* allow 也不可绕过 → Ask")
    void pathConstraint_precedesContentAllow_sedWriteStillAsk() {
        // WHY（规则九）: CC bashPermissions.ts:1106-1139 —— 第 3 步 checkPathConstraints 先于
        //      第 4/5 步 exact/matching allow。sed 写文件（write 操作）在 path 约束层即被拦
        //      为 Ask（write 不可被 content allow 放行），`sed:*` content allow 规则在 3.5
        //      永远不可达。若本测试转绿为 Allow，说明 path 约束层被绕过/顺序错乱，构成
        //      "allow 规则放行文件写" 安全降级。该测试接管被删除的 3 个 sed 测试的安全护栏意图。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "sed:*")), Set.of(), Set.of());

        PermissionResult result = check(tool, "sed 'w /tmp/x' file", toolCtx(permCtx));

        assertThat(result)
            .as("sed 写文件被 path 约束(3.4)先行 Ask，sed:* content allow 规则不可绕过")
            .isInstanceOf(PermissionResult.Ask.class)
            .isNotInstanceOf(PermissionResult.Allow.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 6：危险命令语义 Ask（对齐 CC bashSecurity ask），非 deny / 非规则放行
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("验收6: 危险命令 → Ask（Other 归因，对齐 CC bashSecurity 危险检测恒 ask）")
    void dangerousCommand_askedBySafetyCheck() {
        // WHY: CC bashSecurity.ts 危险检测恒 ask 零 deny（grep -c "behavior: 'deny'" = 0）；
        //      bashPermissions.ts:1221-1238 bashCommandIsSafeAsync → ask + type:'other' +
        //      suggestions:[]（危险命令弹窗而非直接拒绝）。Java dangerous[] contains 对齐为 Ask。
        //      Security: 危险命令须人工确认，但可被 whole-tool allow 覆盖（CC 语义，非 bypass-immune）。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT, Set.of(), Set.of(), Set.of());

        PermissionResult result = check(tool, "rm -rf /", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        PermissionResult.Ask ask = (PermissionResult.Ask) result;
        assertThat(ask.reason())
            .as("危险命令 Ask 归因为 Other（对齐 CC bashPermissions.ts:1224 type:'other'）")
            .isInstanceOf(PermissionDecisionReason.Other.class);
    }

    @Test
    @DisplayName("验收6b: 危险命令命中 ask 规则 → Ask(Rule)（IMP-B2 dangerous[] 删除后 2b 内容 ask 规则优先）")
    void dangerousCommand_bypassesAskRule() {
        // WHY: IMP-B2 删除 dangerous[] 黑名单（TR-B1-⊕2）。旧链危险检测（step 2）在规则求值之前
        //      → Ask(Other)；删除后 `rm -rf /` 的 ask 规则由 2b 内容 ask 层（CC bashPermissions.ts
        //      matchingAskRules → ask(Rule)，CC :1195-1197 exact 早退）求值 → Ask(Rule 归因)。
        //      与 CC 一致：用户显式 ask 规则命中优先于 path/read-only 判定。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(), Set.of(rule(PermissionBehavior.ASK, "rm -rf /")));

        PermissionResult result = check(tool, "rm -rf /", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .as("ask 规则求值优先 → Rule 归因（CC matchingAskRules → ask(Rule)）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // 决策链
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("决策链a: passthrough 不再携带内容建议（建议生成迁出工具）")
    void passthrough_noContentSuggestions() {
        // WHY: WF-A 新链 Passthrough（:775-780）suggestions 恒空（旧 CC checkCommandAndSuggestRules
        //      的 Bash(npm install:*) 建议不再由工具生成；建议迁外层管线 layer3/分类器路径）。
        //      断言空即守住"工具不再生成建议"这一行为迁移。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT, Set.of(), Set.of(), Set.of());

        PermissionResult result = check(tool, "npm install foo", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Passthrough.class);
        assertThat(((PermissionResult.Passthrough) result).suggestions())
            .as("新链工具 Passthrough 不生成内容建议")
            .isEmpty();
    }

    @Test
    @DisplayName("决策链b: prefix deny 先于 exact allow → Deny（pipeline 1a）")
    void prefixDeny_takesPrecedenceOverExactAllow() {
        // WHY: 同一命令命中 exact allow 与 prefix deny → 新链 pipeline 1a（content deny）
        //      在 1c（工具 allow）之前 → Deny。旧链主状态机同序（bashToolCheckPermission
        //      deny :1082-1092 先于 exact allow :1124-1127），但求值位置迁外层管线。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "git status")),
            Set.of(rule(PermissionBehavior.DENY, "git:*")), Set.of());

        PermissionResult result = checkViaPipeline(tool, "git status", permCtx);

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
        assertThat(((PermissionResult.Deny) result).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("决策链d: content ask 规则命中 → Ask + Rule 归因（ask 先于 exact allow，[WF2-02]）")
    void contentAskRule_matchesToAskWithRuleAttribution() {
        // WHY: [WF2-02] 内容 ask 桶接线 —— CC bashToolCheckPermission 2b
        //      （bashPermissions.ts:1095-1104）matchingAskRules[0] → Ask + {type:'rule',
        //      rule}，且 ask 先于 exact allow（2b 在 4/5 之前）。`git status` 同时命中
        //      Bash(git:*) ask 前缀与 Bash(git status) allow 精确，ask 必须赢（Ask + Rule
        //      归因 + ruleBehavior==ASK），否则 exact allow 覆盖 ask 破坏对齐。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "git status")),
            Set.of(), Set.of(rule(PermissionBehavior.ASK, "git:*")));

        PermissionResult result = check(tool, "git status", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        PermissionResult.Ask ask = (PermissionResult.Ask) result;
        assertThat(ask.reason()).isInstanceOf(PermissionDecisionReason.Rule.class);
        PermissionDecisionReason.Rule ruleReason = (PermissionDecisionReason.Rule) ask.reason();
        assertThat(ruleReason.rule().ruleBehavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(ruleReason.rule().ruleValue().ruleContent()).isEqualTo("git:*");
    }

    @Test
    @DisplayName("决策链j: content ask 规则 BYPASS 模式下仍 Ask（1f bypass-immune，[WF2-02]）")
    void contentAskRule_bypassImmuneViaPipeline() {
        // WHY: [WF2-02] 内容 ask 规则（Bash(npm publish:*)）在工具 checkPermissions 产出
        //      Ask + Rule 归因后，管线 1f（CheckLayer1f_ContentSpecificAskRule）在 2a(bypass)
        //      之前消费为 bypass-immune —— CC permissions.ts:1238-1250：即使 BYPASS_PERMISSIONS
        //      模式，用户显式配置的 content ask rule 也必须 ask，不能被 bypass 覆盖。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.BYPASS_PERMISSIONS,
            Set.of(), Set.of(), Set.of(rule(PermissionBehavior.ASK, "npm publish:*")));

        PermissionResult result = checkViaPipeline(tool, "npm publish --tag next", permCtx);

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("决策链e: 危险命令 + exact allow → 仍 Ask（危险检测先于 allow 规则）")
    void exactAllow_dangerousCommand_denied() {
        // WHY: 新链危险检测（step 2）在 content allow 规则（step 5）之前 → `rm -rf /`
        //      有精确 allow 也 Ask(Other)。安全底线：危险命令须人工确认。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "rm -rf /")), Set.of(), Set.of());

        PermissionResult result = check(tool, "rm -rf /", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .isInstanceOf(PermissionDecisionReason.Other.class);
    }

    @Test
    @DisplayName("决策链f: sed 写（-i）被 path 约束先行 Ask，sed:* allow 不可绕过（对齐 CC）")
    void sedPrefixAllow_sedDashIConstraintBypassed() {
        // WHY（规则九）: 对齐 CC pathValidation.ts:141-263 isPathAllowed 顺序——path 约束（step 3）
        //      在 content allow 规则（step 4）之前；sed 属 write 操作，DEFAULT 模式下行内路径
        //      需 acceptEdits 才 auto-allow（step 3 :207），`Bash(sed:*)` 是工具内容规则而非
        //      Edit 路径规则，不命中 step 4 matchingRuleForInput('edit') → step 5 不落任何允许
        //      范围 → Ask。旧断言（Allow）为旧 allow-优先语义，已废弃。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "sed:*")), Set.of(), Set.of());

        PermissionResult result = check(tool, "sed -i 's/a/b/' file", toolCtx(permCtx));

        assertThat(result)
            .as("sed -i 写文件：path 约束（write 需 acceptEdits auto-allow）先行 Ask，sed:* allow 不可绕过")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("决策链g: sed 写（w 命令）被 path 约束先行 Ask，sed:* allow 不可绕过（对齐 CC）")
    void sedPrefixAllow_sedWConstraintBypassed() {
        // WHY（规则九）: 与决策链f 同源——sed 'w /tmp/x' file 的写操作经 checkPathConstraints
        //      （CC bashToolCheckPermission step 3 :1106-1122）在 content allow 规则（step 5 :1129-1139）
        //      之前求值；DEFAULT 模式 write 不 auto-allow → Ask。守卫测试
        //      pathConstraint_precedesContentAllow_sedWriteStillAsk 已锁定同命令同结论。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "sed:*")), Set.of(), Set.of());

        PermissionResult result = check(tool, "sed 'w /tmp/x' file", toolCtx(permCtx));

        assertThat(result)
            .as("sed 'w' 写文件：path 约束先行 Ask，sed:* allow 不可绕过")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("决策链h: prefix allow 命中危险命令 → 仍 Ask（危险 contains 先于 allow）")
    void prefixAllow_dangerousCommand_denied() {
        // WHY: 旧链 `rm -rf /tmp/`id`` + Bash(rm:*) → 注入门 Ask(misparsing)（CC :1217-1239）；
        //      新链危险检测（"rm -rf /" contains）在 allow 之前 → Ask。命令替换注入
        //      面由危险 contains 兜住。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "rm:*")), Set.of(), Set.of());

        PermissionResult result = check(tool, "rm -rf /tmp/`id`", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("决策链i: 反引号危险命令 + exact allow → Allow（IMP-B2 dangerous[] 删除后对齐 CC exact-allow 覆盖）")
    void exactAllow_dangerousBacktick_denied() {
        // WHY: IMP-B2 删除 dangerous[] 黑名单（TR-B1-⊕2）。旧链 `rm -rf /tmp/`id`` 含子串 "rm -rf /"
        //      被 dangerous[] 拦为 Ask；删除后 CC 语义成立：misparsing-ask 的 exact-allow 覆盖
        //      （CC bashPermissions.ts:2105-2117：remainder 仍 misparsing-ask 时先以 exact 模式查
        //      allow 桶，命中显式 allow 规则则 allow 覆盖——用户对该具体命令做过 conscious choice）
        //      → 3.3a 覆盖 → Allow。与 CC :1195-1196 exact match 早退一致。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "rm -rf /tmp/`id`")), Set.of(), Set.of());

        PermissionResult result = check(tool, "rm -rf /tmp/`id`", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("决策链c: git push -f（危险写）→ pipeline 1a deny 规则 → Deny（IMP-B2 dangerous[] 删除后）")
    void dangerousGitPushForce_denied() {
        // WHY: IMP-B2 删除 dangerous[] 黑名单（TR-B1-⊕2）。`git push -f` 不再被工具内危险 contains
        //      拦为 Ask；用户 deny 规则（git push -f / git:*）由 pipeline 1a 内容 deny 求值 → Deny
        //      （对齐 CC bashToolHasPermission：bashToolCheckPermission matchingDenyRules → deny，
        //      先于命令注入安全检测）。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(),
            Set.of(rule(PermissionBehavior.DENY, "git push -f"), rule(PermissionBehavior.DENY, "git:*")),
            Set.of());

        PermissionResult result = checkViaPipeline(tool, "git push -f", permCtx);

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // 沙箱 auto-allow：deny 规则优先（CC checkSandboxAutoAllow）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("沙箱a: 无 deny/ask 规则 → sandbox auto-allow Allow")
    void sandboxAutoAllow_noExplicitRule() {
        BashTool tool = new BashTool();
        injectSandboxManager(tool, new SandboxManager(true, true));
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT, Set.of(), Set.of(), Set.of());

        PermissionResult result = check(tool, "ls /tmp", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isInstanceOf(PermissionDecisionReason.Other.class);
    }

    @Test
    @DisplayName("沙箱b: deny 规则优先于 sandbox auto-allow → Deny（pipeline 1a 先于 1c sandbox）")
    void sandboxAutoAllow_denyRuleWins() {
        // WHY: CC checkSandboxAutoAllow（bashPermissions.ts:1276-1348）deny 优先于 auto-allow。
        //      新链 pipeline 1a（content deny）在 1c（sandbox auto-allow :672-680）之前 →
        //      `ls /tmp` 有 Bash(ls:*) deny 时恒 Deny，sandbox 不覆盖用户 deny。
        BashTool tool = new BashTool();
        injectSandboxManager(tool, new SandboxManager(true, true));
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(rule(PermissionBehavior.DENY, "ls:*")), Set.of());

        PermissionResult result = checkViaPipeline(tool, "ls /tmp", permCtx);

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("沙箱c: compound 子命令 deny 规则命中 → 沙箱预检 Deny（S01，原 Allow 缺口修复）")
    void sandboxAutoAllow_compoundSubcommandDenyWins() {
        // WHY (WF-4 S01): 原测试断言 Allow——compound 子命令 deny（rm:*）匹配属既有 pipeline 1a
        //      缺口（Java 不逐子命令求值 deny）。S01 在 BashTool 沙箱 auto-allow 前接入
        //      matchingDenyOrAskRule 逐子命令预检（对齐 CC checkSandboxAutoAllow :1293-1336）→
        //      `echo hi && rm -rf /tmp/x` 命中 Bash(rm:*) 子命令 → 全命令 Deny（堵 deny 绕过，R3）。
        BashTool tool = new BashTool();
        injectSandboxManager(tool, new SandboxManager(true, true));
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(rule(PermissionBehavior.DENY, "rm:*")), Set.of());

        PermissionResult result = check(tool, "echo hi && rm -rf /tmp/x", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("沙箱d: 全命令 deny 规则 → 沙箱预检 Deny（S01 checkSandboxAutoAllow :1284）")
    void sandboxAutoAllow_fullCommandDenyWins() {
        // WHY (WF-4 S01): 对齐 CC checkSandboxAutoAllow（bashPermissions.ts:1276-1293）——auto-allow
        //      前先查全命令 deny 规则，deny 优先于放行（堵"沙箱即安全边界"跳过用户 deny 的绕过）。
        //      与 沙箱b（checkViaPipeline，走 pipeline 1a）互补：本测试直调 tool.checkPermissions
        //      （工具内 S01 预检），验证 deny 在工具内也先于 auto-allow 生效。
        BashTool tool = new BashTool();
        injectSandboxManager(tool, new SandboxManager(true, true));
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(rule(PermissionBehavior.DENY, "ls:*")), Set.of());

        PermissionResult result = check(tool, "ls /tmp", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Deny.class);
    }

    @Test
    @DisplayName("沙箱e: compound 子命令 ask 规则 → 沙箱预检 Ask（S01 checkSandboxAutoAllow :1327）")
    void sandboxAutoAllow_compoundSubcommandAskPrompts() {
        // WHY (WF-4 S01): 对齐 CC checkSandboxAutoAllow（bashPermissions.ts:1327-1335）——compound
        //      子命令命中 ask 规则（{@code echo hi && ls /tmp} 命中 Bash(ls:*)）→ 弹窗 Ask，不自动放行。
        //      2b 只做全命令 ask 匹配（[S09] 删除后无子命令展开），故本路径专由 S01 逐子命令预检承接。
        BashTool tool = new BashTool();
        injectSandboxManager(tool, new SandboxManager(true, true));
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(), Set.of(), Set.of(rule(PermissionBehavior.ASK, "ls:*")));

        PermissionResult result = check(tool, "echo hi && ls /tmp", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).reason())
            .as("子命令 ask 命中 → Rule 归因（CC :1332 decisionReason.type 'rule'）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    // ════════════════════════════════════════════════════════════════════
    // echo 名字层简单形式（CC readOnlyValidation.ts:1516，WF-A A7c 回退）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("echo 简单形式 → Allow（名字层回退）")
    void echo_simpleForm_allowed() {
        // WHY: readOnlyValidation.ts:1516 echo 正则 —— 无管道/无重定向/无变量/无 glob 的
        //      echo 简单形式只读放行（BashTool :757）。旧链 echo 走 BashRuleMatcher 规则；
        //      新链经名字层正则回退 Allow。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT, Set.of(), Set.of(), Set.of());

        PermissionResult result = check(tool, "echo hi", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("echo hi 2>&1 → Allow（stderr 重定向预剥离只读）")
    void echo_redir2And1_allowed() {
        // WHY: isCommandReadOnly:1682-1686 先剥尾随 ` 2>&1` 再匹配 echo 正则 —— stderr 合并
        //      重定向只读，仍放行（matchesBashReadonlyEcho 预剥离）。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT, Set.of(), Set.of(), Set.of());

        PermissionResult result = check(tool, "echo hi 2>&1", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
    }

    @Test
    @DisplayName("echo *（未引号 glob）→ 拒绝（containsUnquotedExpansion 守卫）")
    void echo_unquotedGlob_rejected() {
        // WHY: isCommandReadOnly:1600-1630 containsUnquotedExpansion —— bash 运行时 glob 展开
        //      可能把文件名单词展开成危险 flag，静态期无法验证 → 拒绝（非 Allow）。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT, Set.of(), Set.of(), Set.of());

        PermissionResult result = check(tool, "echo *", toolCtx(permCtx));

        assertThat(result)
            .as("未引号 glob 展开无法静态验证 → 拒绝只读放行")
            .isNotInstanceOf(PermissionResult.Allow.class);
    }

    // ════════════════════════════════════════════════════════════════
    // 验收 7：isNormalizedCdCommand wrapper/env 前缀剥除（RV-D-01 NG-2 闭环）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("验收7: wrapper/env 前缀 cd 被识别（timeout/FORCE_COLOR/nohup → true，git/ls 控制组 false）")
    void isNormalizedCdCommand_stripsSafeWrappers() {
        // WHY（规则九）: CC isNormalizedCdCommand（bashPermissions.ts:2603-2611）先
        // stripSafeWrappers 再匹配首词；裸前缀匹配会漏检 `timeout 10 cd` → multi-cd /
        // cd+git 守卫失效。断言 wrapper/env 前缀不掩蔽 cd（裸 cd 控制组 true，非 cd
        // 控制组 false），该断言唯一验证 cd 检测的前置剥离语义。
        assertThat(BashTool.isNormalizedCdCommand("cd sub")).isTrue();
        assertThat(BashTool.isNormalizedCdCommand("timeout 10 cd sub")).isTrue();
        assertThat(BashTool.isNormalizedCdCommand("FORCE_COLOR=1 cd sub")).isTrue();
        assertThat(BashTool.isNormalizedCdCommand("nohup cd sub")).isTrue();
        assertThat(BashTool.isNormalizedCdCommand("git status")).isFalse();
        assertThat(BashTool.isNormalizedCdCommand("ls -la")).isFalse();
    }

    @Test
    @DisplayName("验收7b: 引号包裹首词 cd 被识别（'cd' .claude / \"cd\" .claude / 'popd' → true）")
    void isNormalizedCdCommand_stripsShellQuotes() {
        // WHY（规则九）: CC isNormalizedCdCommand 经 tryParseShellCommand（shell-quote）把
        // `'cd' .claude` 归一化为 tokens[0]==='cd'；裸 startsWith 匹配会漏检引号包裹首词，
        // 使 `'cd' .claude && mv ...` 绕过 cd+write 守卫。断言引号包裹首词不被漏检。
        assertThat(BashTool.isNormalizedCdCommand("'cd' .claude")).isTrue();
        assertThat(BashTool.isNormalizedCdCommand("\"cd\" .claude")).isTrue();
        assertThat(BashTool.isNormalizedCdCommand("'popd'")).isTrue();
        assertThat(BashTool.isNormalizedCdCommand("'git' status")).isFalse();
    }

    @Test
    @DisplayName("验收7c: isNormalizedGitCommand 剥 wrapper/env + 引号首词归一化（xargs git → true）")
    void isNormalizedGitCommand_stripsSafeWrappersAndQuotes() {
        // WHY（规则九）: CC isNormalizedGitCommand（bashPermissions.ts:2567-2580）快路径后经
        // stripSafeWrappers + tryParseShellCommand 归一化首词，防 `'git' status` / `NO_COLOR=1
        // git status` / `timeout 5 git push` 绕过裸正则；xargs git 按 token 精确判定
        // （contains("git") 而非子串 " git"）。断言 wrapper/env/引号均不掩蔽 git，xargs 精确
        // token 语义（xargs grep foo 无 git token → false 已由验收4a 覆盖）。
        assertThat(BashTool.isNormalizedGitCommand("timeout 5 git push")).isTrue();
        assertThat(BashTool.isNormalizedGitCommand("FORCE_COLOR=1 git status")).isTrue();
        assertThat(BashTool.isNormalizedGitCommand("NO_COLOR=1 git status")).isTrue();
        assertThat(BashTool.isNormalizedGitCommand("'git' status")).isTrue();
        assertThat(BashTool.isNormalizedGitCommand("\"git\" status")).isTrue();
        assertThat(BashTool.isNormalizedGitCommand("xargs git status")).isTrue();
        assertThat(BashTool.isNormalizedGitCommand("xargs -0 git log")).isTrue();
        assertThat(BashTool.isNormalizedGitCommand("gitstatus")).isFalse();
        assertThat(BashTool.isNormalizedGitCommand("mygit status")).isFalse();
        assertThat(BashTool.isNormalizedGitCommand("ls -la")).isFalse();
    }

    // ════════════════════════════════════════════════════════════════════
    // FIX-A-R3：misparsing ask 的 exact-allow 覆盖（对齐 CC bashPermissions.ts:2105-2117）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("FIX-A-R3a: exact allow 规则覆盖 misparsing ask（echo $(id) → Allow 非 Ask）")
    void exactAllowRule_overridesMisparsingAsk() {
        // WHY（规则九）: CC misparsing gate（bashPermissions.ts:2105-2117）在 remainder 仍
        //      misparsing-ask 时，先以 exact 模式查 allow 桶（bashToolCheckExactMatchPermission
        //      :991-1021），命中显式 allow 规则则 allow 覆盖——用户对 `echo $(id)` 做过
        //      conscious choice，即使它含 $() 注入向量也不应再弹 Ask。断言结果必须是 Allow
        //      且归因是命中的 Rule（非 Other/Ask），证明 exact-allow 是覆盖来源，而非误判
        //      为只读 echo 放行。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "echo $(id)")), Set.of(), Set.of());

        PermissionResult result = check(tool, "echo $(id)", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("FIX-A-R3b: 同命令无 exact allow 规则 → 仍 Ask（锚定覆盖来源是 allow 桶）")
    void misparsingAsk_noExactAllowRule_returnsAsk() {
        // WHY（规则九）: 负向锚定——同一条 `echo $(id)` 注入向量，在无 exact allow 规则时
        //      仍返回 Ask（isBashSecurityCheckForMisparsing=true），证明 FIX-A-R3a 的 Allow
        //      来自 exact-allow 覆盖，而非 misparsing gate 本身被削弱。若本测试转绿（返回
        //      Allow），说明 misparsing 门禁被误伤，构成安全降级。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT, Set.of(), Set.of(), Set.of());

        PermissionResult result = check(tool, "echo $(id)", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) result).isBashSecurityCheckForMisparsing()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════
    // IMP-3 Bash 主链对齐（R2 exact 优先 / R4 operator 重检 / R5 扇出上限）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("IMP-3 R2: exact allow 优先于 prefix allow（CC bashPermissions.ts:1124-1127 先于 :1129-1139）")
    void exactAllow_precedesPrefixAllow() {
        // WHY（规则九）: OPD-WF4-01-R2 —— 3.5 原用前缀模式查询（RuleQuery exactMode=false，
        //      EV-WF4-01-061），exact 优先语义不成立。同一命令同时命中 exact 与 prefix 规则
        //      时，CC 先返回 exact allow（bashToolCheckExactMatchPermission 结果），prefix
        //      仅作兜底。断言归因规则为 exact 的 `git status`（非 prefix 的 `git:*`）。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT,
            Set.of(rule(PermissionBehavior.ALLOW, "git:*"),
                rule(PermissionBehavior.ALLOW, "git status")),
            Set.of(), Set.of());

        PermissionResult result = check(tool, "git status", toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        PermissionResult.Allow allow = (PermissionResult.Allow) result;
        PermissionDecisionReason.Rule ruleReason = (PermissionDecisionReason.Rule) allow.reason();
        assertThat(ruleReason.rule().ruleValue().ruleContent())
            .as("exact 规则优先归因（CC :1124-1127）")
            .isEqualTo("git status");
    }

    @Test
    @DisplayName("IMP-3 R4: operator all-allow 原命令重定向重检 → Ask（CC bashPermissions.ts:1992-2056）")
    void operatorAllAllow_originalCommandRecheck(@TempDir java.nio.file.Path tempDir) {
        // WHY（规则九）: OPD-WF4-01-R4 / EV-WF4-01-051 —— 管道段剥离重定向后逐段 allow，
        //      原命令 `> out.txt` 重定向目标在段级被剥掉（echo hi | grep foo），若不重检可
        //      绕过输出重定向校验。CC :1992-2056 在 operator allow 后对原命令补
        //      bashCommandIsSafeAsync + checkPathConstraints → 重定向须 Ask。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT, Set.of(), Set.of(), Set.of());
        ToolUseContext tctx = ToolUseContext.of(AGENT, SESSION, permCtx.mode(),
            List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP, List.of(),
            permCtx, permCtx.mode(),
            Map.of(), false, "", tempDir);

        PermissionResult result = check(tool, "echo hi | grep foo > out.txt", tctx);

        assertThat(result)
            .as("operator all-allow 后原命令含输出重定向 → 重检 Ask（CC :1992-2056）")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("IMP-3 R5: 子命令扇出超 50 上限 → Ask（CC bashPermissions.ts:2162-2179，CC-643 防 REPL 冻结）")
    void subcommandFanout_overCap_asks() {
        // WHY（规则九）: OPD-WF4-01-R5 / EV-WF4-01-003/054 —— Java 无扇出上限；CC
        //      MAX_SUBCOMMANDS_FOR_SECURITY_CHECK=50：splitCommand 切分后子命令数超限 →
        //      ask（"too many to safety-check individually"），防极端复合命令冻结 REPL。
        BashTool tool = new BashTool();
        ToolPermissionContext permCtx = ctx(PermissionMode.DEFAULT, Set.of(), Set.of(), Set.of());

        // 51 个子命令（echo 0 && echo 1 && ... && echo 50）> 50
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= 50; i++) {
            if (i > 0) {
                sb.append(" && ");
            }
            sb.append("echo ").append(i);
        }

        PermissionResult result = check(tool, sb.toString(), toolCtx(permCtx));

        assertThat(result).isInstanceOf(PermissionResult.Ask.class);
        PermissionResult.Ask ask = (PermissionResult.Ask) result;
        assertThat(ask.reason()).isInstanceOf(PermissionDecisionReason.Other.class);
    }
}
