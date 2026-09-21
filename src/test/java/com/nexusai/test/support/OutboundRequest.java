package com.nexusai.test.support;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.infra.llm.OutboundWireProjection;
import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;

/**
 * <b>步骤 8 · 防回归判据的观测载体</b>：一次<b>出站 LLM 请求</b>在 provider 边界的三个构件。
 *
 * <p><b>为什么需要它（= 本类存在的全部理由）</b>：CC/dsh 的 {@code expectPrefixExtension} 断言作用于
 * {@code GenerateOptions}（dsh {@code packages/core/agent-loop/tests/request-reconstruction.spec.ts:55-60}：
 * {@code current.messages} 是 {@code previous.messages} 的严格前缀扩展，且 {@code current.system} /
 * {@code current.tools} 与 {@code previous} 逐字节相等）。本仓没有对应的「请求对象」——
 * 出站请求在 {@code LlmProvider.stream(...)} 的<b>形参表</b>上就散开了
 * （system blocks / history / tools 三个独立实参）。本 record 把它们<b>捆成一个可比较的对象</b>，
 * 使「同一会话相邻两次请求是否逐字节相等」成为可判绿的断言，而不是每次重新拼三个 assert。
 *
 * <h2>⭐ 比较口径：线级（wire）投影，不是 DTO 深比较</h2>
 * <p>{@code ChatMessageDto} 携带 {@code id} / {@code createdAt} / {@code time} 等<b>客户端字段</b>——
 * 它们<b>不进 wire</b>（{@code OpenAiSdkProvider.toSdkMessage} 只取 role / content / contentBlocks /
 * toolCalls(id,name,arguments) / toolCallId / acceptFeedback）。因此：
 * <ul>
 *   <li>DTO 深比较（{@code equals}）会因每轮新生成的 message id 而<b>恒假</b> ⇒ 无法表达
 *       「前缀逐字节稳定」这一 wire 事实；</li>
 *   <li>{@link #wireMessages()} / {@link #toolsText()} 走<b>线级投影</b>
 *       （{@link OutboundWireProjection}），才是「DeepSeek 前缀缓存看到的字节」的忠实等价物。</li>
 * </ul>
 * <p>⚠ <b>投影必须与 provider 的 wire 字段集同源</b>：漏一个进 wire 的字段
 * （如 {@code toolCallId}）就会把真实漂移判成绿；多一个不进 wire 的字段（如 {@code id}、
 * tools 源 JSON 里的 {@code defer_loading}）就会把稳定判成红。
 * 两点都在 {@code PromptHeadFreezePrefixInvariantTest} 与
 * {@code OutboundHeadProbeDigestTest} 里有正反用例钉住。
 *
 * <p>⭐ <b>单一实现（不许双份）</b>：投影能力的<b>唯一</b>实现是生产类
 * {@link OutboundWireProjection} —— 本类只是<b>转调</b>，⛔ 不在这里另写一套。
 * WHY：同一个口径还有第二个消费者 = 生产侧的出站头部探针
 * （{@code OpenAiSdkProvider.fingerprintHead}）。两处若各写一份，任一处漂移都会静默产生
 * 假判据（探针假阳性会误导归因；测试假红会掩盖真实漂移）。
 * 依赖方向 = 测试 → 生产（本类 import 生产类），⛔ 生产代码不依赖测试类。
 *
 * <h2>system 的口径</h2>
 * <p>{@link #systemText()} = 各 block 文本以 {@code "\n\n"} 连接 —— 这正是 OpenAI 兼容链路
 * （DeepSeek 走的那条）的线级形态：{@code OpenAiSdkProvider.stream} 的
 * {@code blocks.stream().map(text).collect(Collectors.joining("\n\n"))}。
 * ⛔ 不比较 block <b>数组形态</b>：block 划分 / {@code cache_scope} 在 DeepSeek 链路上<b>不落 wire</b>
 * （计划 §2/§3「不抄这一半」）——把不落 wire 的东西纳入判据会把本仓判成假红。
 *
 * @param system   出站 system blocks（{@code LlmProvider.stream} 第 3 实参；可 null = 不发送）
 * @param messages 出站消息列表（第 4 实参；record 构造期即做防御性拷贝）
 * @param tools    出站工具数组（第 5 实参；可 null = 无工具）
 */
public record OutboundRequest(
        List<SystemPromptBlock> system,
        List<ChatMessageDto> messages,
        ArrayNode tools) {

    /** block 连接符 · 与出站 system 串的 {@code joining("\n\n")} 同一字面量。 */
    public static final String SYSTEM_BLOCK_JOIN = "\n\n";

    public OutboundRequest {
        system = system == null ? List.of() : List.copyOf(system);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * 线级 system 串（各 block 以 {@code "\n\n"} 连接）· 见类 javadoc「system 的口径」。
     * <p>空 blocks ⇒ {@code ""}（对齐 provider「null/空 blocks = 不发送 system」的语义投影）。
     */
    public String systemText() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (SystemPromptBlock b : system) {
            String t = b == null || b.text() == null ? "" : b.text();
            if (!first) {
                sb.append(SYSTEM_BLOCK_JOIN);
            }
            first = false;
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * 线级 tools 串 · 转调 {@link OutboundWireProjection#projectTools(ArrayNode, boolean)}
     * （字段集 = {@code OpenAiSdkProvider.toOpenAiSdkTool} 透传的那些；⛔ 不含源 JSON 里
     * 从不被读取、因而不进 wire 的 {@code defer_loading}）。
     *
     * <p>⚠ 门控入参取 {@code true}（= 假定 strict 模型层门控通过）：测试侧没有模型上下文，
     * 而「带上 strict」是 wire 的<b>超集</b> —— 只会更严，不会把真实漂移判成绿。
     */
    public String toolsText() {
        return OutboundWireProjection.projectTools(tools, true);
    }

    /** 消息条数。 */
    public int messageCount() {
        return messages.size();
    }

    /**
     * 线级消息投影 · 每条形如 {@code "role|content|toolCallId|toolCalls"}（见类 javadoc 的口径说明）。
     *
     * <p>转调 {@link OutboundWireProjection#projectMessages(List)}（= 生产唯一实现）：
     * 与 wire 同序，先过 {@code ToolResultPairingRepair#ensureToolResultPairing(List)}
     * （发送边界的同一次修复），否则「悬挂 tool_use / 孤儿 tool_result」会在两侧被不同地折叠，
     * 产生与 wire 无关的假差异。
     */
    public List<String> wireMessages() {
        return OutboundWireProjection.projectMessages(messages);
    }

    /** 头部消息（{@code messages[0]}）的线级投影；无消息 ⇒ {@code "<none>"}（⛔ 不返回 null）。 */
    public String headWireMessage() {
        List<String> wire = wireMessages();
        return wire.isEmpty() ? "<none>" : wire.get(0);
    }
}
