package com.nexusai.application.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.RunRequest;
import com.nexusai.application.agent.TestContexts;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [IMP-CM-03] SM 阈值 Web 调参通道（消费侧）· OPD-CM3-14 / DEC-CM3-C01。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>: CC 从 GrowthBook 远端配置通道
 * （tengu_sm_config / tengu_sm_compact_config）灰度调 SM 阈值，Java 无远端通道故恒
 * DEFAULT（全局报告 B D1：setSmCompactConfig/setSessionMemoryConfig 零生产调用方）。
 * IMP-CM-35 建 Web 调参通道（REST 端点 + SessionMemoryConfigChannel 存储）写运行期值；
 * 本任务（IMP-CM-03，消费侧）验证<b>消费点改从调参通道读动态值</b>——通道写入
 * （setSmCompactConfig / setSessionMemoryConfig）后，SM 消费点
 * （SessionMemoryService.calculateMessagesToKeepIndex / SessionMemoryUtils 阈值谓词）
 * 运行期立即读新值（不重启进程），未配置时回退 CC DEFAULT（与改造前恒 DEFAULT 一致）。
 *
 * <p>依赖 IMP-CM-35（先建端点再改消费）：消费动态读值用例经通道写路径
 * （setSmCompactConfig / setSessionMemoryConfig）验证；正值门控用例经
 * {@link SessionMemoryConfigChannel#updateSessionMemoryConfig} 读入点验证（[IMP-MV2-33]
 * 过滤层位置修复后，过滤在该读入点、setter 为 CC 纯 merge）。
 */
@DisplayName("[IMP-CM-03] SM 阈值调参消费侧：运行期读新值（不重启） + 未配置回退 DEFAULT")
class SessionMemoryTuningConsumptionTest {

    @TempDir
    Path baseDir;

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void tearDown() {
        SessionMemoryUtils.resetSessionMemoryState();
        // IMP-MV2-11：复位 AutoMemPaths override env 测试缝（防跨测试污染）
        AutoMemPaths.setOverrideEnvForTest(null);
    }

    // ── 辅助：构造 ChatMessageDto（17-参兼容构造器，角色 user）──
    private static ChatMessageDto msg(String content) {
        return new ChatMessageDto(UUID.randomUUID().toString(), null, Role.user, "user", content, null,
            List.of(), FinishReason.stop, null, null, "刚刚", OffsetDateTime.now(),
            null, null, null, List.of(), List.of());
    }

    @Test
    @DisplayName("未配置 → 消费点回退 CC DEFAULT（压缩 10000/5/40000 + 提取 10000/5000/3）")
    void unconfigured_consumptionFallsBackToCcDefault() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        List<ChatMessageDto> messages = List.of(
            msg("user1"), msg("user2"), msg("user3"), msg("user4"), msg("user5"), msg("user6"));

        // 压缩消费点：DEFAULT minTokens=10000 远超 6 条短消息（约几十 token）→ 双最低永不达
        // → 展开至 floor=0 → 返回 0（保留全部）——未配置与改造前恒 DEFAULT 行为一致。
        assertThat(svc.calculateMessagesToKeepIndex(messages, 0))
            .as("DEFAULT minTokens=10000：短消息远达不到 → 保留全部（返回 0）")
            .isEqualTo(0);

        // 提取消费点：DEFAULT 阈值
        assertThat(SessionMemoryUtils.hasMetInitializationThreshold(12_000)).isTrue();
        assertThat(SessionMemoryUtils.getToolCallsBetweenUpdates()).isEqualTo(3);
    }

    @Test
    @DisplayName("setSmCompactConfig（调参通道写路径）→ calculateMessagesToKeepIndex 运行期读新值")
    void smCompactTuning_isConsumedByCalculateMessagesToKeepIndex() {
        SessionMemoryService svc = new SessionMemoryService(baseDir);
        List<ChatMessageDto> messages = List.of(
            msg("user1"), msg("user2"), msg("user3"), msg("user4"), msg("user5"), msg("user6"));

        // 基线：DEFAULT minTokens=10000 → 短消息达不到 → 保留全部（返回 0）
        assertThat(svc.calculateMessagesToKeepIndex(messages, 0)).isEqualTo(0);

        // 运行期经调参通道写路径把 minTokens 降到 1 → lastSummarizedIndex=0 之后首条
        // （索引 1）即达双最低 → 保留起点从 0 移到 1（调参生效，无需重启进程）。
        svc.setSmCompactConfig(new SessionMemoryService.SmCompactConfig(1, 1, 100_000));
        assertThat(svc.calculateMessagesToKeepIndex(messages, 0))
            .as("通道新 minTokens=1 运行期生效：保留起点 0 → 1（少保留一条已摘要消息）")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("setSessionMemoryConfig（调参通道写路径）→ SessionMemoryUtils 阈值谓词运行期读新值")
    void sessionMemoryTuning_isConsumedByThresholdPredicates() {
        // DEFAULT minimumTokensBetweenUpdate=5000 → 增长 4000 未达更新阈值
        // [sm-cursor-sessionize] 阈值谓词会话化（null → "unknown" 键，纯阈值判定用）
        SessionMemoryUtils.recordExtractionTokenCount(null, 8_000);
        assertThat(SessionMemoryUtils.hasMetUpdateThreshold(null, 12_000)).isFalse();

        // 运行期经调参通道写路径把 minimumTokensBetweenUpdate 降到 3000 → 增长 4000 已达
        SessionMemoryUtils.setSessionMemoryConfig(
            new SessionMemoryUtils.SessionMemoryConfig(10_000, 3_000, 3));
        assertThat(SessionMemoryUtils.hasMetUpdateThreshold(null, 12_000))
            .as("通道新 minimumTokensBetweenUpdate=3000 运行期生效：增长 4000 ≥ 3000 → 已达更新阈值")
            .isTrue();

        // 工具调用阈值同步走通道
        SessionMemoryUtils.setSessionMemoryConfig(
            new SessionMemoryUtils.SessionMemoryConfig(10_000, 3_000, 7));
        assertThat(SessionMemoryUtils.getToolCallsBetweenUpdates())
            .as("通道新 toolCallsBetweenUpdates=7 运行期生效")
            .isEqualTo(7);
    }

    @Test
    @DisplayName("通道读入点正值门控：0/负值不覆盖当前值（对齐 CC initSessionMemoryConfigIfNeeded）")
    void sessionMemoryConfigPositiveGate_zeroNegativeDoNotOverride() {
        // [IMP-MV2-33] 过滤层位置修复：过滤自 setSessionMemoryConfig 内嵌位移入
        // ConfigChannel 读入点（CC sessionMemory.ts:246-262 init 层 ↔ utils.ts:131-138 纯 merge）
        SessionMemoryConfigChannel channel = new SessionMemoryConfigChannel();

        // 基线 DEFAULT(10000/5000/3)：显式正值覆盖，负值保留当前值
        SessionMemoryUtils.SessionMemoryConfig merged = channel.updateSessionMemoryConfig(
            new SessionMemoryUtils.SessionMemoryConfig(20_000, -1, -1));
        assertThat(merged.minimumMessageTokensToInit())
            .as("minimumMessageTokensToInit=20000 显式正值 → 覆盖")
            .isEqualTo(20_000);
        assertThat(merged.minimumTokensBetweenUpdate())
            .as("minimumTokensBetweenUpdate=-1 负值 → 不覆盖，保留当前 5000")
            .isEqualTo(5_000);
        assertThat(merged.toolCallsBetweenUpdates())
            .as("toolCallsBetweenUpdates=-1 负值 → 不覆盖，保留当前 3")
            .isEqualTo(3);

        // 0 同样不覆盖（CC sessionMemory.ts:246-262 仅正值）；返回值为过滤后完整配置
        merged = channel.updateSessionMemoryConfig(
            new SessionMemoryUtils.SessionMemoryConfig(0, 0, 0));
        assertThat(merged.minimumMessageTokensToInit())
            .as("0 不覆盖 → 保留 20000")
            .isEqualTo(20_000);
        assertThat(merged.minimumTokensBetweenUpdate())
            .as("0 不覆盖 → 保留 5000")
            .isEqualTo(5_000);
        assertThat(merged.toolCallsBetweenUpdates())
            .as("0 不覆盖 → 保留 3")
            .isEqualTo(3);

        // null partial → 保持当前（等价旧 setter null 忽略）
        assertThat(channel.updateSessionMemoryConfig(null).minimumMessageTokensToInit())
            .as("null partial → 保持当前 20000")
            .isEqualTo(20_000);
    }

    @Test
    @DisplayName("setSessionMemoryConfig 纯 merge（CC utils.ts:131-138）：入参全字段覆盖，无内嵌过滤")
    void setSessionMemoryConfig_pureMergeMatchesCc() {
        // [IMP-MV2-33] 过滤已上移通道读入点：setter 对齐 CC 纯 merge——0/负值同样写入
        SessionMemoryUtils.setSessionMemoryConfig(
            new SessionMemoryUtils.SessionMemoryConfig(20_000, -1, -1));
        SessionMemoryUtils.SessionMemoryConfig cfg = SessionMemoryUtils.getSessionMemoryConfig();
        assertThat(cfg.minimumMessageTokensToInit())
            .as("纯 merge：20000 覆盖")
            .isEqualTo(20_000);
        assertThat(cfg.minimumTokensBetweenUpdate())
            .as("纯 merge：-1 覆盖（无过滤）")
            .isEqualTo(-1);
        assertThat(cfg.toolCallsBetweenUpdates())
            .as("纯 merge：-1 覆盖（无过滤）")
            .isEqualTo(-1);

        SessionMemoryUtils.setSessionMemoryConfig(
            new SessionMemoryUtils.SessionMemoryConfig(0, 0, 0));
        cfg = SessionMemoryUtils.getSessionMemoryConfig();
        assertThat(cfg.minimumMessageTokensToInit())
            .as("纯 merge：0 覆盖")
            .isEqualTo(0);
        assertThat(cfg.minimumTokensBetweenUpdate())
            .as("纯 merge：0 覆盖")
            .isEqualTo(0);
        assertThat(cfg.toolCallsBetweenUpdates())
            .as("纯 merge：0 覆盖")
            .isEqualTo(0);
    }

    @Test
    @DisplayName("IMP-MV2-11: custom+override 场景 tengu_memdir_loaded 每 turn 仅发射一次（do-while 外计算一次 · CC QueryEngine.ts:316-319）")
    void memoryMechanicsPrompt_telemetryEmittedOncePerTurn(@TempDir Path overrideDir) throws Exception {
        // WHY（E2 △-1 / OPD-MM-34「do-while 外一次」）：旧实现 memoryMechanicsPrompt 在 do-while 内
        //   每迭代重算 —— custom+override 场景 2 次迭代（工具轮 + 收尾轮）会 2 次执行 loadMemoryPrompt
        //   → 2 次 ensureMemoryDirExists（幂等 IO）+ 2 次 tengu_memdir_loaded 异步发射（遥测计数失真）。
        //   修复后 do-while 外计算一次（对齐 CC QueryEngine.ts:316-319 组装在 while 前一次）→ 恰好 1 次。
        //   行为文本不变由 s10 组装链断言（EffectiveSystemPromptBuilderTest G-11 层序）承接，本用例
        //   只锁频率。JVM 无法改 System.getenv → AutoMemPaths override env 测试缝（同库
        //   MemoryBareModeConfig.setEnvOverride 惯例）；default 路径 memory section 无 memoryStorage
        //   （本 harness 未注入）→ 本用例唯一发射通道 = memoryMechanicsPrompt 路径。
        Files.createDirectories(overrideDir);
        AutoMemPaths.setOverrideEnvForTest(overrideDir.toString());
        try {
            ToolRegistry registry = new ToolRegistry();
            registry.register(TestContexts.dummyTool("Bash"));
            LlmProviderFactory factory = mock(LlmProviderFactory.class);
            LlmProvider mainProvider = mock(LlmProvider.class);
            List<List<ChatMessageDto>> histories = new ArrayList<>();
            final boolean[] firstRound = {true};
            doAnswer(inv -> {
                histories.add(new ArrayList<>((List<ChatMessageDto>) inv.getArgument(3)));
                Consumer<AssistantMessage> onMsg = inv.getArgument(10);
                Runnable onComplete = inv.getArgument(16);
                if (firstRound[0]) {
                    firstRound[0] = false;
                    // 第 1 轮：tool_calls → 工具轮 → do-while 第二次迭代
                    ObjectNode input = JSON.createObjectNode().put("command", "ls");
                    onMsg.accept(new AssistantMessage("Let me check", "tool_calls",
                        List.of(new ToolUseBlock("toolu_mv2_11_1", "Bash", input))));
                } else {
                    // 第 2 轮：纯文本 stop → needsFollowUp=false → 退出
                    onMsg.accept(new AssistantMessage("Done", "stop", List.of()));
                }
                onComplete.run();
                return null;
            }).when(mainProvider).stream(any(), anyString(), anyList(), anyList(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            when(factory.getProvider(any(), any())).thenReturn(mainProvider);

            LlmAgentLoop loop = new LlmAgentLoop(factory, null, registry);
            Telemetry tel = mock(Telemetry.class);
            loop.setTelemetry(tel);

            // custom systemPrompt（非 null）+ override env → memoryMechanicsPrompt 门控开（CC :316-317）
            AgentState state = loop.run(RunRequest.session("configure the system now",
                "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(), ProviderConfig.empty(), "test-model",
                "CUSTOM SYSTEM PROMPT", null));

            assertThat(histories).as("必须 2 轮 LLM 调用（多迭代是频率断言的前提；旧实现 2 次发射）").hasSize(2);
            awaitSingleMemdirLoaded(tel);
        } finally {
            AutoMemPaths.setOverrideEnvForTest(null);
        }
    }

    /**
     * 轮询等待 tengu_memdir_loaded 异步发射落地（fire-and-forget 线程池），并断言全程恰好 1 次。
     *
     * <p>防竞态：首次 verify(times(1)) 通过后进入 500ms 静默期再复核 —— 旧实现 2 次发射场景下
     * 第二次异步发射可能在首查通过后才落地（fire-and-forget），静默期复核使 2 次发射必在
     * deadline 内暴露（RED）；修复后 1 次发射经静默期复核稳定通过（GREEN）。
     */
    private static void awaitSingleMemdirLoaded(Telemetry tel) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try {
                verify(tel, times(1)).recordEvent(eq("tengu_memdir_loaded"), any());
                // 静默期：异步发射池可能在首查通过后才落地（fire-and-forget）——
                // 旧实现 2 次发射场景下第二次发射在此窗口出现 → 复核失败 → 循环至 deadline 抛出（RED）。
                Thread.sleep(500);
                verify(tel, times(1)).recordEvent(eq("tengu_memdir_loaded"), any());
                return;
            } catch (AssertionError e) {
                Thread.sleep(50);
            }
        }
        throw new AssertionError("5s 内 tengu_memdir_loaded 未落地或发射次数 ≠ 1（期望每 turn 恰好 1 次）");
    }
}
