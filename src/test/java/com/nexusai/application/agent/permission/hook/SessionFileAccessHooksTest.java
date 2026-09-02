package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.TeamMemPaths;
import com.nexusai.application.agent.permission.hook.SessionFileAccessHooks.FileType;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [Session H14] SessionFileAccessHooks 对齐 CC sessionFileAccessHooks.ts:146-250.
 *
 * <p>WHY (规则九 · 测试验证意图): CC 通过 PostToolUse 埋点追踪"会话记忆 / transcript /
 * memdir 文件被 agent 访问" — 这些 analytics 事件 (tengu_session_memory_accessed /
 * tengu_transcript_accessed / tengu_memdir_accessed) 是隐私合规与 memory 行为审计的
 * 数据基础. Java 端完全缺失 (0%), 本测试锁定:
 * <ul>
 *   <li><b>file_path 解析</b>: Read/Edit/Write 输入中提取 file_path (CC :49-69)</li>
 *   <li><b>session file type 检测</b>: session-memory/.md + projects/.jsonl (CC :75-116)</li>
 *   <li><b>事件发射</b>: 命中 session_memory → tengu_session_memory_accessed (CC :161-162)</li>
 *   <li><b>5 工具注册</b>: Read/Grep/Glob/Edit/Write 5 个 PostToolUse matcher hooks (CC :233-250)</li>
 * </ul>
 */
