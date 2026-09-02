package com.nexusai.application.agent.readonly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReadOnlyCommandTable 表一致性测试 · 逐条对照 CC 真源数量与关键条目。
 *
 * <p>WHY（意图验证）：共享只读命令表是 Bash/PowerShell 权限链的安全数据源，表项与 CC
 * 漂移 = 只读命令被误放行（fail-open）或误阻断（可用性损坏）。本测试锁定数量契约：
 * <ul>
 *   <li>PS CMDLET_ALLOWLIST 85 项（68 引号 cmdlet + 17 原生 exe，CC readOnlyValidation.ts:129-882）</li>
 *   <li>GIT_READ_ONLY_COMMANDS 24 / GH_READ_ONLY_COMMANDS 22 / DOCKER 2（CC readOnlyCommandValidation.ts）</li>
 *   <li>bash COMMAND_ALLOWLIST 51 项（23 显式 + git24 + rg + pyright + docker2，CC BashTool/readOnlyValidation.ts:128-1140）</li>
 *   <li>resolveToCanonical 别名解析（cd→set-location、rm→remove-item）</li>
 * </ul>
 */
class ReadOnlyCommandTableTest {

    @Test
    @DisplayName("表数量契约与 CC 一致")
    void tableCountsMatchCc() {
        assertEquals(85, ReadOnlyCommandTable.psCmdletCount(),
            "PS CMDLET_ALLOWLIST 应为 85 项（68 引号 + 17 原生，CC readOnlyValidation.ts:129-882）");
        assertEquals(24, ReadOnlyCommandTable.gitCommandCount(),
            "GIT_READ_ONLY_COMMANDS 应为 24 项（CC readOnlyCommandValidation.ts:107-923）");
        assertEquals(22, ReadOnlyCommandTable.ghCommandCount(),
            "GH_READ_ONLY_COMMANDS 应为 22 项（CC readOnlyCommandValidation.ts:984-1380）");
        assertEquals(2, ReadOnlyCommandTable.dockerCommandCount(),
            "DOCKER_READ_ONLY_COMMANDS 应为 2 项（docker logs/inspect）");
        assertEquals(51, ReadOnlyCommandTable.bashCommandCount(),
            "bash COMMAND_ALLOWLIST 有效键应为 51 项（23 显式 + git24 + rg + pyright + docker2，CC BashTool/readOnlyValidation.ts:128-1140）");
    }

    @Test
    @DisplayName("pyright 在 bash COMMAND_ALLOWLIST 中（CC :1501 PYRIGHT_READ_ONLY_COMMANDS spread）")
    void pyrightPresent() {
        assertNotNull(ReadOnlyCommandTable.lookupExternalCommand("pyright"),
            "pyright 应存在（CC readOnlyCommandValidation.ts:1501-1528，必须补 pyright）");
        ReadOnlyCommandTable.ExternalCommandConfig cfg =
            ReadOnlyCommandTable.lookupExternalCommand("pyright");
        assertTrue(!cfg.respectsDoubleDash(), "pyright respectsDoubleDash=false（CC 注释：-- 当文件路径）");
        assertTrue(cfg.safeFlags().containsKey("--outputjson"),
            "pyright safeFlags 应含 --outputjson");
    }

    @Test
    @DisplayName("关键 cmdlet 白名单 flag 与 CC 一致")
    void keyCmdletConfigsMatchCc() {
        ReadOnlyCommandTable.CmdletConfig getProcess =
            ReadOnlyCommandTable.lookupPsCmdlet("Get-Process");
        assertNotNull(getProcess, "Get-Process 应在 allowlist");
        assertTrue(getProcess.safeFlags().stream().anyMatch(f -> f.equalsIgnoreCase("-Name")),
            "Get-Process safeFlags 应含 -Name（CC readOnlyValidation.ts:405-413）");

        ReadOnlyCommandTable.CmdletConfig formatTable =
            ReadOnlyCommandTable.lookupPsCmdlet("Format-Table");
        assertNotNull(formatTable);
        assertTrue(formatTable.allowAllFlags(), "Format-Table 应 allowAllFlags（CC :520-524）");
        assertTrue(formatTable.argLeaksValue(), "Format-Table 应 argLeaksValue（CC additionalCommandIsDangerousCallback）");

        assertNull(ReadOnlyCommandTable.lookupPsCmdlet("Remove-Item"),
            "Remove-Item 不在只读 allowlist（删除类命令必须 ask）");
        assertNull(ReadOnlyCommandTable.lookupPsCmdlet("Invoke-Expression"),
            "Invoke-Expression 不在只读 allowlist（代码执行必须 ask）");
    }

