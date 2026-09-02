package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session A7a · BashTool.checkPermissions 只读 Allow 分支接线共享表验证。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * 旧实现（BashTool.java:732）硬编码 9 命令名数组 {@code {"ls","cat","pwd","echo","head","tail","wc","grep","find"}}
 * 做 word-match，凡命令名命中一律 Allow —— 命令名级，无 flag 校验，且 8/9 不在 CC {@code COMMAND_ALLOWLIST}
 * （readOnlyValidation.ts:128-1137，flag 级 CommandConfig）。本测试验证 step4 改查
 * {@link com.nexusai.application.agent.readonly.ReadOnlyCommandTable#lookupBashCommand} 后的行为：
 * <ol>
 *   <li><b>表内命令 + flag 全在 safeFlags → Allow</b>（如 {@code grep -e}、{@code tree -L}）。</li>
 *   <li><b>表内命令 + 未知 flag → 非 Allow</b>（CC {@code validateFlags} utils/shell/readOnlyCommandValidation.ts:1684
 *       unknown flag → false）。旧 9 数组对 grep 一律 Allow，此为接线共享表的动机（flag 级拦截）。</li>
 *   <li><b>表内命令 + 写文件 flag → 非 Allow</b>（如 {@code tree -o/--output}，CC 将 tree 移入
 *       COMMAND_ALLOWLIST 正为拦截 -o —— readOnlyValidation.ts:1543 注释）。此用例替代 A7.md 验收#3
 *       错误的 {@code ls -o} 示例（ls 不在表）。</li>
 *   <li><b>表外命令（ls/cat/wc/find/pwd）→ 非 Allow</b>：9→51 收紧，CC 的 ls 走
 *       READONLY_COMMAND_REGEXES regex tier（readOnlyValidation.ts:1564），不进 COMMAND_ALLOWLIST，
 *       step4 只查表 → 落 Passthrough 交上层规则（IMP-OPD-05 已拍板"行为更严格"）。</li>
 *   <li><b>echo 例外（A7c 用户拍板 A）</b>：echo 简单形式经 {@code matchesBashReadonlyEcho} 名字层
 *       回退恢复只读 Allow（对齐 CC isCommandReadOnly:1719 regex tier + :1516 echo 正则 +
 *       :1682 2>&1 预剥离 + :1600 未引号展开守卫）；危险形式（管道/重定向/变量/&&/大写/glob）仍 Deny。</li>
 * </ol>
 */
@DisplayName("BashTool checkPermissions 只读 Allow 分支 · 接线共享表（A7a）")
class BashToolReadOnlyTableTest {

    private final BashTool bashTool = new BashTool();
    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode input(String command) {
        return JSON.createObjectNode().put("command", command);
    }

    /** ctx=null 跳过 mode/sed/operator 分支，直达 step4 只读 Allow 分支。 */
    private boolean isAllow(String command) {
        return bashTool.checkPermissions(input(command), null) instanceof PermissionResult.Allow;
    }

    @Test
    @DisplayName("grep -e foo . → Allow（grep ∈ 表，-e ∈ safeFlags）")
    void grepInTableWithSafeFlag_allows() {
        // WHY: CC COMMAND_ALLOWLIST 含 grep（readOnlyValidation.ts:456 对应），-e/--regexp 在 safeFlags。
        assertThat(isAllow("grep -e foo .")).as("grep -e foo . 须 Allow").isTrue();
    }

    @Test
    @DisplayName("grep --unknownflag foo → 非 Allow（flag 级拦截）")
    void grepUnknownFlag_notAllow() {
        // WHY: 旧 9 数组只做命令名 word-match，grep 一律 Allow；CC validateFlags(:1684) 对未知
        //      flag 返回 false → isCommandSafeViaFlagParsing 拒绝。flag 级拦截是接线共享表的动机。
        assertThat(isAllow("grep --unknownflag foo")).as("grep --unknownflag 不得 Allow").isFalse();
    }

    @Test
    @DisplayName("grep -e foo \"$Z--output=/tmp/pwned\" → 非 Allow（$ 变量展开守卫）")
    void grepDollarVarGuard_notAllow() {
        // WHY: CC readOnlyValidation.ts:1355-1357 拒绝任何含 $ 的 token。bash 运行时展开 $VAR
        //      （未定义 → 空串），`"$Z--output=/tmp/pwned"` 展开为 `--output=/tmp/pwned` → git diff
        //      任意文件写（CC :1357-1360 注释的 parser 差异攻击）。$ 前缀不走 startsWith('-') 的
        //      validateFlags 分支，只能由前置守卫拦截。若只靠 isReadOnly 兜底则此用例漏网（F2 收口）。
        assertThat(isAllow("grep -e foo \"$Z--output=/tmp/pwned\""))
            .as("含 $ 变量展开的 token 不得 Allow").isFalse();
    }

    @Test
    @DisplayName("grep -e \"{a,b}.txt\" → 非 Allow（brace expansion 混淆守卫）")
    void grepBraceExpansionGuard_notAllow() {
        // WHY: CC readOnlyValidation.ts:1366-1368 拒绝同时含 { 与 , 的 token（brace expansion 混淆，
        //      `git diff {@'{'0},--output=/tmp/pwned}` 脱引号后 token `{@{0},--output=...}` 含 {+,
        //      → brace 展开绕过 flag 校验，CC :1359-1362）。双条件避免误伤 stash@{0}（有 { 无 ,）。
        assertThat(isAllow("grep -e \"{a,b}.txt\"")).as("brace expansion 混淆不得 Allow").isFalse();
    }

    @Test
    @DisplayName("tree -L 2 → Allow（-L ∈ tree safeFlags）")
    void treeSafeFlag_allows() {
        // WHY: tree ∈ COMMAND_ALLOWLIST（readOnlyValidation.ts:648 附近），-L/--level 在 safeFlags。
        assertThat(isAllow("tree -L 2")).as("tree -L 2 须 Allow").isTrue();
    }

    @Test
    @DisplayName("tree -o out.txt → 非 Allow（-o/--output 写文件 flag 拦截）")
    void treeOutputFlag_notAllow() {
        // WHY: CC 将 tree 从 READONLY_COMMANDS 移入 COMMAND_ALLOWLIST 正为拦截 -o/--output
        //      （readOnlyValidation.ts:1543 注释"tree command moved to COMMAND_ALLOWLIST for proper
        //      flag validation (blocks -o/--output)"）。-o 不在 safeFlags → flag 级拒绝。
        //      ★ 替代 A7.md 验收#3 错误的 ls -o 示例（ls 不在表，用 tree 真源示例）。
        assertThat(isAllow("tree -o out.txt")).as("tree -o out.txt 不得 Allow").isFalse();
        assertThat(isAllow("tree --output out.txt")).as("tree --output 不得 Allow").isFalse();
    }

    @Test
    @DisplayName("ls -la → 非 Allow（9→51 收紧：CC ls 走 regex tier 不进表）")
    void lsNotInTable_notAllow() {
        // WHY: CC ls 经 READONLY_COMMAND_REGEXES（readOnlyValidation.ts:1564 /^ls...$/）regex tier，
        //      不在 COMMAND_ALLOWLIST。step4 只查表 → 落 Passthrough 交上层规则（IMP-OPD-05 收紧）。
        assertThat(isAllow("ls -la")).as("ls -la 不得在 step4 Allow").isFalse();
    }

    @Test
    @DisplayName("cat/wc/find/pwd → 非 Allow（表外命令落 Passthrough；echo 由 A7c 单独回退）")
    void commandsOutsideTable_notAllow() {
        // WHY: 旧 9 数组对 cat/wc/find/pwd/echo 一律 Allow；CC COMMAND_ALLOWLIST 无这些键，
        //      表外命令须交上层规则判定（行为更严格，IMP-OPD-05 已拍板）。echo 除外——用户拍板 A
        //      仅回退 echo 简单形式（见 echoSimpleForm_allows），本测试删除 echo 断言（A7c）。
        assertThat(isAllow("cat file.txt")).as("cat 不得在 step4 Allow").isFalse();
        assertThat(isAllow("wc -l file")).as("wc 不得在 step4 Allow").isFalse();
        assertThat(isAllow("find . -type f")).as("find 不得在 step4 Allow").isFalse();
        assertThat(isAllow("pwd")).as("pwd 不得在 step4 Allow").isFalse();
    }

    @Test
    @DisplayName("echo 简单形式 Allow 矩阵（A7c 名字层回退 · CC readOnlyValidation.ts:1516 + :1682 2>&1 预剥离）")
    void echoSimpleForm_allows() {
        // WHY: CC isCommandReadOnly regex tier（:1719）在 flag 级 miss 后仍允许 echo 简单形式——
        //      单引号串/双引号串（禁 $<> 与换行）/未引号纯 token，及尾随 2>&1（isCommandReadOnly
        //      :1682-1686 预剥离后缀再匹配，正则内 (?:\s+2>&1)? 因 $ 结束锚点而永不匹配）。用户拍板
        //      A 恢复 `echo hi` 只读 Allow（PromptShellExecutorTest.readOnlyCommand_autoAllowed 期望行为）。
        assertThat(isAllow("echo hi")).as("echo hi 简单形式须 Allow").isTrue();
        assertThat(isAllow("echo 'a b'")).as("echo 单引号串（含空格）须 Allow").isTrue();
        assertThat(isAllow("echo \"a b\"")).as("echo 双引号串（无 $<>）须 Allow").isTrue();
        assertThat(isAllow("echo hi 2>&1")).as("echo 尾随 2>&1 预剥离后须 Allow").isTrue();
    }

    @Test
    @DisplayName("echo 危险形式 Deny 矩阵（A7c · 全命令正则防裸名字层放行写命令）")
    void echoDangerousForm_notAllow() {
        // WHY: 裸名字层查表会把 cat a>b / echo a|b 一并放行；CC echo 正则（:1516）逐 token 排除
        //      管道/重定向/命令替换/变量/&& 与大小写（^echo 大小写敏感），:1600 containsUnquotedExpansion
        //      守卫拒未引号 glob（echo *）。每例即一个 bypass 向量，缺失任一即 Write 逃逸（规则九）。
        assertThat(isAllow("echo hi > f")).as("echo 重定向不得 Allow").isFalse();
        assertThat(isAllow("echo $HOME")).as("echo 变量展开不得 Allow").isFalse();
        assertThat(isAllow("echo $(date)")).as("echo 命令替换不得 Allow").isFalse();
        assertThat(isAllow("echo a | grep b")).as("echo 管道不得 Allow").isFalse();
        assertThat(isAllow("echo `whoami`")).as("echo 反引号命令替换不得 Allow").isFalse();
        assertThat(isAllow("echo a && b")).as("echo && 复合命令不得 Allow").isFalse();
        assertThat(isAllow("ECHO hi")).as("ECHO 大写不得 Allow（^echo 大小写敏感）").isFalse();
        assertThat(isAllow("echo *")).as("echo 未引号 glob 不得 Allow（CC :1600 守卫）").isFalse();
    }

    @Test
    @DisplayName("grep -nv foo → Allow（combined short flags 拆字符全命中 safeFlags）")
    void grepCombinedShortFlags_allows() {
        // WHY: CC validateFlags:1812-1830 将 combined short flags（-nv）拆单字符逐查 safeFlags，
        //      全部命中（-n/--line-number、-v/--invert-match 均 in safeFlags，且 CC 中 -n:-'none'、
        //      -v:-'none' 均 no-arg → bundle 全 no-arg 放行）。Java walker BashTool.java:856-867
        //      拆字符查 CmdletConfig.safeFlags，-n/-v 均在表 → Allow。旧 9 数组 word-match 对
        //      grep 一律 Allow，无 bundle 概念——此为接线共享表带来的 flag 级语义（规则九 WHY）。
        assertThat(isAllow("grep -nv foo")).as("grep -nv foo 须 Allow").isTrue();
    }

    @Test
    @DisplayName("grep -e foo -- --output=pwned → Allow（-- 后为参数，flag 校验终止）")
    void grepDoubleDashBreak_allows() {
        // WHY: CC validateFlags:1719-1731 respectsDoubleDash 默认 true → 遇 `--` 即 break，
        //      `--` 之后全部按位置参数放行（grep 表未设 respectsDoubleDash:false）。Java walker
        //      BashTool.java:847-849 同语义 break。`-- --output=pwned` 在 `--` 后是字面文件名
        //      非 flag → 只读安全。若 Java walker 不 break 会误拒合法 `--` 用法（规则九 WHY）。
        assertThat(isAllow("grep -e foo -- --output=pwned")).as("grep -e foo -- ... 须 Allow").isTrue();
    }

    @Test
    @DisplayName("isReadOnly 名字层不回归：cat/ls/find/head → true（经共享表 lookupBashCommandName）")
    void isReadOnly_nameLayer_noRegression() {
        // WHY（意图验证 · A7b 搬迁）：BashParser.parseForReadOnly 的 :373 gate 已从私有
        // READONLY_COMMANDS 改为查 ReadOnlyCommandTable.lookupBashCommandName。BashToolAlignmentTest:93-96
        // 断言这些命令 isReadOnly==true 是名字层契约，搬迁后数据源变了但行为必须不变——本测试
        // 显式锁定（cat/ls/find/head ∈ 名字层但不在 51 键 flag 表，仅名字层 gate 能放行）。
        assertThat(bashTool.isReadOnly(input("cat file.txt"))).as("cat 名字层只读").isTrue();
        assertThat(bashTool.isReadOnly(input("ls -la"))).as("ls 名字层只读").isTrue();
        assertThat(bashTool.isReadOnly(input("find . -type f"))).as("find 名字层只读").isTrue();
        assertThat(bashTool.isReadOnly(input("head -5 x"))).as("head 名字层只读").isTrue();
    }

    @Test
    @DisplayName("isReadOnly fail-closed：curl/python 不在名字层 → false（:373 gate 有效）")
    void isReadOnly_nameLayer_failClosed() {
        // WHY（意图验证 · A7b 搬迁）：删除 :373 分支会 fail-open（curl/python 无 gate → isReadOnly
        // 误判 true → 只读/并发安全误判）。A7b 保留名字层 gate 改查共享表，curl/python 仍须
        // fail-closed false（Pattern #11）。
        assertThat(bashTool.isReadOnly(input("curl https://evil.example"))).as("curl 非只读").isFalse();
        assertThat(bashTool.isReadOnly(input("python s.py"))).as("python 非只读").isFalse();
        assertThat(bashTool.isReadOnly(input("mv a b"))).as("mv 非只读").isFalse();
    }

    @Test
    @DisplayName("Bash hostname 位置参数拒绝：hostname mybox → 非 Allow（CC BashTool/readOnlyValidation.ts:827 regex 语义）")
    void hostnamePositionalArgRejected() {
        // WHY（意图验证 · RV-D-04 NG-PD-1）：Bash hostname 在 CC 用 regex 只放行 hostname + 纯 flag，
        // hostname NAME（设置主机名）必须拒绝。缺回调时 NAME 位置参数在 token 循环穿透（非 flag 放行）
        // → 只读自动放行（假接线）。
        assertThat(isAllow("hostname mybox")).as("hostname mybox 须非 Allow（位置参数设置主机名）").isFalse();
    }

    @Test
    @DisplayName("Bash hostname 纯 flag / 裸命令 → Allow（CC :827 regex 语义）")
    void hostnameFlagAndBareAllowed() {
        // WHY：hostname -a（纯 flag 显示）与裸 hostname（无位置参数）均只读安全，须 Allow。
        assertThat(isAllow("hostname -a")).as("hostname -a 须 Allow（纯 flag 显示）").isTrue();
        assertThat(isAllow("hostname")).as("裸 hostname 须 Allow（显示主机名）").isTrue();
    }

    @Test
    @DisplayName("Bash hostname 补 6 项 safeFlags：-f/--fqdn/--long/-s/--short/-i → Allow · -F → 拒绝")
    void hostnameSafeFlagsSixAdded() {
        // WHY（意图验证 · RV-D-04）：CC BashTool/readOnlyValidation.ts:800-823 hostname safeFlags 含
        // -f/--fqdn/--long/-s/--short/-i 六项只读显示选项；Java 旧表缺这 6 项 → hostname -f 被误拒
        // （fail-closed 但偏离 CC）。-F/--file 不在 safeFlags（从文件设置主机名 = 写）→ 必须拒绝。
        assertThat(isAllow("hostname -f")).as("hostname -f 须 Allow（--fqdn 显示 FQDN）").isTrue();
        assertThat(isAllow("hostname --fqdn")).as("hostname --fqdn 须 Allow").isTrue();
        assertThat(isAllow("hostname --long")).as("hostname --long 须 Allow").isTrue();
        assertThat(isAllow("hostname -s")).as("hostname -s 须 Allow（--short 显示短名）").isTrue();
        assertThat(isAllow("hostname --short")).as("hostname --short 须 Allow").isTrue();
        assertThat(isAllow("hostname -i")).as("hostname -i 须 Allow（--ip-address 显示 IP）").isTrue();
        assertThat(isAllow("hostname -F /etc/x")).as("hostname -F 不在 safeFlags → 拒绝").isFalse();
    }
}
