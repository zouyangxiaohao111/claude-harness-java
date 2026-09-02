package com.nexusai.application.agent.skill;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /schedule skill 注册器 · 对齐 CC skills/bundled/scheduleRemoteAgents.ts.
 *
 * <p>L1 语义: 注册 /schedule skill — 创建/更新/列表/运行远程 (CCR) 定时 agent.
 *            OAuth 检查 (claude.ai access token) + 远程环境 (fetchEnvironments/createDefaultCloudEnvironment)
 *            + git repo 自动探测 + MCP connector 列表. 所有副作用通过 Supplier/Function 注入;
 *            没有 OAuth/远程环境/CCR 时, 返回带 ⚠ Heads-up 的引导 prompt, 不抛错.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: register() → BundledSkillDefinition; name="schedule"; userInvocable=true;
 *       isEnabled 已接线 feature && policy 双开关（CC scheduleRemoteAgents.ts:332-333）;
 *       BASE_QUESTION 常量; 无 argumentHint（CC scheduleRemoteAgents.ts:326-335 register 无该字段）;
 *       allowedTools=[REMOTE_TRIGGER_TOOL_NAME, ASK_USER_QUESTION_TOOL_NAME].</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — getPromptForCommand(args) → 无 OAuth → 引导登录;
 *       拉取 environments 失败 → 错误提示; args 非空 → 跳过 initial question, 直入 workflow;
 *       否则 → firstStep 含 jsonStringify(initialQuestion)（CC :170）+ 4 action options.</li>
 *   <li><b>A3</b>: 状态 — NO_AUTH / FETCH_FAILED / NO_ENV / READY;
 *       args.trim() 决定 firstStep 文案; setupNotes 累积用于 Heads-up 块;
 *       buildPrompt 完整文案（CC :174-321）含 ## Create body shape / ## API Field Reference /
 *       Cron Expression Examples / ## Workflow (CREATE/UPDATE/LIST/RUN NOW) / ## Important Notes /
 *       needsGitHubAccessReminder 分支.</li>
 *   <li><b>A4</b>: OAuth=null → 引导; envs=[] → 自动 create 一个 (mock) 否则报错;
 *       repo=null → setupNotes; connectors=[] → setupNotes;
 *       userArgs 含 [hooks-only] → 不支持 (按 CC 行为不识别).</li>
 *   <li><b>A5</b>: 真实场景 — 运营人员 `/schedule check Datadog and Slack me errors every morning at 9am`
 *       → timezone-aware cron + remote trigger body 模板 + MCP connector 校验.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS async → 同步 Function.apply (远程调用由上层包装);
 *                    TS template literal → Java text block;
 *                    TS `registerBundledSkill({...})` → 返回 BundledSkillDefinition (上层 register).
 */
public final class ScheduleRemoteAgentsSkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRemoteAgentsSkillRegistrar.class);

    public static final String SKILL_NAME = "schedule";
    public static final String REMOTE_TRIGGER_TOOL_NAME = "RemoteTrigger";
    public static final String ASK_USER_QUESTION_TOOL_NAME = "AskUserQuestion";

    /** CC BASE_QUESTION — 初始 AskUserQuestion 标题. */
    public static final String BASE_QUESTION = "What would you like to do with scheduled remote agents?";

    private final BooleanSupplier featureEnabled;
    private final BooleanSupplier policyAllowed;
    private final java.util.function.Supplier<String> oauthAccessToken;
    private final java.util.function.Supplier<List<EnvironmentResource>> fetchEnvironments;
    private final java.util.function.Function<String, EnvironmentResource> createDefaultEnvironment;
    private final java.util.function.Supplier<String> userTimezone;
    private final java.util.function.Supplier<List<ConnectorInfo>> connectedConnectors;
    private final java.util.function.Supplier<String> currentGitRepoUrl;

    public ScheduleRemoteAgentsSkillRegistrar(
            BooleanSupplier featureEnabled,
            BooleanSupplier policyAllowed,
            java.util.function.Supplier<String> oauthAccessToken,
            java.util.function.Supplier<List<EnvironmentResource>> fetchEnvironments,
            java.util.function.Function<String, EnvironmentResource> createDefaultEnvironment,
            java.util.function.Supplier<String> userTimezone,
            java.util.function.Supplier<List<ConnectorInfo>> connectedConnectors,
            java.util.function.Supplier<String> currentGitRepoUrl) {
        this.featureEnabled = Objects.requireNonNull(featureEnabled);
        this.policyAllowed = Objects.requireNonNull(policyAllowed);
        this.oauthAccessToken = Objects.requireNonNull(oauthAccessToken);
        this.fetchEnvironments = Objects.requireNonNull(fetchEnvironments);
        this.createDefaultEnvironment = Objects.requireNonNull(createDefaultEnvironment);
        this.userTimezone = Objects.requireNonNull(userTimezone);
        this.connectedConnectors = Objects.requireNonNull(connectedConnectors);
        this.currentGitRepoUrl = Objects.requireNonNull(currentGitRepoUrl);
    }

    @FunctionalInterface
    public interface BooleanSupplier { boolean getAsBoolean(); }

    public record EnvironmentResource(String name, String environmentId, String kind) {}

    public record ConnectorInfo(String uuid, String name, String url) {}

    /** CC setupNotes 累积 + Heads-up 块渲染. */
    public static String formatSetupNotes(List<String> notes) {
        if (notes == null || notes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("⚠ Heads-up:\n");
        for (String n : notes) {
            sb.append("- ").append(n).append('\n');
        }
        return sb.toString();
    }

    /**
     * Base58 字母表（Bitcoin 风格）· CC original: BASE58（scheduleRemoteAgents.ts:24）。
     * taggedIdToUUID 解码依赖。
     */
    static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /**
     * 解码 mcpsrv_ 前缀 tagged ID 为 UUID · CC original: taggedIdToUUID（scheduleRemoteAgents.ts:35-57）。
     *
     * <p>Tagged ID 格式 {@code mcpsrv_01{base58(uuid.int)}}（01 为 version 前缀）；base58 解码 → hex →
     * 标准 UUID 串。非法字符/非 mcpsrv_ 前缀 → null（CC :38/:48-51 同）。
     *
     * @param taggedId 形如 {@code mcpsrv_01...} 的 connector tagged id
     * @return UUID 串或 null
     */
    static String taggedIdToUUID(String taggedId) {
        if (taggedId == null || !taggedId.startsWith("mcpsrv_")) {
            return null;
        }
        String rest = taggedId.substring("mcpsrv_".length());
        // 跳过 version 前缀（2 字符，恒 "01"）
        String base58Data = rest.length() > 2 ? rest.substring(2) : "";
        BigInteger n = BigInteger.ZERO;
        for (int i = 0; i < base58Data.length(); i++) {
            int idx = BASE58.indexOf(base58Data.charAt(i));
            if (idx == -1) {
                return null;
            }
            n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(idx));
        }
        // 32 位 hex 左补零（CC padStart(32,'0')）
        String hex = n.toString(16);
        StringBuilder padded = new StringBuilder(32);
        for (int i = 0; i < 32 - hex.length(); i++) {
            padded.append('0');
        }
        padded.append(hex);
        String h = padded.toString();
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16)
            + "-" + h.substring(16, 20) + "-" + h.substring(20, 32);
    }

    /**
     * 净化 connector name 为 {@code [a-zA-Z0-9_-]} · CC original: sanitizeConnectorName
     * （scheduleRemoteAgents.ts:89-95）：去 {@code claude.ai} 前缀（大小写不敏感）+ 非 alnum 归一为
     * {@code -} + 折叠连续 {@code -} + 去首尾 {@code -}。供 {@code mcp_connections.name} 校验参考。
     */
    static String sanitizeConnectorName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceFirst("(?i)^claude[.\\s-]ai[.\\s-]", "")
            .replaceAll("[^a-zA-Z0-9_-]", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");
    }

    /**
     * CC formatConnectorsInfo（scheduleRemoteAgents.ts:97-109）— list → 渲染字符串; 空 → 引导用户去
     * settings/connectors。非空行含 {@code name: {safeName}}（sanitizeConnectorName 净化名，△-8 关闭）。
     */
    public static String formatConnectorsInfo(List<ConnectorInfo> connectors) {
        if (connectors == null || connectors.isEmpty()) {
            return "No connected MCP connectors found. The user may need to connect servers at "
                + "https://claude.ai/settings/connectors";
        }
        StringBuilder sb = new StringBuilder("Connected connectors (available for triggers):\n");
        for (ConnectorInfo c : connectors) {
            String safeName = sanitizeConnectorName(c.name());
            sb.append("- ").append(c.name()).append(" (connector_uuid: ").append(c.uuid())
              .append(", name: ").append(safeName).append(", url: ").append(c.url()).append(")\n");
        }
        return sb.toString();
    }

    /**
     * CC formatEnvironmentsInfo · 对齐 scheduleRemoteAgents.ts:427-433（environmentsInfo 拼装内联，
     * <b>无空环境 fallback 文案</b>——envs 恒非空才调用：buildPromptForCommand 在 envs 为空时先
     * createDefaultCloudEnvironment 补位）。P3-9 03-1 / DEL-04：删除 Java 旧空分支文案
     * 「(none yet — a default will be created automatically)」（dead branch，CC 无对应文案）。
     */
    public static String formatEnvironmentsInfo(List<EnvironmentResource> envs) {
        StringBuilder sb = new StringBuilder("Available environments:");
        for (EnvironmentResource e : envs) {
            sb.append("\n- ").append(e.name()).append(" (id: ").append(e.environmentId())
              .append(", kind: ").append(e.kind()).append(")");
        }
        return sb.toString();
    }

    /** 主入口 — 注册 bundled skill · 统一产出 BundledSkillDefinition（P1-4）. */
    public BundledSkillDefinition register() {
        return new BundledSkillDefinition(
            SKILL_NAME,
            "Create, update, list, or run scheduled remote agents (triggers) that execute on a cron schedule.",
            null,   // aliases
            "When the user wants to schedule a recurring remote agent, set up automated tasks, "
                + "create a cron job for Claude Code, or manage their scheduled agents/triggers.",
            null,   // argumentHint（CC scheduleRemoteAgents.ts:326-335 register 无 argumentHint — Java 自增已移除）
            List.of(REMOTE_TRIGGER_TOOL_NAME, ASK_USER_QUESTION_TOOL_NAME),  // allowedTools (CC scheduleRemoteAgents.ts:335, 修 E8)
            null,   // model
            false,  // disableModelInvocation=false (CC undefined → default false; skill model can call RemoteTrigger)
            true,   // userInvocable
            () -> featureEnabled.getAsBoolean() && policyAllowed.getAsBoolean(),  // isEnabled 双开关（CC scheduleRemoteAgents.ts:332-333 feature && policy）
            null,   // hooks
            null,   // context
            null,   // agent
            null,   // files
            (args, cwd) -> buildPromptForCommand(args)
        );
    }

    /** CC registerBundledSkill getPromptForCommand — 主链. */
    private List<PromptBlock> buildPromptForCommand(String args) {
        String userArgs = args == null ? "" : args.trim();
        // OAuth check
        if (oauthAccessToken.get() == null) {
            return List.of(PromptBlock.text(
                "You need to authenticate with a claude.ai account first. API accounts are not supported. "
                    + "Run /login, then try /schedule again."));
        }
        // Fetch environments
        List<EnvironmentResource> envs;
        try {
            envs = fetchEnvironments.get();
        } catch (Exception ex) {
            log.warn("[schedule] Failed to fetch environments: {}", ex.getMessage());
            return List.of(PromptBlock.text(
                "We're having trouble connecting with your remote claude.ai account to set up "
                    + "a scheduled task. Please try /schedule again in a few minutes."));
        }
        EnvironmentResource createdEnv = null;
        if (envs.isEmpty()) {
            try {
                createdEnv = createDefaultEnvironment.apply("claude-code-default");
                envs = List.of(createdEnv);
            } catch (Exception ex) {
                log.warn("[schedule] Failed to create environment: {}", ex.getMessage());
                return List.of(PromptBlock.text(
                    "No remote environments found, and we could not create one automatically. "
                        + "Visit https://claude.ai/code to set one up, then run /schedule again."));
            }
        }
        // setupNotes
        List<String> setupNotes = new java.util.ArrayList<>();
        String repoUrl = currentGitRepoUrl.get();
        if (repoUrl == null || repoUrl.isBlank()) {
            setupNotes.add("Not in a git repo — you'll need to specify a repo URL manually "
                + "(or skip repos entirely).");
        }
        List<ConnectorInfo> connectors = connectedConnectors.get();
        if (connectors.isEmpty()) {
            setupNotes.add("No MCP connectors — connect at https://claude.ai/settings/connectors if needed.");
        }
        // [MCP-I-9 Q-33] TODO 登记（本期不接线，/schedule 维持 disabled）：
        //   C4-C6 connector 枚举 = CC :415-417 getConnectedClaudeAIConnectors(context.options.mcpClients) —
        //   Q-30 连接继承落地后 mcpClients 仍有连接，但 Java 无 claudeai-proxy 型 client（Q-26 TODO）
        //   → connectors 恒空（登记事实，不实施）。
        // needsGitHubAccessReminder：Java 无 checkRepoForRemoteAccess（CC scheduleRemoteAgents.ts:398-408）
        // 基建，恒传 false（P2-7 concerns 方案 A），prompt 中不出现 GitHub App 提醒句；待接线时改此处。
        String prompt = buildPrompt(userArgs, setupNotes, repoUrl,
            formatConnectorsInfo(connectors),
            formatEnvironmentsInfo(envs),
            createdEnv,
            userTimezone.get(),
            false);
        if (log.isDebugEnabled()) {
            log.debug("[ScheduleRemoteAgentsSkillRegistrar] buildPromptForCommand userArgs='{}' envs={} connectors={} createdEnv={} needsGitHubAccessReminder=false（CC scheduleRemoteAgents.ts:336-445；GitHub access 检查未接线）",
                userArgs, envs.size(), connectors.size(), createdEnv == null ? "null" : createdEnv.name());
        }
        return List.of(PromptBlock.text(prompt));
    }

    /** Jackson — 仅用于 jsonStringify（JSON.stringify 等价，CC slowOperations.ts:170-183）. */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** CC original: jsonStringify（slowOperations.ts）— 对字符串做 JSON 编码（scheduleRemoteAgents.ts:170 jsonStringify(initialQuestion)）. */
    public static String jsonStringify(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            // JSON.stringify 对任意 string 不失败；Jackson 序列化 String 亦不会抛，防御性回退
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
    }

    /**
     * CC buildPrompt 完整文案（scheduleRemoteAgents.ts:135-322）· 纯函数, 暴露便于测试.
     *
     * <p>签名新增 {@code boolean needsGitHubAccessReminder}（CC opts.needsGitHubAccessReminder :142）：
     * 该分支文案固定取 tengu_cobalt_lantern=false 分支（Java 无 GrowthBook，见 P2-7 concerns）。
     */
    public static String buildPrompt(String userArgs, List<String> setupNotes,
            String gitRepoUrl, String connectorsInfo, String environmentsInfo,
            EnvironmentResource createdEnvironment, String userTimezone,
            boolean needsGitHubAccessReminder) {
        // CC :158-161 setupNotesSection（userArgs 非空时 setup notes 沉入 prompt body）
        String setupNotesSection = (!userArgs.isEmpty() && !setupNotes.isEmpty())
            ? "\n## Setup Notes\n\n" + formatSetupNotes(setupNotes) + "\n"
            : "";
        // CC :162-165 initialQuestion
        String initialQuestion = setupNotes.isEmpty()
            ? BASE_QUESTION
            : formatSetupNotes(setupNotes) + "\n" + BASE_QUESTION;
        // CC :166-172 firstStep — initialQuestion 经 jsonStringify（:170）
        String firstStep = userArgs.isEmpty()
            ? "Your FIRST action must be a single " + ASK_USER_QUESTION_TOOL_NAME
                + " tool call (no preamble). Use this EXACT string for the `question` field — "
                + "do not paraphrase or shorten it:\n\n" + jsonStringify(initialQuestion)
                + "\n\nSet `header: \"Action\"` and offer the four actions (create/list/update/run) "
                + "as options. After the user picks, follow the matching workflow below."
            : "The user has already told you what they want (see User Request at the bottom). "
                + "Skip the initial question and go directly to the matching workflow.";
        // CC :245 createdEnvironment note
        String createdEnvNote = createdEnvironment != null
            ? "\n**Note:** A new environment `" + createdEnvironment.name() + "` (id: `"
                + createdEnvironment.environmentId()
                + "`) was just created for the user because they had none. Use this id for "
                + "`job_config.ccr.environment_id` and mention the creation when you confirm the trigger config.\n"
            : "";
        // CC :208 Create body shape git_repository url（gitRepoUrl || 'https://github.com/ORG/REPO'）
        String repoUrlInBody = (gitRepoUrl == null || gitRepoUrl.isBlank())
            ? "https://github.com/ORG/REPO" : gitRepoUrl;
        // CC :290 CREATE step5 validateConnections tail
        String validateConnectionsTail = (gitRepoUrl == null || gitRepoUrl.isBlank())
            ? " Ask which git repos the remote agent needs cloned into its environment."
            : " The default git repo is already set to `" + gitRepoUrl
                + "`. Ask the user if this is the right repo or if they need a different one.";
        // CC :320 needsGitHubAccessReminder（tengu_cobalt_lantern=false 分支文案）
        String gitHubReminder = needsGitHubAccessReminder
            ? "- If the user's request seems to require GitHub repo access (e.g. cloning a repo, opening PRs, reading code), remind them that they need the Claude GitHub App installed on the repo — otherwise the remote agent won't be able to access it"
            : "";
        // CC :321 userArgs tail
        String userRequestTail = userArgs.isEmpty()
            ? ""
            : "\n## User Request\n\nThe user said: \"" + userArgs
                + "\"\n\nStart by understanding their intent and working through the appropriate workflow above.";

        return SCHEDULE_PROMPT_TEMPLATE
            .replace("{{firstStep}}", firstStep)
            .replace("{{setupNotesSection}}", setupNotesSection)
            .replace("{{remoteTriggerTool}}", REMOTE_TRIGGER_TOOL_NAME)
            .replace("{{gitRepoUrlBody}}", repoUrlInBody)
            .replace("{{connectorsInfo}}", connectorsInfo)
            .replace("{{environmentsInfo}}", environmentsInfo)
            .replace("{{createdEnvNote}}", createdEnvNote)
            .replace("{{userTimezone}}", userTimezone)
            .replace("{{validateConnectionsTail}}", validateConnectionsTail)
            .replace("{{gitHubReminder}}", gitHubReminder)
            .replace("{{userRequestTail}}", userRequestTail);
    }

    /** CC buildPrompt 完整文案模板（scheduleRemoteAgents.ts:174-321），占位符经 {@link #buildPrompt} 替换. */
    private static final String SCHEDULE_PROMPT_TEMPLATE = """
        # Schedule Remote Agents

        You are helping the user schedule, update, list, or run **remote** Claude Code agents. These are NOT local cron jobs — each trigger spawns a fully isolated remote session (CCR) in Anthropic's cloud infrastructure on a cron schedule. The agent runs in a sandboxed environment with its own git checkout, tools, and optional MCP connections.

        ## First Step

        {{firstStep}}
        {{setupNotesSection}}

        ## What You Can Do

        Use the `{{remoteTriggerTool}}` tool (load it first with `ToolSearch select:{{remoteTriggerTool}}`; auth is handled in-process — do not use curl):

        - `{action: "list"}` — list all triggers
        - `{action: "get", trigger_id: "..."}` — fetch one trigger
        - `{action: "create", body: {...}}` — create a trigger
        - `{action: "update", trigger_id: "...", body: {...}}` — partial update
        - `{action: "run", trigger_id: "..."}` — run a trigger now

        You CANNOT delete triggers. If the user asks to delete, direct them to: https://claude.ai/code/scheduled

        ## Create body shape

        ```json
        {
          "name": "AGENT_NAME",
          "cron_expression": "CRON_EXPR",
          "enabled": true,
          "job_config": {
            "ccr": {
              "environment_id": "ENVIRONMENT_ID",
              "session_context": {
                "model": "claude-sonnet-4-6",
                "sources": [
                  {"git_repository": {"url": "{{gitRepoUrlBody}}"}}
                ],
                "allowed_tools": ["Bash", "Read", "Write", "Edit", "Glob", "Grep"]
              },
              "events": [
                {"data": {
                  "uuid": "<lowercase v4 uuid>",
                  "session_id": "",
                  "type": "user",
                  "parent_tool_use_id": null,
                  "message": {"content": "PROMPT_HERE", "role": "user"}
                }}
              ]
            }
          }
        }
        ```

        Generate a fresh lowercase UUID for `events[].data.uuid` yourself.

        ## Available MCP Connectors

        These are the user's currently connected claude.ai MCP connectors:

        {{connectorsInfo}}

        When attaching connectors to a trigger, use the `connector_uuid` and `name` shown above (the name is already sanitized to only contain letters, numbers, hyphens, and underscores), and the connector's URL. The `name` field in `mcp_connections` must only contain `[a-zA-Z0-9_-]` — dots and spaces are NOT allowed.

        **Important:** Infer what services the agent needs from the user's description. For example, if they say "check Datadog and Slack me errors," the agent needs both Datadog and Slack connectors. Cross-reference against the list above and warn if any required service isn't connected. If a needed connector is missing, direct the user to https://claude.ai/settings/connectors to connect it first.

        ## Environments

        Every trigger requires an `environment_id` in the job config. This determines where the remote agent runs. Ask the user which environment to use.

        {{environmentsInfo}}

        Use the `id` value as the `environment_id` in `job_config.ccr.environment_id`.
        {{createdEnvNote}}

        ## API Field Reference

        ### Create Trigger — Required Fields
        - `name` (string) — A descriptive name
        - `cron_expression` (string) — 5-field cron. **Minimum interval is 1 hour.**
        - `job_config` (object) — Session configuration (see structure above)

        ### Create Trigger — Optional Fields
        - `enabled` (boolean, default: true)
        - `mcp_connections` (array) — MCP servers to attach:
          ```json
          [{"connector_uuid": "uuid", "name": "server-name", "url": "https://..."}]
          ```

        ### Update Trigger — Optional Fields
        All fields optional (partial update):
        - `name`, `cron_expression`, `enabled`, `job_config`
        - `mcp_connections` — Replace MCP connections
        - `clear_mcp_connections` (boolean) — Remove all MCP connections

        ### Cron Expression Examples

        The user's local timezone is **{{userTimezone}}**. Cron expressions are always in UTC. When the user says a local time, convert it to UTC for the cron expression but confirm with them: "9am {{userTimezone}} = Xam UTC, so the cron would be `0 X * * 1-5`."

        - `0 9 * * 1-5` — Every weekday at 9am **UTC**
        - `0 */2 * * *` — Every 2 hours
        - `0 0 * * *` — Daily at midnight **UTC**
        - `30 14 * * 1` — Every Monday at 2:30pm **UTC**
        - `0 8 1 * *` — First of every month at 8am **UTC**

        Minimum interval is 1 hour. `*/30 * * * *` will be rejected.

        ## Workflow

        ### CREATE a new trigger:

        1. **Understand the goal** — Ask what they want the remote agent to do. What repo(s)? What task? Remind them that the agent runs remotely — it won't have access to their local machine, local files, or local environment variables.
        2. **Craft the prompt** — Help them write an effective agent prompt. Good prompts are:
           - Specific about what to do and what success looks like
           - Clear about which files/areas to focus on
           - Explicit about what actions to take (open PRs, commit, just analyze, etc.)
        3. **Set the schedule** — Ask when and how often. The user's timezone is {{userTimezone}}. When they say a time (e.g., "every morning at 9am"), assume they mean their local time and convert to UTC for the cron expression. Always confirm the conversion: "9am {{userTimezone}} = Xam UTC."
        4. **Choose the model** — Default to `claude-sonnet-4-6`. Tell the user which model you're defaulting to and ask if they want a different one.
        5. **Validate connections** — Infer what services the agent will need from the user's description. For example, if they say "check Datadog and Slack me errors," the agent needs both Datadog and Slack MCP connectors. Cross-reference with the connectors list above. If any are missing, warn the user and link them to https://claude.ai/settings/connectors to connect first.{{validateConnectionsTail}}
        6. **Review and confirm** — Show the full configuration before creating. Let them adjust.
        7. **Create it** — Call `{{remoteTriggerTool}}` with `action: "create"` and show the result. The response includes the trigger ID. Always output a link at the end: `https://claude.ai/code/scheduled/{TRIGGER_ID}`

        ### UPDATE a trigger:

        1. List triggers first so they can pick one
        2. Ask what they want to change
        3. Show current vs proposed value
        4. Confirm and update

        ### LIST triggers:

        1. Fetch and display in a readable format
        2. Show: name, schedule (human-readable), enabled/disabled, next run, repo(s)

        ### RUN NOW:

        1. List triggers if they haven't specified which one
        2. Confirm which trigger
        3. Execute and confirm

        ## Important Notes

        - These are REMOTE agents — they run in Anthropic's cloud, not on the user's machine. They cannot access local files, local services, or local environment variables.
        - Always convert cron to human-readable when displaying
        - Default to `enabled: true` unless user says otherwise
        - Accept GitHub URLs in any format (https://github.com/org/repo, org/repo, etc.) and normalize to the full HTTPS URL (without .git suffix)
        - The prompt is the most important part — spend time getting it right. The remote agent starts with zero context, so the prompt must be self-contained.
        - To delete a trigger, direct users to https://claude.ai/code/scheduled
        {{gitHubReminder}}
        {{userRequestTail}}
        """;
}