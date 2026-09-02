package com.nexusai.application.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.compact.BoundaryReader;
import com.nexusai.model.command.PromptFnContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /skillify skill 注册器 · 对齐 CC skills/bundled/skillify.ts.
 *
 * <p>L1 语义: ant-only /skillify skill — 把当前 session 的可重复过程捕获为 SKILL.md.
 *            - 非 ant → no-op (不注册).
 *            - getPromptForCommand → SKILLIFY_PROMPT 模板 + sessionMemory + userMessages 替换.
 *            - 5 步流程: analyze session → interview user (4 rounds) → write SKILL.md → confirm.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: registerSkillifySkill() → void; SKILLIFY_PROMPT 完整还原 CC skillify.ts:22-156
 *       （Step1 分析 / Step2 四 rounds 面试 / Step3 完整 SKILL.md 模板 + Per-step annotations +
 *       Step structure tips + Frontmatter rules / Step4 Confirm and Save）;
 *       7 allowedTools; userInvocable + disableModelInvocation; 3 placeholders.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — USER_TYPE=ant → register;getPromptForCommand(args) →
 *       替换 {{userDescriptionBlock}}/{{sessionMemory}}/{{userMessages}} → 返回 prompt.</li>
 *   <li><b>A3</b>: 状态: NOT_REGISTERED (non-ant) / REGISTERED (ant).</li>
 *   <li><b>A4</b>: non-ant → no register;args trim → 空 userDescriptionBlock;
 *       sessionMemory null → fallback 'No session memory available.';
 *       empty messages → empty userMessages string.</li>
 *   <li><b>A5</b>: 真实场景 — ant 工程师结束 process → /skillify "refactor X" →
 *       捕获 session + 用户描述 → interview → 生成 SKILL.md.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `process.env.USER_TYPE` → 注入式 Supplier;
 *                    TS template literal `{{var}}` → Java String.replace;
 *                    TS `registerBundledSkill` → 返回 BundledSkillDefinition (上层 register);
 *                    TS `getSessionMemoryContent()`（当前会话）→ 注入式 Function&lt;String,String&gt;
 *                    （sessionId → 内容，生产接 SessionMemoryService.getSessionMemoryContent）；
 *                    TS `extractUserMessages(getMessagesAfterCompactBoundary(context.messages))`
 *                    → 注入式 Function&lt;List&lt;ChatMessageDto&gt;,List&lt;String&gt;&gt; + 本类内部 boundary 剥离
 *                    （拍板#9 part2：getPromptForCommand 第二参升级为 {@link PromptFnContext}，
 *                    会话通道真实注入，替代旧 {@code () -> ""} 空桩）。
 */
