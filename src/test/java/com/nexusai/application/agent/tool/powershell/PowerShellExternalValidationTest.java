package com.nexusai.application.agent.tool.powershell;

import com.nexusai.application.agent.readonly.ReadOnlyCommandTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PowerShell A5 链外部命令只读校验测试（external-validate 域）。
 *
 * <p>WHY（意图验证，CC 真源 readOnlyCommandValidation.ts + PowerShellTool/readOnlyValidation.ts）：
 * 只读 allowlist 对 git/gh/docker 的 flag 校验若与 CC 漂移 = 写命令被误放行（fail-open）。本测试
 * 锁定 CC 完整语义的每个新分支：
 * <ul>
 *   <li>git reflog/tag/branch/remote/remote show 写能力回调（:270-302 / :473-503 / :712-806 / :807-920）</li>
 *   <li>DANGEROUS_GIT_SHORT_FLAGS_ATTACHED 附着式 -c/-C（:1582 / :1622-1630，阻断 -ccore.pager=sh RCE）</li>
 *   <li>GIT_GLOBAL_FLAGS_WITH_VALUES 值消费（:1566-1576，--namespace foo 值不误判子命令）</li>
 *   <li>git ls-remote URL 拒绝（:1679-1692，数据 exfil 向量）</li>
 *   <li>ghIsDangerousCallback 三段 exfil 拒绝（:944-982）——CC 仅 17/22 项挂该回调（5 search 不挂）</li>
 *   <li>isGhSafe USER_TYPE='ant' 门（:1703-1707，fail-closed）</li>
 *   <li>validateFlags 完整语义（:1684-1893：git -5 简写、--flag= 空值 hasEquals、捆绑全 none、--sort 例外）</li>
 *   <li>hasSyncSecurityConcerns 8 项正则（PowerShellTool/readOnlyValidation.ts:1112-1159）</li>
 * </ul>
 */
class PowerShellExternalValidationTest {

    private static ReadOnlyCommandTable.ExternalCommandConfig cfg(String key) {
        ReadOnlyCommandTable.ExternalCommandConfig c = ReadOnlyCommandTable.lookupExternalCommand(key);
        assertNotNull(c, "外部命令配置应存在：" + key);
        return c;
    }

    /** 构造 git/gh 等外部命令元素（沿用 WildcardBareTest.cmd 模式；git/gh 走 isExternalCommandSafe 不依赖 elementTypes）。 */
    private static PowerShellAstService.CommandElement cmd(String name, String nameType, String... args) {
        List<String> elementTypes = new ArrayList<>();
        elementTypes.add("StringConstant");
        for (int i = 0; i < args.length; i++) {
            elementTypes.add("StringConstant");
        }
        String text = name + (args.length > 0 ? " " + String.join(" ", args) : "");
        return new PowerShellAstService.CommandElement(name, nameType, "CommandAst",
            List.of(args), elementTypes, text, List.of(), List.of());
    }

    private static PowerShellPermissionChain chain() {
        // isAllowlistedCommand / isExternalCommandSafe 不触 astService，传 null 足够
        return new PowerShellPermissionChain(null);
    }

    private static boolean gitSafe(String... args) {
        return chain().isAllowlistedCommand(cmd("git", "unknown", args));
    }

    // ════════════════════════════════════════════════════════════════════════
    // git 写能力回调（reflog / remote / remote show / tag / branch）
    // ════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("git reflog expire/delete/exists 拒绝 · show/裸/HEAD 放行（写 .git/logs/**）")
    void gitReflogDangerousCallback() {
        ReadOnlyCommandTable.AdditionalCommandIsDangerousCallback cb = cfg("git reflog").callback();
        assertNotNull(cb, "git reflog 应挂危险回调（CC :283-303）");
        assertTrue(cb.isDangerous("", List.of("expire")), "reflog expire 写 .git/logs → 拒绝");
        assertTrue(cb.isDangerous("", List.of("delete", "HEAD")), "reflog delete 写 → 拒绝");
        assertTrue(cb.isDangerous("", List.of("exists", "HEAD")), "reflog exists 写 → 拒绝");
        assertFalse(cb.isDangerous("", List.of()), "裸 git reflog = show → 放行");
        assertFalse(cb.isDangerous("", List.of("show", "HEAD")), "reflog show HEAD → 放行");
        assertFalse(cb.isDangerous("", List.of("HEAD")), "reflog <ref> 位置参数 → 放行");
        // 端到端：git reflog expire → 非只读（isAllowlistedCommand false）
        assertFalse(gitSafe("reflog", "expire"), "git reflog expire 必须拒绝");
        assertTrue(gitSafe("reflog", "show", "HEAD"), "git reflog show HEAD 必须放行");
        assertTrue(gitSafe("reflog"), "裸 git reflog 必须放行");
    }

