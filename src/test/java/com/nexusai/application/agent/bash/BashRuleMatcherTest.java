package com.nexusai.application.agent.bash;

import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.ToolPermissionContext;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S09] Bash 规则匹配器测试 · 对齐 CC bashPermissions.ts filterRulesByContentsMatchingInput
 * (:778-935) / stripSafeWrappers (:524-615) / stripAllLeadingEnvVars (:733-776) /
 * suggestionForExactCommand (:266-295)。
 *
 * <p>验收标准覆盖：
 * <ol>
 *   <li>{@code FOO=bar cmd} env 前缀被 deny/ask 规则匹配（防绕过）</li>
 *   <li>timeout/nice/stdbuf/nohup wrapper 剥离匹配；timeout flag 值白名单</li>
 *   <li>{@code cd x && evil} compound guard（prefix 规则不匹配 compound）</li>
 *   <li>xargs 前缀匹配；wildcard exact 模式拒绝</li>
 * </ol>
 *
 * <p>匹配参数约定（对齐 CC matchingRulesForInput :937-986）：
 * deny/ask 桶 → stripAllEnvVars=true + skipCompoundCheck=true；
 * allow 桶 → stripAllEnvVars=false + skipCompoundCheck=false。
 */
@DisplayName("[S09] Bash 规则匹配器（CC bashPermissions.ts 语义）")
class BashRuleMatcherTest {

    private static final PermissionRule DENY_RULE = rule(PermissionBehavior.DENY, "claude:*");
    private static final PermissionRule ALLOW_RULE = rule(PermissionBehavior.ALLOW, "npm run:*");

    private static PermissionRule rule(PermissionBehavior behavior, String content) {
        return new PermissionRule(PermissionRuleSource.USER_SETTINGS, behavior,
            PermissionRuleValue.withContent("Bash", content));
    }

    /** 仅含一条 Bash deny 规则的权限上下文（matchingDenyOrAskRule 主链测试用）。 */
    private static ToolPermissionContext denyCtx(String content) {
        Map<PermissionRuleSource, Set<PermissionRule>> denyMap = new EnumMap<>(PermissionRuleSource.class);
        denyMap.put(PermissionRuleSource.USER_SETTINGS, Set.of(rule(PermissionBehavior.DENY, content)));
        return ToolPermissionContext.of(PermissionMode.DEFAULT,
            new EnumMap<>(PermissionRuleSource.class), denyMap,
            new EnumMap<>(PermissionRuleSource.class), Map.of());
    }

    /** deny/ask 桶参数：stripAllEnvVars=true + skipCompoundCheck=true。 */
    private static boolean denyMatch(String ruleContent, String command) {
        return BashRuleMatcher.matchesRuleContent(ruleContent, command, false, true, true);
    }