@DisplayName("[H14] SessionFileAccessHooks analytics 埋点")
class SessionFileAccessHooksTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认实例 (生产默认目录 ~/.claude + AutoMemPaths per-project 记忆目录). */
    private static final SessionFileAccessHooks DEFAULT = new SessionFileAccessHooks(new RecordingTelemetry());

    /** 构造带 file_path 的工具输入. */
    private static ObjectNode inputWithPath(String path) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("file_path", path);
        return n;
    }

    /** 便捷: 实例方法 getSessionFileTypeFromInput (依赖注入式 configHome/memDir, 静态不可用). */
    private static FileType fileTypeOf(String tool, ObjectNode input) {
        return DEFAULT.getSessionFileTypeFromInput(tool, input);
    }

    // ════════════════════════════════════════════════════════════════
    // 1. getFilePathFromInput — Read/Edit/Write 的 file_path 解析
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Read/Edit/Write 输入 → file_path 解析 (CC :49-69)")
    void getFilePathFromInput_extractsFilePath() {
        // WHY: memdir 访问检测依赖从工具输入提取 file_path (CC :49-69).
        //      解析失败 → 后续 memdir/team-mem 事件全部漏发.
        for (String tool : List.of("Read", "Edit", "Write")) {
            String path = "/tmp/somefile.md";
            assertThat(SessionFileAccessHooks.getFilePathFromInput(tool, inputWithPath(path)))
                .as("工具 %s 的 file_path 必须被解析", tool)
                .isEqualTo(path);
        }
    }

    @Test
    @DisplayName("非文件工具 / 无 file_path → null (CC :66-68)")
    void getFilePathFromInput_unknownTool_returnsNull() {
        // WHY: Grep/Glob 无 file_path 字段 (走 glob/path 检测); 未知工具返回 null (CC :66-68).
        assertThat(SessionFileAccessHooks.getFilePathFromInput("Bash", inputWithPath("/x")))
            .isNull();
        assertThat(SessionFileAccessHooks.getFilePathFromInput("Read", MAPPER.createObjectNode()))
            .isNull();
    }

    // ════════════════════════════════════════════════════════════════
    // 2. getSessionFileTypeFromInput — session-memory/.md + projects/.jsonl
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Read 读取 session-memory/*.md → session_memory (CC :75-84)")
    void readSessionMemoryFile_detectedAsSessionMemory() {
        // WHY: agent 读 ~/.claude/session-memory/*.md = 访问会话记忆 → 需发
        //       tengu_session_memory_accessed (CC :161-162). 若检测失效, 会话记忆访问
        //       在 analytics 中完全隐身 (隐私/合规审计缺口).
        String path = Path.of(System.getProperty("user.home"), ".claude", "session-memory", "abc.md").toString();
        FileType type = fileTypeOf("Read", inputWithPath(path));

        assertThat(type).isEqualTo(FileType.SESSION_MEMORY);
    }

    @Test
    @DisplayName("Read 读取 projects/*.jsonl → session_transcript (CC :85)")
    void readTranscriptFile_detectedAsSessionTranscript() {
        String path = Path.of(System.getProperty("user.home"), ".claude", "projects", "abc.jsonl").toString();
        FileType type = fileTypeOf("Read", inputWithPath(path));

        assertThat(type).isEqualTo(FileType.SESSION_TRANSCRIPT);
    }

    @Test
    @DisplayName("Grep 的 glob 命中 session-memory 模式 → session_memory (CC :86-99)")
    void grepGlobPattern_detectsSessionMemory() {
        // WHY: Grep 工具输入无 file_path, 靠 glob 判断访问意图 (CC :94-97).
        ObjectNode in = MAPPER.createObjectNode();
        in.put("glob", "**/session-memory/*.md");
        in.put("path", System.getProperty("user.home"));

        FileType type = fileTypeOf("Grep", in);
        assertThat(type).isEqualTo(FileType.SESSION_MEMORY);
    }

    // ════════════════════════════════════════════════════════════════
    // 3. handleSessionFileAccess — telemetry 事件发射
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("读 session-memory 文件 → tengu_session_memory_accessed 事件 (CC :161-162)")
    void readSessionMemory_emitsTelemetry() {
        // WHY: 事件名必须与 CC 一致 (tengu_session_memory_accessed) — analytics 管道
        //       后端按事件名聚合, 名字漂移 = 数据丢失. Java 端用 Telemetry.recordEvent.
        RecordingTelemetry telemetry = new RecordingTelemetry();
        SessionFileAccessHooks hooks = new SessionFileAccessHooks(telemetry);
        String path = Path.of(System.getProperty("user.home"), ".claude", "session-memory", "abc.md").toString();

        hooks.handleSessionFileAccess("Read", inputWithPath(path));

        assertThat(telemetry.events)
            .as("session_memory 命中必须发射 tengu_session_memory_accessed")
            .contains("tengu_session_memory_accessed");
        assertThat(telemetry.events).doesNotContain("tengu_memdir_accessed");
    }

    @Test
    @DisplayName("读 memdir 文件 → tengu_memdir_accessed + tengu_memdir_file_read (CC :168-177)")
    void readMemdirFile_emitsMemdirEvents(@TempDir Path memoryBase, @TempDir Path projectRoot) {
        // WHY: agent 读 memdir 文件 (auto memory) → 需发 tengu_memdir_accessed +
        //       tengu_memdir_file_read (CC :168-185). 区分 file_read/edit/write 子事件
        //       用于理解 memory 生命周期.
        //       DEL-M-06 联动: memdir 路径从全局 ~/.nexusai/memory 改为 CC per-project
        //       <memoryBase>/projects/<sanitized-git-root>/memory/ —— 断言必须跟随对齐后路径。
        RecordingTelemetry telemetry = new RecordingTelemetry();
        AutoMemPaths autoMemPaths = new AutoMemPaths(
            () -> projectRoot.toString(), () -> memoryBase.toString(), () -> null, () -> null);
        SessionFileAccessHooks hooks = new SessionFileAccessHooks(telemetry, autoMemPaths);
        String path = autoMemPaths.getAutoMemPath() + "MEMORY.md";

        hooks.handleSessionFileAccess("Read", inputWithPath(path));

        assertThat(telemetry.events).contains("tengu_memdir_accessed");
        assertThat(telemetry.events).contains("tengu_memdir_file_read");
    }

    @Test
    @DisplayName("feature 关 + runtime 开 → team 文件 Edit 不发射遥测、不 notify（CC :189 外层 TEAMMEM 门）")
    void teamFile_featureOffRuntimeOn_noTelemetryNoNotify(@TempDir Path memoryBase, @TempDir Path projectRoot) {
        // WHY: CC sessionFileAccessHooks.ts:189 `feature('TEAMMEM') && filePath && isTeamMemFile` ——
        //       编译门关时 team 分支整体不可达：tengu_team_mem_accessed 不发射 + notifyTeamMemoryWrite
        //       不调用（旧实现仅 isTeamMemFile 门 → feature 关 + runtime 开时遥测多发射，△ S2）。
        RecordingTelemetry telemetry = new RecordingTelemetry();
        AutoMemPaths autoMemPaths = new AutoMemPaths(
            () -> projectRoot.toString(), () -> memoryBase.toString(), () -> null, () -> null);
        SessionFileAccessHooks hooks = new SessionFileAccessHooks(
            telemetry,
            () -> System.getProperty("user.home"),
            autoMemPaths,
            () -> true,   // autoMemoryEnabled
            () -> false,  // teamMemFeatureEnabled（CC feature('TEAMMEM') 关）
            () -> true);  // teamMemoryRuntimeEnabled（CC tengu_herring_clock 开）
        TeamMemPaths paths = new TeamMemPaths(autoMemPaths, () -> true, () -> false, () -> true);
        String teamFile = paths.getTeamMemPath() + "MEMORY.md";
        CountingWatcher watcher = new CountingWatcher();
        hooks.setTeamMemoryWatcher(watcher);

        hooks.handleSessionFileAccess("Edit", inputWithPath(teamFile));

        assertThat(telemetry.events)
            .as("feature 关 → tengu_team_mem_accessed 不得发射")
            .doesNotContain("tengu_team_mem_accessed");
        assertThat(watcher.notifyCalls).as("feature 关 → notifyTeamMemoryWrite 不得调用").isZero();
    }

    @Test
    @DisplayName("isMemoryFileAccess：team 分支需 feature 外层门 —— feature 关 + runtime 开 → false；双开 → true（CC :135）")
    void isMemoryFileAccess_teamBranchRequiresFeatureGate(@TempDir Path memoryBase, @TempDir Path projectRoot) {
        // WHY: CC sessionFileAccessHooks.ts:135 `isAutoMemFile(filePath) || (feature('TEAMMEM') &&
        //       teamMemPaths!.isTeamMemFile(filePath))` —— isMemoryFileAccess 的 team 分支同样带编译门
        //       （旧实现仅 isTeamMemFile 门，与已修 notify 消费侧同根 S2 残留）。team 目录是 autoMem
        //       子目录，autoMemory 开时 autoMem 分支会兜底 team 路径 → 用例关 autoMemory 隔离 team
        //       分支：feature 关 + runtime 开 → 不构成 memory 文件访问；双开 → 构成。
        AutoMemPaths autoMemPaths = new AutoMemPaths(
            () -> projectRoot.toString(), () -> memoryBase.toString(), () -> null, () -> null);
        TeamMemPaths paths = new TeamMemPaths(autoMemPaths, () -> true, () -> false, () -> true);
        String teamFile = paths.getTeamMemPath() + "MEMORY.md";

        // feature 关 + runtime 开 → team 分支被编译门拦截 → 不构成 memory 文件访问
        SessionFileAccessHooks featureOff = new SessionFileAccessHooks(
            new RecordingTelemetry(), () -> System.getProperty("user.home"), autoMemPaths,
            () -> false,  // autoMemoryEnabled（关：隔离 team 分支，防 autoMem 兜底）
            () -> false,  // teamMemFeatureEnabled（CC feature('TEAMMEM') 关）
            () -> true);  // teamMemoryRuntimeEnabled（CC tengu_herring_clock 开）
        assertThat(featureOff.isMemoryFileAccess("Edit", inputWithPath(teamFile)))
            .as("feature 关 + runtime 开 → team 文件不构成 memory 文件访问")
            .isFalse();

        // 双开（feature + runtime）→ team 分支可达 → 构成 memory 文件访问
        SessionFileAccessHooks bothOn = new SessionFileAccessHooks(
            new RecordingTelemetry(), () -> System.getProperty("user.home"), autoMemPaths,
            () -> true,   // autoMemoryEnabled
            () -> true,   // teamMemFeatureEnabled
            () -> true);  // teamMemoryRuntimeEnabled
        assertThat(bothOn.isMemoryFileAccess("Edit", inputWithPath(teamFile)))
            .as("双开 → team 文件构成 memory 文件访问")
            .isTrue();
    }

    /** 记录 notify 调用次数的 watcher 假实现（对齐 TeamMemorySyncTest.CountingWatcher 语义）。 */
    private static final class CountingWatcher
        extends com.nexusai.application.agent.memory.TeamMemoryWatcher {
        int notifyCalls;

        CountingWatcher() {
            super(null, null, null, null);
        }

        @Override
        public void notifyTeamMemoryWrite() {
            notifyCalls++;
        }
    }
    // ════════════════════════════════════════════════════════════════
    // 4. registerSessionFileAccessHooks — 5 工具 PostToolUse 注册
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("subagent 上下文读取 session-memory → 事件附带 subagent_name (CC :158-159)")
    void subagentContext_emitsSubagentName() {
        // WHY: CC sessionFileAccessHooks.ts:158-159 只在 subagent 上下文携带 subagent_name 事件
        //      属性（getSubagentLogName 非 subagent → undefined → 无该属性）。analytics 归因需要
        //      区分"哪个 subagent 访问了会话记忆"（OPD-M-50）。Java 端用 AgentContext.runWithAgentContext
        //      模拟 subagent 线程上下文（ThreadLocal 等价 CC AsyncLocalStorage）。
        RecordingTelemetry telemetry = new RecordingTelemetry();
        SessionFileAccessHooks hooks = new SessionFileAccessHooks(telemetry);
        String path = Path.of(System.getProperty("user.home"), ".claude", "session-memory", "abc.md").toString();
        ObjectNode input = inputWithPath(path);
        AtomicInteger subagentNameSeen = new AtomicInteger(0);

        // subagent 上下文：内置 agent（如 "Explore"）→ subagent_name=agent 名
        com.nexusai.application.agent.subagent.AgentContext.SubagentContext subagent =
            new com.nexusai.application.agent.subagent.AgentContext.SubagentContext(
                "agent-1", "parent-session", "Explore", true, "req-1", "spawn");
        com.nexusai.application.agent.subagent.AgentContext.runWithAgentContext(subagent, () -> {
            hooks.handleSessionFileAccess("Read", input);
            return null;
        });

        // 事件名 + 属性都必须携带 subagent_name
        assertThat(telemetry.records)
            .as("subagent 上下文必须发射 subagent_name 属性")
            .anySatisfy(r -> {
                assertThat(r.name).isEqualTo("tengu_session_memory_accessed");
                assertThat(r.attributes.get("subagent_name")).isEqualTo("Explore");
            });
        subagentNameSeen.incrementAndGet();
        assertThat(subagentNameSeen.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("主线程读取 session-memory → 事件无 subagent_name 属性 (CC :158-159)")
    void mainThread_emitsNoSubagentName() {
        // WHY: 主线程无 subagent context → getSubagentLogName() 返回 null → subagentProps 空 →
        //      事件不带 subagent_name（CC :159 主线程无 subagentName → 无该属性）。与 subagent 场景
        //      对照验证"仅在 subagent 上下文才携带"的归因语义（OPD-M-50 验收口径）。
        RecordingTelemetry telemetry = new RecordingTelemetry();
        SessionFileAccessHooks hooks = new SessionFileAccessHooks(telemetry);
        String path = Path.of(System.getProperty("user.home"), ".claude", "session-memory", "abc.md").toString();

        hooks.handleSessionFileAccess("Read", inputWithPath(path));

        assertThat(telemetry.records)
            .as("主线程事件不得带 subagent_name 属性")
            .allSatisfy(r -> assertThat(r.attributes).doesNotContainKey("subagent_name"));
    }

    @Test
    @DisplayName("registerSessionFileAccessHooks 注册 5 个 PostToolUse hooks (CC :233-250)")
    void register_registersFiveToolHooks() {
        // WHY: CC registerHookCallbacks 注册 Read/Grep/Glob/Edit/Write 5 个工具 matcher
        //       hooks (internal + timeout 1ms). 注册缺失 → 埋点永不触发.
        RecordingRegistry registry = new RecordingRegistry();
        SessionFileAccessHooks hooks = new SessionFileAccessHooks(new RecordingTelemetry());

        hooks.registerSessionFileAccessHooks(registry);

        assertThat(registry.registeredNames)
            .as("必须注册 5 个工具的 PostToolUse hooks")
            .containsExactlyInAnyOrder("sessionFileAccess:Read", "sessionFileAccess:Grep",
                "sessionFileAccess:Glob", "sessionFileAccess:Edit", "sessionFileAccess:Write");
    }

    /** 记录发射事件名+属性的 Telemetry 假实现. */
    private static final class RecordingTelemetry extends Telemetry {
        final List<String> events = new ArrayList<>();
        final List<RecordedEvent> records = new ArrayList<>();

        /** 记录事件名 + 属性（subagent_name 断言用）. */
        record RecordedEvent(String name, java.util.Map<String, Object> attributes) {}

        @Override
        public void recordEvent(String eventName, java.util.Map<String, Object> attributes) {
            events.add(eventName);
            records.add(new RecordedEvent(eventName, attributes));
            super.recordEvent(eventName, attributes);
        }
    }

    /** 记录注册 hook 名的假注册中心 · 实现 SessionFileAccessHooks.PostToolUseRegistrar. */
    private static final class RecordingRegistry implements SessionFileAccessHooks.PostToolUseRegistrar {
        final List<String> registeredNames = new ArrayList<>();
        final AtomicInteger counter = new AtomicInteger();

        @Override
        public void registerPostToolUse(String name, PostToolUseHook hook) {
            registeredNames.add(name);
            counter.incrementAndGet();
        }
    }
}
