package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.agent.SessionCwdHolder;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.loadAgentsDir;
import com.nexusai.common.RequestContext;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [C-方案3][DEC-C-01/02/03] SubagentTool per-session agent-defs 惰性载入测试。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 测试验证意图）</b>：CC agent-defs 是
 * {@code getAgentDefinitionsWithOverrides = memoize(cwd)}（loadAgentsDir.ts:296，缓存键=cwd），
 * project 源（cwd 向上至 git root/home 的 {@code .claude/agents/*.md}）per-cwd 不同
 * （loadAgentsDir.ts:357-372）。Java 多会话 web 架构下会话可绑不同项目（CwdResolution
 * boundProject/sessionCwd/override）——旧构造期 user.dir 单表使 cwd≠user.dir 的会话拿不到自己
 * project 的自定义 agent。本测试验证三意图：
 * <ol>
 *   <li><b>一会话一项目</b>：会话 A 绑项目 P1 → agent-defs 从 P1/.claude/agents 载入；会话 B 绑
 *       P2 → 从 P2 载入；同 tool 实例两会话隔离（对齐 CC memoize(cwd) per-cwd 表）。</li>
 *   <li><b>非 per-call 重建</b>（DEC-C-02 反模式禁令）：会话首调一次载入，后续复用缓存 —— 磁盘
 *       agent 变更在未清缓存时不可见。</li>
 *   <li><b>缓存清理通道</b>（DEC-C-03）：loadAgentsDir.clearCache() + clearRegistryCache() 成对清后
 *       磁盘 agent 变更可见（对齐 CC clearAgentDefinitionsCache loadAgentsDir.ts:395-398）。</li>
 * </ol>
 *
 * <p>隔离：ClaudePaths configDir/managedFilePath override 到临时目录（避免读真实 ~/.claude/agents，
 * 环境依赖 → 非可重复硬断言）；SessionProjectRoot.setForSession 绑定会话→项目（CwdResolution L3
 * boundProject 层）；RequestContext.setSession 写 MDC。断言用 contains（project 祖先路径理论上
 * 可能有额外 agent，contains 不受影响）。
 */
@DisplayName("C-方案3 · SubagentTool per-session agent-defs 惰性载入（DEC-C-01/02/03）")
class SubagentToolPerSessionLoadTest {

    private Path configHome;
    private Path managedRoot;

    @BeforeEach
    void setUp() throws Exception {
        configHome = Files.createTempDirectory("c3-cfg");
        managedRoot = Files.createTempDirectory("c3-managed");
        com.nexusai.application.agent.skill.ClaudePaths.setConfigDirOverride(configHome.toString());
        com.nexusai.application.agent.skill.ClaudePaths.setManagedFilePathOverride(managedRoot.toString());
        // G5：loadAgentsDir user 源 = NexusaiPaths 自有根优先 → 唯一 appName 隔离（防读真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());
        loadAgentsDir.clearCache(); // 清 MarkdownConfigLoader memoize，避免跨用例陈旧
        SessionProjectRoot.reset();
        SessionCwdHolder.reset(); // 清 L2 会话 cwd + originalCwd 槽，避免 cd/worktree 用例跨测残留
        RequestContext.clear();
    }

    @AfterEach
    void tearDown() {
        SessionProjectRoot.reset();
        SessionCwdHolder.reset();
        RequestContext.clear();
        loadAgentsDir.clearCache();
        com.nexusai.application.agent.skill.ClaudePaths.setConfigDirOverride(null);
        com.nexusai.application.agent.skill.ClaudePaths.setManagedFilePathOverride(null);
        NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
    }

    /** 在项目根写一个 agent markdown（project 源，source=projectSettings）。 */
    private static void writeProjectAgent(Path projectRoot, String agentType, String description) throws Exception {
        Path agentsDir = projectRoot.resolve(".claude").resolve("agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve(agentType + ".md"),
            "---\nname: " + agentType + "\ndescription: " + description + "\n---\n\nbody");
    }

    /** 构造 SubagentTool，workspaceDir 用无 agent 的独立 temp（证明会话 cwd 而非 workspaceDir 是发现源）。 */
    private SubagentTool newTool() throws Exception {
        Path workspace = Files.createTempDirectory("c3-ws");
        return new SubagentTool(
            List.of(), null, null, null, "gpt-4", "", null,
            workspace, List.of());
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, ?> registriesByCwd(SubagentTool tool) throws Exception {
        java.lang.reflect.Field f = SubagentTool.class.getDeclaredField("registriesByCwd");
        f.setAccessible(true);
        return (java.util.Map<String, ?>) f.get(tool);
    }

    private static List<String> types(SubagentTool tool) {
        return tool.listAgents().stream().map(AgentDefinition::agentType).toList();
    }

    @Test
    @DisplayName("一会话一项目：会话 A 绑 P1 → agent-defs 从 P1/.claude/agents 载入（对齐 CC memoize(cwd) per-cwd project 源）")
    void sessionA_bindsProjectP1_loadsP1AgentDefs() throws Exception {
        // WHY: CC agent-defs 的 project 源 per-cwd 不同（loadAgentsDir.ts:357-372）。Java 会话绑
        //   P1 → CwdResolution.getCwd(sessionA)=P1 → loadAllSources(P1) 载入 P1 的 project agent。
        //   旧构造期 user.dir 单表使 cwd≠user.dir 的会话拿不到自己 project 的自定义 agent（DEC-C-01）。
        Path p1 = Files.createTempDirectory("c3-p1");
        writeProjectAgent(p1, "alpha-agent", "alpha from P1");
        SubagentTool tool = newTool();
        RequestContext.setSession("sessA");
        SessionProjectRoot.setForSession("sessA", p1.toString());

        List<String> types = types(tool);

        assertThat(types)
            .as("会话 A 绑 P1 → agent-defs 必须含 P1/.claude/agents 的 alpha-agent（project 源 per-cwd）")
            .contains("alpha-agent");
        // per-cwd 缓存视图 key = P1（会话 cwd），而非 workspaceDir（无 agent 的独立 temp）
        assertThat(registriesByCwd(tool).keySet())
            .as("per-cwd 缓存视图必须以会话 cwd（P1）为键（对齐 CC memoize 键=cwd）")
            .containsExactly(p1.toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("同一 tool 两会话绑不同项目 → 各自从各自项目载入，互不可见（per-session 隔离）")
    void twoSessions_boundToDifferentProjects_getDifferentAgentDefs() throws Exception {
        // WHY: 多项目部署下各会话 project agent-defs 应不同（DEC-C-01）。同 tool 实例经 MDC sessionId
        //   切换 → registryFor(会话 cwd) 各自载入 P1/P2 的 project agent —— 会话 A 看不到 P2 的
        //   beta-agent，会话 B 看不到 P1 的 alpha-agent（project 源 per-cwd 语义）。
        Path p1 = Files.createTempDirectory("c3-p1a");
        Path p2 = Files.createTempDirectory("c3-p2");
        writeProjectAgent(p1, "alpha-agent", "alpha from P1");
        writeProjectAgent(p2, "beta-agent", "beta from P2");
        SubagentTool tool = newTool();

        RequestContext.setSession("sessA");
        SessionProjectRoot.setForSession("sessA", p1.toString());
        List<String> typesA = types(tool);

        RequestContext.setSession("sessB");
        SessionProjectRoot.setForSession("sessB", p2.toString());
        List<String> typesB = types(tool);

        assertThat(typesA)
            .as("会话 A 绑 P1 → 含 alpha-agent 且不含 P2 的 beta-agent（per-session 隔离）")
            .contains("alpha-agent")
            .doesNotContain("beta-agent");
        assertThat(typesB)
            .as("会话 B 绑 P2 → 含 beta-agent 且不含 P1 的 alpha-agent（per-session 隔离）")
            .contains("beta-agent")
            .doesNotContain("alpha-agent");
        assertThat(registriesByCwd(tool))
            .as("两会话两项目 → per-cwd 缓存视图含 2 个 registry（P1/P2 各自一张表）")
            .hasSize(2);
    }

    @Test
    @DisplayName("非 per-call 重建（DEC-C-02 反模式禁令）：会话首调一次载入，后续复用缓存")
    void sameSession_reusesCachedRegistry_notPerCallRebuild() throws Exception {
        // WHY: CC 会话期固定一张表，AgentTool.tsx:286/338/341 只读 options 不重载（loadAgentsDir.ts:296
        //   memoize(cwd)）。Java 若每次 Agent tool 调用都 loadAllSources 重建 → 无谓 I/O 且违背 CC
        //   （DEC-C-02 反模式禁令）。验证：二次 listAgents 缓存命中（registriesByCwd 仍 1 条），且磁盘
        //   agent 变更在未清缓存时不可见（同一 registry 实例复用）。
        Path p1 = Files.createTempDirectory("c3-p1c");
        writeProjectAgent(p1, "alpha-agent", "alpha v1");
        SubagentTool tool = newTool();
        RequestContext.setSession("sessA");
        SessionProjectRoot.setForSession("sessA", p1.toString());

        List<String> first = types(tool);
        assertThat(first).contains("alpha-agent");

        // 二次调用 → 缓存命中（不重建）：registriesByCwd 仍 1 条
        assertThat(registriesByCwd(tool)).as("会话首调一次载入 → 缓存视图 1 条").hasSize(1);
        List<String> second = types(tool);
        assertThat(registriesByCwd(tool)).as("二次调用缓存命中（非 per-call 重建）→ 仍 1 条").hasSize(1);

        // 磁盘 agent 变更（description 改 v2）在未清缓存时不可见 → 证明读的是缓存 registry 而非重读盘
        Files.writeString(p1.resolve(".claude").resolve("agents").resolve("alpha-agent.md"),
            "---\nname: alpha-agent\ndescription: alpha v2\n---\n\nbody");
        List<String> third = types(tool);
        assertThat(third)
            .as("未清缓存 → 磁盘变更不可见（复用首调 registry，非 per-call 重建）")
            .contains("alpha-agent");
        AgentDefinition alpha = tool.listAgents().stream()
            .filter(a -> a.agentType().equals("alpha-agent")).findFirst().orElseThrow();
        assertThat(alpha.whenToUse()).as("复用缓存 registry → description 仍是 v1（未重读盘）")
            .isEqualTo("alpha v1");
    }

    @Test
    @DisplayName("缓存清理通道（DEC-C-03）：loadAgentsDir.clearCache + clearRegistryCache 成对清后磁盘变更可见")
    void clearRegistryCache_forcesRebuild() throws Exception {
        // WHY: CC clearAgentDefinitionsCache（loadAgentsDir.ts:395-398）由 /clear（caches.ts:138）+
        //   插件刷新（cacheUtils.ts:47）触发，清后下次请求重建。Java 侧 agent-defs 组装层缓存
        //   （per-cwd registry）必须与文件发现层（loadAgentsDir.clearCache）成对清，否则磁盘 agent
        //   变更不可见。验证：新增 agent 文件 + 成对清 → 新 agent 可见（惰性重建）。
        Path p1 = Files.createTempDirectory("c3-p1d");
        writeProjectAgent(p1, "alpha-agent", "alpha");
        SubagentTool tool = newTool();
        RequestContext.setSession("sessA");
        SessionProjectRoot.setForSession("sessA", p1.toString());

        assertThat(types(tool)).as("首调载入 alpha-agent").contains("alpha-agent");

        // 新增 gamma-agent → 未清缓存不可见
        writeProjectAgent(p1, "gamma-agent", "gamma new");
        assertThat(types(tool)).as("未清缓存 → 新增 gamma-agent 不可见（复用首调 registry）")
            .doesNotContain("gamma-agent");

        // 成对清（对齐 /clear + 插件刷新触发点：loadAgentsDir.clearCache + clearRegistryCache）
        loadAgentsDir.clearCache();
        tool.clearRegistryCache();
        assertThat(registriesByCwd(tool)).as("clearRegistryCache 清空 per-cwd 视图").isEmpty();

        List<String> after = types(tool);
        assertThat(after)
            .as("成对清后下次访问惰性重建 → gamma-agent 可见")
            .contains("gamma-agent");
    }

    @Test
    @DisplayName("REWORK-1：会话内 bash cd 到其他目录 → agent-defs 复用原 registry 不重载（对齐 CC startup-cwd-fixed）")
    void sessionCwdChange_cd_doesNotReloadAgentDefs() throws Exception {
        // WHY: CC agent-defs 进程启动期一次捕获（main.tsx:1929 getAgentDefinitionsWithOverrides(preSetupCwd)），
        //   bash cd（Shell.ts:407 setCwd → STATE.cwd）只改会话 cwd、从不重载 agent-defs
        //   （AgentTool.tsx:286/338/341 只读 options；runAgent.ts:687 子 agent 继承父表）。Java 若发现键
        //   走 CwdResolution.getCwd（含动态 L2 SessionCwdHolder）→ cd 后按新 cwd 重建 registry、可能载入
        //   另一项目 agent（REWORK-1 根因，违背核验点 #2 "cd/worktree 不重载"）。验证：会话内 cd 后
        //   per-cwd 缓存视图键不变、agent-defs 不变（发现 cwd 首访问冻结于启动目录）。
        Path p1 = Files.createTempDirectory("c3-p1e");
        Path cdDir = Files.createTempDirectory("c3-cd");
        writeProjectAgent(p1, "alpha-agent", "alpha from P1");
        writeProjectAgent(cdDir, "beta-agent", "beta from cd dir");
        SubagentTool tool = newTool();
        RequestContext.setSession("sessA");
        SessionProjectRoot.setForSession("sessA", p1.toString());

        List<String> beforeCd = types(tool);
        assertThat(beforeCd)
            .as("会话绑定 P1 → 首调（启动目录=P1）载入 P1 的 alpha-agent")
            .contains("alpha-agent");

        // 模拟 bash 前台命令 cd（BashTool.java:967 读回 newCwd → SessionCwdHolder.set(sessionId, newCwd)）
        SessionCwdHolder.set("sessA", cdDir.toString());

        List<String> afterCd = types(tool);
        assertThat(afterCd)
            .as("会话内 cd 到 cdDir → agent-defs 复用原 registry 不重载（对齐 CC startup-cwd-fixed：仍 P1 表，不载入 cdDir 的 beta-agent）")
            .contains("alpha-agent")
            .doesNotContain("beta-agent");
        assertThat(registriesByCwd(tool).keySet())
            .as("cd 后 per-cwd 缓存视图键仍为 P1（发现键冻结于启动目录，不按新 cwd 重建）")
            .containsExactly(p1.toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("REWORK-2：worktree 进入（L2 cwd + originalCwd 双写）→ agent-defs 不重载（对齐 CC EnterWorktreeTool.ts:95 不重载）")
    void worktreeEnter_doesNotReloadAgentDefs() throws Exception {
        // WHY: CC 进入 worktree（EnterWorktreeTool.ts:95 setCwd(worktreePath)，:96 setOriginalCwd）只改
        //   STATE.cwd / STATE.originalCwd，从不重调 getAgentDefinitionsWithOverrides → agent-defs 仍是
        //   启动期表（startup-cwd-fixed，§1.2/§7.1：子 agent 在 worktree 执行 agent-defs 仍是父启动表）。
        //   Java 端 EnterWorktreeTool 同写 L2 SessionCwdHolder.set + setOriginalCwd 两槽；验证发现键冻结于
        //   boundProject（P1），worktree 路径上的 project agent 不载入（REWORK-2 回归护栏）。
        Path p1 = Files.createTempDirectory("c3-p1f");
        Path worktree = Files.createTempDirectory("c3-wt");
        writeProjectAgent(p1, "alpha-agent", "alpha from P1");
        writeProjectAgent(worktree, "wt-agent", "agent from worktree");
        SubagentTool tool = newTool();
        RequestContext.setSession("sessA");
        SessionProjectRoot.setForSession("sessA", p1.toString());

        assertThat(types(tool))
            .as("首调（启动目录=P1）→ 载入 P1 的 alpha-agent")
            .contains("alpha-agent");

        // 模拟 EnterWorktreeTool.applySessionCwd：L2 cwd 槽 + originalCwd 槽同写 worktree 路径
        SessionCwdHolder.set("sessA", worktree.toString());
        SessionCwdHolder.setOriginalCwd("sessA", worktree.toString());

        List<String> inWorktree = types(tool);
        assertThat(inWorktree)
            .as("worktree 进入 → agent-defs 不重载（仍 P1 启动表，不载入 worktree 的 wt-agent，对齐 CC startup-cwd-fixed）")
            .contains("alpha-agent")
            .doesNotContain("wt-agent");
        assertThat(registriesByCwd(tool).keySet())
            .as("worktree 进入后缓存视图键仍为 P1（发现键冻结，不按 worktree 路径重建）")
            .containsExactly(p1.toAbsolutePath().normalize().toString());
    }
}
