package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.memory.AutoMemPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * system prompt section 注册表 · 对齐 CC {@code resolveSystemPromptSections}
 * （Open-ClaudeCode/src/constants/systemPromptSections.ts:43-58）。
 *
 * <p>CC 的 section 列表是静态常量（{@code sections} 数组）；Java 以可变注册表承载，
 * 由 IMP-SP-03/05 的真实注册（13 条）与测试的合成 section 填充；resolveAll 唯一直接
 * 消费方为 {@link SystemPromptAssembler#assemble}（SystemPromptAssembler.java:77）；
 * assemble 调用方 = LlmAgentLoop :2268/:3156、PartialCompactService :345、
 * ToolRegistrationConfig :1565、ContextAnalyzeService :355、ResumeService :416
 * （grep -n 自验 2026-08-12）。
 *
 * <p>resolve 语义逐条对齐 CC（systemPromptSections.ts:50-55）：
 * <ol>
 *   <li>短路唯一前提 {@code !cacheBreak && cache.has(name)}（I-1 命中不重算，I-2 cacheBreak=true 恒重算）；</li>
 *   <li>命中返回缓存值（含 null，I-3 读侧）；</li>
 *   <li>compute 后无条件写回缓存含 null（I-3 写侧，set 不判空）；</li>
 *   <li>并行计算 + 结果按输入序（CC {@code Promise.all}，Java 以
 *       {@link CompletableFuture}[] + {@link CompletableFuture#allOf(CompletableFuture...)}
 *       按索引收集等价）。</li>
 * </ol>
 *
 * <p><b>谁可以 cacheBreak=true</b>：段名必须在 {@link SystemPromptCacheBreakWhitelist} 登记
 * （构造期 fail loud，见 {@link SystemPromptSection} 紧凑构造器）—— 对齐目标 CC <b>2.1.278</b>
 * 实测 {@code cacheBreak:true} <b>0 处</b>，故本仓白名单<b>当前为空表</b> ⇒
 * 上面第 1 条里的 {@code cacheBreak=true} 分支（I-2「恒重算」）在本仓<b>结构化不可达</b>
 * （没有任何段能构造出 {@code cacheBreak=true}）；该分支代码保留为机制，⛔ 不是遗漏。
 */
public class SystemPromptSectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptSectionRegistry.class);

    private final List<SystemPromptSection> sections = new ArrayList<>();

    /**
     * 追加注册 section · 对齐 CC section 常量数组的元素（systemPromptSections.ts:43）。
     *
     * <p>name 重复时按注册顺序，后者覆盖前者（resolve 按输入序，同名命中短路
     * 取先注册者缓存值）。
     *
     * @param section 待注册 section（name/compute/cacheBreak）
     */
    public void register(SystemPromptSection section) {
        if (section == null) {
            throw new IllegalArgumentException("注册的 SystemPromptSection 不能为 null");
        }
        sections.add(section);
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptSectionRegistry] 注册 section: name={}, cacheBreak={}", section.name(), section.cacheBreak());
        }
    }

    /**
     * 并行解析全部已注册 section · 对齐 CC {@code resolveSystemPromptSections}
     * （CC original: {@code Promise.all(sections.map(async s => ...))} 并行 + 输入序
     * (systemPromptSections.ts:43-58)）。
     *
     * <p>每条独立语义（systemPromptSections.ts:50-55）：
     * <pre>{@code
     * if (!s.cacheBreak && cache.has(s.name)) return cache.get(s.name) ?? null;  // 短路命中
     * const value = await s.compute();                                           // 计算
     * setSystemPromptSectionCacheEntry(s.name, value)                            // 无条件写回含 null
     * return value;
     * }</pre>
     *
     * <p>并发：compute 并行执行，写回 synchronizedMap（{@link SystemPromptSectionCache}）
     * 线程安全；结果数组按注册序收集，与输入序一致。
     *
     * @param cache 会话级缓存（由 {@code SessionPromptCacheStore#sectionCache()} 提供，
     *              跨 run 复用；参数注入同时使跨会话隔离可测）
     * @return 与注册序一致的解析结果数组（null 段也入列）
     */
    public List<String> resolveAll(SystemPromptSectionCache cache) {
        // [批 4b-1] 原「请求线程快照 projectRoot → 每 worker set/restore 回放」块已删：
        //   AutoMemPaths.CURRENT_PROJECT_ROOT ThreadLocal 载体删除后无可回放对象（用户铁律：
        //   会话态一律显式传参，回放不算合规）。memory section 的项目根现由
        //   LoadMemoryPrompt 构造期显式携带的 sessionProjectRoot 提供（LlmAgentLoop 装配点），
        //   不再依赖 compute 线程的隐式环境态。
        CompletableFuture<String>[] futures = new CompletableFuture[sections.size()];
        for (int i = 0; i < sections.size(); i++) {
            final SystemPromptSection s = sections.get(i);
            futures[i] = CompletableFuture.supplyAsync(() -> {
                if (!s.cacheBreak() && cache.has(s.name())) {
                    if (log.isDebugEnabled()) {
                        // 数据流日志（命中分支）：段名 + 命中值长度，证明「同一会话第二次 run 起走缓存」
                        String hit = cache.get(s.name());
                        log.debug("[SystemPromptSectionRegistry] 会话级缓存命中，跳过 compute: name={}, cacheBreak={}, 命中值长度={}",
                            s.name(), s.cacheBreak(), hit == null ? 0 : hit.length());
                    }
                    return cache.get(s.name());
                }
                if (log.isDebugEnabled()) {
                    // cacheBreak=true 走到这里不是「未命中」而是「每轮强制重算」：只写不读
                    // （CC systemPromptSections.ts:50-54 跳过 cache 读、仍无条件写回）。
                    log.debug("[SystemPromptSectionRegistry] {}: name={}, cacheBreak={}（cacheBreak=true ⇒ 每轮重算并覆盖缓存）",
                        cache.has(s.name()) ? "cacheBreak 重算（已有旧值被覆盖）" : "缓存未命中，开始 compute",
                        s.name(), s.cacheBreak());
                }
                String value = s.compute().compute().join();
                cache.set(s.name(), value);
                if (log.isDebugEnabled()) {
                    // 数据流日志（首次计算分支）：段名 + 计算值长度（null 记 0）
                    log.debug("[SystemPromptSectionRegistry] 首次计算完成并写回缓存: name={}, 计算值长度={}",
                        s.name(), value == null ? 0 : value.length());
                }
                return value;
            });
        }
        CompletableFuture.allOf(futures).join();
        List<String> results = new ArrayList<>(futures.length);
        for (CompletableFuture<String> f : futures) {
            results.add(f.join());
        }
        if (log.isDebugEnabled()) {
            log.debug("[SystemPromptSectionRegistry] resolve 完成: {} 条，结果按注册序返回；缓存现有 {} 条",
                results.size(), cache.size());
        }
        return results;
    }

}