    @Test
    @DisplayName("别名解析 resolveToCanonical 与 CC 一致")
    void canonicalAliasResolution() {
        assertEquals("set-location", ReadOnlyCommandTable.resolveToCanonical("cd"),
            "cd → Set-Location（CC parser.ts:1326 COMMON_ALIASES）");
        assertEquals("remove-item", ReadOnlyCommandTable.resolveToCanonical("rm"),
            "rm → Remove-Item");
        assertEquals("get-childitem", ReadOnlyCommandTable.resolveToCanonical("ls"),
            "ls → Get-ChildItem");
        assertEquals("invoke-expression", ReadOnlyCommandTable.resolveToCanonical("iex"),
            "iex → Invoke-Expression");
        assertEquals("git", ReadOnlyCommandTable.resolveToCanonical("git.exe"),
            "git.exe 去 PATHEXT → git（CC readOnlyValidation.ts:984-996）");
        // 别名匹配：ls 通过 COMMON_ALIASES 解析到 get-childitem allowlist
        assertNotNull(ReadOnlyCommandTable.lookupPsCmdlet("ls"),
            "ls 经别名解析应命中 get-childitem allowlist");
    }

    @Test
    @DisplayName("外部命令表 key 与 CC 一致")
    void externalCommandKeys() {
        assertTrue(ReadOnlyCommandTable.gitCommandKeys().contains("git status"),
            "git status 应在 git 只读表（CC :341）");
        assertTrue(ReadOnlyCommandTable.gitCommandKeys().contains("git log"));
        assertTrue(ReadOnlyCommandTable.ghCommandKeys().contains("gh pr view"),
            "gh pr view 应在 gh 只读表");
        assertTrue(ReadOnlyCommandTable.ghCommandKeys().contains("gh search code"));
        assertNotNull(ReadOnlyCommandTable.lookupExternalCommand("docker logs"),
            "docker logs 应为外部命令配置");
        assertTrue(ReadOnlyCommandTable.isExternalReadOnlyCommand("docker ps"),
            "docker ps 应为 EXTERNAL_READONLY_COMMANDS（CC :1539-1543）");
        assertTrue(ReadOnlyCommandTable.isExternalReadOnlyCommand("docker images"));
    }

    @Test
    @DisplayName("命令名层 lookupBashCommandName 覆盖（CC READONLY_COMMANDS :1432-1503 + regex 简单命令）")
    void bashReadonlyCommandNameLookup() {
        // WHY（意图验证 · A7b 搬迁）：BashParser 私有 READONLY_COMMANDS 已删，命令名层单源化到
        // 本表。isReadOnly 契约的 fail-closed gate 依赖此查询——命令名不在名字层 → 非只读。
        // 验证 CC 名字层代表命令命中，危险/未知命令 fail-closed 不命中。
        assertTrue(ReadOnlyCommandTable.lookupBashCommandName("cat"),
            "cat ∈ CC READONLY_COMMANDS (readOnlyValidation.ts:1432-1503)");
        assertTrue(ReadOnlyCommandTable.lookupBashCommandName("ls"),
            "ls ∈ 命令名层（BashToolAlignmentTest:93 断言 isReadOnly('ls -la')==true 依赖）");
        assertTrue(ReadOnlyCommandTable.lookupBashCommandName("find"),
            "find ∈ 命令名层（BashToolAlignmentTest:96 isReadOnly('find . -type f')==true）");
        assertTrue(ReadOnlyCommandTable.lookupBashCommandName("grep"),
            "grep ∈ 命令名层");
        assertTrue(ReadOnlyCommandTable.lookupBashCommandName("head"),
            "head ∈ CC READONLY_COMMANDS");
        assertTrue(ReadOnlyCommandTable.lookupBashCommandName("echo"),
            "echo ∈ CC READONLY_COMMAND_REGEXES 简单命令 (readOnlyValidation.ts:1516)");

        assertFalse(ReadOnlyCommandTable.lookupBashCommandName("curl"),
            "curl 不在名字层（fail-closed，:373 gate 应拒绝）");
        assertFalse(ReadOnlyCommandTable.lookupBashCommandName("mv"),
            "mv 是写命令，不在只读名字层");
        assertFalse(ReadOnlyCommandTable.lookupBashCommandName(null),
            "null 查询须 fail-closed false");
        assertFalse(ReadOnlyCommandTable.lookupBashCommandName(""),
            "空串查询须 false");
        // 大小写不敏感（CC 命令名小写归一）
        assertTrue(ReadOnlyCommandTable.lookupBashCommandName("CAT"),
            "命令名小写归一（CAT → cat）");
    }

