package com.nexusai.apis.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.application.agent.memory.SessionMemoryConfigChannel;
import com.nexusai.application.agent.memory.SessionMemoryService;
import com.nexusai.application.agent.memory.SessionMemoryUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SM 阈值 Web 调参通道 REST 端点（IMP-CM-35 / OPD-CM3-14 DEC-CM3-C01）· 替代 CC GrowthBook
 * 远端配置通道（tengu_sm_config / tengu_sm_compact_config）的本地调参入口。
 *
 * <p><b>CC 真源（grep 自验 2026-08-15）</b>：CC 生产经 GrowthBook 动态配置读阈值
 * <ul>
 *   <li>{@code tengu_sm_config} → SessionMemoryConfig（sessionMemoryUtils.ts:18-29，
 *       default 10000/5000/3）· getSessionMemoryRemoteConfig（sessionMemory.ts:88-93）+
 *       initSessionMemoryConfigIfNeeded（:240-264，仅正值覆盖）。</li>
 *   <li>{@code tengu_sm_compact_config} → SessionMemoryCompactConfig
 *       （sessionMemoryCompact.ts:47-54，default 10000/5/40000）· initSessionMemoryCompactConfig
 *       （:98-130，仅正值覆盖）。</li>
 * </ul>
 * Java 无 GrowthBook（B/CM-B1 X1、B/CM-B3 ✗-1），OPD-CM3-14 拍板以本端点 + 通道存储建模远端通道。
 * 消费侧（IMP-CM-03）改从该通道读取动态值；通道未配置 → DEFAULT 回退。
 *
 * <p><b>端点契约</b>（路径沿用本仓 {@code /api/v1/...} 规范）：
 * <ul>
 *   <li>{@code GET  /api/v1/session-memory/config} —— 读当前生效 SM 提取 + 压缩阈值。</li>
 *   <li>{@code PUT  /api/v1/session-memory/config/sm} —— 更新 SM 提取阈值（tengu_sm_config 等价）。
 *       body 各字段可选（Integer，null/0 表示不覆盖 = CC「仅正值覆盖」语义）。</li>
 *   <li>{@code PUT  /api/v1/session-memory/config/sm-compact} —— 更新 SM 压缩阈值
 *       （tengu_sm_compact_config 等价）。body 各字段可选（Integer，null/0 不覆盖）。</li>
 * </ul>
 * 更新立即生效（写运行期状态，不重启进程），返回更新后的完整配置。
 */
