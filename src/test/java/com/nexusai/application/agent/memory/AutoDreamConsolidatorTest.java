package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.compact.fork.CacheSafeParams;
import com.nexusai.application.agent.compact.fork.ForkRawMaterial;
import com.nexusai.application.agent.compact.fork.ForkedAgentParams;
import com.nexusai.application.agent.compact.fork.ForkedAgentResult;
import com.nexusai.application.agent.compact.fork.RunForkedAgent;
import com.nexusai.application.agent.config.MemoryRemoteModeConfig;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.SessionStorage;
import com.nexusai.application.agent.tool.SystemMessage;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.memory.AutoDreamConsolidator.AutoDreamConfig;
import com.nexusai.application.agent.tasks.DreamTaskRegistry;
import com.nexusai.application.agent.tasks.DreamTaskState;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.SystemMessage;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import com.nexusai.repository.settings.entity.SettingsRecord;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IMP-M-P0-3 + IMP-M-P2-1 · AutoDreamConsolidator 测试。
 *
 * <p><b>WHY (规则 9 · 测试验证意图)</b>：
 * <ol>
 *   <li>P0-3：CC 恒 runForkedAgent 直接写文件，consolidateIfNeeded 唯一出路是 fork
 *       （mechanical/JSON 协议已删）；isAutoDreamEnabled 门控零成本跳过。</li>
 *   <li>P2-1：锁并发（consolidationLock.ts:46-84）—— stale(1h) 内 PID 存活 → 阻塞；
 *       死 PID/超 stale → 回收；写 PID + re-read 校验；rollback（:91-108）精确回退 mtime / unlink；
 *       abort 中止 → 回滚锁（Java 无 kill 路径，abort 为唯一回滚点 · DreamTask.ts:136-155）；
 *       遥测 tengu_auto_dream_fired/completed/failed 双发射（recordEvent + logOTelEvent ·
 *       HookRegistry:278-279 惯例，autoDream.ts:195/252/267）。</li>
 *   <li>P2-1 会话门数据源 = 扁平 transcript：{configHome}/projects/{sanitize(ws)}/{sess-xxx 或 uuid}.jsonl
 *       （listCandidates:169-198 扁平扫描；SessionStorage.getProjectDir(ws) 布局，2026-09-04 修正
 *       原直扫 workspaceDir 的 bug）—— 测试须把 jsonl 写到该 config-home 派生目录而非 ws 项目根。</li>
 *   <li>D5-A/M-11：consolidateIfNeeded/scanSessionTranscripts 收 (workspaceDir, sessionId) 显式
 *       参数（替代共享 volatile 读写）—— 两会话目录隔离断言（无跨会话交错窗口）。</li>
 * </ol>
 */
@DisplayName("[IMP-M-P0-3/P2-1/D5-A] AutoDreamConsolidator 接线 + 锁并发/rollback/遥测 + 扁平 transcript + 参数化隔离")
class AutoDreamConsolidatorTest {

    @TempDir
    Path tempDir;

    Path ws;
    Path mem;
    MemoryStorage storage;
    AutoDreamConsolidator consolidator;
    RecordingQuery query;
    Telemetry telemetry;

    @BeforeEach
    void setUp() throws IOException {
        // V56：env NEXUSAI_AUTO_DREAM 降为可选覆盖 → 默认开用例需 env 未设；清理残留 property
        //   （旧用例 finally 已清，此处防御性兜底保证「默认 true」断言不被上一用例污染）。
        System.clearProperty("NEXUSAI_AUTO_DREAM");
        // V56：DB 静态桥接兜底清除——「无 DB 默认 true」用例依赖桥接为 null（前一用例泄漏 mapper
        //   会令默认开误判；防御性复位保证用例隔离）。
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
        ws = tempDir.resolve("ws");
        mem = tempDir.resolve("mem");
        // G-16：MemoryStorage 构造器不再创建目录（CC ensureMemoryDirExists 仅 prompt 构建分支，
        // memdir.ts:459/:479）→ 测试种子文件（:176 mem0.md）依赖的目录显式创建。
        Files.createDirectories(mem);
        storage = new MemoryStorage(mem);
        consolidator = new AutoDreamConsolidator(storage);
        // D5-A/M-11: workspaceDir 不再经 volatile 注入 —— 每轮 consolidateIfNeeded(ws, sessionId, ...)
        //   显式传参（两会话目录隔离见 scanSessionTranscripts_parameterizedIsolationBetweenWorkspaces）
        query = new RecordingQuery();
        consolidator.setForkedQuery(query);
        // spy：recordEvent 计数委托真实对象 + logOTelEvent 双发射可 verify（P2-1）
        telemetry = spy(new Telemetry());
        consolidator.setTelemetry(telemetry);
        // [transcript-reloc 2026-09-04] 生产 transcript 不在 workspaceDir(项目根)平铺，而在
        //   {configHome}/projects/{sanitizePath(ws)}（SessionStorage.getProjectDir(ws) =
        //   NexusaiPaths.getAppConfigHomePath().resolve("projects").resolve(sanitize)）——
        //   scanSessionTranscripts 内部经 SessionStorage.getProjectDir 派生同一目录。config-home
        //   override 隔离到 @TempDir（防写/读真实 ~/.nexusai；getConfigHomeDirOverride 优先于 appName）。
        NexusaiPaths.setConfigHomeDirOverride(Files.createDirectories(tempDir.resolve("cfg-home")).toString());
    }

    @AfterEach
    void tearDown() {
        // 复位 config home override（防泄漏到下一用例 —— @TempDir 每用例唯一，不串扰）
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    @Test
    @DisplayName("门控全过 → 走 fork（skipTranscript + querySource=auto_dream + 受限 canUseTool）+ 遥测 fired/completed")
    void consolidateIfNeeded_enabledRunsFork_withTelemetry() throws IOException {
        writeSessions(ws, 5);
        consolidator.setAutoDreamEnabled(() -> true);

        // fork 已发起（D-18 void 化：CC executeAutoDream 返回 Promise<void>，无结果值，
        //   observable = fork 已调用 + 遥测 fired/completed）
        // DC-9（IMP-MV2-30）：观察点移入测试包 —— mockStatic 捕获 RunForkedAgent.run 的
        //   ForkedAgentParams 实参（生产 lastForkParamsRef 已删）
        ForkedAgentParams p = captureForkParams(() -> consolidator.consolidateIfNeeded(ws, null, null));
        assertThat(p).isNotNull();
        // fork 参数契约（autoDream.ts:224-233）
        assertThat(p.skipTranscript()).isTrue();
        assertThat(p.querySource()).isEqualTo(QuerySource.AUTO_DREAM);
        assertThat(p.forkLabel()).isEqualTo("auto_dream");
        assertThat(p.canUseTool()).isNotNull();   // createAutoMemCanUseTool（受限写）
        assertThat(p.abortController()).isNotNull(); // overrides.abortController（autoDream.ts:231）
        assertThat(p.onMessage()).isNotNull();       // makeDreamProgressWatcher（autoDream.ts:232）
        // prompt = buildConsolidationPrompt（4 阶段 dream）
        ChatMessageDto promptMsg = p.promptMessages().get(p.promptMessages().size() - 1);
        assertThat(promptMsg.content()).contains("# Dream: Memory Consolidation");
        assertThat(promptMsg.content()).contains("Phase 4 — Prune and index");
        // 锁文件保留（成功不删锁 · consolidationLock.ts:42-44，mtime 即 lastConsolidatedAt）
        assertThat(mem.resolve(ConsolidationLock.LOCK_FILE)).exists();
        // 遥测 fired + completed（autoDream.ts:195-198/252-257）
        assertThat(telemetry.getCounter("tengu_auto_dream_fired")).isEqualTo(1);
        assertThat(telemetry.getCounter("tengu_auto_dream_completed")).isEqualTo(1);
        // logOTelEvent 双发射（HookRegistry:278-279 惯例 · 事件真实出 OTel）
        verify(telemetry).logOTelEvent(eq("tengu_auto_dream_fired"), any());
        verify(telemetry).logOTelEvent(eq("tengu_auto_dream_completed"), any());

    }
    @Test
    @DisplayName("[IMP-MV2-09 T9] fork 原料注入：dream fork 载荷三段原料与主线程同值 + forkContextMessages 带回消息快照（CC createCacheSafeParams · autoDream.ts:226）")
    void consolidateIfNeeded_forkRawMaterial_payloadMatchesMainThread() throws IOException {
        // WHY: △-1（域级唯一 HIGH）—— ToolRegistrationConfig.buildProductionCacheSafeParams 空载荷
        // （三段恒空 + forkContextMessages 空）→ dream fork 无主系统提示 + prompt-cache key 与主线程
        // 不一致（cache 共享失效）。T9 修复：LlmAgentLoop:5154 捕获 ForkRawMaterial（forkedAgent.ts:131-141
        // createCacheSafeParams）透传，doConsolidate 合并注入（supplied 空 → 原料）。
        writeSessions(ws, 5);
        consolidator.setAutoDreamEnabled(() -> true);
        // 生产 supplier 形态：三段恒空，仅 toolUseContext 载荷（buildProductionCacheSafeParams）
        ToolUseContext supplierCtx = new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), "", new AbortController(), List.of());
        consolidator.setCacheSafeParamsSupplier(() -> new CacheSafeParams(
            List.of(), Map.of(), Map.of(), supplierCtx, List.of(), false));
        List<ChatMessageDto> mainMsgs = List.of(
            new ChatMessageDto("u0", null, Role.user, "user", "turn1", null,
                List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of()),
            new ChatMessageDto("a0", null, Role.assistant, "assistant", "reply", null,
                List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
                null, null, null, List.of(), List.of()));
        ForkRawMaterial raw = new ForkRawMaterial(
            List.of("MAIN-SYSTEM-PROMPT-1", "MAIN-SYSTEM-PROMPT-2"),
            Map.of("claudeMd", "项目指令"),
            Map.of("gitStatus", "GIT-BLOCK"),
            mainMsgs);