    @Test
    @DisplayName("bashReadonlyCommandNameCount ≥ 60（名字层超集 flag 表 51 键）")
    void bashReadonlyCommandNameCount_superset() {
        // WHY（意图验证 · A7b 超集证明）：名字层搬迁必须覆盖旧 BashParser 私有 Set 全集，
        // 否则 isReadOnly 语义回归（ls/cat/find 不在 51 键 flag 表，BashToolAlignmentTest:93-96
        // 依赖名字层）。60 = 旧私有 Set 条目数（48 CC READONLY_COMMANDS + 7 regex 简单命令 + 5
        // COMMAND_ALLOWLIST 核心名）。
        int nameCount = ReadOnlyCommandTable.bashReadonlyCommandNameCount();
        assertTrue(nameCount >= 60,
            "命令名层应 ≥ 60 条（旧 BashParser.READONLY_COMMANDS 全集搬迁），实际 " + nameCount);
        // 51 键超集：flag 表外但名字层内的命令（A7a 已断言 ls/cat/find/pwd/echo 不在 flag 表）
        assertTrue(ReadOnlyCommandTable.bashReadonlyCommandNameCount()
                > ReadOnlyCommandTable.bashCommandCount(),
            "名字层条目应多于 flag 级表（名字层是 flag 表超集，Cover 表外命令）");
    }

    @Test
    @DisplayName("matchesBashReadonlyEcho 语义锁（A7c · 全命令正则 + 未引号展开守卫 + 2>&1 预剥离）")
    void matchesBashReadonlyEcho_semanticLock() {
        // WHY（意图验证 · A7c）：BashTool step4 flag 级 miss 后以此方法作 echo 名字层回退门。
        // 方法若退化放行任一写命令（cat a>b / echo a|b / echo $HOME），即从"只读 Allow"变
        // "任意写放行"——因此 Allow 子集锁 CC 正则（:1516）允许的简单形式，Deny 子集锁
        // 每个 bypass 向量（管道/重定向/变量/命令替换/&&/大小写/未引号 glob，:1600 守卫）。
        // Allow 子集（CC isCommandReadOnly:1719 regex tier 对 echo 放行）：
        assertTrue(ReadOnlyCommandTable.matchesBashReadonlyEcho("echo hi"),
            "echo hi 简单形式须 Allow（CC readOnlyValidation.ts:1516 正则）");
        assertTrue(ReadOnlyCommandTable.matchesBashReadonlyEcho("echo 'a b'"),
            "单引号串（含空格/换行安全）须 Allow");
        assertTrue(ReadOnlyCommandTable.matchesBashReadonlyEcho("echo \"a b\""),
            "双引号串（无 $<> ）须 Allow");
        assertTrue(ReadOnlyCommandTable.matchesBashReadonlyEcho("echo hi 2>&1"),
            "尾随 2>&1 预剥离（isCommandReadOnly:1682-1686）后须 Allow");
        assertTrue(ReadOnlyCommandTable.matchesBashReadonlyEcho("echo '$HOME'"),
            "单引号内 $ 为字面，CC 放行（containsUnquotedExpansion 单引号跳过）");
        // Deny 子集（任一允许即 Write 逃逸）：
        assertFalse(ReadOnlyCommandTable.matchesBashReadonlyEcho("echo hi > f"),
            "重定向写文件不得 Allow");
        assertFalse(ReadOnlyCommandTable.matchesBashReadonlyEcho("echo $HOME"),
            "未引号变量展开不得 Allow");
        assertFalse(ReadOnlyCommandTable.matchesBashReadonlyEcho("echo \"$HOME\""),
            "双引号变量展开不得 Allow（正则排除 $）");
        assertFalse(ReadOnlyCommandTable.matchesBashReadonlyEcho("echo $(date)"),
            "命令替换不得 Allow");
        assertFalse(ReadOnlyCommandTable.matchesBashReadonlyEcho("echo a | grep b"),
            "管道不得 Allow");
        assertFalse(ReadOnlyCommandTable.matchesBashReadonlyEcho("echo `whoami`"),
            "反引号命令替换不得 Allow");
        assertFalse(ReadOnlyCommandTable.matchesBashReadonlyEcho("echo a && b"),
            "&& 复合命令不得 Allow");
        assertFalse(ReadOnlyCommandTable.matchesBashReadonlyEcho("ECHO hi"),
            "ECHO 大写不得 Allow（^echo 大小写敏感）");
        assertFalse(ReadOnlyCommandTable.matchesBashReadonlyEcho("echo *"),
            "未引号 glob 不得 Allow（containsUnquotedExpansion:1600 守卫）");
        assertFalse(ReadOnlyCommandTable.matchesBashReadonlyEcho("cat file.txt"),
            "非 echo 命令不得 Allow（防裸名字层放行 cat）");
        assertFalse(ReadOnlyCommandTable.matchesBashReadonlyEcho(null),
            "null 须 fail-closed false");
        assertFalse(ReadOnlyCommandTable.matchesBashReadonlyEcho(""),
            "空串须 false");
    }

