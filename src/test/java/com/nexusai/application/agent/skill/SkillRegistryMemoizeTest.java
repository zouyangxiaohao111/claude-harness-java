package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.domain.mcp.McpServerService;
import com.nexusai.infra.util.GitIgnoreHelper;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-1 命令聚合 memoize 缓存 + 缓存清理测试 · 对齐 CC commands.ts loadAllCommands:449 /
 * getSkillToolCommands:563 双 memoize 层 + clearCommandMemoizationCaches:523-531。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>磁盘/MCP 变更不应每次调用即时可见</b>——CC 用 memoize-by-cwd 把昂贵的 FS 重扫摊薄到每个
 *       cwd 一次，直到显式 clear cache。Java 无 cwd 入参，等价物 = by-skillsRoot 缓存；唯一失效
 *       入口是 {@link SkillRegistry#refresh()}（对齐 CC 靠 chokidar / MCP list_changed 显式 clear）。
 *       若缓存被删除，追加写 skill-b 后立即重扫 → 本测试 fail。</li>
 *   <li><b>getModelInvocableCommands 是独立 memoize 层</b>——CC getSkillToolCommands 在 getCommands
 *       之上再包一层 memoize；Java 侧过滤结果独立缓存，同一实例 + refresh() 前新增可调用技能不可见。</li>
 *   <li><b>单源失败不中断整体</b>——CC getSkills 每源独立 .catch → 该源返回空（commands.ts:360-373）；
 *       Java 每源 try-catch，抛异常的 MCP 源不得拖垮 bundled/FS 源。</li>
 * </ol>
 */
class SkillRegistryMemoizeTest {

    /** 写一个最小 SKILL.md（无 frontmatter，name 取目录名）· 对齐 SkillsLoader.loadFromSkillMd */
    private static void writeSkill(Path root, String dir, String name) throws Exception {
        writeSkill(root, dir, name, null);
    }

    private static void writeSkill(Path root, String dir, String name, String extraFrontmatter) throws Exception {
        Path skillDir = root.resolve(dir);
        Files.createDirectories(skillDir);
        StringBuilder fm = new StringBuilder("---\nname: ").append(name).append('\n');
        if (extraFrontmatter != null) {
            fm.append(extraFrontmatter).append('\n');
        }
        fm.append("---\n# ").append(name).append("\n");
        Files.writeString(skillDir.resolve("SKILL.md"), fm.toString());
    }

    @Test
    @DisplayName("getAllCommands raw memoize + enabled 过滤新鲜求值（P2-6 方案 A · CC commands.ts:478 注释）")
    void getAllCommands_stableContent_freshFilter(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a");
        BundledSkills.clear(); // 隔离跨测试泄漏的 bundled 注册集（P2-6 后 bundled 命令经 enabled 过滤入 getAllCommands）

        // 注入 isEnabled supplier 可翻转的 bundled 命令：第 1 次求值 true、后续 false。
        // 验证 P2-6 方案 A（CC commands.ts:478「isEnabled checks run fresh every call」）：
        // raw loadAllCommands 仍 memoize（FS 不重扫、skill-a 稳定在），但 isCommandEnabled 过滤
        // 在公开边界每调用新鲜求值 → supplier 翻转即时生效，不冻结进缓存。
        AtomicInteger gate = new AtomicInteger(0);
        Command toggling = new Command();
        toggling.setId("bundled-toggle-t");
        toggling.setName("toggle-t");
        toggling.setSource(CommandSource.BUNDLED);
        toggling.setEnabled(Boolean.TRUE);
        toggling.setIsEnabled(() -> gate.incrementAndGet() == 1);
        BundledSkills.register(toggling);

        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        // 第 1 次：supplier 第 1 次求值 true → 含 toggle-t；skill-a 恒在（raw memoize）
        assertThat(registry.getAllCommands()).extracting(Command::getName)
            .contains("skill-a").contains("toggle-t");
        // 第 2 次：supplier 第 2 次求值 false → toggle-t 被过滤（fresh，不冻结进缓存）
        assertThat(registry.getAllCommands()).extracting(Command::getName)
            .contains("skill-a").doesNotContain("toggle-t");
        // 第 3 次：同第 2 次（supplier 继续每调用重求值）
        assertThat(registry.getAllCommands()).extracting(Command::getName)
            .contains("skill-a").doesNotContain("toggle-t");
        // supplier 求值次数 = getAllCommands 调用次数（3），证明过滤新鲜而非注册期冻结
        assertThat(gate.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("磁盘变更 refresh() 前不可见、refresh() 后可见（memoize 唯一失效入口）")
    void diskChange_invisibleUntilRefresh(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        assertThat(registry.getAllCommands()).extracting(Command::getName).contains("skill-a");

        // 追加写 skill-b：未 refresh 前不可见（RED 于现状——现状每次调用重扫返回 2）
        writeSkill(tempDir, "skill-b", "skill-b");
        assertThat(registry.getAllCommands()).extracting(Command::getName).doesNotContain("skill-b");

        // refresh() 是唯一失效入口 → 之后可见（GREEN）
        registry.refresh();
        assertThat(registry.getAllCommands()).extracting(Command::getName).contains("skill-b");
    }

    @Test
    @DisplayName("P3-5 refresh() 触发 clearSkillIndexCache 挂钩（CC commands.ts:531 clearCommandMemoizationCaches 内）")
    void refresh_triggersSkillIndexClearerHook(@TempDir Path tempDir) throws Exception {
        // WHY: CC clearCommandMemoizationCaches 末尾调 clearSkillIndexCache?.()（commands.ts:531，
        //   注释「getSkillIndex 是 built ON TOP of getSkillToolCommands 的独立 memoize 层，
        //   清内层缓存对它 no-op，必须显式清」）。Java refresh() 清三层命令缓存后须触发
        //   索引清除挂钩，否则已建 skill-search 索引引用陈旧命令快照。
        writeSkill(tempDir, "skill-a", "skill-a");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        AtomicInteger clearCount = new AtomicInteger();
        registry.setSkillIndexClearer(clearCount::incrementAndGet);

        registry.refresh();

        assertThat(clearCount.get())
            .as("P3-5 refresh() 必须触发 clearSkillIndexCache 挂钩（CC commands.ts:531）")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("P3-5 默认未注入 skillIndexClearer → refresh() no-op 不抛")
    void refresh_withoutSkillIndexClearer_noOp(@TempDir Path tempDir) throws Exception {
        // WHY: 默认 skillIndexClearer 是 no-op（concern #30 子系统范围外）。未接线时
        // refresh() 不得 NPE —— 若默认值改为 null 直接 run()，本测试 fail。
        writeSkill(tempDir, "skill-a", "skill-a");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        registry.refresh();

        // 走到这里即证明 no-op 不抛，且缓存清理语义不受影响
        assertThat(registry.getAllCommands()).extracting(Command::getName).contains("skill-a");
    }

    @Test
    @DisplayName("getModelInvocableCommands 独立 memoize + refresh() 后新增可调用技能出现")
    void modelInvocable_memoized_and_refresh(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a");                                        // USER 源默认可模型调用
        writeSkill(tempDir, "skill-b", "skill-b", "disable-model-invocation: true");      // 模型不可调用
        SkillRegistry registry = new SkillRegistry(tempDir.toString());

        List<Command> invocable = registry.getModelInvocableCommands();
        assertThat(invocable).extracting(Command::getName).contains("skill-a");
        assertThat(invocable).extracting(Command::getName).doesNotContain("skill-b");
        // 独立 memoize：同一过滤结果实例
        assertThat(registry.getModelInvocableCommands()).isSameAs(invocable);

        // 新增可调用 skill-c：refresh() 前不可见、后可见（对齐 CC getSkillToolCommands memoize）
        writeSkill(tempDir, "skill-c", "skill-c");
        assertThat(registry.getModelInvocableCommands()).extracting(Command::getName).doesNotContain("skill-c");
        registry.refresh();
        assertThat(registry.getModelInvocableCommands()).extracting(Command::getName).contains("skill-c");
    }

    @Test
    @DisplayName("分离实证：getAllCommands 完全不触碰 MCP 源（P2-9 · CC commands.ts:541-546 live outside getCommands）")
    void getAllCommands_neverTouchesMcpSource(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a");
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        // 抛异常 MCP 服务：若 getAllCommands 仍并入 MCP（旧架构），调用必抛 RuntimeException；
        // P2-9 分离后 loadAllCommands 不再调 getMcpSkillCommands → 不抛 + FS 源照常返回 = 分离实证。
        registry.setMcpServerService(new ThrowingMcpServerService());

        // 不抛（MCP 源从未被触碰）
        List<Command> all = registry.getAllCommands();
        assertThat(all).extracting(Command::getName).contains("skill-a");
        assertThat(all).extracting(Command::getName).doesNotContain("mcp-exploded");
        // 抛异常服务下 findCommandIncludingMcp 会真实触碰 MCP（thread-in 语义）→ 抛 = 证明分离与 thread-in 并存
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.findCommandIncludingMcp("mcp-exploded"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("mcp exploded");
    }

    @Test
    @DisplayName("setSkillsRoot 变更后新根目录即时可见（键变更自失效 + hygiene 清空）")
    void setSkillsRoot_change_reflectsNewRoot(@TempDir Path tempDir) throws Exception {
        Path rootA = tempDir.resolve("skillsA");
        Path rootB = tempDir.resolve("skillsB");
        writeSkill(rootA, "skill-a", "skill-a");
        writeSkill(rootB, "skill-b", "skill-b");

        SkillRegistry registry = new SkillRegistry(rootA.toString());
        assertThat(registry.getAllCommands()).extracting(Command::getName).contains("skill-a");

        registry.setSkillsRoot(rootB.toString());
        assertThat(registry.getAllCommands()).extracting(Command::getName).contains("skill-b");
    }

    @Test
    @DisplayName("IMP-E: 缓存键并入 projectRoot —— 会话 A(Pa)/B(Pb) 各自缓存槽（CC memoize-by-cwd commands.ts:449 · M-09）")
    void cacheKey_incorporatesProjectRoot_sessionsIsolated(@TempDir Path tempDir) throws Exception {
        // 两个会话目录，各自 .claude/skills 下放独有技能（CC project 源 getProjectDirsUpToHome('skills', cwd)）
        Path pa = tempDir.resolve("proj-a");
        Path pb = tempDir.resolve("proj-b");
        writeSkill(pa, ".claude/skills/pa-only", "pa-only");
        writeSkill(pb, ".claude/skills/pb-only", "pb-only");
        BundledSkills.clear(); // 隔离跨测试泄漏的 bundled 注册集

        // 生产接线语义：cwdSupplier = AutoMemPaths::currentSessionProjectRoot（ToolRegistrationConfig:393）
        SkillRegistry registry = new SkillRegistry(tempDir.resolve("shared-root").toString());
        registry.setCwdSupplier(AutoMemPaths::currentSessionProjectRoot);

        // 会话 A：当前线程注入 Pa → 键含 Pa → 加载 Pa/.claude/skills
        String prev = AutoMemPaths.captureCurrentProjectRoot();
        try {
            AutoMemPaths.setCurrentProjectRoot(pa.toString());
            assertThat(registry.getAllCommands()).extracting(Command::getName).contains("pa-only");

            // 会话 B：同一 registry 实例、同 skillsRoot，仅 projectRoot 切换 → 键含 Pb → 新缓存槽。
            // RED 于现状（键 = skillsRoot）：B 阶段命中 A 的缓存槽 → 仍含 pa-only → doesNotContain 失败。
            AutoMemPaths.setCurrentProjectRoot(pb.toString());
            assertThat(registry.getAllCommands()).extracting(Command::getName)
                .contains("pb-only").doesNotContain("pa-only");

            // 切回会话 A：A 槽位仍完好（不被 B 的加载污染）
            AutoMemPaths.setCurrentProjectRoot(pa.toString());
            assertThat(registry.getAllCommands()).extracting(Command::getName).contains("pa-only");
        } finally {
            AutoMemPaths.restoreCurrentProjectRoot(prev); // capture/restore 成对，restore 外层原值
        }
    }

    @Test
    @DisplayName("IMP-E: 首触发线程为工具线程（IMP-C 传播已注入会话值）→ 键仍为会话值，不串会话")
    void cacheKey_firstTriggerOnWorkerThread_sessionValueWins(@TempDir Path tempDir) throws Exception {
        Path pa = tempDir.resolve("proj-a");
        Path pb = tempDir.resolve("proj-b");
        writeSkill(pa, ".claude/skills/pa-only", "pa-only");
        writeSkill(pb, ".claude/skills/pb-only", "pb-only");
        BundledSkills.clear();

        SkillRegistry registry = new SkillRegistry(tempDir.resolve("shared-root").toString());
        registry.setCwdSupplier(AutoMemPaths::currentSessionProjectRoot);

        // 两个工具线程（fixed-8 池语义，非会话线程）：IMP-C 捕获-回放传播在任务体开头注入会话
        // projectRoot（StreamingToolExecutor.executeAsync 模式），finally reset 防线程复用泄漏。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<String>> fA = pool.submit(() -> {
                AutoMemPaths.setCurrentProjectRoot(pa.toString());
                try {
                    return registry.getAllCommands().stream().map(Command::getName).toList();
                } finally {
                    AutoMemPaths.resetCurrentProjectRoot();
                }
            });
            Future<List<String>> fB = pool.submit(() -> {
                AutoMemPaths.setCurrentProjectRoot(pb.toString());
                try {
                    return registry.getAllCommands().stream().map(Command::getName).toList();
                } finally {
                    AutoMemPaths.resetCurrentProjectRoot();
                }
            });
            // 各自首触发各自槽位：A 结果 = Pa 的 skills、B 结果 = Pb 的 skills，互不污染
            assertThat(fA.get()).contains("pa-only").doesNotContain("pb-only");
            assertThat(fB.get()).contains("pb-only").doesNotContain("pa-only");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("M27: refresh() 经 DynamicSkillsManager 双清条件状态（CC clearSkillCaches loadSkillsDir.ts:809-810），dynamicSkills 保留")
    void refresh_clearsConditionalStateViaDynamicSkillsManager(@TempDir Path tempDir) throws Exception {
        writeSkill(tempDir, "skill-a", "skill-a");
        // 管理器先独立激活（未接线 onChange → 激活态不被级联 refresh 干扰），再注入注册中心
        DynamicSkillsManager m = new DynamicSkillsManager();
        m.setGitExec(SkillRegistryMemoizeTest::notIgnored);
        Command cond = new Command();
        cond.setName("cond");
        cond.setPaths(List.of("src/**"));
        m.registerConditional(cond);
        Path file = tempDir.resolve("src/a.ts");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "a");
        assertThat(m.activateConditionalSkillsForPaths(List.of(file.toString()), tempDir))
            .containsExactly("cond");
        assertThat(m.isActivatedConditionalSkill("cond")).isTrue();
        assertThat(m.getDynamicSkills()).extracting(Command::getName).contains("cond");
        // 未激活条件技能（待激活态）
        Command cond2 = new Command();
        cond2.setName("cond-2");
        cond2.setPaths(List.of("docs/**"));
        m.registerConditional(cond2);
        assertThat(m.getConditionalSkillCount()).isEqualTo(1);

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setDynamicSkillsManager(m);
        registry.refresh();

        // M27 双清（CC clearSkillCaches :809-810）：conditionalSkills + activatedConditionalSkillNames 均清
        assertThat(m.getConditionalSkillCount()).isZero();
        assertThat(m.isActivatedConditionalSkill("cond")).isFalse();
        // dynamicSkills 保留（CC clearSkillCaches 不清动态技能池；4 态全清是 clearDynamicSkills :1070-1075）
        assertThat(m.getDynamicSkills()).extracting(Command::getName).contains("cond");
    }

    @Test
    @DisplayName("P1-3: workflowCommandProvider 注入 → workflow 命令入 getAllCommands（CC commands.ts:457/:464 第 4 源）；null → 空")
    void workflowCommandProvider_mergesSource(@TempDir Path tempDir) throws Exception {
        // WHY: CC loadAllCommands — workflowCommands 为第 4 源（commands.ts:457 getWorkflowCommands ?
        //   getWorkflowCommands(cwd) : Promise.resolve([])，:464 ...workflowCommands spread）。Java 旧实现
        //   无该合并路径（✗-1/GAP-PC-4），feature WORKFLOW_SCRIPTS 关时 CC 产出 []（provider null 等价）。
        // 未注入 provider 的独立注册中心 → 无 workflow 命令（对齐 CC feature 关 Promise.resolve([])）
        assertThat(new SkillRegistry(tempDir.toString()).getAllCommands().stream().map(Command::getName))
            .doesNotContain("workflow-cmd");

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        Command wf = new Command();
        wf.setName("workflow-cmd");
        wf.setDescription("a workflow command");
        // provider 须在首次 getAllCommands 前注入（memoize-by-skillsRoot：raw 缓存含 provider 结果；
        //   注入后需 refresh() 才可见，对齐 CC 磁盘变更 refresh() 前不可见）
        registry.setWorkflowCommandProvider(() -> List.of(wf));

        assertThat(registry.getAllCommands().stream().map(Command::getName))
            .as("P1-3: workflowCommandProvider 注入 → workflow 命令入 getAllCommands（CC commands.ts:464 ...workflowCommands）")
            .contains("workflow-cmd");
    }

    @Test
    @DisplayName("P2-13: refreshCommandsOnly() 窄变体不动条件技能激活态（CC clearCommandMemoizationCaches commands.ts:523-532，△-3 修复）")
    void refreshCommandsOnly_narrow_keepsConditionalState(@TempDir Path tempDir) throws Exception {
        // WHY: CC skillChangeDetector.ts:94-97 动态技能加载用窄变体 clearCommandMemoizationCaches（不动
        //   clearSkillCaches）——clearCommandsCache 会调 clearSkillCaches 清掉刚加载的动态技能/条件激活态。
        //   Java 旧实现 setDynamicSkillsManager 的 onChange→refresh() 全量清会重置条件技能激活状态（△-3
        //   可观测）；窄变体 refreshCommandsOnly() 只清命令三层缓存 + skillIndexClearer，条件状态保留。
        DynamicSkillsManager m = new DynamicSkillsManager();
        m.setGitExec(SkillRegistryMemoizeTest::notIgnored);
        Command cond = new Command();
        cond.setName("cond");
        cond.setPaths(List.of("src/**"));
        m.registerConditional(cond);
        Path file = tempDir.resolve("src/a.ts");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "a");
        assertThat(m.activateConditionalSkillsForPaths(List.of(file.toString()), tempDir))
            .containsExactly("cond");
        assertThat(m.isActivatedConditionalSkill("cond")).isTrue();

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.setDynamicSkillsManager(m);
        // 窄变体：只清命令缓存，条件激活态保留（CC clearCommandMemoizationCaches 不动 conditionalSkills /
        //   activatedConditionalSkillNames，:523-532 对比 clearSkillCaches :809-810）
        registry.refreshCommandsOnly();

        assertThat(m.isActivatedConditionalSkill("cond"))
            .as("P2-13: 窄变体不动 activatedConditionalSkillNames（CC clearCommandMemoizationCaches :523-532，△-3）")
            .isTrue();
    }

    /** 默认 gitExec 桩：exit 1 = 不忽略（fail-open 等价 git 无命中）· 与 DynamicSkillsManagerTest 同款。 */
    private static GitIgnoreHelper.ExecResult notIgnored(String[] args, String cwd) {
        return new GitIgnoreHelper.ExecResult(1, "", "");
    }

    /** 抛异常的 MCP 服务（隔离源验证）· McpServerService 为具体类，子类覆写即可。 */
    private static final class ThrowingMcpServerService extends McpServerService {
        @Override
        public List<Command> getMcpSkillCommands() {
            throw new RuntimeException("mcp exploded");
        }

        @Override
        public List<Command> getMcpSkillCommandsForSearch() {
            throw new RuntimeException("mcp exploded");
        }
    }

    @Test
    @DisplayName("P2-1 缓存按 cwd 槽化：不同 cwd 各取各的项目目录技能（对齐 CC memoize-by-cwd loadSkillsDir.ts:638-639）")
    void cacheKeyedByCwd_sessionsIsolated(@TempDir Path tempDir) throws Exception {
        // WHY: EV-WF1-LD-029 —— 旧 Java 缓存键=固定 skillsRoot，跨 per-session cwd 陈旧（会话 B 命中
        //   会话 A 的项目技能）。CC getSkillDirCommands = memoize(async (cwd) => ...) 按 cwd 槽化。
        //   P2-1 修复：cwdSupplier 注入时缓存键 = 解析后 cwd。
        // 隔离真实 config-home/managed（防用户真实技能污染断言）
        ClaudePaths.setConfigDirOverride(tempDir.resolve("cfg").toString());
        ClaudePaths.setManagedFilePathOverride(tempDir.resolve("managed").toString());
        // G5：registry user 源 = NexusaiPaths 自有根优先 → 唯一 appName 隔离（防读真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        try {
            // 两个项目各自 .claude/skills + config-home 空（隔离 user 源）
            Path projA = tempDir.resolve("projA");
            Path projB = tempDir.resolve("projB");
            Files.createDirectories(projA.resolve(".git"));
            Files.createDirectories(projB.resolve(".git"));
            writeSkill(projA.resolve(".claude").resolve("skills"), "skill-a", "skill-a");
            writeSkill(projB.resolve(".claude").resolve("skills"), "skill-b", "skill-b");

            SkillRegistry registry = new SkillRegistry(tempDir.toString());
            // 双 cwd 交替注入（模拟两个 per-session projectRoot）
            registry.setCwdSupplier(() -> currentCwd.get());
            currentCwd.set(projA.toString());
            assertThat(registry.getAllCommands()).extracting(Command::getName)
                .contains("skill-a").doesNotContain("skill-b");
            currentCwd.set(projB.toString());
            // 会话 B 不得命中 A 的缓存槽（旧实现按 skillsRoot 缓存 → 恒返回 skill-a）
            assertThat(registry.getAllCommands()).extracting(Command::getName)
                .contains("skill-b").doesNotContain("skill-a");
            // 切回 A：槽位隔离，仍只含 skill-a
            currentCwd.set(projA.toString());
            assertThat(registry.getAllCommands()).extracting(Command::getName)
                .contains("skill-a").doesNotContain("skill-b");
        } finally {
            ClaudePaths.setConfigDirOverride(null);
            ClaudePaths.setManagedFilePathOverride(null);
            NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
            MarkdownConfigLoader.clearCache();
        }
    }

    /** 当前 cwd（P2-1 测试 seam · 模拟 per-session ThreadLocal projectRoot）。 */
    private static final ThreadLocal<String> currentCwd = new ThreadLocal<>();
}
