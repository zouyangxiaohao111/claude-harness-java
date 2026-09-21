package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.compact.PostCompactAttachmentRestorer;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.McpClientRuntime;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.toolsearch.ToolSearchService;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>A1 · mcp_instructions_delta 每轮尾部投递</b>意图测试（对齐 CC 2.1.88 形态
 * {@code getAttachments} 的 {@code maybe('mcp_instructions_delta', ...)}，
 * Open-ClaudeCode/src/utils/attachments.ts:854-863 + 产物消费链 query.ts:1580-1588；
 * 渲染 case CC utils/messages.ts:4216-4231）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 验证意图而非行为）</b>：C4=A2 把 {@code mcp_instructions} 改成
 * 会话冻结段，代价是「MCP 服务器中途连接/断开不再即时可见」。A1 的对齐点<b>不是</b>「有一条消息被
 * 追加」，而是下面三对取舍<b>两侧同时对</b> —— 任一侧反了都会让本通道变成「看似接了、实际不生效」
 * 或「把前缀缓存修复白修」：
 * <ol>
 *   <li><b>内容未变 ⇒ 一条都不追加</b>：判据来自 producer 的跨轮 diff（
 *       {@code scanAnnouncedDeltaNames} 扫历史里的 delta JSON），而不是「每轮重新公告一遍」——
 *       后者会让上下文随轮次线性膨胀（每条 server 指令都会被反复重发）。</li>
 *   <li><b>内容变了 ⇒ <u>尾部</u>追加一条 delta，且 <u>头部字节不变</u></b>：这两个断言必须在
 *       同一份断言里同时成立 —— 只断言「追加了」会放过「往前插队」的实现（插队 = 重新生成整段
 *       前缀 ⇒ 前缀缓存修复被这一下抹平）。</li>
 *   <li><b>首轮无需投递 ⇒ 零追加</b>：没有带 instructions 的 MCP server 时 producer 恒 null，
 *       通道必须零字节（否则每个会话开头都白塞一条空壳）。</li>
 * </ol>
 * 另加两条本通道独有的不变量：<b>断连也必须被公告</b>（{@code removedNames} —— A2 分歧的另一半，
 * 只做「新增」等于只关了一半）、<b>发送边界把人可读文案渲染出来</b>（落库的是 diff 扫描用的 JSON，
 * 模型不该看到原始 JSON —— 对齐 CC「结构化附件持久化 + 发送时渲染」）。
 *
 * <p><b>门（2026-09-21 去门 · 对齐 2.1.278）</b>：本通道<b>无任何 off 开关</b> —— 2.1.88 的
 * {@code isMcpInstructionsDeltaEnabled} 已在 2.1.278 发行产物中整体删除（见
 * {@code PostCompactAttachmentRestorer.mcpInstructionsDeltaAttachment} javadoc）。不变量 ⑤ 因此
 * 从「门关 ⇒ 零追加」改写为「旧门阀值不再拦截 ⇒ 无条件公告」。
 *
 * <p>纯单测（⛔ 无 Spring / 无 @SpringBootTest）：env 走 {@link ToolSearchService#envOverride} 注入，
 * 历史用真 {@link AgentState}，MCP 连接用真 {@code McpClientRuntime}。
 */
@DisplayName("A1 · mcp_instructions_delta 每轮尾部投递（头部冻结 + 尾部告知增删）")
class McpInstructionsDeltaTailDeliveryTest {

    /**
     * 2.1.88 的旧门 env（{@code isMcpInstructionsDeltaEnabled}，mcpInstructionsDelta.ts:38-39）。
     * <b>该门已按 2.1.278 删除</b> ⇒ 本常量现在只用于「旧阀值必须不再有任何拦截效果」的反证。
     */
    private static final String LEGACY_GATE_ENV = "CLAUDE_CODE_MCP_INSTR_DELTA";

    @AfterEach
    void resetEnvSeam() {
        // [R9(b) env seam 归一] delta 门统一读 ToolSearchService.currentEnv() → 只复位一个 seam
        ToolSearchService.envOverride = null;
    }

    // ═══════════════════════ 夹具 ═══════════════════════

    private static String newSessionId() {
        return "sess-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 生产同款 per-turn TUC 最小形态 + 指定 MCP 连接集。 */
    private static ToolUseContext tuc(String sessionId, McpClientRuntime... servers) {
        Map<String, McpClientRuntime> clients = new LinkedHashMap<>();
        for (McpClientRuntime s : servers) {
            clients.put(s.serverName(), s);
        }
        return ToolUseContext.of(
                null, sessionId, PermissionMode.DEFAULT,
                List.of(), "", AbortController.NOOP, List.of(),
                null, PermissionMode.DEFAULT, clients, false, "")
            .withEffectiveProviderType("anthropic");
    }

    /** 造一条「用户消息」当作 messages[0] 的近似（用于验证头部未被回写）。 */
    private static ChatMessageDto userMsg(String sessionId, String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), sessionId, Role.user, "user",
            content, null, List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, false);
    }

    private static McpClientRuntime server(String name, String instructions) {
        return new McpClientRuntime(name, "mcp__" + name + "__tool", instructions);
    }

    /** 历史里所有 mcp_instructions_delta 消息（= 本通道的投递产物）。 */
    private static List<ChatMessageDto> deltas(AgentState state) {
        List<ChatMessageDto> out = new ArrayList<>();
        for (ChatMessageDto m : state.rawMessages()) {
            if (PostCompactAttachmentRestorer.DELTA_TYPE_MCP_INSTRUCTIONS.equals(m.subtype())) {
                out.add(m);
            }
        }
        return out;
    }

    // ════════ 不变量 ① · 内容未变 ⇒ 一条都不追加（对齐 producer 跨轮 name-diff）════════

    @Test
    @DisplayName("同一连接连续两轮 ⇒ 第二轮零追加（producer 扫到已公告集合；⛔ 不是每轮重公告）")
    void unchanged_noAppendOnSecondTurn() {
        String sessionId = newSessionId();
        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMsg(sessionId, "请查一下文档"));
        ToolUseContext tuc = tuc(sessionId, server("docs-server", "先查目录再读文件"));

        // 第 1 轮：全新连接 ⇒ 公告一次
        AgentLoopContext.maybeEmitMcpInstructionsDelta(state, tuc, "claude-sonnet-4-5");
        int afterFirst = state.rawMessages().size();
        assertThat(deltas(state)).as("首轮必须公告（否则本通道对新增 server 完全不生效）").hasSize(1);

        // 第 2/3 轮：连接集合与指令都没变 ⇒ 一条都不追加
        AgentLoopContext.maybeEmitMcpInstructionsDelta(state, tuc, "claude-sonnet-4-5");
        AgentLoopContext.maybeEmitMcpInstructionsDelta(state, tuc, "claude-sonnet-4-5");

        assertThat(state.rawMessages())
            .as("内容未变 ⇒ 零追加（producer 跨轮 diff 命中已公告集合；⛔ 每轮重公告会让上下文线性膨胀）")
            .hasSize(afterFirst);
        assertThat(deltas(state)).hasSize(1);
    }

    // ════════ 不变量 ② · 内容变了 ⇒ 尾部追加一条，且头部字节不变（同一份断言）════════

    @Test
    @DisplayName("新增一台 server ⇒ 尾部追加一条 delta（只含新增者），messages[0] 逐字不变")
    void newServer_appendsTailDelta_headUntouched() {
        String sessionId = newSessionId();
        AgentState state = new AgentState("sys", sessionId, null);
        ChatMessageDto head = userMsg(sessionId, "昨天的请求");
        state.appendMessage(head);

        // 第 1 轮：docs-server 已在
        AgentLoopContext.maybeEmitMcpInstructionsDelta(
            state, tuc(sessionId, server("docs-server", "先查目录再读文件")), "claude-sonnet-4-5");
        int before = state.rawMessages().size();

        // 第 2 轮：会话**中途**多了 im-server（A2 分歧的场景本体）
        AgentLoopContext.maybeEmitMcpInstructionsDelta(
            state,
            tuc(sessionId, server("docs-server", "先查目录再读文件"), server("im-server", "发消息用 im_send")),
            "claude-sonnet-4-5");

        List<ChatMessageDto> after = state.rawMessages();
        // ⭐ 两条断言必须在同一份断言里同时成立：只查「追加了」会放过往前插队的实现
        assertThat(after).as("中途新增 ⇒ 必须追加一条（这正是 A2 遗留分歧要关的那一半）").hasSize(before + 1);
        assertThat(after.get(0))
            .as("⛔ 头部（messages[0]）不得被回写 —— 回写即重新生成整段前缀，前缀缓存修复被抹平")
            .isSameAs(head);
        assertThat(after.get(0).content()).as("头部字节逐字不变").isEqualTo("昨天的请求");

        ChatMessageDto tail = after.get(after.size() - 1);
        assertThat(tail).as("必须在**尾部**（不插队、不改写前缀）").isNotSameAs(head);
        assertThat(tail.role()).isEqualTo(Role.user);
        assertThat(tail.isMeta()).as("isMeta ⇒ 前端隐藏、不污染用户转录（CC createUserMessage isMeta:true）").isTrue();
        assertThat(tail.author()).as("attachment 通道产出（与 CC AttachmentMessage 同契约）").isEqualTo("attachment");
        assertThat(tail.subtype()).as("subtype 与 CC attachment.type 同名").isEqualTo("mcp_instructions_delta");
        assertThat(tail.sessionId())
            .as("落库必需（messages.session_id NOT NULL）：producer 的 envelope sessionId=null，本通道必须补")
            .isEqualTo(sessionId);
        // 内容 = producer 的原样 JSON payload（跨轮 diff 的扫描源；渲染在发送边界做）
        assertThat(tail.content())
            .as("payload 只含**增量**（新增的 im-server），⛔ 不得把已公告的 docs-server 再公告一遍")
            .contains("\"addedNames\":[\"im-server\"]")
            .contains("## im-server")
            .doesNotContain("## docs-server");
        assertThat(tail.content()).contains("removedNames");
    }

    // ════════ 不变量 ③ · 断连也必须被公告（A2 分歧的另一半：断开）════════

    @Test
    @DisplayName("会话中途断连一台 server ⇒ 尾部追加一条 delta（removedNames 含断连者）")
    void disconnectedServer_isAnnouncedAtTail() {
        String sessionId = newSessionId();
        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMsg(sessionId, "请求"));
        ToolUseContext both = tuc(sessionId,
            server("docs-server", "先查目录再读文件"), server("im-server", "发消息用 im_send"));
        AgentLoopContext.maybeEmitMcpInstructionsDelta(state, both, "claude-sonnet-4-5");
        int before = state.rawMessages().size();

        // im-server 断连（只剩 docs-server）
        AgentLoopContext.maybeEmitMcpInstructionsDelta(
            state, tuc(sessionId, server("docs-server", "先查目录再读文件")), "claude-sonnet-4-5");

        List<ChatMessageDto> after = state.rawMessages();
        assertThat(after).as("断连必须被公告（只做新增 = 只关了一半分歧）").hasSize(before + 1);
        assertThat(after.get(after.size() - 1).content())
            .contains("\"removedNames\":[\"im-server\"]");
    }

    // ════════ 不变量 ④ · 首轮无需投递 ⇒ 零追加 ════════

    @Test
    @DisplayName("无带 instructions 的 MCP server ⇒ 零追加（producer 恒 null，通道零字节）")
    void nothingToAnnounce_noAppend() {
        String sessionId = newSessionId();
        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMsg(sessionId, "请求"));
        int before = state.rawMessages().size();

        // (a) 完全没有 MCP 连接
        AgentLoopContext.maybeEmitMcpInstructionsDelta(state, tuc(sessionId), "claude-sonnet-4-5");
        // (b) 有连接但没有 instructions
        AgentLoopContext.maybeEmitMcpInstructionsDelta(
            state, tuc(sessionId, server("silent-server", "   ")), "claude-sonnet-4-5");

        assertThat(state.rawMessages()).as("无需投递 ⇒ 零追加").hasSize(before);
    }

    // ════════ 不变量 ⑤ · 无门：旧「关门」阀值不再有任何拦截效果（对齐 2.1.278）════════

    @Test
    @DisplayName("旧门阀值（USER_TYPE≠ant 且 CLAUDE_CODE_MCP_INSTR_DELTA=false）⇒ 仍无条件公告（门已删）")
    void legacyGateOffEnv_noLongerBlocks_unconditionalAnnounce() {
        // WHY（CLAUDE.md 规则九）：2.1.88 里下面这两个 env 会让 isMcpInstructionsDeltaEnabled()
        //   返回 false ⇒ 该通道零字节（旧断言锁的就是这件事）。2.1.278 发行产物已把该门整体删除
        //   （CLAUDE_CODE_MCP_INSTR_DELTA / tengu_basalt_3kr 各 0 命中，挂点与 producer 体内外均无
        //   gate）⇒ 这两个 env **必须不再有任何拦截效果**。本用例守护的正是「门真的没了」：
        //   若谁把门（哪怕以「默认 true 的开关」形式）塞回来，本用例立刻变红。
        ToolSearchService.envOverride =
            Map.of("USER_TYPE", "user", LEGACY_GATE_ENV, "false");
        String sessionId = newSessionId();
        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMsg(sessionId, "请求"));
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitMcpInstructionsDelta(
            state, tuc(sessionId, server("docs-server", "先查目录再读文件")), "claude-sonnet-4-5");

        int afterFirst = state.rawMessages().size();
        assertThat(deltas(state))
            .as("门已删 ⇒ 旧「关门」env 不再拦截，必须无条件公告（旧语义 = 这里零追加）")
            .hasSize(1);
        assertThat(afterFirst).as("尾部确实多出一条（上面的 before 只作为增量证据）").isEqualTo(before + 1);

        // 无门形态下，「内容未变 ⇒ 不追加」这一半仍在 run 内成立（门删掉 ≠ 每轮重公告）
        AgentLoopContext.maybeEmitMcpInstructionsDelta(
            state, tuc(sessionId, server("docs-server", "先查目录再读文件")), "claude-sonnet-4-5");
        assertThat(state.rawMessages())
            .as("内容未变 ⇒ 仍零追加（diff 语义与门无关；⛔ 每轮重公告会让上下文线性膨胀）")
            .hasSize(afterFirst);
    }

    // ════════ 不变量 ⑥ · 发送边界：落库 JSON → 人可读文案（复用既有渲染 case）════════

    @Test
    @DisplayName("发送边界：delta JSON 渲染为可读文案（含 server 名与指令），且二次调用幂等")
    void sendBoundary_rendersJsonToReadableText() {
        AgentState state = new AgentState("sys", newSessionId(), null);
        String payload = "{\"type\":\"mcp_instructions_delta\",\"addedNames\":[\"docs-server\"],"
            + "\"addedBlocks\":[\"## docs-server\\n先查目录再读文件\"],\"removedNames\":[]}";
        ChatMessageDto json = new ChatMessageDto(
            UUID.randomUUID().toString(), state.sessionId(), Role.user, "attachment",
            payload, null, List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, List.of(), List.of(), null, true)
            .withSubtype("mcp_instructions_delta");
        List<ChatMessageDto> in = List.of(json);

        List<ChatMessageDto> rendered = AgentLoopContext.maybeRenderDeltaAttachmentsForApi(in);

        ChatMessageDto out = rendered.get(0);
        assertThat(out).as("命中 ⇒ 惰性复制出新列表").isNotSameAs(json);
        assertThat(out.id()).as("id 透传（withContent 只换 content）").isEqualTo(json.id());
        assertThat(out.subtype()).isEqualTo("mcp_instructions_delta");
        assertThat(out.isMeta()).isTrue();
        assertThat(out.content())
            .as("渲染文案对齐 CC messages.ts:4216-4231（⛔ 模型不该看到原始 JSON）")
            .startsWith("<system-reminder>")
            .contains("# MCP Server Instructions")
            .contains("## docs-server")
            .contains("先查目录再读文件")
            .doesNotContain("\"addedNames\"");

        // 幂等：已是渲染文案（非 JSON）⇒ 解析失败 ⇒ 原引用返回（不会二次包裹）
        List<ChatMessageDto> again = AgentLoopContext.maybeRenderDeltaAttachmentsForApi(rendered);
        assertThat(again).as("非 JSON / 无该 subtype ⇒ 返回原引用，零行为变化").isSameAs(rendered);
        // 无关消息不得被复制
        List<ChatMessageDto> other = List.of(userMsg(state.sessionId(), "普通消息"));
        assertThat(AgentLoopContext.maybeRenderDeltaAttachmentsForApi(other)).isSameAs(other);
    }

    // ════════ 接线锚（源码扫描型 · 见类头边界说明）════════

    /**
     * <b>⚠️ 本方法是源码扫描型断言，只守护「接线位置」这一没有更便宜运行时观察点的不变量</b>
     * （同 {@code LlmAgentLoopWiringOrderTest} 类头声明的三条边界）：它读 LlmAgentLoop 的<b>文本</b>，
     * ⛔ 不具行为鉴别力，⛔ 不得拿它冒充「投递真的发生了」（那由上面五条真跑被测对象的用例承担）。
     *
     * <p>WHY 需要它：本类的行为用例直接调 {@code AgentLoopContext.maybeEmitMcpInstructionsDelta}，
     * <b>看不见调用点</b> —— 把 LlmAgentLoop 里的这行删掉，行为用例会全绿而通道整体失效
     * （历史前科：A1 子 agent 谎报「接通」，见 backend/CLAUDE.md「可参考的经验」第 4 条）。
     * 故用一条最便宜的文本锚补上「调用点存在且相对序正确」。
     */
    @Test
    @DisplayName("接线锚：每轮挂点存在（且未被注释），位于 date_change 之后、changed_files 之前")
    void wiring_perTurnCallSitsBetweenDateChangeAndChangedFiles() throws Exception {
        String src = Files.readString(Path.of(
            "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java"));
        int dateChange = src.indexOf("AgentLoopContext.maybeEmitDateChange(state)");
        int mcpDelta = src.indexOf("AgentLoopContext.maybeEmitMcpInstructionsDelta(");
        int changedFiles = src.indexOf("AgentLoopContext.maybeEmitChangedFiles(state, params.toolUseContext())");
        assertThat(dateChange).as("date_change 挂点必须在（CC attachments.ts:830）").isGreaterThan(-1);
        assertThat(mcpDelta).as("⛔ mcp_instructions_delta 每轮挂点必须存在（CC attachments.ts:854）").isGreaterThan(-1);
        assertThat(changedFiles).as("changed_files 挂点必须在（CC attachments.ts:871）").isGreaterThan(-1);
        // ⭐ 变异实测（2026-09-21）：只断言 indexOf > -1 是**假绿** —— 把该行注释掉后，
        //   注释文本仍被 indexOf 命中，7 条用例全绿（挂点实际已摘除）。⇒ 必须再断言「命中行是活代码」。
        assertThat(lineOf(src, mcpDelta).strip())
            .as("⛔ 命中行不得是注释（注释里的文本也能被 indexOf 命中 —— 实测过的假绿陷阱）")
            .doesNotStartWith("//")
            .doesNotStartWith("*")
            .doesNotStartWith("/*")
            .contains("maybeEmitMcpInstructionsDelta(");
        assertThat(lineOf(src, dateChange).strip()).doesNotStartWith("//");
        assertThat(lineOf(src, changedFiles).strip()).doesNotStartWith("//");
        assertThat(mcpDelta).as("CC 段内相对序：date_change(:830) 之前于 mcp_instructions_delta(:854)")
            .isGreaterThan(dateChange);
        assertThat(mcpDelta).as("CC 段内相对序：mcp_instructions_delta(:854) 之前于 changed_files(:871)")
            .isLessThan(changedFiles);

        // ⭐ 发送边界渲染必须在请求链上（否则就是「照抄了死通道」：产出了 JSON 却没人渲染 ⇒
        //   模型看到原始 JSON，或整条通道静默失效 —— 2.1.278 的 prefix_delta 就是这么死的）。
        int renderCall = src.indexOf("AgentLoopContext.maybeRenderDeltaAttachmentsForApi(");
        assertThat(renderCall).as("⛔ 发送边界渲染必须有调用点，否则通道是死通道").isGreaterThan(-1);
        assertThat(lineOf(src, renderCall).strip()).doesNotStartWith("//");
        assertThat(renderCall)
            .as("必须挂在发送边界（wrapQueuedMessagesForApi 之后 = CC normalizeMessagesForAPI 等价点）")
            .isGreaterThan(src.indexOf("wrapQueuedMessagesForApi(messagesForLlm)"));
    }

    /** 取 {@code index} 所在那一行（源码扫描锚用）。 */
    private static String lineOf(String src, int index) {
        int start = src.lastIndexOf('\n', index);
        int end = src.indexOf('\n', index);
        return src.substring(start < 0 ? 0 : start + 1, end < 0 ? src.length() : end);
    }
}
