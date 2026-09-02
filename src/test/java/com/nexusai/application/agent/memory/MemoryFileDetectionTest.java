package com.nexusai.application.agent.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-M-P0-1] MemoryFileDetection 门控 + normalize + Windows 小写化 · 对齐 CC
 * {@code utils/memoryFileDetection.ts}。
 *
 * <p>WHY (规则九 · 测试验证意图): CC isAutoMemFile = isAutoMemoryEnabled() && isAutoMemPath()
 * （memoryFileDetection.ts:87-92）——门控关闭时即使路径在 auto-memory 目录内也必须返回 false
 * （否则禁用 auto-memory 后记忆文件仍被当作 memory 访问处理）。旧 SessionFileAccessHooks.isAutoMemFile
 * 无门控、无 normalize。本测试锁定: 门控 on/off 两侧 + 大小写归一化。
 */
@DisplayName("[IMP-M-P0-1] MemoryFileDetection 门控对齐 CC memoryFileDetection.ts")
class MemoryFileDetectionTest {

    private static AutoMemPaths paths(Path memoryBase, Path projectRoot) {
        return new AutoMemPaths(
            () -> projectRoot.toString(), () -> memoryBase.toString(), () -> null, () -> null);
    }

    @Test
    @DisplayName("isAutoMemFile 门控 on → 目录内命中；门控 off → 目录内也不命中（memoryFileDetection.ts:87-92）")
    void isAutoMemFile_gateOnAndOff(@TempDir Path memoryBase, @TempDir Path projectRoot) {
        // WHY: isAutoMemFile 必须先过 isAutoMemoryEnabled 门控。门控 off（如 CLAUDE_CODE_DISABLE_AUTO_MEMORY=1）
        //       时，即使路径落在 <autoMemPath> 内也必须返回 false（CC :88-90）。
        AutoMemPaths paths = paths(memoryBase, projectRoot);
        String fileInAutoMem = paths.getAutoMemPath() + "MEMORY.md";

        MemoryFileDetection on = new MemoryFileDetection(paths, () -> memoryBase.toString(),
            () -> true, () -> false, () -> false);
        assertThat(on.isAutoMemFile(fileInAutoMem))
            .as("门控 on + 目录内路径 → true")
            .isTrue();

        MemoryFileDetection off = new MemoryFileDetection(paths, () -> memoryBase.toString(),
            () -> false, () -> false, () -> false);
        assertThat(off.isAutoMemFile(fileInAutoMem))
            .as("门控 off → 目录内路径也必须 false")
            .isFalse();
    }

    @Test
    @DisplayName("toComparable normalize 分隔符（memoryFileDetection.ts:31-34）")
    void toComparable_normalizesSeparators() {
        // WHY: 路径匹配必须统一分隔符（Windows 反斜杠 vs posix 正斜杠），否则跨平台路径判定漂移。
        assertThat(MemoryFileDetection.toComparable("C:\\Users\\foo\\memory\\a.md"))
            .contains("/memory/a.md")
            .doesNotContain("\\memory");
    }

    // ════════════════════════════════════════════════════════════════
    // IMP-MV2-18 · 谓词链 team 分支 feature('TEAMMEM') 组合
    // （memoryFileDetection.ts:107/:136-137/:169-171 · △-feature-1/2/3）
    // ════════════════════════════════════════════════════════════════

    /** 构造 MemoryFileDetection：autoMemory 恒开 + feature/runtime 双门可控。 */
    private static MemoryFileDetection gates(boolean feature, boolean runtime) {
        AutoMemPaths p = new AutoMemPaths(
            () -> "C:/proj", () -> "C:/mem", () -> null, () -> null);
        return new MemoryFileDetection(p, () -> "C:/cfg",
            () -> true,  // autoMemoryEnabled（CC isAutoMemoryEnabled）
            () -> feature,  // 编译开关 feature('TEAMMEM')
            () -> runtime); // 运行时开关 tengu_herring_clock
    }

