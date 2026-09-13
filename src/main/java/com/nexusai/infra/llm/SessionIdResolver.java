package com.nexusai.infra.llm;

import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;

/**
 * 会话 ID 解析 · 单点判据源（3 级优先级）。
 *
 * <p><b>刻意不读任何环境态会话槽</b>——这是 T3 实测后的决定，批 3c 之后更强。
 * 历史形态是「读裸 MDC 会话槽」（那个工具类（批 3c 已整体删除），
 * <b>已随批 3c 连同其清理 Filter 与 logback 前缀一并删除</b>）。当时 MDC 有<b>两类</b>写点
 * （均已实测普查）：{@code MDC.put} 单键写（仅该工具类一处）+ 12 处
 * {@code MDC.setContextMap} 整表回放（{@code LlmAgentLoop} 的 {@code STREAM_EXECUTOR} 虚拟线程、
 * {@code StreamingToolExecutor}、{@code SubagentTool}、{@code SpawnInProcess}、
 * {@code YoloClassifierImpl}、{@code McpToolPool}）。两类写点都会让 MDC 出现<b>三种</b>状态：
 * 干净线程 = null；<b>残留线程 = 上一个请求留下的、别的会话的 sessionId</b>；以及被回放进来的、
 * 可能来自别会话的 map。全仓<b>没有任何 Filter / Interceptor / ChannelInterceptor</b> 写 MDC，
 * 残留来自各 REST 端点散点手写 {@code setSession} 而无成对 {@code clear}。
 *
 * <p><b>采纳残留值比返回 null 更坏</b>——它会静默把 A 会话的亲和 id 发给 B 会话的请求，
 * 不报错、不发常量。而 MDC 在<b>主链上又永远用不到</b>（{@code stream} /
 * {@code nonStreamingSend} / {@code chatWithOptions} 都有 history）→ 剔除它只有收益、没有损失。
 * <b>批 3c 结论</b>：该槽已被彻底删除，且 {@code isTodoV2Enabled()} 等原本依赖它的判定已改为
 * 进程级 / 显式传参（详见各文件 [批 3c] 注释）。
 *
 * <p>为什么不是「全局单例」：本仓是 Web 多会话，会话级状态存 {@code sessions} 表列，
 * 不用 settings 全局单例（架构铁律）。故 sessionId 只能显式穿线。
 *
 * <p>解析不出来时返回 null（由 {@link DynamicHeaderExpander} 落 STATIC_FALLBACK），
 * <b>绝不在这里编造一个会话 ID</b>。
 *
 * <p><b>⚠️ 接线时勿照抄调用点里的任何环境态兜底</b>：{@code consumePostCompactionAtApiSuccess}
 * 曾紧邻一句「{@code SessionIdResolver} 取不到就用当前线程会话槽」的兜底，但<b>那属另一条链路
 * （consumePostCompaction）的既有语义，勿照抄</b>——本类刻意不读任何槽。该句已随批 3b/3c 删除。
 * 任务 6 接 9 个调用点时若「以同文件最近的先例为准」照抄那种兜底，<b>本类全部测试仍会绿</b>
 * （守卫是方法局部的，管不到调用点），而「静默把 A 会话的亲和 id 发给 B 会话请求」会原样复发。
 *
 * <p><b>接线级护栏已落地</b>：{@code ProviderSessionIdWiringGuardTest} 遍历
 * {@code buildClient(config} 调用点所在的方法，断言方法体内<b>不出现任何环境态会话槽</b>
 * （已删的 MDC 工具类 / {@code MDC.get} / {@code AutoMemPaths.currentSessionProjectRoot}），
 * 并断言 5 个真实调用点都经本类解析 —— 上面的隐患由此自动化守住。
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
