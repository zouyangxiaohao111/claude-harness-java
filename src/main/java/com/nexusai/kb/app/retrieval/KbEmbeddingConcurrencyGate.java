package com.nexusai.kb.app.retrieval;

import com.nexusai.repository.provider.entity.ModelRecord;
import com.nexusai.repository.provider.mapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 后台向量化的「每模型并发闸」· 对齐源站 {@code limiter.GateNamedN}。
 *
 * <p><b>WHY 需要它</b>（2026-11-14 用户实测 429）：
 * 入库并发是 {@code parse=8 / postprocess=2 / enrichment=12}，每个文档的向量化在自己的线程里
 * <b>串行</b>发批次；12 个文档同时入库 = 12 路并发打同一个 embedding endpoint。
 * 客户端内部怎么看都是串行，但 provider 侧看到的是 12 路并发 ⇒ 撞 TPM/RPM 配额直接 429。
 *
 * <p><b>与源站的对齐关系</b>：
 * <ul>
 *   <li>源站 {@code internal/models/limiter/governor.go} 是进程级 governor，每模型信号量；</li>
 *   <li>限值来自模型配置 {@code model.max_concurrency}（本仓 {@code models.max_concurrency}，V291），
 *       0/空 = 全局默认；源站默认 32（{@code internal/container/container.go:914}），本仓同值；</li>
 *   <li>源站只掐<b>后台任务</b>（{@code types.IsBackgroundTask(ctx)}）。本仓没有该上下文标记，
 *       故闸只放在后台调用点（{@code KbIndexWriter} 向量化 / {@code KbImageVectorService} 后台编码），
 *       <b>⛔ 不放进 {@code KbEmbeddingPort}</b> —— 那会连交互式问答的查询编码一起掐住。</li>
 * </ul>
 *
 * <p><b>实时生效</b>：每次 {@link #call} 都重新读 {@code models.max_concurrency}（一行主键查询），
 * 用户在模型编辑弹窗改完立刻生效、无需重启（源站同样实时读取）。
 *
 * <p><b>失败语义</b>：等槽被中断 ⇒ 恢复中断位并抛 {@link IllegalStateException}（fail loud，
 * ⛔ 不吞）。等槽期间不设超时：入库是后台任务，排队比 429 好。
 */
@Component
public class KbEmbeddingConcurrencyGate {

    private static final Logger log = LoggerFactory.getLogger(KbEmbeddingConcurrencyGate.class);

    /** 全局默认并发上限 · 与源站 {@code defaultModelMaxConcurrency} 同值。 */
    public static final int DEFAULT_MAX_CONCURRENCY = 32;

    /**
     * 模型查询器（字段注入 + {@code required=false}）。
     *
     * <p>为什么不用构造器参数：{@link KbIndexWriter} 等调用方有既有测试构造器，
     * 加参数会让所有直构点编译失败；本类又允许在非 Spring 单测里 {@code new} 出来（mapper 为 null
     * ⇒ 不限流，行为与接入前逐字相同）。
     */
    @Autowired(required = false)
    private ModelMapper modelMapper;

    /**
     * 全局设置的读取器（字段注入 · 与 {@link #modelMapper} 同理，单测可不装配）。
     *
     * <p>源站的全局默认来自系统设置 {@code model.max_concurrency}
     * （{@code internal/container/container.go:920 resolveModelMaxConcurrency}），
     * 本仓对应 {@code settings.model_max_concurrency}（V292）。
     */
    @Autowired(required = false)
    private com.nexusai.repository.settings.mapper.SettingsMapper settingsMapper;

    /** modelId → 信号量（容量变化时重建）。 */
    private final Map<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    /** modelId → 上次见到的容量（用于检测配置变更后重建信号量）。 */
    private final Map<String, Integer> limits = new ConcurrentHashMap<>();

    /**
     * 在并发闸内执行一次向量化调用。
     *
     * @param modelId 嵌入模型 ID（null/空 ⇒ 不限制，直接执行）
     * @param body    实际调用
     * @return body 的返回值
     */
    public <T> T call(String modelId, Supplier<T> body) {
        Semaphore semaphore = semaphoreFor(modelId);
        if (semaphore == null) {
            return body.get();
        }
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 embedding 并发槽被中断: model=" + modelId, e);
        }
        try {
            return body.get();
        } finally {
            semaphore.release();
        }
    }

    /**
     * 取该模型的信号量。
     *
     * @return {@code null} = 不限制（模型未配置负数 / 查不到模型时按全局默认，仍受限）
     */
    private Semaphore semaphoreFor(String modelId) {
        int limit = resolveLimit(modelId);
        if (limit <= 0) {
            return null;
        }
        Integer previous = limits.put(modelId, limit);
        if (previous != null && previous != limit) {
            // 用户在模型编辑里改了并发上限 ⇒ 换一个新的信号量。
            // 旧信号量上仍在跑的调用结束后自然释放，不需要等待。
            semaphores.remove(modelId);
            log.info("[embedding 并发闸] 上限变更: model={} {} → {}", modelId, previous, limit);
        }
        return semaphores.computeIfAbsent(modelId, key -> new Semaphore(limit));
    }

    /**
     * 读模型级并发上限（未配置时用全局默认，见 {@link #resolveGlobalDefault()}）。
     *
     * @return &gt;0 = 上限；0 = 不限制（显式负数）；查不到 / 未配置 ⇒ 全局默认
     */
    private int resolveLimit(String modelId) {
        if (modelMapper == null || modelId == null || modelId.isBlank()) {
            return resolveGlobalDefault();
        }
        try {
            ModelRecord row = modelMapper.selectOneById(modelId);
            if (row == null || row.getMaxConcurrency() == null) {
                return resolveGlobalDefault();
            }
            int configured = row.getMaxConcurrency();
            // 负数 = 关闭限制（与源站「0 或负数 = 关闭默认限制」同义；0 视为未配置 ⇒ 用全局默认）
            if (configured < 0) {
                return 0;
            }
            return configured == 0 ? resolveGlobalDefault() : configured;
        } catch (RuntimeException e) {
            // 读配置失败 ⇒ 用全局默认（宁可慢，不可无闸把配额打爆）
            log.warn("[embedding 并发闸] 读模型配置失败，按全局默认处理: model={} 原因={}",
                modelId, e.toString());
            return resolveGlobalDefault();
        }
    }

    /**
     * 全局默认并发上限 · 实时读 {@code settings.model_max_concurrency}（V292）。
     *
     * <p>与源站一致：每次调用现读，系统设置里改完立刻生效、无需重启。
     * 未配置 / ≤0 ⇒ 常量 {@link #DEFAULT_MAX_CONCURRENCY}（32，源站同值）。
     */
    int resolveGlobalDefault() {
        if (settingsMapper == null) {
            return DEFAULT_MAX_CONCURRENCY;
        }
        try {
            var rows = settingsMapper.selectAll();
            if (rows == null || rows.isEmpty()) {
                return DEFAULT_MAX_CONCURRENCY;
            }
            Integer configured = rows.get(0).getModelMaxConcurrency();
            return configured == null || configured <= 0 ? DEFAULT_MAX_CONCURRENCY : configured;
        } catch (RuntimeException e) {
            log.warn("[embedding 并发闸] 读全局设置失败，按常量默认 {} 处理: {}",
                DEFAULT_MAX_CONCURRENCY, e.toString());
            return DEFAULT_MAX_CONCURRENCY;
        }
    }
}
