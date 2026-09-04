package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.api.AnalyticsTracker;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.application.agent.config.MemoryBareModeConfig;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.FileReadingLimits;
import com.nexusai.application.agent.tool.ContentBlockParam;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.skill.DynamicSkillsManager;
import com.nexusai.application.agent.skill.SkillsLoader;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.ToolUseContext.ReadState;
import com.nexusai.infra.util.GitIgnoreHelper;
import com.nexusai.model.command.Command;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.entity.ProviderRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.provider.mapper.ProviderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Session L · {@link ReadFileTool} CC 对齐验证。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * 本测试验证 ReadFileTool 与 CC {@code FileReadTool.ts} 的 L1 行为对齐契约：
 * <ol>
 *   <li><b>isReadOnly = true</b> —— 上游 PermissionPipeline / YoloClassifier 据此
 *       跳过读工具的写权限检查。本测试若有人改回 {@code false}，会让读工具进入
 *       完整 10 层权限流程——违背 CC 对齐契约。</li>
 *   <li><b>validateInput 各分支</b> —— pages 格式、>20 页、UNC 提前通过、二进制扩展名、
 *       设备路径。任一分支漏掉都会让"LLM 读到 /dev/zero 永远阻塞"等真实安全漏洞重现。</li>
 *   <li><b>checkPermissions 默认 Allow</b> —— 与 CC {@code FileReadTool.ts:398-405}
 *       委托语义对齐；本批次新建 {@code ReadPermissionChecker}，此处仅测默认 Allow。</li>
 *   <li><b>多类型输出 dispatch</b> —— .ipynb → notebook, .png/.jpg → image,
 *       .pdf → pdf/parts（[P-CC-01] pdfbox 完整解析：readPDF document 块 + extractPDFPages
 *       页图提取，对齐 CC FileReadTool.ts:893-1017），其余 text;
 *       file_unchanged dedup 是契约必备。</li>
 *   <li><b>FileReadListener</b> —— 仅 text 分支成功后通知，对齐 CC :1040-1044。</li>
 * </ol>
 */