    @Test
    @DisplayName("git remote 只允许裸/-v/--verbose · remote show 仅 -n+一个远端名")
    void gitRemoteCallbacks() {
        ReadOnlyCommandTable.AdditionalCommandIsDangerousCallback remoteCb = cfg("git remote").callback();
        assertNotNull(remoteCb, "git remote 应挂危险回调（CC :495-503）");
        assertTrue(remoteCb.isDangerous("", List.of("add", "origin", "url")), "remote add 写 → 拒绝");
        assertTrue(remoteCb.isDangerous("", List.of("set-url", "origin")), "remote set-url 写 → 拒绝");
        assertFalse(remoteCb.isDangerous("", List.of()), "裸 git remote → 放行");
        assertFalse(remoteCb.isDangerous("", List.of("-v")), "git remote -v → 放行");
        assertFalse(remoteCb.isDangerous("", List.of("--verbose")), "git remote --verbose → 放行");

        ReadOnlyCommandTable.AdditionalCommandIsDangerousCallback showCb = cfg("git remote show").callback();
        assertNotNull(showCb, "git remote show 应挂危险回调（CC :478-487）");
        assertTrue(showCb.isDangerous("", List.of()), "remote show 无远端名 → 拒绝");
        assertTrue(showCb.isDangerous("", List.of("origin", "extra")), "remote show 多位置参数 → 拒绝");
        assertFalse(showCb.isDangerous("", List.of("origin")), "remote show origin → 放行");
        assertFalse(showCb.isDangerous("", List.of("-n", "origin")), "remote show -n origin → 放行");

        assertFalse(gitSafe("remote", "add", "origin", "http://x"), "git remote add 必须拒绝");
        assertTrue(gitSafe("remote", "show", "origin"), "git remote show origin 必须放行");
    }

    @Test
    @DisplayName("git tag 创建拒绝 · --list/-l 前缀放行")
    void gitTagDangerousCallback() {
        ReadOnlyCommandTable.AdditionalCommandIsDangerousCallback cb = cfg("git tag").callback();
        assertNotNull(cb, "git tag 应挂危险回调（CC :739-805）");
        assertTrue(cb.isDangerous("", List.of("mytag")), "git tag mytag 创建 .git/refs/tags → 拒绝");
        assertTrue(cb.isDangerous("", List.of("--", "-l")), "git tag -- -l 创建名为 -l 的 tag → 拒绝");
        assertFalse(cb.isDangerous("", List.of()), "裸 git tag（list）→ 放行");
        assertFalse(cb.isDangerous("", List.of("--list", "pat")), "git tag --list pat → 放行");
        assertFalse(cb.isDangerous("", List.of("-l", "pat")), "git tag -l pat → 放行");
        assertFalse(cb.isDangerous("", List.of("-li", "pat")), "-li 捆绑含 l → 视为 list → 放行");
        assertFalse(cb.isDangerous("", List.of("--contains", "abc")), "tag --contains <ref> → 放行");

        assertFalse(gitSafe("tag", "mytag"), "git tag mytag 必须拒绝");
        assertTrue(gitSafe("tag", "--list", "pat"), "git tag --list pat 必须放行");
    }

    @Test
    @DisplayName("git branch 创建拒绝 · --merged/--no-merged 可选参放行")
    void gitBranchDangerousCallback() {
        ReadOnlyCommandTable.AdditionalCommandIsDangerousCallback cb = cfg("git branch").callback();
        assertNotNull(cb, "git branch 应挂危险回调（CC :851-921）");
        assertTrue(cb.isDangerous("", List.of("newbranch")), "git branch newbranch 创建 refs/heads → 拒绝");
        assertTrue(cb.isDangerous("", List.of("newbranch", "start")), "git branch <name> <start-point> → 拒绝");
        assertFalse(cb.isDangerous("", List.of()), "裸 git branch（list）→ 放行");
        assertFalse(cb.isDangerous("", List.of("--merged")), "git branch --merged 可选参无值 → 放行");
        assertFalse(cb.isDangerous("", List.of("--merged", "main")), "git branch --merged main → 放行");
        assertFalse(cb.isDangerous("", List.of("--list", "pat")), "git branch --list pat → 放行");

        assertFalse(gitSafe("branch", "newbranch"), "git branch newbranch 必须拒绝");
        assertTrue(gitSafe("branch", "--merged"), "git branch --merged 必须放行");
        assertTrue(gitSafe("branch", "--list"), "git branch --list 必须放行");
    }

