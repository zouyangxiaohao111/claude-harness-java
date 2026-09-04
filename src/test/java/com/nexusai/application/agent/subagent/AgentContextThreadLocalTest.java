package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.lsp.PromptCacheBreakDetection;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.ToolUseContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Session S7 · AgentContext (ThreadLocal 等价 AsyncLocalStorage) RED→GREEN 验证 ·
 * 对齐 CC utils/agentContext.ts:1-178.
 *
 * <p><b>WHY (CLAUDE.md 规则九 · 测试验证意图)</b>: CC 用 AsyncLocalStorage 隔离每条异步
 * 执行链的 agent 身份 (agentContext.ts:16-21), 避免并发 agent 的 analytics 归因串台
 * (agent A 的 event 归到 agent B)。Java 无等价物, S7 以 ThreadLocal 实现
 * getAgentContext / runWithAgentContext / consumeInvokingRequestId。
 */
@DisplayName("Session S7 · AgentContext ThreadLocal 等价 AsyncLocalStorage")
class AgentContextThreadLocalTest {

    @Test
    @DisplayName("runWithAgentContext 设置 context, getAgentContext 可读（CC agentContext.ts:108/100）")
    void runWithAgentContext_setsContext_readableByGetAgentContext() {
        // WHY: CC :108 runWithAgentContext 用 AsyncLocalStorage.run(context, fn) 隔离执行链,
        //   链内 getAgentContext() (:100) 必须能读到该 context.
        AgentContext.SubagentContext ctx =
            new AgentContext.SubagentContext("a-123", null, "Explore", true, "req-1", "spawn");

        AgentContext returned = AgentContext.runWithAgentContext(ctx, AgentContext::getAgentContext);

        assertThat(returned)
            .as("runWithAgentContext 块内 getAgentContext 必须返回该 ctx (CC :108-110)")
            .isSameAs(ctx);
    }

    @Test
    @DisplayName("runWithAgentContext 块外 getAgentContext 返回 null（CC agentContext.ts:101 undefined）")
    void getAgentContext_returnsNull_outsideRunBlock() {
        // WHY: CC :100-102 未在 agent context 内执行时返回 undefined; Java ThreadLocal 未 set 时返回 null.
        assertThat(AgentContext.getAgentContext())
            .as("无 context 时必须返回 null (不抛异常)")
            .isNull();
    }

    @Test
    @DisplayName("consumeInvokingRequestId 一次消费后清空（CC agentContext.ts:163-178 sparse edge）")
    void consumeInvokingRequestId_returnsOnce_thenClears() {
        // WHY: CC :159-161 sparse edge — invokingRequestId 只在每个 invocation 第一个 terminal
        //   API event 出现一次; 消费后 invocationEmitted=true (:173) 阻止重复返回.
        AgentContext.SubagentContext ctx =
            new AgentContext.SubagentContext("a-123", null, "Explore", true, "req-1", "spawn");

        AgentContext.runWithAgentContext(ctx, () -> {
            AgentContext.InvokingRequestEdge first = AgentContext.consumeInvokingRequestId();
            AgentContext.InvokingRequestEdge second = AgentContext.consumeInvokingRequestId();

            assertThat(first)
                .as("首次 consume 必须返回 edge (CC :174-177)")
                .isNotNull();
            assertThat(first.invokingRequestId()).isEqualTo("req-1");
            assertThat(first.invocationKind()).isEqualTo("spawn");
            assertThat(second)
                .as("二次 consume 必须 null (invocationEmitted=true 已消费)")
                .isNull();
        });
    }

