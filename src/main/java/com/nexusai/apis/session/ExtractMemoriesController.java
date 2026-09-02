package com.nexusai.apis.session;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.memory.ConsolidationLock;
import com.nexusai.application.agent.memory.ConsolidationPrompt;
import com.nexusai.application.agent.memory.MemoryStorage;
import com.nexusai.application.agent.skill.BundledSkillEnabledGates;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /dream 手动整合 Web 等价 REST 端点 · 对齐 CC {@code skills/bundled/dream.ts}（OPD-CM5-E-06）。
 *
 * <p><b>CC 真源（E2 逐行）</b>：{@code registerDreamSkill()}（dream.ts:19-49）——bundled
 * {@code name='dream'} skill，{@code userInvocable: true}，{@code isEnabled: () => isAutoMemoryEnabled()}
 * （dream.ts:31）。{@code getPromptForCommand(args)}（dream.ts:32-45）：
 * <ol>
 *   <li>{@code memoryRoot = getAutoMemPath()}（dream.ts:33）；</li>
 *   <li>{@code transcriptDir = getProjectDir(getOriginalCwd())}（dream.ts:34）——Java 等价会话
 *       projectRoot（{@link CwdResolution#getOriginalCwdLayer()}，MemoryController.originalCwd 同源）；</li>
 *   <li>{@code await recordConsolidation()}（dream.ts:36-37）——手动 /dream 乐观盖章锁
 *       （consolidationLock.ts:130-140，{@link ConsolidationLock#recordConsolidation()}）；</li>
 *   <li>{@code prompt = DREAM_PROMPT_PREFIX + buildConsolidationPrompt(memoryRoot, transcriptDir, '')}
 *       （dream.ts:39-40）；</li>
 *   <li>args 非空 → {@code prompt += '\n\n## Additional context from user\n\n' + args}
 *       （dream.ts:42-44）；</li>
 *   <li>返回 {@code [{type:'text', text: prompt}]} —— REST 以 text/plain 载体返回 prompt 文本。</li>
 * </ol>
 *
 * <p><b>Web 载体契约</b>（对齐 away-summary 先例，E3 ⊕-2）：CC 触发层在 REPL 斜杠命令，Web 后端无
 * 终端斜杠命令 → 本端点提供 REST 载体：前端 POST 本端点获取手动 dream prompt 文本，自行注入会话
 * 运行（等价 CC /dream 把 prompt 作为 user 消息注入）。gate = {@code isAutoMemoryEnabled()} 关闭
 * → 400（CC skill 未启用，命令不可调用）；未接线 memory 存储 → 500 fail loud（无静默降级，对齐
 * MemoryController resolveEngine 同语义）。
 *
 * <p><b>Bearer 鉴权</b>：{@code /api/agent/dream} 已纳入 {@code BearerTokenAuthFilterConfig}
 * bearer 白名单（对齐 away-summary :77 先例，前端接线即暴露须同步纳入）。
 */
@RestController
@RequestMapping("/api/agent")
public class ExtractMemoriesController {

    private static final Logger log = LoggerFactory.getLogger(ExtractMemoriesController.class);

    /**
     * CC dream.ts:15-18 DREAM_PROMPT_PREFIX —— 手动 /dream prompt 前缀（逐字对齐，含收尾空行）。
     *
     * <pre>
     * # Dream: Memory Consolidation (manual run)
     *
     * You are performing a manual dream — a reflective pass over your memory files. Unlike the automatic background dream, this run has full tool permissions and the user is watching. Synthesize what you've learned recently into durable, well-organized memories so that future sessions can orient quickly.
     *
     * </pre>
     */
    private static final String DREAM_PROMPT_PREFIX =
        "# Dream: Memory Consolidation (manual run)\n\n"
            + "You are performing a manual dream — a reflective pass over your memory files. Unlike the automatic background dream, this run has full tool permissions and the user is watching. Synthesize what you've learned recently into durable, well-organized memories so that future sessions can orient quickly.\n\n";

    /** memory 存储层（memoryRoot = {@link MemoryStorage#memoryDir()}，CC getAutoMemPath）· @Bean
     *  自动装配（ToolRegistrationConfig.memoryStorage），required=false 容错单测反射注入。 */
    @Autowired(required = false)
    private MemoryStorage memoryStorage;

    /**
     * gate 注入 seam · CC dream.ts:31 {@code isEnabled: () => isAutoMemoryEnabled()}。默认
     * {@link BundledSkillEnabledGates#isAutoMemoryEnabled()}；测试可经 setter 注入覆盖（对齐
     * AutoDreamConsolidator.setAutoMemoryEnabled 同款缝隙）。null → 默认门（不覆盖）。
     */
    private volatile java.util.function.BooleanSupplier autoMemoryEnabled =
        BundledSkillEnabledGates::isAutoMemoryEnabled;

    /**
     * 注入 isAutoMemoryEnabled gate（CC dream.ts:31；null → 恢复默认 {@link
     * BundledSkillEnabledGates#isAutoMemoryEnabled()}）。测试注入恒 true 时需同步确认不破坏
     * 生产门语义 —— 生产 @Bean 不调用本 setter，默认门即 CC isEnabled。
     */
    public void setAutoMemoryEnabled(java.util.function.BooleanSupplier enabled) {
        this.autoMemoryEnabled = enabled != null ? enabled : BundledSkillEnabledGates::isAutoMemoryEnabled;
    }

    /**
     * 生成手动 /dream prompt · POST /api/agent/dream。
     *
     * <p>流程: gate（CC dream.ts:31 isAutoMemoryEnabled）→ memoryRoot（getAutoMemPath）→
     * transcriptDir（getProjectDir(getOriginalCwd())）→ {@link ConsolidationLock#recordConsolidation()}
     * 乐观盖章 → prompt = DREAM_PROMPT_PREFIX + buildConsolidationPrompt(memoryRoot, transcriptDir, '')
     * + 可选 args。结果语义:
     * <ul>
     *   <li>prompt 文本 → 200（text/plain，前端注入会话运行手动 dream）</li>
     *   <li>auto-memory 未启用 → 400（ValidationException，CC skill isEnabled=false 命令不可调用）</li>
     *   <li>memory 存储未接线 → 500（fail loud，无静默降级）</li>
     * </ul>
     *
     * @param body POST JSON 请求体（可为空 body；{@link DreamRequest#args} 可选附加上下文，CC
     *             getPromptForCommand(args)）
     * @return 200 prompt 文本
     */
    @PostMapping(value = "/dream", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> dream(@RequestBody(required = false) DreamRequest body) {
        // gate · CC dream.ts:31 isEnabled: () => isAutoMemoryEnabled() —— skill 未启用命令不可调用
        if (!autoMemoryEnabled.getAsBoolean()) {
            log.warn("[ExtractMemoriesController] /dream 拒绝：isAutoMemoryEnabled=false（CC dream.ts:31"
                + " isEnabled gate，命令不可调用）→ 400");
            throw new ValidationException("auto-memory is disabled (manual /dream unavailable)");
        }
        MemoryStorage storage = resolveMemoryStorage();
        // CC dream.ts:33 memoryRoot = getAutoMemPath()（MemoryStorage.memoryDir 同源 per-project）
        String memoryRoot = storage.memoryDir().toString();
        // CC dream.ts:34 transcriptDir = getProjectDir(getOriginalCwd()) —— [S2] Java 等价
        //   config-home 项目 slug 目录（getOriginalCwdLayer 层做 config-home 派生）
        String transcriptDir = com.nexusai.application.agent.tool.SessionStorage
            .getProjectDir(java.nio.file.Path.of(CwdResolution.getOriginalCwdLayer())).toString();
        // CC dream.ts:36-37 await recordConsolidation() —— 手动 /dream 乐观盖章锁（best-effort）
        new ConsolidationLock(storage.memoryDir()).recordConsolidation();
        if (log.isDebugEnabled()) {
            log.debug("[ExtractMemoriesController] /dream 构建 prompt: memoryRoot={} transcriptDir={}"
                + "（已乐观盖章锁 .consolidate-lock）", memoryRoot, transcriptDir);
        }
        // CC dream.ts:39-40 prompt = DREAM_PROMPT_PREFIX + buildConsolidationPrompt(memoryRoot, transcriptDir, '')
        String basePrompt = ConsolidationPrompt.buildConsolidationPrompt(memoryRoot, transcriptDir, "");
        String prompt = DREAM_PROMPT_PREFIX + basePrompt;
        // CC dream.ts:42-44 args 非空 → 追加 "## Additional context from user"
        String args = body != null ? body.args() : null;
        if (args != null && !args.isBlank()) {
            prompt += "\n\n## Additional context from user\n\n" + args;
            if (log.isDebugEnabled()) {
                log.debug("[ExtractMemoriesController] /dream 追加用户上下文 {} 字符", args.length());
            }
        }
        if (log.isInfoEnabled()) {
            log.info("[ExtractMemoriesController] /dream 手动整合完成: prompt {} 字符（含{}用户上下文）",
                prompt.length(), (args != null && !args.isBlank()) ? "": "无");
        }
        return ResponseEntity.ok(prompt);
    }

    /**
     * 解析 memory 存储层 · 未接线 → 500（fail loud：/dream 依赖 memoryRoot 与锁盖章，无静默降级）。
     */
    private MemoryStorage resolveMemoryStorage() {
        MemoryStorage storage = memoryStorage;
        if (storage == null) {
            log.error("[ExtractMemoriesController] memoryStorage 未接线 → /dream 不可用（fail loud）");
            throw new IllegalStateException("memoryStorage not wired (ExtractMemoriesController /dream unavailable)");
        }
        return storage;
    }

    /**
     * /dream 请求体 · CC original: {@code getPromptForCommand(args)} 的可选附加上下文
     * （dream.ts:42 {@code if (args) prompt += ...}）。
     *
     * @param args 用户提供的附加上下文（可空；非空追加 "## Additional context from user" 段）
     */
    public record DreamRequest(String args) {}
}