    @Test
    @DisplayName("memoryScopeForPath：feature 关 + runtime 开 → team 文件回落 'personal'（memoryFileDetection.ts:107）")
    void memoryScopeForPath_featureOffRuntimeOn_teamFileFallsBackToPersonal() {
        // WHY: CC :107 `feature('TEAMMEM') && teamMemPaths!.isTeamMemFile(filePath)` —— feature 编译门
        //       关时 team 分支不可达；team 目录在 autoMemPath 下 → isAutoMemFile 命中 → 'personal'
        //       （旧实现无 feature 组合 → 误判 'team'，遥测 scope 污染）。
        MemoryFileDetection det = gates(false, true);
        String teamFile = det.getTeamMemPath() + "MEMORY.md";

        assertThat(det.memoryScopeForPath(teamFile))
            .as("feature 关 + runtime 开 → team 分支不可达，回落 personal")
            .isEqualTo(MemoryFileDetection.MemoryScope.PERSONAL);
    }

    @Test
    @DisplayName("memoryScopeForPath：双开 → team 文件判 'team'（team 先查，CC :99-100/:107）")
    void memoryScopeForPath_bothOn_teamFileIsTeam() {
        MemoryFileDetection det = gates(true, true);
        String teamFile = det.getTeamMemPath() + "MEMORY.md";

        assertThat(det.memoryScopeForPath(teamFile))
            .as("双开 → team 分支命中")
            .isEqualTo(MemoryFileDetection.MemoryScope.TEAM);
    }

    @Test
    @DisplayName("memoryScopeForPath：feature 开 + runtime 关 → team 文件回落 'personal'（isTeamMemFile 内含 runtime 门）")
    void memoryScopeForPath_featureOnRuntimeOff_teamFileFallsBackToPersonal() {
        // WHY: isTeamMemFile = isTeamMemoryEnabled && isTeamMemPath（teamMemPaths.ts:290-292），
        //       runtime 关 → team 分支不命中 → 回落 personal。与 feature 门正交（双门控拆分）。
        MemoryFileDetection det = gates(true, false);
        String teamFile = det.getTeamMemPath() + "MEMORY.md";

        assertThat(det.memoryScopeForPath(teamFile))
            .isEqualTo(MemoryFileDetection.MemoryScope.PERSONAL);
    }

    @Test
    @DisplayName("memoryScopeForPath：非 memory 文件 → null（双关零行为变化）")
    void memoryScopeForPath_nonMemoryFile_isNull() {
        MemoryFileDetection det = gates(false, false);

        assertThat(det.memoryScopeForPath("C:/proj/other/file.md")).isNull();
    }

    @Test
    @DisplayName("isAutoManagedMemoryFile：feature 关 + runtime 开 → team 文件经 autoMem 分支仍 true（CC :136-137）")
    void isAutoManagedMemoryFile_featureOffRuntimeOn_teamFileStillManaged() {
        // WHY: CC :133-147 链 = isAutoMemFile || (feature && isTeamMemFile) || session || agent ——
        //       feature 关时 team 分支不可达，但 team 目录是 autoMemPath 子目录 → isAutoMemFile 分支
        //       兜底 → 结果不变（双关零行为变化：与旧实现结果一致）。
        MemoryFileDetection det = gates(false, true);
        String teamFile = det.getTeamMemPath() + "MEMORY.md";

        assertThat(det.isAutoManagedMemoryFile(teamFile)).isTrue();
    }

    @Test
    @DisplayName("isAutoManagedMemoryFile：双开 → team 文件 true（CC :136-137 组合生效）")
    void isAutoManagedMemoryFile_bothOn_teamFileIsManaged() {
        MemoryFileDetection det = gates(true, true);
        String teamFile = det.getTeamMemPath() + "MEMORY.md";

        assertThat(det.isAutoManagedMemoryFile(teamFile)).isTrue();
    }