@RestController
@RequestMapping("/api/v1/session-memory/config")
public class SessionMemoryConfigController {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryConfigController.class);

    @Autowired
    private SessionMemoryConfigChannel channel;

    /**
     * 当前生效配置（读端）· GET /api/v1/session-memory/config。
     *
     * <p>[V52 token-compact-settings-fix] 读<b>生效值</b>（DB &gt; Web 调参通道 &gt; DEFAULT）：
     * 经 {@code channel.getEffectiveSessionMemoryConfig()/getEffectiveSmCompactConfig()} 反映 DB settings
     * 合并结果，而非仅 Web 内存通道值；DB 未配置时回落内存通道值（与 PUT 合并基值通道同源，不污染）。
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public SessionMemoryConfigView get() {
        SessionMemoryUtils.SessionMemoryConfig sm = channel.getEffectiveSessionMemoryConfig();
        SessionMemoryService.SmCompactConfig compact = channel.getEffectiveSmCompactConfig();
        if (log.isInfoEnabled()) {
            log.info("[SessionMemoryConfigController] GET 调参配置: sm={}, smCompact={}", sm, compact);
        }
        return new SessionMemoryConfigView(
            sm.minimumMessageTokensToInit(),
            sm.minimumTokensBetweenUpdate(),
            sm.toolCallsBetweenUpdates(),
            compact.minTokens(),
            compact.minTextBlockMessages(),
            compact.maxTokens());
    }

    /**
     * 更新 SM 提取阈值（tengu_sm_config 等价）· PUT /api/v1/session-memory/config/sm。
     *
     * <p>body 各字段可选：null/≤0 不覆盖当前值（CC initSessionMemoryConfigIfNeeded「仅正值覆盖」
     * 语义，sessionMemory.ts:246-262）；更新立即生效，返回更新后完整配置。
     */
    @PutMapping(value = "/sm", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SessionMemoryConfigView updateSm(@RequestBody(required = false) SessionMemoryConfigPartial partial) {
        SessionMemoryUtils.SessionMemoryConfig current = channel.getSessionMemoryConfig();
        int minInit = partial != null && partial.minimumMessageTokensToInit() != null
            ? partial.minimumMessageTokensToInit() : 0;
        int minBetween = partial != null && partial.minimumTokensBetweenUpdate() != null
            ? partial.minimumTokensBetweenUpdate() : 0;
        int toolCalls = partial != null && partial.toolCallsBetweenUpdates() != null
            ? partial.toolCallsBetweenUpdates() : 0;
        SessionMemoryUtils.SessionMemoryConfig updated =
            channel.updateSessionMemoryConfig(new SessionMemoryUtils.SessionMemoryConfig(
                minInit, minBetween, toolCalls));
        // 仅返回 sm 段，smCompact 保持当前
        SessionMemoryService.SmCompactConfig compact = channel.getSmCompactConfig();
        log.info("[SessionMemoryConfigController] PUT sm: 旧={} 新={}", current, updated);
        return new SessionMemoryConfigView(
            updated.minimumMessageTokensToInit(),
            updated.minimumTokensBetweenUpdate(),
            updated.toolCallsBetweenUpdates(),
            compact.minTokens(),
            compact.minTextBlockMessages(),
            compact.maxTokens());
    }

    /**
     * 更新 SM 压缩阈值（tengu_sm_compact_config 等价）· PUT /api/v1/session-memory/config/sm-compact。
     *
     * <p>body 各字段可选：null/≤0 不覆盖当前值（CC initSessionMemoryCompactConfig「仅正值覆盖」
     * 语义，sessionMemoryCompact.ts:113-128）；更新立即生效，返回更新后完整配置。
     */
    @PutMapping(value = "/sm-compact", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SessionMemoryConfigView updateSmCompact(@RequestBody(required = false) SmCompactConfigPartial partial) {
        SessionMemoryUtils.SessionMemoryConfig sm = channel.getSessionMemoryConfig();
        int minTokens = partial != null && partial.minTokens() != null ? partial.minTokens() : 0;
        int minTextBlocks = partial != null && partial.minTextBlockMessages() != null
            ? partial.minTextBlockMessages() : 0;
        int maxTokens = partial != null && partial.maxTokens() != null ? partial.maxTokens() : 0;
        SessionMemoryService.SmCompactConfig updated =
            channel.updateSmCompactConfig(new SessionMemoryService.SmCompactConfig(
                minTokens, minTextBlocks, maxTokens));
        log.info("[SessionMemoryConfigController] PUT sm-compact: 新={}", updated);
        return new SessionMemoryConfigView(
            sm.minimumMessageTokensToInit(),
            sm.minimumTokensBetweenUpdate(),
            sm.toolCallsBetweenUpdates(),
            updated.minTokens(),
            updated.minTextBlockMessages(),
            updated.maxTokens());
    }

    /**
     * 调参响应视图 · 合并 SM 提取（tengu_sm_config）与 SM 压缩（tengu_sm_compact_config）两段。
     *
     * @param minimumMessageTokensToInit    SM 提取·初始化阈值 · CC original: minimumMessageTokensToInit（sessionMemoryUtils.ts:19-22）
     * @param minimumTokensBetweenUpdate    SM 提取·更新增长阈值 · CC original: minimumTokensBetweenUpdate（:23-26）
     * @param toolCallsBetweenUpdates       SM 提取·工具调用阈值 · CC original: toolCallsBetweenUpdates（:27-28）
     * @param minTokens                     SM 压缩·最小保留 token · CC original: minTokens（sessionMemoryCompact.ts:48-49）
     * @param minTextBlockMessages          SM 压缩·最小文本块消息数 · CC original: minTextBlockMessages（:50-51）
     * @param maxTokens                     SM 压缩·硬上限 token · CC original: maxTokens（:52-53）
     */
    public record SessionMemoryConfigView(
        int minimumMessageTokensToInit,
        int minimumTokensBetweenUpdate,
        int toolCallsBetweenUpdates,
        int minTokens,
        int minTextBlockMessages,
        int maxTokens
    ) {}

    /**
     * SM 提取阈值部分更新请求 · CC original: Partial&lt;SessionMemoryConfig&gt;（sessionMemoryUtils.ts:18-29）。
     * 字段可选：null/≤0 不覆盖（CC「仅正值覆盖」）。null body 等价空 partial。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionMemoryConfigPartial(
        Integer minimumMessageTokensToInit,
        Integer minimumTokensBetweenUpdate,
        Integer toolCallsBetweenUpdates
    ) {}

    /**
     * SM 压缩阈值部分更新请求 · CC original: Partial&lt;SessionMemoryCompactConfig&gt;
     * （sessionMemoryCompact.ts:47-54）。字段可选：null/≤0 不覆盖。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SmCompactConfigPartial(
        Integer minTokens,
        Integer minTextBlockMessages,
        Integer maxTokens
    ) {}
}
