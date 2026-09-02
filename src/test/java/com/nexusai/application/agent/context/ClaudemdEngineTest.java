package com.nexusai.application.agent.context;

import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.MemoryFileDetection;
import com.nexusai.application.agent.permission.hook.HookEvent;
import com.nexusai.application.agent.permission.hook.HookEventType;
import com.nexusai.application.agent.permission.hook.HookRegistry;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * [IMP-M-P2-4] ClaudemdEngine · 全量对齐 CC {@code utils/claudemd.ts}.
 *
 * <p>WHY（规则九 · 测试验证意图）：旧 {@code ClaudemdParser} 是孤儿死代码（DEL-M-32），
 * claudemd 引擎整体缺失（09 X8-X14，OPD-M-45）。本测试锁定 CC 的不变量：
 * <ul>
 *   <li>INV-13 加载序 Managed→User→Project→Local→AutoMem→TeamMem（claudemd.ts:1-26 头注释）</li>
 *   <li>MAX_MEMORY_CHARACTER_COUNT=40000 + getLargeMemoryFiles（claudemd.ts:92/1132-1134）</li>
 *   <li>@include MAX_INCLUDE_DEPTH=5 + stripHtmlComments（claudemd.ts:537/618-685/292-334）</li>
 *   <li>processMdRules/processConditionedMdRules 条件规则（claudemd.ts:697-788/1354-1397）</li>
 *   <li>getClaudeMds MEMORY_INSTRUCTION_PROMPT 契约（claudemd.ts:1153-1195）</li>
 *   <li>缓存失效：clearMemoryFileCaches / resetGetMemoryFilesCache（claudemd.ts:1119-1130）</li>
 *   <li>FIX-CL 已删 prependClaudeMdContext 双轨（前置渲染唯一实现 AgentLoopContext.prependUserContext，
 *       LlmAgentLoopChainTest 覆盖）</li>
 * </ul>
 */
@DisplayName("[IMP-M-P2-4] ClaudemdEngine 对齐 CC utils/claudemd.ts")
class ClaudemdEngineTest {

    @TempDir
    Path workspace;      // originalCwd + Project/Local 记忆文件根

    Path configHome;     // NexusaiPaths 根等价（User memory / rules，T4 决策 D1）
    String originalUserHome;   // T4：user.home 原值（NexusaiPaths 根隔离复位用）
    Path managedPath;    // managed settings（Managed memory / rules）
    Path autoMemDir;     // auto-memory 目录（MEMORY.md）
    Path memoryBase;     // <memoryBase>/projects/... 派生 autoMemPath

    private AutoMemPaths autoMemPaths;
    private MemoryFileDetection detection;
    private ClaudemdEngine engine;

    /** 每次测试运行唯一 appName 序号 · T4 隔离：避免测试写 getUserClaudeRulesDir()
     *  （T4 已改 nexusai 自有根）污染真实 ~/.nexusai 或跨运行残留导致 stale 误加载。 */
    private static final java.util.concurrent.atomic.AtomicInteger APP_NAME_SEQ =
        new java.util.concurrent.atomic.AtomicInteger();

    /** 初始化隔离实例（ClaudePaths override + NexusaiPaths 隔离 + 注入 suppliers）。 */
    private void setUp(boolean teamMemoryEnabled) throws Exception {
        managedPath = Files.createTempDirectory("claude-managed");
        autoMemDir = Files.createTempDirectory("claude-auto-mem");
        memoryBase = Files.createTempDirectory("claude-memory-base");

        // T4（决策 D1）：User 档已走 NexusaiPaths 自有根（~/.{appName}/CLAUDE.md，ClaudemdEngine:1558-1559）。
        //   隔离 user.home 到临时根，使 NexusaiPaths.getAppConfigHomeDir() 落临时目录（不污染真实 ~/.nexusai），
        //   configHome 直接 = NexusaiPaths 根（User memory/rules fixture 落点，断言路径同源）。
        Path userHomeTemp = Files.createTempDirectory("claude-user-home");
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", userHomeTemp.toString());
        NexusaiPaths.setAppNameOverride("nexusai-test-" + APP_NAME_SEQ.incrementAndGet());
        configHome = Path.of(NexusaiPaths.getAppConfigHomeDir());

        ClaudePaths.setConfigDirOverride(configHome.toString());
        ClaudePaths.setManagedFilePathOverride(managedPath.toString());

        autoMemPaths = new AutoMemPaths(
            () -> workspace.toString(),
            () -> memoryBase.toString(),
            () -> autoMemDir.toString() + java.io.File.separator,
            () -> null);

        // IMP-CM-09 双门控：feature('TEAMMEM')=teamMemoryEnabled + runtime=tengu_herring_clock=true
        detection = new MemoryFileDetection(
            autoMemPaths, () -> configHome.toString(), () -> true,
            () -> teamMemoryEnabled, () -> true);

        engine = new ClaudemdEngine(autoMemPaths, detection,
            () -> workspace.toString(),       // CC getOriginalCwd()
            () -> true, () -> true, () -> true,   // userSettings/projectSettings/localSettings 恒启用
            () -> teamMemoryEnabled,          // feature('TEAMMEM')
            () -> List.of());                 // claudeMdExcludes（无 settings 源 → 空）
    }