    @Test
    @DisplayName("isMemoryDirectory：feature 关 + runtime 开 → team 目录经 autoMem 分支仍 true（CC :169-171）")
    void isMemoryDirectory_featureOffRuntimeOn_teamDirStillMemoryDir() {
        // WHY: CC :169-175 feature 关 → team 分支不可达，但 autoMemPath override 分支（:177-187）
        //       命中 team 目录（team 是 autoMem 子目录）→ 结果不变（双关零行为变化）。
        MemoryFileDetection det = gates(false, true);
        String teamDir = det.getTeamMemPath();

        assertThat(det.isMemoryDirectory(teamDir)).isTrue();
    }

    @Test
    @DisplayName("isMemoryDirectory：双开 → team 目录 true（CC :169-175 组合生效）")
    void isMemoryDirectory_bothOn_teamDirIsMemoryDir() {
        MemoryFileDetection det = gates(true, true);
        String teamDir = det.getTeamMemPath();

        assertThat(det.isMemoryDirectory(teamDir)).isTrue();
    }

    @Test
    @DisplayName("isMemoryDirectory：双关 → team 目录仍 true（autoMem 分支；零行为变化）")
    void isMemoryDirectory_bothOff_teamDirIsMemoryDir() {
        MemoryFileDetection det = gates(false, false);
        String teamDir = det.getTeamMemPath();

        assertThat(det.isMemoryDirectory(teamDir)).isTrue();
    }

    // ════════════════════════════════════════════════════════════════
    // IMP-CM-09 · TeamMemPaths 双门控独立控制（OPD-CM3-11/B04）
    // ════════════════════════════════════════════════════════════════

    private static TeamMemPaths dualGate(boolean feature, boolean runtime) {
        AutoMemPaths p = new AutoMemPaths(
            () -> "C:/proj", () -> "C:/mem", () -> null, () -> null);
        return new TeamMemPaths(p,
            () -> true,  // autoMemoryEnabled（CC isAutoMemoryEnabled）
            () -> feature,  // 编译开关 feature('TEAMMEM')
            () -> runtime); // 运行时开关 tengu_herring_clock
    }

    @Test
    @DisplayName("双门控：编译开 + 运行时关 → isTeamMemoryEnabled=false（不启用）")
    void dualGate_compileOnRuntimeOff_disabled() {
        // WHY: OPD-CM3-11/B04 验收「两开关可分别控制（编译开 + 运行时关 = 不启用）」。CC AND 语义：
        // isTeamMemoryEnabled = isAutoMemoryEnabled() && tengu_herring_clock（teamMemPaths.ts:73-78）——
        // 运行时开关关 → 即使编译开关开也不启用。
        TeamMemPaths paths = dualGate(true, false);
        assertThat(paths.isTeamMemoryEnabled())
            .as("编译开 + 运行时关 → isTeamMemoryEnabled=false（CC :77 tengu_herring_clock=false）")
            .isFalse();
        assertThat(paths.isTeamMemFeatureEnabled())
            .as("编译开关独立可控制 → 编译开时 isTeamMemFeatureEnabled=true")
            .isTrue();
    }

    @Test
    @DisplayName("双门控：编译关 + 运行时开 → isTeamMemFeatureEnabled=false（不启用）")
    void dualGate_compileOffRuntimeOn_disabled() {
        // WHY: OPD-CM3-11/B04 验收「编译关 + 运行时开 = 不启用」。CC watcher.ts:253
        // if (!feature('TEAMMEM')) return —— 编译开关关 → 整链不启用。
        TeamMemPaths paths = dualGate(false, true);
        assertThat(paths.isTeamMemFeatureEnabled())
            .as("编译关 + 运行时开 → isTeamMemFeatureEnabled=false（watcher.ts:253）")
            .isFalse();
        assertThat(paths.isTeamMemoryEnabled())
            .as("运行时开关独立可控制 → 运行时开 + autoMemory 开时 isTeamMemoryEnabled=true")
            .isTrue();
    }

