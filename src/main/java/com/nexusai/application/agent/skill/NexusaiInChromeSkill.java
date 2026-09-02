package com.nexusai.application.agent.skill;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /nexusai-in-chrome skill · 对齐 CC skills/bundled/claudeInChrome.ts.
 *
 * <p>L1 语义: Chrome 浏览器自动化 skill 注册 — 通过 @ant/claude-for-chrome-mcp 暴露
 *            browser tools (click, fill form, screenshot, console logs, navigate);
 *            auto-enable 检测;userInvocable=true.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: SKILL_NAME='nexusai-in-chrome'; SKILL_DESCRIPTION constant;
 *       ToolFilter record (allowedTools); 3 method (registerSkill + shouldAutoEnable + formatPrompt).</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — registerBundledSkill(name, description, allowedTools, isEnabled) →
 *       getPromptForCommand(args) → BASE_CHROME_PROMPT + SKILL_ACTIVATION + ## Task args.</li>
 *   <li><b>A3</b>: 注入式 (basePromptSupplier + shouldAutoEnableSupplier);纯函数 formatPrompt.</li>
 *   <li><b>A4</b>: null args → 无 Task 段;null basePrompt → 仅 SKILL_ACTIVATION.</li>
 *   <li><b>A5</b>: 真实场景 — 用户 "/nexusai-in-chrome 打开 google.com" → browser tool activation + task.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS registerBundledSkill → Java 注入式 register 回调;
 *                    TS template literal → Java text block.
 */
public final class NexusaiInChromeSkill {

    private static final Logger log = LoggerFactory.getLogger(NexusaiInChromeSkill.class);

    public static final String SKILL_NAME = "nexusai-in-chrome";
    public static final String SKILL_DESCRIPTION =
        "Automates your Chrome browser to interact with web pages - clicking elements, "
        + "filling forms, capturing screenshots, reading console logs, and navigating sites. "
        + "Opens pages in new tabs within your existing Chrome session. Requires site-level "
        + "permissions before executing (configured in the extension).";
    public static final String SKILL_WHEN_TO_USE =
        "When the user wants to interact with web pages, automate browser tasks, capture "
        + "screenshots, read console logs, or perform any browser-based actions. Always invoke "
        + "BEFORE attempting to use any mcp__nexusai-in-chrome__* tools.";
    public static final String SKILL_ACTIVATION_MESSAGE =
        "Now that this skill is invoked, you have access to Chrome browser automation tools. "
        + "You can now use the mcp__nexusai-in-chrome__* tools to interact with web pages.\n\n"
        + "IMPORTANT: Start by calling mcp__nexusai-in-chrome__tabs_context_mcp to get information "
        + "about the user's current browser tabs.";
    public static final String TOOL_PREFIX = "mcp__nexusai-in-chrome__";

    private final Supplier<String> basePromptSupplier;
    private final Supplier<Boolean> shouldAutoEnableSupplier;
    private final Consumer<BundledSkillDefinition> registrar;

    public NexusaiInChromeSkill(Supplier<String> basePromptSupplier,
            Supplier<Boolean> shouldAutoEnableSupplier,
            Consumer<BundledSkillDefinition> registrar) {
        this.basePromptSupplier = Objects.requireNonNull(basePromptSupplier);
        this.shouldAutoEnableSupplier = shouldAutoEnableSupplier == null
            ? (Supplier<Boolean>) () -> false
            : shouldAutoEnableSupplier;
        if (registrar == null) {
            this.registrar = def -> {};
        } else {
            this.registrar = registrar;
        }
    }

    public NexusaiInChromeSkill() {
        this(() -> "", () -> false, null);
    }

    /** CC registerClaudeInChromeSkill 主链 · 统一产出 BundledSkillDefinition（P1-4）. */
    public void registerSkill(List<String> browserToolNames) {
        List<String> prefixed = new java.util.ArrayList<>();
        if (browserToolNames != null) {
            for (String t : browserToolNames) prefixed.add(TOOL_PREFIX + t);
        }
        BundledSkillDefinition def = new BundledSkillDefinition(
            SKILL_NAME,
            SKILL_DESCRIPTION,
            null,   // aliases
            SKILL_WHEN_TO_USE,
            null,   // argumentHint
            prefixed,   // allowedTools (CC claudeInChrome.ts:23 CLAUDE_IN_CHROME_MCP_TOOLS)
            null,   // model
            null,   // disableModelInvocation (CC undefined → default false)
            true,   // userInvocable (CC claudeInChrome.ts:24)
            this::shouldAutoEnable,   // isEnabled (CC claudeInChrome.ts:25 isEnabled: () => shouldAutoEnableClaudeInChrome() 惰性判定)
            null,   // hooks
            null,   // context
            null,   // agent
            null,   // files
            (args, cwd) -> formatPrompt(args)
        );
        registrar.accept(def);
    }

    public boolean shouldAutoEnable() {
        return Boolean.TRUE.equals(shouldAutoEnableSupplier.get());
    }

    /** CC getPromptForCommand 纯函数. */
    public List<PromptBlock> formatPrompt(String args) {
        StringBuilder sb = new StringBuilder();
        String base = basePromptSupplier.get();
        if (base != null && !base.isEmpty()) sb.append(base).append('\n');
        sb.append(SKILL_ACTIVATION_MESSAGE);
        if (args != null && !args.isBlank()) {
            sb.append("\n\n## Task\n\n").append(args);
        }
        return List.of(PromptBlock.text(sb.toString()));
    }
}