    @Test
    @DisplayName("attachInvokingRequestEdge 消费并接入遥测事件属性（CC logging.ts:294/:461 + :320-327/:493-500）")
    void attachInvokingRequestEdge_consumesAndAttaches_toEventAttrs() {
        // WHY: D19/A3 接入遥测链路 —— CC consumeInvokingRequestId 的激活点在 terminal API event
        //   发射点（logging.ts:294 error / :461 success），edge 非 null 时把 invokingRequestId/
        //   invocationKind 展开进事件负载。Java 遥测链路事件属性构建点调用 attachInvokingRequestEdge
        //   即完成等价接入；sparse-edge（agentContext.ts:159-161）保证每个 invocation 仅一个事件携带。
        AgentContext.SubagentContext ctx =
            new AgentContext.SubagentContext("a-123", null, "Explore", true, "req-1", "resume");
        Map<String, Object> attrs = new HashMap<>();

        AgentContext.InvokingRequestEdge edge = AgentContext.runWithAgentContext(ctx,
            () -> AgentContext.attachInvokingRequestEdge(attrs));

        assertThat(edge)
            .as("首次 attach 必须消费并返回 edge（非 null = 当前事件携带稀疏边）")
            .isNotNull();
        assertThat(edge.invokingRequestId()).as("edge.requestId 透传 (CC :175)").isEqualTo("req-1");
        assertThat(edge.invocationKind()).as("edge.kind 透传 (CC :176)").isEqualTo("resume");
        assertThat(attrs)
            .as("事件 attrs 必须写入 invokingRequestId (CC logging.ts:322-325)")
            .containsEntry("invokingRequestId", "req-1");
        assertThat(attrs)
            .as("事件 attrs 必须写入 invocationKind (CC logging.ts:324-325)")
            .containsEntry("invocationKind", "resume");

        // sparse-edge: 同 invocation 二次 attach 必须 null 且不重复写属性（CC :170 invocationEmitted）
        AgentContext.runWithAgentContext(ctx, () -> {
            AgentContext.InvokingRequestEdge second = AgentContext.attachInvokingRequestEdge(attrs);
            assertThat(second)
                .as("sparse-edge: 二次 attach 必须 null（已消费）")
                .isNull();
        });
        assertThat(attrs)
            .as("已消费后不重复写/不覆盖 invokingRequestId")
            .containsEntry("invokingRequestId", "req-1");
    }

    @Test
    @DisplayName("attachInvokingRequestEdge 无 invokingRequestId → 不写遥测属性（CC :170 guard）")
    void attachInvokingRequestEdge_noInvokingRequestId_noAttach() {
        // WHY: CC :170 `if (!context?.invokingRequestId || context.invocationEmitted) return undefined` —
        //   main REPL subagent（无父 requestId）的 terminal event 不得写 invokingRequestId 键，
        //   否则 spawn/resume 边界标记失真。
        AgentContext.SubagentContext ctx =
            new AgentContext.SubagentContext("a-123", null, "Explore", true, null, null);
        Map<String, Object> attrs = new HashMap<>();

        AgentContext.InvokingRequestEdge edge = AgentContext.runWithAgentContext(ctx,
            () -> AgentContext.attachInvokingRequestEdge(attrs));

        assertThat(edge)
            .as("无 invokingRequestId 时 consume 返回 null (CC :170)")
            .isNull();
        assertThat(attrs).as("不写 invokingRequestId 键").doesNotContainKey("invokingRequestId");
        assertThat(attrs).as("不写 invocationKind 键").doesNotContainKey("invocationKind");
    }

    @Test
    @DisplayName("attachInvokingRequestEdge eventAttrs=null → 仅消费不写属性（无 NPE）")
    void attachInvokingRequestEdge_nullAttrs_stillConsumes() {
        // WHY: attach 把稀疏边写入事件属性的动作与消费动作解耦 —— 调用方尚未构建 attrs 时
        //   （或只关心消费副作用）不得 NPE；消费副作用（invocationEmitted=true）仍须发生，
        //   否则同 invocation 后续事件会误带边。
        AgentContext.SubagentContext ctx =
            new AgentContext.SubagentContext("a-123", null, "Explore", true, "req-1", "spawn");

        AgentContext.InvokingRequestEdge edge = AgentContext.runWithAgentContext(ctx,
            () -> AgentContext.attachInvokingRequestEdge(null));

        assertThat(edge)
            .as("eventAttrs=null 仍消费并返回 edge")
            .isNotNull();
        AgentContext.runWithAgentContext(ctx, () -> {
            AgentContext.InvokingRequestEdge second = AgentContext.attachInvokingRequestEdge(null);
            assertThat(second)
                .as("eventAttrs=null 消费副作用仍生效：二次必须 null")
                .isNull();
        });
    }

