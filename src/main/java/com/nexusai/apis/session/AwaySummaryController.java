package com.nexusai.apis.session;

import com.nexusai.application.agent.memory.AwaySummaryService;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.common.RequestContext;
import com.nexusai.domain.session.MessageService;
import com.nexusai.model.session.dto.AwaySummaryRequest;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * away-summary REST 端点 · 对齐 CC hooks/useAwaySummary.ts（blur 5min + feature('AWAY_SUMMARY')
 * + flag 'tengu_sedge_lantern' 默认 false 的前端触发钩子）。
 *
 * <p><b>ODF-B1</b>（owner 2026-08-06 拍板）: CC 触发层在前端 REPL —— blur 5min 计时 +
 * {@code hasSummarySinceLastUserTurn} 去重（useAwaySummary.ts:16-23）+ 回插 away_summary
 * 系统消息（useAwaySummary.ts:80 {@code createAwaySummaryMessage}）。Web 后端无 blur/focus
 * （OPD-M-39 登记「触发层 N/A 待前端同步」），本端点提供 REST 载体：前端 blur 5min 后 POST
 * 本端点获取 recap 文本，自行回插为 away_summary 系统消息。前端详细要求见
 * {@code 待前端对接.md} §8 away-summary 小节。
 *
 * <p><b>服务层契约（IMP-M-P2-3 / OPD-M-41）</b>: 端点经
 * {@link AwaySummaryService#generate} 走 {@code chatWithOptions} —— querySource='away_summary'
 * / skipCacheWrite=true / small-fast（haiku）/ thinking disabled（awaySummary.ts:44/:49/:54/:56）。
 *
 * <p><b>sessionId 源（ODF-B1R）</b>: <b>请求优先 + MDC 兜底</b>——CC 触发层在前端 REPL
 * （useAwaySummary.ts:32 起），会话上下文由前端持有，故前端 POST 可随请求传 sessionId（body JSON
 * {@code {"sessionId": "..."}} 或 query {@code ?sessionId=...}，body 优先）；请求未传时兜底
 * {@link RequestContext#sessionId()}（MDC）—— AS-05 rev2 显式单轨：bean 无 sessionId supplier，
 * ToolRegistrationConfig awaySummaryService @Bean（:1018-1040）构造参数为
 * llmProviderFactory/sessionMemoryService/四个可选 mapper·provider 依赖，无 sessionId 源。
 * 请求与 MDC 双空 → 500 fail loud（对齐 CommandController executeResume 同语义）。
 *
 * <p><b>null → 204</b>: 空 transcript / API error / abort / 异常 → 服务返回 null（CC
 * awaySummary.ts:33-35/:60-73），REST 以 204 空体表达（不抛 500）；LLM 调用超时（兜底限时）
 * → 500（§8 不擅自决定）。
 */
@RestController
@RequestMapping("/api/agent")
public class AwaySummaryController {

    private static final Logger log = LoggerFactory.getLogger(AwaySummaryController.class);

    /**
     * {@link #generate} 的 {@code CompletableFuture.get()} 限时兜底。CC 侧 blur 5min 触发，
     * 本值仅约束单次 LLM 调用不无限挂起（small-fast 1-3 句 recap 正常 <10s，60s 为兜底上限）。
     */
    private static final long AWAY_SUMMARY_TIMEOUT_MS = 60_000;

    @Autowired private AwaySummaryService awaySummaryService;

    @Autowired private MessageService messageService;

    /**
     * 生成 "while you were away" session recap 文本 · POST /api/agent/away-summary。
     *
     * <p>流程: 解析 sessionId（ODF-B1R 请求优先 + MDC 兜底）→ {@link MessageService#listBySession}
     * 加载全部消息 → {@link AwaySummaryService#generate}{@code (messages, new AbortController())} →
     * {@code .get(60s)}。结果语义:
     * <ul>
     *   <li>recap 文本 → 200（text/plain，前端回插 away_summary 系统消息）</li>
     *   <li>null（空 transcript / API error / abort / 异常）→ 204 空体（CC 静默降级）</li>
     *   <li>超时 / 中断 / 服务异常 → 500（fail loud，规则十二）</li>
     * </ul>
     *
     * @param querySessionId query 参数 {@code ?sessionId=...}（可为空；body 未传时兜底）
     * @param body           POST JSON 请求体（可为空 body；sessionId 优先于 query 与 MDC）
     * @return 200 recap 文本或 204 空体
     */
    @PostMapping(value = "/away-summary", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> generate(
            @RequestParam(value = "sessionId", required = false) String querySessionId,
            @RequestBody(required = false) AwaySummaryRequest body) {
        String sessionId = resolveSessionId(body, querySessionId);
        if (sessionId == null) {
            log.warn("[AwaySummaryController] 请求(body/query)与 MDC 均无 sessionId → 500 fail loud"
                    + "（对齐 CommandController executeResume；ODF-B1R 契约：请求优先+MDC 兜底）");
            throw new IllegalStateException("无会话上下文 (sessionId)");
        }
        // [S1] away-summary = 续聊加载历史通道 → listForResume（对齐 CC
        //   deserializeMessagesWithInterruptDetection：未配对 tool_use/孤立 thinking/纯空白
        //   assistant 剥离，避免 "有问无答" 中断 turn 进入摘要输入）。DB 权威写入不变。
        List<ChatMessageDto> messages = messageService.listForResume(sessionId);
        if (log.isDebugEnabled()) {
            log.debug("[AwaySummaryController] session={} 加载消息 {} 条（listForResume 中断语义），"
                    + "触发 away-summary 生成（前端 blur 5min 后调用，CC useAwaySummary.ts）",
                sessionId, messages.size());
        }

        // CC useAwaySummary.ts:73-74 new AbortController()（未 abort 新实例；REST 端点无前端 abort
        // 信号源，交由 get() 限时兜底）
        AbortController signal = new AbortController();
        // AS-05（rev2）：显式 sessionId 单轨 —— resolveSessionId 结果同时驱动消息加载与
        // 服务内 memory 读（消除 MDC supplier 双轨；CC getSessionMemoryContent 无参读当前会话
        // 经调用方注入表达）
        CompletableFuture<String> future = awaySummaryService.generate(messages, signal, sessionId);
        String recap;
        try {
            recap = future.get(AWAY_SUMMARY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // §8: generate 异步 CompletableFuture 需 .get() 限时（超时→500）；中止信号 + 取消
            // future 防悬挂 LLM 任务泄漏
            signal.abort("away_summary_timeout");
            future.cancel(true);
            log.error("[AwaySummaryController] away-summary 生成超时（{}ms）→ 500", AWAY_SUMMARY_TIMEOUT_MS);
            throw new IllegalStateException("away-summary 生成超时（" + AWAY_SUMMARY_TIMEOUT_MS + "ms）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            signal.abort("away_summary_interrupted");
            future.cancel(true);
            log.error("[AwaySummaryController] away-summary 生成被中断 → 500");
            throw new IllegalStateException("away-summary 生成被中断", e);
        } catch (ExecutionException e) {
            // 服务层已吞掉全部异常返回 null（awaySummary.ts:60-73），此处防御性 fail loud
            signal.abort("away_summary_exception");
            future.cancel(true);
            log.error("[AwaySummaryController] away-summary 生成异常 → 500", e.getCause());
            throw new IllegalStateException("away-summary 生成异常", e.getCause());
        }

        if (recap == null) {
            if (log.isDebugEnabled()) {
                log.debug("[AwaySummaryController] away-summary 结果为空（空 transcript/API error/abort）"
                        + "→ 204 空体（CC awaySummary.ts:33-35/:60-73 静默降级）");
            }
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        if (log.isDebugEnabled()) {
            log.debug("[AwaySummaryController] away-summary 生成成功: session={} recap {} 字符"
                    + "（前端回插 away_summary 系统消息，CC useAwaySummary.ts:80）",
                sessionId, recap.length());
        }
        return ResponseEntity.ok(recap);
    }

    /**
     * [ODF-B1R] 解析 sessionId · <b>请求优先 + MDC 兜底</b>。
     *
     * <p>CC 触发层在前端 REPL（useAwaySummary.ts:32），会话上下文由前端持有——Web 前端 POST 可随请求
     * 传 sessionId（body {@link AwaySummaryRequest#sessionId} 优先，其次 query
     * {@code ?sessionId=...}）；请求均未传（或空白）时兜底 {@link RequestContext#sessionId()}（MDC，
     * 与 ToolRegistrationConfig awaySummaryService @Bean :1018-1040 显式单轨一致）。三源均缺 → null（调用方 fail loud 500）。
     *
     * @param body           POST JSON 请求体（可为 null）
     * @param querySessionId query 参数（可为 null）
     * @return 解析出的 sessionId 或 null
     */
    private String resolveSessionId(AwaySummaryRequest body, String querySessionId) {
        if (body != null && body.sessionId() != null && !body.sessionId().isBlank()) {
            return body.sessionId();
        }
        if (querySessionId != null && !querySessionId.isBlank()) {
            return querySessionId;
        }
        return RequestContext.sessionId();
    }
}