    /** allow 桶参数：stripAllEnvVars=false + skipCompoundCheck=false。 */
    private static boolean allowMatch(String ruleContent, String command) {
        return BashRuleMatcher.matchesRuleContent(ruleContent, command, false, false, false);
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 1：env 前缀（FOO=bar cmd）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("验收1a: deny 规则匹配任意 env 前缀（FOO=bar claude → Bash(claude:*)）")
    void deny_matchesArbitraryEnvPrefix() {
        assertThat(denyMatch("claude:*", "FOO=bar claude run"))
            .as("CC :811-853 deny 防 env 前缀绕过（HackerOne #3543050 修复链）").isTrue();
        assertThat(denyMatch("claude:*", "A=1 B=2 claude"))
            .as("多 env 前缀同样剥离").isTrue();
    }

    @Test
    @DisplayName("验收1b: allow 规则只剥 SAFE_ENV_VARS 白名单 env")
    void allow_stripsOnlySafeEnvVars() {
        // NODE_ENV ∈ SAFE_ENV_VARS（CC :378-430）→ 剥离后匹配
        assertThat(allowMatch("npm run:*", "NODE_ENV=prod npm run build"))
            .as("白名单 env 剥离后 npm run 前缀命中").isTrue();
        // FOO ∉ SAFE_ENV_VARS → allow 不剥离（防 DOCKER_HOST=evil docker ps 命中 docker ps:*）
        assertThat(allowMatch("npm run:*", "FOO=bar npm run build"))
            .as("非白名单 env 不剥离 → 前缀不命中（CC :806-809）").isFalse();
    }

    @Test
    @DisplayName("验收1c: 单/双引号、append、数组 env 赋值剥离（CC stripAllLeadingEnvVars :733-776）")
    void stripAllLeadingEnvVars_quoteAndAppendForms() {
        assertThat(BashRuleMatcher.stripAllLeadingEnvVars("FOO='x'y\"z\" cmd", null)).isEqualTo("cmd");
        assertThat(BashRuleMatcher.stripAllLeadingEnvVars("FOO+=bar cmd", null)).isEqualTo("cmd");
        assertThat(BashRuleMatcher.stripAllLeadingEnvVars("FOO[0]=bar cmd", null)).isEqualTo("cmd");
        // 含注入字符的 env 值不剥离（CC :759-760 排除 $`;|&()<>）
        assertThat(BashRuleMatcher.stripAllLeadingEnvVars("FOO=$(id) cmd", null)).isEqualTo("FOO=$(id) cmd");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 2：wrapper 剥离 + timeout flag 值白名单
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("验收2a: timeout/time/nice/stdbuf/nohup wrapper 剥离（CC stripSafeWrappers :524-615）")
    void stripSafeWrappers_removesWrappers() {
        assertThat(BashRuleMatcher.stripSafeWrappers("timeout 5 rm -rf x")).isEqualTo("rm -rf x");
        assertThat(BashRuleMatcher.stripSafeWrappers("timeout -k 5 --signal=TERM 10s ls")).isEqualTo("ls");
        assertThat(BashRuleMatcher.stripSafeWrappers("time ls")).isEqualTo("ls");
        assertThat(BashRuleMatcher.stripSafeWrappers("nice -n 5 ls")).isEqualTo("ls");
        assertThat(BashRuleMatcher.stripSafeWrappers("nice -5 ls")).isEqualTo("ls");
        assertThat(BashRuleMatcher.stripSafeWrappers("stdbuf -oL ls")).isEqualTo("ls");
        assertThat(BashRuleMatcher.stripSafeWrappers("nohup ls")).isEqualTo("ls");
    }

    @Test
    @DisplayName("验收2b: timeout flag 值白名单拒绝注入（timeout -k$(id) 不得剥离，CC :617-620）")
    void stripSafeWrappers_timeoutFlagValueWhitelist() {
        // $(id) 不在 [A-Za-z0-9_.+-] 白名单 → 不剥离 → Bash(ls:*) 不得命中
        assertThat(BashRuleMatcher.stripSafeWrappers("timeout -k$(id) 10 ls"))
            .as("flag 值含注入字符 → 保持原命令").isEqualTo("timeout -k$(id) 10 ls");
        assertThat(denyMatch("ls:*", "timeout -k$(id) 10 ls"))
            .as("不剥离的注入命令不得命中 ls:*").isFalse();
        assertThat(denyMatch("ls:*", "timeout -k5 10 ls"))
            .as("合法 flag 值正常剥离命中").isTrue();
    }

    @Test
    @DisplayName("验收2c: wrapper 剥离后 deny 命中（timeout 5 rm -rf x → Bash(rm:*)）")
    void deny_matchesAfterWrapperStrip() {
        assertThat(denyMatch("rm:*", "timeout 5 rm -rf x"))
            .as("CC :803-809 wrapper 剥离派生候选").isTrue();
        assertThat(denyMatch("rm:*", "nohup rm -rf x"))
            .as("nohup 包装同样剥离").isTrue();
    }

    @Test
    @DisplayName("验收2d: 整行注释剥离（CC stripCommentLines :508-522）")
    void stripCommentLines_fullLineComments() {
        assertThat(BashRuleMatcher.stripSafeWrappers("# 注释\nls -la"))
            .as("注释行剥离后 ls -la").isEqualTo("ls -la");
        assertThat(BashRuleMatcher.stripCommentLines("# all comment"))
            .as("全注释返回原命令（CC :516-519）").isEqualTo("# all comment");
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 3：compound guard（cd x && evil）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("验收3a: allow prefix 规则不匹配 compound（Bash(cd:*) 不得命中 cd x && evil）")
    void allow_prefixDoesNotMatchCompound() {
        assertThat(allowMatch("cd:*", "cd x && evil"))
            .as("CC :883-893 prefix 规则 compound guard").isFalse();
        assertThat(allowMatch("cd:*", "cd x"))
            .as("单命令正常命中").isTrue();
    }

    @Test
    @DisplayName("验收3b: filter 不展开 compound 子命令（DEL-WF4-02）+ 主链 matchingDenyOrAskRule 逐子命令 deny")
    void compound_subcommandDenyMovedToMainChain() {
        // WHY (DEL-WF4-02): CC filterRulesByContentsMatchingInput（bashPermissions.ts:826-853）只做
        //   env/wrapper 交替剥离，无子命令展开；compound 子命令 deny/ask 由主链逐子命令检查
        //   （checkSandboxAutoAllow :1303-1336 / checkSemanticsDeny :1431-1453）。Java 删除 [S09]
        //   兜底后 filter 不再命中 compound（对齐 CC），复合子命令 deny 收敛于
        //   matchingDenyOrAskRule（S01 接入 BashTool 沙箱 auto-allow 预检，防 `echo hi && rm -rf /`
        //   绕过 Bash(rm:*)）。
        assertThat(denyMatch("rm:*", "echo hi && rm -rf /"))
            .as("filter 不再展开 compound 子命令（CC filterRulesByContentsMatchingInput 语义）").isFalse();
        assertThat(denyMatch("evil:*", "cd x && evil"))
            .as("filter 不再展开 compound 子命令（CC filterRulesByContentsMatchingInput 语义）").isFalse();
        // 主链逐子命令检查：Bash(rm:*) 命中 "rm -rf /" 子命令 → deny
        BashRuleMatcher.DenyOrAskRule denyOrAsk =
            BashRuleMatcher.matchingDenyOrAskRule("echo hi && rm -rf /", denyCtx("rm:*"));
        assertThat(denyOrAsk).as("主链 compound 逐子命令 deny 命中").isNotNull();
        assertThat(denyOrAsk.deny()).as("子命令 deny 优先返回 deny 标记").isTrue();
        assertThat(denyOrAsk.rule().ruleValue().ruleContent()).isEqualTo("rm:*");
        // 对偶：无 deny/ask 命中 → null（→ 沙箱 auto-allow 放行）
        assertThat(BashRuleMatcher.matchingDenyOrAskRule("echo hi", denyCtx("rm:*")))
            .as("无 deny/ask 命中返回 null").isNull();
    }

    @Test
    @DisplayName("验收3c: wildcard allow 规则不匹配 compound（Bash(cd *) 不得命中 cd /path && evil）")
    void allow_wildcardDoesNotMatchCompound() {
        assertThat(allowMatch("cd *", "cd /path && python3 evil.py"))
            .as("CC :923-928 wildcard compound guard").isFalse();
        assertThat(allowMatch("cd *", "cd /path"))
            .as("单命令通配正常命中").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 验收 4：xargs 前缀 + wildcard exact 拒绝
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("验收4a: xargs 前缀匹配（Bash(grep:*) 命中 xargs grep pattern，CC :902-911）")
    void prefixMatchesXargs() {
        assertThat(denyMatch("grep:*", "xargs grep pattern"))
            .as("裸 xargs 前缀命中").isTrue();
        assertThat(denyMatch("rm:*", "xargs rm file"))
            .as("deny 规则经 xargs 阻断").isTrue();
        assertThat(denyMatch("grep:*", "xargs -n1 grep pattern"))
            .as("带 flag 的 xargs 不命中（自然词边界）").isFalse();
    }

    @Test
    @DisplayName("验收4b: wildcard 匹配 + 尾随参数可选（Bash(git *) 命中 git status / 裸 git）")
    void wildcard_matchesWithOptionalTrailingArgs() {
        assertThat(allowMatch("git *", "git status")).isTrue();
        assertThat(allowMatch("git *", "git"))
            .as("CC shellRuleMatching.ts:136-145 尾随 * 可选").isTrue();
        assertThat(allowMatch("git \\*", "git status"))
            .as("\\* 字面星不得命中 git status").isFalse();
    }

    @Test
    @DisplayName("验收4c: wildcard exact 模式拒绝（foo * 不得匹配 foo arg && curl evil.com，CC :915-922）")
    void wildcard_exactModeRejected() {
        assertThat(BashRuleMatcher.matchesRuleContent("foo *", "foo arg && curl evil.com", true, false, false))
            .as("exact 模式通配拒绝").isFalse();
        assertThat(BashRuleMatcher.matchesRuleContent("foo *", "foo arg", true, false, false))
            .as("exact 模式非 compound 命令仍拒绝（exact 模式只精确匹配）").isFalse();
        assertThat(BashRuleMatcher.matchesRuleContent("foo", "foo", true, false, false))
            .as("exact 规则 exact 模式精确命中").isTrue();
    }

    @Test
    @DisplayName("验收4d: 重定向剥离（Bash(python:*) 命中 python script.py > output.txt，CC :789-793）")
    void redirectionStrippedForMatching() {
        assertThat(allowMatch("python:*", "python script.py > output.txt"))
            .as("输出重定向剥离后前缀命中").isTrue();
        assertThat(allowMatch("python:*", "python script.py 2>>err.log > out.txt"))
            .as("多重重定向全部剥离").isTrue();
        // 引号内 > 不是重定向
        assertThat(allowMatch("echo:*", "echo 'a > b'"))
            .as("引号内 > 保留，echo a > b 仍命中 echo:*").isTrue();
    }

    // ════════════════════════════════════════════════════════════════════
    // 建议生成（CC suggestionForExactCommand :266-295）
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("建议a: 单行命令 → 2 词前缀建议（npm run build → Bash(npm run:*)）")
    void suggestion_singleLinePrefix() {
        List<PermissionUpdate> updates = BashRuleMatcher.suggestionForExactCommand("npm run build");
        assertThat(updates).hasSize(1);
        PermissionUpdate.AddRules add = (PermissionUpdate.AddRules) updates.get(0);
        assertThat(add.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(add.destination()).isEqualTo(PermissionUpdate.Destination.LOCAL_SETTINGS);
        assertThat(add.rules().get(0).ruleValue().ruleContent()).isEqualTo("npm run:*");
    }

    @Test
    @DisplayName("建议b: heredoc 命令 → heredoc 前稳定前缀（cat <<EOF → Bash(cat:*)）")
    void suggestion_heredocPrefix() {
        List<PermissionUpdate> updates =
            BashRuleMatcher.suggestionForExactCommand("cat <<EOF\nhello\nEOF");
        assertThat(((PermissionUpdate.AddRules) updates.get(0)).rules().get(0)
            .ruleValue().ruleContent()).isEqualTo("cat:*");
    }

    @Test
    @DisplayName("建议c: 多行命令 → 首行前缀建议")
    void suggestion_multilineFirstLine() {
        List<PermissionUpdate> updates =
            BashRuleMatcher.suggestionForExactCommand("git commit -m x\ngit push");
        assertThat(((PermissionUpdate.AddRules) updates.get(0)).rules().get(0)
            .ruleValue().ruleContent()).isEqualTo("git commit -m x:*");
    }

    @Test
    @DisplayName("建议d: 无法提取前缀 → exact 建议（python3 script.py 子命令形状不符 → 原文建议）")
    void suggestion_exactFallback() {
        List<PermissionUpdate> updates =
            BashRuleMatcher.suggestionForExactCommand("python3 script.py");
        assertThat(((PermissionUpdate.AddRules) updates.get(0)).rules().get(0)
            .ruleValue().ruleContent()).isEqualTo("python3 script.py");
    }

    @Test
    void firstWordPrefix_bareShellRejected() {
        assertThat(BashRuleMatcher.getFirstWordPrefix("bash -c evil")).isNull();
        assertThat(BashRuleMatcher.getFirstWordPrefix("sudo rm -rf /")).isNull();
        assertThat(BashRuleMatcher.getFirstWordPrefix("NODE_ENV=prod python3 x.py")).isEqualTo("python3");
        // 非安全 env 前缀 → null 回退（CC :246-255）
        assertThat(BashRuleMatcher.getFirstWordPrefix("FOO=bar python3 x.py")).isNull();
    }

    @Test
    @DisplayName("建议f: getSimpleCommandPrefix 子命令形状门（git commit -m x → git commit）")
    void simpleCommandPrefix_shapeGate() {
        assertThat(BashRuleMatcher.getSimpleCommandPrefix("git commit -m \"fix\"")).isEqualTo("git commit");
        assertThat(BashRuleMatcher.getSimpleCommandPrefix("ls -la")).isNull();
        assertThat(BashRuleMatcher.getSimpleCommandPrefix("cat file.txt")).isNull();
        assertThat(BashRuleMatcher.getSimpleCommandPrefix("MY_VAR=val npm run build")).isNull();
    }

    // ════════════════════════════════════════════════════════════════════
    // filterRulesByContentsMatchingInput 批量语义
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("批量: deny 桶含剥离候选 fixed-point（nohup FOO=bar timeout 5 claude → claude）")
    void filter_fixedPointInterleavedStripping() {
        // CC :818-853 交替剥离 fixed-point：nohup → FOO=bar → timeout 5 → claude
        List<PermissionRule> matched = BashRuleMatcher.filterRulesByContentsMatchingInput(
            "nohup FOO=bar timeout 5 claude", List.of(DENY_RULE, ALLOW_RULE),
            false, true, true);
        assertThat(matched)
            .as("deny 桶剥离 fixed-point 命中 claude:*")
            .extracting(r -> r.ruleValue().ruleContent())
            .containsExactly("claude:*");
    }
}
