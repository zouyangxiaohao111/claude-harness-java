package com.nexusai.application.agent.cost;

import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.infra.llm.LlmInvocationGateway;
import com.nexusai.infra.llm.LlmInvocationRequest;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.UsageAttribution;
import com.nexusai.infra.llm.UsageCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <b>旁路 LLM 单发客户端</b> · 把「非 query 循环的一次性 LLM 调用」收口到请求网关并自动记账。
 *
 * <p><b>WHY 需要它（改造前症状）</b>：全仓有 20+ 处旁路调用（标题生成、/btw 提问、记忆检索、
 * 离开摘要、视觉分析、WebSearch/WebFetch、文档更新、权限解释器、Yolo 分类器、prompt hook、
 * 子代理摘要、工具使用摘要…）此前各自直呼 {@code provider.chat / chatWithOptions /
 * chatWithRaw / chatWithOptionsMessage}，<b>没有任何一处记账</b> ⇒ 这些真实发生的上游开销
 * 在会话金额与统计里完全不存在。本类让它们只需一次方法调用即完成「调用 + 归属 + 记账」。
 *
 * <p><b>归属缺省语义</b>（诚实优先，不伪造）：调用方拿不到会话/agent 上下文时传 null ⇒
 * 归属为 {@link UsageAttribution#unknown(String)}，账本落入无会话槽、明细表 {@code session_id}
 * 为 NULL —— 仍然可统计（这正是「无遗漏」的要求），但不会被硬塞进某个会话的账目。
 *
 * <p><b>local-only</b>：本类不推送任何外部通道；记账只落进程内账本 + {@code llm_usage_calls} 明细。
 */
@Component
public class LlmSideCallClient {

    private static final Logger log = LoggerFactory.getLogger(LlmSideCallClient.class);

    @Autowired(required = false)
    private LlmProviderFactory providerFactory;

    @Autowired(required = false)
    private ModelConfigResolver modelConfigResolver;

    @Autowired(required = false)
    private SessionUsageAccountingRegistry ledgerRegistry;

    /** 归属入参束（避免调用点传 6 个散参）。 */
    public record SideCallUsage(
            String sessionId,
            String agentId,
            UsageCategory category,
            String label,
            AgentContext agentContext
    ) {
        /** 有会话上下文（主 agent 发起的旁路调用）：父节点 = 会话根，depth 1。 */
        public static SideCallUsage inSession(String sessionId, String agentId,
                                              UsageCategory category, String label) {
            return new SideCallUsage(sessionId, agentId, category, label, null);
        }

        /** 无上下文（工具内部 / 定时任务）：归 UNKNOWN，仍统计。 */
        public static SideCallUsage standalone(UsageCategory category, String label) {
            return new SideCallUsage(null, null, category, label, null);
        }

        /** 追加归因上下文（provider 的 per-LLM-call 归因边）。 */
        public SideCallUsage withAgentContext(AgentContext ctx) {
            return new SideCallUsage(sessionId, agentId, category, label, ctx);
        }

        /**
         * 归属构造（记账节点身份）。
         *
         * <p><b>① 身份归一</b>：{@code agentId} 经 {@link com.nexusai.application.agent.subagent.AgentContext#canonicalAgentId(String)} 归一到 a+16hex
         * （单一身份源）——旁路调用点普遍传 {@code state.agentId().toString()}（UUID 形式），
         * 而归属于同一 agent 的主循环调用用 a+16hex；不归一 ⇒ 同一 agent 裂成两个账本节点
         * （实测：子代理本体一行 + 它的旁路一行，费用被拆开、前端同名两行）。
         *
         * <p><b>② 层级</b>：该 agent 已有节点（账本里查得到）⇒ 本调用记在**它自己的节点**上
         * （复用其 parent/depth；类别/label 用本次调用的，byCategory 照常分列）；查不到 ⇒ 挂在
         * 会话根下 depth 1；{@code agentId} 空 / 等于会话 id（会话根自己发起）⇒ depth 0，
         * ⛔ 不造「自己是自己的父」那种自环节点。
         */
        UsageAttribution attribution(SessionUsageLedger ledger) {
            if (sessionId == null || sessionId.isBlank()) {
                // 无会话上下文 → 归类 UNKNOWN（⛔ 不伪造 sessionId），但**保留本次调用的用途 label**：
                // 否则前端「按代理层级/按调用类型」只能显示「未归类」，读不出这条是什么调用
                return new UsageAttribution(null, null, UsageCategory.UNKNOWN, null, 0, label);
            }
            UsageCategory cat = category != null ? category : UsageCategory.SIDE_CALL;
            String agentKey = com.nexusai.application.agent.subagent.AgentContext.canonicalAgentId(agentId);
            if (agentKey == null || agentKey.equals(sessionId)) {
                return UsageAttribution.top(sessionId, sessionId, cat, label);
            }
            UsageAttribution self = ledger != null ? ledger.attributionOf(agentKey) : null;
            if (self != null) {
                return new UsageAttribution(sessionId, agentKey, cat,
                    self.parentAgentId(), self.depth(), label);
            }
            return UsageAttribution.top(sessionId, sessionId, UsageCategory.MAIN, null)
                .child(agentKey, cat, label);
        }
    }

    /**
     * 单发调用（返回文本）· 调用方只要把原来的 {@code provider.chatWithOptions(...)} 换成
     * {@code sideCallClient.chat(...)} 即完成「调用 + 记账」。
     *
     * @param config       解密后的 provider 配置（调用方既有解析结果）
     * @param modelName    模型名
     * @param systemPrompt 系统提示词（可 null）
     * @param userMessage  单条 user 消息
     * @param options      chat 选项（可 null）
     * @param usage        归属（不可 null；用 {@link SideCallUsage} 的两个工厂构造）
     * @return 模型输出文本
     */
    public String chat(ProviderConfig config, String modelName, String systemPrompt, String userMessage,
                       LlmProvider.ChatRequestOptions options, SideCallUsage usage) {
        return message(config, modelName, systemPrompt, userMessage, options, usage).content();
    }

    /**
     * 单发调用（返回完整结果：文本 + 工具块 + usage）· 需要 tool_use 块（权限解释器）或需要
     * 读取 usage（分类器遥测）的调用方用它。
     */
    public LlmProvider.LlmInvocationResult message(ProviderConfig config, String modelName,
                                                   String systemPrompt, String userMessage,
                                                   LlmProvider.ChatRequestOptions options,
                                                   SideCallUsage usage) {
        if (providerFactory == null) {
            // 显式失败：装配缺失属配置错误，不得静默返回空串（规则十二）
            throw new IllegalStateException("[LlmSideCallClient] LlmProviderFactory 未装配，无法发起旁路调用");
        }
        SideCallUsage u = usage != null ? usage : SideCallUsage.standalone(UsageCategory.SIDE_CALL, null);
        SessionUsageLedger ledger = ledgerRegistry != null ? ledgerRegistry.forSession(u.sessionId()) : null;
        LlmInvocationRequest request = new LlmInvocationRequest(
            config, modelName, systemPrompt, userMessage, options,
            options != null && options.querySource() != null ? options.querySource() : u.label(),
            null, u.agentContext(), u.attribution(ledger), ledger);
        if (log.isDebugEnabled()) {
            log.debug("[LlmSideCallClient] 旁路调用: model={} category={} label={} sessionId={} agentId={} 记账={}",
                modelName, u.category(), u.label(), u.sessionId(), u.agentId(), ledger != null);
        }
        return LlmInvocationGateway.invoke(providerFactory, modelConfigResolver, request);
    }
}
