package com.nexusai.application.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆提示加载器 · 对齐 CC memdir.ts:419-507 loadMemoryPrompt()。
 *
 * <p>真实三路分发逻辑在 {@link MemoryPromptBuilder#loadMemoryPrompt()}（KAIROS → team → auto →
 * null，disabled 返回 null）。本类无 @Bean 无组件注解，不经过 Spring 注册：由 LlmAgentLoop 以
 * {@code new LoadMemoryPrompt(MemoryPromptBuilder.productionDefault(tel, kairos, team, mothCopse))}
 * 四参全量接线直接装配（kairos=NEW-6 部署标志 / team=IMP-MV2-19 FeatureFlags 双门控 /
 * mothCopse=IMP-MV2-12 tenguMothCopse，LlmAgentLoop buildSystemPromptAssemblyInput 生产装配点），
 * 经 SystemPromptAssemblyInput.memoryLoader 供
 * SystemPromptSections.memoryCompute 消费。旧假接线（方法 0 调用点 + 泛化 "# Memory" 索引块 +
 * MemoryIndex.applyConstraints 路径）已删除（DEL-M-02 / DEL-M-40）。
 *
 * <p><b>双契约（merge worktree-memory-align × system_prompt）</b>：
 * <ol>
 *   <li>A 契约（memory 模块 IMP-M-P0-2）：{@link #loadMemoryPrompt()} → String（disabled→null，
 *       CC memdir.ts:419-507 三路分发）—— LlmAgentLoop buildPromptContext / MemoryPromptBuilder 调用方。</li>
 *   <li>B 契约（system_prompt 架构 IMP-SP-05）：{@link #loadMemoryPromptAttachments()} →
 *       {@code List<MemoryAttachment>} + {@link #formatForSystemPrompt(List)} → String ——
 *       SystemPromptSections.memoryCompute 消费（prompts.ts:495-496）。</li>
 * </ol>
 *
 * <p>两契约共享同一分发源：B 契约的 attachment 由 A 契约的 String 结果包裹（不重建旧
 * {@code "# Memory + MEMORY.md 索引"}泛化块，DEL-M-01/02 已删；MEMORY.md 索引由模型维护，
 * DEL-M-04）。
 */
public class LoadMemoryPrompt {

    private static final Logger log = LoggerFactory.getLogger(LoadMemoryPrompt.class);

    private final MemoryPromptBuilder builder;

    public LoadMemoryPrompt(MemoryPromptBuilder builder) {
        this.builder = builder;
    }

    /**
     * 加载记忆行为指令 prompt（真实分发）· CC original: {@code loadMemoryPrompt}（memdir.ts:419-507）。
     *
     * @return 行为指令 prompt 文本；auto memory 禁用时返回 {@code null}（INV-3 disabled→null）
     */
    public String loadMemoryPrompt() {
        String prompt = builder.loadMemoryPrompt();
        if (log.isDebugEnabled()) {
            log.debug("[LoadMemoryPrompt] 分发结果: {}", prompt == null ? "null(disabled)" : prompt.length() + " chars");
        }
        return prompt;
    }

    /**
     * 加载记忆附件（B 契约）· 供 SystemPromptSections.memoryCompute 消费。
     *
     * <p>内部委托 {@link #loadMemoryPrompt()}（同一分发源）：非 null → 单个 attachment；
     * disabled（null）→ 空列表（memory section 不产生内容）。
     *
     * <p>原 {@code loadMemoryPrompt(PromptContext)} 的 ctx 参数已删除（DC-V5-14：B 契约 ctx 未消费，
     * 多会话记忆隔离由 AutoMemPaths ThreadLocal projectRoot 保证，不依赖 PromptContext）。
     *
     * @return 记忆附件列表（可为空）
     */
    public List<MemoryAttachment> loadMemoryPromptAttachments() {
        String prompt = loadMemoryPrompt();
        if (prompt == null) {
            return List.of();
        }
        return List.of(new MemoryAttachment("memory_prompt", prompt));
    }

    /**
     * 将记忆附件格式化为 system prompt 文本（B 契约）。
     *
     * <p>A 契约的 prompt 已是完整 CC 行为指令段（buildMemoryLines 等价，DEL-M-02），这里直接拼接
     * 附件内容、不再包裹旧 {@code "# Memory + index"} 泛化块（DEL-M-01/02）；空 → null
     * （SystemPromptSections.memoryCompute 判空跳过该 section）。
     *
     * @param attachments 记忆附件列表
     * @return 可直接注入 system prompt 的文本；空附件 → null
     */
    public String formatForSystemPrompt(List<MemoryAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }
        String joined = attachments.stream()
            .map(MemoryAttachment::content)
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.joining("\n\n"));
        if (joined.isBlank()) {
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("[LoadMemoryPrompt] formatForSystemPrompt: {} attachments → {} chars",
                attachments.size(), joined.length());
        }
        return joined;
    }

    // ── records（B 契约 · SystemPromptSections / 未来 MemoryPrefetcher 消费）──

    /** 记忆附件 */
    public record MemoryAttachment(String source, String content) {}
}