    @Test
    @DisplayName("getAgentContext 区分 subagent vs teammate（CC :38 agentType='subagent' vs :76 'teammate'）")
    void getAgentContext_distinguishesSubagentVsTeammate() {
        // WHY: CC discriminated union (:91) 需按 agentType 区分 subagent/teammate 两类归因.
        AgentContext.SubagentContext sub =
            new AgentContext.SubagentContext("a-1", null, "Explore", true, null, null);
        AgentContext.TeammateAgentContext team =
            new AgentContext.TeammateAgentContext("researcher@my-team", "researcher", "my-team",
                "blue", false, "sess-1", false, null, null);

        AgentContext.runWithAgentContext(sub, () -> {
            assertThat(AgentContext.getAgentContext())
                .as("subagent context 必须是 SubagentContext 实例")
                .isInstanceOf(AgentContext.SubagentContext.class);
            assertThat(AgentContext.isSubagentContext(AgentContext.getAgentContext()))
                .as("isSubagentContext 必须 true (CC :115-119)")
                .isTrue();
        });
        AgentContext.runWithAgentContext(team, () -> {
            assertThat(AgentContext.getAgentContext())
                .as("teammate context 必须是 TeammateAgentContext 实例")
                .isInstanceOf(AgentContext.TeammateAgentContext.class);
            assertThat(AgentContext.isSubagentContext(AgentContext.getAgentContext()))
                .as("teammate context isSubagentContext 必须 false")
                .isFalse();
        });
    }

    @Test
    @DisplayName("createAgentId 格式对齐 CC a+16hex（uuid.ts:24-27，D18/B2）")
    void createAgentId_alignsCC_aPlus16Hex() {
        // WHY: D18/B2 拍板 agentId 格式对齐 CC — CC createAgentId (uuid.ts:24-27)
        //   返回 'a'+16hex（非 UUID），影响 transcript 目录名 / analytics agent_id /
        //   resume 续写键。格式必须匹配 CC 校验正则 /^a(?:.+-)?[0-9a-f]{16}$/ (ids.ts:35)。
        String id = AgentContext.createAgentId();
        assertThat(id)
            .as("无标签 createAgentId 必须匹配 CC AgentId 正则 ^a(?:.+-)?[0-9a-f]{16}$")
            .matches("^a[0-9a-f]{16}$");
        assertThat(id)
            .as("agentId 长度 = 'a' + 16 hex = 17 (CC randomBytes(8).toString('hex'))")
            .hasSize(17);
        assertThat(id)
            .as("agentId 恒 'a' 前缀 (CC :26 a${suffix})")
            .startsWith("a");
        assertThat(id)
            .as("非 UUID 格式: 不含连字符分隔的 UUID 形态 (CC 16 hex 无 '-' 除 label)")
            .doesNotContain("-");

        String labeled = AgentContext.createAgentId("compact");
        assertThat(labeled)
            .as("带 label 格式 a{label}-{16hex} (CC :26 a${label}-${suffix}, e.g. acompact-a3f2c1b4d5e6f7a8)")
            .matches("^acompact-[0-9a-f]{16}$");

        String emptyLabel = AgentContext.createAgentId("");
        assertThat(emptyLabel)
            .as("空 label 为 JS falsy → 无标签格式 (CC :26 label ? ... : a${suffix})")
            .matches("^a[0-9a-f]{16}$");

        assertThat(AgentContext.createAgentId())
            .as("两次生成必须不同 (randomBytes 随机)")
            .isNotEqualTo(AgentContext.createAgentId());
    }