    @Test
    @DisplayName("ipconfig/hostname/route 位置参数拒绝回调接线（CC readOnlyValidation.ts:705-712/749-755/777-791）")
    void ipconfigHostnameRoutePositionalCallbacks() {
        // WHY（意图验证 · RV-D-04 NG-PD-1）：三命令的附加危险回调若缺接线，位置参数在 flag 循环
        // 穿透（flag 循环只校验 isFlag，非 flag 位置参数不校验）→ 只读自动放行（假接线）。回调负责
        // 位置参数拒绝，须非 null 且语义与 CC 逐字一致。

        ReadOnlyCommandTable.CmdletConfig ipconfig = ReadOnlyCommandTable.lookupPsCmdlet("ipconfig");
        assertNotNull(ipconfig, "ipconfig 应在 allowlist");
        assertNotNull(ipconfig.callback(), "ipconfig 应挂位置参数拒绝回调（CC :705-712）");
        assertTrue(ipconfig.callback().isDangerous("ipconfig", List.of("set", "en1", "DHCP")),
            "ipconfig set en1 DHCP → 危险（位置参数写配置）");
        assertFalse(ipconfig.callback().isDangerous("ipconfig", List.of("/all")),
            "ipconfig /all → 安全（纯 flag 显示）");

        ReadOnlyCommandTable.CmdletConfig hostname = ReadOnlyCommandTable.lookupPsCmdlet("hostname");
        assertNotNull(hostname, "hostname 应在 allowlist");
        assertNotNull(hostname.callback(), "hostname 应挂位置参数拒绝回调（CC :749-755）");
        assertTrue(hostname.callback().isDangerous("hostname", List.of("mybox")),
            "hostname mybox → 危险（设置主机名）");
        assertFalse(hostname.callback().isDangerous("hostname", List.of("-a")),
            "hostname -a → 安全（纯 flag 显示）");

        ReadOnlyCommandTable.CmdletConfig route = ReadOnlyCommandTable.lookupPsCmdlet("route");
        assertNotNull(route, "route 应在 allowlist");
        assertNotNull(route.callback(), "route 应挂 verb 拒绝回调（CC :777-791）");
        assertTrue(route.callback().isDangerous("route", List.of("add", "10.0.0.0", "mask", "255.0.0.0", "192.168.1.1")),
            "route add ... → 危险（写路由表）");
        assertFalse(route.callback().isDangerous("route", List.of("print")),
            "route print → 安全（只读显示）");
        assertFalse(route.callback().isDangerous("route", List.of("PRINT")),
            "route PRINT → 安全（大小写不敏感）");
        assertTrue(route.callback().isDangerous("route", List.of()),
            "裸 route（无参）→ 危险（CC :786-788 verb undefined 分支）");
    }
}