    // ════════════════════════════════════════════════════════════════════════
    // DANGEROUS_GIT_SHORT_FLAGS_ATTACHED / GIT_GLOBAL_FLAGS_WITH_VALUES / ls-remote
    // ════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("git 附着式 -c/-C 拒绝（-ccore.pager=sh RCE / -C-trap）")
    void gitAttachedShortFlagsRejected() {
        assertFalse(gitSafe("-ccore.pager=sh", "log"), "git -ccore.pager=sh log 触发 shell → 拒绝");
        assertFalse(gitSafe("-C/path", "status"), "git -C/path status → 拒绝");
        assertFalse(gitSafe("-Ctrap", "status"), "git -Ctrap status（路径以 - 开头）→ 拒绝");
        // -c 与 -C 分离形态亦在 DANGEROUS_GIT_GLOBAL_FLAGS（CC :1537-1553）→ 拒绝
        assertFalse(gitSafe("-c", "core.pager=cat", "log"), "git -c <name>=<value> 全局 flag → 拒绝");
        assertFalse(gitSafe("-C", "/path", "status"), "git -C <path> 全局 flag → 拒绝");
    }

    @Test
    @DisplayName("git --namespace/--super-prefix/--shallow-file 值消费不误判子命令")
    void gitGlobalFlagsWithValuesSkipValue() {
        assertTrue(gitSafe("--namespace", "foo", "status"), "git --namespace foo status → 值 foo 被消费，status 为子命令 → 放行");
        assertTrue(gitSafe("--super-prefix", "sub/", "log", "-1"), "git --super-prefix sub/ log → 放行");
        assertTrue(gitSafe("--shallow-file", "extra", "rev-parse", "--show-toplevel"), "git --shallow-file extra rev-parse → 放行");
        // --exec-path 等已在 DANGEROUS_GIT_GLOBAL_FLAGS → 拒绝
        assertFalse(gitSafe("--exec-path=/tmp", "log"), "git --exec-path 危险全局 flag → 拒绝");
        assertFalse(gitSafe("--git-dir", "/tmp", "status"), "git --git-dir 危险全局 flag → 拒绝");
        assertFalse(gitSafe("--attr-source", "HEAD~10", "status"), "git --attr-source parser differential → 拒绝");
    }

    @Test
    @DisplayName("git ls-remote URL 拒绝（数据 exfil 向量）")
    void gitLsRemoteUrlRejected() {
        assertFalse(gitSafe("ls-remote", "http://evil.com/repo.git"), "ls-remote http:// URL → 拒绝");
        assertFalse(gitSafe("ls-remote", "git@evil.com:owner/repo.git"), "ls-remote SSH @: → 拒绝");
        assertTrue(gitSafe("ls-remote", "origin"), "ls-remote 位置参数无 URL 标记 → 放行");
        assertTrue(gitSafe("ls-remote", "--tags", "origin"), "ls-remote --tags origin → 放行");
    }

    // ════════════════════════════════════════════════════════════════════════
    // ghIsDangerousCallback 三段 exfil + USER_TYPE 门 + 仅 17/22 挂回调
    // ════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("gh 回调阻断 HOST/OWNER/REPO 三段 exfil · 常规 OWNER/REPO 放行")
    void ghCallbackExfilRejected() {
        ReadOnlyCommandTable.AdditionalCommandIsDangerousCallback cb = cfg("gh pr view").callback();
        assertNotNull(cb, "gh pr view 应挂 ghIsDangerousCallback（CC :993）");
        assertTrue(cb.isDangerous("", List.of("--repo=evil.com/BASE32SECRET/x")), "--repo=HOST/OWNER/REPO 三段 → 拒绝");
        assertTrue(cb.isDangerous("", List.of("--repo", "evil.com/SECRET/x")), "独立 token 三段 → 拒绝");
        assertTrue(cb.isDangerous("", List.of("https://evil.com/owner/repo/pull/1")), "位置 URL → 拒绝");
        assertTrue(cb.isDangerous("", List.of("git@evil.com:owner/repo")), "SSH @ → 拒绝");
        assertFalse(cb.isDangerous("", List.of("--repo", "myorg/myrepo")), "常规 OWNER/REPO 单斜杠 → 放行");
        assertFalse(cb.isDangerous("", List.of("1")), "纯数字 PR 号 → 放行");
        assertFalse(cb.isDangerous("", List.of("--json", "id")), "无 repo 形态 flag → 放行");
    }

