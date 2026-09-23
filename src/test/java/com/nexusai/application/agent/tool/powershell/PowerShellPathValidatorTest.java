package com.nexusai.application.agent.tool.powershell;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PowerShellPathValidator 测试 · 对齐 CC pathValidation.ts checkPathConstraints 核心不变量。
 *
 * <p>WHY：修正版计划把危险删除 deny 从 pre-parse 移入 parse-failed 片段循环 + parse-succeeded 的
 * checkPathConstraints（pathValidation.ts:1735/1850）——本测试验证 parse-success 危险删除 deny、
 * Edit deny 规则、cd 复合 ask、unvalidatable ask、只读 passthrough 五条路径。
 */
class PowerShellPathValidatorTest {

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

    private static PowerShellAstService.ParsedResult single(PowerShellAstService.CommandElement... cmds) {
        List<PowerShellAstService.Statement> stmts = new ArrayList<>();
        stmts.add(new PowerShellAstService.Statement("PipelineAst",
            cmds[0].text() + (cmds.length > 1 ? " ; " + cmds[1].text() : ""),
            List.of(cmds), List.of()));
        return new PowerShellAstService.ParsedResult(true, List.of(), false, false, false, false, false, false, false, false, false, List.of(), List.of(), stmts, List.of(), "stub");
    }