    @Test
    @DisplayName("buildForkAgentQueryEventAttrs 全字段映射 + cacheHitRate 派生（CC forkedAgent.ts:631-689）")
    void buildForkAgentQueryEventAttrs_mapsCCFields_withCacheHitRate() {
        // WHY: B10 拍板本期补 tengu_fork_agent_query 遥测 —— CC logForkAgentQueryEvent
        //   (forkedAgent.ts:656-688) 发射 forkLabel/querySource/durationMs/messageCount/
        //   四 token 字段 + cacheHitRate 派生（:651-654）+ queryTracking 条件展开（:681-687）。
        //   属性映射错则用量归因与 cache 命中率观测失真（探查 ✗-1 EV-WF4-FS-019 根因）。
        Map<String, Object> queryTracking = new HashMap<>();
        queryTracking.put("chainId", "chain-1");
        queryTracking.put("depth", 2);

        Map<String, Object> attrs = AgentContext.buildForkAgentQueryEventAttrs(
            "compact", "compact", 1500L, 3,
            100L, 200L, 900L, 50L, queryTracking, true);

        assertThat(attrs).as("forkLabel 透传 (CC :658-659)").containsEntry("forkLabel", "compact");
        assertThat(attrs).as("querySource 透传 canonical (CC :660-661)").containsEntry("querySource", "compact");
        assertThat(attrs).as("durationMs 透传 (CC :663)").containsEntry("durationMs", 1500L);
        assertThat(attrs).as("messageCount 透传 (CC :664)").containsEntry("messageCount", 3);
        assertThat(attrs).as("inputTokens (CC :667)").containsEntry("inputTokens", 100L);
        assertThat(attrs).as("outputTokens (CC :668)").containsEntry("outputTokens", 200L);
        assertThat(attrs).as("cacheReadInputTokens (CC :669)").containsEntry("cacheReadInputTokens", 900L);
        assertThat(attrs).as("cacheCreationInputTokens (CC :670)").containsEntry("cacheCreationInputTokens", 50L);
        Double cacheHitRate = (Double) attrs.get("cacheHitRate");
        assertThat(cacheHitRate)
            .as("anthropic=true → cacheHitRate = cache_read/(input+cache_read+cache_create) (CC :651-654)")
            .isCloseTo(0.857142857, within(1e-6));
        assertThat(attrs).as("queryChainId 附带 (CC :683-685)").containsEntry("queryChainId", "chain-1");
        assertThat(attrs).as("queryDepth 附带 (CC :686-687)").containsEntry("queryDepth", 2);
    }

    @Test
    @DisplayName("buildForkAgentQueryEventAttrs 非 anthropic（openai_sdk/deepseek）→ cacheHitRate = cache_read/input（input 已含 cache hit）")
    void buildForkAgentQueryEventAttrs_deepseek_cacheHitRateReadOverInput() {
        // WHY: deepseek（openai 协议）input_tokens 已含 cache hit（input==H+M）；旧恒三字段分母
        //   read/(input+read+create)=900/(1000+900+100)=0.45 恒为真实一半。修复后按 provider 分派：
        //   anthropic=false → read/input = 0.9。RED teeth：改回三字段分母 → 0.45 → 断言失败。
        Map<String, Object> attrs = AgentContext.buildForkAgentQueryEventAttrs(
            "extract_memories", "extract_memories", 900L, 4,
            1000L, 200L, 900L, 100L, null, false);

        Double cacheHitRate = (Double) attrs.get("cacheHitRate");
        assertThat(cacheHitRate)
            .as("anthropic=false → cache_read/input = 0.9（防 0.45 回归）")
            .isCloseTo(0.9, within(1e-9));
    }