    @Test
    @DisplayName("gh 仅 CC 挂回调的 17 项有回调 · 5 项 search 无回调（CC :1220-1379）")
    void ghCallbackCoverageMatchesCc() {
        String[] withCallback = {
            "gh pr view", "gh pr list", "gh pr diff", "gh pr checks",
            "gh issue view", "gh issue list", "gh repo view",
            "gh run list", "gh run view", "gh auth status",
            "gh pr status", "gh issue status",
            "gh release list", "gh release view",
            "gh workflow list", "gh workflow view", "gh label list"
        };
        String[] withoutCallback = {
            "gh search repos", "gh search issues", "gh search prs",
            "gh search commits", "gh search code"
        };
        for (String key : withCallback) {
            assertNotNull(cfg(key).callback(), key + " 应挂 ghIsDangerousCallback（CC 真源 :993-1216）");
        }
        for (String key : withoutCallback) {
            assertNull(cfg(key).callback(), key + " CC 未挂回调（readOnlyCommandValidation.ts:1220-1379）");
        }
    }

    @Test
    @DisplayName("gh 非 ant 用户 fail-closed 拒绝（USER_TYPE !== 'ant'）")
    void ghRequiresAntUser() {
        if ("ant".equals(System.getenv("USER_TYPE"))) {
            return; // ant 环境门放行，跳过非 ant 断言
        }
        assertFalse(chain().isAllowlistedCommand(cmd("gh", "unknown", "pr", "view")),
            "非 ant 用户 gh 全部拒绝（CC :1703-1707）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // validateFlags 完整语义
    // ════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("validateFlags：git -5 数字简写、-n 5、--flag= 空值、捆绑拒绝、--sort 附着例外")
    void validateFlagsCcSemantics() {
        assertTrue(gitSafe("log", "-5"), "git log -5 数字简写 = -n 5 → 放行");
        assertTrue(gitSafe("log", "-n", "5"), "git log -n 5 分离数字 → 放行");
        assertTrue(gitSafe("log", "--max-count=5"), "git log --max-count=5 附着 → 放行");
        assertFalse(gitSafe("log", "-n"), "git log -n 缺值 → 拒绝");
        assertFalse(gitSafe("log", "-nr"), "捆绑含参（-n NUMBER）-nr → 拒绝（防 parser differential）");
        assertFalse(gitSafe("log", "-5", "-x"), "git log -x 未知 flag → 拒绝");
        assertFalse(gitSafe("diff", "-S"), "git diff -S 缺必选参（pickaxe）→ 拒绝");
        assertTrue(gitSafe("diff", "-S", "pattern"), "git diff -S pattern → 放行");
        // --sort 反向排序：仅附着式 --sort=-refname 可达（detached -refname 被误判为缺失参数先拒绝）
        assertTrue(gitSafe("for-each-ref", "--sort=-refname"), "git for-each-ref --sort=-refname 附着反向排序 → 放行");
        assertFalse(gitSafe("for-each-ref", "--sort", "-refname"), "detached -refname 形 → CC 视为缺失参数 → 拒绝");
        // git grep 附着数值 -A20 非 grep/rg 顶层命令 → CC 走捆绑分支拒绝（commandName='git'，:1771 仅 grep/rg）
        assertFalse(gitSafe("grep", "-A20", "x"), "git grep -A20 附着数值 commandName=git → 拒绝（CC :1771 仅顶层 grep/rg）");
        assertTrue(gitSafe("grep", "-A", "20", "x"), "git grep -A 20 分离 → 放行");
    }

    @Test
    @DisplayName("validateFlags respectsDoubleDash：-- 后不再校验（pyright 除外）")
    void validateFlagsDoubleDash() {
        assertTrue(gitSafe("log", "--", "-x"), "git log -- -x：-- 后为位置参数 → 放行");
    }

    // ════════════════════════════════════════════════════════════════════════
    // hasSyncSecurityConcerns 8 项正则
    // ════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("hasSyncSecurityConcerns 危险模式全命中")
    void hasSyncSecurityConcernsPositives() {
        assertTrue(PowerShellPermissionChain.hasSyncSecurityConcerns("Get-Content $($env:SECRET)"), "$( 子表达式");
        assertTrue(PowerShellPermissionChain.hasSyncSecurityConcerns("Get-ChildItem @splat"), "splatting");
        assertTrue(PowerShellPermissionChain.hasSyncSecurityConcerns("$obj.Method()"), "成员调用");
        assertTrue(PowerShellPermissionChain.hasSyncSecurityConcerns("$x = 1"), "赋值");
        assertTrue(PowerShellPermissionChain.hasSyncSecurityConcerns("cmd /c --% dir"), "stop-parsing");
        assertTrue(PowerShellPermissionChain.hasSyncSecurityConcerns("\\\\server\\share\\file"), "反斜杠 UNC");
        assertTrue(PowerShellPermissionChain.hasSyncSecurityConcerns("//server/share"), "前斜杠 UNC");
        assertTrue(PowerShellPermissionChain.hasSyncSecurityConcerns("[Type]::StaticMethod()"), "静态方法调用");
    }

    @Test
    @DisplayName("hasSyncSecurityConcerns 安全命令不误报（email/URL/常规 cmdlet）")
    void hasSyncSecurityConcernsNegatives() {
        assertFalse(PowerShellPermissionChain.hasSyncSecurityConcerns("Get-ChildItem"), "常规 cmdlet 无危险模式");
        assertFalse(PowerShellPermissionChain.hasSyncSecurityConcerns("git status"), "git status 无危险模式");
        assertFalse(PowerShellPermissionChain.hasSyncSecurityConcerns("user@example.com"), "email 中 @ 前是词字符，非 splatting");
        assertFalse(PowerShellPermissionChain.hasSyncSecurityConcerns("https://example.com"), "URL 中 // 前是 :，非 UNC");
        assertFalse(PowerShellPermissionChain.hasSyncSecurityConcerns(""), "空命令 false");
        assertFalse(PowerShellPermissionChain.hasSyncSecurityConcerns(null), "null false");
    }

    // ════════════════════════════════════════════════════════════════════════
    // native exe win32 '/' 前缀 flag 校验（CC readOnlyValidation.ts:1441-1489）
    // ════════════════════════════════════════════════════════════════════════
    @Test
    @DisplayName("native exe win32 '/bogus' 拒绝 · '/all' 命中 safeFlags 放行（/all 不再被转 -/all 假接线）")
    void ipconfigWin32SlashFlag() {
        // WHY（意图验证）：ipconfig 为 native exe（canonical 无 '-'，isCmdlet=false），win32 下 /flag 是
        // argv 约定 flag；旧实现恒前置 '-' 把 /all 转成 -/all 永不命中 safeFlags（假接线），且 /bogus
        // 被当位置参数穿透 Allow。对齐 CC :1481-1489 后 /bogus 走 flag 循环被 safeFlags 拒绝，/all 命中放行。
        assertFalse(new PowerShellPermissionChain(null, true)
            .isAllowlistedCommand(cmd("ipconfig", "cmdlet", "/bogus")), "win32 ipconfig /bogus 必须拒绝");
        assertTrue(new PowerShellPermissionChain(null, true)
            .isAllowlistedCommand(cmd("ipconfig", "cmdlet", "/all")), "win32 ipconfig /all 必须放行");
    }

    @Test
    @DisplayName("native exe 非 win32 '/bogus' 视为路径放行（CC gating：/ 前缀仅 win32 为 flag）")
    void ipconfigNonWin32SlashIsPath() {
        // WHY（意图验证 · gating 文档化）：非 win32（Linux/macOS）下 /x 是路径非 flag，CC :1483-1484
        // 仅 win32 认 / 前缀。此断言锁定平台门：非 win32 下 /bogus 经回调（/ 前缀非位置参数）放行。
        assertTrue(new PowerShellPermissionChain(null, false)
            .isAllowlistedCommand(cmd("ipconfig", "cmdlet", "/bogus")), "非 win32 ipconfig /bogus 视为路径放行");
    }

    @Test
    @DisplayName("表数量契约回归（CC 对齐基线）")
    void tableCountsStable() {
        assertEquals(24, ReadOnlyCommandTable.gitCommandCount(), "GIT_READ_ONLY_COMMANDS 24 项");
        assertEquals(22, ReadOnlyCommandTable.ghCommandCount(), "GH_READ_ONLY_COMMANDS 22 项");
        assertEquals(2, ReadOnlyCommandTable.dockerCommandCount(), "DOCKER 2 项");
    }
}