    @Test
    @DisplayName("双门控：双开 → isTeamMemFeatureEnabled && isTeamMemoryEnabled 均 true（启用）")
    void dualGate_bothOn_enabled() {
        // WHY: OPD-CM3-11/B04 验收「双开 = 启用」—— 与 CC AND 语义一致（watcher.ts:253 feature
        // && :256 isTeamMemoryEnabled）。
        TeamMemPaths paths = dualGate(true, true);
        assertThat(paths.isTeamMemFeatureEnabled())
            .as("双开 → 编译开关生效")
            .isTrue();
        assertThat(paths.isTeamMemoryEnabled())
            .as("双开 → 运行时开关生效（autoMemory && tengu_herring_clock）")
            .isTrue();
    }

    @Test
    @DisplayName("双门控：双关 → isTeamMemFeatureEnabled && isTeamMemoryEnabled 均 false（不启用）")
    void dualGate_bothOff_disabled() {
        // WHY: OPD-CM3-11/B04 验收「双关 = 不启用」—— 两开关独立控制，任一关即不启用。
        TeamMemPaths paths = dualGate(false, false);
        assertThat(paths.isTeamMemFeatureEnabled()).as("双关 → 编译开关关").isFalse();
        assertThat(paths.isTeamMemoryEnabled()).as("双关 → 运行时开关关").isFalse();
    }

    // ════════════════════════════════════════════════════════════════
    // IMP-C-3 · U-2 五谓词接线（OPD-CM5-C-07）· collapseReadSearch 消费链
    // isMemorySearch / isMemoryWriteOrEdit · collapseReadSearch.ts:81-115
    // ════════════════════════════════════════════════════════════════

    /**
     * 构造 MemoryFileDetection：autoMemory 恒开 + feature/runtime 双关（team 分支不可达，
     * 纯 personal memory 判定路径）。
     */
    private static MemoryFileDetection personalOnly() {
        AutoMemPaths p = new AutoMemPaths(
            () -> "C:/proj", () -> "C:/mem", () -> null, () -> null);
        return new MemoryFileDetection(p, () -> "C:/cfg",
            () -> true, () -> false, () -> false);
    }

    @Test
    @DisplayName("isMemorySearch：搜索路径命中记忆文件/目录 → true（collapseReadSearch.ts:89-92）")
    void isMemorySearch_memoryPath_isTrue() {
        // WHY: collapseReadSearch.ts:89-92 `if (input.path) { if (isAutoManagedMemoryFile(input.path)
        //      || isMemoryDirectory(input.path)) return true }` —— Grep/Glob 搜索路径落在记忆文件/目录时
        //      该搜索必须计入 memorySearchCount（U-2 五谓词接线后 isAutoManagedMemoryFile/
        //      isMemoryDirectory 获得生产消费）。
        AutoMemPaths p = new AutoMemPaths(
            () -> "C:/proj", () -> "C:/mem", () -> null, () -> null);
        MemoryFileDetection det = new MemoryFileDetection(p, () -> "C:/cfg",
            () -> true, () -> false, () -> false);
        String memoryFile = p.getAutoMemPath() + "MEMORY.md";

        assertThat(det.isMemorySearch(memoryFile, null, null))
            .as("path 命中 auto-memory 文件（isAutoManagedMemoryFile 分支）→ true")
            .isTrue();
        assertThat(det.isMemorySearch(p.getAutoMemPath(), null, null))
            .as("path 命中记忆目录（isMemoryDirectory 分支）→ true")
            .isTrue();
    }