@DisplayName("Session L · ReadFileTool 对齐 CC FileReadTool.ts 契约")
class ReadFileToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ToolUseBlock callWith(String path, Object... extras) {
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", path);
        for (int i = 0; i + 1 < extras.length; i += 2) {
            String key = (String) extras[i];
            Object val = extras[i + 1];
            if (val instanceof Integer n) input.put(key, n);
            else if (val instanceof String s) input.put(key, s);
            else if (val instanceof Boolean b) input.put(key, b);
        }
        return new ToolUseBlock("call-rft-1", "read_file", input);
    }

    private static ReadFileTool toolFor(Path workspace) {
        return new ReadFileTool(new PathGuard(workspace));
    }

    /**
     * [RV-06] text 分支 tool_result 载荷文本 = mapper content（行号 + reminder 在序列化层拼接）。
     * 对齐 CC FileReadTool.ts:692-715 mapToolResultToToolResultBlockParam；call 层 data() 已是 raw。
     */
    private static String textContent(ReadFileTool tool, ToolResult result) {
        return (String) tool.mapToToolResultBlockParam(result, "read-call", false).content();
    }

    /** [IMP-C5] 捕获 FileReadListener 二元投递 · CC (filePath, content) 签名（FileReadTool.ts:162）。 */
    private record ReadNotification(String path, String content) {}

    /**
     * [IMP-C5] 行号渲染体（剥 CYBER_RISK_MITIGATION_REMINDER）· 替代旧 "N more lines" suffix 的
     * {@code split("...")[0]} 截断（TR-D1-⊕-3 删除 suffix 后，reminder 后缀不再有 "..." 可切）。
     */
    private static String lineNumberBody(ReadFileTool tool, ToolResult result) {
        return textContent(tool, result).replace(ReadFileTool.CYBER_RISK_MITIGATION_REMINDER, "");
    }

    /**
     * 构造一个带 readFileState (per-session 隔离) 的最小 ToolUseContext, 让 ReadFileTool
     * 走 ctx 路径访问 dedup cache. 使用 {@link Path} 作为 agentId 派生哈希 + 稳定 UUID
     * 保证 @TempDir 每次测试唯一.
     */
    private static ToolUseContext ctxWithSession(Path workspace) {
        UUID agentId = UUID.nameUUIDFromBytes(("rft-agent-" + workspace).getBytes());
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8).toString().toString();
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT);
    }

    // ═════════════ GAP-A: isReadOnly ═════════════

    @Test
    @DisplayName("GAP-A · isReadOnly 必须 true——上游据此跳过写权限检查；改回 false 会让读工具进入完整 10 层权限流程")
    void isReadOnlyReturnsTrue() {
        ReadFileTool tool = toolFor(Path.of("."));

        // WHY 显式 null input：CC 默认方法签名带 input 但语义上不读参数，只读 tool 自身
        assertThat(tool.isReadOnly(null)).isTrue();
        assertThat(tool.isReadOnly(JSON.createObjectNode())).isTrue();
    }

    // ═════════════ B 组: skill 目录发现 (CC FileReadTool.ts:575-591) ═════════════

    /** gitExec 桩: exit 1 = 不忽略（fail-open 等价 git 无命中）· 同 DynamicSkillsManagerTest.notIgnored。 */
    private static GitIgnoreHelper.ExecResult notIgnored(String[] args, String cwd) {
        return new GitIgnoreHelper.ExecResult(1, "", "");
    }

    /**
     * 带 effectiveCwd=workspace 的 ToolUseContext: 技能发现上界必须显式等于 workspace
     * （3 参工厂 effectiveCwd 兜底为 user.dir，父链 startsWith(user.dir+sep) 不成立 → 发现为空）。
     */
    private static ToolUseContext ctxWithCwd(Path workspace) throws Exception {
        UUID agentId = UUID.nameUUIDFromBytes(("rft-agent-" + workspace).getBytes());
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8).toString().toString();
        // toRealPath: @TempDir 在 Windows 返回 8.3 短名（ADMINI~1.DES），而 PathGuard.resolve
        // 内部 toRealPath 长名（Administrator.DESKTOP-S50NL12）—— 形态不一致会让 discover
        // 的 startsWith(cwd+sep) 前缀判断失败（长名不 startsWith 短名）。统一为 RealPath 长名。
        return ToolUseContext.of(agentId, sessionId, PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            null, false, "", workspace.toRealPath());
    }

    /** 轮询等待后台加载完成（CC addSkillDirectories fire-and-forget，无 await 点可挂）。 */
    private static void awaitSkill(Supplier<Boolean> condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("等待超时: " + what);
    }

    /** 慢速 SkillsLoader: 模拟后台加载耗时（同步实现会阻塞 Read 调用链 → 本类 RED 证据）。 */
    private static final class SlowSkillsLoader extends SkillsLoader {
        @Override
        public List<Command> loadFromDirectoryUnconditional(String skillsRoot) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.loadFromDirectoryUnconditional(skillsRoot);
        }
    }

    /** 计数 SkillsLoader: 统计后台加载触发次数（dedup 命中不得新增加载）。 */
    private static final class CountingSkillsLoader extends SkillsLoader {
        final AtomicInteger loadCount = new AtomicInteger();

        @Override
        public List<Command> loadFromDirectoryUnconditional(String skillsRoot) {
            loadCount.incrementAndGet();
            return super.loadFromDirectoryUnconditional(skillsRoot);
        }
    }

    /**
     * 计数 DynamicSkillsManager: 覆写 discover/activate 两个技能链入口统计调用次数。
     * 为什么必须计数 manager 而不是 loader：manager 内 dynamicSkillDirs 去重会让二次
     * discover 返回空（newDirs 空 → 不 add triggers、不调 addSkillDirectories），
     * 仅凭 triggers/loader 断言区分不了"技能链执行了但空发现"与"零技能链"；
     * activateConditionalSkillsForPaths 无条件执行（ReadFileTool :1207 在 discover 块外），
     * 其调用次数是"技能链是否执行"的可靠观测点。
     */
    private static final class CountingSkillsManager extends DynamicSkillsManager {
        final AtomicInteger discoverCount = new AtomicInteger();
        final AtomicInteger activateCount = new AtomicInteger();

        @Override
        public java.util.List<String> discoverSkillDirsForPaths(java.util.List<String> filePaths, Path cwd) {
            discoverCount.incrementAndGet();
            return super.discoverSkillDirsForPaths(filePaths, cwd);
        }

        @Override
        public java.util.List<String> activateConditionalSkillsForPaths(java.util.List<String> filePaths, Path cwd) {
            activateCount.incrementAndGet();
            return super.activateConditionalSkillsForPaths(filePaths, cwd);
        }
    }

    @Test
    @DisplayName("[B 组/P-AL-07] 读取嵌套路径命中 .claude/skills → 记入 dynamicSkillDirTriggers (CC :583-584)")
    void readDiscoveredSkillDirTriggers(@TempDir Path workspace) throws Exception {
        // 嵌套层: cwd 级技能 CC :874-876 启动时已加载不发现，只有嵌套目录才触发发现
        Path file = workspace.resolve("sub").resolve("a.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "hello\n");
        Path skillsDir = workspace.resolve("sub/.claude").resolve("skills");
        Files.createDirectories(skillsDir.resolve("demo"));
        Files.writeString(skillsDir.resolve("demo/SKILL.md"), "---\nname: demo\n---\nbody");

        DynamicSkillsManager manager = new DynamicSkillsManager();
        manager.setGitExec(ReadFileToolTest::notIgnored);
        ReadFileTool tool = toolFor(workspace);
        tool.setDynamicSkillsManager(manager);
        ToolUseContext ctx = ctxWithCwd(workspace);
        ToolResult r = (ToolResult) tool.execute(callWith("sub/a.txt"), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        // Windows 8.3 短名差异 (tool 内部 file.getParent() 解析出 ADMINI~1.DES):
        // 两侧都 toRealPath 规范化后比较.
        String expected = skillsDir.toRealPath().toString();
        assertThat(ctx.dynamicSkillDirTriggers())
            .as("CC :583-584 读取触发 skill 目录记入 triggers (fire-and-forget 语义)")
            .anyMatch(p -> {
                try {
                    return java.nio.file.Path.of(p).toRealPath().toString().equals(expected);
                } catch (Exception e) {
                    return false;
                }
            });
    }

    @Test
    @DisplayName("[P-AL-07] cwd 级 .claude/skills 读取不触发记录 —— CC :874-876 启动时已加载，只发现嵌套层")
    void cwdLevelSkillDirNotDiscovered(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        Files.createDirectories(workspace.resolve(".claude").resolve("skills"));

        DynamicSkillsManager manager = new DynamicSkillsManager();
        manager.setGitExec(ReadFileToolTest::notIgnored);
        ReadFileTool tool = toolFor(workspace);
        tool.setDynamicSkillsManager(manager);
        ToolUseContext ctx = ctxWithCwd(workspace);
        ToolResult r = (ToolResult) tool.execute(callWith("a.txt"), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        assertThat(ctx.dynamicSkillDirTriggers())
            .as("cwd 级技能启动时已加载（CC :874-876），读取不得触发记录")
            .isEmpty();
    }

    // ═════════════ G24-bare: 会话级 bare 跳过 skill 目录遍历 (CC FileReadTool.ts:578) ═════════════

    @Test
    @DisplayName("[G24-bare] bare 会话 Read 跳过动态技能发现 —— CC FileReadTool.ts:578 SIMPLE 门控")
    void bareMode_skipsSkillDiscovery(@TempDir Path workspace) throws Exception {
        // WHY（规则九 · 验证意图）：CC FileReadTool.ts:578 `if (!isEnvTruthy(process.env.CLAUDE_CODE_SIMPLE))`
        //   包裹 discoverSkillDirsForPaths + activateConditionalSkillsForPaths —— bare（SIMPLE）模式
        //   跳过 skill 目录遍历（envUtils.ts:50 isBareMode ~30 gates 之一）。Java Web 端无 simple
        //   mode 概念 → 会话级判定（bareMode 随会话走，V33 列）。变异点：删除 bare 门控 →
        //   discover/activate 被调用 + triggers 记录 → 红。
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "hello\n");
        ReadFileTool tool = toolFor(workspace);
        CountingSkillsManager manager = new CountingSkillsManager();
        manager.setGitExec(ReadFileToolTest::notIgnored);
        tool.setDynamicSkillsManager(manager);
        ToolUseContext ctx = ctxWithCwd(workspace);
        try {
            new MemoryBareModeConfig(true);   // 全局桥 bare=true（ctx sessionId 非 "sess-" 派生 → 回落全局）
            ToolResult r = (ToolResult) tool.execute(callWith("a.txt"), ctx);
            assertThat(LlmAgentLoop.isToolErrorData(r.data())).as("bare 会话读取仍须成功").isFalse();
            assertThat(manager.discoverCount.get())
                .as("bare 会话必须跳过 discoverSkillDirsForPaths（CC FileReadTool.ts:579）")
                .isZero();
            assertThat(manager.activateCount.get())
                .as("bare 会话必须跳过 activateConditionalSkillsForPaths（CC FileReadTool.ts:590）")
                .isZero();
            assertThat(ctx.dynamicSkillDirTriggers())
                .as("bare 会话不得记录 skill 目录")
                .isEmpty();
        } finally {
            MemoryBareModeConfig.reset();
        }
    }

    @Test
    @DisplayName("[G24-bare] 非 bare 会话 Read 仍触发动态技能发现（SIMPLE 门控反面）")
    void nonBareMode_stillDiscoversSkills(@TempDir Path workspace) throws Exception {
        // WHY：bare 门控必须仅 isBareMode() 开启时生效；默认（非 bare）技能目录遍历照常执行
        //   （CC FileReadTool.ts:578 非 SIMPLE 分支）。变异点：门控误判（恒 true）→ 非 bare
        //   discover/activate 被误跳 → 红。
        Path file = workspace.resolve("a.txt");
        Files.writeString(file, "hello\n");
        ReadFileTool tool = toolFor(workspace);
        CountingSkillsManager manager = new CountingSkillsManager();
        manager.setGitExec(ReadFileToolTest::notIgnored);
        tool.setDynamicSkillsManager(manager);
        ToolUseContext ctx = ctxWithCwd(workspace);
        try {
            new MemoryBareModeConfig(false);  // 全局桥 bare=false
            ToolResult r = (ToolResult) tool.execute(callWith("a.txt"), ctx);
            assertThat(LlmAgentLoop.isToolErrorData(r.data())).as("非 bare 会话读取须成功").isFalse();
            assertThat(manager.discoverCount.get())
                .as("非 bare 会话必须执行 discoverSkillDirsForPaths（CC :579）")
                .isGreaterThanOrEqualTo(1);
            assertThat(manager.activateCount.get())
                .as("非 bare 会话必须执行 activateConditionalSkillsForPaths（CC :590）")
                .isGreaterThanOrEqualTo(1);
        } finally {
            MemoryBareModeConfig.reset();
        }
    }

    @Test
    @DisplayName("[P-AL-07] 读取触发后台 addSkillDirectories → dynamicSkills 可见 (CC :586 fire-and-forget)")
    void readTriggersBackgroundSkillLoad(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("sub/deep/a.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        Path skillsDir = workspace.resolve("sub/deep/.claude").resolve("skills");
        Files.createDirectories(skillsDir);
        Files.createDirectories(skillsDir.resolve("deep-skill"));
        Files.writeString(skillsDir.resolve("deep-skill/SKILL.md"), "---\nname: deep-skill\n---\nbody");

        DynamicSkillsManager manager = new DynamicSkillsManager();
        manager.setGitExec(ReadFileToolTest::notIgnored);
        ReadFileTool tool = toolFor(workspace);
        tool.setDynamicSkillsManager(manager);
        ToolUseContext ctx = ctxWithCwd(workspace);
        ToolResult r = (ToolResult) tool.execute(callWith("sub/deep/a.txt"), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        // 后台加载完成后 dynamicSkills 对模型可见（CC getDynamicSkills → getCommands 叠加）
        awaitSkill(
            () -> manager.getDynamicSkills().stream().anyMatch(c -> "deep-skill".equals(c.getName())),
            "后台 addSkillDirectories 应加载 deep-skill");
    }

    @Test
    @DisplayName("[P-AL-07] addSkillDirectories 后台 fire-and-forget：慢加载不阻塞 Read 调用 (CC :585-586)")
    void backgroundLoadDoesNotBlockRead(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("sub/a.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        Files.createDirectories(workspace.resolve("sub/.claude").resolve("skills"));

        DynamicSkillsManager manager = new DynamicSkillsManager();
        manager.setGitExec(ReadFileToolTest::notIgnored);
        manager.setSkillsLoader(new SlowSkillsLoader());
        ReadFileTool tool = toolFor(workspace);
        tool.setDynamicSkillsManager(manager);
        ToolUseContext ctx = ctxWithCwd(workspace);
        long start = System.nanoTime();
        ToolResult r = (ToolResult) tool.execute(callWith("sub/a.txt"), ctx);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(LlmAgentLoop.isToolErrorData(r.data())).isFalse();
        assertThat(elapsedMs)
            .as("addSkillDirectories 必须后台执行（CC FileReadTool.ts:585 'Don't await - let skill loading happen in the background'），同步实现会阻塞 ~3s")
            .isLessThan(2000);
    }

    @Test
    @DisplayName("[P-AL-07 R1] dedup 命中零技能链：二次同 range 读 file_unchanged → triggers 不重新填充 + 无新后台加载 (CC :562-567 早返在 :575-591 之前)")
    void dedupHitSkipsSkillDiscovery(@TempDir Path workspace) throws Exception {
        Path file = workspace.resolve("sub/deep/a.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        Path skillsDir = workspace.resolve("sub/deep/.claude").resolve("skills");
        Files.createDirectories(skillsDir);
        Files.createDirectories(skillsDir.resolve("deep-skill"));
        Files.writeString(skillsDir.resolve("deep-skill/SKILL.md"), "---\nname: deep-skill\n---\nbody");
        CountingSkillsLoader loader = new CountingSkillsLoader();
        CountingSkillsManager manager = new CountingSkillsManager();
        manager.setGitExec(ReadFileToolTest::notIgnored);
        manager.setSkillsLoader(loader);
        ReadFileTool tool = toolFor(workspace);
        tool.setDynamicSkillsManager(manager);
        ToolUseContext ctx = ctxWithCwd(workspace);

        // 第一次读：技能链触发（discover → triggers 记录 → 后台 addSkillDirectories → activate）
        ToolResult first = (ToolResult) tool.execute(callWith("sub/deep/a.txt"), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(first.data())).isFalse();
        awaitSkill(() -> manager.getDynamicSkills().stream().anyMatch(c -> "deep-skill".equals(c.getName())),
            "第一次读应触发后台加载");
        assertThat(ctx.dynamicSkillDirTriggers()).isNotEmpty();
        int discoverAfterFirst = manager.discoverCount.get();
        int activateAfterFirst = manager.activateCount.get();
        assertThat(discoverAfterFirst).isGreaterThanOrEqualTo(1);
        assertThat(activateAfterFirst).isGreaterThanOrEqualTo(1);

        int loadBefore = loader.loadCount.get();
        // 清空 triggers：若第二次读（dedup 命中）仍执行技能链，会重新填充 → 断言空锁偏离
        ctx.dynamicSkillDirTriggers().clear();

        // 第二次读同 range：mtime 未变 → dedup 命中 file_unchanged（CC :562-567 早返 return）
        ToolResult second = (ToolResult) tool.execute(callWith("sub/deep/a.txt"), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(second.data())).isFalse();
        assertThat(((JsonNode) second.data()).get("read_file_output_type").asText())
            .isEqualTo("file_unchanged");
        assertThat(ctx.dynamicSkillDirTriggers())
            .as("dedup 命中早返（CC :562-567 return 在技能链 :575-591 之前）→ 技能链零执行，triggers 不得被重新填充")
            .isEmpty();
        // 零技能链：discover/activate 调用次数不得增加（activate 无条件执行——若技能链
        // 被调用，即使 discover 因去重返回空，activate 计数也会 +1，此断言锁得住偏离）
        assertThat(manager.discoverCount.get())
            .as("dedup 命中不得再执行 discoverSkillDirsForPaths")
            .isEqualTo(discoverAfterFirst);
        assertThat(manager.activateCount.get())
            .as("dedup 命中不得再执行 activateConditionalSkillsForPaths")
            .isEqualTo(activateAfterFirst);
        assertThat(loader.loadCount.get())
            .as("dedup 命中不得新增 addSkillDirectories 后台加载")
            .isEqualTo(loadBefore);
    }

    @Test
    @DisplayName("[P-AL-07 O1] 文件不存在仍触发技能链：CC 技能链在 callInner(ENOENT :611) 之前，对缺失文件同样发现")
    void missingFileStillTriggersSkillDiscovery(@TempDir Path workspace) throws Exception {
        // 嵌套技能目录存在，但目标文件从未创建（discover 从父目录向上找，不依赖文件存在）
        Path skillsDir = workspace.resolve("sub/.claude").resolve("skills");
        Files.createDirectories(skillsDir.resolve("demo"));
        Files.writeString(skillsDir.resolve("demo/SKILL.md"), "---\nname: demo\n---\nbody");

        DynamicSkillsManager manager = new DynamicSkillsManager();
        manager.setGitExec(ReadFileToolTest::notIgnored);
        ReadFileTool tool = toolFor(workspace);
        tool.setDynamicSkillsManager(manager);
        ToolUseContext ctx = ctxWithCwd(workspace);

        ToolResult r = (ToolResult) tool.execute(callWith("sub/missing.txt"), ctx);
        // [IMP-C5] 错误 data 为 String（"File not found" 不在 isToolErrorData 前缀集，直接断言错误消息文本）
        assertThat(r.data()).isInstanceOf(String.class);
        assertThat((String) r.data()).contains("File not found");
        assertThat(ctx.dynamicSkillDirTriggers())
            .as("CC FileReadTool.ts:575-591 技能链在 callInner（:593 ENOENT 友好错误 :611）之前执行——文件不存在同样触发技能发现")
            .isNotEmpty();
    }

    // ═════════════ GAP-C: validateInput ═════════════

    @Test
    @DisplayName("GAP-C · validateInput: pages 格式非法 → errorCode=7 —— 让 LLM 自纠写错格式")
    void validateInputRejectsBadPagesFormat(@TempDir Path workspace) {
        ReadFileTool tool = toolFor(workspace);
        ToolUseBlock call = callWith("doc.md", "pages", "abc");

        var result = tool.validateInput(call.input(), null);

        assertThat(result.ok()).isFalse();
        // errorCode 用字符串形式（CC 原生是 number 7，本类 ValidationResult 用 String）
        assertThat(result.errorCode()).isEqualTo("7");
    }

    @Test
    @DisplayName("GAP-C · validateInput: pages>20 → errorCode=8 —— 与 CC PDF_MAX_PAGES_PER_READ 对齐")
    void validateInputRejectsPagesOverLimit(@TempDir Path workspace) {
        ReadFileTool tool = toolFor(workspace);
        ToolUseBlock call = callWith("doc.md", "pages", "1-25");

        var result = tool.validateInput(call.input(), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("8");
    }

    @Test
    @DisplayName("GAP-C · validateInput: UNC 路径(//开头)提前 pass——把决策推迟到 checkPermissions(防 NTLM 凭据泄露)")
    void validateInputUncPathPassesThrough(@TempDir Path workspace) {
        ReadFileTool tool = toolFor(workspace);
        ToolUseBlock call = callWith("//server/share/file.txt");

        var result = tool.validateInput(call.input(), null);

        assertThat(result.ok())
            .as("UNC 路径在 validateInput 阶段必须提前 pass，让 checkPermissions 弹窗走 ask 路径")
            .isTrue();
    }

    @Test
    @DisplayName("GAP-C · validateInput: 二进制扩展名(.exe) → errorCode=4 —— 防 LLM 读二进制当文本解析崩 context")
    void validateInputRejectsBinaryExtension(@TempDir Path workspace) {
        ReadFileTool tool = toolFor(workspace);
        ToolUseBlock call = callWith("installer.exe");

        var result = tool.validateInput(call.input(), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("4");
    }

    @Test
    @DisplayName("GAP-C · validateInput: BLOCKED_DEVICE_PATHS (/dev/zero) → errorCode=9 —— 防止读设备文件让进程永久阻塞")
    void validateInputRejectsBlockedDevicePath(@TempDir Path workspace) {
        ReadFileTool tool = toolFor(workspace);
        ToolUseBlock call = callWith("/dev/zero");

        var result = tool.validateInput(call.input(), null);

        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("9");
    }

    @Test
    @DisplayName("GAP-C · validateInput: 合法 .txt 路径 → pass —— 不该误拒合法调用")
    void validateInputAcceptsNormalTextFile(@TempDir Path workspace) {
        ReadFileTool tool = toolFor(workspace);
        ToolUseBlock call = callWith("notes.txt");

        var result = tool.validateInput(call.input(), null);

        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("GAP-C · validateInput: .pdf 扩展名不被 binary 规则拒（image 类扩展也不拒）—— 对齐 CC IMAGE/PDF 白名单")
    void validateInputAcceptsPdfAndImages(@TempDir Path workspace) {
        ReadFileTool tool = toolFor(workspace);

        assertThat(tool.validateInput(callWith("doc.pdf").input(), null).ok()).isTrue();
        assertThat(tool.validateInput(callWith("pic.png").input(), null).ok()).isTrue();
        assertThat(tool.validateInput(callWith("pic.jpg").input(), null).ok()).isTrue();
        assertThat(tool.validateInput(callWith("pic.jpeg").input(), null).ok()).isTrue();
        assertThat(tool.validateInput(callWith("pic.gif").input(), null).ok()).isTrue();
        assertThat(tool.validateInput(callWith("pic.webp").input(), null).ok()).isTrue();
    }

    // ═════════════ GAP-E: 多类型输出 dispatch ═════════════

    @Test
    @DisplayName("GAP-E · .ipynb 分支: cells 经 processCell 处理后通过 notebook 输出（processed cells + <cell> 渲染）")
    void executeNotebookOutputsNotebook(@TempDir Path workspace) throws Exception {
        String notebookJson = "{\"cells\":[" +
            "{\"cell_type\":\"code\",\"source\":[\"print('hi')\"],\"metadata\":{}}" +
            "],\"metadata\":{},\"nbformat\":4,\"nbformat_minor\":5}";
        Files.writeString(workspace.resolve("a.ipynb"), notebookJson);
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("a.ipynb"));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        // notebook 类型 data(JsonNode) 携带 processed cells（CC data.file.cells = NotebookCellSource[]）
        JsonNode data = (JsonNode) result.data();
        assertThat(data.has("notebook_cells")).isTrue();
        assertThat(data.get("notebook_cells").get(0).get("cellType").asText()).isEqualTo("code");
        assertThat(data.get("notebook_cells").get(0).get("source").asText()).contains("print");
        assertThat(data.get("notebook_cells_json").asText()).contains("print");
        assertThat(data.get("notebook_rendered").asText())
            .contains("<cell id=\"cell-0\">").contains("print('hi')");
        assertThat(data.get("summary").asText()).contains("notebook").contains("a.ipynb");
    }

    @Test
    @DisplayName("rv-b-r1 gap1 · notebook 读成功后写 readFileState（content=处理后的 cellsJson, offset/limit/mtime）")
    void executeNotebookWritesReadFileState(@TempDir Path workspace) throws Exception {
        String notebookJson = "{\"cells\":[" +
            "{\"cell_type\":\"code\",\"source\":[\"print('hi')\"],\"metadata\":{}}" +
            "],\"metadata\":{},\"nbformat\":4,\"nbformat_minor\":5}";
        Files.writeString(workspace.resolve("a.ipynb"), notebookJson);
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSession(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("a.ipynb"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        String key = ToolUseContext.keyForReadFileState(new PathGuard(workspace), "a.ipynb");
        ReadState state = ctx.readFileState().get(key);
        assertThat(state).as("notebook 读成功必须写 readFileState（CC FileReadTool.ts:842）").isNotNull();
        assertThat(state.offset()).as("offset 存本次入参默认 1").isEqualTo(1);
        assertThat(state.limit()).as("无 limit 入参 = full read (null)").isNull();
        assertThat(state.mtimeMillis()).as("独立 stat mtime > 0").isGreaterThan(0L);
        assertThat(state.content()).as("content 为处理后的 cellsJson（非 raw 文件）")
            .contains("print").contains("cell-0");
    }

    @Test
    @DisplayName("rv-b-r1 gap2 · standalone image resize 后生成 isMeta metadata 文本消息（Multiply coordinates by）")
    void executeResizedImageEmitsMetadataMessage(@TempDir Path workspace) throws Exception {
        // 3000x100 纯色 PNG → 宽 > IMAGE_MAX_WIDTH(2000) → resize 到 2000x67 → display != original
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
            3000, 100, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 3000, 100);
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", workspace.resolve("big.png").toFile());
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("big.png"), ctxWithSession(workspace));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(result.newMessages()).as("resize 后应生成 isMeta metadata 消息").hasSize(1);
        com.nexusai.model.session.dto.ChatMessageDto msg =
            (com.nexusai.model.session.dto.ChatMessageDto) result.newMessages().get(0);
        assertThat(msg.isMeta()).as("CC createUserMessage({isMeta:true})").isTrue();
        assertThat(msg.content()).contains("Multiply coordinates by").contains("to map to original image");
    }

    @Test
    @DisplayName("rv-b-r1 gap2 · 未 resize 小图不生成 metadata 消息（createImageMetadataText 返回 null）")
    void executeSmallImageHasNoMetadataMessage(@TempDir Path workspace) throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
            10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(img, "png", workspace.resolve("small.png").toFile());
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("small.png"), ctxWithSession(workspace));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(result.newMessages()).as("未 resize 且无 sourcePath → metadata 为 null，无 newMessages").isEmpty();
    }

    @Test
    @DisplayName("rv-b-r1 gap3 · code cell 输出经 processOutput（text/plain + image/png 白空格剥离 + error 格式 + source join）")
    void executeNotebookProcessesCellOutputs(@TempDir Path workspace) throws Exception {
        String notebookJson = "{\"cells\":[" +
            "{\"cell_type\":\"code\",\"execution_count\":2,\"source\":[\"print(\",\"'hi')\"],\"outputs\":[" +
                "{\"output_type\":\"execute_result\",\"data\":{\"text/plain\":[\"x\"],\"image/png\":\"  aGVs bG8=  \"}}," +
                "{\"output_type\":\"error\",\"ename\":\"ValueError\",\"evalue\":\"boom\",\"traceback\":[\"t1\",\"t2\"]}" +
            "],\"metadata\":{}}," +
            "{\"cell_type\":\"markdown\",\"source\":[\"# title\"]}" +
            "],\"metadata\":{\"language_info\":{\"name\":\"python\"}},\"nbformat\":4,\"nbformat_minor\":5}";
        Files.writeString(workspace.resolve("a.ipynb"), notebookJson);
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("a.ipynb"));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode data = (JsonNode) result.data();
        JsonNode cells = data.get("notebook_cells");
        assertThat(cells.size()).isEqualTo(2);

        JsonNode codeCell = cells.get(0);
        assertThat(codeCell.get("cellType").asText()).isEqualTo("code");
        assertThat(codeCell.get("cell_id").asText()).isEqualTo("cell-0");
        assertThat(codeCell.get("execution_count").asInt()).as("execution_count 仅 code cell 才带").isEqualTo(2);
        assertThat(codeCell.get("language").asText()).isEqualTo("python");
        assertThat(codeCell.get("source").asText()).as("source 数组 join('')").isEqualTo("print('hi')");

        JsonNode outputs = codeCell.get("outputs");
        assertThat(outputs.size()).isEqualTo(2);
        assertThat(outputs.get(0).get("output_type").asText()).isEqualTo("execute_result");
        assertThat(outputs.get(0).get("text").asText()).isEqualTo("x");
        assertThat(outputs.get(0).get("image").get("media_type").asText()).isEqualTo("image/png");
        assertThat(outputs.get(0).get("image").get("image_data").asText())
            .as("image/png 白空格剥离（CC extractImage replace(/\\s/g,'')）").isEqualTo("aGVsbG8=");
        assertThat(outputs.get(1).get("output_type").asText()).isEqualTo("error");
        assertThat(outputs.get(1).get("text").asText())
            .as("error → `${ename}: ${evalue}\\n${traceback.join('\\n')}`").isEqualTo("ValueError: boom\nt1\nt2");

        JsonNode mdCell = cells.get(1);
        assertThat(mdCell.get("cellType").asText()).isEqualTo("markdown");
        assertThat(mdCell.has("execution_count")).as("markdown cell 无 execution_count").isFalse();
        assertThat(mdCell.has("language")).as("markdown cell 无 language").isFalse();

        String rendered = data.get("notebook_rendered").asText();
        assertThat(rendered).contains("<cell id=\"cell-0\">print('hi')</cell id=\"cell-0\">");
        assertThat(rendered).contains("x");
    }

    @Test
    @DisplayName("OPD-TOOL-06-3c · 超大 notebook (cellsJson > 256KB) → error + Bash jq 提示 —— 对齐 CC FileReadTool.ts:826-835")
    void oversizedNotebookReturnsSizeErrorWithJqHint(@TempDir Path workspace) throws Exception {
        // WHY: 大 notebook 直读会爆 context；CC 不静默截断，而是报错并附 jq 4 条示例，
        //   引导模型用 Bash 读指定 cells。此处验证 size 上限 fail-loud + jq 提示逐字对齐。
        String bigSource = "x".repeat(300_000);
        String notebookJson = "{\"cells\":[{\"cell_type\":\"code\",\"source\":[\"" + bigSource
            + "\"],\"metadata\":{}}],\"metadata\":{},\"nbformat\":4,\"nbformat_minor\":5}";
        Files.writeString(workspace.resolve("big.ipynb"), notebookJson);
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("big.ipynb"));

        assertThat(result.data()).isInstanceOf(String.class);
        String msg = (String) result.data();
        assertThat(msg).contains("exceeds maximum allowed size");
        assertThat(msg).contains("Bash").contains("jq");
        // jq 4 条示例逐字对齐 CC FileReadTool.ts:831-834
        assertThat(msg).contains("cat \"big.ipynb\" | jq '.cells[:20]'");
        assertThat(msg).contains("cat \"big.ipynb\" | jq '.cells[100:120]'");
        assertThat(msg).contains("cat \"big.ipynb\" | jq '.cells | length'");
        assertThat(msg).contains("cat \"big.ipynb\" | jq '.cells[] | select(.cell_type==\"code\") | .source'");
    }

    @Test
    @DisplayName("OPD-TOOL-06-3c · 大 notebook (cellsJson > 100k 字符) → token 超限 error —— 对齐 CC validateContentTokens + MaxFileReadTokenExceededError")
    void oversizedNotebookReturnsTokenError(@TempDir Path workspace) throws Exception {
        // WHY: 即便字节 < 256KB（size 校验放行），字符 > 100k 仍会爆 token 上限；沿用 dispatchText
        //   字符近似口径（1 token ≈ 4 chars），保证 notebook 与 text 分支超限契约一致，而非各自为政。
        String bigSource = "x".repeat(150_000);
        String notebookJson = "{\"cells\":[{\"cell_type\":\"code\",\"source\":[\"" + bigSource
            + "\"],\"metadata\":{}}],\"metadata\":{},\"nbformat\":4,\"nbformat_minor\":5}";
        Files.writeString(workspace.resolve("big.ipynb"), notebookJson);
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("big.ipynb"));

        assertThat(result.data()).isInstanceOf(String.class);
        assertThat((String) result.data())
            .contains("tokens estimated")
            .contains("exceeds maximum allowed tokens");
    }

    @Test
    @DisplayName("GAP-E · .png 分支: bytes 读 → base64 → image 输出（metadata 携带 image_base64 + image_media_type）")
    void executePngOutputsImage(@TempDir Path workspace) throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};
        Files.write(workspace.resolve("tiny.png"), png);
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("tiny.png"));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode data = (JsonNode) result.data();
        assertThat(data.has("image_base64")).isTrue();
        assertThat(data.has("image_media_type")).isTrue();
        assertThat(data.get("image_media_type").asText()).isEqualTo("image/png");
        assertThat(data.get("summary").asText()).contains("image");
    }

    @Test
    @DisplayName("P-CC-01 · .pdf 无 pages: 真实 PDF → pdf 数据 (document_base64 + application/pdf) —— 对齐 CC :987-1016 readPDF 分支")
    void executePdfOutputsDocumentData(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 2);
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf"));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode data = (JsonNode) result.data();
        assertThat(data.get("read_file_output_type").asText()).isEqualTo("pdf");
        assertThat(data.get("document_media_type").asText()).isEqualTo("application/pdf");
        // readPDF 原样 base64 整个文件 (CC pdf.ts:88) — base64 解码后必须以 %PDF- 开头
        String base64 = data.get("document_base64").asText();
        byte[] decoded = java.util.Base64.getDecoder().decode(base64);
        assertThat(new String(decoded, 0, 5)).isEqualTo("%PDF-");
        assertThat(data.get("summary").asText()).contains("PDF file read").contains("doc.pdf");
    }

    @Test
    @DisplayName("P-CC-01 · .pdf 空文件 → error empty —— 对齐 CC pdf.ts:50-55")
    void executePdfEmptyFileReturnsError(@TempDir Path workspace) throws Exception {
        Files.write(workspace.resolve("doc.pdf"), new byte[0]);
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf"));

        assertThat(result.data()).isInstanceOf(String.class);
        assertThat((String) result.data()).contains("empty");
    }

    @Test
    @DisplayName("P-CC-01 · .pdf 缺 %PDF- magic → error corrupted —— 对齐 CC pdf.ts:77-86（HTML 改名 .pdf 拒入对话）")
    void executePdfMissingMagicHeaderReturnsError(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("doc.pdf"), "<html>not a pdf</html>");
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf"));

        assertThat(result.data()).isInstanceOf(String.class);
        assertThat((String) result.data()).contains("not a valid PDF");
    }

    @Test
    @DisplayName("P-CC-01 · .pdf 超 20MB → error too_large —— 对齐 CC apiLimits.ts:54 PDF_TARGET_RAW_SIZE")
    void executePdfTooLargeReturnsError(@TempDir Path workspace) throws Exception {
        // 20MB + 1B 越过 PDF_TARGET_RAW_SIZE；size 检查先于 magic 检查 (CC pdf.ts:60-68)
        Files.write(workspace.resolve("doc.pdf"), new byte[20 * 1024 * 1024 + 1]);
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf"));

        assertThat(result.data()).isInstanceOf(String.class);
        assertThat((String) result.data()).contains("maximum allowed size");
    }

    @Test
    @DisplayName("P-CC-01 · .pdf >10 页且无 pages → error 提示用 pages —— 对齐 CC :949-955 PDF_AT_MENTION_INLINE_THRESHOLD")
    void executePdfTooManyPagesReturnsError(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 11);
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf"));

        assertThat(result.data()).isInstanceOf(String.class);
        assertThat((String) result.data()).contains("too many to read at once");
    }

    @Test
    @DisplayName("P-CC-01 · .pdf pages=1-2 → parts 提取 2 页 JPEG —— 对齐 CC :895-946 extractPDFPages 分支")
    void executePdfPagesExtractsPageImages(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 3);
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSession(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf", "pages", "1-2"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode data = (JsonNode) result.data();
        assertThat(data.get("read_file_output_type").asText()).isEqualTo("parts");
        assertThat(data.get("read_file_parts_count").asInt()).isEqualTo(2);
        // 页图已渲染到 outputDir (page-01.jpg, page-02.jpg, CC pdf.ts:222-230 命名)
        Path outputDir = Path.of(data.get("read_file_parts_output_dir").asText());
        assertThat(Files.isDirectory(outputDir)).isTrue();
        try (var stream = Files.list(outputDir)) {
            assertThat(stream.filter(p -> p.toString().endsWith(".jpg")).count()).isEqualTo(2);
        }
        assertThat(data.get("summary").asText())
            .contains("PDF pages extracted").contains("2 page(s)");
    }

    @Test
    @DisplayName("P-CC-01 · .pdf pages 非法字符串 → 全量提取 (CC :896 parsedRange ?? undefined)")
    void executePdfInvalidPagesFallsBackToFullExtraction(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 2);
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSession(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf", "pages", "abc"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode data = (JsonNode) result.data();
        assertThat(data.get("read_file_output_type").asText()).isEqualTo("parts");
        assertThat(data.get("read_file_parts_count").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("[pdf-vision-align 纠正] 文本模型 + 全读 PDF → fail-loud error（不发 document/image block，对齐 CC isPDFSupported throw）")
    void textModelPdfFull_returnsFailLoudError(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 2);
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = haikuModelCtx(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf"), ctx);

        assertThat(result.data())
            .as("文本模型全读 PDF → 引导文本 data（非 parts/JsonNode —— 不发 document/image block）")
            .isInstanceOf(String.class);
        String data = (String) result.data();
        assertThat(data)
            .as("错误含模型不支持 image/PDF + 范围 + path；引导 vision_analyze(contentType=pdf, path=绝对路径) 直达（非派多模态子代理）")
            .contains("当前模型不支持 image/PDF 视觉")
            .contains("完整 PDF")
            .contains("doc.pdf")
            .contains("vision_analyze(type=analyze, contentType=pdf, path=")
            .contains("pages=[要分析的页号数组]")
            .doesNotContain("多模态子代理")
            .doesNotContain("multimodalModelName");
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("对抗核验 #3：error 文案以 'Error: ' 识别前缀开头 → is_error=true（fail loud + hook/analytics 正确）")
            .isTrue();
        assertThat(result.newMessages())
            .as("文本模型不发 document/image block newMessages（deepseek 400 根因防线）")
            .isNullOrEmpty();
    }

    @Test
    @DisplayName("[vision-cc-align 2026-09-03] 文本模型 + .png 图 → fail-loud 引导 vision_analyze(path)（不发 image block —— 原 bug：模型 Read 空图死循环）")
    void textModelImage_returnsFailLoudErrorGuidesToVisionAnalyze(@TempDir Path workspace) throws Exception {
        // 文件只需存在：dispatchImage 文本模型门控前置（gate 在 Files.readAllBytes 前），不读真实图片内容
        Files.write(workspace.resolve("shot.png"),
            new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0});
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = haikuModelCtx(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("shot.png"), ctx);

        assertThat(result.data())
            .as("文本模型图片 → 引导文本 data（非 parts/JsonNode —— 不发 image block，deepseek 空读根因）")
            .isInstanceOf(String.class);
        String data = (String) result.data();
        assertThat(data)
            .as("错误含模型不支持 + path；引导 vision_analyze(type=analyze, path=…)（非派子代理/非 contentId）")
            .contains("当前模型不支持 Read 直接显示图像")
            .contains("shot.png")
            .contains("vision_analyze(type=analyze, path=")
            .doesNotContain("多模态子代理")
            .doesNotContain("contentId");
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("error 文案以 'Error: ' 识别前缀开头 → is_error=true（fail loud）")
            .isTrue();
        assertThat(result.newMessages())
            .as("文本模型图片分支不发 image block newMessages（deepseek 400/空读根因防线）")
            .isNullOrEmpty();
    }

    @Test
    @DisplayName("[pdf-vision-align 纠正] 文本模型 + pages=1-2 → fail-loud error（不发 image block —— 原 bug：渲染页图发 deepseek → 400）")
    void textModelPdfPages_returnsFailLoudError(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 3);
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = haikuModelCtx(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf", "pages", "1-2"), ctx);

        assertThat(result.data())
            .as("文本模型 pages → 引导文本 data（非 parts/JsonNode —— 不发 image block newMessages）")
            .isInstanceOf(String.class);
        String data = (String) result.data();
        assertThat(data)
            .contains("当前模型不支持 image/PDF 视觉")
            .contains("PDF 页图（pages=1-2）")
            .contains("doc.pdf")
            .contains("vision_analyze(type=analyze, contentType=pdf, path=")
            .doesNotContain("多模态子代理")
            .doesNotContain("multimodalModelName");
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("对抗核验 #3：error 文案以 'Error: ' 识别前缀开头 → is_error=true")
            .isTrue();
        assertThat(result.newMessages())
            .as("文本模型 pages 分支不发 image block newMessages（deepseek 400 根因防线）")
            .isNullOrEmpty();
    }

    @Test
    @DisplayName("[vision-cc-align 2026-09-03] 文本模型 + pages → fail-loud 引导 vision_analyze(contentType=pdf, path, pages) 直达（删派多模态子代理递归源）")
    void textModelPdfPages_errorGuidesToVisionAnalyze(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 3);
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = haikuModelCtx(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf", "pages", "1-2"), ctx);

        assertThat(result.data())
            .as("data 为引导文本（非 parts/JsonNode —— 不发 image block）")
            .isInstanceOf(String.class);
        String data = (String) result.data();
        assertThat(data)
            // [vision-cc-align 2026-09-03] 递归根因修复：不再引导派多模态子代理（fork 继承文本模型 →
            //   Read 再触发门控 → 无限递归）。改为引导 vision_analyze 直达（工具内部懒渲染 pages → 调多模态
            //   档位模型 → 返回文本），path 传绝对路径（vision_analyze resolvePath 直读，无 cwd 歧义）。
            .as("错误含模型不支持 + 范围 + path；引导 vision_analyze(contentType=pdf, path=绝对路径, pages=…)；不含 Agent 子代理/动态档位名")
            .contains("当前模型不支持 image/PDF 视觉")
            .contains("PDF 页图（pages=1-2）")
            .contains("doc.pdf")
            .contains("vision_analyze(type=analyze, contentType=pdf, path=")
            .contains("pages=[要分析的页号数组]")
            .contains("分段分析该 PDF")
            .doesNotContain("多模态子代理")
            .doesNotContain("Agent 工具")
            .doesNotContain("multimodalModelName");
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("对抗核验 #3：error 文案以 'Error: ' 识别前缀开头 → is_error=true")
            .isTrue();
        assertThat(result.newMessages())
            .as("文本模型 pages 分支仍不发 image block newMessages（deepseek 400 根因防线）")
            .isNullOrEmpty();
    }

    // ═════════════ [CC 对齐 2026-09-03] PathGuard 逃逸拦截已删 · 绝对路径直接可读（对齐 CC expandPath）═════════════

    @Test
    @DisplayName("CC 对齐：cwd 外绝对路径 PDF 直接可读（PathGuard 逃逸拦截已删，原附件表豁免机制整体删除）")
    void absolutePathOutsideWorkspaceDirectlyReadable(@TempDir Path workspace, @TempDir Path outsideDir) throws Exception {
        // 模拟附件/系统文件（Desktop/pdf-cache）位于会话 cwd 之外——CC 语义：绝对路径显式指定即可读
        // （FileReadTool.ts:443 仅 expandPath，无逃逸检查；日志实证 2026-09-03 blocked path escape 误伤附件）
        writeRealPdf(outsideDir, "attach.pdf", 2);
        Path pdf = outsideDir.resolve("attach.pdf");
        ReadFileTool tool = toolWithModelType(workspace, "vision", "vision-exp");
        ToolUseContext ctx = ctxWithSessionAndModel(workspace, "vision-exp");

        ToolResult result = (ToolResult) tool.execute(callWith(pdf.toAbsolutePath().toString()), ctx);

        assertThat(String.valueOf(result.data()))
            .as("绝对路径（cwd 外）应直接可读——不再有 'escapes workspace' 逃逸拦截（对齐 CC）")
            .doesNotContain("escapes workspace");
    }

    // ═════════════ [pdf-vision-align 对抗核验 #2] 生产 3 参路径 · ModelMapper/ProviderMapper 注入（真实 DB type 判定）═════════════

    /** 构造 type 指定的 ModelRecord（mock selectListByQuery 返回；name 与 ctx appState mainLoopModel 对应）。 */
    private static ModelRecord modelRecordOfType(String type, String name) {
        ModelRecord m = new ModelRecord();
        m.setId("m1");
        m.setProviderId("p1");
        m.setName(name);
        m.setType(type);
        m.setEnabled(true);
        return m;
    }

    /** [pdf-vision-align 对抗核验 #2] 注入 mapper 的 ReadFileTool：mock selectListByQuery 返回 type 记录。
     *  modelName 无 '/' → ModelNameResolver 走兼容路径 selectListByQuery（mock 已 stub）。
     *  [vision-cc-align 2026-09-03] providerMapper 补 stub selectOneById→anthropic：Read 直给判据升级为
     *  ant 直给格式 && supportsImage（canModelViewReadResultDirectly 经 ContextUsageCalculator.isAnthropic
     *  查 provider.type==anthropic）——vision 测试（type=multimodal, claude-sonnet-4-6）须真 ant 才直给。 */
    private static ReadFileTool toolWithModelType(Path workspace, String type, String modelName) {
        ReadFileTool tool = toolFor(workspace);
        ModelMapper modelMapper = Mockito.mock(ModelMapper.class);
        when(modelMapper.selectListByQuery(any())).thenReturn(List.of(modelRecordOfType(type, modelName)));
        tool.setModelMapper(modelMapper);
        ProviderMapper providerMapper = Mockito.mock(ProviderMapper.class);
        ProviderRecord provider = new ProviderRecord();
        provider.setId("p1");
        provider.setType("anthropic");
        when(providerMapper.selectOneById(any())).thenReturn(provider);
        tool.setProviderMapper(providerMapper);
        return tool;
    }

    @Test
    @DisplayName("[对抗核验 #2] 3 参生产路径：type=chat（deepseek DB 实值）→ 全读 PDF fail-loud error + isToolErrorData=true + 无 newMessages")
    void textModelPdfFull_threeParamChatType_failLoudError(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 2);
        ReadFileTool tool = toolWithModelType(workspace, "chat", "deepseek-chat");
        ToolUseContext ctx = ctxWithSessionAndModel(workspace, "deepseek-chat");

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf"), ctx);

        assertThat(result.data()).isInstanceOf(String.class);
        assertThat((String) result.data())
            .as("3 参 type=chat（deepseek）→ 引导文本 error（含 model=deepseek-chat，非 1 参 haiku 名字契约路径）")
            .contains("Error: 当前模型不支持 image/PDF 视觉")
            .contains("model=deepseek-chat")
            .contains("完整 PDF");
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("对抗核验 #3：error 文案必须以 isToolErrorData 识别前缀开头 → is_error=true（fail loud + hook/analytics 正确）")
            .isTrue();
        assertThat(result.newMessages())
            .as("文本模型不发 document/image block newMessages（deepseek 400 根因防线）")
            .isNullOrEmpty();
    }

    @Test
    @DisplayName("[对抗核验 #2] 3 参生产路径：type=chat → pages 读 PDF fail-loud error + isToolErrorData=true + 无 image block newMessages")
    void textModelPdfPages_threeParamChatType_failLoudError(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 3);
        ReadFileTool tool = toolWithModelType(workspace, "chat", "deepseek-chat");
        ToolUseContext ctx = ctxWithSessionAndModel(workspace, "deepseek-chat");

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf", "pages", "1-2"), ctx);

        assertThat(result.data()).isInstanceOf(String.class);
        assertThat((String) result.data())
            .as("3 参 type=chat pages → 引导文本 error（含 pages 范围，不渲染页图）")
            .contains("Error: 当前模型不支持 image/PDF 视觉")
            .contains("PDF 页图（pages=1-2）")
            .contains("model=deepseek-chat");
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isTrue();
        assertThat(result.newMessages()).isNullOrEmpty();
    }

    @Test
    @DisplayName("[vision-cc-align 2026-09-03] 判据升级：type=vision 但 openai provider（deepseek-vision-exp）→ pages 引导 vision_analyze（多模态≠Read 直给，格式也须 ant）")
    void visionExpOpenAiProvider_pages_guidesToVisionAnalyzeNotDirect(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 3);
        ReadFileTool tool = toolFor(workspace);
        ModelMapper modelMapper = Mockito.mock(ModelMapper.class);
        when(modelMapper.selectListByQuery(any())).thenReturn(
            List.of(modelRecordOfType("vision", "deepseek-vision-exp")));
        tool.setModelMapper(modelMapper);
        ProviderMapper providerMapper = Mockito.mock(ProviderMapper.class);
        ProviderRecord provider = new ProviderRecord();
        provider.setId("p1");
        provider.setType("openai");
        when(providerMapper.selectOneById(any())).thenReturn(provider);
        tool.setProviderMapper(providerMapper);
        ToolUseContext ctx = ctxWithSessionAndModel(workspace, "deepseek-vision-exp");

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf", "pages", "1-2"), ctx);

        assertThat(result.data())
            .as("deepseek-vision-exp：多模态（type=vision）但 openai-completions 非 ant 直给格式 → 引导（不直给页图）")
            .isInstanceOf(String.class);
        String data = (String) result.data();
        assertThat(data)
            .contains("Error: 当前模型不支持 image/PDF 视觉")
            .contains("model=deepseek-vision-exp")
            .contains("vision_analyze(type=analyze, contentType=pdf, path=")
            .doesNotContain("image blocks");
        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("error 文案以 'Error: ' 前缀开头 → is_error=true（fail loud）")
            .isTrue();
        assertThat(result.newMessages())
            .as("非 ant 直给格式不发 image block newMessages（格式不支持 Read 带图）")
            .isNullOrEmpty();
    }

    @Test
    @DisplayName("[对抗核验 #2] 3 参生产路径：type=multimodal → 全读 PDF document block newMessages 正常（不误拒）")
    void visionModelPdfFull_threeParamMultimodal_deliversDocumentBlock(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 2);
        ReadFileTool tool = toolWithModelType(workspace, "multimodal", "claude-sonnet-4-6");
        ToolUseContext ctx = ctxWithSessionAndModel(workspace, "claude-sonnet-4-6");

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("type=multimodal → 不走 error 分支（3 参 supportsImage=true）")
            .isFalse();
        assertThat(result.data()).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) result.data()).path("read_file_output_type").asText()).isEqualTo("pdf");
        List<ChatMessageDto> newMessages = result.newMessages();
        assertThat(newMessages)
            .as("type=multimodal → document block newMessages 正常送达（CC FileReadTool.ts:999-1016）")
            .hasSize(1);
        ChatMessageDto meta = newMessages.get(0);
        assertThat(meta.isMeta()).isTrue();
        assertThat(meta.role()).isEqualTo(Role.user);
        JsonNode docBlock = (JsonNode) meta.contentBlocks().get(0);
        assertThat(docBlock.get("type").asText()).isEqualTo("document");
        assertThat(docBlock.get("source").get("media_type").asText()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("[对抗核验 #2] 3 参生产路径：type=multimodal → pages 读 PDF image blocks newMessages 正常")
    void visionModelPdfPages_threeParamMultimodal_deliversImageBlocks(@TempDir Path workspace) throws Exception {
        writeRealPdf(workspace, "doc.pdf", 3);
        ReadFileTool tool = toolWithModelType(workspace, "multimodal", "claude-sonnet-4-6");
        ToolUseContext ctx = ctxWithSessionAndModel(workspace, "claude-sonnet-4-6");

        ToolResult result = (ToolResult) tool.execute(callWith("doc.pdf", "pages", "1-2"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("type=multimodal pages → 不走 error 分支")
            .isFalse();
        assertThat(result.data()).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) result.data()).path("read_file_output_type").asText()).isEqualTo("parts");
        List<ChatMessageDto> newMessages = result.newMessages();
        assertThat(newMessages).hasSize(1);
        List<?> blocks = newMessages.get(0).contentBlocks();
        assertThat(blocks)
            .as("2 页提取 → 2 个 image blocks")
            .hasSize(2);
        JsonNode block0 = (JsonNode) blocks.get(0);
        assertThat(block0.get("type").asText()).isEqualTo("image");
        assertThat(block0.get("source").get("media_type").asText()).isEqualTo("image/jpeg");
    }

    /** [pdf-vision-align 纠正] 文本模型上下文：appState mainLoopModel=claude-3-haiku（mappers 未注入 → 1 参回落 false → 文本模型分支）。 */
    private static ToolUseContext haikuModelCtx(Path workspace) {
        UUID agentId = UUID.nameUUIDFromBytes(("rft-haiku-agent-" + workspace).getBytes());
        String sessionId = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        return ToolUseContext.of(
            agentId, sessionId, PermissionMode.DEFAULT,
            java.util.List.<com.nexusai.application.agent.tool.Tool>of(),
            "", AbortController.NOOP,
            java.util.List.<Object>of(),
            null, PermissionMode.DEFAULT,
            java.util.Map.<String, com.nexusai.application.agent.tool.McpClientRuntime>of(),
            false, "", null,
            null,
            java.util.Map.<String, com.nexusai.application.agent.tool.ToolDecisionInfo>of(),
            null,
            state -> state == null
                ? java.util.Map.<String, Object>of("mainLoopModel", "claude-3-haiku")
                : state,
            updater -> {
            }, m -> {
            }, s -> {
            });
    }

    /** 用 pdfbox 生成真实 PDF（P-CC-01 拍板引入依赖后的测试夹具）。 */
    private static Path writeRealPdf(Path workspace, String name, int pages) throws Exception {
        Path file = workspace.resolve(name);
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            }
            doc.save(file.toFile());
        }
        return file;
    }

    @Test
    @DisplayName("GAP-E · .txt 文本分支: 正常 offset/limit window —— 不该破坏既有行为")
    void executeTextWindow(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a\nb\nc\nd\ne\n");
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("a.txt", "offset", 2, "limit", 2));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        String body = lineNumberBody(tool, result);
        assertThat(body).isEqualTo("2\tb\n3\tc");
    }

    @Test
    @DisplayName("[RV-06] dispatchText 输出含 cat -n 行号前缀 —— 对齐 CC addLineNumbers (utils/file.ts:290-319)")
    void dispatchTextOutputHasLineNumbers(@TempDir Path workspace) throws Exception {
        ReadFileTool tool = toolFor(workspace);

        // full read (offset 默认 1): "hello\n" → "1\thello\n2\t" + reminder（startLine=offset=1，compact N\t；split("\n",-1) 保留尾空行号，对齐 CC split(/\r?\n/)）
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        ToolResult full = (ToolResult) tool.execute(callWith("a.txt"));
        assertThat(LlmAgentLoop.isToolErrorData(full.data())).isFalse();
        assertThat(textContent(tool, full))
            .as("full read 输出 = 行号前缀内容 + reminder（startLine=offset=1 默认，compact N\\t）")
            .isEqualTo("1\thello\n2\t" + ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);

        // 窗口读 offset=2 limit=2: "a\nb\nc\nd\ne\n" → 前缀 "2\tb\n3\tc"（startLine=offset=2；join('\n') 无尾换行，对齐 CC readFileInRange.ts:184）
        Files.writeString(workspace.resolve("b.txt"), "a\nb\nc\nd\ne\n");
        ToolResult window = (ToolResult) tool.execute(callWith("b.txt", "offset", 2, "limit", 2));
        assertThat(LlmAgentLoop.isToolErrorData(window.data())).isFalse();
        String body = lineNumberBody(tool, window);
        assertThat(body)
            .as("窗口读行号起点 = offset(2) 非 0 非 1（对齐 CC FileReadTool.ts:497/1052 startLine=offset）")
            .isEqualTo("2\tb\n3\tc");
    }

    // ═════════════ GAP-T5: FileReadListener 通知契约 ═════════════

    @Test
    @DisplayName("GAP-T5 · text 分支成功后 listener 收到通知 —— 对齐 CC :1040-1044 仅 text 通知")
    void textBranchNotifiesListener(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello");
        // 直接构造 ReadFileTool + ListenerRegistry 走 wire 全路径
        com.nexusai.application.agent.tool.FileReadListenerRegistry registry =
            new com.nexusai.application.agent.tool.FileReadListenerRegistry();
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace), registry);

        java.util.List<ReadNotification> received =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Runnable unsubscribe = registry.register((path, content) -> received.add(new ReadNotification(path, content)));

        try {
            tool.execute(callWith("a.txt"));

            assertThat(received).hasSize(1);
            assertThat(received.get(0).path()).isEqualTo("a.txt");
            assertThat(received.get(0).content()).isEqualTo("hello");
        } finally {
            unsubscribe.run();
        }
    }

    @Test
    @DisplayName("[IMP-C5][W6] listener 收到 range 内容（窗口读）而非全文件 —— 对齐 CC :1040-1044 + magicDocs.ts:245-250")
    void listenerReceivesRangeContent(@TempDir Path workspace) throws Exception {
        // WHY: CC FileReadTool.ts:1040-1044 listener(resolvedFilePath, content) 的 content
        // 是 readFileInRange 返回的<b>窗口内容</b>（range），Java 原传全文件 content（△-29/R2）。
        // MagicDocs 侧 detectMagicDocHeader(content) 语义依赖此 range 内容。
        Files.writeString(workspace.resolve("a.txt"), "a\nb\nc\nd\ne\n");
        com.nexusai.application.agent.tool.FileReadListenerRegistry registry =
            new com.nexusai.application.agent.tool.FileReadListenerRegistry();
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace), registry);

        java.util.List<ReadNotification> received =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Runnable unsubscribe = registry.register((path, content) -> received.add(new ReadNotification(path, content)));

        try {
            // 窗口读 offset=2 limit=2 → 本次读取的 range 内容 = "b\nc"（非全文件 "a\nb\nc\nd\ne\n"）
            tool.execute(callWith("a.txt", "offset", 2, "limit", 2));

            assertThat(received).hasSize(1);
            assertThat(received.get(0).path()).isEqualTo("a.txt");
            assertThat(received.get(0).content()).isEqualTo("b\nc");
        } finally {
            unsubscribe.run();
        }
    }

    @Test
    @DisplayName("GAP-T5 · image/notebook/pdf/error 分支不通知 listener —— 对齐 CC image/PDF 不入 readFileState + 不通知")
    void nonTextBranchesDoNotNotify(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.ipynb"),
            "{\"cells\":[{\"cell_type\":\"code\",\"source\":[\"x\"],\"metadata\":{}}]," +
            "\"metadata\":{},\"nbformat\":4,\"nbformat_minor\":5}");
        writeRealPdf(workspace, "doc.pdf", 2);
        com.nexusai.application.agent.tool.FileReadListenerRegistry registry =
            new com.nexusai.application.agent.tool.FileReadListenerRegistry();
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace), registry);

        java.util.List<ReadNotification> received =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        registry.register((path, content) -> received.add(new ReadNotification(path, content)));

        tool.execute(callWith("a.ipynb"));
        // [P-CC-01] PDF 分支成功（pdf 数据）也不通知 —— 对齐 CC :1040-1044 仅 text 通知
        tool.execute(callWith("doc.pdf"));

        // notebook/pdf 分支成功也不通知 —— 对齐 CC image/PDF 不在 readFileState 也不通知
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("[L+ GAP-A] listener 抛异常 → execute 返回 error result + 后续 listener 不调用 —— 对齐 CC :1042 裸调用 fail-fast")
    void listenerExceptionPropagates(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        com.nexusai.application.agent.tool.FileReadListenerRegistry registry =
            new com.nexusai.application.agent.tool.FileReadListenerRegistry();
        ReadFileTool tool = new ReadFileTool(new PathGuard(workspace), registry);

        java.util.List<ReadNotification> received =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        // 第一个 listener 抛异常 (CC FileReadTool.ts:1042 裸调用 → 中断后续 listener)
        Runnable unsub1 = registry.register((path, content) -> {
            throw new IllegalStateException("listener boom");
        });
        Runnable unsub2 = registry.register((path, content) -> received.add(new ReadNotification(path, content)));

        try {
            ToolResult result = (ToolResult) tool.execute(callWith("a.txt"));
            // 裸调用 → 异常向上传播 → executeInternal 统一 catch → ToolResult.error (fail-loud)。
            // [IMP-C5] 错误 data 为 String 错误文案（"Read error: ..." 不在 isToolErrorData 前缀集，
            //   直接断言错误消息文本以锁死 fail-loud 语义，避免依赖共享启发式）。
            assertThat(result.data())
                .as("listener 异常必须 fail-loud (error result), 不能静默吞掉")
                .isInstanceOf(String.class);
            assertThat((String) result.data()).contains("listener boom");
            // 后续 listener 不被调用 (CC 中断语义)
            assertThat(received)
                .as("第一个 listener 抛异常后, 后续 listener 不得被调用 (CC :1042-1044 裸调用)")
                .isEmpty();
        } finally {
            unsub1.run();
            unsub2.run();
        }
    }

    // ═════════════════ GAP-B: checkPermissions 依赖缺失 fail-loud ═════════════════

    @Test
    @DisplayName("[Session M.4.4 收尾] GAP-B · permissionChecker 未注入 → fail-loud ISE, 不再静默 Allow")
    void checkPermissions_nullPermissionChecker_failsLoud() {
        ReadFileTool tool = toolFor(Path.of("."));

        assertThatThrownBy(() -> tool.checkPermissions(JSON.createObjectNode(), null))
            .as("依赖缺失静默 Allow = Pattern #11 门禁绕过; 对齐 ReadFileTool fail-loud ISE 模式")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("permissionChecker 未注入");
    }

    // ═════════════ 既有契约不被破坏 ═════════════

    @Test
    @DisplayName("[CC 对齐 2026-09-03] 相对路径逃逸(../../)不再拦截——resolve 纯展开，文件不存在返回 error")
    void executePathEscape_noLongerIntercepted(@TempDir Path workspace) {
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("../../etc/passwd"));

        assertThat(result.data())
            .as("PathGuard 逃逸拦截已删（对齐 CC）：../../ 展开后读文件，不存在返回 String error（非 escape 拦截）")
            .isInstanceOf(String.class);
        assertThat(String.valueOf(result.data()))
            .doesNotContain("escapes workspace");
    }

    @Test
    @DisplayName("[P-CC-01] inputSchema 声明 file_path+offset+limit+pages —— 对齐 CC FileReadTool.ts:236-241")
    void inputSchemaDeclaresPages() {
        ReadFileTool tool = toolFor(Path.of("."));

        var props = tool.inputSchema().path("properties");
        assertThat(props.has("file_path")).isTrue();
        assertThat(props.has("offset")).isTrue();
        assertThat(props.has("limit")).isTrue();
        assertThat(props.has("pages"))
            .as("P-CC-01 拍板对齐 CC：PDF 解析已实现（pdfbox），pages 必须声明（FileReadTool.ts:236-241）")
            .isTrue();
        assertThat(props.get("pages").get("description").asText())
            .as("pages 描述必须携带 CC 的 PDF 范围示例与单次上限说明")
            .contains("Page range for PDF files");
    }

    @Test
    @DisplayName("[GAP-D 严格对齐] full read 后二次 full read → file_unchanged (CC :553 rangeMatch, undefined===undefined)")
    void fullReadAfterFullReadDedups(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSession(workspace);

        ToolResult first = (ToolResult) tool.execute(callWith("a.txt"), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(first.data())).isFalse();
        assertThat(textContent(tool, first))
            .as("[P-AL-06] 非豁免模型 text 结果 = 内容 + CYBER_RISK_MITIGATION_REMINDER（CC FileReadTool.ts:699-701）")
            .isEqualTo("1\thello\n2\t" + ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);

        // [GAP-D 严格对齐 2026-08-04] 撤销 L+ GAP-B 语义: CC :497 调用侧 offset=1 默认,
        //   full read = offset=1 + limit=undefined; entry 侧 :550 offset!==undefined 通过;
        //   :553 rangeMatch undefined===undefined → 命中 → 二次 full read 返回 file_unchanged.
        //   (L+ GAP-B "CC full read offset=undefined" 系错误理解, 实际 offset 恒为 1.)
        ToolResult second = (ToolResult) tool.execute(callWith("a.txt"), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(second.data())).isFalse();
        assertThat(second.data())
            .as("full read 后二次 full read 命中 dedup, 返回 file_unchanged (CC :553)")
            .isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) second.data()).get("read_file_output_type").asText())
            .isEqualTo("file_unchanged");
    }

    @Test
    @DisplayName("[L+ GAP-B] 窗口读同 range 二次读 → file_unchanged —— 这才是 CC 语义的 dedup 场景 (:552-553 rangeMatch)")
    void windowReadSameRangeDedupHits(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a\nb\nc\nd\ne\n");
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSession(workspace);

        // 显式 offset/limit 窗口读 → entry (mtime, 1, 2) 参与 dedup (CC :547-553)
        ToolResult first = (ToolResult) tool.execute(callWith("a.txt", "offset", 1, "limit", 2), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(first.data())).isFalse();

        // 同 range 二次读 + mtime 未变 → rangeMatch 命中 → file_unchanged
        ToolResult second = (ToolResult) tool.execute(callWith("a.txt", "offset", 1, "limit", 2), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(second.data())).isFalse();
        JsonNode data = (JsonNode) second.data();
        assertThat(data.get("summary").asText()).contains("unchanged");
        assertThat(data.get("read_file_output_type").asText()).isEqualTo("file_unchanged");
    }

    /**
     * [L+ R1 收尾] 无 ctx 路径必须<b>完全跳过 dedup</b> —— 对齐 CC readFileState 仅 ctx 持有.
     *
     * <p>WHY 显式锁死这条不变量（规则九 · 验证意图而非行为）: 早期 R1 半成品保留
     * 实例级 fallbackMap 让 execute(call) 也能 dedup。该路径(1) 单例多 session 共享
     * → 跨 session 误判；(2) 无 ctx 调用方（测试直调等）仅单次读, 根本
     * 不需要 dedup；(3) CC 端无 fallback 对应物。R1 收尾彻底删 fallback 后, 本测试
     * 锁死"无 ctx → 必走 full read"作为永不回退的契约。
     */
    @Test
    @DisplayName("R1 收尾 · noCtxSkipsDedup: 无 ctx 两次连读, 第二次仍 full read (不 dedup) —— 单例多 session 不能复用缓存")
    void noCtxSkipsDedup(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        ReadFileTool tool = toolFor(workspace);

        // 两次都用无 ctx 重载 → 既不读也不写 dedup cache, 每次都 full read
        ToolResult first = (ToolResult) tool.execute(callWith("a.txt"));
        assertThat(LlmAgentLoop.isToolErrorData(first.data())).isFalse();
        assertThat(textContent(tool, first))
            .as("[P-AL-06] 无 ctx full read 同样附加 reminder（注入与 ctx 无关，CC 渲染层恒附加）")
            .isEqualTo("1\thello\n2\t" + ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);

        ToolResult second = (ToolResult) tool.execute(callWith("a.txt"));
        assertThat(LlmAgentLoop.isToolErrorData(second.data())).isFalse();
        // 必须不是 file_unchanged (没 ctx = 永远不 dedup), 必须返回真实内容
        assertThat(second.data())
            .as("无 ctx 路径无 dedup, 第二次不该返回 file_unchanged")
            .isNotInstanceOf(JsonNode.class);
        assertThat(textContent(tool, second))
            .as("无 ctx 路径两次都 full read, 第二次应返回真实内容 + reminder")
            .isEqualTo("1\thello\n2\t" + ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);
    }


    // ═════════════ [P-AL-06] CYBER_RISK_MITIGATION_REMINDER (CC FileReadTool.ts:729-738) ═════════════

    /**
     * [P-AL-06] 构造带 appState mainLoopModel 的 ctx —— 20 参注入方式（Java appStateRef 语义，
     * 同 haikuModelCtx 的 mainLoopModel 键来源）。
     */
    private static ToolUseContext ctxWithSessionAndModel(Path workspace, String mainLoopModel) {
        return ToolUseContext.of(
            UUID.nameUUIDFromBytes(("rft-agent-" + workspace).getBytes()),
            "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT,
            java.util.List.<com.nexusai.application.agent.tool.Tool>of(),
            "", AbortController.NOOP,
            java.util.List.<Object>of(),
            null, PermissionMode.DEFAULT,
            java.util.Map.<String, com.nexusai.application.agent.tool.McpClientRuntime>of(),
            false, "", null,
            null,
            java.util.Map.<String, com.nexusai.application.agent.tool.ToolDecisionInfo>of(),
            null,
            state -> state == null
                ? java.util.Map.<String, Object>of("mainLoopModel", mainLoopModel)
                : state,
            updater -> {
            }, m -> {
            }, s -> {
            });
    }

    @Test
    @DisplayName("[P-AL-06] text 读取附加 CYBER_RISK_MITIGATION_REMINDER —— CC FileReadTool.ts:699-701 text case 模型侧序列化")
    void textBranchAppendsCyberRiskReminder(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("a.txt"));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(textContent(tool, result))
            .as("非豁免模型 text 读取 = 内容 + reminder（顺序与 CC :698-701 拼接一致）")
            .isEqualTo("1\thello\n2\t" + ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);
    }

    @Test
    @DisplayName("[P-AL-06] 豁免模型 claude-opus-4-6 → 不附加 reminder —— CC :733/735-738 MITIGATION_EXEMPT_MODELS")
    void textBranchSkipsReminderForExemptModel(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSessionAndModel(workspace, "claude-opus-4-6");

        ToolResult result = (ToolResult) tool.execute(callWith("a.txt"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(textContent(tool, result))
            .as("豁免模型数据必须保持纯净（无 <system-reminder>），但行号仍渲染")
            .isEqualTo("1\thello\n2\t");
    }

    @Test
    @DisplayName("[P-AL-06] 豁免模型日期后缀变体 (claude-opus-4-6-20250805) → 不附加 —— CC firstPartyNameToCanonical :221-222 includes 语义")
    void textBranchSkipsReminderForExemptModelWithDateSuffix(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSessionAndModel(workspace, "claude-opus-4-6-20250805");

        ToolResult result = (ToolResult) tool.execute(callWith("a.txt"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(textContent(tool, result)).isEqualTo("1\thello\n2\t");
    }

    @Test
    @DisplayName("[P-AL-06] 非豁免模型 claude-sonnet-4-6 → 附加 reminder（CC getMainLoopModel 默认 Sonnet 非豁免 ≡ 恒注入）")
    void textBranchAppendsReminderForNonExemptModel(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSessionAndModel(workspace, "claude-sonnet-4-6");

        ToolResult result = (ToolResult) tool.execute(callWith("a.txt"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(textContent(tool, result))
            .isEqualTo("1\thello\n2\t" + ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);
    }

    @Test
    @DisplayName("[P-AL-06][IMP-C5] 空文件走 CC :703-707 warning 分支（非空内容才附加 reminder）")
    void emptyFileSkipsReminder(@TempDir Path workspace) throws Exception {
        Files.write(workspace.resolve("empty.txt"), new byte[0]);
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("empty.txt"));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        // [IMP-C5] 对齐 CC FileReadTool.ts:703-707：data.file.content falsy → warning 分支。
        //   空文件 Java split("\n",-1) totalLines=1（与 CC readFileInRange lineIndex=1 一致）→
        //   "shorter than the provided offset" 文案（totalLines===0 的空文件警告在 read 路径不可达）。
        assertThat(textContent(tool, result))
            .as("空文件应显示 CC warning 分支（FileReadTool.ts:703-707），而非空串")
            .contains("<system-reminder>Warning: the file exists but is shorter than the provided offset (1). The file has 1 lines.</system-reminder>");
        // CYBER_RISK_MITIGATION_REMINDER 不附加（warning 分支无 reminder，CC :703-707）
        assertThat(textContent(tool, result))
            .doesNotContain(ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);
    }

    @Test
    @DisplayName("[P-AL-06] image 分支不附加 reminder —— CC image case :654-669 只含 image block（任务书'图片渲染层注入'系误述）")
    void imageBranchHasNoReminder(@TempDir Path workspace) throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};
        Files.write(workspace.resolve("tiny.png"), png);
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("tiny.png"));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(result.data().toString())
            .as("image data 含 base64/summary 但不得含 reminder 文本")
            .doesNotContain("<system-reminder>");
    }

    @Test
    @DisplayName("[IMP-C5] image 分支 mapper 产出独立 image block（模型可视，非 base64 JSON 文本）—— CC FileReadTool.ts:652-669")
    void imageBranchMapsToIndependentImageBlock(@TempDir Path workspace) throws Exception {
        // WHY（TR-D1 W1/R1 HIGH）：Java 原 image 分支 mapper 走默认渲染器 → tool_result content
        // = JsonNode.toString()（含 image_base64 全量 base64 JSON 文本），模型可能看到 JSON 而非图像。
        // CC FileReadTool.ts:654-669 image case 返回 content:[{type:'image', source:{type:'base64',
        // data, media_type}}] —— 独立 image block。本测试锁死该契约。
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};
        Files.write(workspace.resolve("tiny.png"), png);
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("tiny.png"));
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        JsonNode data = (JsonNode) result.data();
        assertThat(data.get("read_file_output_type").asText()).isEqualTo("image");
        assertThat(data.get("image_base64").asText()).isNotEmpty();

        ToolResultBlockParam block = tool.mapToToolResultBlockParam(result, "call-img-1", false);
        // content 为 List<ContentBlockParam>（块数组），元素为独立 image 块 —— 非 base64 JSON 文本
        assertThat(block.content()).isInstanceOf(List.class);
        List<?> blocks = (List<?>) block.content();
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).isInstanceOf(ContentBlockParam.ImageBlockParam.class);
        ContentBlockParam.ImageBlockParam img = (ContentBlockParam.ImageBlockParam) blocks.get(0);
        assertThat(img.source().mediaType()).isEqualTo(data.get("image_media_type").asText());
        assertThat(img.source().data()).isEqualTo(data.get("image_base64").asText());
    }

    @Test
    @DisplayName("[P-AL-06] dedup 缓存保持干净内容（无 reminder）—— CC readFileState 缓存干净 content :1032-1037")
    void dedupCacheKeepsCleanContent(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSession(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("a.txt"), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        // 返回给模型的 tool_result 载荷（mapper）带 reminder
        assertThat(textContent(tool, result)).endsWith(ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);

        // 但缓存 content 必须干净（后续 Edit stale-write 兜底比对用；CC 缓存原始内容）
        ToolUseContext.ReadState cached = ctx.readFileState()
            .get(ToolUseContext.keyForReadFileState(new PathGuard(workspace), "a.txt"));
        assertThat(cached).isNotNull();
        assertThat(cached.content())
            .as("缓存内容不得含 reminder（CC readFileState 缓存干净 content，渲染层才附加）")
            .isEqualTo("hello\n")
            .doesNotContain("<system-reminder>");
    }

    // ═════════════ [OPD-TOOL-06-3a] addLineNumbers 行号渲染 (CC file.ts:290-319) ═════════════

    @Test
    @DisplayName("[OPD-TOOL-06-3a] 文本读取行号渲染：全文读尾随换行文件 → compact 前缀 + 末尾空行编号；ReadState 存原始无行号内容")
    void textBranchRendersLineNumbers(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a\nb\nc\n");
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSession(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("a.txt"), ctx);

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        assertThat(textContent(tool, result))
            .as("对齐 CC addLineNumbers：尾随换行文件产生末尾空行编号 \"4\\t\"（CC split(/\\r?\\n/) 保留空 fragment）")
            .isEqualTo("1\ta\n2\tb\n3\tc\n4\t" + ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);

        // 锁死"行号仅渲染层、ReadState 存原始"不变量（规则九·验证意图）：
        // 若未来把行号写进缓存，Edit/Write stale-write 内容比对会永远失配，本断言必红。
        ToolUseContext.ReadState cached = ctx.readFileState()
            .get(ToolUseContext.keyForReadFileState(new PathGuard(workspace), "a.txt"));
        assertThat(cached).isNotNull();
        assertThat(cached.content())
            .as("ReadState 必须存原始无行号无 reminder 内容")
            .isEqualTo("a\nb\nc\n")
            .doesNotContain("\t");
    }

    @Test
    @DisplayName("[OPD-TOOL-06-3a] 窗口读行号起始对齐 CC startLine=offset：offset=2 limit=2 → 2\\tb / 3\\tc，无尾随换行无伪空行")
    void textBranchLineNumbersRespectOffset(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a\nb\nc\nd\ne\n");
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("a.txt", "offset", 2, "limit", 2));

        assertThat(LlmAgentLoop.isToolErrorData(result.data())).isFalse();
        String body = lineNumberBody(tool, result);
        assertThat(body)
            .as("窗口读 = selectedLines.join(换行)（无尾随换行），行号从 offset=2 起")
            .isEqualTo("2\tb\n3\tc")
            .doesNotEndWith("\n")
            .doesNotContain("4\t");
    }

    @Test
    @DisplayName("[P-AL-06] canonicalModelName 对齐 CC firstPartyNameToCanonical（model.ts:217-270，顺序敏感：4-6 先于 4-5/4）")
    void canonicalModelNameMapsToCcShortNames() {
        // 全名/日期后缀/3P ARN 形态 → canonical（CC :221-222 includes 语义）
        assertThat(ReadFileTool.canonicalModelName("claude-opus-4-6")).isEqualTo("claude-opus-4-6");
        assertThat(ReadFileTool.canonicalModelName("claude-opus-4-6-20250805")).isEqualTo("claude-opus-4-6");
        assertThat(ReadFileTool.canonicalModelName("us.anthropic.claude-opus-4-6-v1:0")).isEqualTo("claude-opus-4-6");
        // 顺序敏感: 4-5/4-1/4 不得被 4-6 捕获（CC :221-232 逐版本检查）
        assertThat(ReadFileTool.canonicalModelName("claude-opus-4-5")).isEqualTo("claude-opus-4-5");
        assertThat(ReadFileTool.canonicalModelName("claude-opus-4-1")).isEqualTo("claude-opus-4-1");
        assertThat(ReadFileTool.canonicalModelName("claude-opus-4")).isEqualTo("claude-opus-4");
        // sonnet/haiku 族
        assertThat(ReadFileTool.canonicalModelName("claude-sonnet-4-6")).isEqualTo("claude-sonnet-4-6");
        assertThat(ReadFileTool.canonicalModelName("claude-haiku-4-5")).isEqualTo("claude-haiku-4-5");
        assertThat(ReadFileTool.canonicalModelName("claude-3-5-sonnet-20241022")).isEqualTo("claude-3-5-sonnet");
        // 大写输入（CC :218 toLowerCase）
        assertThat(ReadFileTool.canonicalModelName("CLAUDE-OPUS-4-6")).isEqualTo("claude-opus-4-6");
        // 未知模型: 正则兜底 → 原样（CC :264-269）
        assertThat(ReadFileTool.canonicalModelName("gpt-4o")).isEqualTo("gpt-4o");
        // null/空白 → null（Java 防御；CC getMainLoopModel 恒有值，等价语义 null 恒注入）
        assertThat(ReadFileTool.canonicalModelName(null)).isNull();
        assertThat(ReadFileTool.canonicalModelName("  ")).isNull();
    }

    @Test
    @DisplayName("[P-AL-06] shouldIncludeFileReadMitigation 对齐 CC :735-738（豁免集 contains canonical）")
    void shouldIncludeFileReadMitigationFollowsCcExemptSet() {
        // 豁免 canonical → false
        assertThat(ReadFileTool.shouldIncludeFileReadMitigation("claude-opus-4-6")).isFalse();
        assertThat(ReadFileTool.shouldIncludeFileReadMitigation("claude-opus-4-6-20250805")).isFalse();
        // 非豁免 → true（CC 默认模型 Sonnet 非豁免 ≡ null/blank → true）
        assertThat(ReadFileTool.shouldIncludeFileReadMitigation("claude-sonnet-4-6")).isTrue();
        assertThat(ReadFileTool.shouldIncludeFileReadMitigation(null)).isTrue();
        assertThat(ReadFileTool.shouldIncludeFileReadMitigation("")).isTrue();
    }

    @Test
    @DisplayName("[P-AL-06] 常量文本与 CC 逐字一致（FileReadTool.ts:729-730 export 常量）")
    void reminderConstantMatchesCcText() {
        assertThat(ReadFileTool.CYBER_RISK_MITIGATION_REMINDER)
            .startsWith("\n\n<system-reminder>\n")
            .contains("You CAN and SHOULD provide analysis of malware, what it is doing.")
            .contains("But you MUST refuse to improve or augment the code.")
            .contains("You can still analyze existing code, write reports, or answer questions about the code behavior.")
            .endsWith("\n</system-reminder>\n");
    }

    // ═════════════ [L+ R3] dedup 完整对齐 CC FileReadTool.ts:530-575 ═════════════

    @Test
    @DisplayName("[L+ R3] isPartialView=true 的 prevState 不参与 dedup —— 对齐 CC :549 existingState.isPartialView 守卫")
    void partialViewPrevStateRejectedForDedup(@TempDir Path workspace) throws Exception {
        // 准备: 真实文件 + ctx 注入, 显式 put 一个 isPartialView=true 的 prevState.
        Files.writeString(workspace.resolve("big.txt"), "x".repeat(10_000));  // 10k 字符, GAP-D 输出限制 100k 内
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSession(workspace);

        // 步骤 1: 显式注入 prevState isPartialView=true (模拟行窗口读)
        ctx.readFileState().set(ToolUseContext.keyForReadFileState(new PathGuard(workspace), "big.txt"),
            new ReadState(
                Files.getLastModifiedTime(workspace.resolve("big.txt")).toMillis(), 1, 2000, true, null));

        // 步骤 2: 同 range (offset=1, limit=2000) 二次读 — isPartialView=true → 拒绝 dedup.
        // [L+ GAP-B] 必须显式 offset/limit: full read (offset=null) 现由调用侧 null 守卫
        // 短路拒绝, 不再是 isPartialView 在起作用 — 显式 range 才能精确测 isPartialView 守卫.
        ToolResult second = (ToolResult) tool.execute(callWith("big.txt", "offset", 1, "limit", 2000), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(second.data())).isFalse();
        assertThat(second.data())
            .as("isPartialView=true 阻止 dedup, 第二次应是 text (full read) 而非 file_unchanged")
            .isNotInstanceOf(JsonNode.class);
        assertThat(textContent(tool, second))
            .as("full read 应返回实际文件内容, 不是 <file_unchanged> 摘要")
            .doesNotContain("<file_unchanged>");
    }

    @Test
    @DisplayName("[L+ R3] offset 不匹配拒绝 dedup —— 对齐 CC :553 range match 严格")
    void offsetMismatchRejectsDedup(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "a\nb\nc\nd\ne\n");
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSession(workspace);

        // 第一次: offset=1, limit=2 (写入 cache: prevState.offset=1)
        ToolResult first = (ToolResult) tool.execute(callWith("a.txt", "offset", 1, "limit", 2), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(first.data())).isFalse();

        // 第二次: offset=2 (不同) → range mismatch, 不参与 dedup
        ToolResult second = (ToolResult) tool.execute(callWith("a.txt", "offset", 2, "limit", 2), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(second.data())).isFalse();
        assertThat(second.data())
            .as("offset 不一致, 不该 dedup 命中")
            .isNotInstanceOf(JsonNode.class);
        // 验证返回的是窗口 (offset=2 limit=2 → 应该是 b\nc\n)
        String body = lineNumberBody(tool, second);
        assertThat(body).isEqualTo("2\tb\n3\tc");
    }

    @Test
    @DisplayName("[L+ R3] killswitch off → 强制 full read, 不 dedup —— 对齐 CC tengu_read_dedup_killswitch")
    void killswitchOffForcesFullRead(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSession(workspace);

        // [L+ GAP-B] 必须显式窗口读: full read 现由 offset=null 守卫拒绝 dedup,
        // 与 killswitch 无关 — 显式 range 才能精确测 killswitch 开关.
        // 第一次窗口读 (offset=1, limit=2000) → 写入 dedup state
        ToolResult first = (ToolResult) tool.execute(callWith("a.txt", "offset", 1, "limit", 2000), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(first.data())).isFalse();

        // 对照组: killswitch 开启时同 range 二次读 → dedup 命中 file_unchanged (证明测试有效性)
        ToolResult control = (ToolResult) tool.execute(callWith("a.txt", "offset", 1, "limit", 2000), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(control.data())).isFalse();
        assertThat(((JsonNode) control.data()).get("read_file_output_type").asText())
            .isEqualTo("file_unchanged");

        // 关闭 dedup killswitch
        java.lang.reflect.Field ksField = ReadFileTool.class.getDeclaredField("dedupEnabled");
        ksField.setAccessible(true);
        ksField.setBoolean(tool, false);

        // 第三次同 range → killswitch off → 强制 full read, 不返回 file_unchanged
        ToolResult second = (ToolResult) tool.execute(callWith("a.txt", "offset", 1, "limit", 2000), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(second.data())).isFalse();
        assertThat(second.data())
            .as("killswitch off 不该返回 file_unchanged")
            .isNotInstanceOf(JsonNode.class);
        assertThat(textContent(tool, second))
            .as("[P-AL-06] full read 应返回实际内容 'hello' + reminder")
            .isEqualTo("1\thello\n2\t" + ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);
    }

    /**
     * [L+ R3] mtime 变化必须击穿 dedup —— 对齐 CC FileReadTool.ts:557
     * {@code if (mtimeMs === existingState.timestamp)}。
     *
     * <p>WHY 这个测试重要（规则九：验证意图而非行为）:
     * dedup 的存在意义是"省掉重复读同一份未变内容"，而不是"缓存文件"。
     * 若只比对 path+offset+limit 而漏掉 mtime，则"读 → 文件被外部改写 → 再读"
     * 会返回 {@code <file_unchanged>}，LLM 拿到 <b>stale content</b> 并基于旧内容做决策 ——
     * 这是 dedup 从优化退化成正确性事故。业务逻辑一旦回退（去掉 mtime 守卫），本测试必须失败。
     */
    @Test
    void mtimeChangeBreaksDedup(@TempDir Path workspace) throws Exception {
        Path target = workspace.resolve("a.txt");
        Files.writeString(target, "hello\n");
        ReadFileTool tool = toolFor(workspace);
        ToolUseContext ctx = ctxWithSession(workspace);

        // [L+ GAP-B] 显式窗口读 (offset=1, limit=2000): full read 现由 offset=null 守卫
        // 永不 dedup, 与 mtime 无关 — 显式 range 才能精确测 mtime 守卫击穿 dedup.
        // 第一次窗口读 → 写入 dedup state (mtime = t0)
        ToolResult first = (ToolResult) tool.execute(callWith("a.txt", "offset", 1, "limit", 2000), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(first.data())).isFalse();
        assertThat(textContent(tool, first)).isEqualTo("1\thello\n2\t" + ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);

        // 外部改写文件内容并显式推进 mtime（避免文件系统时间戳精度导致 t1 == t0）
        Files.writeString(target, "CHANGED\n");
        Files.setLastModifiedTime(target,
            java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(target).toMillis() + 5_000L));

        // 第二次同 path 同 range → rangeMatch 命中但 mtime 已变 → 必须 full read 拿到新内容
        ToolResult second = (ToolResult) tool.execute(callWith("a.txt", "offset", 1, "limit", 2000), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(second.data())).isFalse();
        assertThat(second.data())
            .as("mtime 变化后不该返回 file_unchanged（会让 LLM 拿到 stale content）")
            .isNotInstanceOf(JsonNode.class);
        assertThat(textContent(tool, second))
            .as("mtime 变化后必须返回改写后的新内容 + reminder")
            .isEqualTo("1\tCHANGED\n2\t" + ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);
    }

    // ═════════════ [L+ R1 收尾 · 跨工具消费者] Edit/Write invalidate dedup ═════════════

    /**
     * [L+ R1 收尾 · 跨工具消费者] Edit 成功后必须 invalidate ctx.readFileState() —
     * 对齐 CC FileEditTool.ts:520 readFileState.set(absoluteFilePath,
     * { content, timestamp: mtime, offset: undefined, limit: undefined }).
     *
     * <p>WHY 这条契约重要（规则九 · 验证意图而非行为）:
     * 缺这条 → "Read → Edit → Read 同 range" 会命中 dedup 返回 file_unchanged,
     * 让 LLM 拿到 stale content。基于旧内容做决策 = 正确性事故。
     * Edit 写回时设 offset=null/limit=null 让 ReadFileTool dedup 守卫
     * ({@code prevState.offset() != null}) 拒绝命中, 强制下次 Read 走 full read。
     */
    @Test
    @DisplayName("[L+ R1 跨工具] Edit 成功后 invalidate dedup → Read 拿到新内容, 不 file_unchanged")
    void editInvalidatesDedup(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        ReadFileTool readTool = toolFor(workspace);
        EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxWithSession(workspace);

        // [L+ GAP-B] 显式窗口读: full read 现由 offset=null 守卫永不 dedup,
        // 该测试验证的是 Edit 写回 offset=null 使同 range 二次读不命中 dedup —
        // 必须显式 range 才有 dedup 可被 invalidate.
        // 步骤 1: Read → cache 写入 ReadState(mtime, offset=1, limit=2000, isPartialView=false)
        ToolResult first = (ToolResult) readTool.execute(callWith("a.txt", "offset", 1, "limit", 2000), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(first.data())).isFalse();
        // [L+ round 3] key 改为归一化绝对路径 (ToolUseContext.keyForReadFileState 派生)
        String normalizedKey = ToolUseContext.keyForReadFileState(new PathGuard(workspace), "a.txt");
        assertThat(ctx.readFileState().get(normalizedKey)).isNotNull();
        assertThat(ctx.readFileState().get(normalizedKey).offset())
            .as("Read 写入的 cache offset 应是 1 (非 null)")
            .isEqualTo(1);

        // 步骤 2: Edit 把 hello → CHANGED
        ObjectNode editInput = JSON.createObjectNode();
        editInput.put("file_path", "a.txt");
        editInput.put("old_string", "hello");
        editInput.put("new_string", "CHANGED");
        ToolResult editResult = (ToolResult) editTool.execute(
            new ToolUseBlock("call-edit-1", "edit_file", editInput), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(editResult.data())).isFalse();

        // 步骤 3: 验证 ctx.readFileState().set 已发生 (offset=null)
        // [P-CC-02] FileStateCache API: getIfPresent → get
        ToolUseContext.ReadState afterEdit = ctx.readFileState().get(normalizedKey);
        assertThat(afterEdit).as("Edit 后 cache 必须仍持有 a.txt entry (offset=null)").isNotNull();
        assertThat(afterEdit.offset())
            .as("Edit 写回后 offset 必须 = null, 让 dedup 守卫拒绝命中 (CC offset=undefined 对齐)")
            .isNull();
        assertThat(afterEdit.limit())
            .as("Edit 写回后 limit 必须 = null, 让 dedup 守卫拒绝命中 (CC limit=undefined 对齐)")
            .isNull();

        // 步骤 4: 同 range 二次 Read → Edit 写回 offset=null → 守卫拒绝 dedup → full read 新内容
        ToolResult second = (ToolResult) readTool.execute(callWith("a.txt", "offset", 1, "limit", 2000), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(second.data())).isFalse();
        assertThat(second.data())
            .as("Edit 后 Read 不该返回 file_unchanged (会让 LLM 拿到 stale content)")
            .isNotInstanceOf(JsonNode.class);
        assertThat(textContent(readTool, second))
            .as("Edit 后 Read 必须返回改写后的新内容 + reminder")
            .isEqualTo("1\tCHANGED\n2\t" + ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);
    }

    /**
     * [L+ R1 收尾 · 跨工具消费者] Write 成功后必须 invalidate ctx.readFileState() —
     * 对齐 CC BashTool.tsx:404 (Bash 写文件后 readFileState.set) +
     * FileEditTool.ts:520 (offset=undefined 让 dedup 跳过本条).
     */
    @Test
    @DisplayName("[L+ R1 跨工具] Write 成功后 invalidate dedup → Read 拿到新内容, 不 file_unchanged")
    void writeInvalidatesDedup(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "old\n");
        ReadFileTool readTool = toolFor(workspace);
        WriteFileTool writeTool = new WriteFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxWithSession(workspace);

        // [L+ GAP-B] 显式窗口读: full read 现由 offset=null 守卫永不 dedup —
        // 该测试验证 Write 写回 offset=null 使同 range 二次读不命中 dedup, 必须显式 range.
        // 步骤 1: Read → cache 写入 ReadState(mtime, offset=1, limit=2000, isPartialView=false)
        ToolResult first = (ToolResult) readTool.execute(callWith("a.txt", "offset", 1, "limit", 2000), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(first.data())).isFalse();
        String normalizedKey = ToolUseContext.keyForReadFileState(new PathGuard(workspace), "a.txt");

        // 步骤 2: Write 覆盖文件为 "NEW\n"
        ObjectNode writeInput = JSON.createObjectNode();
        writeInput.put("file_path", "a.txt");
        writeInput.put("content", "NEW\n");
        ToolResult writeResult = (ToolResult) writeTool.execute(
            new ToolUseBlock("call-write-1", "write_file", writeInput), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(writeResult.data())).isFalse();

        // 步骤 3: 验证 ctx.readFileState().set 已发生 (offset=null)
        ToolUseContext.ReadState afterWrite = ctx.readFileState().get(normalizedKey);
        assertThat(afterWrite).as("Write 后 cache 必须仍持有 a.txt entry (offset=null)").isNotNull();
        assertThat(afterWrite.offset())
            .as("Write 写回后 offset 必须 = null, 让 dedup 守卫拒绝命中")
            .isNull();
        assertThat(afterWrite.limit())
            .as("Write 写回后 limit 必须 = null, 让 dedup 守卫拒绝命中")
            .isNull();
        assertThat(afterWrite.content())
            .as("Write 写回后 content 字段必须 = 新内容 CRLF-归一化形式")
            .isEqualTo("NEW\n");

        // 步骤 4: 同 range 二次 Read → Write 写回 offset=null → 守卫拒绝 dedup → full read 新内容
        ToolResult second = (ToolResult) readTool.execute(callWith("a.txt", "offset", 1, "limit", 2000), ctx);
        assertThat(LlmAgentLoop.isToolErrorData(second.data())).isFalse();
        assertThat(second.data())
            .as("Write 后 Read 不该返回 file_unchanged")
            .isNotInstanceOf(JsonNode.class);
        assertThat(textContent(readTool, second))
            .as("Write 后 Read 必须返回覆盖后的新内容 + reminder")
            .isEqualTo("1\tNEW\n2\t" + ReadFileTool.CYBER_RISK_MITIGATION_REMINDER);
    }

    /**
     * [L+ R1 收尾] key 格式一致性 + 替换验证 — Read 用 relPath 作 key,
     * Edit 写回 ctx.readFileState() 也必须用同一 key. 接线正确性硬性证明.
     * 验证: Edit/Write 必须替换 Read 的 entry (offset=null), 不能让 Read 的旧
     * offset=1 entry 残留在 cache (那会让 dedup 仍命中, 拿到 stale content).
     */
    @Test
    @DisplayName("[L+ R1 跨工具] key 格式一致: Edit/Write put 必须替换 Read entry, offset=null → 不残留 stale offset=1")
    void crossToolKeyConsistency(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");
        ReadFileTool readTool = toolFor(workspace);
        EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
        WriteFileTool writeTool = new WriteFileTool(new PathGuard(workspace));
        ToolUseContext ctx = ctxWithSession(workspace);

        // Read → cache 写入归一化 key + offset=1 (显式窗口读, GAP-B: full read 存 offset=null)
        readTool.execute(callWith("a.txt", "offset", 1, "limit", 2000), ctx);
        String normalizedKey = ToolUseContext.keyForReadFileState(new PathGuard(workspace), "a.txt");
        // [P-CC-02] FileStateCache API: containsKey → get != null
        assertThat(ctx.readFileState().get(normalizedKey)).isNotNull();
        assertThat(ctx.readFileState().get(normalizedKey).offset()).isEqualTo(1);

        // Edit → 必须覆盖同一 key, 用 offset=null 替换旧 Read entry
        ObjectNode editInput = JSON.createObjectNode();
        editInput.put("file_path", "a.txt");
        editInput.put("old_string", "hello");
        editInput.put("new_string", "X");
        editTool.execute(new ToolUseBlock("call-edit-1", "edit_file", editInput), ctx);
        assertThat(ctx.readFileState().size())
            .as("Edit 写回必须使用 Read 同一归一化 key, 不能产生 entry 孤儿")
            .isEqualTo(1);
        assertThat(ctx.readFileState().get(normalizedKey).offset())
            .as("Edit 必须用 offset=null 替换 Read 的 offset=1, 否则 dedup 会命中 stale")
            .isNull();

        // Write → 同 key 覆盖
        ObjectNode writeInput = JSON.createObjectNode();
        writeInput.put("file_path", "a.txt");
        writeInput.put("content", "Y");
        writeTool.execute(new ToolUseBlock("call-write-1", "write_file", writeInput), ctx);
        assertThat(ctx.readFileState().size())
            .as("Write 写回必须使用 Read 同一归一化 key, 不能产生 entry 孤儿")
            .isEqualTo(1);
        assertThat(ctx.readFileState().get(normalizedKey).offset()).isNull();
    }

    // ═════════════ OPD-D1-01: Read fileReadingLimits override (CC FileReadTool.ts:502-516) ═════════════

    /**
     * 写一个纯 ASCII 内容文件（字符数 ≈ 字节数）· 供大小/token 上限测试。
     */
    private static Path writeAsciiFile(Path workspace, String name, int sizeBytes) throws Exception {
        byte[] bytes = new byte[sizeBytes];
        java.util.Arrays.fill(bytes, (byte) 'a');
        Path p = workspace.resolve(name);
        Files.write(p, bytes);
        return p;
    }

    /** 构造带 fileReadingLimits override 的 ctx（CC Tool.ts:251 fileReadingLimits?）。 */
    private static ToolUseContext ctxWithOverride(Path workspace, FileReadingLimits.Override override) {
        return ctxWithSession(workspace).withFileReadingLimits(override);
    }

    @Test
    @DisplayName("OPD-D1-01 · override maxSizeBytes 放宽 pre-read 字节上限（300KB 默认被拒 → 512KB 放行）")
    void overrideMaxSizeBytesRelaxesFullRead(@TempDir Path workspace) throws Exception {
        // WHY: CC FileReadTool.ts:505-506 maxSizeBytes = fileReadingLimits?.maxSizeBytes ?? defaults；
        //   默认 256KB 拒绝 >256KB 全读（GAP-D），override 512KB 后同一文件放行 —— 验证 override 覆盖默认上限。
        writeAsciiFile(workspace, "big.txt", 300 * 1024);
        ReadFileTool tool = toolFor(workspace);

        // 对照组：无 override → 默认 256KB 上限拒绝 300KB 全读
        ToolResult withoutOverride = (ToolResult) tool.execute(callWith("big.txt"), ctxWithSession(workspace));
        assertThat(withoutOverride.data()).as("无 override 默认 256KB 必须拒绝 300KB").isInstanceOf(String.class);
        assertThat((String) withoutOverride.data()).contains("exceeds maximum allowed size");

        // override：maxSizeBytes=512KB（放宽）+ maxTokens 足够大（token 校验不参与）→ 放行
        ToolResult withOverride = (ToolResult) tool.execute(callWith("big.txt"),
            ctxWithOverride(workspace, new FileReadingLimits.Override(1_000_000, 512 * 1024)));
        assertThat(LlmAgentLoop.isToolErrorData(withOverride.data()))
            .as("override maxSizeBytes=512KB 必须放行 300KB 全读（CC :505-506）").isFalse();
    }

    @Test
    @DisplayName("OPD-D1-01 · override maxSizeBytes 收紧 pre-read 字节上限（100KB 默认放行 → 64KB 拒绝）")
    void overrideMaxSizeBytesTightensFullRead(@TempDir Path workspace) throws Exception {
        // WHY: 收紧语义——调用方可以把默认 256KB 降为 64KB，让超大文件更早 fail-loud（CC 无截断概念）。
        writeAsciiFile(workspace, "mid.txt", 100 * 1024);
        ReadFileTool tool = toolFor(workspace);

        ToolResult withoutOverride = (ToolResult) tool.execute(callWith("mid.txt"), ctxWithSession(workspace));
        assertThat(LlmAgentLoop.isToolErrorData(withoutOverride.data()))
            .as("无 override 100KB < 256KB 默认放行").isFalse();

        ToolResult withOverride = (ToolResult) tool.execute(callWith("mid.txt"),
            ctxWithOverride(workspace, new FileReadingLimits.Override(1_000_000, 64 * 1024)));
        assertThat(withOverride.data()).as("override maxSizeBytes=64KB 必须拒绝 100KB（CC :505-506）").isInstanceOf(String.class);
        assertThat((String) withOverride.data()).contains("exceeds maximum allowed size");
    }

    @Test
    @DisplayName("OPD-D1-01 · override maxTokens 放宽 post-read token 上限（200KB 默认超限 → maxTokens=100000 放行）")
    void overrideMaxTokensRelaxesPostRead(@TempDir Path workspace) throws Exception {
        // WHY: CC FileReadTool.ts:507 maxTokens = fileReadingLimits?.maxTokens ?? defaults；默认 25000 tokens
        //   （Java 近似 100k 字符）拒绝 200KB 输出；override maxTokens=100000（400k 字符）后放行 —— 验证 token 上限可覆写。
        writeAsciiFile(workspace, "wide.txt", 200 * 1024);
        ReadFileTool tool = toolFor(workspace);

        ToolResult withoutOverride = (ToolResult) tool.execute(callWith("wide.txt"), ctxWithSession(workspace));
        assertThat(withoutOverride.data()).as("无 override 默认 token 上限必须拒绝 200KB 输出").isInstanceOf(String.class);
        assertThat((String) withoutOverride.data()).contains("exceeds maximum allowed tokens");

        // maxSizeBytes 也放宽到 1MB（否则 size 校验先拒绝）；maxTokens=100000 → 400k 字符 ≥ 200k
        ToolResult withOverride = (ToolResult) tool.execute(callWith("wide.txt"),
            ctxWithOverride(workspace, new FileReadingLimits.Override(100_000, 1024 * 1024)));
        assertThat(LlmAgentLoop.isToolErrorData(withOverride.data()))
            .as("override maxTokens=100000 必须放行 200KB 输出（CC :507）").isFalse();
    }

    @Test
    @DisplayName("OPD-D1-01 · override maxTokens 收紧 post-read token 上限（10KB 默认放行 → maxTokens=1000 拒绝）")
    void overrideMaxTokensTightensPostRead(@TempDir Path workspace) throws Exception {
        // WHY: 收紧语义——调用方可把输出 token 上限压到 1000（4000 字符），10KB 内容即超限 fail-loud。
        writeAsciiFile(workspace, "small.txt", 10 * 1024);
        ReadFileTool tool = toolFor(workspace);

        ToolResult withoutOverride = (ToolResult) tool.execute(callWith("small.txt"), ctxWithSession(workspace));
        assertThat(LlmAgentLoop.isToolErrorData(withoutOverride.data()))
            .as("无 override 10KB < 100k 字符默认放行").isFalse();

        ToolResult withOverride = (ToolResult) tool.execute(callWith("small.txt"),
            ctxWithOverride(workspace, new FileReadingLimits.Override(1_000, null)));
        assertThat(withOverride.data()).as("override maxTokens=1000 必须拒绝 10KB 输出（CC :507）").isInstanceOf(String.class);
        assertThat((String) withOverride.data()).contains("exceeds maximum allowed tokens");
    }

    @Test
    @DisplayName("OPD-D1-01 · 无 override 默认回退：50KB 全读放行（env/GB 缺省 → 256KB + 25000 tokens 默认）")
    void defaultLimitsApplyWithoutOverride(@TempDir Path workspace) throws Exception {
        // WHY: CC FileReadTool.ts:505-507 无 fileReadingLimits 时回退 getDefaultFileReadingLimits()；
        //   Java 端 env(System.getenv) / GB(@Value 注入) 缺省 → FileReadingLimits.resolve 默认 256KB/25000。
        writeAsciiFile(workspace, "ok.txt", 50 * 1024);
        ReadFileTool tool = toolFor(workspace);

        ToolResult result = (ToolResult) tool.execute(callWith("ok.txt"), ctxWithSession(workspace));

        assertThat(LlmAgentLoop.isToolErrorData(result.data()))
            .as("无 override 50KB 全读必须放行（默认 256KB + 100k 字符内）").isFalse();
    }

    @Test
    @DisplayName("OPD-D1-01 · override 有值才触发 tengu_file_read_limits_override 埋点（默认不触发）")
    void overrideTelemetryFiresOnlyWhenOverrideSet(@TempDir Path workspace) throws Exception {
        // WHY: CC FileReadTool.ts:511-516 if (fileReadingLimits !== undefined) logEvent(...) ——
        //   埋点计数 = override 频率（低流量）；无 override 不得发射。metadata = hasMaxTokens/hasMaxSizeBytes 布尔。
        writeAsciiFile(workspace, "t.txt", 1000);
        ReadFileTool tool = toolFor(workspace);
        AnalyticsTracker tracker = new AnalyticsTracker();
        tool.setAnalyticsTracker(tracker);

        // 无 override → 不触发
        tool.execute(callWith("t.txt"), ctxWithSession(workspace));
        assertThat(tracker.countsByEventName()).as("无 override 不得触发埋点（CC :511）").doesNotContainKey("tengu_file_read_limits_override");

        // override → 触发一次（含两布尔 metadata 键）
        tool.execute(callWith("t.txt"), ctxWithOverride(workspace, new FileReadingLimits.Override(5000, null)));
        java.util.Map<String, Long> counts = tracker.countsByEventName();
        assertThat(counts.get("tengu_file_read_limits_override"))
            .as("override 有值必须触发埋点（CC :511-516）").isEqualTo(1L);
    }
}