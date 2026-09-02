package com.nexusai.application.agent.bash;

import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BashPathValidator 测试 · 对齐 CC tools/BashTool/pathValidation.ts checkPathConstraints 核心不变量。
 *
 * <p>WHY（规则九：验证意图而非仅行为）：Q-BS-2 补齐 Bash path 约束，防四类攻击——
 * 路径越界读取、cd+write、cd+redirect、危险删除、sed 写文件。测试断言每条攻击路径必须
 * 落到 Ask（危险删除 message 明确 "cannot be auto-allowed by permission rules"），
 * 而白名单内只读/重定向到 /dev/null 必须 Passthrough。变异测试：把 createPathChecker
 * 的 deny 分支改 {@code if(false)} 会令 Edit-deny 用例变红。
 */
class BashPathValidatorTest {

    private static final Path CWD = Path.of("C:/work/project");

    private static ToolPermissionContext permCtx(PermissionMode mode, PermissionRule... rules) {
        Map<PermissionRuleSource, Set<PermissionRule>> deny = new HashMap<>();
        Map<PermissionRuleSource, Set<PermissionRule>> allow = new HashMap<>();
        for (PermissionRule r : rules) {
            (r.ruleBehavior() == PermissionBehavior.DENY ? deny : allow)
                .computeIfAbsent(PermissionRuleSource.SESSION, k -> new HashSet<>()).add(r);
        }
        return new ToolPermissionContext(mode, allow, deny, Map.of(), Map.of(),
            false, false, Map.of(), false, false, null);
    }