    @BeforeEach
    void initEngine() throws Exception {
        setUp(false);
    }

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);
        ClaudePaths.setManagedFilePathOverride(null);
        NexusaiPaths.setAppNameOverride(null);   // T4：复位默认 appName（nexusai）
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);   // T4：复位 user.home（NexusaiPaths 根隔离）
            originalUserHome = null;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // INV-13 加载序：Managed→User→Project→Local→AutoMem→TeamMem
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getMemoryFiles 加载序 Managed→User→Project→Local→AutoMem→TeamMem (claudemd.ts:790-1075)")
    void getMemoryFiles_loadingOrder() throws Exception {
        // WHY: INV-13 是 claudemd 引擎结构性决策核心（09 R2/OPD-M-45）。文件按优先级反序
        //       加载：后加载的优先级更高（claudemd.ts:9-10）。旧 ClaudemdParser 无加载序概念，
        //       仅单文件解析 → 无法表达"越近 cwd 优先级越高"。
        setUp(false);
        // Managed（恒加载）
        Files.createDirectories(managedPath);
        Files.writeString(managedPath.resolve("CLAUDE.md"), "# Managed root\n");
        Files.createDirectories(managedPath.resolve(".claude").resolve("rules"));
        Files.writeString(managedPath.resolve(".claude").resolve("rules").resolve("managed-rule.md"),
            "Managed rules content\n");
        // User
        Files.createDirectories(configHome);
        Files.writeString(configHome.resolve("CLAUDE.md"), "# User root\n");
        Files.createDirectories(configHome.resolve("rules"));
        Files.writeString(configHome.resolve("rules").resolve("user-rule.md"), "User rules content\n");
        // Project（cwd 向上遍历：先 root 后 cwd —— 这里只有 workspace 一层）
        Files.writeString(workspace.resolve("CLAUDE.md"), "# Project root\n");
        Files.createDirectories(workspace.resolve(".claude"));
        Files.writeString(workspace.resolve(".claude").resolve("CLAUDE.md"), "# Dot Claude\n");
        Files.createDirectories(workspace.resolve(".claude").resolve("rules"));
        Files.writeString(workspace.resolve(".claude").resolve("rules").resolve("proj-rule.md"),
            "Project rules content\n");
        // Local
        Files.writeString(workspace.resolve("CLAUDE.local.md"), "# Local root\n");
        // AutoMem（isAutoMemoryEnabled 默认 true）
        Files.createDirectories(autoMemDir);
        Files.writeString(autoMemDir.resolve("MEMORY.md"), "# AutoMem entry\n");

        List<MemoryFileInfo> files = engine.getMemoryFiles(false);

        // 类型序列断言：Managed 文件 + Managed rules → User 文件 + User rules →
        // Project CLAUDE.md + .claude/CLAUDE.md + rules → Local → AutoMem
        List<String> typeSeq = files.stream().map(f -> f.type().ccName()).toList();
        assertThat(typeSeq).as("INV-13 加载序（类型序列）").containsExactly(
            "Managed", "Managed", "User", "User",
            "Project", "Project", "Project", "Local", "AutoMem");
        // 具体路径顺序断言（managed root 先于 user root 先于 project root）
        List<String> paths = files.stream().map(MemoryFileInfo::path).toList();
        assertThat(paths.indexOf(managedPath.resolve("CLAUDE.md").toString()))
            .as("Managed root 最先").isLessThan(paths.indexOf(configHome.resolve("CLAUDE.md").toString()));
        assertThat(paths.indexOf(configHome.resolve("CLAUDE.md").toString()))
            .as("User root 先于 Project root")
            .isLessThan(paths.indexOf(workspace.resolve("CLAUDE.md").toString()));
        assertThat(paths.indexOf(workspace.resolve("CLAUDE.md").toString()))
            .as("Project root 先于 CLAUDE.local.md")
            .isLessThan(paths.indexOf(workspace.resolve("CLAUDE.local.md").toString()));
    }

    @Test
    @DisplayName("F-02 生产缝：2 参构造 + setTeamMemoryEnabled(() -> true) → TeamMem 入口注入（claudemd.ts:995-1007）")
    void teamMemoryEnabled_setterWiresTeamMemEntrypoint() throws Exception {
        // WHY（探查 F-02 / △-5 / R1 / T-1）：生产 @Bean 走 2 参构造 → teamMemoryEnabled=() -> false
        //   恒 false，与 enum 门控（ClaudemdMemoryType.setTeamMemEnabled，OPD-CM3-35/IMP-CM-11）及
        //   MemoryFileDetection 双门控（IMP-CM-09）内部不一致——feature('TEAMMEM') 开启时值域恢复但
        //   getMemoryFiles 入口恒不注入。本测试锁 setter 装配缝（ToolRegistrationConfig 注入
        //   () -> featureFlags.teamMem() 等价）：开启 → TeamMem 入口注入且序在 AutoMem 之后。
        setUp(false);
        // team entrypoint = <autoMem>/team/MEMORY.md（getTeamMemPath = join(autoMemPath, 'team')，teamMemPaths.ts:84-86）
        Files.createDirectories(autoMemDir.resolve("team"));
        Files.writeString(autoMemDir.resolve("team").resolve("MEMORY.md"), "# Team entry\n");

        // 复刻生产 @Bean 装配（2 参构造 + setter，对齐 ToolRegistrationConfig claudemdEngine @Bean）
        ClaudemdEngine prodEngine = new ClaudemdEngine(autoMemPaths, detection);
        prodEngine.setTeamMemoryEnabled(() -> true);   // 等价 () -> featureFlags != null && featureFlags.teamMem()

        List<MemoryFileInfo> files = prodEngine.getMemoryFiles(false);
        List<String> typeSeq = files.stream().map(f -> f.type().ccName()).toList();
        assertThat(typeSeq).as("F-02：teamMemoryEnabled 开启 → TeamMem 入口注入")
            .contains("TeamMem");
        assertThat(typeSeq.indexOf("TeamMem")).as("TeamMem 在 AutoMem 之后（加载序 claudemd.ts:980-1007）")
            .isGreaterThan(typeSeq.indexOf("AutoMem"));
    }

    @Test
    @DisplayName("F-02 生产缝：2 参构造默认（未 setter）→ TeamMem 不注入（claudemd.ts:995 feature 关）")
    void teamMemoryEnabled_defaultOff_doesNotInjectTeamMem() throws Exception {
        // WHY：2 参生产构造 teamMemoryEnabled 默认 () -> false（CC feature('TEAMMEM') 默认关）——
        //   即使 team MEMORY.md 存在也不注入；未调 setter = 保守缺省（对齐 CC 编译门 feature 关）。
        setUp(false);
        Files.createDirectories(autoMemDir.resolve("team"));
        Files.writeString(autoMemDir.resolve("team").resolve("MEMORY.md"), "# Team entry\n");

        ClaudemdEngine prodEngine = new ClaudemdEngine(autoMemPaths, detection);

        List<MemoryFileInfo> files = prodEngine.getMemoryFiles(false);
        List<String> typeSeq = files.stream().map(f -> f.type().ccName()).toList();
        assertThat(typeSeq).as("F-02：teamMemoryEnabled 默认 false → 不注入 TeamMem")
            .doesNotContain("TeamMem");
    }

    @Test
    @DisplayName("getMemoryFiles 首次加载 → tengu_claudemd__initial_load 一次性事件（claudemd.ts:1021-1043）")
    void getMemoryFiles_emitsInitialLoadOnce() throws Exception {
        // WHY: CC claudemd.ts:1025-1043 hasLoggedInitialLoad 一次性去重 —— 首次 getMemoryFiles
        //       发 tengu_claudemd__initial_load（file_count/total_content_length/typeCounts/
        //       duration_ms），此后不再发（避免每次会话重复计数污染 dashboard）。
        setUp(false);
        Files.createDirectories(configHome);
        Files.writeString(configHome.resolve("CLAUDE.md"), "# User root\n");

        RecordingTelemetry telemetry = new RecordingTelemetry();
        engine.setTelemetry(telemetry);
        engine.clearMemoryFileCaches();   // 清 memoize，确保走真实加载

        engine.getMemoryFiles(false);
        // 二次调用命中 memoize 缓存 → 不再发 initial_load（且各缓存键独立：forceIncludeExternal=true 也走缓存）
        engine.getMemoryFiles(false);

        assertThat(telemetry.events)
            .as("首次加载必须发射 tengu_claudemd__initial_load（一次性）")
            .contains("tengu_claudemd__initial_load");
        assertThat(telemetry.events)
            .as("hasLoggedInitialLoad 去重：memoize 二次调用不再发射")
            .containsExactly("tengu_claudemd__initial_load");
    }

    @Test
    @DisplayName("getMemoryFiles memoize：二次调用返回缓存（clearMemoryFileCaches 失效）claudemd.ts:790/1119-1122")
    void getMemoryFiles_memoizeAndClear() throws Exception {
        // WHY: CC lodash memoize（claudemd.ts:790）避免每轮 LLM call 重新 readdir/readFile；
        //       clearMemoryFileCaches（claudemd.ts:1119-1122）供 compact/设置同步失效缓存。
        setUp(false);
        Files.writeString(workspace.resolve("CLAUDE.md"), "# v1\n");
        List<MemoryFileInfo> first = engine.getMemoryFiles(false);

        // 文件变更后不清缓存 → 返回旧结果
        Files.writeString(workspace.resolve("CLAUDE.md"), "# v2 changed\n");
        List<MemoryFileInfo> cached = engine.getMemoryFiles(false);
        assertThat(cached).as("memoize 命中：内容不变").isSameAs(first);

        // clearMemoryFileCaches → 下次重新加载
        engine.clearMemoryFileCaches();
        List<MemoryFileInfo> reloaded = engine.getMemoryFiles(false);
        assertThat(reloaded).as("clear 后重载：内容更新").isNotSameAs(first);
        assertThat(reloaded.stream().map(MemoryFileInfo::content).toList())
            .as("重载内容含 v2").anyMatch(c -> c.contains("v2 changed"));
    }

    // ════════════════════════════════════════════════════════════════
    // MAX_MEMORY_CHARACTER_COUNT=40000 + getLargeMemoryFiles · claudemd.ts:92/1132-1134
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getLargeMemoryFiles 过滤 content.length > 40000 (claudemd.ts:92/1132-1134)")
    void getLargeMemoryFiles_over40000() {
        // WHY: OPD-M-46 要求 MAX_MEMORY_CHARACTER_COUNT=40000。超大记忆文件不该全量进
        //       system prompt（token 预算）；getLargeMemoryFiles 供调用方识别并降级。
        MemoryFileInfo big = MemoryFileInfo.of("/tmp/big.md", ClaudemdMemoryType.PROJECT,
            "x".repeat(40001), null);
        MemoryFileInfo exactly = MemoryFileInfo.of("/tmp/exact.md", ClaudemdMemoryType.PROJECT,
            "x".repeat(40000), null);
        MemoryFileInfo small = MemoryFileInfo.of("/tmp/small.md", ClaudemdMemoryType.PROJECT,
            "tiny", null);

        List<MemoryFileInfo> large = engine.getLargeMemoryFiles(List.of(big, exactly, small));

        assertThat(large).as("仅 >40000 命中（40000 整不命中，CC > 严格大于）")
            .extracting(MemoryFileInfo::path).containsExactly("/tmp/big.md");
    }

    // ════════════════════════════════════════════════════════════════
    // @include MAX_INCLUDE_DEPTH=5 + stripHtmlComments · claudemd.ts:537/618-685/292-334
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("processMemoryFile @include 解析 + stripHtmlComments (claudemd.ts:618-685/292-334)")
    void processMemoryFile_atIncludeAndStripComments() throws Exception {
        // WHY: @include 是 CLAUDE.md 引用子文件的机制（claudemd.ts:18-26 头注释），被包含文件
        //       作为独立 entry 排在包含文件之后；HTML 块级注释是作者注记，不该进 LLM 上下文。
        setUp(false);
        Path root = Files.createTempDirectory("include-root");
        Path sub = root.resolve("docs");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("child.md"), "child content\n");
        // 主文件：注释 + @include + 代码块内的假 @include（不应被提取）
        Files.writeString(root.resolve("CLAUDE.md"),
            "# Main\n"
                + "<!-- author note to strip -->\n"
                + "Main body\n"
                + "@docs/child.md\n"
                + "```\n@docs/child.md (in code, ignored)\n```\n"
                + "`@docs/child.md` (codespan, ignored)\n");

        java.util.Set<String> processed = new java.util.HashSet<>();
        List<MemoryFileInfo> files = engine.processMemoryFile(
            root.resolve("CLAUDE.md").toString(), ClaudemdMemoryType.PROJECT,
            processed, true, 0, null);

        assertThat(files).as("主文件 + 1 个 @include 子文件").hasSize(2);
        assertThat(files.get(0).content()).as("主文件注释被剥离")
            .doesNotContain("author note to strip")
            .contains("Main body");
        assertThat(files.get(1).path()).as("子文件解析为绝对路径")
            .isEqualTo(sub.toRealPath().resolve("child.md").toString());
        assertThat(files.get(1).parent()).as("子文件 parent = 主文件")
            .isEqualTo(root.resolve("CLAUDE.md").toString());
        assertThat(files.get(0).content()).as("主文件不含 @include 行（注释剥离后 content 无 @docs 残留）")
            .contains("@docs/child.md");
    }

    @Test
    @DisplayName("MAX_INCLUDE_DEPTH=5 限制递归 (claudemd.ts:537/630)")
    void processMemoryFile_maxIncludeDepth() throws Exception {
        // WHY: 防止恶意/误配置的 @include 环造成无限递归（claudemd.ts:626-632 头注释）。
        //       depth >= 5 时停止；processedPaths 亦防环。
        setUp(false);
        Path root = Files.createTempDirectory("depth-root");
        // 链式 @include：a.md -> b.md -> c.md -> d.md -> e.md -> f.md（6 层）
        for (char c = 'a'; c <= 'f'; c++) {
            String next = c < 'f' ? ("@" + (char) (c + 1) + ".md") : "";
            Files.writeString(root.resolve(c + ".md"),
                "content-" + c + (next.isEmpty() ? "" : "\n" + next));
        }

        java.util.Set<String> processed = new java.util.HashSet<>();
        List<MemoryFileInfo> files = engine.processMemoryFile(
            root.resolve("a.md").toString(), ClaudemdMemoryType.PROJECT, processed, true, 0, null);

        // depth 0=a,1=b,2=c,3=d,4=e 加载；f 在 depth=5 被截断
        List<String> paths = files.stream().map(MemoryFileInfo::path).toList();
        assertThat(paths).as("depth 0..4 加载，depth=5 的 f.md 截断")
            .contains(root.toRealPath().resolve("e.md").toString())
            .doesNotContain(root.resolve("f.md").toString());
    }

    // ════════════════════════════════════════════════════════════════
    // REQ-14 段落内联注释 @path / REQ-15△ EISDIR（OPD-CM3-19/D05）· claudemd.ts:458-491/:402-416
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("REQ-14 段落内联注释内 @path 不误提取；注释外真实 @path 仍提取 (claudemd.ts:503-514)")
    void extractIncludePaths_inlineCommentAtPathIgnored() throws Exception {
        // WHY: CC marked 把段落内行内注释拆为独立 html inline token，仅查 strip 注释后 residue
        //       （claudemd.ts:503-514，probe 实测 text/html/text 三 token）；Java 块级 lexer 把行内
        //       注释留在 TEXT 块内，若不先剥注释 span 会把注释内 @path 误提取进上下文，多加载一个文件
        //       导致 token 与内容语义漂移（REQ-14，F1 §14-2）。本用例锁注释内 @path 忽略 + 注释外
        //       真实 @path 仍提取（防过度修复）。
        setUp(false);
        Path root = Files.createTempDirectory("inline-comment-root");
        Path real = root.resolve("real.md");
        Files.writeString(real, "real content\n");
        Path fakeDir = Files.createDirectories(root.resolve("docs"));
        Files.writeString(fakeDir.resolve("foo.md"), "fake content\n");
        // 段落内联注释中的 @docs/foo.md 不应被提取；@./real.md 是注释外的真实引用应被提取
        Files.writeString(root.resolve("CLAUDE.md"),
            "# Main\n"
                + "Some text <!-- 详见 @docs/foo.md --> @./real.md more\n");

        java.util.Set<String> processed = new java.util.HashSet<>();
        List<MemoryFileInfo> files = engine.processMemoryFile(
            root.resolve("CLAUDE.md").toString(), ClaudemdMemoryType.PROJECT, processed, true, 0, null);

        List<String> paths = files.stream().map(MemoryFileInfo::path).toList();
        assertThat(files).as("主文件 + 1 个真实 @include（注释内假引用不加载）").hasSize(2);
        assertThat(paths).as("只加载真实 @./real.md，注释内 @docs/foo.md 被忽略（REQ-14）")
            .contains(root.toRealPath().resolve("real.md").toString())
            .doesNotContain(fakeDir.toRealPath().resolve("foo.md").toString());
    }

    @Test
    @DisplayName("REQ-15△ 目录 @include 读前 isRegularFile 判定 → 不发 permission_error (claudemd.ts:405 EISDIR 静默)")
    void processMemoryFile_directoryInclude_noPermissionError() throws Exception {
        // WHY: CC handleMemoryFileReadError 对 EISDIR 静默（claudemd.ts:405）。Windows 11 上
        //       Files.readString(目录) 抛 AccessDeniedException（非 NoSuchFileException）→ 旧实现误发
        //       tengu_claude_md_permission_error（E4 实测 EV-F1-20，真实权限错误被噪声淹没）。读前
        //       isRegularFile 判定 → 目录静默返回 null（对齐 safelyReadEntrypoint:775-777 既有检查）。
        setUp(false);
        com.nexusai.application.agent.telemetry.Telemetry telemetry =
            new com.nexusai.application.agent.telemetry.Telemetry();
        engine.setTelemetry(telemetry);

        Path root = Files.createTempDirectory("dir-include-root");
        Files.createDirectories(root.resolve("subdir"));
        // 用户误引用目录而非文件：@subdir 指向一个目录
        Files.writeString(root.resolve("CLAUDE.md"), "# Main\n@subdir\n");

        java.util.Set<String> processed = new java.util.HashSet<>();
        List<MemoryFileInfo> files = engine.processMemoryFile(
            root.resolve("CLAUDE.md").toString(), ClaudemdMemoryType.PROJECT, processed, true, 0, null);

        assertThat(files).as("目录不作为记忆文件加载，仅主文件").hasSize(1);
        assertThat(telemetry.getCounter("tengu_claude_md_permission_error"))
            .as("REQ-15△ 目录读不发 permission_error（CC EISDIR 静默 claudemd.ts:405）").isZero();
    }

    // ════════════════════════════════════════════════════════════════
    // processMdRules / processConditionedMdRules · claudemd.ts:697-788/1354-1397
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("processConditionedMdRules glob 匹配目标路径 (claudemd.ts:1354-1397)")
    void processConditionedMdRules_globMatch() throws Exception {
        // WHY: 条件规则（frontmatter paths）让 CLAUDE.md 只对匹配文件生效 —— 目录级记忆的
        //       核心机制（claudemd.ts:1197-1203 注释）。Project 规则 glob 相对 .claude 父目录。
        setUp(false);
        Path projectRoot = Files.createTempDirectory("cond-root");
        Files.createDirectories(projectRoot.resolve(".claude").resolve("rules"));
        Files.writeString(projectRoot.resolve(".claude").resolve("rules").resolve("cond.md"),
            "---\npaths:\n  - src/**.java\n  - docs/**.md\n---\nConditional rule body\n");
        Files.writeString(projectRoot.resolve(".claude").resolve("rules").resolve("plain.md"),
            "Unconditional rule body\n");

        java.util.Set<String> processed = new java.util.HashSet<>();
        String rulesDir = projectRoot.resolve(".claude").resolve("rules").toString();

        List<MemoryFileInfo> matched = engine.processConditionedMdRules(
            projectRoot.resolve("src/main/App.java").toString(), rulesDir,
            ClaudemdMemoryType.PROJECT, processed, false);

        assertThat(matched).as("src/**/*.java 匹配 cond.md，排除无 globs 的 plain.md")
            .extracting(MemoryFileInfo::path)
            .containsExactly(projectRoot.resolve(".claude").resolve("rules").resolve("cond.md").toString());
    }

    @Test
    @DisplayName("processConditionedMdRules 拒绝 ../ 逃逸与基准外路径 (claudemd.ts:1385-1394)")
    void processConditionedMdRules_rejectsTraversal() throws Exception {
        // WHY: 安全不变式 —— glob 相对基准（.claude 父目录）外路径不能匹配，防条件规则
        //       越界加载敏感文件（claudemd.ts:1385-1387 ignore() 对 ../ 抛错的注释）。
        setUp(false);
        Path projectRoot = Files.createTempDirectory("cond-traversal");
        Files.createDirectories(projectRoot.resolve(".claude").resolve("rules"));
        Files.writeString(projectRoot.resolve(".claude").resolve("rules").resolve("cond.md"),
            "---\npaths:\n  - ../outside/**\n---\nbody\n");

        java.util.Set<String> processed = new java.util.HashSet<>();
        String rulesDir = projectRoot.resolve(".claude").resolve("rules").toString();

        // ../outside 逃逸基准 → 拒绝
        List<MemoryFileInfo> matched = engine.processConditionedMdRules(
            projectRoot.resolve("../outside/x.md").toString(), rulesDir,
            ClaudemdMemoryType.PROJECT, processed, false);
        assertThat(matched).as("../ 相对路径被拒绝").isEmpty();
    }

    // ════════════════════════════════════════════════════════════════
    // getClaudeMds · claudemd.ts:1153-1195
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getClaudeMds MEMORY_INSTRUCTION_PROMPT 契约 (claudemd.ts:89-90/1153-1195)")
    void getClaudeMds_contract() {
        // WHY: getClaudeMds 是 claudeMd 注入 system prompt 的最终渲染（09 X12）。契约逐字：
        //       MEMORY_INSTRUCTION_PROMPT 前缀 + "Contents of {path}{desc}:\n\n{trim}",
        //       多条 \n\n 连接；空 → 空串。
        MemoryFileInfo project = MemoryFileInfo.of("/p/CLAUDE.md", ClaudemdMemoryType.PROJECT,
            "\n  # Project rules  \n", null);
        MemoryFileInfo local = MemoryFileInfo.of("/p/CLAUDE.local.md", ClaudemdMemoryType.LOCAL,
            "local\n", null);
        MemoryFileInfo autoMem = MemoryFileInfo.of("/m/MEMORY.md", ClaudemdMemoryType.AUTO_MEM,
            "auto\n", null);

        String out = engine.getClaudeMds(List.of(project, local, autoMem), null);

        assertThat(out).as("MEMORY_INSTRUCTION_PROMPT 前缀（逐字 claudemd.ts:89-90）")
            .startsWith("Codebase and user instructions are shown below. Be sure to adhere to "
                + "these instructions. IMPORTANT: These instructions OVERRIDE any default behavior "
                + "and you MUST follow them exactly as written.\n\n");
        assertThat(out)
            .as("Project 描述（claudemd.ts:1169-1170）")
            .contains("Contents of /p/CLAUDE.md (project instructions, checked into the codebase):\n\n# Project rules")
            .as("Local 描述（claudemd.ts:1171-1172）")
            .contains("Contents of /p/CLAUDE.local.md (user's private project instructions, not checked in):\n\nlocal")
            .as("AutoMem 描述（claudemd.ts:1175-1176）")
            .contains("Contents of /m/MEMORY.md (user's auto-memory, persists across conversations):\n\nauto");
    }

    @Test
    @DisplayName("getClaudeMds 空记忆返回空串 + filter 类型过滤 (claudemd.ts:1164/1190-1194)")
    void getClaudeMds_emptyAndFilter() {
        assertThat(engine.getClaudeMds(List.of(), null)).as("空列表 → 空串").isEmpty();

        MemoryFileInfo user = MemoryFileInfo.of("/u/CLAUDE.md", ClaudemdMemoryType.USER, "u\n", null);
        MemoryFileInfo project = MemoryFileInfo.of("/p/CLAUDE.md", ClaudemdMemoryType.PROJECT, "p\n", null);
        String onlyUser = engine.getClaudeMds(List.of(user, project),
            t -> t == ClaudemdMemoryType.USER);
        assertThat(onlyUser)
            .as("filter 仅保留 User")
            .contains("Contents of /u/CLAUDE.md")
            .doesNotContain("Contents of /p/CLAUDE.md");
    }

    @Test
    @DisplayName("getClaudeMds tengu_paper_halyard 开 → 跳过 Project/Local (claudemd.ts:1158-1166)")
    void getClaudeMds_paperHalyardGate() {
        // WHY: CC claudemd.ts:1158-1166 —— feature('tengu_paper_halyard') 开时 getClaudeMds
        //       跳过 Project/Local（paper_halyard = 记忆索引不注入项目级/本地级指令）。
        //       Java 经 paperHalyardGate supplier 注入；未注入（默认）→ 仍注入 Project/Local。
        MemoryFileInfo project = MemoryFileInfo.of("/p/CLAUDE.md", ClaudemdMemoryType.PROJECT, "p\n", null);
        MemoryFileInfo local = MemoryFileInfo.of("/p/CLAUDE.local.md", ClaudemdMemoryType.LOCAL, "l\n", null);
        MemoryFileInfo user = MemoryFileInfo.of("/u/CLAUDE.md", ClaudemdMemoryType.USER, "u\n", null);
        MemoryFileInfo autoMem = MemoryFileInfo.of("/m/MEMORY.md", ClaudemdMemoryType.AUTO_MEM, "a\n", null);

        // 默认（未注入）→ Project/Local 仍注入
        String off = engine.getClaudeMds(List.of(project, local, user, autoMem), null);
        assertThat(off)
            .as("feature 关（默认）→ Project 注入")
            .contains("Contents of /p/CLAUDE.md")
            .as("feature 关（默认）→ Local 注入")
            .contains("Contents of /p/CLAUDE.local.md")
            .as("feature 关（默认）→ User 注入")
            .contains("Contents of /u/CLAUDE.md");

        // 注入门控 → Project/Local 跳过，User/AutoMem 保留
        engine.setPaperHalyardGate(() -> true);
        String on = engine.getClaudeMds(List.of(project, local, user, autoMem), null);
        assertThat(on)
            .as("feature 开 → Project 跳过")
            .doesNotContain("Contents of /p/CLAUDE.md")
            .as("feature 开 → Local 跳过")
            .doesNotContain("Contents of /p/CLAUDE.local.md")
            .as("feature 开 → User 保留")
            .contains("Contents of /u/CLAUDE.md")
            .as("feature 开 → AutoMem 保留")
            .contains("Contents of /m/MEMORY.md");
    }

    @Test
    @DisplayName("getNestedMemoryAttachmentsForFile tengu_paper_halyard 开 → Phase3 nested Project/Local 跳过 (attachments.ts:1823-1835/1850-1852)")
    void getNestedMemoryAttachmentsForFile_paperHalyardGate() throws Exception {
        // WHY（探查 △-6 / R7 / OPD-CM5-F-05）：CC tengu_paper_halyard 门控有两处消费点 ——
        //   getClaudeMds（claudemd.ts:1158-1166，已由 getClaudeMds_paperHalyardGate 锁定）与
        //   getNestedMemoryAttachmentsForFile（attachments.ts:1823-1826 read +
        //   :1833-1835 Phase3 nested 目录 / :1850-1852 Phase4 cwd 级目录 filter）。
        //   Java 两处共用 paperHalyardGate supplier；本测试锁 nested 注入路径，feature 开时
        //   Project/Local 记忆文件不注入 LLM 上下文（记忆索引不承载项目级/本地级指令）。
        setUp(false);
        // Phase3 nested 目录 workspace/sub：CLAUDE.md(Project) + CLAUDE.local.md(Local)
        Path nested = Files.createDirectories(workspace.resolve("sub"));
        Files.writeString(nested.resolve("CLAUDE.md"), "# Nested\n");
        Files.writeString(nested.resolve("CLAUDE.local.md"), "local\n");
        String target = nested.resolve("src/main/App.java").toString();

        // 默认（未注入 gate）→ Project/Local 均注入（feature 关 = CC GB flag 缺省）
        List<MemoryFileInfo> off = engine.getNestedMemoryAttachmentsForFile(target,
            java.util.concurrent.ConcurrentHashMap.newKeySet(),
            ToolUseContext.createFileStateCache());
        assertThat(off)
            .as("feature 关（默认）→ nested CLAUDE.md(Project) 注入")
            .anyMatch(f -> f.path().equals(nested.resolve("CLAUDE.md").toString()))
            .as("feature 关（默认）→ nested CLAUDE.local.md(Local) 注入")
            .anyMatch(f -> f.path().equals(nested.resolve("CLAUDE.local.md").toString()));

        // 注入 gate=true → Project/Local 跳过（CC attachments.ts:1833-1835 filter）
        engine.setPaperHalyardGate(() -> true);
        List<MemoryFileInfo> on = engine.getNestedMemoryAttachmentsForFile(target,
            java.util.concurrent.ConcurrentHashMap.newKeySet(),
            ToolUseContext.createFileStateCache());
        assertThat(on)
            .as("feature 开 → nested CLAUDE.md(Project) 跳过")
            .noneMatch(f -> f.path().equals(nested.resolve("CLAUDE.md").toString()))
            .as("feature 开 → nested CLAUDE.local.md(Local) 跳过")
            .noneMatch(f -> f.path().equals(nested.resolve("CLAUDE.local.md").toString()));
    }

    // ════════════════════════════════════════════════════════════════
    // FIX-CL · resetGetMemoryFilesCache one-shot 发射 / filterInjectedMemoryFiles 门控 /
    //          getExternalClaudeMdIncludes / 目录遍历到 root 前停止 / 嵌套目录加载
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("resetGetMemoryFilesCache 清缓存 + 下次 miss 发射 InstructionsLoaded('compact') 且 one-shot 消费 (claudemd.ts:1124-1130)")
    void resetGetMemoryFilesCache_clearsCacheAndFiresHookOnce() throws Exception {
        // WHY: FIX-CL 压缩失效接线 —— 压缩后 getMemoryFiles 缓存必须失效，且 InstructionsLoaded
        //       hook 上报真实 reason 'compact' 而非误报 'session_start'（claudemd.ts:1088-1108）。
        setUp(false);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("CLAUDE.md"), "# Project\n");

        HookRegistry registry = new HookRegistry();
        // [ODF-B4] 异步 fire-and-forget：hook 在独立 executor 跑，断言前必须 await 完成
        List<HookEvent> captured = new CopyOnWriteArrayList<>();
        registry.register("claudemd-capture",
            event -> { captured.add(event); return com.nexusai.application.agent.permission.hook.GenericHook.HookResult.proceed(); },
            HookEventType.INSTRUCTIONS_LOADED);
        engine.setHookRegistry(registry);

        // 初始态（shouldFireHook=true, nextEagerLoadReason='session_start'）→ 首次 miss 发射 session_start
        engine.getMemoryFiles(false);
        awaitTrue(() -> captured.stream().anyMatch(e -> "session_start".equals(e.data().get("load_reason"))), 3000);
        assertThat(captured).as("首次加载发射 session_start")
            .anyMatch(e -> "session_start".equals(e.data().get("load_reason")));

        captured.clear();
        engine.resetGetMemoryFilesCache("compact");
        engine.getMemoryFiles(false);
        awaitTrue(() -> captured.stream().anyMatch(e -> "compact".equals(e.data().get("load_reason"))), 3000);
        assertThat(captured)
            .as("reset 后缓存 miss → 发射 compact（load_reason=compact, path 含 Project CLAUDE.md）")
            .anyMatch(e -> "compact".equals(e.data().get("load_reason")))
            .anyMatch(e -> ((String) e.data().get("file_path")).endsWith("CLAUDE.md"));

        captured.clear();
        engine.getMemoryFiles(false);
        assertThat(captured).as("缓存命中 + one-shot 已消费 → 不再发射").isEmpty();
    }

    /** 轮询等待异步条件（hook 在独立 executor 跑，getMemoryFiles 不 await）· 超时抛 AssertionError。 */
    private static void awaitTrue(java.util.function.BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("异步条件超时未满足 (timeoutMs=" + timeoutMs + ")");
    }

    @Test
    @DisplayName("resetGetMemoryFilesCache 后立即读缓存命中（先读后 reset 语义）")
    void resetGetMemoryFilesCache_recomputes() throws Exception {
        // WHY: reset 必须清缓存 —— 否则后续 getMemoryFiles 命中陈旧文件列表（FIX-CL 接线证据）。
        setUp(false);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("CLAUDE.md"), "# V1\n");
        List<MemoryFileInfo> first = engine.getMemoryFiles(false);
        Files.writeString(workspace.resolve("CLAUDE.md"), "# V1\n# V2\n");

        assertThat(engine.getMemoryFiles(false)).as("缓存未失效 → 仍为 V1").isEqualTo(first);
        engine.resetGetMemoryFilesCache("compact");
        List<MemoryFileInfo> reloaded = engine.getMemoryFiles(false);
        assertThat(reloaded.get(reloaded.size() - 1).content())
            .as("reset 后重算 → 反映磁盘 V2")
            .contains("# V2");
    }

    @Test
    @DisplayName("InstructionsLoaded hook fire-and-forget：getMemoryFiles 主路径不等待 hook，最终异步完成 (claudemd.ts:1060)")
    void getMemoryFiles_instructionsLoadedFireAndForget() throws Exception {
        // WHY（ODF-B4 唯一目标）：CC claudemd.ts:1060 void executeInstructionsLoadedHooks
        //   （fire-and-forget 不 await）+ hooks.ts:4335 executeHooksOutsideREPL（REPL 外异步跑完）。
        //   audit/observability hook 若阻塞主路径，启动加载被拖慢 = 行为漂移；本测试锁"主路径
        //   快速返回 + hook 最终异步触发"双不变量。同步实现（executor.submit 前）下阻塞 hook
        //   拖住 getMemoryFiles → elapsedMs 断言失败（RED）。
        setUp(false);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("CLAUDE.md"), "# Project\n");

        HookRegistry registry = new HookRegistry();
        CountDownLatch hookEntered = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        List<HookEvent> captured = new CopyOnWriteArrayList<>();
        registry.register("claudemd-blocking",
            event -> {
                hookEntered.countDown();
                try {
                    assertThat(releaseHook.await(5, TimeUnit.SECONDS))
                        .as("hook 放行闸超时").isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                captured.add(event);
                return com.nexusai.application.agent.permission.hook.GenericHook.HookResult.proceed();
            },
            HookEventType.INSTRUCTIONS_LOADED);
        engine.setHookRegistry(registry);

        long start = System.nanoTime();
        List<MemoryFileInfo> files = engine.getMemoryFiles(false);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(files).as("getMemoryFiles 正常返回记忆文件").isNotEmpty();
        assertThat(hookEntered.await(2, TimeUnit.SECONDS))
            .as("阻塞 hook 已在异步线程进入（未占用主路径）").isTrue();
        assertThat(elapsedMs)
            .as("主路径不等待阻塞 audit hook（fire-and-forget，claudemd.ts:1060）")
            .isLessThan(2000);

        // 放行 hook → 异步完成并触发（captured 最终收到 session_start）
        releaseHook.countDown();
        awaitTrue(() -> captured.stream().anyMatch(e -> "session_start".equals(e.data().get("load_reason"))), 5000);
        assertThat(captured).as("hook 异步完成后捕获 load_reason=session_start")
            .anyMatch(e -> "session_start".equals(e.data().get("load_reason")));
    }

    @Test
    @DisplayName("InstructionsLoaded hook executor 已关闭 → warn 降级不崩溃、不静默丢任务")
    void getMemoryFiles_hookExecutorShutdown_degrades() throws Exception {
        // WHY（验收 §5.4）：executor 关闭时提交前 isShutdown 检查 → warn 跳过，getMemoryFiles
        //       主路径不崩溃、hook 不静默执行（避免丢任务/竞态）。
        setUp(false);
        Files.writeString(workspace.resolve("CLAUDE.md"), "# Project\n");

        HookRegistry registry = new HookRegistry();
        List<HookEvent> captured = new CopyOnWriteArrayList<>();
        registry.register("claudemd-capture",
            event -> { captured.add(event); return com.nexusai.application.agent.permission.hook.GenericHook.HookResult.proceed(); },
            HookEventType.INSTRUCTIONS_LOADED);
        engine.setHookRegistry(registry);

        ExecutorService dead = Executors.newSingleThreadExecutor();
        dead.shutdown();
        engine.setHookExecutor(dead);

        assertThatCode(() -> engine.getMemoryFiles(false))
            .as("executor 已关闭 → 主路径不崩溃")
            .doesNotThrowAnyException();
        assertThat(captured).as("executor 已关闭 → hook 不执行（warn 降级，不静默丢任务）").isEmpty();
    }

    @Test
    @DisplayName("InstructionsLoaded hook executor 提交被拒（RejectedExecutionException）→ warn 降级不崩溃")
    void getMemoryFiles_hookExecutorRejects_submitDegrades() throws Exception {
        // WHY（验收 §5.4）：提交前 isShutdown=false 但 submit 抛 RejectedExecutionException 的
        //       竞态窗口必须被 catch → warn 降级，不向上抛（fail loud 但只 warn 不阻断主路径）。
        setUp(false);
        Files.writeString(workspace.resolve("CLAUDE.md"), "# Project\n");

        HookRegistry registry = new HookRegistry();
        engine.setHookRegistry(registry);
        engine.setHookExecutor(new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return java.util.List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException { return true; }
            @Override public void execute(Runnable command) {
                throw new java.util.concurrent.RejectedExecutionException("rejected");
            }
        });

        assertThatCode(() -> engine.getMemoryFiles(false))
            .as("executor 提交被拒 → 主路径不崩溃")
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("filterInjectedMemoryFiles tengu_moth_copse 门控 (claudemd.ts:1142-1151)")
    void filterInjectedMemoryFiles_mothCopseGate() {
        // WHY: tengu_moth_copse 开启时 findRelevantMemories 预取经 attachments 暴露记忆文件，
        //       MEMORY.md 索引不再注入 system prompt → context builder 过滤 AutoMem/TeamMem。
        MemoryFileInfo auto = MemoryFileInfo.of("/a/MEMORY.md", ClaudemdMemoryType.AUTO_MEM, "auto", null);
        MemoryFileInfo user = MemoryFileInfo.of("/u/CLAUDE.md", ClaudemdMemoryType.USER, "user", null);
        MemoryFileInfo team = MemoryFileInfo.of("/t/MEMORY.md", ClaudemdMemoryType.TEAM_MEM, "team", null);

        assertThat(engine.filterInjectedMemoryFiles(List.of(auto, user, team)))
            .as("gate 未注入（默认 false）→ 原样返回")
            .containsExactly(auto, user, team);

        engine.setMothCopseGate(() -> true);
        assertThat(engine.filterInjectedMemoryFiles(List.of(auto, user, team)))
            .as("gate 开启 → 剔除 AutoMem/TeamMem，保留 User")
            .containsExactly(user);
    }

    @Test
    @DisplayName("getExternalClaudeMdIncludes 非 User + parent + cwd 外路径 → 外部 include (claudemd.ts:1404-1418)")
    void getExternalClaudeMdIncludes_detectsExternalExcludesUser() {
        // WHY: 外部 @include 审批检查 —— 仅非 User 类型（User 恒可 include 外部）且不在 originalCwd
        //       内的 parent 文件算外部。前端审批流依赖此判定（claudemd.ts:1399-1430）。
        String cwd = workspace.toString();
        MemoryFileInfo externalProject = new MemoryFileInfo("/out/CLAUDE.md", ClaudemdMemoryType.PROJECT,
            "x", "/p/CLAUDE.md", null, false, null);
        MemoryFileInfo internalProject = new MemoryFileInfo(Paths.get(cwd, "CLAUDE.md").toString(),
            ClaudemdMemoryType.PROJECT, "x", "/p/CLAUDE.md", null, false, null);
        MemoryFileInfo externalUser = new MemoryFileInfo("/out/CLAUDE.md", ClaudemdMemoryType.USER,
            "x", "/u/CLAUDE.md", null, false, null);

        assertThat(engine.hasExternalClaudeMdIncludes(List.of(externalProject)))
            .as("cwd 外 + parent → 外部 include").isTrue();
        assertThat(engine.hasExternalClaudeMdIncludes(List.of(internalProject)))
            .as("cwd 内 → 非外部").isFalse();
        assertThat(engine.hasExternalClaudeMdIncludes(List.of(externalUser)))
            .as("User 类型恒可 include 外部 → 不属审批范畴").isFalse();
    }

    @Test
    @DisplayName("includeExternal 审批态：approved=true → getMemoryFiles(false) 含外部 @include；否则不含 (claudemd.ts:798-801 / OPD-CM5-F-09)")
    void getMemoryFiles_includeExternalApprovalState() throws Exception {
        // WHY（OPD-CM5-F-09 · 探查 △-15 / T-9 / R11）：CC includeExternal =
        //   forceIncludeExternal || config.hasClaudeMdExternalIncludesApproved（claudemd.ts:798-801）。
        //   Java 无 getCurrentProjectConfig → 注入式 Supplier（默认 false，CC config 缺省）。审批通过后
        //   正常加载（forceIncludeExternal=false）也应包含外部 @include —— 否则前端审批无实际效果。
        setUp(false);
        Path externalDir = Files.createTempDirectory("external-include-dir");
        Path externalMd = externalDir.resolve("external.md");
        Files.writeString(externalMd, "# External include\n");
        // Managed CLAUDE.md @include 外部绝对路径（originalCwd=workspace 外）。
        // 用正斜杠：Java 端 @include 解析器对反斜杠按转义处理（Windows 盘符路径会错），
        // 正斜杠形态 Paths.get 正常解析为绝对路径（跨平台一致）。
        String externalPath = externalMd.toAbsolutePath().toString().replace('\\', '/');
        Files.createDirectories(managedPath);
        Files.writeString(managedPath.resolve("CLAUDE.md"),
            "# Managed\n@" + externalPath + "\n");

        // 未审批（approval supplier 未注入 → 恒 false）→ 外部 include 不含
        List<MemoryFileInfo> notApproved = engine.getMemoryFiles(false);
        assertThat(notApproved)
            .as("未审批 → getMemoryFiles(false) 不含外部 @include（claudemd.ts:798-801）")
            .noneMatch(f -> f.path().equals(externalMd.toAbsolutePath().toString()));

        // 审批（fresh engine 注入 approval supplier=true）→ 外部 include 含
        ClaudemdEngine approvedEngine = new ClaudemdEngine(autoMemPaths, detection,
            () -> workspace.toString(), () -> true, () -> true, () -> true, () -> false, () -> List.of());
        approvedEngine.setHasClaudeMdExternalIncludesApproved(() -> true);
        List<MemoryFileInfo> approved = approvedEngine.getMemoryFiles(false);
        assertThat(approved)
            .as("审批后 → getMemoryFiles(false) 含外部 @include（forceIncludeExternal=false 也加载）")
            .anyMatch(f -> f.path().equals(externalMd.toAbsolutePath().toString()));
    }

    @Test
    @DisplayName("shouldShowClaudeMdExternalIncludesWarning 审批/警告已示 → false；未审批且含外部 → true (claudemd.ts:1420-1430 / OPD-CM5-F-09)")
    void shouldShowClaudeMdExternalIncludesWarning_approvalState() throws Exception {
        // WHY（OPD-CM5-F-09）：CC 先查 hasClaudeMdExternalIncludesApproved ||
        //   hasClaudeMdExternalIncludesWarningShown，任一 true → false（claudemd.ts:1423-1426）；
        //   否则 hasExternalClaudeMdIncludes(getMemoryFiles(true))（:1428）。旧 Java 无审批态 →
        //   恒返回外部判定，审批/拒绝后仍弹窗（R11）。
        setUp(false);
        Path externalDir = Files.createTempDirectory("external-include-dir");
        Path externalMd = externalDir.resolve("external.md");
        Files.writeString(externalMd, "# External include\n");
        // 正斜杠形态（同 getMemoryFiles_includeExternalApprovalState）
        String externalPath = externalMd.toAbsolutePath().toString().replace('\\', '/');
        Files.createDirectories(managedPath);
        Files.writeString(managedPath.resolve("CLAUDE.md"),
            "# Managed\n@" + externalPath + "\n");

        // 未审批 + 未显示 → true（存在外部 include）
        assertThat(engine.shouldShowClaudeMdExternalIncludesWarning())
            .as("未审批且未显示过警告 → 应显示（claudemd.ts:1423-1428）").isTrue();

        // 审批 → false
        engine.setHasClaudeMdExternalIncludesApproved(() -> true);
        assertThat(engine.shouldShowClaudeMdExternalIncludesWarning())
            .as("已审批 → 不再弹窗（claudemd.ts:1423-1424）").isFalse();

        // 拒绝（approved=false 但 warningShown=true）→ false
        engine.setHasClaudeMdExternalIncludesApproved(() -> false);
        engine.setHasClaudeMdExternalIncludesWarningShown(() -> true);
        assertThat(engine.shouldShowClaudeMdExternalIncludesWarning())
            .as("已拒绝但显示过警告 → 不再弹窗（claudemd.ts:1425-1426）").isFalse();
    }

    @Test
    @DisplayName("getMemoryFiles cwd 恰为 root → 无 Project/Local（到 root 前停止）(claudemd.ts:854-857)")
    void getMemoryFiles_cwdIsRoot_noProjectLocal() throws Exception {
        // WHY: CC while(currentDir !== parse(currentDir).root) —— root 不入 dirs；cwd 恰为 root
        //       时 dirs 空 → 不加载任何 Project/Local（FIX-CL 删 Java 额外 root push 的对齐证据）。
        String root = Paths.get(workspace.toString()).getRoot().toString();
        engine = new ClaudemdEngine(autoMemPaths, detection,
            () -> root, () -> true, () -> true, () -> true, () -> false, () -> List.of());
        Files.createDirectories(managedPath);
        Files.writeString(managedPath.resolve("CLAUDE.md"), "# Managed root\n");
        Files.createDirectories(configHome);
        Files.writeString(configHome.resolve("CLAUDE.md"), "# User root\n");

        List<String> typeSeq = engine.getMemoryFiles(false).stream()
            .map(f -> f.type().ccName()).toList();
        assertThat(typeSeq)
            .as("cwd=root → 仅 Managed+User，无 Project/Local")
            .containsExactly("Managed", "User");
    }

    @Test
    @DisplayName("getMemoryFilesForNestedDirectory 嵌套目录 CLAUDE.md + .claude/CLAUDE.md + 条件规则 (claudemd.ts:1249-1318)")
    void getMemoryFilesForNestedDirectory_loadsDirFiles() throws Exception {
        // WHY: 嵌套记忆加载（attachments.ts:1809-1845 消费方）—— 对 target 路径匹配 glob 的
        //       目录级记忆文件，CLAUDE.md 家族 + 条件规则一并加载（FIX-CL 补 API）。
        setUp(false);
        Path nested = Files.createDirectories(workspace.resolve("sub"));
        Files.writeString(nested.resolve("CLAUDE.md"), "# Nested\n");
        Files.createDirectories(nested.resolve(".claude"));
        Files.writeString(nested.resolve(".claude").resolve("CLAUDE.md"), "# Nested dot\n");
        Files.createDirectories(nested.resolve(".claude").resolve("rules"));
        Files.writeString(nested.resolve(".claude").resolve("rules").resolve("cond.md"),
            "---\npaths:\n  - src/**.java\n---\nConditional rule body\n");

        Set<String> processed = new LinkedHashSet<>();
        // 目标路径必须在嵌套目录内（条件规则 glob 相对 .claude 父目录 = nested）
        List<MemoryFileInfo> files = engine.getMemoryFilesForNestedDirectory(
            nested.toString(), nested.resolve("src/main/App.java").toString(), processed);

        assertThat(files.stream().map(MemoryFileInfo::path).toList())
            .as("CLAUDE.md + .claude/CLAUDE.md + 条件规则命中 src/**/*.java")
            .contains(
                nested.resolve("CLAUDE.md").toString(),
                nested.resolve(".claude").resolve("CLAUDE.md").toString(),
                nested.resolve(".claude").resolve("rules").resolve("cond.md").toString());
    }

    // ════════════════════════════════════════════════════════════════
    // （FIX-CL 已删 prependClaudeMdContext 双轨实现 —— 生产唯一实现为 AgentLoopContext.prependUserContext，
    //   LlmAgentLoopChainTest 已覆盖 map 渲染 + isMeta 语义）
    // ════════════════════════════════════════════════════════════════
    // isMemoryFilePath / getAllMemoryFilePaths · claudemd.ts:1435-1479
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("isMemoryFilePath 判定 CLAUDE.md/CLAUDE.local.md/.claude/rules/*.md · 平台原生分隔符 (claudemd.ts:1435-1452, CLD-05⑤)")
    void isMemoryFilePath_detection() {
        String sep = java.io.File.separator;
        assertThat(engine.isMemoryFilePath("x" + sep + "CLAUDE.md")).isTrue();
        assertThat(engine.isMemoryFilePath("x" + sep + "CLAUDE.local.md")).isTrue();
        assertThat(engine.isMemoryFilePath("x" + sep + ".claude" + sep + "rules" + sep + "foo.md")).isTrue();
        assertThat(engine.isMemoryFilePath("x" + sep + "src" + sep + "main" + sep + "App.java")).isFalse();
        assertThat(engine.isMemoryFilePath("x" + sep + "CLAUDE.mds")).isFalse();
        // CLD-05⑤：Windows 上正斜杠输入不命中（CC 平台原生分隔符 `${sep}.claude${sep}rules${sep}`）
        if (java.io.File.separatorChar == '\\') {
            assertThat(engine.isMemoryFilePath("C:/x/.claude/rules/a.md"))
                .as("Windows 正斜杠输入不命中（accept-more 收敛）").isFalse();
            assertThat(engine.isMemoryFilePath("C:\\x\\.claude\\rules\\a.md"))
                .as("Windows 原生反斜杠命中").isTrue();
        }
    }

    @Test
    @DisplayName("getAllMemoryFilePaths 合并 files + readFileState keys (claudemd.ts:1460-1479)")
    void getAllMemoryFilePaths_combine() {
        MemoryFileInfo nonEmpty = MemoryFileInfo.of("/p/CLAUDE.md", ClaudemdMemoryType.PROJECT, "x", null);
        MemoryFileInfo empty = MemoryFileInfo.of("/p/CLAUDE.local.md", ClaudemdMemoryType.LOCAL, "  ", null);

        List<String> paths = engine.getAllMemoryFilePaths(
            List.of(nonEmpty, empty),
            List.of(java.nio.file.Paths.get("/p/.claude/rules/foo.md").toString(),
                "/p/src/App.java", "/p/CLAUDE.md"));

        assertThat(paths).as("content 空的不计入 + readFileState 匹配记忆模式的计入 + 去重")
            .containsExactlyInAnyOrder(
                "/p/CLAUDE.md",
                java.nio.file.Paths.get("/p/.claude/rules/foo.md").toString());
    }

    // ════════════════════════════════════════════════════════════════
    // ODF-B4R-LAZY · lazy-load 发射点对齐（memoryFilesToAttachments + InstructionsLoaded）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("memoryFilesToAttachments 三态 loadReason 发射 + 非 instructions 不发射 (attachments.ts:1710-1770)")
    void memoryFilesToAttachments_loadReasonThreeStates() throws Exception {
        // WHY（ODF-B4R-LAZY 唯一目标）：lazy 路径必须按 CC 三态上报 load_reason —— globs →
        //   'path_glob_match' / parent → 'include' / else → 'nested_traversal'（attachments.ts:1760-1763）。
        //   且仅 instructions-type（User/Project/Local/Managed）发射（isInstructionsMemoryType 门控，
        //   claudemd.ts:1077-1086）—— AutoMem/TeamMem 独立 memory 系统不发射。
        setUp(false);
        HookRegistry registry = new HookRegistry();
        List<HookEvent> captured = new CopyOnWriteArrayList<>();
        registry.register("lazy-capture",
            event -> { captured.add(event); return com.nexusai.application.agent.permission.hook.GenericHook.HookResult.proceed(); },
            HookEventType.INSTRUCTIONS_LOADED);
        engine.setHookRegistry(registry);

        Set<String> loaded = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<MemoryFileInfo> files = List.of(
            new MemoryFileInfo("/a/globs.md", ClaudemdMemoryType.PROJECT, "g",
                null, List.of("src/**"), false, null),   // globs 非空 → path_glob_match
            new MemoryFileInfo("/b/include.md", ClaudemdMemoryType.LOCAL, "i",
                "/p/CLAUDE.md", null, false, null),       // parent 非空 → include
            new MemoryFileInfo("/c/plain.md", ClaudemdMemoryType.MANAGED, "p",
                null, null, false, null),                  // 两者皆无 → nested_traversal
            new MemoryFileInfo("/d/auto.md", ClaudemdMemoryType.AUTO_MEM, "a",
                null, null, false, null));                 // 非 instructions → 不发射

        List<MemoryFileInfo> newly = engine.memoryFilesToAttachments(files, loaded,
            ToolUseContext.createFileStateCache(), "/trigger/App.java");
        awaitTrue(() -> captured.size() >= 3, 5000);

        assertThat(newly).as("全部文件均新加载（无重复）").hasSize(4);
        assertThat(loaded).as("已加载路径集合写入全部 4 路径")
            .contains("/a/globs.md", "/b/include.md", "/c/plain.md", "/d/auto.md");
        assertThat(captured).as("仅 instructions-type 3 文件发射（AutoMem 不发射）").hasSize(3);
        assertThat(captured).as("load_reason 三态精确对齐")
            .anyMatch(e -> e.data().get("file_path").equals("/a/globs.md")
                && "path_glob_match".equals(e.data().get("load_reason")))
            .anyMatch(e -> e.data().get("file_path").equals("/b/include.md")
                && "include".equals(e.data().get("load_reason")))
            .anyMatch(e -> e.data().get("file_path").equals("/c/plain.md")
                && "nested_traversal".equals(e.data().get("load_reason")));
        assertThat(captured).as("trigger_file_path 上报（CC triggerFilePath，attachments.ts:1766）")
            .allMatch(e -> "/trigger/App.java".equals(e.data().get("trigger_file_path")));
    }

    @Test
    @DisplayName("memoryFilesToAttachments loadedNestedMemoryPaths 去重 → 不重复发射 (attachments.ts:1723-1726)")
    void memoryFilesToAttachments_dedupNoDoubleFire() throws Exception {
        // WHY：loadedNestedMemoryPaths 是跨函数/跨 turn 去重唯一源（CC 注释：readFileState 是
        //   100 条目 LRU，繁忙会话会驱逐 → 单靠它会在每次驱逐周期重新注入同一 CLAUDE.md）。已加载
        //   路径再次出现 → 跳过，不重复发射（防 lazy 路径 instructionsLoaded 双发）。
        setUp(false);
        HookRegistry registry = new HookRegistry();
        List<HookEvent> captured = new CopyOnWriteArrayList<>();
        registry.register("lazy-capture",
            event -> { captured.add(event); return com.nexusai.application.agent.permission.hook.GenericHook.HookResult.proceed(); },
            HookEventType.INSTRUCTIONS_LOADED);
        engine.setHookRegistry(registry);

        Set<String> loaded = java.util.concurrent.ConcurrentHashMap.newKeySet();
        com.nexusai.application.agent.tool.FileStateCache cache = ToolUseContext.createFileStateCache();
        MemoryFileInfo f = MemoryFileInfo.of("/p/CLAUDE.md", ClaudemdMemoryType.PROJECT, "x", null);
        engine.memoryFilesToAttachments(List.of(f), loaded, cache, "/t/App.java");
        engine.memoryFilesToAttachments(List.of(f), loaded, cache, "/t/App.java");   // 第二次：已加载
        awaitTrue(() -> captured.size() >= 1, 5000);

        assertThat(captured).as("同一文件二次出现不重复发射（去重）").hasSize(1);
    }

    @Test
    @DisplayName("getNestedMemoryAttachments 触发集消费：三孤儿子函数接线 + instructions 发射 + triggers 清空 (attachments.ts:2165-2190)")
    void getNestedMemoryAttachments_consumesTriggersFiresHooks() throws Exception {
        // WHY（ODF-B4R-LAZY 验收 §3/§5）：getNestedMemoryAttachments 为三个孤儿子函数
        //   （getManagedAndUserConditionalRules / getMemoryFilesForNestedDirectory /
        //   getConditionalRulesForCwdLevelDirectory）的生产消费方 —— 本测试一次性覆盖触发集
        //   驱动（Phase1 Managed/User 条件规则 + Phase3 nested 目录 + Phase4 cwd 级目录）、
        //   instructions-type 发射、以及消费后 triggers 清空。
        setUp(false);
        // Phase 1: Managed 条件规则（glob 匹配 target）
        Files.createDirectories(Paths.get(engine.getManagedClaudeRulesDir()));
        Files.writeString(Paths.get(engine.getManagedClaudeRulesDir(), "managed-cond.md"),
            "---\npaths:\n  - src/**.java\n---\nManaged conditional\n");
        // Phase 1: User 条件规则
        Files.createDirectories(Paths.get(engine.getUserClaudeRulesDir()));
        Files.writeString(Paths.get(engine.getUserClaudeRulesDir(), "user-cond.md"),
            "---\npaths:\n  - src/**.java\n---\nUser conditional\n");
        // Phase 3: nested 目录 workspace/sub（originalCwd=workspace）：CLAUDE.md + 条件规则
        Path nested = Files.createDirectories(workspace.resolve("sub"));
        Files.writeString(nested.resolve("CLAUDE.md"), "# Nested\n");
        Files.createDirectories(nested.resolve(".claude").resolve("rules"));
        Files.writeString(nested.resolve(".claude").resolve("rules").resolve("nested-cond.md"),
            "---\npaths:\n  - src/**.java\n---\nNested conditional\n");
        // Phase 4: cwd 级目录 workspace（root→CWD）：仅条件规则
        Files.createDirectories(workspace.resolve(".claude").resolve("rules"));
        Files.writeString(workspace.resolve(".claude").resolve("rules").resolve("cwd-cond.md"),
            "---\npaths:\n  - src/**.java\n---\nCWD conditional\n");

        HookRegistry registry = new HookRegistry();
        List<HookEvent> captured = new CopyOnWriteArrayList<>();
        registry.register("lazy-capture",
            event -> { captured.add(event); return com.nexusai.application.agent.permission.hook.GenericHook.HookResult.proceed(); },
            HookEventType.INSTRUCTIONS_LOADED);
        engine.setHookRegistry(registry);

        String target = nested.resolve("src/main/App.java").toString();
        Set<String> triggers = java.util.concurrent.ConcurrentHashMap.newKeySet();
        triggers.add(target);
        Set<String> loaded = java.util.concurrent.ConcurrentHashMap.newKeySet();

        List<MemoryFileInfo> newly = engine.getNestedMemoryAttachments(triggers, loaded,
            ToolUseContext.createFileStateCache());
        awaitTrue(() -> captured.size() >= 5, 5000);

        assertThat(triggers).as("触发集消费后清空（CC :2186 clear）").isEmpty();
        assertThat(loaded).as("三阶段文件均写入已加载集合")
            .contains(
                Paths.get(engine.getManagedClaudeRulesDir(), "managed-cond.md").toString(),
                Paths.get(engine.getUserClaudeRulesDir(), "user-cond.md").toString(),
                nested.resolve("CLAUDE.md").toString(),
                nested.resolve(".claude").resolve("rules").resolve("nested-cond.md").toString(),
                workspace.resolve(".claude").resolve("rules").resolve("cwd-cond.md").toString());
        assertThat(newly).as("新加载列表非空（接线三孤儿子函数）").isNotEmpty();
        assertThat(captured).as("instructions-type 均发射 InstructionsLoaded（含 trigger_file_path）")
            .allMatch(e -> target.equals(e.data().get("trigger_file_path")));
    }

    @Test
    @DisplayName("getNestedMemoryAttachments 空触发集 → 快速返回 (attachments.ts:2168-2172)")
    void getNestedMemoryAttachments_emptyTriggers_fastReturn() {
        // WHY：CC 注释 check triggers first —— 常见情况是空触发集，先查触发集避免 getAppState()
        //   React 渲染等待（attachments.ts:2167-2170 注释）。空集 → 返回空列表、不抛异常、不访问
        //   文件系统（孤儿消费方空跑安全）。
        Set<String> triggers = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Set<String> loaded = java.util.concurrent.ConcurrentHashMap.newKeySet();
        assertThat(engine.getNestedMemoryAttachments(triggers, loaded,
            ToolUseContext.createFileStateCache()))
            .as("空触发集 → 空列表").isEmpty();
        assertThat(engine.getNestedMemoryAttachments(null, loaded,
            ToolUseContext.createFileStateCache()))
            .as("null 触发集 → 空列表（不 NPE）").isEmpty();
    }

    @Test
    @DisplayName("pathInAllowedWorkingPath 判定: originalCwd + 附加目录全量判定 (filesystem.ts:683-707 / OPD-CM5-F-07)")
    void pathInAllowedWorkingPath_allWorkingDirectories() throws Exception {
        // WHY（探查 △-14 / T-6 / OPD-CM5-F-07）：CC getNestedMemoryAttachmentsForFile 早期返回用
        //   pathInAllowedWorkingPath(filePath, appState.toolPermissionContext)（permission context 全量判定：
        //   allWorkingDirectories = originalCwd + additionalWorkingDirectories，filesystem.ts:667-674），
        //   Java 原仅 pathInWorkingPath(originalCwd)（仅 cwd 包含判定）——cwd 内但不在允许路径的文件被放行，
        //   权限面弱于 CC。本测试锁定补判定：originalCwd 内 + 附加目录内均判定通过；两者之外判定拒绝 →
        //   getNestedMemoryAttachmentsForFile 早期返回空。
        setUp(false);
        Path addDir = Files.createTempDirectory("add-dir");
        // 附加目录注入（CLAUDE_CODE_ADDITIONAL_DIRECTORIES 无法进程内改 env → 覆写包私有
        // getAdditionalDirectoriesForClaudeMd，与 SkillChangeDetectorTest:319 注入模式一致）
        ClaudemdEngine engineWithAddDir = new ClaudemdEngine(autoMemPaths, detection,
            () -> workspace.toString(), () -> true, () -> true, () -> true, () -> false, () -> List.of()) {
            @Override
            List<String> getAdditionalDirectoriesForClaudeMd() {
                return List.of(addDir.toString());
            }
        };

        // 1. originalCwd 内 → 通过
        assertThat(engineWithAddDir.pathInAllowedWorkingPath(workspace.resolve("src/App.java").toString()))
            .as("originalCwd 内路径判定通过").isTrue();
        // 2. 附加目录内 → 通过（CC allWorkingDirectories = originalCwd + additionalWorkingDirectories）
        assertThat(engineWithAddDir.pathInAllowedWorkingPath(addDir.resolve("App.java").toString()))
            .as("附加目录内路径判定通过（对齐 CC additionalWorkingDirectories）").isTrue();
        // 3. 两者之外 → 拒绝
        Path outside = Files.createTempDirectory("outside");
        assertThat(engineWithAddDir.pathInAllowedWorkingPath(outside.resolve("x.md").toString()))
            .as("工作目录外路径判定拒绝").isFalse();

        // 4. getNestedMemoryAttachmentsForFile 早期返回联动：allowed working path 外触发文件 → 空
        Path outsideFile = Files.writeString(outside.resolve("target.md"), "# t\n");
        Set<String> loaded = java.util.concurrent.ConcurrentHashMap.newKeySet();
        assertThat(engineWithAddDir.getNestedMemoryAttachmentsForFile(outsideFile.toString(), loaded,
            ToolUseContext.createFileStateCache()))
            .as("allowed working path 外触发文件 → 早期返回空（对齐 CC attachments.ts:1801-1803）").isEmpty();
    }

    @Test
    @DisplayName("E2E 生产接线: ReadFileTool 触发集写入 → loop 消费 → InstructionsLoaded 发射 + 三孤儿子函数生产调用 (规则九, 返工 finding 1/2)")
    void endToEnd_readFileProducer_loopConsumption_firesHooks() throws Exception {
        // WHY（规则九 · 返工 finding 1/2）：原 4 测试直接构造 triggers/loaded 调
        //   engine.getNestedMemoryAttachments，绕过生产接线，无法发现"触发集无生产者 + 接线副本隔离"
        //   → lazy-load 生产链路死代码。本测试走真实生产路径：ReadFileTool（CC FileReadTool.ts:848/870/1038
        //   生产者）经 per-turn TUC 写触发集（与 base 共享同一 KeySetView 实例，keepOrCopyMutableSet 修复）
        //   → loop 消费表达式（LlmAgentLoop:3126-3130 原样 getNestedMemoryAttachments(baseTuc
        //   .nestedMemoryAttachmentTriggers(), baseTuc.loadedNestedMemoryPaths(), baseTuc.readFileState())）
        //   → InstructionsLoaded 发射 + 三孤儿子函数生产调用。
        setUp(false);
        // [CLD-06 环境修复] ReadFileTool 触发路径经 PathGuard.resolve → toRealPath（Windows 8.3
        //   短名展开为长名）；engine originalCwd 若用 workspace.toString()（短名）→ pathInWorkingPath
        //   判定失败 → lazy 加载空跑（hooks 0 发射）→ 本机（8.3 短名生效）E2E 恒失败。
        Path realWorkspace = workspace.toRealPath();
        engine = new ClaudemdEngine(autoMemPaths, detection,
            () -> realWorkspace.toString(), () -> true, () -> true, () -> true,
            () -> false, () -> List.of());
        // fixtures（同 getNestedMemoryAttachments_consumesTriggersFiresHooks）：Phase1 managed/user 条件
        //   规则 + Phase3 nested CLAUDE.md/cond + Phase4 cwd 级条件规则
        Files.createDirectories(Paths.get(engine.getManagedClaudeRulesDir()));
        Files.writeString(Paths.get(engine.getManagedClaudeRulesDir(), "managed-cond.md"),
            "---\npaths:\n  - src/**.java\n---\nManaged\n");
        Files.createDirectories(Paths.get(engine.getUserClaudeRulesDir()));
        Files.writeString(Paths.get(engine.getUserClaudeRulesDir(), "user-cond.md"),
            "---\npaths:\n  - src/**.java\n---\nUser\n");
        Path nested = Files.createDirectories(workspace.resolve("sub"));
        Files.writeString(nested.resolve("CLAUDE.md"), "# Nested\n");
        Files.createDirectories(nested.resolve(".claude").resolve("rules"));
        Files.writeString(nested.resolve(".claude").resolve("rules").resolve("nested-cond.md"),
            "---\npaths:\n  - src/**.java\n---\nNested\n");
        Files.createDirectories(workspace.resolve(".claude").resolve("rules"));
        Files.writeString(workspace.resolve(".claude").resolve("rules").resolve("cwd-cond.md"),
            "---\npaths:\n  - src/**.java\n---\nCWD\n");

        HookRegistry registry = new HookRegistry();
        List<HookEvent> captured = new CopyOnWriteArrayList<>();
        registry.register("lazy-capture",
            event -> { captured.add(event); return com.nexusai.application.agent.permission.hook.GenericHook.HookResult.proceed(); },
            HookEventType.INSTRUCTIONS_LOADED);
        engine.setHookRegistry(registry);

        // ① 生产 base TUC（buildBaseToolUseContext 等价：compact ctor 持会话级 KeySetView）
        ToolUseContext baseTuc = ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            com.nexusai.application.agent.permission.PermissionMode.DEFAULT,
            List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP,
            List.of(), null, com.nexusai.application.agent.permission.PermissionMode.DEFAULT);
        // ② per-turn TUC 派生（loop toolExecContext → copyWith）：必须共享同一 Set 实例（finding 2 修复）
        ToolUseContext perTurnTuc = baseTuc.withQueryTracking(Map.of());
        assertThat(perTurnTuc.nestedMemoryAttachmentTriggers())
            .as("per-turn 派生必须与 base 共享同一触发集实例 (keepOrCopyMutableSet 修复副本隔离)")
            .isSameAs(baseTuc.nestedMemoryAttachmentTriggers());
        assertThat(perTurnTuc.loadedNestedMemoryPaths())
            .as("per-turn 派生必须与 base 共享同一已加载集实例 (keepOrCopyMutableSet 修复副本隔离)")
            .isSameAs(baseTuc.loadedNestedMemoryPaths());

        // ③ 生产读取：ReadFileTool 读 workspace/sub/src/App.java → 写触发集（finding 1 生产者，CC :1038）
        Path app = Files.createDirectories(workspace.resolve("sub").resolve("src"))
            .resolve("App.java");
        Files.writeString(app, "class App {}\n");
        com.fasterxml.jackson.databind.JsonNode input =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("file_path", "sub/src/App.java");
        ReadFileTool readFileTool = new ReadFileTool(new PathGuard(workspace));
        readFileTool.execute(new ToolUseBlock("call-e2e-1", "read_file", input), perTurnTuc);

        // 期望触发路径 = guard.resolve（toRealPath 规范化后，与 ReadFileTool 写入的 file.toString() 一致）
        String expectedTrigger = new PathGuard(workspace).resolve("sub/src/App.java").toString();
        assertThat(baseTuc.nestedMemoryAttachmentTriggers())
            .as("ReadFileTool 读成功后写入触发集，loop 消费端（base TUC 共享实例）可见")
            .contains(expectedTrigger);

        // ④ loop 消费表达式（LlmAgentLoop:3126-3130 原样）
        List<MemoryFileInfo> newly = engine.getNestedMemoryAttachments(
            baseTuc.nestedMemoryAttachmentTriggers(), baseTuc.loadedNestedMemoryPaths(),
            baseTuc.readFileState());
        awaitTrue(() -> captured.size() >= 5, 5000);

        assertThat(baseTuc.nestedMemoryAttachmentTriggers())
            .as("消费后触发集清空（CC :2191 clear，同一共享实例）").isEmpty();
        assertThat(newly).as("lazy 加载非空（接线三孤儿子函数）").isNotEmpty();
        // 三孤儿子函数生产调用证据：captured 含 Phase1（managed/user）+ Phase3（nested CLAUDE.md）
        //   + Phase4（cwd 级规则）文件，证明三条生产调用点均真实执行
        assertThat(captured).as("InstructionsLoaded 覆盖三孤儿子函数产物（Phase1/3/4 全部真实调用）")
            .extracting(e -> e.data().get("file_path"))
            .contains(
                Paths.get(engine.getManagedClaudeRulesDir(), "managed-cond.md").toString(),
                Paths.get(engine.getUserClaudeRulesDir(), "user-cond.md").toString(),
                workspace.toRealPath().resolve("sub").resolve("CLAUDE.md").toString(),
                workspace.toRealPath().resolve(".claude").resolve("rules").resolve("cwd-cond.md").toString());
        assertThat(captured).as("触发文件作为 trigger_file_path 上报（CC triggerFilePath）")
            .allMatch(e -> expectedTrigger.equals(e.data().get("trigger_file_path")));
    }

    // ════════════════════════════════════════════════════════════════
    // IMP-M-R2-P0-CLD · CLD-01..06 + NEW-5 对齐测试
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("CLD-01: getMemoryFiles 并发首调单飞 —— 一次计算 + initial_load/InstructionsLoaded 各一次 (claudemd.ts:790 lodash memoize Promise)")
    void getMemoryFiles_concurrentFirstCall_singleFlight() throws Exception {
        // WHY（OPD-R2-CLD-01/G-96）：CC lodash memoize 缓存 Promise —— 并发首次调用共享一次
        //   计算、一次 hook 发射；旧 ConcurrentHashMap get→compute 非原子 → 双线程同时 miss
        //   双算双发（多会话 JVM 启动窗口双发 tengu_claudemd__initial_load / InstructionsLoaded）。
        setUp(false);
        Files.createDirectories(configHome);
        Files.writeString(configHome.resolve("CLAUDE.md"), "# User root\n");
        RecordingTelemetry telemetry = new RecordingTelemetry();
        engine.setTelemetry(telemetry);
        HookRegistry registry = new HookRegistry();
        List<HookEvent> captured = new CopyOnWriteArrayList<>();
        registry.register("cl01-capture",
            event -> { captured.add(event); return com.nexusai.application.agent.permission.hook.GenericHook.HookResult.proceed(); },
            HookEventType.INSTRUCTIONS_LOADED);
        engine.setHookRegistry(registry);
        engine.clearMemoryFileCaches();

        int n = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<java.util.concurrent.Future<List<MemoryFileInfo>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return engine.getMemoryFiles(false);
                }));
            }
            start.countDown();
            List<MemoryFileInfo> first = futures.get(0).get(10, TimeUnit.SECONDS);
            for (java.util.concurrent.Future<List<MemoryFileInfo>> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS))
                    .as("并发线程必须拿到同一计算结果（单飞）").isEqualTo(first);
            }
        } finally {
            pool.shutdownNow();
        }
        awaitTrue(() -> captured.size() >= 1, 5000);

        assertThat(telemetry.events)
            .as("tengu_claudemd__initial_load 仅发射一次（单飞：一次计算一次发射）")
            .containsExactly("tengu_claudemd__initial_load");
        assertThat(captured)
            .as("InstructionsLoaded 仅发射一次（User 文件，单飞）").hasSize(1);
    }

    @Test
    @DisplayName("CLD-02: memoryFilesToAttachments 双源去重（readFileState.has）+ 注入后注册 isPartialView (attachments.ts:1719-1750)")
    void memoryFilesToAttachments_readFileStateDedupAndRegister() throws Exception {
        // WHY（OPD-R2-CLD-02/G-95）：CC 双源去重 = loadedNestedMemoryPaths + readFileState.has
        //   （readFileState 命中 = 模型本会话已 Read/Edit/Write，内容已在上下文 → 跳过注入）；
        //   注入后 set(path,{content:rawContent??content,timestamp,offset:undefined,limit:undefined,
        //   isPartialView:contentDiffersFromDisk}) → Edit/Write 门禁拒绝 partial-view 未 Read 文件。
        setUp(false);
        com.nexusai.application.agent.tool.FileStateCache cache = ToolUseContext.createFileStateCache();
        Set<String> loaded = java.util.concurrent.ConcurrentHashMap.newKeySet();

        // ① 内容与磁盘不一致（strip 后 differs）→ 注册 rawContent + isPartialView=true
        MemoryFileInfo partial = new MemoryFileInfo("/p/CLAUDE.md", ClaudemdMemoryType.PROJECT,
            "injected", null, null, true, "raw\ncontent");
        List<MemoryFileInfo> injected = engine.memoryFilesToAttachments(
            List.of(partial), loaded, cache, "/t/App.java");
        assertThat(injected).as("内容不一致文件正常注入").hasSize(1);
        String key = java.nio.file.Paths.get("/p/CLAUDE.md").toAbsolutePath().normalize().toString();
        assertThat(cache.has(key)).as("注入后必须注册 readFileState（CC :1742）").isTrue();
        ToolUseContext.ReadState state = cache.get(key);
        assertThat(state.isPartialView()).as("contentDiffersFromDisk → isPartialView=true").isTrue();
        assertThat(state.content()).as("content = rawContent ?? content").isEqualTo("raw\ncontent");
        assertThat(state.offset()).as("offset=undefined 等价（null）").isNull();
        assertThat(state.limit()).as("limit=undefined 等价（null）").isNull();

        // ② 已注册（=本会话已 Read/注入）→ readFileState.has 去重跳过（CC :1725）
        List<MemoryFileInfo> second = engine.memoryFilesToAttachments(
            List.of(partial), loaded, cache, "/t/App.java");
        assertThat(second).as("readFileState 命中 → 跳过注入（双源去重）").isEmpty();

        // ③ 内容与磁盘一致 → isPartialView=false + content=处理内容
        MemoryFileInfo exact = new MemoryFileInfo("/q/CLAUDE.local.md", ClaudemdMemoryType.LOCAL,
            "body", null, null, false, null);
        List<MemoryFileInfo> injected2 = engine.memoryFilesToAttachments(
            List.of(exact), loaded, cache, "/t/App.java");
        assertThat(injected2).hasSize(1);
        ToolUseContext.ReadState exactState = cache.get(
            java.nio.file.Paths.get("/q/CLAUDE.local.md").toAbsolutePath().normalize().toString());
        assertThat(exactState.isPartialView()).as("内容一致 → isPartialView=false（可过 Edit/Write 门禁）").isFalse();
        assertThat(exactState.content()).isEqualTo("body");
    }

    @Test
    @DisplayName("CLD-03: parseFrontmatter 内联数组 paths: [a.md, b.md] → List（CC Bun.YAML 子集）")
    void parseFrontmatter_inlineArrayPaths() {
        // WHY（OPD-R2-CLD-03/G-89）：旧 parseSimpleYaml 把内联数组当字符串
        //   "[a.md, b.md]" → splitPathInFrontmatter 产出带方括号 glob → 条件规则永不命中。
        ClaudemdLexer.FrontmatterResult fm = ClaudemdLexer.parseFrontmatter(
            "---\npaths: [a.md, b.md]\n---\nbody");
        assertThat(fm.frontmatter().get("paths")).isEqualTo(List.of("a.md", "b.md"));
        assertThat(fm.content()).as("frontmatter 剥离后内容").isEqualTo("body");

        // 引号形态（Bun.YAML 等价）
        ClaudemdLexer.FrontmatterResult fm2 = ClaudemdLexer.parseFrontmatter(
            "---\npaths: [\"a.md\", 'b.md']\n---\nbody");
        assertThat(fm2.frontmatter().get("paths")).isEqualTo(List.of("a.md", "b.md"));

        // 空数组 → 空 List（CC 无 paths 语义：patterns 空 → 无 globs）
        ClaudemdLexer.FrontmatterResult fm3 = ClaudemdLexer.parseFrontmatter(
            "---\npaths: []\n---\nbody");
        assertThat(fm3.frontmatter().get("paths")).isEqualTo(List.of());

        // splitPathInFrontmatter 接受 List（CC string | string[]，frontmatterParser.ts:189-192）
        assertThat(ClaudemdLexer.splitPathInFrontmatter(List.of("a.md", "b.md")))
            .isEqualTo(List.of("a.md", "b.md"));
    }

    @Test
    @DisplayName("CLD-03: 条件规则 paths 内联数组命中（G-89：glob 不再带方括号）")
    void processConditionedMdRules_inlineArrayPathsHits() throws Exception {
        setUp(false);
        Path nested = Files.createDirectories(workspace.resolve("sub"));
        Files.createDirectories(nested.resolve(".claude").resolve("rules"));
        Files.writeString(nested.resolve(".claude").resolve("rules").resolve("cond.md"),
            "---\npaths: [src/**.java, src/**.kt]\n---\nInline array rule\n");
        Set<String> processed = new LinkedHashSet<>();
        List<MemoryFileInfo> files = engine.getMemoryFilesForNestedDirectory(
            nested.toString(), nested.resolve("src/main/App.java").toString(), processed);

        assertThat(files.stream().map(MemoryFileInfo::path).toList())
            .as("内联数组 paths 解析为独立 glob → 条件规则命中（旧实现带方括号永不命中）")
            .contains(nested.resolve(".claude").resolve("rules").resolve("cond.md").toString());
    }

    @Test
    @DisplayName("CLD-03: frontmatter 闭合标记容差（CC FRONTMATTER_REGEX：首个 --- 即闭合，\\s* 与 \\n? 均可空）")
    void parseFrontmatter_closeMarkerTrailingWhitespace() {
        ClaudemdLexer.FrontmatterResult fm = ClaudemdLexer.parseFrontmatter(
            "---\npaths:\n  - a.md\n--- \nbody");
        // C-15 收敛（OPD-CM5-C-15）：frontmatter 解析统一走共享 ParseSkillFrontmatter（真实 YAML，
        //   CC Bun.YAML 等价）→ 多行列表解析为 YAML List（旧 parseSimpleYaml 手写子集拼为逗号串
        //   String）。消费方 parseFrontmatterPaths 对 String|List 双型处理，最终 globs 一致。
        assertThat(fm.frontmatter().get("paths")).isEqualTo(List.of("a.md"));
        assertThat(fm.content()).isEqualTo("body");
        // CC 正则 ([\\s\\S]*?)---\\s*\\n? 惰性捕获 → 首个 `---` 出现即闭合（\\s* 与 \\n? 均可匹配
        // 空串，不要求闭合标记后是空白/换行）：`---foo` 闭合，frontmatter 止于该 `---`，
        // content=`foo\nbody`（E4 实测 frontmatterParser.ts:123 FRONTMATTER_REGEX）
        ClaudemdLexer.FrontmatterResult fm2 = ClaudemdLexer.parseFrontmatter(
            "---\npaths: [a.md]\n---foo\nbody");
        assertThat(fm2.frontmatter().get("paths")).as("---foo 闭合（CC 首个 --- 即闭合）")
            .isEqualTo(List.of("a.md"));
        assertThat(fm2.content()).isEqualTo("foo\nbody");
        // `----` 同理：首个 `---` = 前三个横线 → 闭合，content=`-\nbody`（旧实现与 CC 均闭合，
        // 返工轮 1 的「accept-more」断言为假）
        ClaudemdLexer.FrontmatterResult fm3 = ClaudemdLexer.parseFrontmatter(
            "---\npaths: [a.md]\n----\nbody");
        assertThat(fm3.frontmatter().get("paths")).as("---- 闭合（CC 首个 --- 即闭合）")
            .isEqualTo(List.of("a.md"));
        assertThat(fm3.content()).isEqualTo("-\nbody");
    }

    @Test
    @DisplayName("CLD-04: expandPath Windows MinGW /c/... → native（path.ts:67-76）")
    void expandPath_windowsMinGwConversion() {
        if (java.io.File.separatorChar == '\\') {
            assertThat(ClaudemdLexer.expandPath("/c/Users/x", "/base/doc.md"))
                .as("MSYS2/Git Bash /c/... → C:\\...（CC path.ts:67-76 + windowsPaths.ts:162-167）")
                .isEqualTo("C:\\Users\\x");
            // CC 守卫 /^\/[a-z]\//i 不匹配 /cygdrive/...（windowsPaths.ts cygdrive 分支仅直调可达）→ 不转换
            assertThat(ClaudemdLexer.expandPath("/cygdrive/d/proj", "/base/doc.md"))
                .as("expandPath 不转换 /cygdrive/（CC 守卫不匹配）").isNotEqualTo("D:\\proj");
            // //server/share：守卫不匹配，Java Paths.get 原生识别 UNC → 归一（CC normalize 等价；
            //   Windows Path 的 UNC 根 toString 带尾反斜杠，Node normalize 去尾 —— 平台表达差）
            assertThat(ClaudemdLexer.expandPath("//server/share", "/base/doc.md"))
                .as("UNC → 平台归一形态").isEqualTo(
                    java.nio.file.Paths.get("//server/share").normalize().toString());
        } else {
            assertThat(ClaudemdLexer.expandPath("/c/Users/x", "/base/doc.md"))
                .as("非 Windows 不转换（CC getPlatform()==='windows' 门控）").isEqualTo("/c/Users/x");
        }
        // 相对路径不受 MinGW 转换影响
        assertThat(ClaudemdLexer.expandPath("./docs/notes.md", "/base/doc.md"))
            .isEqualTo(java.nio.file.Paths.get("/base", "docs", "notes.md").normalize().toString());
    }

    @Test
    @DisplayName("CLD-05①: stripHtmlComments type-6/7 HTML 块内注释不剥离（<div> 到空行）")
    void stripHtmlComments_keepsCommentInsideHtmlBlock() {
        // WHY（OPD-R2-CLD-05①/G-90）：marked gfm:false type-6/7 HTML 块持续到空行，块内独立
        //   `<!-- note -->` 属块内容不剥离；旧 lex 在 isSpecialBlockStart 断开 → 注释被误删。
        String input = "<div>\n<!-- note -->\n</div>\n";
        ClaudemdLexer.StripResult r = ClaudemdLexer.stripHtmlComments(input);
        assertThat(r.content()).as("HTML 块内注释保留").isEqualTo(input);
        assertThat(r.stripped()).as("无剥离发生（stripped=false，对齐 CC :317-328）").isFalse();

        // 对照：独立注释行（type-2 块）仍剥离
        ClaudemdLexer.StripResult r2 = ClaudemdLexer.stripHtmlComments("<!-- note -->\nbody\n");
        assertThat(r2.content()).isEqualTo("body\n");
        assertThat(r2.stripped()).isTrue();
    }

    @Test
    @DisplayName("CLD-05②: extractIncludePaths 未配对反引号容忍继续扫描（marked 行内容忍）")
    void extractIncludePaths_unmatchedBacktickContinues() {
        // WHY（OPD-R2-CLD-05②/G-91）：未配对 `` ` `` 不构成 codespan → 后续文本继续扫 @path；
        //   旧实现 break 停止整段 → 后续 include 丢失。
        String expected = java.nio.file.Paths.get("/base", "file.md").normalize().toString();
        assertThat(ClaudemdLexer.extractIncludePaths("use ` once then @./file.md", "/base/doc.md"))
            .as("未闭合反引号后 @path 仍提取").contains(expected);
        // 配对反引号（codespan）内不提取
        assertThat(ClaudemdLexer.extractIncludePaths("use `@./skip.md` then @./file.md", "/base/doc.md"))
            .as("codespan 内跳过，span 外提取").containsExactly(expected);
    }

    @Test
    @DisplayName("CLD-05③: ClaudemdGlob 否定字符类/通配转义/父目录传播（ignore 库语义）")
    void claudemdGlob_patternSemantics() {
        // 字符类否定 [!a]（gitignore）→ Java [^a]
        assertThat(ClaudemdGlob.empty().add(List.of("[!a].md")).ignores("b.md"))
            .as("[!a].md 匹配 b.md").isTrue();
        assertThat(ClaudemdGlob.empty().add(List.of("[!a].md")).ignores("a.md"))
            .as("[!a].md 不匹配 a.md").isFalse();
        // 通配转义 \*
        assertThat(ClaudemdGlob.empty().add(List.of("\\*.md")).ignores("*.md"))
            .as("\\*.md 匹配字面 *.md").isTrue();
        assertThat(ClaudemdGlob.empty().add(List.of("\\*.md")).ignores("x.md"))
            .as("\\*.md 不匹配 x.md").isFalse();
        // 父目录忽略传播：目录被忽略 → 其下全部忽略
        assertThat(ClaudemdGlob.empty().add(List.of("docs")).ignores("docs/index.md"))
            .as("pattern docs 匹配祖先目录 → 传播").isTrue();
        assertThat(ClaudemdGlob.empty().add(List.of("docs/")).ignores("docs/index.md"))
            .as("trailing / 目录模式匹配祖先目录 → 传播").isTrue();
        assertThat(ClaudemdGlob.empty().add(List.of("src/foo")).ignores("src/foo/bar.ts"))
            .as("含斜杠 pattern 匹配祖先目录 → 传播").isTrue();
        // 目录模式不匹配同名文件本身（gitignore trailing / 仅目录）
        assertThat(ClaudemdGlob.empty().add(List.of("docs/")).ignores("docs"))
            .as("目录模式不匹配同名文件").isFalse();
        // 中段 ** 跨段保持（npm ignore globstar 任意位置；v4.0 复验未列差异）
        assertThat(ClaudemdGlob.empty().add(List.of("a**b")).ignores("a/b"))
            .as("中段 a**b 跨段（保持既有语义）").isTrue();
    }

    @Test
    @DisplayName("NEW-5: truncateEntrypointContent WARNING 字节形态 formatFileSize（memdir.ts:89-98）")
    void truncateEntrypointContent_warningFormatFileSize() throws Exception {
        // WHY（NEW-5/OPD-R2-CLD-05⑥）：WARNING 文本对齐 CC formatFileSize —— 25000 字节 →
        //   "24.4KB (limit: 24.4KB)"（旧实现裸字节 "25000 bytes (limit: 25000)"）。
        setUp(false);
        // 仅字节截断：单行 25001 字符
        String longLine = "x".repeat(25001);
        MemoryFileInfo info = engine.parseMemoryFileContent(longLine, "/m/MEMORY.md",
            ClaudemdMemoryType.AUTO_MEM);
        assertThat(info.content())
            .as("字节截断 WARNING 用 formatFileSize（24.4KB）")
            .contains("> WARNING: MEMORY.md is 24.4KB (limit: 24.4KB) — index entries are too long");
        assertThat(info.content())
            .as("旧裸字节形态必须消失").doesNotContain("25000 bytes");
        assertThat(info.contentDiffersFromDisk()).as("截断 → contentDiffersFromDisk=true").isTrue();

        // 仅行截断：201 行短行
        String manyLines = String.join("\n", java.util.Collections.nCopies(201, "line")) + "\n";
        MemoryFileInfo lineInfo = engine.parseMemoryFileContent(manyLines, "/m/MEMORY.md",
            ClaudemdMemoryType.AUTO_MEM);
        assertThat(lineInfo.content())
            .as("行截断 WARNING 保持 lines 形态")
            .contains("> WARNING: MEMORY.md is 201 lines (limit: 200).");

        // 双截断：201 行且超字节（40400 字节 → 39.5KB）
        String manyLongLines = String.join("\n", java.util.Collections.nCopies(201, "x".repeat(200))) + "\n";
        MemoryFileInfo bothInfo = engine.parseMemoryFileContent(manyLongLines, "/m/MEMORY.md",
            ClaudemdMemoryType.AUTO_MEM);
        assertThat(bothInfo.content())
            .as("双截断 WARNING 第二段用 formatFileSize（40400 字节 → 39.5KB）")
            .contains("lines and 39.5KB");
    }

    // ════════════════════════════════════════════════════════════════
    // ODF-A4 · 单来源 bean 启动验证（双注册收敛后唯一 @Bean）
    // ════════════════════════════════════════════════════════════════

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("claudemdEngine 单 @Bean 加载：无 BeanDefinitionOverrideException + bean 单一 + 注入方同一实例 (ODF-A4)")
    void claudemdEngine_singleSourceBean_loadsWithoutOverride(CapturedOutput output) {
        // WHY（ODF-A4 唯一目标）：ClaudemdEngine 双注册收敛后为单来源 @Bean（ToolRegistrationConfig:633）。
        //   allow-bean-definition-overriding 未配置（默认 false，全仓 grep 0 命中）→ 覆盖冲突才 fail loud。
        //   本测试锁"单来源加载成功 + getBeanNamesForType 长度=1 + field/构造两式注入方命中同一实例 +
        //   启动日志出现注册行"四重不变量。
        new ApplicationContextRunner()
            .withUserConfiguration(ClaudemdEngineSingletonConfig.class)
            .run(ctx -> {
                assertThat(ctx.getStartupFailure())
                    .as("单来源 @Bean 加载不得抛 BeanDefinitionOverrideException")
                    .isNull();
                assertThat(ctx.getBeanNamesForType(ClaudemdEngine.class))
                    .as("getBeanNamesForType(ClaudemdEngine) 长度 = 1（唯一来源）")
                    .hasSize(1);
                ClaudemdEngine singleton = ctx.getBean(ClaudemdEngine.class);
                assertThat(ctx.getBean(FieldInjectConsumer.class).engine)
                    .as("field 注入方（对齐 ChatService:98 / AgentLoopContextFactory:101）命中同一实例")
                    .isSameAs(singleton);
                assertThat(ctx.getBean(CtorInjectConsumer.class).engine)
                    .as("构造注入方（对齐 UserContextProvider:83 / TeamMemorySyncService:61）命中同一实例")
                    .isSameAs(singleton);
                assertThat(output)
                    .as("启动日志出现 '注册 ClaudemdEngine → claudemd 引擎'（验收 §5.5）")
                    .contains("注册 ClaudemdEngine → claudemd 引擎");
            });
    }

    @Test
    @DisplayName("重复注册 claudemdEngine → refresh 抛 BeanDefinitionOverrideException（allow-bean-definition-overriding 默认 false）(ODF-A4)")
    void claudemdEngine_duplicateRegistration_failsLoud() {
        // WHY（规则十二 · 显式失败）：单来源不变量 —— 若未来误加 @Component 或第二处 @Bean，
        //   默认配置下 Spring 必须 fail loud（BeanDefinitionOverrideException），不得静默覆盖
        //   （ODF-A4 §3 要求 2：覆盖冲突才报警）。
        new ApplicationContextRunner()
            .withUserConfiguration(ClaudemdEngineSingletonConfig.class, DuplicateRegistrationConfig.class)
            .run(ctx -> {
                Throwable ex = ctx.getStartupFailure();
                assertThat(ex)
                    .as("第二处注册必须显式抛 BeanDefinitionOverrideException（不静默覆盖）")
                    .isInstanceOf(org.springframework.beans.factory.support.BeanDefinitionOverrideException.class);
            });
    }

    /**
     * ODF-A4 测试装配：复刻 ToolRegistrationConfig:632-646 的 claudemdEngine @Bean 定义
     * （构造 + mothCopseGate 接线 + 注册日志逐字一致），最小依赖 AutoMemPaths/MemoryFileDetection/
     * FeatureFlags。
     *
     * <p>不 @Import 整个 ToolRegistrationConfig（55 个 @Bean，依赖仓库/LLM/DB 过重）—— 复用
     * R32B7a2 的 TestConfig 模式（最小依赖 + 显式注册目标 bean）。field 注入消费方对齐
     * ChatService:98 / AgentLoopContextFactory:101 注入式样；构造注入消费方对齐
     * UserContextProvider:83 / TeamMemorySyncService:61 注入式样。
     */
    @Configuration
    static class ClaudemdEngineSingletonConfig {

        private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ClaudemdEngineSingletonConfig.class);

        @Bean
        AutoMemPaths autoMemPaths() {
            return new AutoMemPaths(
                () -> System.getProperty("user.dir"),  // CC getProjectRoot 等价（测试非断言点）
                () -> null, () -> null, () -> null);
        }

        @Bean
        MemoryFileDetection memoryFileDetection(AutoMemPaths autoMemPaths) {
            // IMP-CM-09 双门控：测试 bean 双开关全开
            return new MemoryFileDetection(autoMemPaths, () -> true, () -> true);
        }

        @Bean
        com.nexusai.application.agent.loop.FeatureFlags featureFlags() {
            return com.nexusai.application.agent.loop.FeatureFlags.ALL_DISABLED;
        }

        @Bean
        ClaudemdEngine claudemdEngine(AutoMemPaths autoMemPaths,
                                      MemoryFileDetection memoryFileDetection,
                                      com.nexusai.application.agent.loop.FeatureFlags featureFlags) {
            ClaudemdEngine engine = new ClaudemdEngine(autoMemPaths, memoryFileDetection);
            engine.setMothCopseGate(() -> featureFlags != null && featureFlags.tenguMothCopse());
            // F-02（OPD-CM5-F-02）：teamMemoryEnabled 接 FeatureFlags.teamMem() —— 与 ToolRegistrationConfig
            //   claudemdEngine @Bean 同步（BACK 装配）；FeatureFlags.ALL_DISABLED → teamMem=false → 入口不注入
            engine.setTeamMemoryEnabled(() -> featureFlags != null && featureFlags.teamMem());
            log.info("IMP-M-P2-4/FIX-CL: 注册 ClaudemdEngine → claudemd 引擎 (claudemd.ts, DEL-M-32 替代, "
                + "mothCopseGate=tengu_moth_copse 真实门控 FIX-FR; "
                + "teamMem=" + (featureFlags != null && featureFlags.teamMem()) + " F-02 teamMemoryEnabled)");
            return engine;
        }

        @Bean
        FieldInjectConsumer fieldInjectConsumer() {
            return new FieldInjectConsumer();
        }

        @Bean
        CtorInjectConsumer ctorInjectConsumer(ClaudemdEngine claudemdEngine) {
            return new CtorInjectConsumer(claudemdEngine);
        }
    }

    /** ODF-A4 负例触发方：第二处 claudemdEngine @Bean 定义（模拟误加 @Component/重复注册）。 */
    @Configuration
    static class DuplicateRegistrationConfig {
        @Bean
        ClaudemdEngine claudemdEngine(AutoMemPaths autoMemPaths, MemoryFileDetection memoryFileDetection) {
            return new ClaudemdEngine(autoMemPaths, memoryFileDetection);
        }
    }

    /** field 注入消费方 · 对齐 ChatService:98 / AgentLoopContextFactory:101 注入式样。 */
    static class FieldInjectConsumer {
        @Autowired(required = false)
        ClaudemdEngine engine;
    }

    /** 构造注入消费方 · 对齐 UserContextProvider:83 / TeamMemorySyncService:61 注入式样。 */
    static class CtorInjectConsumer {
        final ClaudemdEngine engine;

        CtorInjectConsumer(ClaudemdEngine engine) {
            this.engine = engine;
        }
    }

    /** 记录发射事件名的 Telemetry 假实现（参照 SessionFileAccessHooksTest.RecordingTelemetry 模式）。 */
    private static final class RecordingTelemetry extends com.nexusai.application.agent.telemetry.Telemetry {
        final List<String> events = new java.util.ArrayList<>();

        @Override
        public void recordEvent(String eventName, java.util.Map<String, Object> attributes) {
            events.add(eventName);
            super.recordEvent(eventName, attributes);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // WF-1B / G7 / DEL-04：生产构造器 originalCwdSupplier 走 CwdResolution.getOriginalCwdLayer
    // （对齐 CC claudemd.ts:851 getOriginalCwd()）
    // ════════════════════════════════════════════════════════════════

    /**
     * WHY（规则九 · 测试验证意图）：CC {@code getMemoryFiles}（claudemd.ts:851）以
     * {@code getOriginalCwd()} 作为 Project/Local CLAUDE.md 向上遍历的扫描根 ——
     * {@code getOriginalCwd()} 返回 STATE.originalCwd（启动 cwd，随 worktree/resume 重锚）。
     * Java 生产 2 参构造器旧实现 {@code () -> System.getProperty("user.dir")} 固定 JVM 启动目录，
     * 在会话绑定项目场景扫描根错位（G7）。须走 {@code CwdResolution.getOriginalCwdLayer()} ——
     * 绑定项目层覆盖 user.dir 时取对扫描根。本测试钉死"生产构造器扫描根锚定绑定项目"语义。
     */
    // ════════════════════════════════════════════════════════════════
    // IMP-F1-2 resolveExcludePatterns 完整实现（OPD-CM5-F-03 / claudemd.ts:547-612）
    // ════════════════════════════════════════════════════════════════

    /** 构造带 claudeMdExcludes 的引擎（8 参注入式构造器）；TeamMem 默认关（exclude 不涉）。 */
    private ClaudemdEngine engineWithExcludes(List<String> excludes) {
        return new ClaudemdEngine(autoMemPaths, detection,
            () -> workspace.toString(), () -> true, () -> true, () -> true,
            () -> false, () -> excludes);
    }

    @Test
    @DisplayName("isClaudeMdExcluded picomatch 语义：**/code/CLAUDE.md + *.md（claudemd.ts:572，OPD-CM5-F-03）")
    void isClaudeMdExcluded_picomatchSemantics() {
        // WHY: F-03 决策把 claudeMdExcludes 匹配从 gitignore 语义 ClaudemdGlob 换成 picomatch
        //      语义（claudemd.ts:572）——basename 不跨层（*.md 不匹配 dir/foo.md）、dot:true、
        //      realpath 展开（resolveExcludePatterns）。旧 ClaudemdGlob 的任意深度 basename
        //      语义会导致 **/code/CLAUDE.md 误排除 /x/CLAUDE.md。
        ClaudemdEngine e = engineWithExcludes(List.of("**/code/CLAUDE.md", "*.md"));

        // **/code/CLAUDE.md：globstar 前导匹配 code/ 前缀任意深度
        assertThat(e.isClaudeMdExcluded("/x/code/CLAUDE.md", ClaudemdMemoryType.PROJECT)).isTrue();
        assertThat(e.isClaudeMdExcluded("code/CLAUDE.md", ClaudemdMemoryType.PROJECT)).isTrue();
        assertThat(e.isClaudeMdExcluded("/x/CLAUDE.md", ClaudemdMemoryType.PROJECT))
            .as("**/code/ 仅命中 code 前缀，/x/CLAUDE.md 不排除").isFalse();
        // *.md 单段匹配（basename 规则：picomatch 全路径锚定，无 gitignore 任意深度 basename）
        assertThat(e.isClaudeMdExcluded("foo.md", ClaudemdMemoryType.USER)).isTrue();
        assertThat(e.isClaudeMdExcluded("dir/foo.md", ClaudemdMemoryType.USER))
            .as("*.md 不跨段匹配 dir/foo.md（picomatch basename 规则与 ClaudemdGlob 的差异）").isFalse();
        // 类型守卫：仅 User/Project/Local（claudemd.ts:548-550）
        assertThat(e.isClaudeMdExcluded("/x/code/CLAUDE.md", ClaudemdMemoryType.TEAM_MEM)).isFalse();
        assertThat(e.isClaudeMdExcluded("/x/code/CLAUDE.md", ClaudemdMemoryType.AUTO_MEM)).isFalse();
        assertThat(e.isClaudeMdExcluded("/x/code/CLAUDE.md", ClaudemdMemoryType.MANAGED)).isFalse();
    }

    @Test
    @DisplayName("isClaudeMdExcluded picomatch extglob/brace/中段 **/dot 语义（claudemd.ts:572）")
    void isClaudeMdExcluded_picomatchExtGlobBraceMidStar() {
        // WHY: 探查 △-3 差异面逐项验证——extglob（@(a|b)）、brace（{1..3}）、中段 **（a**b
        //      退化单 * 不跨 /）、dot 文件（*.md 匹配 .md）。settings 示例 pattern
        //      "**/some-dir/.claude/rules/**" 命中 .claude 隐藏目录。
        ClaudemdEngine rules = engineWithExcludes(List.of("**/some-dir/.claude/rules/**"));
        assertThat(rules.isClaudeMdExcluded("/x/some-dir/.claude/rules/r.md", ClaudemdMemoryType.PROJECT)).isTrue();
        assertThat(rules.isClaudeMdExcluded("/some-dir/.claude/rules/r.md", ClaudemdMemoryType.PROJECT)).isTrue();
        assertThat(rules.isClaudeMdExcluded("/x/some-dir/other/r.md", ClaudemdMemoryType.PROJECT))
            .as("仅 rules 目录下排除").isFalse();

        ClaudemdEngine midStar = engineWithExcludes(List.of("a**b"));
        assertThat(midStar.isClaudeMdExcluded("ab", ClaudemdMemoryType.LOCAL))
            .as("中段 ** 零字符（ab）").isTrue();
        assertThat(midStar.isClaudeMdExcluded("aXb", ClaudemdMemoryType.LOCAL)).isTrue();
        assertThat(midStar.isClaudeMdExcluded("a/b", ClaudemdMemoryType.LOCAL))
            .as("中段 a**b 退化为单 *（不跨 /）—— 与 ClaudemdGlob 的 .* 跨段差异").isFalse();

        ClaudemdEngine extglob = engineWithExcludes(List.of("@(a|b).md", "{1..3}.md"));
        assertThat(extglob.isClaudeMdExcluded("a.md", ClaudemdMemoryType.USER))
            .as("extglob @(a|b) 命中 a.md").isTrue();
        assertThat(extglob.isClaudeMdExcluded("c.md", ClaudemdMemoryType.USER))
            .as("extglob @(a|b) 不命中 c.md").isFalse();
        assertThat(extglob.isClaudeMdExcluded("2.md", ClaudemdMemoryType.USER))
            .as("brace {1..3} 命中 2.md").isTrue();
        assertThat(extglob.isClaudeMdExcluded("4.md", ClaudemdMemoryType.USER))
            .as("brace {1..3} 不命中 4.md").isFalse();

        ClaudemdEngine dot = engineWithExcludes(List.of("*.md"));
        assertThat(dot.isClaudeMdExcluded(".md", ClaudemdMemoryType.USER))
            .as("dot:true —— *.md 命中 .md（旧 ClaudemdGlob 无 dot 概念但行为相近）").isTrue();
    }

    @Test
    @DisplayName("resolveExcludePatterns 非绝对透传 + 目录不存在静默跳过（claudemd.ts:581-612）")
    void resolveExcludePatterns_passthroughAndSkip() {
        // WHY: CC resolveExcludePatterns 仅对 / 开头绝对 pattern 做 realpath 静态前缀展开；
        //      非绝对（**/*.md / 盘符路径）原样透传，目录不存在 catch 静默跳过（:588-608）。
        assertThat(ClaudemdEngine.resolveExcludePatterns(
            List.of("**/code/CLAUDE.md", "*.md", "C:/abs/CLAUDE.md")))
            .as("非 / 前缀 pattern 透传").containsExactly("**/code/CLAUDE.md", "*.md", "C:/abs/CLAUDE.md");
        assertThat(ClaudemdEngine.resolveExcludePatterns(List.of("/no/such/dir/CLAUDE.md")))
            .as("目录不存在 → 静默跳过，仅保留原 pattern").containsExactly("/no/such/dir/CLAUDE.md");
    }

    @Test
    @DisplayName("resolveExcludePatterns 绝对 / 前缀静态前缀 realpath 展开（claudemd.ts:593-605）")
    void resolveExcludePatterns_absolutePrefixRealpathExpansion() throws Exception {
        // WHY: CC 对 / 开头 pattern 的 glob 元字符前静态前缀目录 realpathSync 展开（symlink
        //      两侧匹配）。此处用 cwd 所在盘符的相对 / 路径构造（/code/.../cm5-impl），
        //      realpath 后得到盘符绝对形态（D:/...）→ 双 pattern；Windows 下即验证
        //      realpath 展开分支。
        Path cwd = Paths.get(".").toAbsolutePath().toRealPath();
        String rel = cwd.toString().replace('\\', '/');       // D:/code/.../cm5-impl
        int colon = rel.indexOf(':');
        String driveRel = colon >= 0 ? rel.substring(colon + 1) : rel; // /code/.../cm5-impl
        String posixPattern = driveRel + "/CLAUDE.md";
        List<String> expanded = ClaudemdEngine.resolveExcludePatterns(List.of(posixPattern));
        assertThat(expanded).as("原 / 前缀 pattern 保留").contains(posixPattern);
        if (colon >= 0) {
            assertThat(expanded).as("realpath 盘符绝对侧追加（/tmp→/private/tmp 两侧匹配等价）")
                .contains(rel + "/CLAUDE.md");
        }
    }

    @Test
    @DisplayName("isClaudeMdExcluded realpath 展开集成：/link/CLAUDE.md → realpath 侧命中（claudemd.ts:565）")
    void isClaudeMdExcluded_symlinkResolvedSideMatches() throws Exception {
        // WHY: resolveExcludePatterns 的完整语义 —— 用户写 /tmp/project/CLAUDE.md（symlink 侧），
        //      系统解析 CWD 为 /private/tmp/project/...，exclude 需两侧都能命中（claudemd.ts:560-572）。
        //      Java symlink 需要开发者模式/管理员权限，不可用时跳过。
        Path cwd = Paths.get(".").toAbsolutePath().toRealPath();
        String rel = cwd.toString().replace('\\', '/');
        int colon = rel.indexOf(':');
        String driveRel = colon >= 0 ? rel.substring(colon + 1) : rel;

        Path base = Files.createTempDirectory(cwd, "excl-base-");
        Path target = Files.createDirectories(base.resolve("target"));
        Path link = base.resolve("link");
        boolean symlinkOk;
        try {
            Files.createSymbolicLink(link, target);
            symlinkOk = true;
        } catch (UnsupportedOperationException | java.io.IOException e) {
            symlinkOk = false;
        }
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(symlinkOk,
                "Files.createSymbolicLink 不可用（需开发者模式/管理员权限），跳过 realpath 集成断言");
            String linkRel = cwd.relativize(link).toString().replace('\\', '/');
            String posixPattern = driveRel + "/" + linkRel + "/CLAUDE.md";
            ClaudemdEngine e = engineWithExcludes(List.of(posixPattern));
            // realpath 侧（target 真实路径）命中 —— 这是 CC 想实现的 symlink 两侧匹配
            String targetRealPath = target.toRealPath().toString().replace('\\', '/') + "/CLAUDE.md";
            assertThat(e.isClaudeMdExcluded(targetRealPath, ClaudemdMemoryType.PROJECT))
                .as("realpath 展开侧（target 真实路径）命中").isTrue();
            // 字面侧：POSIX 下 link 路径命中；Windows 盘符路径与 / 前缀 pattern 不匹配（CC 同局限）
            String linkPath = link.toString().replace('\\', '/') + "/CLAUDE.md";
            if (colon < 0) {
                assertThat(e.isClaudeMdExcluded(linkPath, ClaudemdMemoryType.PROJECT))
                    .as("字面 link 路径命中（POSIX）").isTrue();
            }
        } finally {
            Files.walk(base).sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (java.io.IOException ignored) { } });
        }
    }

    @Test
    @DisplayName("生产 2 参构造器 → originalCwdSupplier 走 CwdResolution.getOriginalCwdLayer（对齐 CC claudemd.ts:851 getOriginalCwd）")
    void productionConstructor_originalCwdSupplier_walksCwdResolutionBoundProject() throws Exception {
        // 复用 setUp 已建的 autoMemPaths/detection（其 base=workspace，但 originalCwdSupplier
        // 由生产构造器自决 → 不再硬编码 workspace）
        String sessionId = "wf-1b-claudemd-" + java.util.UUID.randomUUID();
        com.nexusai.common.RequestContext.setSession(sessionId);
        com.nexusai.common.SessionProjectRoot.setForSession(sessionId, workspace.toString());
        try {
            ClaudemdEngine prodEngine = new ClaudemdEngine(autoMemPaths, detection);

            // getMemoryPath(PROJECT/LOCAL) 用 originalCwdSupplier.get() 作为根
            assertThat(prodEngine.getMemoryPath(ClaudemdMemoryType.PROJECT))
                .as("Project CLAUDE.md 扫描根=绑定项目（CwdResolution.getOriginalCwdLayer）")
                .isEqualTo(Paths.get(workspace.toRealPath().toString(), "CLAUDE.md").toString());
            assertThat(prodEngine.getMemoryPath(ClaudemdMemoryType.LOCAL))
                .as("Local CLAUDE.local.md 扫描根=绑定项目")
                .isEqualTo(Paths.get(workspace.toRealPath().toString(), "CLAUDE.local.md").toString());
        } finally {
            com.nexusai.common.SessionProjectRoot.clearSession(sessionId);
            com.nexusai.common.RequestContext.clear();
        }
    }
}
