package com.nexusai.application.agent.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.File;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NexusaiPaths 运行时临时根单出口测试 · 行为镜像 CC getClaudeTempDir / getClaudeTempDirName
 * （filesystem.ts:307-315/:331-347），per-user 层品牌名 = {appName} 自有（方案A 收敛）。
 *
 * <p><b>WHY（测试验证意图而非行为 · 规则九）</b>：运行时临时根被 task 输出 / bundled-skills /
 * scratchpad / yolo-error-dump / bash 沙箱 TMPDIR 共享，此前各写方自带 claudeTempDir 重复实现且
 * Windows 硬编码 'claude' → 全仓收敛 NexusaiPaths.getAppTempDir() 单出口后，per-user 层名（品牌）
 * 与基座解析须锁死，避免回归散落。
 *
 * <p><b>期望值纪律</b>：测试共享 JVM，appName 可能被其它测试 {@link NexusaiPaths#setAppNameOverride}
 * 覆写且未复位 —— 一切期望经 {@link NexusaiPaths#getAppName()} / {@link NexusaiPaths#getAppTempDirName()}
 * 现取，严禁硬编码 "nexusai"。
 */
class NexusaiPathsTest {

    @Test
    @DisplayName("getAppTempDirName Windows：= getAppName()，无 uid 后缀（CC filesystem.ts:307-315）")
    @EnabledOnOs(OS.WINDOWS)
    void appTempDirName_windows_equalsAppNameNoUid() {
        // WHY（规则九）：Windows tmpdir（C:\Users\{user}\AppData\Local\Temp）已 per-user，
        //   CC 不加 uid（filesystem.ts:305/308-310）。per-user 层品牌名 = {appName} 自有。
        assertThat(NexusaiPaths.getAppTempDirName())
            .as("Windows per-user 层必须 = 当前 appName（无 uid 后缀）")
            .isEqualTo(NexusaiPaths.getAppName());
    }

    @Test
    @DisplayName("getAppTempDirName 非 Windows：结构 = {appName}-{uid数字}（CC filesystem.ts:313-314）")
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void appTempDirName_nonWindows_hasAppNameUidStructure() {
        // WHY（规则九）：Unix 多用户共享 /tmp，per-user 层须 uid 隔离（filesystem.ts:311-313）。
        //   uid 数字经 com.sun.security.auth.module.UnixSystem().getUid()，测试无法也不应硬编码 —— 只锁结构。
        String name = NexusaiPaths.getAppTempDirName();
        String appName = NexusaiPaths.getAppName();
        assertThat(name)
            .as("非 Windows per-user 层必须以 {appName}- 开头（带 uid 后缀）")
            .startsWith(appName + "-");
        assertThat(name).as("非 Windows per-user 层不得等于裸 appName（必须带 uid 隔离）")
            .isNotEqualTo(appName);
    }

    @Test
    @DisplayName("getAppTempDir 以 File.separator 结尾且尾部为 per-user 层名（CC filesystem.ts:346 + sep）")
    void appTempDir_endsWithSeparatorAndPerUserLayer() {
        // WHY（规则九）：CC getClaudeTempDir 返回 {base}/{dirName} + sep（filesystem.ts:331-347 + sep）；
        //   下游 Paths.get(getAppTempDir(), ...) 拼接依赖该语义。per-user 名随 appName 动态现取，不硬编码。
        String appTempDir = NexusaiPaths.getAppTempDir();
        String perUser = NexusaiPaths.getAppTempDirName();
        assertThat(appTempDir).as("运行时临时根必须带尾分隔符").endsWith(File.separator);
        assertThat(appTempDir).as("运行时临时根尾部 = per-user 层名 + 分隔符").endsWith(perUser + File.separator);
    }

    @Test
    @DisplayName("getAppTempDir 基座可解析为绝对路径（单出口基座形态）")
    void appTempDir_baseIsAbsoluteResolvable() {
        // WHY（方案A 收敛）：task 输出 / bundled-skills / scratchpad / yolo-dump / bash 沙箱同源，
        //   getAppTempDir 是唯一出口；基座（env CLAUDE_CODE_TMPDIR || Windows java.io.tmpdir / Unix /tmp）
        //   进程内恒定且须为绝对路径（下游 Files.createDirectories 依赖）。
        String appTempDir = NexusaiPaths.getAppTempDir();
        assertThat(Paths.get(appTempDir)).as("运行时临时根须为绝对路径（基座可解析）").isAbsolute();
    }
}