    @Test
    @DisplayName("buildForkAgentQueryEventAttrs queryTracking null → 不附带 queryChainId/queryDepth（CC :681 条件展开）")
    void buildForkAgentQueryEventAttrs_nullQueryTracking_omitsChainFields() {
        // WHY: CC :681-687 `...(queryTracking ? {...} : {})` —— queryTracking 为 undefined 时
        //   不写 queryChainId/queryDepth；Java 端 null 等价 undefined，字段缺失而非 null 值。
        Map<String, Object> attrs = AgentContext.buildForkAgentQueryEventAttrs(
            "session_memory", "session_memory", 800L, 1,
            0L, 0L, 0L, 0L, null, true);

        assertThat(attrs).as("null queryTracking 不附带 queryChainId").doesNotContainKey("queryChainId");
        assertThat(attrs).as("null queryTracking 不附带 queryDepth").doesNotContainKey("queryDepth");
        assertThat(attrs).as("全零 usage → cacheHitRate=0 (CC :653-654)").containsEntry("cacheHitRate", 0.0);
    }

    @Test
    @DisplayName("emitForkAgentQueryEvent telemetry 注入 → recordEvent 计数（双发射 · HookRegistry:278-279 惯例）")
    void emitForkAgentQueryEvent_withTelemetry_recordsEvent() {
        // WHY: 发射器需真实把事件打到 telemetry（recordEvent 1P 计数 + logOTelEvent OTel 转发），
        //   与 AutoDreamConsolidator.emitTelemetry 双发射同型。telemetry=null 时静默跳过。
        Telemetry telemetry = new Telemetry();  // 手动构造，@PostConstruct init 不执行 → OTel noop

        AgentContext.emitForkAgentQueryEvent(
            telemetry, "auto_dream", "auto_dream", 1200L, 5,
            10L, 20L, 0L, 0L, null, false);

        assertThat(telemetry.getCounter("tengu_fork_agent_query"))
            .as("emitForkAgentQueryEvent 必须 recordEvent 计数 (CC forkedAgent.ts:656)")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("emitForkAgentQueryEvent telemetry=null → 静默跳过（测试/未接线零行为变化）")
    void emitForkAgentQueryEvent_nullTelemetry_noop() {
        // WHY: fork 路径在未装配 telemetry 时（单测/局部上下文）不能抛错或刷日志，
        //   对齐 AutoDreamConsolidator.emitTelemetry null 兜底（silent skip）。
        //   以计数不增验证 null 分支与发射分支互斥（无 NPE + 不额外发射）。
        Telemetry telemetry = new Telemetry();
        AgentContext.emitForkAgentQueryEvent(
            telemetry, "compact", "compact", 900L, 2,
            5L, 5L, 0L, 0L, null, false);
        assertThat(telemetry.getCounter("tengu_fork_agent_query")).isEqualTo(1);

        AgentContext.emitForkAgentQueryEvent(
            null, "compact", "compact", 900L, 2,
            5L, 5L, 0L, 0L, null, false);
        assertThat(telemetry.getCounter("tengu_fork_agent_query"))
            .as("telemetry=null 时发射为 no-op（不额外计数、不抛异常）")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("packAgentId/unpackAgentId 可逆编码桥往返（R3-WF-F IMP-SUB-12，CC forkedAgent.ts:448）")
    void packUnpackAgentId_roundTrips_aPlus16Hex() {
        // WHY: Java ToolUseContext.agentId 为 UUID 基础设施（S-12 桥），a+16hex 经 pack 桥确定可逆编码；
        //   生产生成点 createSubagentContext.create() 以 packAgentId(createAgentId()) 产出，
        //   transcript/resume 键经 unpackAgentId 还原。往返必须逐位一致，否则 resume 键错位。
        String hexId = AgentContext.createAgentId();
        UUID packed = AgentContext.packAgentId(hexId);
        assertThat(AgentContext.unpackAgentId(packed))
            .as("pack→unpack 往返必须还原原 a+16hex（CC uuid.ts:24-27 格式）")
            .isEqualTo(hexId);
        assertThat(AgentContext.packAgentId(hexId))
            .as("同 a+16hex 必须映射同 UUID（确定性：resume 双键查找依赖）")
            .isEqualTo(packed);
        assertThat(AgentContext.unpackAgentId(UUID.randomUUID()))
            .as("任意 UUID 均还原为合法 a+16hex 格式（^a[0-9a-f]{16}$，resume fallback 通道）")
            .matches("^a[0-9a-f]{16}$");
        assertThat(AgentContext.unpackAgentId(null))
            .as("null 输入必须返回 null（不抛异常）")
            .isNull();
    }

    @Test
    @DisplayName("createSubagentContext.create 生产生成点产出 a+16hex 可还原 agentId（CC forkedAgent.ts:448）")
    void createSubagentContext_create_generatesPackableAplus16HexAgentId() {
        // WHY: R3-WF-F 生产接线验收 —— 原实现 UUID.randomUUID() 零生产调用 createAgentId；对齐 CC
        //   forkedAgent.ts:448 overrides?.agentId ?? createAgentId() 后，无 override spawn 必须经
        //   packAgentId(createAgentId()) 产出 ToolUseContext.agentId，其 unpack 还原 a+16hex
        //   （transcript/metadata/resume 键格式，SC-068 数据契约闭合）。
        ToolUseContext ctx = createSubagentContext.create(null, null);

        assertThat(ctx.agentId())
            .as("spawn 生成点必须产出非 null agentId")
            .isNotNull();
        assertThat(AgentContext.unpackAgentId(ctx.agentId()))
            .as("unpack 还原必须匹配 CC AgentId 正则 ^a[0-9a-f]{16}$（a+16hex 生产接线）")
            .matches("^a[0-9a-f]{16}$");
    }

    @Test
    @DisplayName("terminal 事件在 context 活跃时首个携带 edge、二次事件不带（sparse-edge · REWORK-1 生产路径）")
    void terminalEvent_insideContext_firstCarriesEdge_secondSparse() {
        // WHY: REWORK-1 假接线根因 —— 原 emitSubagentApiTerminalEvent 在 runWithAgentContext 块外
        //   （Step 22，finally restore/remove ThreadLocal 之后）调用 attachInvokingRequestEdge，
        //   consumeInvokingRequestId 读到 STORAGE=null（顶层）或外层 subagent 的 edge（嵌套）→
        //   子代理自身 invokingRequestId 永不消费。修复后发射点在作用域内（loop 返回后、context
        //   退出前）：context 活跃时首个 terminal 事件必须带 edge（attrs 写入 invokingRequestId/
        //   invocationKind），二次事件（同 invocation）必须不带（sparse-edge，agentContext.ts:170）。
        AgentContext.SubagentContext ctx =
            new AgentContext.SubagentContext("a-123", null, "Explore", true, "req-1", "spawn");
        Map<String, Object> firstAttrs = new HashMap<>();
        Map<String, Object> secondAttrs = new HashMap<>();

        AgentContext.runWithAgentContext(ctx, () -> {
            // 首个 terminal 事件（context 活跃）：edge 非 null + attrs 展开
            AgentContext.InvokingRequestEdge first = AgentContext.attachInvokingRequestEdge(firstAttrs);
            assertThat(first)
                .as("context 活跃时首个 terminal 事件必须携带 edge（CC logging.ts:461/:294 consume 点）")
                .isNotNull();
            assertThat(firstAttrs)
                .as("首个事件 attrs 必须 spread invokingRequestId（CC logging.ts:493-500）")
                .containsEntry("invokingRequestId", "req-1");
            assertThat(firstAttrs)
                .as("首个事件 attrs 必须 spread invocationKind（CC logging.ts:495-498）")
                .containsEntry("invocationKind", "spawn");

            // 二次 terminal 事件（同 invocation）：sparse-edge，edge 已消费 → null 且不写属性
            AgentContext.InvokingRequestEdge second = AgentContext.attachInvokingRequestEdge(secondAttrs);
            assertThat(second)
                .as("二次 terminal 事件必须 null（invocationEmitted=true，CC :170 guard）")
                .isNull();
            assertThat(secondAttrs)
                .as("二次事件不得重复写 invokingRequestId（sparse-edge）")
                .doesNotContainKey("invokingRequestId");
        });
    }

    @Test
    @DisplayName("terminal 事件在 context 作用域外发射 edge 恒 null（REWORK-1 假接线回归守卫）")
    void terminalEvent_outsideContext_edgeAlwaysNull() {
        // WHY: 该测试锁定修复的必要性 —— 若 emitSubagentApiTerminalEvent 被移回 runWithAgentContext
        //   作用域外（原 Step 22 位置），consumeInvokingRequestId 读 STORAGE=null → edge 恒 null，
        //   子代理 invokingRequestId 永不进入 tengu_api_success/error。作用域外调用必须返回 null
        //   才符合 CC agentContext.ts:101（无 context → undefined）。
        AgentContext.SubagentContext ctx =
            new AgentContext.SubagentContext("a-123", null, "Explore", true, "req-1", "spawn");
        // 先让 context 进入作用域一次以建立 invokingRequestId（等价生产：query loop 在作用域内跑）
        AgentContext.runWithAgentContext(ctx, () -> { });

        Map<String, Object> attrs = new HashMap<>();
        AgentContext.InvokingRequestEdge outside = AgentContext.attachInvokingRequestEdge(attrs);

        assertThat(outside)
            .as("runWithAgentContext 作用域外 attach 必须 null（CC :101 无 context → undefined）")
            .isNull();
        assertThat(attrs)
            .as("作用域外不得写 invokingRequestId 键（edge 恒 null → 事件不携带边界）")
            .doesNotContainKey("invokingRequestId");
    }

    @Test
    @DisplayName("cleanup/kill 四键必须回传 packed UUID 字符串而非 a+16hex（REWORK-1 写入侧对齐）")
    void cleanupKeys_mustBePackedUuidString_notAPlus16Hex() {
        // WHY: REWORK-1 回归 —— 生产把 cleanupAgentTracking/cleanupAgentTodos/killShellTasksForAgent/
        //   killMonitorMcpTasksForAgent 的键改成 a+16hex（agentIdHex），而写入/解析侧仍 UUID：
        //   TodoWriteTool:832 todoKey = ctx.agentId().toString()（packed UUID 字符串）；
        //   BackgroundTaskRunner.killShellTasksForAgent(UUID) 以 UUID 匹配 task.agentId()；
        //   PromptCacheBreakDetection.getTrackingKey 用 agentId 原值。a+16hex 传入 → containsKey
        //   恒 false / safeParseUuid 抛 IllegalArgumentException → 静默 no-op/泄漏。
        //   本测试锁定：packed UUID 字符串与 a+16hex 是不同键，cleanup 必须用前者。
        ToolUseContext ctx = createSubagentContext.create(null, null);
        String packedUuidStr = ctx.agentId().toString();
        String aPlus16Hex = AgentContext.unpackAgentId(ctx.agentId());

        assertThat(packedUuidStr)
            .as("写入侧键 = ctx.agentId().toString()（TodoWriteTool:832 同源）必须是合法 UUID 字符串")
            .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(aPlus16Hex)
            .as("a+16hex 是 transcript/resume 可观察格式（SC-068），但非 UUID 桶键")
            .matches("^a[0-9a-f]{16}$");
        assertThat(packedUuidStr)
            .as("packed UUID 字符串 ≠ a+16hex —— cleanup 传 a+16hex 必 miss 写入侧桶（REWORK-1 回归根因）")
            .isNotEqualTo(aPlus16Hex);
        assertThat(AgentContext.packAgentId(aPlus16Hex).toString())
            .as("packAgentId(a+16hex).toString() 必须还原为写入侧 packed UUID 字符串（双向确定）")
            .isEqualTo(packedUuidStr);
        // safeParseUuid 语义（killShellTasksForAgent/killMonitorMcpTasksForAgent 委托路径）：
        //   packed UUID 字符串可被 UUID.fromString 解析（命中 task.agentId()=packed UUID），
        //   a+16hex 抛 IllegalArgumentException → SubagentExecutor.safeParseUuid 返 null → no-op。
        assertThat(UUID.fromString(packedUuidStr))
            .as("killShell/killMonitor safeParseUuid(packed UUID 字符串) 必须解析成功（CC runAgent.ts:851-861 命中）")
            .isEqualTo(ctx.agentId());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> UUID.fromString(aPlus16Hex))
            .as("safeParseUuid(a+16hex) 必须抛 IllegalArgumentException（原返工回归根因）")
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cleanupAgentTracking 四键之一：packed UUID 字符串命中实际 tracking 桶，a+16hex 恒 miss（REWORK-1）")
    void cleanupAgentTracking_packedUuidHitsBucket_aPlus16HexMisses() {
        // WHY: REWORK-1 回归 —— PromptCacheBreakDetection.getTrackingKey(:152) 在 tracked source 下
        //   用 agentId 原值作 PREVIOUS 桶键；生产写入侧（ToolUseContext.agentId=packed UUID）记录的
        //   桶键是 packed UUID 字符串。cleanupAgentTracking 若传 a+16hex（原返工实现）→
        //   PREVIOUS.remove(a+16hex) 恒 miss → tracking 泄漏；必须传 packed UUID 字符串才能命中实际桶。
        //   feature 门控默认关（PROMPT_CACHE_BREAK_DETECTION），但键格式契约与本测试锁定的是
        //   开启后的命中语义（对齐 SubagentExecutorCacheBreakCleanupTest 同款 record→cleanup 断言）。
        //   PREVIOUS 为类级静态 Map（CC previousStateBySource 模块级），跨测试共享 → 每测前清空。
        new PromptCacheBreakDetection(r -> {}).resetPromptCacheBreakDetection();
        PromptCacheBreakDetection detector = new PromptCacheBreakDetection(events -> {}); // enabled=true
        ToolUseContext ctx = createSubagentContext.create(null, null);
        String packedUuidStr = ctx.agentId().toString();
        String aPlus16Hex = AgentContext.unpackAgentId(ctx.agentId());
        // record: 与生产写入侧同键（querySource tracked + agentId=packed UUID 字符串）
        detector.recordPromptState(new PromptCacheBreakDetection.PromptStateSnapshot(
            List.of(Map.of("type", "text", "text", "sys")),
            List.of(Map.of("name", "toolA")),
            "agent:default", "claude-sonnet-4-6", packedUuidStr,
            false, "", List.of(), false, false, false, null, null));
        assertThat(detector.getTrackedSourceCount()).as("record 后 PREVIOUS 有 1 条").isEqualTo(1);

        // 错误格式 a+16hex → miss（原返工回归）
        detector.cleanupAgentTracking(aPlus16Hex);
        assertThat(detector.getTrackedSourceCount())
            .as("cleanup(a+16hex) 必须 miss（键 ≠ 写入侧 packed UUID 字符串）→ 仍 1 条")
            .isEqualTo(1);

        // 正确格式 packed UUID 字符串 → hit
        detector.cleanupAgentTracking(packedUuidStr);
        assertThat(detector.getTrackedSourceCount())
            .as("cleanup(packed UUID 字符串) 必须命中实际桶 → 0 条（CC promptCacheBreakDetection.ts:700-702 delete）")
            .isZero();
    }
}
