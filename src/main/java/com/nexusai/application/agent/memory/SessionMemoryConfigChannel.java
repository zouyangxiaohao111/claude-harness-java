package com.nexusai.application.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * SM 阈值 Web 调参通道（IMP-CM-35 / OPD-CM3-14 DEC-CM3-C01）· 替代 CC GrowthBook 远端
 * 配置通道（tengu_sm_config / tengu_sm_compact_config）的本地 Web 调参入口。
 *
 * <p><b>CC 真源（grep 自验 2026-08-15）</b>：
 * <ul>
 *   <li>{@code getSessionMemoryRemoteConfig()}（sessionMemory.ts:88-93）读 GrowthBook
 *       dynamic config {@code 'tengu_sm_config'}（cached，默认 {}）→ {@code initSessionMemoryConfigIfNeeded}
 *       （sessionMemory.ts:240-264）memoize 一次，<b>仅正值覆盖</b>（0/缺省 → DEFAULT）。</li>
 *   <li>{@code initSessionMemoryCompactConfig()}（sessionMemoryCompact.ts:98-130）读
 *       {@code 'tengu_sm_compact_config'}（每 session 一次），<b>仅正值覆盖</b>（:113-128）。</li>
 * </ul>
 *
 * <p><b>Java 建模</b>：本项目无 GrowthBook 远端，OPD-CM3-14 拍板以 Web REST 端点 + 本通道存储
 * 建模该远端通道。本类为<b>运行时状态持有者</b>：
 *   <li>SM 提取阈值（tengu_sm_config 等价）→ 经本类 {@link #updateSessionMemoryConfig} 读入点
 *       「仅正值覆盖」过滤后写 {@link SessionMemoryUtils#setSessionMemoryConfig}
 *       （静态模块态，纯 merge；[IMP-MV2-33] 过滤层位置修复：过滤自 setter 内嵌位移入本层）；</li>
 *   <li>SM 压缩阈值（tengu_sm_compact_config 等价）→ 写
 *       {@link SessionMemoryService#setSmCompactConfig}（实例态，getSmCompactConfig 新增读端）。</li>
 * </ul>
 *   [F-9 登记 · IMP-MV2-40] △-9 GrowthBook 通道（拍板 OPD-CM3-14）：CC sessionMemory.ts:88-93
 *   读远端 dynamic config；Java 以 Web REST 端点 + 本通道存储建模（本地通道替代）—— 登记声明。
 * 消费侧（IMP-CM-03）经本通道读取动态值；通道未配置（从未 PUT）时读端返回 DEFAULT（回退语义）。
 *
 * <p><b>日志</b>：sfl4j + logback，中文，debug 门控（CLAUDE.md 数据流日志规范）。
 */
@Component
public class SessionMemoryConfigChannel {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryConfigChannel.class);

    /**
     * SM 压缩配置运行期持有者（SessionMemoryService 单例 bean · ToolRegistrationConfig.java:1147）。
     * null = 未接线（测试 / 降级）→ smCompact 读端返回 DEFAULT，写端 fail-loud。
     */
    @Nullable
    @Autowired(required = false)
    private SessionMemoryService sessionMemoryService;

    /** 注入运行期持有者（测试用 / ToolRegistrationConfig 接线）。 */
    public void setSessionMemoryService(@Nullable SessionMemoryService sessionMemoryService) {
        this.sessionMemoryService = sessionMemoryService;
    }

    // ════════════════════════════════════════════════════════════════════
    // 读端 · GET /api/v1/session-memory/config
    // ════════════════════════════════════════════════════════════════════

    /**
     * 读取当前 SM 提取配置 · CC original: {@code getSessionMemoryConfig()}
     * （sessionMemoryUtils.ts:143-145）。通道未配置 → DEFAULT（10000, 5000, 3）。
     *
     * @return 当前生效 SM 提取阈值（副本）
     */
    public SessionMemoryUtils.SessionMemoryConfig getSessionMemoryConfig() {
        return SessionMemoryUtils.getSessionMemoryConfig();
    }

    /**
     * 读取当前 SM 压缩配置 · CC original: {@code getSessionMemoryCompactConfig()}
     * （sessionMemoryCompact.ts:86-88）。通道未配置 / 运行期持有者未接线 → DEFAULT（10000, 5, 40000）。
     *
     * @return 当前生效 SM 压缩阈值（副本）
     */
    public SessionMemoryService.SmCompactConfig getSmCompactConfig() {
        SessionMemoryService svc = sessionMemoryService;
        if (svc == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemoryConfigChannel] sessionMemoryService 未接线，smCompact 返回 DEFAULT");
            }
            return SessionMemoryService.SmCompactConfig.DEFAULT;
        }
        return svc.getSmCompactConfig();
    }

    /**
     * 读取当前<b>生效</b> SM 提取配置（DB &gt; 内存通道 &gt; DEFAULT）· [V52 token-compact-settings-fix]。
     *
     * <p>区别于 {@link #getSessionMemoryConfig()}（Web 内存通道值）：本方法经
     * {@link SessionMemoryUtils#getEffectiveSessionMemoryConfig()}（sessionMemoryUtils.ts 阈值谓词同源）
     * 返回 DB 合并生效值——DB settings 非 null 字段覆盖，未配置字段回落内存通道值；DB 未接线
     * （settingsResolver null）→ 恒返回内存通道值（零行为变化）。
     *
     * <p><b>GET 端点读本方法反映 DB 生效值；PUT 合并基值仍用 {@link #getSessionMemoryConfig()}
     * （内存通道）</b>，防止 DB 生效值经 PUT 合并固化进内存通道污染通道。
     *
     * @return 当前生效 SM 提取阈值（DB &gt; 内存 &gt; 默认，副本）
     */
    public SessionMemoryUtils.SessionMemoryConfig getEffectiveSessionMemoryConfig() {
        return SessionMemoryUtils.getEffectiveSessionMemoryConfig();
    }

    /**
     * 读取当前<b>生效</b> SM 压缩配置（DB &gt; Web 调参通道 &gt; DEFAULT）· [V52 token-compact-settings-fix]。
     *
     * <p>区别于 {@link #getSmCompactConfig()}（Web 内存通道值）：本方法经
     * {@link SessionMemoryService#resolveSmCompactConfig()}（calculateMessagesToKeepIndex 消费点同源）
     * 返回 DB 合并生效值——DB 逐字段覆盖，未配置字段回落内存通道值。通道未配置 / 运行期持有者
     * 未接线 → DEFAULT（与 {@link #getSmCompactConfig()} 同回退语义）。
     *
     * @return 当前生效 SM 压缩阈值（DB &gt; Web &gt; 默认，永不 null）
     */
    public SessionMemoryService.SmCompactConfig getEffectiveSmCompactConfig() {
        SessionMemoryService svc = sessionMemoryService;
        if (svc == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SessionMemoryConfigChannel] sessionMemoryService 未接线，生效 smCompact 返回 DEFAULT");
            }
            return SessionMemoryService.SmCompactConfig.DEFAULT;
        }
        return svc.resolveSmCompactConfig();
    }

    // ════════════════════════════════════════════════════════════════════
    // 写端 · PUT /api/v1/session-memory/config/{sm,sm-compact}
    // ════════════════════════════════════════════════════════════════════

    /**
     * 更新 SM 提取配置 · CC original: {@code initSessionMemoryConfigIfNeeded} 的
     * 「仅正值覆盖」语义（sessionMemory.ts:246-262）：partial 中 ≤0 的字段保留当前值。
     * 本方法为过滤读入点（[IMP-MV2-33] 过滤层位置修复：过滤自
     * {@link SessionMemoryUtils#setSessionMemoryConfig} 内嵌位移入本层，对齐 CC 纯 merge + init 层过滤），
     * 过滤后经 {@link SessionMemoryUtils#setSessionMemoryConfig}（纯 merge，静态模块态）写入 →
     * 运行期消费点（hasMetInitializationThreshold / hasMetUpdateThreshold / getToolCallsBetweenUpdates）
     * 立即读新值（不重启）。
     * <p>[OPD-CM5-B-03] setSessionMemoryConfig 部分合并：v5 决策确认本通道「部分 merge + 仅正值覆盖」
     *   为 CC 等价实现（sessionMemoryUtils.ts:131-138 纯 merge {@code {...cur, ...config}} +
     *   sessionMemory.ts:246-262 init 层仅正值过滤），只改传入（正）字段、未传（≤0）字段保留当前值。
     *
     * @param partial 部分阈值（≤0 的字段忽略；null → 保持当前，等价旧 setter null 忽略）
     * @return 更新后的完整配置
     */
    public SessionMemoryUtils.SessionMemoryConfig updateSessionMemoryConfig(
            SessionMemoryUtils.SessionMemoryConfig partial) {
        SessionMemoryUtils.SessionMemoryConfig current = SessionMemoryUtils.getSessionMemoryConfig();
        if (partial == null) {
            return current;
        }
        SessionMemoryUtils.SessionMemoryConfig merged = new SessionMemoryUtils.SessionMemoryConfig(
            partial.minimumMessageTokensToInit() > 0
                ? partial.minimumMessageTokensToInit()
                : current.minimumMessageTokensToInit(),
            partial.minimumTokensBetweenUpdate() > 0
                ? partial.minimumTokensBetweenUpdate()
                : current.minimumTokensBetweenUpdate(),
            partial.toolCallsBetweenUpdates() > 0
                ? partial.toolCallsBetweenUpdates()
                : current.toolCallsBetweenUpdates());
        SessionMemoryUtils.setSessionMemoryConfig(merged);
        if (log.isInfoEnabled()) {
            log.info("[SessionMemoryConfigChannel] 更新 SM 提取配置: {}", merged);
        }
        return merged;
    }

    /**
     * 更新 SM 压缩配置 · CC original: {@code initSessionMemoryCompactConfig} 的
     * 「仅正值覆盖」语义（sessionMemoryCompact.ts:113-128）：partial 中 ≤0 的字段保留当前值。
     * 经 {@link SessionMemoryService#setSmCompactConfig}（实例态）写入 → 运行期消费点
     * （SessionMemoryService.calculateMessagesToKeepIndex）立即读新值（不重启）。
     *
     * @param partial 部分阈值（≤0 的字段忽略）
     * <p>[F-12 登记 · IMP-MV2-40] △-12 sm-compact 一次性（拍板 OPD-CM3-14，同 F-10 语义）：
     *   CC initSessionMemoryCompactConfig 每 session 拉取一次；Java 可重复 PUT 仅正值覆盖
     *   （sessionMemoryCompact.ts:113-128 语义）—— 一次性面 △ 接受，登记声明。
     * @return 更新后的完整配置
     * @throws IllegalStateException sessionMemoryService 未接线（fail-loud，无静默降级）
     */
    public SessionMemoryService.SmCompactConfig updateSmCompactConfig(
            SessionMemoryService.SmCompactConfig partial) {
        SessionMemoryService svc = sessionMemoryService;
        if (svc == null) {
            log.error("[SessionMemoryConfigChannel] sessionMemoryService 未接线，无法写入 SM 压缩配置（fail-loud）");
            throw new IllegalStateException(
                "SessionMemoryService 未接线，SM 压缩配置通道不可用（ToolRegistrationConfig 需注入）");
        }
        SessionMemoryService.SmCompactConfig current = svc.getSmCompactConfig();
        SessionMemoryService.SmCompactConfig merged = new SessionMemoryService.SmCompactConfig(
            partial.minTokens() > 0 ? partial.minTokens() : current.minTokens(),
            partial.minTextBlockMessages() > 0 ? partial.minTextBlockMessages() : current.minTextBlockMessages(),
            partial.maxTokens() > 0 ? partial.maxTokens() : current.maxTokens());
        svc.setSmCompactConfig(merged);
        if (log.isInfoEnabled()) {
            log.info("[SessionMemoryConfigChannel] 更新 SM 压缩配置: {}", merged);
        }
        return merged;
    }

    /** 重置为 DEFAULT（测试用）· 对齐 CC resetSessionMemoryCompactConfig（sessionMemoryCompact.ts:93-96）。 */
    public void resetToDefaults() {
        SessionMemoryService svc = sessionMemoryService;
        if (svc != null) {
            svc.setSmCompactConfig(SessionMemoryService.SmCompactConfig.DEFAULT);
        }
        SessionMemoryUtils.resetSessionMemoryState();
    }
}
