package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.agent.AgentMemoryDirectory;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [批 4b-1] auto-memory 层「会话项目根显式传参」正向锚守卫。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 验证意图）</b>：批 4b-1 删除了 {@code AutoMemPaths.CURRENT_PROJECT_ROOT}
 * （ThreadLocal 载体）—— 它原先承载「本会话的项目根」。载体删除后，per-project auto-memory 目录
 * （记忆注入的核心产物）只能靠<b>调用方显式传入</b>的 {@code sessionProjectRoot} 得到
 * （用户铁律：会话态一律显式传参，回放不算合规）。
 *
 * <p>本类守护「显式入参真的驱动了解析」这条承重链，且每个断言都带正向锚（⛔ 不用源码字面断言，
 * ⛔ 不用「删了实现仍恒绿」的否定断言）：
 * <ol>
 *   <li>同一实例、两个不同入参 ⇒ 解析出各自不同的目录（判别力来自「两个都命中各自的」，
 *       而不是「不等于某个值」）；</li>
 *   <li>入参缺失 ⇒ 返回 null（<b>不伪造</b> config home 假目录 = 本批红线）；</li>
 *   <li>「Searching past context」段的 transcript 搜索根取自显式入参（用第二个根判别）；</li>
 *   <li>真实线程中调用同样只认入参（无任何线程局部依赖）；</li>
 *   <li>agent-memory PROJECT/LOCAL scope：有根 ⇒ 根下目录；无根 ⇒ (a) 抛 / (b) 不放行（fail-closed）。</li>
 * </ol>
 */
@DisplayName("[批 4b-1] auto-memory 会话项目根显式传参（正向锚：入参驱动解析 / 缺失即不伪造）")
class AutoMemoryExplicitRootPlumbingTest {

    @TempDir
    Path configHome;
    @TempDir
    Path rootA;
    @TempDir
    Path rootB;

    @BeforeEach
    void setUp() {
        // 隔离 nexusai 自有根（memoryBase = <configHome>），防污染真实 ~/.nexusai
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        BundledSkillEnabledGates.bridgeSettingsMapper(null);   // 清 DB settings 桥接（防跨用例泄漏）
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
        NexusaiPaths.setAppNameOverride(null);
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
    }

    /** 生产同形的 MemoryPromptBuilder（auto 开、其余 flag 关、无 override）。 */
    private static MemoryPromptBuilder builder(boolean coralFern) {
        return new MemoryPromptBuilder(
            AutoMemPaths.defaultInstance(),
            () -> true,          // autoMemoryEnabled
            () -> false,         // kairosActive
            () -> false,         // teamMemoryEnabled
            () -> coralFern,     // coralFernFlag（→ Searching past context 段）
            () -> false,         // mothCopseFlag
            () -> false,         // herringClockFlag
            () -> null,          // coworkExtraGuidelinesEnv
            p -> "");            // entrypointReader（无 MEMORY.md 内容）
    }

    @Test
    @DisplayName("loadMemoryPrompt(root)：两个不同入参 ⇒ 各命中各自目录（显式入参真的驱动解析）")
    void loadMemoryPrompt_drivenByExplicitRoot() {
        MemoryPromptBuilder b = builder(false);

        String promptA = b.loadMemoryPrompt(rootA.toString());
        String promptB = b.loadMemoryPrompt(rootB.toString());

        assertThat(promptA).as("显式传 rootA ⇒ 产出非空记忆指令 prompt").isNotNull().isNotEmpty();
        assertThat(promptB).as("显式传 rootB ⇒ 产出非空记忆指令 prompt").isNotNull().isNotEmpty();
        assertThat(promptA)
            .as("rootA 的 prompt 必须指向 rootA 的 per-project 目录（sanitize slug 判别）")
            .contains(AutoMemPaths.sanitizePath(rootA.toString()));
        assertThat(promptB)
            .as("rootB 的 prompt 必须指向 rootB 的 per-project 目录")
            .contains(AutoMemPaths.sanitizePath(rootB.toString()));
        assertThat(promptA)
            .as("⛔ 不得串到另一个会话的项目目录（入参隔离）")
            .doesNotContain(AutoMemPaths.sanitizePath(rootB.toString()));
    }

    @Test
    @DisplayName("loadMemoryPrompt(null)：无显式根 ⇒ null（⛔ 绝不回落 config home 伪造项目根）")
    void loadMemoryPrompt_nullRoot_returnsNullNoFabrication() {
        assertThat(builder(false).loadMemoryPrompt(null))
            .as("无会话项目根（且 env 未配置）⇒ 无有效项目，返回 null（A′）；⛔ 不得拼 config home 假目录")
            .isNull();
        // 且不得把 config home 当项目：config home 自身作根同样被 A′ 拒绝
        assertThat(builder(false).loadMemoryPrompt(configHome.toString()))
            .as("config home 自身不是项目身份 ⇒ null")
            .isNull();
    }

    @Test
    @DisplayName("Searching past context 段：transcript 搜索根取自显式入参（用第二个根判别）")
    void searchingPastContext_transcriptRootFromExplicitRoot() {
        // memoryDir 用 rootA 派生（保证 prompt 里不会意外出现 rootB 的 slug）
        String memoryDir = Paths.get(configHome.toString(), "projects",
            AutoMemPaths.sanitizePath(rootA.toString()), "memory").toString();

        String prompt = builder(true).buildMemoryPrompt(
            MemoryPromptBuilder.AUTO_MEM_DISPLAY_NAME, memoryDir, null, rootB.toString());

        assertThat(prompt)
            .as("coralFern 开 → 注入 Searching past context 段")
            .contains("Searching past context");
        assertThat(prompt)
            .as("transcript 搜索根必须来自显式入参 rootB（memoryDir 用的是 rootA ⇒ 唯一来源即入参）")
            .contains(AutoMemPaths.sanitizePath(rootB.toString()));
        assertThat(prompt)
            .as("⛔ 不得回落其它根/进程目录")
            .doesNotContain(AutoMemPaths.sanitizePath(rootA.toString()) + "/\" glob=\"*.jsonl");
    }