public final class SkillifySkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(SkillifySkillRegistrar.class);

    /** CC skillify.ts SKILLIFY_PROMPT 完整文案（:22-156）· Step2 四 rounds + Step3 完整模板 + annotations + frontmatter rules. */
    private static final String SKILLIFY_PROMPT = """
        # Skillify {{userDescriptionBlock}}

        You are capturing this session's repeatable process as a reusable skill.

        ## Your Session Context

        Here is the session memory summary:
        <session_memory>
        {{sessionMemory}}
        </session_memory>

        Here are the user's messages during this session. Pay attention to how they steered the process, to help capture their detailed preferences in the skill:
        <user_messages>
        {{userMessages}}
        </user_messages>

        ## Your Task

        ### Step 1: Analyze the Session

        Before asking any questions, analyze the session to identify:
        - What repeatable process was performed
        - What the inputs/parameters were
        - The distinct steps (in order)
        - The success artifacts/criteria (e.g. not just "writing code," but "an open PR with CI fully passing") for each step
        - Where the user corrected or steered you
        - What tools and permissions were needed
        - What agents were used
        - What the goals and success artifacts were

        ### Step 2: Interview the User

        You will use the AskUserQuestion to understand what the user wants to automate. Important notes:
        - Use AskUserQuestion for ALL questions! Never ask questions via plain text.
        - For each round, iterate as much as needed until the user is happy.
        - The user always has a freeform "Other" option to type edits or feedback -- do NOT add your own "Needs tweaking" or "I'll provide edits" option. Just offer the substantive choices.

        **Round 1: High level confirmation**
        - Suggest a name and description for the skill based on your analysis. Ask the user to confirm or rename.
        - Suggest high-level goal(s) and specific success criteria for the skill.

        **Round 2: More details**
        - Present the high-level steps you identified as a numbered list. Tell the user you will dig into the detail in the next round.
        - If you think the skill will require arguments, suggest arguments based on what you observed. Make sure you understand what someone would need to provide.
        - If it's not clear, ask if this skill should run inline (in the current conversation) or forked (as a sub-agent with its own context). Forked is better for self-contained tasks that don't need mid-process user input; inline is better when the user wants to steer mid-process.
        - Ask where the skill should be saved. Suggest a default based on context (repo-specific workflows → repo, cross-repo personal workflows → user). Options:
          - **This repo** (`{{projectDirName}}/skills/<name>/SKILL.md`) — for workflows specific to this project
          - **Personal** (`~/{{projectDirName}}/skills/<name>/SKILL.md`) — follows you across all repos

        **Round 3: Breaking down each step**
        For each major step, if it's not glaringly obvious, ask:
        - What does this step produce that later steps need? (data, artifacts, IDs)
        - What proves that this step succeeded, and that we can move on?
        - Should the user be asked to confirm before proceeding? (especially for irreversible actions like merging, sending messages, or destructive operations)
        - Are any steps independent and could run in parallel? (e.g., posting to Slack and monitoring CI at the same time)
        - How should the skill be executed? (e.g. always use a Task agent to conduct code review, or invoke an agent team for a set of concurrent steps)
        - What are the hard constraints or hard preferences? Things that must or must not happen?

        You may do multiple rounds of AskUserQuestion here, one round per step, especially if there are more than 3 steps or many clarification questions. Iterate as much as needed.

        IMPORTANT: Pay special attention to places where the user corrected you during the session, to help inform your design.

        **Round 4: Final questions**
        - Confirm when this skill should be invoked, and suggest/confirm trigger phrases too. (e.g. For a cherrypick workflow you could say: Use when the user wants to cherry-pick a PR to a release branch. Examples: 'cherry-pick to release', 'CP this PR', 'hotfix.')
        - You can also ask for any other gotchas or things to watch out for, if it's still unclear.

        Stop interviewing once you have enough information. IMPORTANT: Don't over-ask for simple processes!

        ### Step 3: Write the SKILL.md

        Create the skill directory and file at the location the user chose in Round 2.

        Use this format:

        ```markdown
        ---
        name: {{skill-name}}
        description: {{one-line description}}
        allowed-tools:
          {{list of tool permission patterns observed during session}}
        when_to_use: {{detailed description of when Claude should automatically invoke this skill, including trigger phrases and example user messages}}
        argument-hint: "{{hint showing argument placeholders}}"
        arguments:
          {{list of argument names}}
        context: {{inline or fork -- omit for inline}}
        ---

        # {{Skill Title}}
        Description of skill

        ## Inputs
        - `$arg_name`: Description of this input

        ## Goal
        Clearly stated goal for this workflow. Best if you have clearly defined artifacts or criteria for completion.

        ## Steps

        ### 1. Step Name
        What to do in this step. Be specific and actionable. Include commands when appropriate.

        **Success criteria**: ALWAYS include this! This shows that the step is done and we can move on. Can be a list.

        IMPORTANT: see the next section below for the per-step annotations you can optionally include for each step.

        ...
        ```

        **Per-step annotations**:
        - **Success criteria** is REQUIRED on every step. This helps the model understand what the user expects from their workflow, and when it should have the confidence to move on.
        - **Execution**: `Direct` (default), `Task agent` (straightforward subagents), `Teammate` (agent with true parallelism and inter-agent communication), or `[human]` (user does it). Only needs specifying if not Direct.
        - **Artifacts**: Data this step produces that later steps need (e.g., PR number, commit SHA). Only include if later steps depend on it.
        - **Human checkpoint**: When to pause and ask the user before proceeding. Include for irreversible actions (merging, sending messages), error judgment (merge conflicts), or output review.
        - **Rules**: Hard rules for the workflow. User corrections during the reference session can be especially useful here.

        **Step structure tips:**
        - Steps that can run concurrently use sub-numbers: 3a, 3b
        - Steps requiring the user to act get `[human]` in the title
        - Keep simple skills simple -- a 2-step skill doesn't need annotations on every step

        **Frontmatter rules:**
        - `allowed-tools`: Minimum permissions needed (use patterns like `Bash(gh:*)` not `Bash`)
        - `context`: Only set `context: fork` for self-contained skills that don't need mid-process user input.
        - `when_to_use` is CRITICAL -- tells the model when to auto-invoke. Start with "Use when..." and include trigger phrases. Example: "Use when the user wants to cherry-pick a PR to a release branch. Examples: 'cherry-pick to release', 'CP this PR', 'hotfix'."
        - `arguments` and `argument-hint`: Only include if the skill takes parameters. Use `$name` in the body for substitution.

        ### Step 4: Confirm and Save

        Before writing the file, output the complete SKILL.md content as a yaml code block in your response so the user can review it with proper syntax highlighting. Then ask for confirmation using AskUserQuestion with a simple question like "Does this SKILL.md look good to save?" — do NOT use the body field, keep the question concise.

        After writing, tell the user:
        - Where the skill was saved
        - How to invoke it: `/{{skill-name}} [arguments]`
        - That they can edit the SKILL.md directly to refine it
        """;

    private final Supplier<String> userTypeSupplier;
    private final Function<String, String> sessionMemoryResolver;
    private final Function<List<ChatMessageDto>, List<String>> userMessagesExtractor;
    private final Consumer<BundledSkillDefinition> registrar;

    /**
     * @param userTypeSupplier      CC process.env.USER_TYPE 门控（"ant" 才注册）
     * @param sessionMemoryResolver sessionId → session memory 内容（CC getSessionMemoryContent，
     *                              生产接 SessionMemoryService.getSessionMemoryContent(sessionId)）
     * @param userMessagesExtractor 已剥离 compact boundary 后的消息 → 用户消息文本列表
     *                              （CC extractUserMessages，skillify.ts:6-20）
     * @param registrar             注册 sink（Consumer）
     */
    public SkillifySkillRegistrar(Supplier<String> userTypeSupplier,
                                    Function<String, String> sessionMemoryResolver,
                                    Function<List<ChatMessageDto>, List<String>> userMessagesExtractor,
                                    Consumer<BundledSkillDefinition> registrar) {
        this.userTypeSupplier = Objects.requireNonNull(userTypeSupplier);
        this.sessionMemoryResolver = Objects.requireNonNull(sessionMemoryResolver);
        this.userMessagesExtractor = Objects.requireNonNull(userMessagesExtractor);
        this.registrar = Objects.requireNonNull(registrar);
    }

    /** CC registerSkillifySkill · 统一产出 BundledSkillDefinition 经 Consumer 注册（P1-4）. */
    public void register() {
        if (!"ant".equals(userTypeSupplier.get())) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillifySkillRegistrar] 非 ant 用户跳过 /skillify 注册（CC skillify.ts ant-only 早返，USER_TYPE={}）",
                    userTypeSupplier.get());
            }
            return;  // ant-only
        }
        BundledSkillDefinition def = new BundledSkillDefinition(
            "skillify",
            "Capture this session's repeatable process into a skill. Call at end of the process you want to capture with an optional description.",
            null,   // aliases
            null,   // whenToUse
            "[description of the process you want to capture]",
            java.util.List.of("Read", "Write", "Edit", "Glob", "Grep", "AskUserQuestion", "Bash(mkdir:*)"),  // allowedTools (CC skillify.ts:167-175)
            null,   // model
            true,   // disableModelInvocation (CC skillify.ts:177)
            true,   // userInvocable (CC skillify.ts:176)
            null,   // isEnabled
            null,   // hooks
            null,   // context
            null,   // agent
            null,   // files
            (args, context) -> {
                // [拍板#9 part2] 会话通道真实注入：CC getPromptForCommand(args, context) 用
                // context.sessionId（getSessionMemoryContent 当前会话）+ context.messages
                // （extractUserMessages(getMessagesAfterCompactBoundary(context.messages))），
                // Java 经 PromptFnContext 承载（替代旧 () -> "" / ignored -> List.of() 空桩）。
                String instruction = args == null ? "" : args.trim();
                String userDescriptionBlock = instruction.isEmpty()
                    ? ""
                    : "The user described this process as: \"" + instruction + "\"";

                String sessionMemory = resolveSessionMemory(context.sessionId());
                if (sessionMemory == null || sessionMemory.isEmpty()) {
                    sessionMemory = "No session memory available.";
                }

                List<String> userMsgList = resolveUserMessages(context.messages());
                String userMessages = String.join("\n\n---\n\n", userMsgList);

                String prompt = SKILLIFY_PROMPT
                    .replace("{{userDescriptionBlock}}", userDescriptionBlock)
                    .replace("{{sessionMemory}}", sessionMemory)
                    .replace("{{userMessages}}", userMessages)
                    // D1/D6 动态：技能保存落点随 appName（.nexusai/skills ↔ .{appName}/skills）
                    .replace("{{projectDirName}}", NexusaiPaths.getProjectDirName());
                if (log.isDebugEnabled()) {
                    log.debug("[SkillifySkillRegistrar] getPromptForCommand 装配完成: instructionLen={} sessionMemoryLen={} "
                            + "userMessagesBlocks={} promptLen={}（CC skillify.ts:22-156）",
                        instruction.length(), sessionMemory.length(), userMsgList.size(), prompt.length());
                }
                return List.of(PromptBlock.text(prompt));
            }
        );
        registrar.accept(def);
        log.info("[SkillifySkillRegistrar] /skillify 技能已注册（ant 用户，7 allowedTools，disableModelInvocation=true）");
    }

    /**
     * 解析会话 memory 内容 · 对齐 CC {@code (await getSessionMemoryContent()) ?? 'No session memory available.'}
     * （skillify.ts:180-181）。null sessionId 或 resolver 返回 null（文件不可访问）→ 返回 null，
     * 由调用方回落默认文案（CC {@code ?? 'No session memory available.'}）。
     *
     * @param sessionId 当前会话 ID（PromptFnContext.sessionId）
     * @return memory 内容；不可用 → null
     */
    private String resolveSessionMemory(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessionMemoryResolver.apply(sessionId);
    }

    /**
     * 解析用户消息 · 对齐 CC {@code extractUserMessages(getMessagesAfterCompactBoundary(context.messages))}
     * （skillify.ts:182-184）：先按 compact boundary 剥离（messages.ts:4643 getMessagesAfterCompactBoundary），
     * 再经注入的 extractor 抽取用户文本（CC extractUserMessages skillify.ts:6-20）。
     *
     * @param messages 会话消息（PromptFnContext.messages）
     * @return 用户消息文本列表（已过滤空白）；消息为空 → 空列表
     */
    private List<String> resolveUserMessages(List<ChatMessageDto> messages) {
        // CC getMessagesAfterCompactBoundary（messages.ts:4643-4656）· 无边界返回全量（BoundaryReader 同语义）
        List<ChatMessageDto> afterBoundary = BoundaryReader.getMessagesAfterCompactBoundary(messages);
        List<String> result = userMessagesExtractor.apply(afterBoundary);
        return result != null ? result : List.of();
    }

    /**
     * CC extractUserMessages（skillify.ts:6-20）· 过滤 user 消息 → 提取内容文本
     * （content 为 string 直接用；否则取 text blocks join '\n'）→ 过滤空白段。
     *
     * <p>Java ChatMessageDto 映射：{@code m.type==='user'} → {@code role==user}；
     * {@code m.message.content}（string）→ {@link ChatMessageDto#content()}；
     * content 为数组时 → {@link ChatMessageDto#contentBlocks()}（JsonNode text 块）。
     *
     * @param messages 已剥离 compact boundary 的消息（CC getMessagesAfterCompactBoundary 输出）
     * @return 非空用户消息文本列表（CC {@code .filter(text => text.trim().length > 0)}）
     */
    static List<String> extractUserMessages(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (ChatMessageDto m : messages) {
            if (m == null || m.role() != Role.user) {
                continue;
            }
            String text = userText(m);
            if (text != null && !text.trim().isEmpty()) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 单条 user 消息 → 文本 · 对齐 CC skillify.ts:9-17：
     * <pre>
     * const content = m.message.content
     * if (typeof content === 'string') return content
     * return content.filter(b => b.type === 'text').map(b => b.text).join('\n')
     * </pre>
     *
     * @param m user 消息
     * @return 文本；content 为 string 直接用，否则 text blocks join '\n'，无 text 块 → ""
     */
    private static String userText(ChatMessageDto m) {
        if (m.content() != null && !m.content().isEmpty()) {
            return m.content();
        }
        if (m.contentBlocks() == null || m.contentBlocks().isEmpty()) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        for (Object block : m.contentBlocks()) {
            if (block instanceof JsonNode node
                    && "text".equals(node.path("type").asText())) {
                String text = node.path("text").asText(null);
                if (text != null) {
                    texts.add(text);
                }
            }
        }
        return String.join("\n", texts);
    }
}