    @Test
    @DisplayName("isMemorySearch：glob 会话记忆模式 → true（collapseReadSearch.ts:94-96）")
    void isMemorySearch_memoryGlob_isTrue() {
        // WHY: collapseReadSearch.ts:94-96 `if (input.glob && isAutoManagedMemoryPattern(input.glob))
        //      return true` —— Glob 工具带 session-memory 模式时计入 memorySearchCount
        //      （isAutoManagedMemoryPattern 获得生产消费）。
        MemoryFileDetection det = personalOnly();

        assertThat(det.isMemorySearch(null, "**/session-memory/**/*.md", null))
            .as("glob 含 session-memory → isAutoManagedMemoryPattern true")
            .isTrue();
    }

    @Test
    @DisplayName("isMemorySearch：shell 命令目标记忆文件 → true（collapseReadSearch.ts:99-101）")
    void isMemorySearch_memoryCommand_isTrue() {
        // WHY: collapseReadSearch.ts:99-101 `if (input.command && isShellCommandTargetingMemory(input.command))
        //      return true` —— bash grep/rg 命令目标记忆路径时计入 memorySearchCount
        //      （isShellCommandTargetingMemory 获得生产消费）。
        AutoMemPaths p = new AutoMemPaths(
            () -> "C:/proj", () -> "C:/mem", () -> null, () -> null);
        MemoryFileDetection det = new MemoryFileDetection(p, () -> "C:/cfg",
            () -> true, () -> false, () -> false);
        String memoryFile = p.getAutoMemPath() + "MEMORY.md";

        assertThat(det.isMemorySearch(null, null, "grep -r foo " + memoryFile))
            .as("command 含 auto-memory 目录绝对路径 → isShellCommandTargetingMemory true")
            .isTrue();
    }

    @Test
    @DisplayName("isMemorySearch：非记忆 path/glob/command → false；全 null → false")
    void isMemorySearch_nonMemory_isFalse() {
        // WHY: CC :83-87 `if (!input) return false` + 三分支均不命中 → false（null 安全，不抛 NPE）。
        MemoryFileDetection det = personalOnly();

        assertThat(det.isMemorySearch("C:/proj/other/file.txt", "**/*.java", "echo hi"))
            .as("非记忆 path/glob/command → false")
            .isFalse();
        assertThat(det.isMemorySearch(null, null, null))
            .as("全 null → false")
            .isFalse();
    }

    @Test
    @DisplayName("isMemoryWriteOrEdit：Write/Edit 记忆文件 true；非记忆文件/Read 工具 false（collapseReadSearch.ts:109-115）")
    void isMemoryWriteOrEdit_memoryFile_isTrue() {
        // WHY: collapseReadSearch.ts:110-114 `if (toolName !== Write && toolName !== Edit) return false;
        //      return filePath !== undefined && isAutoManagedMemoryFile(filePath)` —— Write/Edit 记忆文件
        //      计入 memoryWriteCount（折叠 badge 显示 "Wrote N memories"）；Read 工具不在集合 → 不折叠
        //      （isAutoManagedMemoryFile 获得生产消费）。
        AutoMemPaths p = new AutoMemPaths(
            () -> "C:/proj", () -> "C:/mem", () -> null, () -> null);
        MemoryFileDetection det = new MemoryFileDetection(p, () -> "C:/cfg",
            () -> true, () -> false, () -> false);
        String memoryFile = p.getAutoMemPath() + "MEMORY.md";

        assertThat(det.isMemoryWriteOrEdit("Write", memoryFile))
            .as("Write 记忆文件 → true")
            .isTrue();
        assertThat(det.isMemoryWriteOrEdit("Edit", memoryFile))
            .as("Edit 记忆文件 → true")
            .isTrue();
        assertThat(det.isMemoryWriteOrEdit("Write", "C:/proj/other/file.txt"))
            .as("Write 非记忆文件 → false")
            .isFalse();
        assertThat(det.isMemoryWriteOrEdit("Read", memoryFile))
            .as("Read 工具不在 Write/Edit 集合 → false（CC :110-112）")
            .isFalse();
        assertThat(det.isMemoryWriteOrEdit("Write", null))
            .as("filePath null → false")
            .isFalse();
    }
}