    @Test
    @DisplayName("真实线程：worker 线程用显式入参解析出与主线程相同的目录（无任何线程局部依赖）")
    void explicitRoot_crossesThreadBoundary() throws Exception {
        MemoryPromptBuilder b = builder(false);
        String onMain = b.loadMemoryPrompt(rootA.toString());

        AtomicReference<String> onWorker = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                onWorker.set(b.loadMemoryPrompt(rootA.toString()));
            } finally {
                done.countDown();
            }
        }, "automem-plumbing-probe");
        worker.start();
        assertThat(done.await(5, TimeUnit.SECONDS)).as("worker 必须在 5s 内完成").isTrue();

        assertThat(onWorker.get())
            .as("跨线程只认显式入参 ⇒ worker 产出与主线程一致（CC 单进程语义；批 4b-1 前靠回放）")
            .isEqualTo(onMain);
    }

    @Test
    @DisplayName("agent-memory PROJECT scope：显式根 ⇒ 根下目录；无根 ⇒ (a) fail-loud（⛔ 不伪造假目录）")
    void agentMemoryDirectory_projectScope_requiresExplicitRoot() {
        AgentMemoryDirectory dir = AgentMemoryDirectory.productionDefault();

        Path resolved = dir.getAgentMemoryDir("my-agent", AgentMemoryDirectory.AgentMemoryScope.PROJECT,
            rootA.toString());
        assertThat(resolved)
            .as("显式根 ⇒ PROJECT scope 目录落 <rootA>/.nexusai/agent-memory/<type>")
            .isEqualTo(Paths.get(rootA.toString(), NexusaiPaths.getProjectDirName(),
                "agent-memory", "my-agent"));

        assertThatThrownBy(() -> dir.getAgentMemoryDir("my-agent",
            AgentMemoryDirectory.AgentMemoryScope.PROJECT, null))
            .as("(a) 本该有却没有 ⇒ 抛 IllegalStateException（不回落 config home 拼假目录）")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("不回落 config home");
    }

    @Test
    @DisplayName("agent-memory 路径判定：显式根 ⇒ 命中该项目目录；无根 ⇒ 不放行（(b) fail-closed，不扩张放行面）")
    void isAgentMemoryPath_requiresExplicitRoot() {
        AgentMemoryDirectory dir = AgentMemoryDirectory.productionDefault();
        String projectMemFile = Paths.get(rootA.toString(), NexusaiPaths.getProjectDirName(),
            "agent-memory", "my-agent", "MEMORY.md").toString();

        assertThat(dir.isAgentMemoryPath(projectMemFile, rootA.toString()))
            .as("显式根 ⇒ 该项目 agent-memory 路径命中（carve-out 生效）")
            .isTrue();
        assertThat(dir.isAgentMemoryPath(projectMemFile, null))
            .as("无显式根 ⇒ PROJECT/LOCAL 段判不了 ⇒ 不放行（fail-closed，⛔ 不得用 config home 拼假基址）")
            .isFalse();
        // 对照：USER scope 基址与 cwd 无关（memoryBase 存储基座），无根也应命中 —— 证明上面 false 不是「永远 false」
        String userMemFile = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "agent-memory", "my-agent",
            "MEMORY.md").toString();
        assertThat(dir.isAgentMemoryPath(userMemFile, null))
            .as("USER scope 基址 = memoryBase（存储基座，非项目身份）⇒ 与 cwd 无关，恒可判定")
            .isTrue();
    }

    @Test
    @DisplayName("ClaudemdEngine：getMemoryFiles(.., sessionProjectRoot) 注入该根的 AutoMem 入口；无根 ⇒ 不注入")
    void claudemdEngine_autoMemEntrypointFromExplicitRoot() throws Exception {
        // 造该根的 AutoMem 入口文件（<memoryBase>/projects/<slug>/memory/MEMORY.md）
        Path autoMemDir = Paths.get(NexusaiPaths.getAppConfigHomeDir(), "projects",
            AutoMemPaths.sanitizePath(rootA.toString()), "memory");
        Files.createDirectories(autoMemDir);
        Files.writeString(autoMemDir.resolve("MEMORY.md"), "# 会话记忆\n仅本会话项目可见\n");

        com.nexusai.application.agent.context.ClaudemdEngine engine =
            new com.nexusai.application.agent.context.ClaudemdEngine(AutoMemPaths.defaultInstance(), null);

        List<com.nexusai.application.agent.context.MemoryFileInfo> withRoot =
            engine.getMemoryFiles(false, "sess-plumbing", rootA.toString());
        List<String> paths = withRoot.stream()
            .map(com.nexusai.application.agent.context.MemoryFileInfo::path).toList();

        assertThat(paths)
            .as("显式传会话项目根 ⇒ AutoMem 入口（该根的 MEMORY.md）进入记忆文件列表")
            .anyMatch(p -> p.replace('\\', '/').endsWith(AutoMemPaths.sanitizePath(rootA.toString())
                + "/memory/MEMORY.md"));
    }
}
