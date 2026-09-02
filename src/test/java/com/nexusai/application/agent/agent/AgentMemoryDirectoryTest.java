package com.nexusai.application.agent.agent;

import com.nexusai.application.agent.skill.NexusaiPaths;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentMemoryDirectory 路径解析/安全校验/加载 prompt ·
 * 对齐 CC tools/AgentTool/agentMemory.ts:13-177.
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>:
 * <ol>
 *   <li><b>sanitizeAgentTypeForPath</b>（agentMemory.ts:20-22）——plugin-namespaced
 *       agent type 用冒号（Windows 非法目录字符），必须替换为横杠，否则 memory 落盘失败。</li>
 *   <li><b>isAgentMemoryPath 尾分隔符</b>（agentMemory.ts:74）——CC 在
 *       {@code join(memoryBase, 'agent-memory')} 后追加 {@code sep}。缺失尾分隔符会让
 *       {@code agent-memory-evil} 这种<em>前缀攻击</em>路径误判为 memory 目录，
 *       绕过权限层（INV-12）。</li>
 *   <li><b>loadAgentMemoryPrompt scopeNote</b>（agentMemory.ts:142-156）——user/project/
 *       local 三 scope 的约束文本不同，决定 agent 写入 memory 时是否「保持通用 / 团队共享 /
 *       仅本机」。若三 scope 提示相同，agent 会把用户私密记忆写进 VCS 共享目录。</li>
 *   <li><b>loadAgentMemoryPrompt 真实 prompt</b>（agentMemory.ts:169-176）——JSON 桩
 *       （DEL-M-31）改真实 {@code buildMemoryPrompt} 输出（含 {@code # Persistent Agent Memory}
 *       标题 + {@code ## MEMORY.md} 段），否则模型收不到行为指令。</li>
 * </ol>
 *
 * <p><b>[IMP-M-P2-2] 尾分隔符用例转 GREEN</b>: {@code agent-memory-evil 不匹配} 在 P2-2
 * 落地（AgentMemoryDirectory.java 各 scope 基址 {@code toString() + File.separator}）后
 * 由 RED 转 GREEN。
 */
@DisplayName("[IMP-M-C-2] AgentMemoryDirectory 路径解析 + INV-12 尾分隔符安全 + 加载 prompt")
class AgentMemoryDirectoryTest {

    // 固定测试环境: memoryBase=NexusaiPaths 自有根（决策 D1/D6）, cwd=/repo/project, remote mount 未设置
    private static final Path CWD = Paths.get("/repo/project");
    private static final String SEP = Paths.get("/a/b").toString().substring(Paths.get("/a").toString().length());

    @TempDir
    Path tempRoot;

    private Path memoryBase;

    @BeforeEach
    void setUp() {
        // USER scope 记忆基址走 NexusaiPaths 自有根 ~/.{appName}/（决策 D1：不再落 ~/.claude）·
        // 覆写 appName 指向临时根隔离（防写真实 ~/.nexusai），@AfterEach 复位默认 nexusai
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempRoot.getFileName());
        memoryBase = Paths.get(NexusaiPaths.getAppConfigHomeDir());
    }

    @AfterEach
    void tearDown() {
        NexusaiPaths.setAppNameOverride(null);   // 复位默认 appName（nexusai）
    }

    private AgentMemoryDirectory newDir() {
        return new AgentMemoryDirectory(
            CWD::toString,
            () -> memoryBase,
            () -> null,
            () -> CWD,
            s -> s,
            path -> { /* fire-and-forget mkdir: 无操作 */ },
            () -> null,
            () -> true,
            com.nexusai.application.agent.memory.MemoryPromptBuilder.productionDefault());
    }

    @Test
    @DisplayName("sanitizeAgentTypeForPath 把冒号替换为横杠（Windows 非法字符，agentMemory.ts:20-22）")
    void sanitizeColonToDash() {
        assertThat(AgentMemoryDirectory.sanitizeAgentTypeForPath("plugin:my-agent"))
            .isEqualTo("plugin-my-agent");
        assertThat(AgentMemoryDirectory.sanitizeAgentTypeForPath("plain"))
            .isEqualTo("plain");
    }

    @Test
    @DisplayName("user scope → nexusai 根/agent-memory/<type>；project scope → cwd/.nexusai/agent-memory/<type>")
    void scopesResolve() {
        AgentMemoryDirectory d = newDir();
        assertThat(d.getAgentMemoryDir("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER))
            .isEqualTo(memoryBase.resolve("agent-memory").resolve("my-agent"));
        assertThat(d.getAgentMemoryDir("my-agent", AgentMemoryDirectory.AgentMemoryScope.PROJECT))
            .isEqualTo(CWD.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory").resolve("my-agent"));
        assertThat(d.getAgentMemoryDir("my-agent", AgentMemoryDirectory.AgentMemoryScope.LOCAL))
            .isEqualTo(CWD.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory-local").resolve("my-agent"));
    }

    @Test
    @DisplayName("getAgentMemoryEntrypoint = memoryDir + MEMORY.md（agentMemory.ts:109-114，OPD-CM5-F-24）")
    void entrypointIsMemoryDirPlusMemo() {
        // WHY: CC 导出面 getAgentMemoryEntrypoint 返回子代理记忆入口文件路径
        // (join(getAgentMemoryDir, 'MEMORY.md'))，前端"导出会话记忆"需此入口（待前端对接.md §26）。
        // 若入口与 memoryDir 分离或丢 MEMORY.md 后缀，前端会导出到错误路径/读不到记忆。
        AgentMemoryDirectory d = newDir();
        assertThat(d.getAgentMemoryEntrypoint("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER))
            .isEqualTo(memoryBase.resolve("agent-memory").resolve("my-agent").resolve("MEMORY.md"));
        assertThat(d.getAgentMemoryEntrypoint("my-agent", AgentMemoryDirectory.AgentMemoryScope.PROJECT))
            .isEqualTo(CWD.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory").resolve("my-agent").resolve("MEMORY.md"));
        assertThat(d.getAgentMemoryEntrypoint("my-agent", AgentMemoryDirectory.AgentMemoryScope.LOCAL))
            .isEqualTo(CWD.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory-local").resolve("my-agent").resolve("MEMORY.md"));
        // sanitize 保持生效：冒号替换为横杠（agentMemory.ts:20-22 链）
        assertThat(d.getAgentMemoryEntrypoint("plugin:my-agent", AgentMemoryDirectory.AgentMemoryScope.USER))
            .isEqualTo(memoryBase.resolve("agent-memory").resolve("plugin-my-agent").resolve("MEMORY.md"));
    }

    @Test
    @DisplayName("productionDefault() 返回共享单例（OPD-CM5-F-21 统一 Bean 同实例，⊕-4 关闭）")
    void productionDefault_is_shared_singleton() {
        // WHY: ⊕-4（CM-F4）「@Bean 与生产注入实例分离」——ToolRegistrationConfig @Bean 与
        // SubagentExecutor/SubagentTool/loadAgentsDir 各自 productionDefault() 新建实例，装配不一致。
        // OPD-CM5-F-21 拍板统一：工厂返回同一实例，@Bean 与全部生产注入点共享（DC-V5-10 关闭）。
        // 若每次新建（回归到旧行为），Spring 注入的 @Bean 与直构调用方仍指向不同实例。
        assertThat(AgentMemoryDirectory.productionDefault())
            .isSameAs(AgentMemoryDirectory.productionDefault());
    }

    @Test
    @DisplayName("user scope 尾分隔符安全：真实 memory 子路径匹配")
    void isAgentMemoryPath_matchesRealUserMemory() {
        assertThat(newDir().isAgentMemoryPath(
            memoryBase.resolve("agent-memory").resolve("my-agent").resolve("MEMORY.md").toString()))
            .isTrue();
    }

    @Test
    @DisplayName("INV-12 RED：前缀攻击 agent-memory-evil 必须不匹配（CC 尾分隔符语义，agentMemory.ts:74）")
    void isAgentMemoryPath_evilPrefixNotMatch() {
        AgentMemoryDirectory d = newDir();
        String evilUser = memoryBase.resolve("agent-memory-evil").resolve("x").toString();
        String evilProject = CWD.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory-evil").resolve("x").toString();
        String evilLocal = CWD.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory-local-evil").resolve("x").toString();

        // CC: join(memoryBase,'agent-memory') + sep → 'agent-memory/...'；'agent-memory-evil' 以
        // 'agent-memory' 开头但非 'agent-memory/'，不得命中。当前 Java 无 sep 后缀 → 误判 true（RED）。
        assertThat(d.isAgentMemoryPath(evilUser)).as("agent-memory-evil user 前缀攻击")
            .isFalse();
        assertThat(d.isAgentMemoryPath(evilProject)).as("agent-memory-evil project 前缀攻击")
            .isFalse();
        assertThat(d.isAgentMemoryPath(evilLocal)).as("agent-memory-local-evil 前缀攻击")
            .isFalse();
    }

    @Test
    @DisplayName("project scope：cwd 下 .nexusai/agent-memory 命中（agentMemory.ts:79-83）")
    void isAgentMemoryPath_projectScopeMatches() {
        assertThat(newDir().isAgentMemoryPath(
            CWD.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory").resolve("my-agent").resolve("MEMORY.md").toString()))
            .isTrue();
    }

    @Test
    @DisplayName("非 memory 路径不命中（避免权限层误拦普通项目文件）")
    void isAgentMemoryPath_nonMemoryNotMatch() {
        assertThat(newDir().isAgentMemoryPath(
            CWD.resolve("src").resolve("main").resolve("App.java").toString()))
            .isFalse();
    }

    @Test
    @DisplayName("loadAgentMemoryPrompt 三 scope note 区分（agentMemory.ts:142-156）：user 通用 / project 团队 / local 本机")
    void loadPromptScopeNotes() {
        AgentMemoryDirectory d = newDir();
        String user = d.loadAgentMemoryPrompt("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER);
        String project = d.loadAgentMemoryPrompt("my-agent", AgentMemoryDirectory.AgentMemoryScope.PROJECT);
        String local = d.loadAgentMemoryPrompt("my-agent", AgentMemoryDirectory.AgentMemoryScope.LOCAL);

        assertThat(user).contains("user-scope")
            .contains("keep learnings general");
        assertThat(project).contains("project-scope")
            .contains("shared with your team");
        assertThat(local).contains("local-scope")
            .contains("not checked into version control");
    }

    @Test
    @DisplayName("ensureDirConsumer 以 fire-and-forget 方式被触发（agentMemory.ts:160-165 不可异步）")
    void ensureDirFired() {
        final String[] fired = {null};
        AgentMemoryDirectory d = new AgentMemoryDirectory(
            CWD::toString,
            () -> memoryBase,
            () -> null,
            () -> CWD,
            s -> s,
            path -> fired[0] = path,
            () -> null,
            () -> true,
            com.nexusai.application.agent.memory.MemoryPromptBuilder.productionDefault());
        d.loadAgentMemoryPrompt("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER);
        assertThat(fired[0])
            .isEqualTo(memoryBase.resolve("agent-memory").resolve("my-agent").toString());
    }

    @Test
    @DisplayName("loadAgentMemoryPrompt 输出真实 CC prompt 而非 JSON 桩（DEL-M-31，agentMemory.ts:169-176）")
    void loadAgentMemoryPrompt_is_real_prompt_not_json_stub() {
        // WHY: 旧实现返回 '{displayName="...",memoryDir="...",scopeNote="..."}' JSON 描述 ——
        // 模型收到的不是行为指令，agent memory 注入失效。对齐 CC buildMemoryPrompt 应输出
        // '# Persistent Agent Memory' 标题 + '## MEMORY.md' 段 + scopeNote 行为约束。
        AgentMemoryDirectory d = newDir();
        String prompt = d.loadAgentMemoryPrompt("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER);

        assertThat(prompt).doesNotContain("{displayName=");
        assertThat(prompt).startsWith("# Persistent Agent Memory");
        assertThat(prompt).contains("## MEMORY.md")
            .contains("Your MEMORY.md is currently empty")
            .contains("## How to save memories")
            .contains("user-scope")
            .contains("keep learnings general");
    }

    @Test
    @DisplayName("loadAgentMemoryPrompt 纯构建无内部门控（OPD-CM5-F-25）：autoMemoryEnabled=false 也输出真实 prompt 并 mkdir")
    void loadAgentMemoryPrompt_is_pure_builder_no_gate() {
        // WHY: [OPD-CM5-F-25] 门控移调用方（严格对齐 CC agentMemory.ts:138-177 无内部门控，
        // 门控在 loadAgentsDir.ts:481-488/726-732 调用方）。若本方法仍静默返回 ""，会把「门控
        // 关闭」伪装成「禁用」，调用方即使漏加 isAutoMemoryEnabled 门控也不暴露 —— 纯构建契约要求
        // supplier=false 时依然输出真实 CC prompt 并触发 mkdir（禁用场景由调用方跳过本调用，无 mkdir）。
        final String[] fired = {null};
        AgentMemoryDirectory d = new AgentMemoryDirectory(
            CWD::toString,
            () -> memoryBase,
            () -> null,
            () -> CWD,
            s -> s,
            path -> fired[0] = path,
            () -> null,
            () -> false,
            com.nexusai.application.agent.memory.MemoryPromptBuilder.productionDefault());
        String prompt = d.loadAgentMemoryPrompt("my-agent", AgentMemoryDirectory.AgentMemoryScope.USER);
        assertThat(prompt).startsWith("# Persistent Agent Memory");
        assertThat(prompt).isNotEmpty();
        assertThat(fired[0])
            .isEqualTo(memoryBase.resolve("agent-memory").resolve("my-agent").toString());
    }
}