    private static ObjectNode input(String command) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("command", command);
        return node;
    }

    private static ToolPermissionContext permCtx(PermissionRule... rules) {
        java.util.Map<PermissionRuleSource, java.util.Set<PermissionRule>> deny = new java.util.HashMap<>();
        java.util.Map<PermissionRuleSource, java.util.Set<PermissionRule>> ask = new java.util.HashMap<>();
        for (PermissionRule r : rules) {
            (r.ruleBehavior() == PermissionBehavior.DENY ? deny : ask)
                .computeIfAbsent(PermissionRuleSource.SESSION, k -> new java.util.HashSet<>()).add(r);
        }
        return new ToolPermissionContext(PermissionMode.DEFAULT, java.util.Map.of(), deny, ask,
            java.util.Map.of(), false, false, java.util.Map.of(), false, false, null);
    }

    private static PermissionRule editDeny(String glob) {
        return new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            new PermissionRuleValue("Edit", glob));
    }

    @Test
    @DisplayName("remove-item 危险路径（/）→ Deny（pathValidation.ts:1735 原始路径检查）")
    void dangerousRemovalDenied() {
        JsonNode in = input("Remove-Item / -Recurse -Force");
        PermissionResult r = PowerShellPathValidator.check(in,
            single(cmd("Remove-Item", "cmdlet", "/", "-Recurse", "-Force")),
            permCtx(), Path.of("C:/work/project"), Path.of("C:/work/project"), false);
        assertInstanceOf(PermissionResult.Deny.class, r,
            "Remove-Item / 在 parse-success 下必须硬 deny（isDangerousRemovalRawPath('/')）");
    }

    @Test
    @DisplayName("remove-item 家目录（~）→ Deny")
    void dangerousRemovalHomeDenied() {
        JsonNode in = input("Remove-Item ~");
        PermissionResult r = PowerShellPathValidator.check(in,
            single(cmd("Remove-Item", "cmdlet", "~")),
            permCtx(), Path.of("C:/work/project"), Path.of("C:/work/project"), false);
        assertInstanceOf(PermissionResult.Deny.class, r,
            "Remove-Item ~ 展开家目录后必须硬 deny");
    }

    @Test
    @DisplayName("Edit deny 规则命中路径 → Deny（CC :1765-1771）")
    void editDenyRuleDenies() {
        JsonNode in = input("Set-Content /etc/hosts x");
        PermissionResult r = PowerShellPathValidator.check(in,
            single(cmd("Set-Content", "cmdlet", "/etc/hosts", "x")),
            permCtx(editDeny("//etc/**")), Path.of("C:/work/project"), Path.of("C:/work/project"), false);
        assertInstanceOf(PermissionResult.Deny.class, r,
            "Edit(//etc/**) deny 规则（// 前缀 = 文件系统根 root-relative，对齐 BashPathValidatorTest.editDenyRuleDenies）"
                + "命中 Set-Content /etc/hosts → 必须 deny");
    }

    @Test
    @DisplayName("cd 复合 + 路径操作 → Ask（compoundCommandHasCd 门控，pathValidation.ts:1606-1617）")
    void compoundCdAsk() {
        JsonNode in = input("Set-Location ./x ; Get-Content ./secret");
        PermissionResult r = PowerShellPathValidator.check(in,
            single(cmd("Set-Location", "cmdlet", "./x"), cmd("Get-Content", "cmdlet", "./secret")),
            permCtx(), Path.of("C:/work/project"), Path.of("C:/work/project"), true);
        assertInstanceOf(PermissionResult.Ask.class, r,
            "cd 复合命令内路径操作因 cwd 漂移必须 ask（CC BashTool parity）");
    }

    @Test
    @DisplayName("写操作无目标路径 → Ask（CC :1709-1721 write-zero-paths）")
    void writeNoPathAsk() {
        JsonNode in = input("Set-Content");
        PermissionResult r = PowerShellPathValidator.check(in,
            single(cmd("Set-Content", "cmdlet")),
            permCtx(), Path.of("C:/work/project"), Path.of("C:/work/project"), false);
        assertInstanceOf(PermissionResult.Ask.class, r,
            "Set-Content 写操作但无法确定目标路径 → 必须 ask");
    }

    @Test
    @DisplayName("只读路径在工作目录内 → passthrough（无 deny/ask）")
    void readOnlyPassthrough() {
        JsonNode in = input("Get-Content ./file.txt");
        PermissionResult r = PowerShellPathValidator.check(in,
            single(cmd("Get-Content", "cmdlet", "./file.txt")),
            permCtx(), Path.of("C:/work/project"), Path.of("C:/work/project"), false);
        assertTrue(r instanceof PermissionResult.Passthrough
                || r instanceof PermissionResult.Allow,
            "工作目录内只读路径 → 不应 deny（passthrough 由调用方 reduce 决定）");
    }

    // ════════════════════════════════════════════════════════════════════════
    // [P7] 越界基准双轴：轴 A（相对路径解析基准）与轴 B（越界白名单根）必须独立 —— 对齐 CC：
    //   轴 A = checkPathConstraints 的 cwd 形参（getCwd()，utils/cwd.ts:26-32）
    //   轴 B = allWorkingDirectories(context) 首项 getOriginalCwd()
    //          （utils/permissions/filesystem.ts:666-673；pathInAllowedWorkingPath :683-707 只吃 context）
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[P7] 轴A=子目录 / 轴B=项目根：../data.txt → 放行（白名单根不看解析基准）")
    void dualAxis_whitelistRootWiderThanResolutionBase(@TempDir Path tmp) throws Exception {
        // WHY（规则九）：旧实现把「解析基准」同一值复用为白名单根 ⇒ Set-Location 进子目录后白名单
        //   范围随之下移（CC 语义下 cd 不改白名单范围）⇒ 项目内的 ../x 被误 ask。
        //   解析基准 = proj/sub、白名单根 = proj 时，../data.txt 解析为 proj/data.txt，落在 proj 子树内
        //   ⇒ 必须放行。单轴实现（白名单根 := proj/sub）下该路径判在 sub 之外 ⇒ 变红。
        Path proj = tmp.resolve("proj");
        Path sub = proj.resolve("sub");
        Files.createDirectories(sub);
        Files.writeString(proj.resolve("data.txt"), "x");
        PowerShellPathValidator.PathCheck pc = PowerShellPathValidator.validatePath(
            "../data.txt", sub, proj, /*permCtx*/ null, "read");
        assertTrue(pc.allowed(),
            "白名单根 = proj 时 proj/data.txt 必须放行（解析基准 proj/sub 只用于 resolve，"
                + "不得当白名单根用）实测 resolved=" + pc.resolvedPath());
    }

    @Test
    @DisplayName("[P7] 轴A=项目根 / 轴B=子目录：<proj>/other.txt → 拒绝（轴B独立于轴A）")
    void dualAxis_whitelistRootNarrowerThanResolutionBase(@TempDir Path tmp) throws Exception {
        // WHY（规则九）：反方向 —— 固定解析基准、只改白名单根，判定结果必须随之改变，证明轴 B 是
        //   独立入参。绝对路径 proj/other.txt 在解析基准 proj 之内、在白名单根 sub 之外 ⇒ 必须拒绝。
        //   单轴实现（白名单根 := proj）下该路径在 proj 内 ⇒ 误放行（安全方向错）⇒ 变红。
        Path proj = tmp.resolve("proj");
        Path sub = proj.resolve("sub");
        Files.createDirectories(sub);
        Path other = proj.resolve("other.txt");
        Files.writeString(other, "x");
        PowerShellPathValidator.PathCheck pc = PowerShellPathValidator.validatePath(
            other.toString(), proj, sub, /*permCtx*/ null, "read");
        assertFalse(pc.allowed(),
            "白名单根 = sub 时 sub 之外的 proj/other.txt 必须拒绝（轴 B 独立于轴 A）"
                + "实测 resolved=" + pc.resolvedPath());
    }

    @Test
    @DisplayName("[P7] 两轴同值时行为不变：proj 内 ./data.txt → 放行")
    void dualAxis_sameValueBehavesAsBefore(@TempDir Path tmp) throws Exception {
        Path proj = tmp.resolve("proj");
        Files.createDirectories(proj);
        Files.writeString(proj.resolve("data.txt"), "x");
        PowerShellPathValidator.PathCheck pc = PowerShellPathValidator.validatePath(
            "./data.txt", proj, proj, /*permCtx*/ null, "read");
        assertTrue(pc.allowed(), "两轴同值时白名单内路径必须放行（拆分不得改变常态行为）");
    }

    @Test
    @DisplayName("[P7] 轴B 缺失 → Ask（宁问不放，白名单根不得回落解析基准）")
    void dualAxis_whitelistRootMissingAsks() {
        // WHY（规则九）：越界白名单根缺失时该路径必须落 ask（isInWorkingDir(whitelistRoot=null)
        //   恒 false = fail-closed）—— 回落解析基准等于把「轴 A」重新当「轴 B」用，正是本批要消灭
        //   的混用；无条件 ask 则会把与白名单无关的无路径命令一起变成 ask（误 ask），故不做。
        JsonNode in = input("Get-Content ./file.txt");
        PermissionResult r = PowerShellPathValidator.check(in,
            single(cmd("Get-Content", "cmdlet", "./file.txt")),
            permCtx(), Path.of("C:/work/project"), /*whitelistRoot*/ null, false);
        assertInstanceOf(PermissionResult.Ask.class, r,
            "越界白名单根（轴 B）缺失 ⇒ 必须 ask（不得回落解析基准，也不得静默放行）");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // [批 appname-dyn 追加 2026-09-23] 第三份 DANGEROUS_FILES 的全局配置文件名动态化
    //   （PathValidation / WritePermissionChecker 已改，本份是复验点名的第三份）
    // ─────────────────────────────────────────────────────────────────────────

    /** ACCEPT_EDITS 上下文（工作目录内写 → allowed）· 使 allowed() 成为可判别的二分信号。 */
    private static ToolPermissionContext acceptEdits() {
        return new ToolPermissionContext(PermissionMode.ACCEPT_EDITS, java.util.Map.of(),
            java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
            false, false, java.util.Map.of(), false, false, null);
    }

    @Test
    @DisplayName("⭐危险文件名随 appName：nexusai ⇒ <home>/.nexusai.json 仍危险；nexusai-scene ⇒ 换名且旧名转放行")
    void dangerousGlobalConfigFile_followsAppName() {
        String home = System.getProperty("user.home", ".");
        Path homeDir = Path.of(home);
        try {
            NexusaiPaths.setAppNameOverride("nexusai");
            // 工作目录 = home + ACCEPT_EDITS ⇒ 非危险文件必 allowed；危险文件在 step2.5 先被拦 ⇒ 二分可判别
            PowerShellPathValidator.PathCheck legacy = PowerShellPathValidator.isPathAllowed(
                home + "/.nexusai.json", null, homeDir, acceptEdits(), "write");
            assertFalse(legacy.allowed(),
                "⭐不变量：.nexusai.json 已移出静态 DANGEROUS_FILES ⇒ 必须仍由动态判定拦下"
                    + "（appName=nexusai，报错=" + legacy.message() + "）");
            assertTrue(legacy.message() != null && legacy.message().contains("路径命中危险文件 .nexusai.json"),
                "报错文案取 getGlobalConfigFileName() 规范形 ⇒ 与移出静态条目前的循环文案逐字节同："
                    + legacy.message());
            assertTrue(PowerShellPathValidator.isPathAllowed(
                home + "/.nexusai-scene.json", null, homeDir, acceptEdits(), "write").allowed(),
                "appName=nexusai ⇒ 非当前 appName 的 .nexusai-scene.json 不危险（应放行）");

            NexusaiPaths.setAppNameOverride("nexusai-scene");
            PowerShellPathValidator.PathCheck scene = PowerShellPathValidator.isPathAllowed(
                home + "/.nexusai-scene.json", null, homeDir, acceptEdits(), "write");
            assertFalse(scene.allowed(),
                "⭐scene ⇒ .{appName}.json 必须危险（确实跟着 appName 走，报错=" + scene.message() + "）");
            assertTrue(PowerShellPathValidator.isPathAllowed(
                home + "/.nexusai.json", null, homeDir, acceptEdits(), "write").allowed(),
                "scene ⇒ 旧名 .nexusai.json 随之为放行（证明确实是动态派生，不是静态恒真）");
        } finally {
            // 共享 JVM：appName 必须复原，否则污染后续测试
            NexusaiPaths.setAppNameOverride("nexusai");
        }
    }
}