        consolidator.consolidateIfNeeded(ws, null, null, raw);

        assertThat(query.called()).isTrue();
        RunForkedAgent.ForkQueryParams q = query.last;
        assertThat(q).isNotNull();
        // fork 载荷三段原料与主线程同值（forkedAgent.ts:131-141 createCacheSafeParams）
        assertThat(q.systemPrompt()).containsExactly("MAIN-SYSTEM-PROMPT-1", "MAIN-SYSTEM-PROMPT-2");
        assertThat(q.userContext()).containsEntry("claudeMd", "项目指令");
        assertThat(q.systemContext()).containsEntry("gitStatus", "GIT-BLOCK");
        // forkContextMessages = 主线程消息快照（CC context.messages · 修复旧 List.of() 空前缀）
        assertThat(q.messages())
            .as("initialMessages = [...forkContextMessages, ...promptMessages]（forkedAgent.ts:524）")
            .extracting(ChatMessageDto::id)
            .contains("u0", "a0");
        // 生产 supplier toolUseContext 保留（唯一有效载荷）
        assertThat(q.toolUseContext()).isNotNull();
    }

    @Test
    @DisplayName("isAutoDreamEnabled=false（显式关闭）→ 零成本跳过 fork（config.ts:13 gate）")
    void consolidateIfNeeded_disabledSkipsFork() {
        consolidator.setAutoDreamEnabled(() -> false);

        consolidator.consolidateIfNeeded(ws, null, null);

        assertThat(query.called()).isFalse();
    }

    @Test
    @DisplayName("[IMP-MV2-14 D2-R6] remote mode → 跳过 fork（CC autoDream.ts:97 getIsRemoteMode 门；与 ExtractMemoriesAgent:432-433 对称）")
    void consolidateIfNeeded_remoteModeTrueSkipsFork() throws IOException {
        // WHY: D2-R6 △-D2 —— isGateOpen 四重（autoDream.ts:95-100）Java 旧实现二重（remote 门缺失）；
        //   ExtractMemoriesAgent 有 remoteMode 门（ExtractMemoriesAgent.java:432-433），
        //   AutoDreamConsolidator 无 → 部署 remote-mode=true + NEXUSAI_AUTO_DREAM=true 并存时
        //   extract 跳过而 dream 仍触发（不对称）。此处经 setRemoteMode seam 确定性开启，
        //   断言其他门全开时 fork 仍不启动（CC autoDream.ts:97 在 autoMemory/autoDream 前短路）。
        writeSessions(ws, 5);
        consolidator.setAutoMemoryEnabled(() -> true);
        consolidator.setAutoDreamEnabled(() -> true);
        consolidator.setRemoteMode(() -> true);    // remote mode 开启

        consolidator.consolidateIfNeeded(ws, null, null);

        assertThat(query.called()).isFalse();
        assertThat(telemetry.getCounter("tengu_auto_dream_fired")).isZero();
    }

    @Test
    @DisplayName("[IMP-MV2-14 D2-R6] remote 门在 autoMemory 之前求值（CC isGateOpen 门序 autoDream.ts:95-100 最便宜先）")
    void consolidateIfNeeded_remoteModeGateEvaluatedBeforeAutoMemory() {
        // WHY: CC 门序 = KAIROS → remote → autoMemory → autoDream（autoDream.ts:95-100）；
        //   remote=true 时后续门不应再求值（短路）——断言 autoMemory supplier 未被咨询，
        //   固化门序不回归（若 remote 门放在 autoMemory 之后则语义分叉：autoMemory 先短路，
        //   与 CC 四重门序不一致）。
        AtomicBoolean autoMemoryConsulted = new AtomicBoolean(false);
        consolidator.setAutoMemoryEnabled(() -> {
            autoMemoryConsulted.set(true);
            return true;
        });
        consolidator.setAutoDreamEnabled(() -> true);
        consolidator.setRemoteMode(() -> true);

        consolidator.consolidateIfNeeded(ws, null, null);

        assertThat(query.called()).isFalse();
        assertThat(autoMemoryConsulted).isFalse();
    }

    @Test
    @DisplayName("[IMP-MV2-14 D2-R6] 生产配置面：MemoryRemoteModeConfig remote-mode=true + NEXUSAI_AUTO_DREAM=true → dream 不触发")
    void consolidateIfNeeded_remoteModeConfigTrueBlocksEvenWithEnvAutoDream() throws IOException {
        // WHY: D2-R6 验证命令场景（remote-mode=true + NEXUSAI_AUTO_DREAM=true 并存时 dream 不触发）
        //   —— 走生产默认链（无 seam 注入）：MemoryRemoteModeConfig 构造器桥接
        //   nexusai.memory.remote-mode=true（与 Spring @Value 注入等价），env 经 system property
        //   NEXUSAI_AUTO_DREAM=true 开启 autoDream；settings 隔离到空临时目录防本机真实
        //   settings.json 干扰（同 consolidateIfNeeded_defaultDisabledSkipsFork 惯例）。
        writeSessions(ws, 5);
        Path configHome = Files.createDirectories(tempDir.resolve("cfg-home-remote"));
        AutoMemPaths.setCurrentProjectRoot(tempDir.resolve("proj-remote").toString());
        ClaudePaths.setConfigDirOverride(configHome.toString());
        // G5：autoMemory 门读 nexusai 自有根 settings.json（BundledSkillEnabledGates.java:189）→
        //   唯一 appName 隔离（防读/写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        System.setProperty("NEXUSAI_AUTO_DREAM", "true");
        new MemoryRemoteModeConfig(true);   // Spring 构造器 → 静态桥接（@Value 注入等价）
        try {
            consolidator.consolidateIfNeeded(ws, null, null);
            assertThat(query.called()).isFalse();
        } finally {
            System.clearProperty("NEXUSAI_AUTO_DREAM");
            MemoryRemoteModeConfig.reset();
            AutoMemPaths.setCurrentProjectRoot(null);
            ClaudePaths.setConfigDirOverride(null);
            NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
        }
    }

    @Test
    @DisplayName("[V56 用户拍板 2026-08-30] 无 DB 无 env → 默认开 → fork（覆盖 OPD-CM3-24 Q1 默认关对齐 CC）")
    void consolidateIfNeeded_defaultEnabledRunsFork() throws IOException {
        // WHY: 用户决策「autoDream 统一走 DB 表列 + 默认开 + 弃文件」——覆盖旧 OPD-CM3-24 Q1
        //   「默认关对齐 CC 需显式开启」。isAutoDreamEnabledBySettingsOrEnv 链：DB auto_dream_enabled
        //   （静态桥接未注入 → null）→ env NEXUSAI_AUTO_DREAM（未设 → 跳过）→ 默认 true。
        //   缺省门控（不注入）必须放行 fork。
        //   IMP-MV2-13 D2-R5 惯例：隔离真实 ~/.claude/settings.json（防本机干扰）——autoDream 文件
        //   承载已弃用，此隔离为 autoMemory 门（BundledSkillEnabledGates.isAutoMemoryEnabled 文件
        //   回落）保守保留。
        writeSessions(ws, 5);
        Path configHome = Files.createDirectories(tempDir.resolve("cfg-home"));
        AutoMemPaths.setCurrentProjectRoot(tempDir.resolve("proj").toString());
        ClaudePaths.setConfigDirOverride(configHome.toString());
        // G5：autoMemory 门读 nexusai 自有根 settings.json（BundledSkillEnabledGates.java:189）→
        //   唯一 appName 隔离（防读/写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        try {
            consolidator.consolidateIfNeeded(ws, null, null);
            assertThat(query.called()).isTrue();
        } finally {
            AutoMemPaths.setCurrentProjectRoot(null);
            ClaudePaths.setConfigDirOverride(null);
            NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
        }
    }

    @Test
    @DisplayName("[V56 用户拍板 2026-08-30] env NEXUSAI_AUTO_DREAM 降为可选强制覆盖：truthy→true / falsy→false / 未设→默认 true")
    void autoDreamEnv_optionalOverride() {
        // WHY: 用户决策「env NEXUSAI_AUTO_DREAM 降为可选强制覆盖（或保留为 null 不阻断）」——
        //   DB 未配置时 env 显式 truthy → true；显式 falsy → false；未设/blank → 默认 true
        //   （旧 OPD-CM3-24 Q1「未设即关闭」语义已弃）。
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
        System.setProperty("NEXUSAI_AUTO_DREAM", "true");
        try {
            assertThat(AutoDreamConsolidator.isAutoDreamEnabledBySettingsOrEnv()).isTrue();
        } finally {
            System.clearProperty("NEXUSAI_AUTO_DREAM");
        }
        System.setProperty("NEXUSAI_AUTO_DREAM", "false");
        try {
            assertThat(AutoDreamConsolidator.isAutoDreamEnabledBySettingsOrEnv()).isFalse();
        } finally {
            System.clearProperty("NEXUSAI_AUTO_DREAM");
        }
        // 未设 → 默认 true（默认开）
        assertThat(AutoDreamConsolidator.isAutoDreamEnabledBySettingsOrEnv()).isTrue();
    }

    @Test
    @DisplayName("[V56] resolveEnv 双通道：env NEXUSAI_AUTO_DREAM=false 显式 falsy → 可选覆盖关 gate（部署可关）")
    void consolidateIfNeeded_propertyFalseDisablesDefaultGate() throws IOException {
        // WHY: V56 链末段——DB 未配置时 env 显式 falsy 为可选强制覆盖 → 关闭。部署关闭开关仍生效
        //   （resolveEnv property 优先，ExtractMemoriesAgent.resolveEnv 测试惯例，
        //   ExtractMemoriesAgent.java:878-885）。
        writeSessions(ws, 5);
        Path configHome = Files.createDirectories(tempDir.resolve("cfg-home"));
        AutoMemPaths.setCurrentProjectRoot(tempDir.resolve("proj").toString());
        ClaudePaths.setConfigDirOverride(configHome.toString());
        // G5：autoMemory 门读 nexusai 自有根 settings.json（BundledSkillEnabledGates.java:189）→
        //   唯一 appName 隔离（防读/写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        System.setProperty("NEXUSAI_AUTO_DREAM", "false");
        try {
            consolidator.consolidateIfNeeded(ws, null, null);
            assertThat(query.called()).isFalse();
        } finally {
            AutoMemPaths.setCurrentProjectRoot(null);
            ClaudePaths.setConfigDirOverride(null);
            NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
            System.clearProperty("NEXUSAI_AUTO_DREAM");
        }
    }

    @Test
    @DisplayName("[V56 用户拍板 2026-08-30] DB auto_dream_enabled=true → 门控放行 fork（DB 主控）")
    void consolidateIfNeeded_dbTrueEnablesDefaultGate() throws IOException {
        // WHY: 用户决策「DB auto_dream_enabled 优先（有值用之）」——DB true 必须放行 fork
        //   （对齐 autoMemory V34 先例）。V56 前该场景经 settings.json 文件键
        //   （consolidateIfNeeded_settingsTrueEnablesDefaultGate），文件承载弃用后改走 DB 列
        //   （BundledSkillEnabledGates 静态桥接注入 mock mapper · config.ts:14-15 settings 覆盖
        //   GB 等价）。
        writeSessions(ws, 5);
        SettingsRecord rec = new SettingsRecord();
        rec.setId(1);
        rec.setAutoDreamEnabled(true);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(1)).thenReturn(rec);
        BundledSkillEnabledGates.bridgeSettingsMapper(mapper);
        Path configHome = Files.createDirectories(tempDir.resolve("cfg-home"));
        AutoMemPaths.setCurrentProjectRoot(tempDir.resolve("proj").toString());
        ClaudePaths.setConfigDirOverride(configHome.toString());
        // G5：autoMemory 门读 nexusai 自有根 settings.json（BundledSkillEnabledGates.java:189）→
        //   唯一 appName 隔离（防读/写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        try {
            consolidator.consolidateIfNeeded(ws, null, null);
            assertThat(query.called()).isTrue();
        } finally {
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
            AutoMemPaths.setCurrentProjectRoot(null);
            ClaudePaths.setConfigDirOverride(null);
            NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
        }
    }

    @Test
    @DisplayName("[V56 用户拍板 2026-08-30] DB auto_dream_enabled=false → 门控阻断 fork（DB 主控）")
    void consolidateIfNeeded_dbFalseDisablesDefaultGate() throws IOException {
        // WHY: DB false 显式关闭必须阻断 fork（前端「环境配置」可关，对齐 autoMemory V34 先例）。
        writeSessions(ws, 5);
        SettingsRecord rec = new SettingsRecord();
        rec.setId(1);
        rec.setAutoDreamEnabled(false);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(1)).thenReturn(rec);
        BundledSkillEnabledGates.bridgeSettingsMapper(mapper);
        Path configHome = Files.createDirectories(tempDir.resolve("cfg-home"));
        AutoMemPaths.setCurrentProjectRoot(tempDir.resolve("proj").toString());
        ClaudePaths.setConfigDirOverride(configHome.toString());
        // G5：autoMemory 门读 nexusai 自有根 settings.json（BundledSkillEnabledGates.java:189）→
        //   唯一 appName 隔离（防读/写真实 ~/.nexusai）
        NexusaiPaths.setAppNameOverride("nexusai-test-" + tempDir.getFileName());
        try {
            consolidator.consolidateIfNeeded(ws, null, null);
            assertThat(query.called()).isFalse();
        } finally {
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
            AutoMemPaths.setCurrentProjectRoot(null);
            ClaudePaths.setConfigDirOverride(null);
            NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
        }
    }

    @Test
    @DisplayName("[V56 用户拍板 2026-08-30] DB false 显式关闭压过 env=true（DB 优先 · config.ts:14-15 settings 覆盖 GB 等价）")
    void autoDreamDbSetting_overridesEnv() {
        // WHY: V56 链 DB 优先——DB 显式 false 必须压过 env=true（否则部署 env 开启标志反而覆盖
        //   用户显式关闭；对齐 CC isAutoDreamEnabled = settings 显式值优先于 GB）。
        SettingsRecord rec = new SettingsRecord();
        rec.setId(1);
        rec.setAutoDreamEnabled(false);
        SettingsMapper mapper = mock(SettingsMapper.class);
        when(mapper.selectOneById(1)).thenReturn(rec);
        BundledSkillEnabledGates.bridgeSettingsMapper(mapper);
        System.setProperty("NEXUSAI_AUTO_DREAM", "true");
        try {
            assertThat(AutoDreamConsolidator.isAutoDreamEnabledBySettingsOrEnv()).isFalse();
        } finally {
            BundledSkillEnabledGates.bridgeSettingsMapper(null);
            System.clearProperty("NEXUSAI_AUTO_DREAM");
        }
    }

    @Test
    @DisplayName("[V56 用户拍板 2026-08-30] 无 DB 无 env → 默认 true（默认开）；settings 文件三源序读取已弃用")
    void autoDreamEnabled_defaultTrueWhenUnconfigured() {
        // WHY: 用户决策「无值回落 true（默认开）」；旧「settings 文件三源序 local→project→user」
        //   读取已弃用（AutoDreamConsolidator.readAutoDreamEnabledSetting / readAutoDreamEnabledFromFile
        //   删除）——静态桥接未注入（null）+ env 未设 → isAutoDreamEnabledBySettingsOrEnv 恒 true。
        BundledSkillEnabledGates.bridgeSettingsMapper(null);
        assertThat(AutoDreamConsolidator.isAutoDreamEnabledBySettingsOrEnv()).isTrue();
    }

    @Test
    @DisplayName("会话门控扫 transcript 而非 .md：造 5 个 session.jsonl（mtime>lastAt）→ 通过")
    void sessionGate_countsTranscriptsNotMarkdown() throws IOException {
        // 会话门控数据源 = CC transcript（consolidationLock.ts:118-124 listSessionsTouchedSince）
        // .md 数据源偏移已修正（OPD-M-31）。memory 目录内 .md 不计入。
        // FIX-MC：MemoryStorage CRUD 死层已删（CC 无程序化写 API）→ 直接用 Files 造 .md 种子文件
        java.nio.file.Files.writeString(mem.resolve("mem0.md"), "---\ntype: user\n---\nbody\n");
        consolidator.setAutoDreamEnabled(() -> true);

        consolidator.consolidateIfNeeded(ws, null, null);

        // 1 个 .md 文件但 0 个 transcript → 会话门阻断（< minSessions=5）· D-18 void 化后
        //   gate 阻断无可观测 reason（CC executeAutoDream 无结果值）→ observable：
        //   无 fork + fired 遥测 0（fired 在 doConsolidate 内、所有门控之后发射）
        assertThat(query.called()).isFalse();
        assertThat(telemetry.getCounter("tengu_auto_dream_fired")).isZero();
    }

    @Test
    @DisplayName("锁被存活 PID 持有（stale 窗口内）→ tryAcquire 返回 null（consolidationLock.ts:60-66）")
    void lock_tryAcquire_blockedByLivePid() throws IOException {
        // 锁 body = 当前 JVM PID，mtime=now（stale 窗口内 + PID 存活 → 阻塞）
        long livePid = ProcessHandle.current().pid();
        writeLock(mem, String.valueOf(livePid), System.currentTimeMillis());

        ConsolidationLock lock = new ConsolidationLock(mem);
        Long acquired = lock.tryAcquireConsolidationLock();

        assertThat(acquired).isNull(); // 被持有 → null（autoDream.ts:189 返回）
        // 未写入我们的 PID（不覆盖持有者）
        assertThat(Files.readString(mem.resolve(ConsolidationLock.LOCK_FILE)).trim())
            .isEqualTo(String.valueOf(livePid));
    }

    @Test
    @DisplayName("锁被死 PID 持有（mtime 新但 PID 不存在）→ 回收并写入自身 PID（consolidationLock.ts:67-68/72-73）")
    void lock_tryAcquire_reclaimsDeadPid() throws IOException {
        // 巨大 PID 必然不存在 → isProcessRunning=false → 回收
        writeLock(mem, "99999999", System.currentTimeMillis());

        ConsolidationLock lock = new ConsolidationLock(mem);
        Long acquired = lock.tryAcquireConsolidationLock();

        assertThat(acquired).isNotNull();
        // 回收后写自身 PID（re-read 校验通过 · consolidationLock.ts:74-81）
        assertThat(Files.readString(mem.resolve(ConsolidationLock.LOCK_FILE)).trim())
            .isEqualTo(String.valueOf(ProcessHandle.current().pid()));
    }

    @Test
    @DisplayName("锁超 stale 窗口（>1h）即使 PID 存活也回收（PID 复用防护 · consolidationLock.ts:18/60）")
    void lock_tryAcquire_reclaimsStaleEvenIfLive() throws IOException {
        long livePid = ProcessHandle.current().pid();
        long staleMtime = System.currentTimeMillis() - 2L * 60 * 60 * 1000; // 2h 前
        writeLock(mem, String.valueOf(livePid), staleMtime);

        ConsolidationLock lock = new ConsolidationLock(mem);
        Long acquired = lock.tryAcquireConsolidationLock();

        assertThat(acquired).isNotNull();
        assertThat(acquired).isEqualTo(staleMtime); // 返回 priorMtime 供 rollback
    }

    @Test
    @DisplayName("rollback priorMtime=0 → unlink（恢复无锁 · consolidationLock.ts:96-99）")
    void lock_rollback_unlinksWhenPriorMtimeZero() throws IOException {
        ConsolidationLock lock = new ConsolidationLock(mem);
        Long acquired = lock.tryAcquireConsolidationLock(); // 无前锁 → priorMtime=0
        assertThat(acquired).isEqualTo(0L);
        assertThat(mem.resolve(ConsolidationLock.LOCK_FILE)).exists();

        lock.rollbackConsolidationLock(0L);

        assertThat(mem.resolve(ConsolidationLock.LOCK_FILE)).doesNotExist();
    }

    @Test
    @DisplayName("合并失败 → 遥测 failed + rollback 删锁（priorMtime=0 → unlink · consolidationLock.ts:96-99）")
    void failedFork_emitsFailedAndUnlinksLock() throws IOException {
        writeSessions(ws, 5);
        consolidator.setAutoDreamEnabled(() -> true);
        consolidator.setForkedQuery((params) -> {
            throw new IllegalStateException("boom");
        });

        consolidator.consolidateIfNeeded(ws, null, null);

        // D-18 void 化：失败可观测 = failed 遥测 + rollback 删锁（reason 文本不再有载体）
        // rollback: priorMtime=0 → unlink（恢复无锁）
        assertThat(mem.resolve(ConsolidationLock.LOCK_FILE)).doesNotExist();
        assertThat(telemetry.getCounter("tengu_auto_dream_fired")).isEqualTo(1);
        assertThat(telemetry.getCounter("tengu_auto_dream_failed")).isEqualTo(1);
    }

    @Test
    @DisplayName("abort 中止 → 回滚锁（Java 无 kill 路径，abort 为唯一回滚点 · DreamTask.ts:136-155）")
    void abortDuringFork_rollsBackLock() throws IOException {
        writeSessions(ws, 5);
        consolidator.setAutoDreamEnabled(() -> true);
        // 造既有锁（priorMtime=2 天前，> 时间门 24h 才不阻断）：abort 后 mtime 回退到 priorMtime
        // （不保留 now）
        long priorMtime = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000;
        writeLock(mem, "12345", priorMtime);
        consolidator.setForkedQuery((params) -> {
            // 模拟用户 kill：abort 透传的 controller，然后 fork 抛异常
            params.toolUseContext().abortController().abort("user_cancelled");
            throw new IllegalStateException("aborted-fork");
        });
        consolidator.consolidateIfNeeded(ws, null, null);

        // D-18 void 化：abort 可观测 = 锁 mtime 回退（reason=aborted 不再有载体）
        // CC: kill() 已 abort+rollback（DreamTask.ts:136-155）catch 直接 return 不双回滚；
        // Java 无 kill 路径 → abort 分支回滚锁（mtime 回退到 priorMtime，下轮时间门可重试）
        long after = Files.getLastModifiedTime(mem.resolve(ConsolidationLock.LOCK_FILE)).toMillis();
        assertThat(after).isEqualTo(priorMtime);
        // aborted 不发射 failed（autoDream.ts:262-265 catch 直接 return）
        assertThat(telemetry.getCounter("tengu_auto_dream_failed")).isZero();
    }

    // ═══════════════════ OPD-TP-09 dream registry 接线（W7-02）═══════════════════

    @Test
    @DisplayName("fork 成功 → registerDreamTask + completeDreamTask（autoDream.ts:203-208/235 接线）")
    void forkSuccess_registersAndCompletesDreamTask() throws IOException {
        // WHY: dream fork 原本不可见（RK-9）；注册进 registry + SDK task_started 后前端可见。
        //   fork 成功后必须 completeDreamTask（status=completed + notified=true 立即）——
        //   否则 eviction 永不回收（framework.ts:124-147）。
        DreamTaskRegistry registry = new DreamTaskRegistry();
        consolidator.setDreamTaskRegistry(registry);
        writeSessions(ws, 5);
        consolidator.setAutoDreamEnabled(() -> true);

        consolidator.consolidateIfNeeded(ws, null, null);

        DreamTaskState state = registry.listAll().get(0);
        assertThat(state.sessionsReviewing()).as("sessionsReviewing = sessionIds.length（autoDream.ts:204）")
            .isEqualTo(5);
        assertThat(state.status()).as("fork 成功 → completeDreamTask（autoDream.ts:235）")
            .isEqualTo(com.nexusai.application.agent.tasks.BackgroundTaskStatus.COMPLETED);
        assertThat(state.notified()).isTrue();
        assertThat(state.abortController()).isNull();
    }

    @Test
    @DisplayName("fork 失败 → failDreamTask + 遥测 failed + rollback（autoDream.ts:266-271 接线）")
    void forkFailure_failsDreamTaskAndRollsBack() throws IOException {
        DreamTaskRegistry registry = new DreamTaskRegistry();
        consolidator.setDreamTaskRegistry(registry);
        writeSessions(ws, 5);
        consolidator.setAutoDreamEnabled(() -> true);
        consolidator.setForkedQuery((params) -> {
            throw new IllegalStateException("boom");
        });

        consolidator.consolidateIfNeeded(ws, null, null);

        DreamTaskState state = registry.listAll().get(0);
        assertThat(state.status()).as("fork 失败 → failDreamTask（autoDream.ts:268）")
            .isEqualTo(com.nexusai.application.agent.tasks.BackgroundTaskStatus.FAILED);
        assertThat(state.notified()).isTrue();
        assertThat(telemetry.getCounter("tengu_auto_dream_failed")).isEqualTo(1);
        // rollback：priorMtime=0 → unlink
        assertThat(mem.resolve(ConsolidationLock.LOCK_FILE)).doesNotExist();
    }

    @Test
    @DisplayName("watcher 逐条 addDreamTurn（text.trim + toolUseCount + Edit/Write touched · autoDream.ts:281-313 接线）")
    void watcher_collectsDreamTurnsAndTouchedPaths() {
        // WHY: dream 进度（turns/filesTouched/phase）经 onMessage watcher 逐条 addDreamTurn 收集
        //   供 live display + Improved 完成消息（filesTouched 非空才发，autoDream.ts:238-248）。
        DreamTaskRegistry registry = new DreamTaskRegistry();
        consolidator.setDreamTaskRegistry(registry);
        String taskId = registry.registerDreamTask(1, 1L, new com.nexusai.application.agent.tool.AbortController());
        List<String> holder = new ArrayList<>();

        // 1 条 assistant 消息：文本 + Edit tool_use（file_path）
        List<ToolCallDto> toolCalls = List.of(new ToolCallDto(
            "tu-1", ToolNameConstants.FILE_EDIT_TOOL_NAME,
            "{\"file_path\":\"/m/proj/a.md\"}", null, false));
        ChatMessageDto assistant = new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.assistant, "assistant",
            "  分析完成  ", null, toolCalls, FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null,
            null, null, null, null, false);

        consolidator.makeDreamProgressWatcher(taskId, holder).accept(assistant);

        DreamTaskState state = registry.getDreamTask(taskId).orElseThrow();
        assertThat(state.turns()).hasSize(1);
        assertThat(state.turns().get(0).text()).as("text.trim()（autoDream.ts:310）").isEqualTo("分析完成");
        assertThat(state.turns().get(0).toolUseCount()).isEqualTo(1);
        assertThat(state.filesTouched()).containsExactly("/m/proj/a.md");
        assertThat(state.phase()).isEqualTo(DreamTaskState.DreamPhase.UPDATING); // 首个 Edit 落点翻
        assertThat(holder).containsExactly("/m/proj/a.md"); // Improved 完成消息数据源
    }

    @Test
    @DisplayName("kill（TaskStop 分发）后 abort catch 不双回滚——kill 是唯一回滚点（autoDream.ts:262-265 + DreamTask.ts:153-155）")
    void killDuringFork_abortCatchDoesNotDoubleRollback() throws IOException {
        // WHY: 用户 kill（TaskStop → DreamTask.kill）已 abort + rollback 锁 + 置 status=killed；
        //   catch 若再回滚=双回滚（CC :262-265 直接 return）。回滚须恰好一次（kill 内）——
        //   否则锁 mtime 回退两次虽幂等，但体现"kill 接管回滚、catch 不干预"的职责边界。
        writeSessions(ws, 5);
        consolidator.setAutoDreamEnabled(() -> true);
        long priorMtime = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000;
        writeLock(mem, "12345", priorMtime);

        DreamTaskRegistry registry = new DreamTaskRegistry();
        AtomicInteger rollbackCalls = new AtomicInteger();
        consolidator.setDreamTaskRegistry(registry);
        // setDreamTaskRegistry 已注入真实 ConsolidationLock seam；覆盖为计数 seam 以断言恰好一次
        registry.setRollbackConsolidationLock(m -> rollbackCalls.incrementAndGet());

        consolidator.setForkedQuery((params) -> {
            // 模拟 TaskStop 分发：kill 刚注册的 dream 任务（abort + status=killed + rollback 一次）
            DreamTaskState dream = registry.listAll().get(0);
            registry.kill(dream.id());
            throw new IllegalStateException("aborted-fork");
        });

        consolidator.consolidateIfNeeded(ws, null, null);

        // D-18 void 化：kill 路径可观测 = rollback 恰好一次 + 终态 killed（reason 不再有载体）
        assertThat(rollbackCalls.get()).as("kill 内回滚一次；catch 不双回滚（autoDream.ts:262-265）")
            .isEqualTo(1);
        // 终态=killed（kill 置的，catch 不覆盖）
        assertThat(registry.listAll().get(0).status())
            .isEqualTo(com.nexusai.application.agent.tasks.BackgroundTaskStatus.KILLED);
        // aborted 不发射 failed
        assertThat(telemetry.getCounter("tengu_auto_dream_failed")).isZero();
    }

    @Test
    @DisplayName("rollback 回退 mtime（priorMtime>0 → 写空 body + utimes · consolidationLock.ts:100-102）")
    void rollback_rewindsMtime() throws IOException {
        // 造一个既有锁：mtime=2h 前（等价"上次合并时间"），rollback 后 mtime 回退到该值
        long priorMtime = System.currentTimeMillis() - 2L * 60 * 60 * 1000;
        writeLock(mem, "12345", priorMtime);

        ConsolidationLock lock = new ConsolidationLock(mem);
        Long acquired = lock.tryAcquireConsolidationLock();
        assertThat(acquired).isNotNull();
        assertThat(acquired).isEqualTo(priorMtime); // 返回 priorMtime 供 rollback
        lock.rollbackConsolidationLock(priorMtime);

        // mtime 精确回退到 priorMtime（FileTime.fromMillis 无秒截断 · CC utimes 浮点秒），
        // 下次触发延迟到 minHours
        long after = Files.getLastModifiedTime(mem.resolve(ConsolidationLock.LOCK_FILE)).toMillis();
        assertThat(after).isEqualTo(priorMtime);
        // body 清空（防自身存活 PID 误判为持有者 · consolidationLock.ts:87-90）
        assertThat(Files.readString(mem.resolve(ConsolidationLock.LOCK_FILE))).isBlank();
    }

    @Test
    @DisplayName("rollback 保留亚秒精度（旧实现 long seconds=priorMtime/1000 截断 · CC utimes 浮点秒）")
    void rollback_rewindsExactSubSecondMillis() throws IOException {
        // 造一个带亚秒毫秒尾的 priorMtime（对齐 CC utimes 浮点秒语义）：
        // 旧实现 seconds=priorMtime/1000 → fromMillis(seconds*1000) 会丢失尾 123ms
        long base = System.currentTimeMillis() - 2L * 60 * 60 * 1000;
        long priorMtime = (base / 1000) * 1000 + 123; // 尾 123ms
        writeLock(mem, "12345", priorMtime);

        ConsolidationLock lock = new ConsolidationLock(mem);
        Long acquired = lock.tryAcquireConsolidationLock();
        assertThat(acquired).isEqualTo(priorMtime);
        lock.rollbackConsolidationLock(priorMtime);

        long after = Files.getLastModifiedTime(mem.resolve(ConsolidationLock.LOCK_FILE)).toMillis();
        assertThat(after).isEqualTo(priorMtime); // 精确回退，亚秒不丢
    }

    @Test
    @DisplayName("合并失败 → 遥测 failed 双发射（recordEvent + logOTelEvent · autoDream.ts:267）")
    void failedFork_logOTelEventDualEmission() throws IOException {
        writeSessions(ws, 5);
        consolidator.setAutoDreamEnabled(() -> true);
        consolidator.setForkedQuery((params) -> {
            throw new IllegalStateException("boom");
        });

        consolidator.consolidateIfNeeded(ws, null, null);

        assertThat(telemetry.getCounter("tengu_auto_dream_failed")).isEqualTo(1);
        verify(telemetry).logOTelEvent(eq("tengu_auto_dream_failed"), any());
    }

    @Test
    @DisplayName("当前 session 从会话门排除（autoDream.ts:164，扁平 {configHome}/projects/{slug}/{sess-*.jsonl}）")
    void currentSession_excludedFromSessionGate() throws IOException {
        // 6 个扁平 transcript 但 1 个是当前 session → 排除后 5 个 → 通过（autoDream.ts:164）
        List<String> ids = writeSessions(ws, 6);
        consolidator.setAutoDreamEnabled(() -> true);

        // D5-A/M-11: sessionId 显式传参（替代 setCurrentSessionId volatile）
        consolidator.consolidateIfNeeded(ws, ids.get(5), null);

        assertThat(query.called()).isTrue();
    }

    @Test
    @DisplayName("D5-A/M-11：scanSessionTranscripts 参数化 —— 两会话目录隔离 + 显式 sessionId 排除当前会话")
    void scanSessionTranscripts_parameterizedIsolationBetweenWorkspaces() throws IOException {
        // WHY: M-11 跨会话交错窗口 —— 旧实现读共享 volatile workspaceDir/currentSessionId，
        //   会话 A 注入后 B 改写，A 的异步合并扫到 B 目录/排除 B 的 session。参数化后
        //   workspaceDir/sessionId 随调用显式传入，逐调用生效（本测试同实例三连调互不串扰）。
        Path wsA = tempDir.resolve("wsA");
        Path wsB = tempDir.resolve("wsB");
        List<String> idsA = writeSessions(wsA, 3);
        writeSessions(wsB, 1);

        // 目录 A → 3 个候选
        List<String> fromA = consolidator.scanSessionTranscripts(wsA, null, 0);
        assertThat(fromA).hasSize(3).containsAll(idsA);
        // 目录 B → 1 个候选（目录参数生效，不串到 wsA）
        List<String> fromB = consolidator.scanSessionTranscripts(wsB, null, 0);
        assertThat(fromB).hasSize(1);
        // 显式 sessionId → 排除当前会话（autoDream.ts:164）
        List<String> excluded = consolidator.scanSessionTranscripts(wsA, idsA.get(0), 0);
        assertThat(excluded).hasSize(2).doesNotContain(idsA.get(0));
        // sessionId=null → 不排除
        List<String> noExclusion = consolidator.scanSessionTranscripts(wsA, null, 0);
        assertThat(noExclusion).hasSize(3);
    }

    @Test
    @DisplayName("D5-A/M-11：consolidateIfNeeded 参数化 —— 显式 workspaceDir 生效（有会话目录不触发误扫）")
    void consolidateIfNeeded_explicitWorkspaceDirBlocksWhenDirEmpty() throws IOException {
        // WHY: workspaceDir 参数化回归锚点 —— 即使其他目录（本测试 ws）已备 5 会话，
        //   显式传入的空目录必须被扫描并阻断会话门（旧 volatile 实现会扫错目录而误触发 fork）。
        writeSessions(ws, 5);
        Path emptyWs = tempDir.resolve("emptyWs");
        Files.createDirectories(emptyWs);
        consolidator.setAutoDreamEnabled(() -> true);

        consolidator.consolidateIfNeeded(emptyWs, null, null);

        assertThat(query.called()).isFalse();
        assertThat(telemetry.getCounter("tengu_auto_dream_fired")).isZero();
    }

    @Test
    @DisplayName("会话门扁平扫：生产 sess-* 计入 + agent-* 排除 + UUID 老格式兼容 + 陈旧不计（对齐 CC 意图）")
    void sessionGate_flatScanSkipsAgentAndStale() throws IOException {
        // [B1 修复] 会话门过滤对齐 CC validateUuid 意图（排除 agent-*.jsonl 子代理 sidechain）：
        //   生产主会话 = sess-*.jsonl 计入；agent-* 前缀排除；非 .jsonl / mtime 陈旧不计；
        //   UUID 老格式仍兼容计入（listCandidates:169-198 语义 · sessionStoragePortable.ts:26-30）。
        // [transcript-reloc] 文件写到 SessionStorage.getProjectDir(ws)（生产 transcript 目录）——
        //   扫描端 scanSessionTranscripts 派生同一 config-home 目录，非 ws 项目根。
        Path dir = SessionStorage.getProjectDir(ws);
        Files.createDirectories(dir);
        long staleMtime = System.currentTimeMillis() - 2L * 60 * 60 * 1000;
        // 5 个生产格式主会话（sess-<8hex>.jsonl）→ 会话门通过
        for (int i = 0; i < 5; i++) {
            Path p = dir.resolve("sess-" + UUID.randomUUID().toString().substring(0, 8) + ".jsonl");
            Files.writeString(p, "{}\n");
        }
        // agent-*.jsonl（子代理 sidechain → 排除）+ 非 .jsonl 尾缀 + 陈旧 .jsonl
        Files.writeString(dir.resolve("agent-123.jsonl"), "{}\n");
        Files.writeString(dir.resolve("random.txt"), "{}\n");
        Path stale = dir.resolve("sess-" + UUID.randomUUID().toString().substring(0, 8) + ".jsonl");
        Files.writeString(stale, "{}\n");
        Files.setLastModifiedTime(stale, FileTime.fromMillis(staleMtime));
        consolidator.setAutoDreamEnabled(() -> true);

        consolidator.consolidateIfNeeded(ws, null, null);

        // 恰好 5 个有效（agent-*/random.txt/陈旧不计）→ 通过
        assertThat(query.called()).isTrue();
    }

    @Test
    @DisplayName("会话门兼容：UUID 老格式 transcript 仍计入（CC 布局，validateUuid 兼容保留）")
    void sessionGate_flatScanUuidLegacyStillCounts() throws IOException {
        // WHY: B1 放宽后 UUID 老格式（CC 原生布局）仍须计入 —— 兼容迁移期混合文件系统。
        // [transcript-reloc] 文件写到 SessionStorage.getProjectDir(ws)（生产 transcript 目录）
        Path dir = SessionStorage.getProjectDir(ws);
        Files.createDirectories(dir);
        for (int i = 0; i < 5; i++) {
            Path p = dir.resolve(UUID.randomUUID() + ".jsonl");
            Files.writeString(p, "{}\n");
        }
        consolidator.setAutoDreamEnabled(() -> true);

        consolidator.consolidateIfNeeded(ws, null, null);

        assertThat(query.called()).isTrue();
    }

    @Test
    @DisplayName("fork 成功且 filesTouched>0 → 经 appendSystemMessage 追加 verb='Improved' 的 memory_saved 完成消息（autoDream.ts:238-248）")
    void improvedCompletionMessage_emittedWhenFilesTouched() throws IOException {
        // WHY: CC autoDream.ts:238-248 —— fork 成功且 dreamState.filesTouched.length > 0 时，
        //   主 transcript 追加 {...createMemorySavedMessage(filesTouched), verb:'Improved'} 内联
        //   完成摘要（同 extractMemories "Saved N memories" surface）。Java 缺失 = 前端收不到
        //   auto-dream 完成提示（grep Improved 0 命中）。
        writeSessions(ws, 5);
        consolidator.setAutoDreamEnabled(() -> true);
        // fork 返回含 Edit toolCall 的 assistant 消息 → G-79 流式 fake（产出时调 onMessage）
        //   watcher 收集 touchedPaths（makeDreamProgressWatcher · forkedAgent.ts:578 流式回调）
        String touched = mem.resolve("claude.md").toString();
        String jsonPath = touched.replace("\\", "\\\\");
        ToolCallDto editTc = new ToolCallDto("tc-1", "Edit",
            "{\"file_path\": \"" + jsonPath + "\", \"content\": \"x\"}", null, null);
        ChatMessageDto assistant = new ChatMessageDto("m1", null, Role.assistant, "assistant", "", null,
            List.of(editTc), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
        consolidator.setForkedQuery(params -> {
            // 流式语义：query 产出一条消息即回调 onMessage（G-79 · forkedAgent.ts:578）
            params.onMessage().accept(assistant);
            return new ForkedAgentResult(List.of(assistant), ForkedAgentResult.ForkUsage.empty());
        });

        List<SystemMessage> received = new ArrayList<>();
        consolidator.consolidateIfNeeded(ws, null, received::add);

        assertThat(received).hasSize(1);
        SystemMessage sm = received.get(0);
        // CC: {...createMemorySavedMessage(filesTouched), verb:'Improved'}（messages.ts:4460-4471 +
        //   autoDream.ts:246）—— type=system subtype=memory_saved verb=Improved
        assertThat(sm.role()).isEqualTo("system");
        assertThat(sm.subtype()).isEqualTo("memory_saved");
        assertThat(sm.verb()).isEqualTo("Improved");
        assertThat(sm.writtenPaths()).contains(touched);
    }

    @Test
    @DisplayName("fork 成功但 filesTouched 为空 → 不追加 Improved 完成消息（autoDream.ts:242 length>0 门）")
    void improvedCompletionMessage_skippedWhenNoFilesTouched() throws IOException {
        // WHY: CC autoDream.ts:240-242 —— 空 touchedPaths 不发（isDreamTask &&
        //   dreamState.filesTouched.length > 0 门）；空文件写不产生"Improved"提示。
        writeSessions(ws, 5);
        consolidator.setAutoDreamEnabled(() -> true);
        // 默认 RecordingQuery 返回空 messages → touchedPaths 空 → 不发（autoDream.ts:242）
        List<SystemMessage> received = new ArrayList<>();
        consolidator.consolidateIfNeeded(ws, null, received::add);

        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("e2e OPD-TP-11：触发 autoDream → SDK task_started 可见 + Improved 消息生产（前端渲染通道全链路）")
    void e2e_autoDreamTrigger_producesSdkTaskStartedAndImprovedMessage() throws IOException {
        // WHY: OPD-TP-11 —— dream task 经 TaskFrameworkService store + SDK task_started 事件
        //   暴露给 Web 前端渲染（footer/dialog 等价），Improved 完成消息经 appendSystemMessage
        //   追加主 transcript 内联摘要。任一环断开 = 前端看不到 dream 任务或收不到完成提示
        //   （OPD-TP-11 e2e 回归锚点）。
        writeSessions(ws, 5);
        consolidator.setAutoDreamEnabled(() -> true);

        // 生产装配：SdkEventQueue → TaskFrameworkService → DreamTaskRegistry（TaskConfiguration bean 等价）
        com.nexusai.application.agent.tasks.SdkEventQueue sdk = new com.nexusai.application.agent.tasks.SdkEventQueue();
        com.nexusai.application.agent.tasks.TaskFrameworkService framework =
            new com.nexusai.application.agent.tasks.TaskFrameworkService(sdk);
        DreamTaskRegistry registry = new DreamTaskRegistry(framework);
        consolidator.setDreamTaskRegistry(registry);

        // fork 返回含 Edit toolCall 的 assistant 消息 → watcher 收集 touchedPaths
        String touched = mem.resolve("claude.md").toString();
        String jsonPath = touched.replace("\\", "\\\\");
        ToolCallDto editTc = new ToolCallDto("tc-1", "Edit",
            "{\"file_path\": \"" + jsonPath + "\", \"content\": \"x\"}", null, null);
        ChatMessageDto assistant = new ChatMessageDto("m1", null, Role.assistant, "assistant", "", null,
            List.of(editTc), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
        consolidator.setForkedQuery(params -> {
            // G-79 流式语义：query 产出一条消息即回调 onMessage（forkedAgent.ts:578）
            params.onMessage().accept(assistant);
            return new ForkedAgentResult(List.of(assistant), ForkedAgentResult.ForkUsage.empty());
        });

        List<SystemMessage> received = new ArrayList<>();
        consolidator.consolidateIfNeeded(ws, null, received::add);

        // (1) SDK task_started 已发 → 前端可渲染 dream 任务卡（framework.ts:104-116 + TaskFrameworkService:91）
        var sdkEvents = sdk.drainSdkEvents("sess");
        assertThat(sdkEvents).hasSize(1);
        var started = (com.nexusai.application.agent.tasks.SdkEventQueue.TaskStartedEvent) sdkEvents.get(0).event();
        assertThat(started.subtype()).isEqualTo("task_started");
        assertThat(started.taskType()).isEqualTo("dream");
        assertThat(started.description()).isEqualTo("dreaming");
        // (2) Improved 完成消息生产（autoDream.ts:238-248）
        assertThat(received).hasSize(1);
        SystemMessage sm = received.get(0);
        assertThat(sm.subtype()).isEqualTo("memory_saved");
        assertThat(sm.verb()).isEqualTo("Improved");
        // (3) registry 富状态同步终态（completeDreamTask → 可 evict）
        DreamTaskState state = registry.listAll().get(0);
        assertThat(state.status())
            .isEqualTo(com.nexusai.application.agent.tasks.BackgroundTaskStatus.COMPLETED);
        assertThat(state.notified()).isTrue();
    }

    @Test
    @DisplayName("setConfigSupplier 注入 minHours 抬高 → 时间门阻断（autoDream.ts:73-91/141 动态阈值）")
    void configSupplier_minHoursInjected_blocksTimeGate() throws IOException {
        // WHY (D5 动态阈值行为级 · ODF-D4D5): CC getConfig()（autoDream.ts:73-91）每轮 runAutoDream
        //       读取 GB tengu_onyx_plover 调度阈值；时间门用 cfg.minHours（autoDream.ts:141
        //       hoursSince < cfg.minHours 阻断）。旧测试只覆盖 DEFAULTS 24/5 硬编码 → 注入改变
        //       门控结果的行为级覆盖缺失。FIX-AD setConfigSupplier 注入 minHours=24 + 锁 mtime
        //       30 分钟前 → hoursSince=0.5 < 24 → time_gate 阻断（若用默认无锁 lastAt=0 则
        //       hoursSince 巨大必过，无法证明 minHours 生效）。
        writeLock(mem, "99999999", System.currentTimeMillis() - 30L * 60 * 1000); // 30min 前
        consolidator.setAutoDreamEnabled(() -> true);
        consolidator.setConfigSupplier(() -> new AutoDreamConfig(24.0, 1));

        consolidator.consolidateIfNeeded(ws, null, null);

        // D-18 void 化：门控阻断可观测 = 无 fork + fired 遥测 0（fired 在 doConsolidate 内、
        //   所有门控之后发射；CC executeAutoDream 无 reason 结果值）
        assertThat(query.called()).isFalse();
        assertThat(telemetry.getCounter("tengu_auto_dream_fired")).isZero();
    }

    @Test
    @DisplayName("setConfigSupplier 注入 minHours/minSessions 降低 → 时间门/会话门全过 → fork（autoDream.ts:73-91/141/166）")
    void configSupplier_minHoursAndSessionsLowered_passesGates() throws IOException {
        // WHY (D5 动态阈值行为级 · ODF-D4D5): minHours=0.5（锁 mtime 2h 前 → hoursSince=2 ≥ 0.5
        //       时间门过）+ minSessions=1（1 个 transcript ≥ 1 会话门过）→ 注入阈值改变门控结果。
        //       若实现仍用硬编码 24/5，此配置下时间门(2<24) 与 会话门(1<5) 均应阻断 → 测试必红。
        //       §8 注意门序：时间门先于扫描节流/会话门；扫描节流 lastSessionScanAt=0 首轮必过。
        writeLock(mem, "99999999", System.currentTimeMillis() - 2L * 60 * 60 * 1000); // 2h 前
        writeSessions(ws, 1);
        consolidator.setAutoDreamEnabled(() -> true);
        consolidator.setConfigSupplier(() -> new AutoDreamConfig(0.5, 1));

        consolidator.consolidateIfNeeded(ws, null, null);

        assertThat(query.called()).isTrue();
    }

    @Test
    @DisplayName("setConfigSupplier 注入 minSessions 抬高 → 会话门阻断（autoDream.ts:73-91/166 动态阈值）")
    void configSupplier_minSessionsInjected_blocksSessionGate() throws IOException {
        // WHY (D5 动态阈值行为级 · ODF-D4D5): 会话门用 cfg.minSessions（autoDream.ts:166
        //       sessionIds.length < cfg.minSessions 阻断）。注入 minHours=0.5（时间门过）+
        //       minSessions=3，实际 2 个 transcript → 会话门阻断 reason=session_gate。
        //       若实现仍用硬编码 minSessions=5 → 2 < 5 同样阻断（无法区分），但 2 < 3 是注入值
        //       命中：证明门控读 cfg 而非常量。
        writeLock(mem, "99999999", System.currentTimeMillis() - 2L * 60 * 60 * 1000); // 2h 前
        writeSessions(ws, 2);
        consolidator.setAutoDreamEnabled(() -> true);
        consolidator.setConfigSupplier(() -> new AutoDreamConfig(0.5, 3));

        consolidator.consolidateIfNeeded(ws, null, null);

        // D-18 void 化：会话门阻断可观测 = 无 fork + fired 遥测 0（reason 不再有载体）
        assertThat(query.called()).isFalse();
        assertThat(telemetry.getCounter("tengu_auto_dream_fired")).isZero();
    }

    @Test
    @DisplayName("setConfigSupplier 非法配置（minHours<=0）→ 回退 DEFAULTS 24（autoDream.ts:80-85）")
    void configSupplier_invalidMinHours_fallsBackTo24() throws IOException {
        // WHY (D5 非法值回退 · ODF-D4D5): CC getConfig() 逐字段校验（autoDream.ts:80-85
        //       typeof number && isFinite && >0，非法 → DEFAULTS.minHours=24）。注入 minHours=0
        //       （非法 ≤0）→ 回退 24 → 锁 mtime 30min 前 → hoursSince≈0.5 < 24 → time_gate。
        //       若不回退（用 0）→ 0.5 ≥ 0 时间门过 → 测试必红。
        writeLock(mem, "99999999", System.currentTimeMillis() - 30L * 60 * 1000); // 30min 前
        consolidator.setAutoDreamEnabled(() -> true);
        consolidator.setConfigSupplier(() -> new AutoDreamConfig(0.0, 1));

        consolidator.consolidateIfNeeded(ws, null, null);

        // D-18 void 化：阻断可观测 = 无 fork + fired 遥测 0（reason 不再有载体）
        assertThat(query.called()).isFalse();
        assertThat(telemetry.getCounter("tengu_auto_dream_fired")).isZero();
    }

    @Test
    @DisplayName("setConfigSupplier 非法配置（minSessions<=0）→ 回退 DEFAULTS 5（autoDream.ts:86-91）")
    void configSupplier_invalidMinSessions_fallsBackTo5() throws IOException {
        // WHY (D5 非法值回退 · ODF-D4D5): CC getConfig() minSessions 校验（autoDream.ts:86-91
        //       typeof number && isFinite && >0 → DEFAULTS.minSessions=5）。注入 minHours=0.5
        //       （合法，时间门过）+ minSessions=0（非法 ≤0）→ 回退 5 → 3 个 transcript < 5 →
        //       会话门阻断。若不回退（用 0）→ 3 ≥ 0 会话门过 → 测试必红。
        //       §8：effectiveConfig 对 minSessions 仅 >0 校验（无 isFinite），勿断言 NaN。
        writeLock(mem, "99999999", System.currentTimeMillis() - 2L * 60 * 60 * 1000); // 2h 前
        writeSessions(ws, 3);
        consolidator.setAutoDreamEnabled(() -> true);
        consolidator.setConfigSupplier(() -> new AutoDreamConfig(0.5, 0));

        consolidator.consolidateIfNeeded(ws, null, null);

        // D-18 void 化：阻断可观测 = 无 fork + fired 遥测 0（reason 不再有载体）
        assertThat(query.called()).isFalse();
        assertThat(telemetry.getCounter("tengu_auto_dream_fired")).isZero();
    }

    @Test
    @DisplayName("setConfigSupplier(null) → 回退 DEFAULTS 24/5（autoDream.ts:63-66 + Java FIX-AD null 兜底）")
    void configSupplier_null_fallsBackToDefaults() throws IOException {
        // WHY (D5 非法值回退 · ODF-D4D5): 生产 setConfigSupplier 未接线时 null → () -> DEFAULTS
        //       （24/5，autoDream.ts:63-66 DEFAULTS）。注入 null → 时间门用 24 → 锁 mtime 2h 前
        //       → hoursSince=2 < 24 → time_gate 阻断（不因 null NPE，也不空转门控）。
        writeLock(mem, "99999999", System.currentTimeMillis() - 2L * 60 * 60 * 1000); // 2h 前
        writeSessions(ws, 2);
        consolidator.setAutoDreamEnabled(() -> true);
        consolidator.setConfigSupplier(null);


        consolidator.consolidateIfNeeded(ws, null, null);

        // D-18 void 化：阻断可观测 = 无 fork + fired 遥测 0（reason 不再有载体）
        assertThat(query.called()).isFalse();
        assertThat(telemetry.getCounter("tengu_auto_dream_fired")).isZero();
    }

    @Test
    @DisplayName("G-77：extra/prompt 尾部字节对齐——sessions 列表 join('\\n') 无尾换行 + text block 收尾无 \\n（autoDream.ts:216-221 + consolidationPrompt.ts:64）")
    void g77_extraAndPromptTail_byteAligned() throws IOException {
        // WHY（探查 v4.1 △-1/F-2 + EV-038）：CC extra = `sessionIds.map(id => `- ${id}`).join('\n')`
        //   结尾无尾换行；consolidationPrompt.ts:64 收尾反引号紧跟插值（无尾换行）。旧 Java
        //   两条路径各多 1 个 \n（buildExtra 每条 `- id\n` + text block 收尾 \n）→ prompt 尾部
        //   +2 \n。本测试锁字节：prompt 以最后一个 session id 结尾、无尾 \n、条目间单个 \n 连接。
        List<String> ids = writeSessions(ws, 5);
        consolidator.setAutoDreamEnabled(() -> true);

        // DC-9（IMP-MV2-30）：测试包观察点捕获 fork 参数（生产 lastForkParamsRef 已删）
        ForkedAgentParams p = captureForkParams(() -> consolidator.consolidateIfNeeded(ws, null, null));
        assertThat(p).isNotNull();
        ChatMessageDto promptMsg = p.promptMessages().get(p.promptMessages().size() - 1);
        String prompt = promptMsg.content();
        // 列表头 + 条目间 \n 连接（CC join('\n')）——扫描顺序不保证（Files.list），逐 id 断言
        assertThat(prompt).contains("Sessions since last consolidation (5):\n- ");
        for (String id : ids) {
            assertThat(prompt).contains("\n- " + id);
        }
        // 尾部字节对齐：以最后一个 "- sess-<8hex>" 结尾、无尾 \n（join 无尾换行 + text block 收尾无 \n）。
        // [B1 修复] 生产 sessionId = sess-<8hex>（writeSessions 造生产真实格式）。
        assertThat(prompt).matches("(?s).*- sess-[0-9a-f]{8}$");
        assertThat(prompt).doesNotEndWith("\n");
    }

    @Test
    @DisplayName("G-80：锁文件读失败（stat 成功 + readString 解码失败）→ 无锁路径返回 0（consolidationLock.ts:52-58 Promise.all catch → mtimeMs ?? 0）")
    void lock_tryAcquire_readFailure_fallsBackToZero() throws IOException {
        // WHY（探查 v4.1 △-4）：CC Promise.all([stat, readFile]) 任一失败 → catch → mtimeMs/holderPid
        //   均 undefined → 走「无锁」路径写 PID + re-read 校验 → 返回 `mtimeMs ?? 0` = 0。旧 Java
        //   实现 stat 成功 + readString 抛 IOException 时 mtimeMs 残留 → 返回旧 mtime（rollback
        //   目标错误：CC unlink vs Java 恢复旧 mtime）。故障注入：锁文件内容为非法 UTF-8 字节 →
        //   getLastModifiedTime 成功、readString 抛 MalformedInputException（IOException 子类）。
        long oldMtime = System.currentTimeMillis() - 2L * 60 * 60 * 1000;
        Files.createDirectories(mem);
        Path lock = mem.resolve(ConsolidationLock.LOCK_FILE);
        Files.write(lock, new byte[]{(byte) 0xFF, (byte) 0xFE});
        Files.setLastModifiedTime(lock, FileTime.fromMillis(oldMtime));

        ConsolidationLock lockObj = new ConsolidationLock(mem);
        Long acquired = lockObj.tryAcquireConsolidationLock();

        assertThat(acquired).as("读失败 → mtimeMs 视为未定义 → 返回 0（非旧 mtime）").isEqualTo(0L);
        // CC :70-81 读失败后仍写 PID + re-read 校验（锁照常持有）
        assertThat(Files.readString(lock).trim())
            .isEqualTo(String.valueOf(ProcessHandle.current().pid()));
    }

    // ════════════════════════════════════════════════════════════════════
    // IMP-E-3 (OPD-CM5-E-06) · 手动 /dream doDream
    // ════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[IMP-E-3] doDream 手动整合：recordConsolidation 盖章 + DREAM_PROMPT_PREFIX 前缀 + buildConsolidationPrompt + args + fork（dream.ts:27-42）")
    void doDream_enabled_stampsLockAndForksWithManualPrompt() throws IOException {
        // WHY: OPD-CM5-E-06 —— CC /dream skill（skills/bundled/dream.ts）手动整合执行；Java
        //   doDream 对齐：gate（isAutoMemoryEnabled · dream.ts:26 isEnabled）→ recordConsolidation
        //   盖章（dream.ts:32）→ DREAM_PROMPT_PREFIX + buildConsolidationPrompt + args（:34-38）
        //   → buildForkParams（受限 canUseTool + skipTranscript）→ RunForkedAgent。
        consolidator.setAutoMemoryEnabled(() -> true);
        consolidator.setAutoDreamEnabled(() -> true);

        ForkedAgentParams p = captureForkParams(() -> consolidator.doDream(ws, "用户附加上下文"));
        assertThat(p).isNotNull();
        // 手动 dream 前缀 + 4 阶段模板（dream.ts:34-35）
        ChatMessageDto promptMsg = p.promptMessages().get(p.promptMessages().size() - 1);
        assertThat(promptMsg.content()).contains("# Dream: Memory Consolidation (manual run)");
        assertThat(promptMsg.content()).contains("Phase 4 — Prune and index");
        assertThat(promptMsg.content()).contains("## Additional context from user\n\n用户附加上下文");
        // fork 参数契约（同自动 dream：受限 canUseTool + skipTranscript + querySource=auto_dream）
        assertThat(p.skipTranscript()).isTrue();
        assertThat(p.querySource()).isEqualTo(QuerySource.AUTO_DREAM);
        assertThat(p.canUseTool()).isNotNull();
        // recordConsolidation 已盖章（dream.ts:32 · 写 PID body，锁 mtime 即 lastConsolidatedAt）
        assertThat(mem.resolve(ConsolidationLock.LOCK_FILE)).exists();
        assertThat(Files.readString(mem.resolve(ConsolidationLock.LOCK_FILE)).trim())
            .isEqualTo(String.valueOf(ProcessHandle.current().pid()));
    }

    @Test
    @DisplayName("[IMP-E-3] doDream 手动整合：无 args → 不附加 Additional context（dream.ts:37-38 仅 args 非空时）")
    void doDream_withoutArgs_omitsAdditionalContext() {
        // WHY: CC dream.ts:37-38 `if (args) prompt += ...` —— args 空 → 不追加 Additional context
        consolidator.setAutoMemoryEnabled(() -> true);

        ForkedAgentParams p = captureForkParams(() -> consolidator.doDream(ws, null));
        assertThat(p).isNotNull();
        ChatMessageDto promptMsg = p.promptMessages().get(p.promptMessages().size() - 1);
        assertThat(promptMsg.content()).contains("# Dream: Memory Consolidation (manual run)");
        assertThat(promptMsg.content()).doesNotContain("## Additional context from user");
    }

    @Test
    @DisplayName("[IMP-E-3] doDream gate：isAutoMemoryEnabled=false → 拒绝（dream.ts:26 isEnabled），不盖章不 fork")
    void doDream_autoMemoryDisabled_skips() {
        // WHY: CC /dream skill isEnabled = () => isAutoMemoryEnabled()（dream.ts:26）——auto-memory
        //   关闭时 skill 不注册，REST 直调等价拒绝；recordConsolidation 不应执行（skill 未触发）
        consolidator.setAutoMemoryEnabled(() -> false);
        consolidator.setAutoDreamEnabled(() -> true);

        AutoDreamConsolidator.DreamResult result = consolidator.doDream(ws, "args");
        assertThat(result.started()).isFalse();
        assertThat(result.writtenPaths()).isEmpty();
        // 未盖章（gate 阻断 → recordConsolidation 不执行，dream.ts:26 先于 :32）
        assertThat(mem.resolve(ConsolidationLock.LOCK_FILE)).doesNotExist();
    }

    @Test
    @DisplayName("[IMP-E-3] doDream 返回 writtenPaths：watcher 收集 Edit/Write 触摸路径（makeDreamProgressWatcher 同自动 dream）")
    void doDream_watcherCollectsWrittenPaths() throws IOException {
        // WHY: doDream 结果承载 writtenPaths（REST 回包）——watcher（makeDreamProgressWatcher）
        //   与自动 dream 同一收集链；taskId=null（手动不注册 DreamTask）→ 仅收集不 addDreamTurn。
        consolidator.setAutoMemoryEnabled(() -> true);

        // RecordingQuery 回调 onMessage：构造带 Edit tool_call 的 assistant 消息 → watcher 提取 file_path
        String uuid = UUID.randomUUID().toString();
        String fp = mem.resolve("topic.md").toString().replace("\\", "/");
        ToolCallDto editCall = new ToolCallDto(
            uuid, ToolNameConstants.FILE_EDIT_TOOL_NAME,
            "{\"file_path\":\"" + fp + "\"}", null, false);
        ChatMessageDto assistant = new ChatMessageDto(
            uuid, null, Role.assistant, "assistant", "consolidated", null,
            List.of(editCall), FinishReason.stop, null, null, "刚刚",
            OffsetDateTime.now(), null, null, null, null, null, null, false);

        query = new RecordingQuery() {
            @Override
            public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
                params.onMessage().accept(assistant);
                return new ForkedAgentResult(List.of(assistant), ForkedAgentResult.ForkUsage.empty());
            }
        };
        consolidator.setForkedQuery(query);

        AutoDreamConsolidator.DreamResult result = consolidator.doDream(ws, null);
        assertThat(result.started()).isTrue();
        assertThat(result.writtenPaths()).contains(fp);
    }

    @Test
    @DisplayName("[A1 重做] 5 参显式 memoryDir：无 projectRoot ThreadLocal 时锁/prompt 落传入目录（不回落 config-home）")
    void consolidateIfNeeded_explicitMemoryDir_usedWithoutThreadLocal() throws IOException {
        // WHY: 用户核心诉求 —— ThreadLocal 获取失败（异步 fork 线程无会话 projectRoot）不得导致
        // memoryDir 回落 config-home 写错目录。参数直传后：consolidateIfNeeded 5 参接收会话线程
        // 解析的 memoryDir，锁（ConsolidationLock）与 prompt memoryRoot 均用传入值，与当前线程
        // AutoMemPaths ThreadLocal 完全无关。
        writeSessions(ws, 5);
        consolidator.setAutoDreamEnabled(() -> true);
        // 关键：本测试线程不设 AutoMemPaths projectRoot ThreadLocal（setUp 未 setCurrentProjectRoot），
        // 即使回落也只会到 config-home —— 断言锁/prompt 用显式传入的 mem，证明不依赖 ThreadLocal。
        Path explicitMem = tempDir.resolve("explicit-mem-dir");

        ForkedAgentParams p = captureForkParams(() -> consolidator.consolidateIfNeeded(ws, null, null, null, explicitMem));

        assertThat(p).isNotNull();
        // 锁落在显式 memoryDir（非 config-home / 非 ws）—— fork 后锁文件 mtime 即 lastConsolidatedAt
        assertThat(explicitMem.resolve(ConsolidationLock.LOCK_FILE)).exists();
        // prompt memoryRoot = 显式 memoryDir（buildConsolidationPrompt memoryRoot 参数）
        ChatMessageDto promptMsg = p.promptMessages().get(p.promptMessages().size() - 1);
        assertThat(promptMsg.content()).contains(explicitMem.toString());
        // 且不含 config-home 自身路径（防回落误写）
        assertThat(promptMsg.content()).doesNotContain(
            com.nexusai.application.agent.skill.NexusaiPaths.getAppConfigHomeDir());
    }

    /**
     * DC-9（IMP-MV2-30）：测试包内 fork 参数观察点 —— mockStatic 捕获
     * {@code RunForkedAgent.run} 的 ForkedAgentParams 实参（生产 lastForkParamsRef
     * 已删除；mockito 5.x inline mock maker 支持静态 mock）。
     *
     * <p>注意：mock 期间真实 run 不执行（RecordingQuery 不触发），fork 发起证据 =
     * 捕获实参非 null（替代原 query.called() 断言）。
     */
    private ForkedAgentParams captureForkParams(Runnable trigger) {
        AtomicReference<ForkedAgentParams> captured = new AtomicReference<>();
        try (MockedStatic<RunForkedAgent> mocked = mockStatic(RunForkedAgent.class)) {
            mocked.when(() -> RunForkedAgent.run(any(), any())).thenAnswer(inv -> {
                captured.set(inv.getArgument(0));
                return new ForkedAgentResult(List.of(), ForkedAgentResult.ForkUsage.empty());
            });
            trigger.run();
        }
        return captured.get();
    }
    /** 记录最后一次 fork 调用参数的 fake ForkedQuery。 */
    static class RecordingQuery implements RunForkedAgent.ForkedQuery {
        private RunForkedAgent.ForkQueryParams last;
        private int calls;

        @Override
        public ForkedAgentResult run(RunForkedAgent.ForkQueryParams params) {
            this.last = params;
            this.calls++;
            // G-79 流式语义：query 每产出一条消息即回调 onMessage（对齐 ProductionForkedQuery
            //   fork loop 的产出即回调 + forkedAgent.ts:578）——RunForkedAgent 不再 post-hoc replay
            for (ChatMessageDto m : params.messages()) {
                params.onMessage().accept(m);
            }
            return new ForkedAgentResult(params.messages(), ForkedAgentResult.ForkUsage.empty());
        }

        boolean called() {
            return calls > 0;
        }
    }

    /**
     * 在 SessionStorage.getProjectDir(ws)（= {configHome}/projects/{sanitize(ws)}）下造 n 个扁平
     * transcript sess-&lt;8hex&gt;.jsonl（mtime=now · SessionStorage.getTranscriptPath 布局，
     * listCandidates:169-198 扁平扫描）。返回 sessionId 列表。
     *
     * <p><b>[transcript-reloc 2026-09-04]</b>：目标目录从 ws(项目根) 改为 config-home 派生目录
     * （生产 transcript 实际位置；scanSessionTranscripts 内部经 SessionStorage.getProjectDir 派生
     * 同一目录）。需先经 {@link NexusaiPaths#setConfigHomeDirOverride} 隔离（setUp 已做），否则
     * 会写真实 ~/.nexusai。
     *
     * <p><b>[B1 修复 2026-09-04]</b>：文件名改<b>生产真实格式</b> sess-&lt;8hex&gt;.jsonl
     * （[session-id-short] 键型 UUID→String short 形态，LlmAgentLoop:209）—— 旧实现造 UUID
     * 文件名，会话门过滤测试全绿（UUID 能通过）但生产 sess-*.jsonl 全被 isUuid 排除 → 测试
     * 与生产脱节、autoDream 永不触发（生产实测 6 次 STOP_HOOK 触发 0 次合并）。改造后全部
     * 用例走生产格式，真正覆盖会话门通过路径。
     */
    static List<String> writeSessions(Path ws, int n) throws IOException {
        // [transcript-reloc 2026-09-04] transcript 写生产真实目录 {configHome}/projects/{sanitize(ws)}
        //   （SessionStorage.getProjectDir(ws) · scanSessionTranscripts 内部同样派生该目录）——
        //   非 ws 项目根平铺（原心智模型错误：scan 扫 config-home 派生目录，ws 下平铺恒 0）。
        Path dir = SessionStorage.getProjectDir(ws);
        Files.createDirectories(dir);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = "sess-" + UUID.randomUUID().toString().substring(0, 8);
            Files.writeString(dir.resolve(id + ".jsonl"), "{}\n");
            ids.add(id);
        }
        return ids;
    }

    /** 写锁文件：body = pid，mtime 可指定（默认 now）。 */
    static void writeLock(Path mem, String pidBody, long mtimeMs) throws IOException {
        Files.createDirectories(mem);
        Path lock = mem.resolve(ConsolidationLock.LOCK_FILE);
        Files.writeString(lock, pidBody);
        Files.setLastModifiedTime(lock, FileTime.fromMillis(mtimeMs));
    }
}