    private static PermissionRule editDeny(String glob) {
        return new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            new PermissionRuleValue("Edit", glob));
    }

    private static PermissionRule bashAllow(String content) {
        return new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
            new PermissionRuleValue("Bash", content));
    }

    // ── (a) 危险删除 → Ask（cannot be auto-allowed，非 Allow/非 Passthrough）──

    @Test
    @DisplayName("rm -rf / → Ask（危险删除，含 allow 规则也不 auto-allow）")
    void dangerousRemovalRoot() {
        PermissionResult r = BashPathValidator.check("rm -rf /", CWD,
            permCtx(PermissionMode.DEFAULT, bashAllow("rm:*")));
        assertInstanceOf(PermissionResult.Ask.class, r,
            "rm -rf / 必须 ask，即便存在 Bash(rm:*) allow 规则（cannot be auto-allowed）");
        assertTrue(((PermissionResult.Ask) r).message().contains("cannot be auto-allowed"),
            "危险删除消息须明确 'cannot be auto-allowed by permission rules'");
    }

    @Test
    @DisplayName("rm ~ / rmdir /etc / rm /usr → Ask（危险路径表单一真理源）")
    void dangerousRemovalVariants() {
        assertInstanceOf(PermissionResult.Ask.class,
            BashPathValidator.check("rm ~", CWD, permCtx(PermissionMode.DEFAULT)));
        assertInstanceOf(PermissionResult.Ask.class,
            BashPathValidator.check("rmdir /etc", CWD, permCtx(PermissionMode.DEFAULT)));
        assertInstanceOf(PermissionResult.Ask.class,
            BashPathValidator.check("rm /usr", CWD, permCtx(PermissionMode.DEFAULT)));
    }

    // ── (b) 路径越界读取 → Ask ──

    @Test
    @DisplayName("ls /etc / cat ~/.ssh / find /tmp / git diff --no-index → Ask（越界读取）")
    void outOfBoundsRead() {
        assertInstanceOf(PermissionResult.Ask.class,
            BashPathValidator.check("ls /etc", CWD, permCtx(PermissionMode.DEFAULT)));
        assertInstanceOf(PermissionResult.Ask.class,
            BashPathValidator.check("cat ~/.ssh/id_rsa", CWD, permCtx(PermissionMode.DEFAULT)));
        assertInstanceOf(PermissionResult.Ask.class,
            BashPathValidator.check("find /tmp", CWD, permCtx(PermissionMode.DEFAULT)));
        assertInstanceOf(PermissionResult.Ask.class,
            BashPathValidator.check("git diff --no-index /etc/a /etc/b", CWD, permCtx(PermissionMode.DEFAULT)));
    }

    // ── (c) cd+write / cd+redirect → Ask ──

    @Test
    @DisplayName("cd .claude && mv test.txt settings.json → Ask（cd+write 相对 cwd 漂移）")
    void cdWriteAsk() {
        PermissionResult r = BashPathValidator.check("cd .claude && mv test.txt settings.json",
            CWD, permCtx(PermissionMode.DEFAULT));
        assertInstanceOf(PermissionResult.Ask.class, r,
            "cd+write 复合命令因相对 cwd 漂移必须 ask（防 cd .claude/ && mv 绕过 .claude 校验）");
    }

    @Test
    @DisplayName("cd x && echo hi > y → Ask（cd+redirect 目标按原 cwd 校验不可靠）")
    void cdRedirectAsk() {
        PermissionResult r = BashPathValidator.check("cd x && echo hi > y",
            CWD, permCtx(PermissionMode.DEFAULT));
        assertInstanceOf(PermissionResult.Ask.class, r,
            "cd+redirect 复合命令必须 ask（防 cd .claude/ && echo > settings.json 绕过）");
    }

    // ── (d) sed 写文件 → Ask ──

    @Test
    @DisplayName("sed -i 's/x/y/' /etc/passwd → Ask（sed 写文件越界）")
    void sedWriteAsk() {
        PermissionResult r = BashPathValidator.check("sed -i 's/x/y/' /etc/passwd",
            CWD, permCtx(PermissionMode.DEFAULT));
        assertInstanceOf(PermissionResult.Ask.class, r,
            "sed -i 就地编辑 /etc/passwd 越界必须 ask");
    }

    // ── (e) POSIX `--` 分隔符 ──

    @Test
    @DisplayName("rm -- -/../.claude/settings.json → Ask（-- 之后位置参数仍被提取校验）")
    void doubleDashExtracted() {
        PermissionResult r = BashPathValidator.check("rm -- -/../.claude/settings.json",
            CWD, permCtx(PermissionMode.DEFAULT));
        assertInstanceOf(PermissionResult.Ask.class, r,
            "-- 之后以 - 开头的位置参数必须被提取并校验（.claude 危险目录命中），不得静默放行");
    }

    // ── (f) mv/cp 含 flag → Ask ──

    @Test
    @DisplayName("mv -t /tmp x → Ask（mv/cp 禁 flag，防 --target-directory 绕过路径提取）")
    void mvFlagAsk() {
        assertInstanceOf(PermissionResult.Ask.class,
            BashPathValidator.check("mv -t /tmp x", CWD, permCtx(PermissionMode.DEFAULT)));
        assertInstanceOf(PermissionResult.Ask.class,
            BashPathValidator.check("cp -r /etc /tmp", CWD, permCtx(PermissionMode.DEFAULT)));
    }

    // ── (g) 白名单内路径 → Passthrough ──

    @Test
    @DisplayName("ls . / echo hi > /dev/null → Passthrough（只读 cwd + /dev/null 重定向）")
    void whitelistPassthrough() {
        assertInstanceOf(PermissionResult.Passthrough.class,
            BashPathValidator.check("ls .", CWD, permCtx(PermissionMode.DEFAULT)));
        assertInstanceOf(PermissionResult.Passthrough.class,
            BashPathValidator.check("echo hi > /dev/null", CWD, permCtx(PermissionMode.DEFAULT)));
    }

    @Test
    @DisplayName("rm ./tmp.txt（acceptEdits 模式 cwd 内）→ Passthrough")
    void writeInCwdAcceptEdits() {
        assertInstanceOf(PermissionResult.Passthrough.class,
            BashPathValidator.check("rm ./tmp.txt", CWD, permCtx(PermissionMode.ACCEPT_EDITS)));
    }

    // ── (h) 只读 sed → 走 read 覆盖 → Passthrough ──

    @Test
    @DisplayName("sed -n '1p' file.txt → Passthrough（只读 sed 走 read 覆盖，cwd 内）")
    void readonlySedPassthrough() {
        PermissionResult r = BashPathValidator.check("sed -n '1p' file.txt",
            CWD, permCtx(PermissionMode.DEFAULT));
        assertInstanceOf(PermissionResult.Passthrough.class, r,
            "只读 sed（-n p）应经 sedCommandIsAllowedByAllowlist 判定为 read 覆盖，cwd 内文件放行");
    }

    // ── Edit deny 规则 → Deny（deny 优先，变异测试锚点；OPD-WF5-FS-052 root-relative）──

    @Test
    @DisplayName("Edit(//etc/**) deny 命中 /etc/hosts → Deny（// 根 root-relative）")
    void editDenyRuleDenies() {
        PermissionResult r = BashPathValidator.check("cat /etc/hosts", CWD,
            permCtx(PermissionMode.DEFAULT, editDeny("//etc/**")));
        assertInstanceOf(PermissionResult.Deny.class, r,
            "Edit(//etc/**) deny 规则（// 前缀 = 文件系统根 root-relative）命中 cat /etc/hosts → 必须 deny");
    }

    @Test
    @DisplayName("Edit(/.claude/**) deny 命中 cwd 下 .claude → Deny（/ 前缀 = session/cwd 根）")
    void editDenyProjectRootedClaudeDenies() {
        PermissionResult r = BashPathValidator.check("cat .claude/settings.json", CWD,
            permCtx(PermissionMode.DEFAULT, editDeny("/.claude/**")));
        assertInstanceOf(PermissionResult.Deny.class, r,
            "Edit(/.claude/**) deny 规则（/ 前缀 = cwd 根，CC rootPathForSource session）命中 cwd/.claude/settings.json → 必须 deny");
    }

    @Test
    @DisplayName("Edit(/etc/**) 单 / 前缀 = cwd 根 → 不命中绝对 /etc/hosts（root-relative 语义变化）")
    void editDenySingleSlashNotRootAnchored() {
        PermissionResult r = BashPathValidator.check("cat /etc/hosts", CWD,
            permCtx(PermissionMode.DEFAULT, editDeny("/etc/**")));
        assertInstanceOf(PermissionResult.Ask.class, r,
            "Edit(/etc/**) 单 / 前缀在 CC 中锚定 cwd（非文件系统根）→ 不命中绝对 /etc/hosts，回落越界 Ask（对齐 matchingRuleForInput patternWithRoot）");
    }

    // ── OPD-WF5-FS-071 · AST argv（一次 tokenize 直接产 argv，不再 re-parse 命令字符串）──

    @Test
    @DisplayName("cat '/etc/hosts'（单引号包裹路径）→ argv 剥引号后命中 Edit(//etc/**) deny → Deny")
    void editDenyQuotedPathDenies() {
        PermissionResult r = BashPathValidator.check("cat '/etc/hosts'", CWD,
            permCtx(PermissionMode.DEFAULT, editDeny("//etc/**")));
        assertInstanceOf(PermissionResult.Deny.class, r,
            "AST argv 剥引号后路径 /etc/hosts 必须被校验（单引号包裹不得绕过 deny 规则）");
    }

    @Test
    @DisplayName("timeout 5 cat /etc/hosts → stripWrappersFromArgv 剥 timeout 后 baseCmd=cat → Deny")
    void wrapperPrefixedDenyArgv() {
        PermissionResult r = BashPathValidator.check("timeout 5 cat /etc/hosts", CWD,
            permCtx(PermissionMode.DEFAULT, editDeny("//etc/**")));
        assertInstanceOf(PermissionResult.Deny.class, r,
            "argv 级 wrapper 剥离（CC stripWrappersFromArgv pathValidation.ts:1263）→ timeout 5 不掩蔽 cat 路径校验");
    }

    // ── wrapper/env 前置 cd 不得绕过 cd+write（RV-D-01 NG-2 闭环）──

    @Test
    @DisplayName("timeout 10 cd .claude && mv test.txt settings.json → Ask（ACCEPT_EDITS 下 wrapper 前置 cd 仍守卫）")
    void wrapperPrefixedCdWriteAsk() {
        // WHY（规则九）: ACCEPT_EDITS 模式下写 cwd 本会 auto-allow（对照 writeInCwdAcceptEdits），
        // 若 wrapper 前置的 cd 未被剥除（isCdCommand 裸前缀匹配漏检），compoundCommandHasCd=false
        // → mv 走 acceptEdits auto-allow → Passthrough。剥除 timeout 前缀后命中 cd → Ask，
        // 该断言唯一验证"wrapper 前置 cd 不得绕过 cd+write 守卫"这一安全不变量。
        PermissionResult r = BashPathValidator.check("timeout 10 cd .claude && mv test.txt settings.json",
            CWD, permCtx(PermissionMode.ACCEPT_EDITS));
        assertInstanceOf(PermissionResult.Ask.class, r,
            "ACCEPT_EDITS 下 wrapper 前置 cd 仍必须 ask（cd+write 守卫不得被 timeout 前缀绕过）");
    }

    @Test
    @DisplayName("'cd' .claude && mv test.txt settings.json → Ask（引号包裹首词 cd 不得绕过 cd+write）")
    void quotedPrefixedCdWriteAsk() {
        // WHY（规则九）: 与 wrapperPrefixedCdWriteAsk 同构——引号包裹首词 `'cd' .claude` 经裸
        // startsWith 匹配漏检（isCdCommand 旧实现 t.startsWith("cd ") 对 "'cd'" 为 false），
        // compoundCommandHasCd=false → mv 走 acceptEdits auto-allow → Passthrough。shell-quote
        // 首词归一化（tryParseShellCommand 等价）后 tokens[0]==='cd' 命中 → Ask。该断言唯一
        // 验证"引号包裹首词 cd 不得绕过 cd+write 守卫"这一安全不变量。
        PermissionResult r = BashPathValidator.check("'cd' .claude && mv test.txt settings.json",
            CWD, permCtx(PermissionMode.ACCEPT_EDITS));
        assertInstanceOf(PermissionResult.Ask.class, r,
            "ACCEPT_EDITS 下引号包裹首词 cd 仍必须 ask（cd+write 守卫不得被引号首词绕过）");
    }

    // ── G3-1 symlink 逃逸（双侧 realpath，对齐 CC pathInAllowedWorkingPath filesystem.ts:683-707）──

    @Test
    @DisplayName("项目内 symlink → 项目外文件：cat link → Ask（双侧 realpath 拒绝 symlink 逃逸）")
    void symlinkEscapeReadAsk(@TempDir Path tmp) throws Exception {
        // WHY（规则九）：修复前 isInWorkingDir 仅 lexical normalize，project/link 字面上在 cwd 内 →
        // 误判 allowed → 读项目外 secret 文件。双侧 toRealPath 后 link=/outside/secret.txt 不在 cwd 内
        // → 必须 ask。该断言唯一验证「symlink 逃逸不得被误判在目录内」这一安全不变量。
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);
        Path outside = tmp.resolve("secret.txt");
        Files.writeString(outside, "secret");
        Path link = proj.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "symlink 不可创建（Windows 无权限/未开开发者模式），跳过");
        }
        PermissionResult r = BashPathValidator.check("cat link", proj, permCtx(PermissionMode.DEFAULT));
        assertInstanceOf(PermissionResult.Ask.class, r,
            "项目内 symlink → 项目外文件必须 ask（realpath 后 target 不在 cwd 内），读/写均拒绝");
    }

    @Test
    @DisplayName("symlink 写逃逸（父目录 symlink + 非存在尾段）：echo hi > linkDir/new.txt → Ask（最深已存在祖先 realpath）")
    void symlinkParentWriteEscapeAsk(@TempDir Path tmp) throws Exception {
        // WHY（规则九）：重定向目标 /proj/linkDir/new.txt 本身不存在（写新文件），纯 lexical 回退会判在
        // cwd 内 → 实际写落在 /outside/new.txt（linkDir->/outside）→ 项目外任意写。resolveDeepestExistingAncestor
        // realpath 掉 linkDir → /outside/new.txt 不在 cwd → ask。该断言唯一验证「父目录 symlink + 非存在尾段
        // 不得逃逸」这一安全不变量。
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);
        Path outsideDir = tmp.resolve("outside");
        Files.createDirectories(outsideDir);
        Path linkDir = proj.resolve("linkDir");
        try {
            Files.createSymbolicLink(linkDir, outsideDir);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "symlink 不可创建（Windows 无权限/未开开发者模式），跳过");
        }
        PermissionResult r = BashPathValidator.check("echo hi > linkDir/new.txt",
            proj, permCtx(PermissionMode.DEFAULT));
        assertInstanceOf(PermissionResult.Ask.class, r,
            "重定向目标经父 symlink realpath 后落在项目外必须 ask（防写逃逸）");
    }

    @Test
    @DisplayName("普通路径（无软链）行为不变：cat file.txt → Passthrough")
    void normalPathUnchanged(@TempDir Path tmp) throws Exception {
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);
        Files.writeString(proj.resolve("file.txt"), "hi");
        assertInstanceOf(PermissionResult.Passthrough.class,
            BashPathValidator.check("cat file.txt", proj, permCtx(PermissionMode.DEFAULT)),
            "无软链普通路径 realpath 前后一致，行为不得回归");
    }

    @Test
    @DisplayName("目标不存在回退 lexical：cat newfile.txt（proj 内不存在）→ Passthrough（仍判在目录内）")
    void nonexistentPathLexicalFallback(@TempDir Path tmp) throws Exception {
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);
        assertInstanceOf(PermissionResult.Passthrough.class,
            BashPathValidator.check("cat newfile.txt", proj, permCtx(PermissionMode.DEFAULT)),
            "目标不存在路径回退 lexical（对齐 CC safeResolvePath ENOENT 回退）仍应判在目录内");
    }

    // ── BashPathValidator 比较层归一（遗留项：保留 Path.startsWith，未镜像 CC pathInWorkingPath
    //    /private/var 归一 + 大小写不敏感比较）── 对齐 CC pathInWorkingPath
    //    （utils/permissions/filesystem.ts:709-744）：expandPath（Java 侧 resolvePhysical）→
    //    macOS /private/var|/private/tmp 前缀归一 → normalizeCaseForComparison 小写归一 →
    //    relativePath 包含判定。下方纯函数测试跨平台确定性（不依赖真实文件系统大小写语义）；
    //    集成用例为回归护栏（防比较层改动破坏大小写容错 / 放大 escape）。

    @Test
    @DisplayName("normalizeMacPrivateSymlinks: /private/var/foo → /var/foo（macOS 物理路径前缀归一）")
    void normalizeMacPrivateVar() {
        assertEquals(Path.of("/var/foo"),
            BashPathValidator.normalizeMacPrivateSymlinks(Path.of("/private/var/foo")),
            "CC pathInWorkingPath .replace(/^\\/private\\/var\\//, '/var/')（filesystem.ts:717）"
                + "——realpath 后 macOS 给物理路径 /private/var/...，与未解析工作目录 /var/... 失配须归一");
    }

    @Test
    @DisplayName("normalizeMacPrivateSymlinks: /private/tmp(/|$) → /tmp（CC 正则边界）")
    void normalizeMacPrivateTmp() {
        assertEquals(Path.of("/tmp"),
            BashPathValidator.normalizeMacPrivateSymlinks(Path.of("/private/tmp")),
            "CC 第二正则 /^\\/private\\/tmp(\\/|$)/（filesystem.ts:718）含结尾无尾斜杠边界");
        assertEquals(Path.of("/tmp/foo"),
            BashPathValidator.normalizeMacPrivateSymlinks(Path.of("/private/tmp/foo")));
    }

    @Test
    @DisplayName("normalizeMacPrivateSymlinks: 非 mac 前缀不动（/private/var 单独、/var/foo 原样）")
    void normalizeMacPrivateNoop() {
        // CC 第一正则需尾斜杠（^/private/var/），/private/var 单独不归一——镜像 CC 边界
        assertEquals(Path.of("/private/var"),
            BashPathValidator.normalizeMacPrivateSymlinks(Path.of("/private/var")));
        assertEquals(Path.of("/var/foo"),
            BashPathValidator.normalizeMacPrivateSymlinks(Path.of("/var/foo")));
        assertEquals(Path.of("/private/vartmp/x"),
            BashPathValidator.normalizeMacPrivateSymlinks(Path.of("/private/vartmp/x")),
            "/private/vartmp 非 /private/var/ 前缀（缺尾斜杠），不得误归一");
    }

    @Test
    @DisplayName("pathStartsWithIgnoreCase: 大小写不敏感目录包含 + 段边界 + 越界拒绝")
    void caseInsensitiveContainment() {
        assertTrue(BashPathValidator.pathStartsWithIgnoreCase(Path.of("/work/proj"), Path.of("/WORK/PROJ/file")),
            "大小写不敏感包含（filesystem.ts:90-92 normalizeCaseForComparison）——/work/proj 含 /WORK/PROJ/file");
        assertTrue(BashPathValidator.pathStartsWithIgnoreCase(Path.of("/work/proj"), Path.of("/work/proj")),
            "同路径恒在目录内");
        assertFalse(BashPathValidator.pathStartsWithIgnoreCase(Path.of("/work/proj"), Path.of("/work/projextra/file")),
            "段边界：/work/proj 不得包含 /work/projextra（防字符串 startsWith 误判）");
        assertFalse(BashPathValidator.pathStartsWithIgnoreCase(Path.of("/work/proj"), Path.of("/etc/passwd")),
            "越界：/etc/passwd 不在 /work/proj 内");
        assertFalse(BashPathValidator.pathStartsWithIgnoreCase(Path.of("/work/proj/deep"), Path.of("/work/proj")),
            "base 深于 candidate 时 false（base 非 candidate 前缀）");
    }

    @Test
    @DisplayName("pathInWorkingPathNormalized: /private/var + 大小写组合包含判定（对齐 CC 完整比较语义）")
    void normalizedComparison() {
        assertTrue(BashPathValidator.pathInWorkingPathNormalized(Path.of("/private/var/proj/file.txt"), Path.of("/var/proj")),
            "realpath 侧 /private/var/proj/... 归一后须判在未解析 /var/proj 内（filesystem.ts:716-721）");
        assertTrue(BashPathValidator.pathInWorkingPathNormalized(Path.of("/var/proj/file.txt"), Path.of("/private/var/proj")),
            "反向：工作目录 realpath 为 /private/var/proj 时输入 /var/... 须判在内");
        assertTrue(BashPathValidator.pathInWorkingPathNormalized(Path.of("/private/var/proj/FILE.TXT"), Path.of("/var/proj")),
            "/private/var 归一 + 大小写不敏感叠加生效（尾段 FILE.TXT 大小写变体仍判在内）");
        // 镜像 CC 正则顺序：/private 前缀归一（filesystem.ts:716-721）在 normalizeCaseForComparison
        // （:725，toLowerCase）之前且大小写敏感——全大写 /PRIVATE/VAR 不归一，小写后相对 /var/proj
        // 为 ../../private/var/proj → 含穿越 → 不在内。与 CC 实际行为逐字一致。
        assertFalse(BashPathValidator.pathInWorkingPathNormalized(Path.of("/PRIVATE/VAR/PROJ/FILE.TXT"), Path.of("/var/proj")),
            "镜像 CC：/private 前缀归一是大小写敏感正则且先于小写归一，全大写 /PRIVATE/VAR 不归一 → 判不在内");
        assertTrue(BashPathValidator.pathInWorkingPathNormalized(Path.of("/private/tmp/foo"), Path.of("/tmp")),
            "/private/tmp → /tmp 归一后包含判定成立");
        assertFalse(BashPathValidator.pathInWorkingPathNormalized(Path.of("/etc/passwd"), Path.of("/var/proj")),
            "归一 + 大小写不敏感不得放大越界拒绝（/etc/passwd 仍在外）");
    }

    // ── 集成回归护栏：大小写变体不放大 escape ──

    @Test
    @DisplayName("大小写变体越界 → Ask 不回退（case-insensitive 不放大逃逸）")
    void caseVariantEscapeStillAsk(@TempDir Path tmp) throws Exception {
        // WHY（规则九）：比较层改大小写不敏感后，必须保证"项目内→外"越界仍拒绝。
        // 若把目录包含误实现成无段边界的字符串 startsWith（忽略大小写），
        // /work/proj 会把 /work/projextra 或越界路径误判在目录内 → 逃逸。该断言唯一
        // 验证「大小写不敏感不得放大 escape」这一安全不变量（G3-1 双侧 realpath 不回退）。
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);
        Path outside = tmp.resolve("secret.txt");
        Files.writeString(outside, "secret");
        PermissionResult r = BashPathValidator.check("cat ../SECRET.txt", proj, permCtx(PermissionMode.DEFAULT));
        assertInstanceOf(PermissionResult.Ask.class, r,
            "越界路径（../SECRET.txt 指向项目外，大小写变体）必须 ask，大小写不敏感比较不得放大逃逸");
    }

    @Test
    @DisplayName("大小写变体目录内路径 → Passthrough（比较层大小写容错端到端接线）")
    void caseVariantInsidePathPassthrough(@TempDir Path tmp) throws Exception {
        // WHY（规则九）：CC normalizeCaseForComparison（filesystem.ts:90-92）防大小写不敏感
        // 文件系统（macOS/Windows）上大小写变体误拒（真实文件 file.txt 与输入 FILE.TXT 同目录）。
        // Windows 上 Path.startsWith 已大小写不敏感 + realpath 规范化大小写，本用例为回归护栏，
        // 防比较层改动破坏大小写容错。macOS 上则真正依赖新比较层。
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);
        Files.writeString(proj.resolve("file.txt"), "hi");
        assertInstanceOf(PermissionResult.Passthrough.class,
            BashPathValidator.check("cat FILE.TXT", proj, permCtx(PermissionMode.DEFAULT)),
            "大小写变体路径必须判在目录内（大小写不敏感比较），不得误拒");
    }
}
