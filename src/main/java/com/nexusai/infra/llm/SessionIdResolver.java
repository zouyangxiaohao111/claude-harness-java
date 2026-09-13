package com.nexusai.infra.llm;

import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;

/**
 * 会话 ID 解析 · 单点判据源（3 级优先级）。
 *
 * <p><b>刻意不读 MDC（{@code RequestContext.sessionId()}）</b>——这是 T3 实测后的决定。
 * 该方法读的是裸 MDC，而全仓 MDC 有<b>两类</b>写点（均已实测普查）：
 *
 * <ol>
 *   <li><b>{@code MDC.put} 单键写</b> · 仅 {@code RequestContext} 一处
 *       （{@code :36} / {@code :37} / {@code :48}）；</li>
 *   <li><b>{@code MDC.setContextMap} 整表覆盖</b> · <b>12 处</b>回放：
 *       {@code LlmAgentLoop:6477}、{@code StreamingToolExecutor:2468/:2474}、
 *       {@code SubagentTool:3189/:3309/:3733/:3824}、{@code SpawnInProcess:373/:385}、
 *       {@code YoloClassifierImpl:1166/:1191}、{@code McpToolPool:1521}。
 *       其中 {@code LlmAgentLoop:6477} 跑在 {@code STREAM_EXECUTOR} 虚拟线程内，
 *       <b>正是流式链路（{@code provider.stream}）所在线程</b>。</li>
 * </ol>
 *
 * 全仓<b>没有任何 Filter / Interceptor / ChannelInterceptor</b> 写 MDC；但上面第 2 类
 * 整表覆盖<b>同样能写 {@code SESSION_ID}</b>，故 MDC 实际有<b>三种</b>状态：
 * 干净线程 = null；<b>残留线程 = 上一个请求留下的、别的会话的 sessionId</b>；
 * 以及被回放进来的、可能来自别会话的 map。残留是真实的：{@code MemoryController:143} /
 * {@code TaskController:145} / {@code TeamController:95} 三处都调 {@code setSession}
 * 而均无 {@code clear}（只有 {@code CommandController:363} 清了）。
 *
 * <p><b>这两类写点的存在不改变结论，反而加强了它</b>：{@code setContextMap} 回放进来的 map
 * 本身就可能携带别会话的 id，采纳它同样是静默串号。故剔除 MDC 那一级的决定不受影响。
 *
 * <p>采纳残留值比返回 null 更坏——它会静默把 A 会话的亲和 id 发给 B 会话的请求，
 * 不报错、不发常量。而 MDC 在<b>主链上又永远用不到</b>（{@code stream} /
 * {@code nonStreamingSend} / {@code chatWithOptions} 都有 history）→ 剔除它只有收益、没有损失。
 *
 * <p>为什么不是「全局单例」：本仓是 Web 多会话，会话级状态存 {@code sessions} 表列，
 * 不用 settings 全局单例（架构铁律）。故 sessionId 只能显式穿线。
 *
 * <p>解析不出来时返回 null（由 {@link DynamicHeaderExpander} 落 STATIC_FALLBACK），
 * <b>绝不在这里编造一个会话 ID</b>。
 *
 * <p><b>⚠️ 接线时勿照抄调用点的 MDC 兜底</b>：现存唯一调用点
 * {@code AnthropicSdkProvider.consumePostCompactionAtApiSuccess}（方法声明在 {@code :2789}，
 * 其中 {@code if (sessionId == null) sessionId = RequestContext.sessionId();} 在 {@code :2798}）
 * 紧邻一句 MDC 兜底，但<b>那属另一条链路（consumePostCompaction）的既有语义，勿照抄</b>——
 * 本类刻意不读 MDC。任务 6 接 9 个调用点时若「以同文件最近的先例为准」照抄那句兜底，
 * <b>本类全部测试仍会绿</b>（守卫是方法局部的，管不到调用点），而「静默把 A 会话的亲和 id
 * 发给 B 会话请求」会原样复发。
 *
 * <p><b>接线级护栏已落地</b>：{@code ProviderSessionIdWiringGuardTest}（任务 6 · 步骤 6b）遍历
 * {@code buildClient(config} 调用点所在的方法，断言方法体内<b>不出现</b>
 * {@code RequestContext.sessionId()}，并断言 5 个真实调用点都经本类解析 —— 上面的隐患由此自动化守住。
 *
 * <p>设计见 docs/zjkycode/specs/2026-09-12-provider-custom-headers-design.md §6.3。
 */
public final class SessionIdResolver {

    private SessionIdResolver() {
    }

    /** ①/② 从任一 history 列表取第一条非空 sessionId（DB 真值，主链必中）。 */
    public static String fromHistory(List<ChatMessageDto> history) {
        if (history == null) {
            return null;
        }
        for (ChatMessageDto m : history) {
            if (m != null && m.sessionId() != null && !m.sessionId().isBlank()) {
                return m.sessionId();
            }
        }
        return null;
    }

    /**
     * 完整 3 级优先级：history → options.history → null。
     *
     * <p>第 3 级「null」由调用方的 {@link DynamicHeaderExpander} 落兜底常量，
     * 不在此处编码，避免「解析」与「回落策略」两件事耦合。
     *
     * @param history        主 history（{@code LlmProvider.stream} 等方法形参）；可为 null
     * @param optionsHistory {@code ChatRequestOptions.history()}（chatWithOptions 系列）；
     *                       可为 null
     * @return 会话 ID；全不中返回 null
     */
    public static String resolve(List<ChatMessageDto> history, List<ChatMessageDto> optionsHistory) {
        String fromMain = fromHistory(history);
        if (fromMain != null) {
            return fromMain;
        }
        return fromHistory(optionsHistory);
    }
}
