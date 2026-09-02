package com.nexusai.application.agent.permission.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YoloClassifier 的 Prompt 构造器 + 序列化 + XML 解析 · 对齐 CC yoloClassifier.ts。
 *
 * <p>[S06 重构] 职责变更（OPD-WF6-01/03，⊕-05）：
 * <ul>
 *   <li><b>删</b> {@code buildSystemPrompt(Map)} 手写 JSON prompt（allow/deny/ask 三态 +
 *       confidence 输出契约）—— ⊕-05 手写静态 prompt 删除。</li>
 *   <li><b>增</b> {@link #buildYoloSystemPrompt} — 对齐 CC {@code buildYoloSystemPrompt}
 *       （yoloClassifier.ts:484-540）：BASE_PROMPT + {@code <permissions_template>} 段 +
 *       {@code <user_*_to_replace>} 规则标签。</li>
 *   <li><b>增</b> {@link #replaceOutputFormatWithXml} — 对齐 CC（yoloClassifier.ts:648-664）：
 *       把 "Use the classify_result tool to report your classification." 行替换为 XML 输出格式。</li>
 *   <li><b>增</b> {@link #parseXmlBlock} / {@link #parseXmlReason} / {@link #parseXmlThinking} /
 *       {@link #stripThinking} — 对齐 CC（yoloClassifier.ts:567-604）。</li>
 * </ul>
 *
 * <p>保留（已 CC 对齐，不动）：
 * <ul>
 *   <li>{@link #buildTranscriptEntries} — 对话历史转录（全量；user 文本 + assistant tool_use）</li>
 *   <li>{@link #toCompact} / {@link #toCompactBlock} — 单条消息/工具调用序列化（per-tool 投影）</li>
 * </ul>
 *
 * <p><b>[IMP-6] 模板真源（OPD-WF6-03/04 闭环）</b>：CC BASE_PROMPT / EXTERNAL / ANTHROPIC
 * 模板（yolo-classifier-prompts/*.txt，yoloClassifier.ts:54-68）已随 commit 0557959d 入库到
 * {@code src/main/resources/yolo-classifier-prompts/}，本类启动即从 classpath 加载真源
 * （替换旧结构占位）。{@link #buildYoloSystemPrompt} 对齐 CC buildYoloSystemPrompt
 * （yoloClassifier.ts:484-540）：BASE_PROMPT.replace('&lt;permissions_template&gt;') →
 * 三段 {@code <user_*_to_replace>} 规则替换（用户 auto-mode 规则 REPLACE 模板默认值）。
 *
 * <p><b>[IMP-6] 1-stage classify_result 工具协议（OPD-WF6-01-RV）</b>：
 * {@link #buildClassifyResultToolsArray} 构建 CC YOLO_CLASSIFIER_TOOL_SCHEMA
 * （yoloClassifier.ts:262-285）的 OpenAI function-calling 投影，供 1-stage 分类器
 * 强制 {@code tool_choice: classify_result} 结构化输出。
 *
 * @see YoloClassifier
 * @see YoloClassifierResult
 */
@Component
public class YoloPromptBuilder {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(YoloPromptBuilder.class);

    /** 解析历史 toolCall arguments JSON（thread-safe readTree）。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 模板资源目录（classpath）· 对齐 CC yolo-classifier-prompts/（yoloClassifier.ts:54-68）。 */
    static final String PROMPTS_RESOURCE_DIR = "yolo-classifier-prompts/";

    /** CC BASE_PROMPT（auto_mode_system_prompt.txt，yoloClassifier.ts:54-56）。 */
    private static final String BASE_PROMPT = loadTemplateResource("auto_mode_system_prompt.txt");

    /** CC EXTERNAL_PERMISSIONS_TEMPLATE（permissions_external.txt，yoloClassifier.ts:61-63）。
     *  非 ant 构建恒用外部模板（isUsingExternalPermissions :71-78 ant 分支 N/A）。 */
    private static final String EXTERNAL_PERMISSIONS_TEMPLATE = loadTemplateResource("permissions_external.txt");

    /** CC ANTHROPIC_PERMISSIONS_TEMPLATE（permissions_anthropic.txt，yoloClassifier.ts:65-68）。
     *  ant 平台 N/A —— 保留资源加载，isUsingExternalPermissions 非 ant 恒 true 不消费。 */
    private static final String ANTHROPIC_PERMISSIONS_TEMPLATE = loadTemplateResource("permissions_anthropic.txt");

    /** 可信 userSettings 源文件 · 对齐 CC ~/.claude/settings.json（settings.ts:896-911）
     *  + {@code AutoModeGate.DEFAULT_USER_SETTINGS_PATH} 既有先例。
     *  用户级源改走 {@link NexusaiPaths#getAppConfigHomePath()}（决策 D2，~/.{appName}/settings.json；
     *  appName=nexusai → ~/.nexusai/settings.json，与 UserSettingsLoader/AutoModeGate 同源）。 */
    static final Path DEFAULT_USER_SETTINGS_PATH =
        NexusaiPaths.getAppConfigHomePath().resolve("settings.json");

    /** 用户级 settings 文件路径（测试可覆盖）· settings.autoMode 三段规则源。 */
    private volatile Path userSettingsPath = DEFAULT_USER_SETTINGS_PATH;

    /** [R4-3] 项目根惰性供应 · 决策 D6 项目根（{@code CwdResolution.getOriginalCwdLayer()}，
     *  无会话回落 {@code user.dir}）。localSettings 源 = {@code <projectRoot>/.nexusai/settings.local.json}
     *  （项目级，对齐 LocalSettingsLoader 既有语义）；nexusai.home 已废弃（第二轮拍板），不再经
     *  {@code @Value("${nexusai.home}")} 注入。 */
    private final Supplier<String> projectRootSupplier = CwdResolution::getOriginalCwdLayer;

    /** [prompt-align TOOLS-02] 托管策略文件路径 · policySettings 源
     *  （PolicySettingsLoader:61-66 先例，nexusai.policy.path 配置）。未配置/blank → 跳过。 */
    @org.springframework.beans.factory.annotation.Value("${nexusai.policy.path:}")
    private volatile String policySettingsPath = "";

    /** [prompt-align TOOLS-02] localSettings 源路径覆盖（测试用）· null → 默认解析
     *  （{@link #localSettingsPath()}）。 */
    private volatile Path localSettingsPathOverride;

    /** settings.autoMode 规则 · 对齐 CC AutoModeRules（yoloClassifier.ts:85-89）{allow, soft_deny, environment}。 */
    record AutoModeRules(List<String> allow, List<String> softDeny, List<String> environment) {}

    /** 加载模板资源 · 对齐 CC txtRequire + feature('TRANSCRIPT_CLASSIFIER') 门控（yoloClassifier.ts:50-68）；
     *  资源缺失/异常 → 空串（调用方兜底，不崩）。 */
    private static String loadTemplateResource(String name) {
        try (InputStream in = YoloPromptBuilder.class.getClassLoader()
                .getResourceAsStream(PROMPTS_RESOURCE_DIR + name)) {
            if (in == null) {
                if (log.isWarnEnabled()) {
                    log.warn("YoloPromptBuilder: 模板资源 {} 缺失（classpath:{}{}），回落空串", name, PROMPTS_RESOURCE_DIR, name);
                }
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("YoloPromptBuilder: 模板资源 {} 加载失败，回落空串: {}", name, e.getMessage());
            }
            return "";
        }
    }

    /**
     * CC 输出格式锚点行（yoloClassifier.ts:650 toolUseLine）—— replaceOutputFormatWithXml 的替换目标。
     */
    private static final String TOOL_USE_LINE =
            "Use the classify_result tool to report your classification.";

    /** CC XML_S1_SUFFIX（yoloClassifier.ts:550）—— stage1 追加后缀。 */
    public static final String XML_S1_SUFFIX = "\nErr on the side of blocking. <block> immediately.";

    /** CC XML_S2_SUFFIX（yoloClassifier.ts:560-561）—— stage2 追加后缀。 */
    public static final String XML_S2_SUFFIX =
            "\nReview the classification process and follow it carefully, making sure you deny actions that should be blocked. As a reminder, explicit (not suggestive or implicit) user confirmation is required to override blocks. Use <thinking> before responding with <block>.";

    private static final Pattern XML_BLOCK_PATTERN =
            Pattern.compile("<block>(yes|no)\\b(</block>)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_REASON_PATTERN =
            Pattern.compile("<reason>([\\s\\S]*?)</reason>", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_THINKING_PATTERN =
            Pattern.compile("<thinking>([\\s\\S]*?)</thinking>");
    private static final Pattern STRIP_THINKING_CLOSED =
            Pattern.compile("<thinking>[\\s\\S]*?</thinking>");
    private static final Pattern STRIP_THINKING_OPEN =
            Pattern.compile("<thinking>[\\s\\S]*$");

    // [IMP-6 OPD-WF6-03-RV] 三段 <user_*_to_replace> 规则标签 · 对齐 CC buildYoloSystemPrompt
    //   （yoloClassifier.ts:527-539）/[\\s\\S]*? 非贪婪捕获模板默认值。
    private static final Pattern ALLOW_TAG_PATTERN =
            Pattern.compile("<user_allow_rules_to_replace>([\\s\\S]*?)</user_allow_rules_to_replace>");
    private static final Pattern DENY_TAG_PATTERN =
            Pattern.compile("<user_deny_rules_to_replace>([\\s\\S]*?)</user_deny_rules_to_replace>");
    private static final Pattern ENV_TAG_PATTERN =
            Pattern.compile("<user_environment_to_replace>([\\s\\S]*?)</user_environment_to_replace>");

    /** CC YOLO_CLASSIFIER_TOOL_NAME（yoloClassifier.ts:260）· 1-stage classify_result 工具名。 */
    public static final String CLASSIFY_RESULT_TOOL_NAME = "classify_result";

    // ════════════════════════════════════════════════════════════════════════
    // §8.2 buildYoloSystemPrompt + replaceOutputFormatWithXml + XML 解析
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 构建分类器 system prompt · 对齐 CC {@code buildYoloSystemPrompt}（yoloClassifier.ts:484-540）。
     *
     * <p>CC 语义（:488-539）：BASE_PROMPT.replace('<permissions_template>', EXTERNAL/ANTHROPIC
     * 模板) → 读 settings.autoMode 三段（allow/soft_deny/environment，getAutoModeConfig
     * settings.ts:936-982）→ 每段 {@code - {rule}} 逐行子弹 → 把整段
     * {@code <user_*_to_replace>...</...>} 标签替换为用户规则；无用户规则 → 保留模板默认值
     * （{@code userAllow ?? defaults}，:530/:534/:538）。非 ant 构建恒用外部模板
     * （isUsingExternalPermissions :71-78）。
     *
     * <p>system prompt 恒含 {@link #TOOL_USE_LINE} 锚点行（auto_mode_system_prompt.txt 末行），
     * 供 {@link #replaceOutputFormatWithXml} 替换为 XML 输出格式（2-stage，CC :648-664）；
     * 1-stage 分类器直接消费原样 prompt（tool_use 输出格式，TOOL_USE_LINE 保留）。
     *
     * <p><b>不接收工具表</b>：CC buildYoloSystemPrompt 基于权限规则而非 per-tool 描述
     * （yoloClassifier.ts:494-509）；旧 Java "## Available Tools" 段（手写 prompt）已删除（⊕-05）。
     *
     * @return 分类器 system prompt（含 TOOL_USE_LINE 锚点行）
     */
    public String buildYoloSystemPrompt() {
        String systemPrompt;
        if (!BASE_PROMPT.isEmpty() && !EXTERNAL_PERMISSIONS_TEMPLATE.isEmpty()) {
            // CC :488-492 systemPrompt = BASE_PROMPT.replace('<permissions_template>', EXTERNAL 模板)
            systemPrompt = BASE_PROMPT.replace("<permissions_template>", EXTERNAL_PERMISSIONS_TEMPLATE);
        } else {
            // 资源缺失兜底（既有结构占位，保 TOOL_USE_LINE 锚点）· 正常仓库快照不含此路径。
            systemPrompt = "You are a security classifier for an AI coding agent.\n\n"
                + "<permissions_template>\n\n" + TOOL_USE_LINE;
        }
        // [IMP-6 OPD-WF6-03-RV] 用户 auto-mode 规则注入 · CC :494-539（getAutoModeConfig 源
        //   [TOOLS-02] 四源合并（user/local/flag/policy，projectSettings 排除），见 readAutoModeConfig）。
        AutoModeRules autoMode = readAutoModeConfig();
        if (autoMode != null) {
            String userAllow = toBulletLines(autoMode.allow());
            String userDeny = toBulletLines(autoMode.softDeny());
            String userEnvironment = toBulletLines(autoMode.environment());
            if (userAllow != null) {
                systemPrompt = ALLOW_TAG_PATTERN.matcher(systemPrompt).replaceAll(mr -> userAllow);
            }
            if (userDeny != null) {
                systemPrompt = DENY_TAG_PATTERN.matcher(systemPrompt).replaceAll(mr -> userDeny);
            }
            if (userEnvironment != null) {
                systemPrompt = ENV_TAG_PATTERN.matcher(systemPrompt).replaceAll(mr -> userEnvironment);
            }
        }
        return systemPrompt;
    }

    /**
     * 构建分类器 CLAUDE.md 前缀块 · 对齐 CC {@code buildClaudeMdMessage}
     * （yoloClassifier.ts:460-477）的文本拼装侧。
     *
     * <p>CC 语义（:460-461）：{@code getCachedClaudeMdContent()} 为 null → 返回 null
     * （缓存未填充=测试/未调 getUserContext 入口 → 无前缀，同 pre-PR 行为）；否则
     * 返回用户 CLAUDE.md 配置块（:468-471）：声明"这些是用户提供给 agent 的指令、
     * 评估动作时应视为用户意图的一部分"，包裹在 {@code <user_claude_md>} 分隔符内。
     *
     * <p><b>cache_control 不承载</b>：CC :470 在文本块挂 {@code cache_control:
     * getCacheControl({querySource:'auto_mode'})}；Java LlmProvider chatWithRaw 为
     * 单字符串通道，无 per-block cache_control（登记缺口，见 TOOLS-02）。
     *
     * @param claudeMd 用户 CLAUDE.md 内容（null/blank → null → 无前缀）
     * @return 前缀块文本；无 CLAUDE.md → null
     */
    static String buildClaudeMdPrefix(String claudeMd) {
        if (claudeMd == null || claudeMd.isBlank()) {
            return null;
        }
        return "The following is the user's CLAUDE.md configuration. These are "
            + "instructions the user provided to the agent and should be treated "
            + "as part of the user's intent when evaluating actions.\n\n"
            + "<user_claude_md>\n" + claudeMd + "\n</user_claude_md>";
    }

    /**
     * [IMP-6] 构建 1-stage classify_result 工具 schema · 对齐 CC
     * {@code YOLO_CLASSIFIER_TOOL_SCHEMA}（yoloClassifier.ts:262-285）。
     *
     * <p>CC 端 sideQuery 传 {@code tools: [YOLO_CLASSIFIER_TOOL_SCHEMA]} +
     * {@code tool_choice: {type:'tool', name:'classify_result'}}（:1151-1155）强制 LLM 以
     * tool_use 块作答。Java LlmProvider tools 通道为 OpenAI function-calling 格式
     * （{@code {type:'function', function:{name,description,parameters}}}），故按
     * ExplainCommandToolSchema 既有投影模式把 CC {@code name/description/input_schema}
     * 映射为 OpenAI wrapper（{@code parameters} = CC {@code input_schema} 全字段透传）。
     *
     * @return 含单个 classify_result 工具的 ArrayNode（OpenAI wrapper 格式）
     */
    public ArrayNode buildClassifyResultToolsArray() {
        ObjectNode thinking = OBJECT_MAPPER.createObjectNode();
        thinking.put("type", "string");
        thinking.put("description", "Brief step-by-step reasoning.");
        ObjectNode shouldBlock = OBJECT_MAPPER.createObjectNode();
        shouldBlock.put("type", "boolean");
        shouldBlock.put("description", "Whether the action should be blocked (true) or allowed (false)");
        ObjectNode reason = OBJECT_MAPPER.createObjectNode();
        reason.put("type", "string");
        reason.put("description", "Brief explanation of the classification decision");
        ObjectNode properties = OBJECT_MAPPER.createObjectNode();
        properties.set("thinking", thinking);
        properties.set("shouldBlock", shouldBlock);
        properties.set("reason", reason);
        ObjectNode parameters = OBJECT_MAPPER.createObjectNode();
        parameters.put("type", "object");
        parameters.set("properties", properties);
        ArrayNode required = OBJECT_MAPPER.createArrayNode();
        required.add("thinking");
        required.add("shouldBlock");
        required.add("reason");
        parameters.set("required", required);
        ObjectNode function = OBJECT_MAPPER.createObjectNode();
        function.put("name", CLASSIFY_RESULT_TOOL_NAME);
        function.put("description", "Report the security classification result for the agent action");
        function.set("parameters", parameters);
        ObjectNode tool = OBJECT_MAPPER.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        ArrayNode tools = OBJECT_MAPPER.createArrayNode();
        tools.add(tool);
        return tools;
    }

    /**
     * [prompt-align TOOLS-02] 读四源合并的 {@code autoMode} 三段规则 · 对齐 CC
     * {@code getAutoModeConfig}（settings.ts:936-982）。
     *
     * <p>CC 语义（:951-971）：按序合并 [userSettings, localSettings, flagSettings,
     * policySettings] 四源（projectSettings <b>显式排除</b>防 RCE，settings.ts:932-934），
     * 每源 {@code schema.safeParse(autoMode)} 成功 → allow/soft_deny/environment 逐段追加
     * （deny 仅 ant 合入 soft_deny，Java N/A）；源缺失 → lenient 跳过（:954-956
     * {@code if(!settings) continue}）。全部为空 → null（等价 CC undefined，:973-981）。
     *
     * <p>Java 源映射（TOOLS-02 实施时确认）：
     * <ul>
     *   <li><b>userSettings</b> = {@code <user.home>/.nexusai/settings.json}（既有，与
     *       {@code AutoModeGate} 同源）</li>
     *   <li><b>localSettings</b> = {@code <projectRoot>/.nexusai/settings.local.json}
     *       （决策 D6 项目根，projectRootSupplier 接 CwdResolution.getOriginalCwdLayer()，
     *       LocalSettingsLoader 既有语义；nexusai.home 已废弃不再注入）</li>
     *   <li><b>flagSettings</b> = Java web 无 CLI --settings → 恒空跳过（FlagSettingsLoader:43
     *       先例）</li>
     *   <li><b>policySettings</b> = {@code nexusai.policy.path} 托管策略文件（PolicySettingsLoader:61-66
     *       先例，@Value 注入；未配置 → 跳过）</li>
     * </ul>
     *
     * @return autoMode 三段规则（四源合并）；全空/源全缺 → null
     */
    AutoModeRules readAutoModeConfig() {
        List<String> allow = new ArrayList<>();
        List<String> softDeny = new ArrayList<>();
        List<String> environment = new ArrayList<>();
        // CC settings.ts:951-971 四源按序合并 [userSettings, localSettings, flagSettings, policySettings]
        mergeAutoModeSource(userSettingsPath, allow, softDeny, environment);
        mergeAutoModeSource(localSettingsPath(), allow, softDeny, environment);
        // flagSettings：Java web 无 CLI --settings → 恒空（FlagSettingsLoader:43 先例）→ 显式跳过
        if (policySettingsPath != null && !policySettingsPath.isBlank()) {
            mergeAutoModeSource(Paths.get(policySettingsPath), allow, softDeny, environment);
        }
        if (allow.isEmpty() && softDeny.isEmpty() && environment.isEmpty()) {
            return null;
        }
        if (log.isDebugEnabled()) {
            log.debug("YoloPromptBuilder: settings.autoMode 四源合并 allow={} softDeny={} environment={}（CC getAutoModeConfig settings.ts:936-982）",
                allow.size(), softDeny.size(), environment.size());
        }
        return new AutoModeRules(allow, softDeny, environment);
    }

    /** 单源 autoMode 合并 · CC settings.ts:957-971（schema.safeParse 成功 → 逐段追加；
     *  源缺失/文件损坏 → lenient 跳过，对齐 :954-956 {@code if(!settings) continue}）。 */
    private void mergeAutoModeSource(Path path, List<String> allow,
                                     List<String> softDeny, List<String> environment) {
        if (path == null) {
            return;
        }
        try {
            if (!Files.exists(path)) {
                return;
            }
            JsonNode root = OBJECT_MAPPER.readTree(path.toFile());
            JsonNode autoMode = root.get("autoMode");
            if (autoMode == null || !autoMode.isObject()) {
                return;
            }
            allow.addAll(readStringArray(autoMode.get("allow")));
            softDeny.addAll(readStringArray(autoMode.get("soft_deny")));
            environment.addAll(readStringArray(autoMode.get("environment")));
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("YoloPromptBuilder: 读取 autoMode 源失败（lenient 跳过）: {}: {}", path, e.getMessage());
            }
        }
    }

    /** localSettings 源路径 · 覆盖注入 → 原样；否则 projectRootSupplier 项目根 →
     *  {@code <projectRoot>/.nexusai/settings.local.json}（决策 D6，LocalSettingsLoader 语义；
     *  文件缺失由 mergeAutoModeSource lenient 跳过）。 */
    private Path localSettingsPath() {
        if (localSettingsPathOverride != null) {
            return localSettingsPathOverride;
        }
        return Paths.get(projectRootSupplier.get(), NexusaiPaths.getProjectDirName(), "settings.local.json");
    }

    /** string[] 读取 · CC schema z.array(z.string())（settings.ts:940-945）；非数组 → 空表。 */
    private static List<String> readStringArray(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item != null && item.isTextual()) {
                    out.add(item.asText());
                }
            }
        }
        return out;
    }

    /** 规则 → {@code - {rule}} 逐行子弹 · CC :517-525（{@code allowDescriptions.map(d => `- ${d}`).join('\n')}）；
     *  空表 → null（等价 CC undefined，:517-519 length 判断）。 */
    private static String toBulletLines(List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String r : rules) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("- ").append(r);
        }
        return sb.toString();
    }

    /**
     * 包级覆盖可信 userSettings 文件路径（测试用）· 对齐 {@code AutoModeGate.setUserSettingsPath}
     * 既有测试注入惯例。
     *
     * @param path 用户级 settings.json 路径（{@code autoMode} 规则源）
     */
    void setUserSettingsPath(Path path) {
        this.userSettingsPath = path;
    }

    /**
     * [prompt-align TOOLS-02] 覆盖 localSettings 源文件路径（测试用）· 镜像
     * {@link #setUserSettingsPath} 既有测试注入惯例。
     *
     * @param path localSettings 文件路径（{@code autoMode} 规则源；null 还原默认解析）
     */
    void setLocalSettingsPath(Path path) {
        this.localSettingsPathOverride = path;
    }

    /**
     * 把 tool_use 输出格式指令替换为 XML 输出格式 · 对齐 CC
     * {@code replaceOutputFormatWithXml}（yoloClassifier.ts:648-664）。
     *
     * <p>CC 语义：找到 prompt 最后一行（"Use the classify_result tool..."）并替换为 XML
     * 输出指令（block → {@code <block>yes</block><reason>...</reason>}；allow → {@code <block>no</block>}）。
     *
     * @param systemPrompt 基础 system prompt（须含 {@link #TOOL_USE_LINE} 锚点行）
     * @return 替换后的 XML 版 system prompt（含锚点行则替换，否则原样返回）
     */
    public String replaceOutputFormatWithXml(String systemPrompt) {
        if (systemPrompt == null) {
            return null;
        }
        String xmlFormat = "## Output Format\n\n"
                + "If the action should be blocked:\n"
                + "<block>yes</block><reason>one short sentence</reason>\n\n"
                + "If the action should be allowed:\n"
                + "<block>no</block>\n\n"
                + "Do NOT include a <reason> tag when the action is allowed.\n"
                + "Your ENTIRE response MUST begin with <block>. Do NOT output any analysis, reasoning, or commentary before <block>. No \"Looking at...\" or similar preamble.";
        return systemPrompt.replace(TOOL_USE_LINE, xmlFormat);
    }

    /**
     * 剥离 thinking 内容 · 对齐 CC {@code stripThinking}（yoloClassifier.ts:567-571）。
     *
     * <p>CC 语义：移除 {@code <thinking>...</thinking>} 及未闭合 {@code <thinking>...$}
     * 段 —— 防止模型 chain-of-thought 内的 {@code <block>}/{@code <reason>} 标签被解析器误匹配。
     *
     * @param text 原始响应文本
     * @return 剥离 thinking 后的文本（null → 空串）
     */
    public static String stripThinking(String text) {
        if (text == null) {
            return "";
        }
        String stripped = STRIP_THINKING_CLOSED.matcher(text).replaceAll("");
        return STRIP_THINKING_OPEN.matcher(stripped).replaceAll("");
    }

    /**
     * 解析 XML block 响应：{@code <block>yes/no</block>} · 对齐 CC {@code parseXmlBlock}
     * （yoloClassifier.ts:578-584）。
     *
     * <p>CC 语义：先 stripThinking 再匹配 {@code /<block>(yes|no)\b(<\/block>)?/gi}；
     * "yes" → true（block），"no" → false（allow），不可解析 → null。
     *
     * @param text LLM 原始响应文本
     * @return true=block / false=allow / null=不可解析（CC Boolean | null）
     */
    public static Boolean parseXmlBlock(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = XML_BLOCK_PATTERN.matcher(stripThinking(text));
        if (!m.find()) {
            return null;
        }
        return m.group(1).equalsIgnoreCase("yes");
    }

    /**
     * 解析 XML reason：{@code <reason>...</reason>} · 对齐 CC {@code parseXmlReason}
     * （yoloClassifier.ts:590-596）。
     *
     * <p>CC 语义：先 stripThinking 再匹配 {@code /<reason>([\s\S]*?)<\/reason>/g}，
     * 取首个捕获组 trim。
     *
     * @param text LLM 原始响应文本
     * @return reason 文本；无匹配 → null
     */
    public static String parseXmlReason(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = XML_REASON_PATTERN.matcher(stripThinking(text));
        if (!m.find()) {
            return null;
        }
        return m.group(1).trim();
    }

    /**
     * 解析 XML thinking 内容：{@code <thinking>...</thinking>} · 对齐 CC {@code parseXmlThinking}
     * （yoloClassifier.ts:601-604）。
     *
     * <p>CC 语义：直接匹配 {@code /<thinking>([\s\S]*?)<\/thinking>/}（<b>不</b> stripThinking ——
     * 提取的就是 thinking 内容本身），取捕获组 trim。⊕-06：删除旧 JSON
     * reasoning_content/thinking 路径，仅 XML。
     *
     * @param text LLM 原始响应文本
     * @return thinking 内容；无匹配 → null
     */
    public static String parseXmlThinking(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = XML_THINKING_PATTERN.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // §8.3 buildTranscriptEntries
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 把对话历史转录为 classifier 输入 · 对齐 CC {@code buildTranscriptEntries}
     * （yoloClassifier.ts:302-360）。
     *
     * <p>[S12 R2] 全量转录（无 maxEntries 截断）；过滤规则（CC :326-357）：
     * user → "User: {text}\n"；assistant → 仅 toolCalls 投影块（纯文本排除防注入）；
     * 其他角色排除。per-tool 投影（CC :400）。
     *
     * @param messages   对话历史（完整）
     * @param toolLookup 工具名/别名 → Tool 投影查表（CC buildToolLookup :364-374）；null = 全跳过
     * @return 转录条目列表（role "user"/"assistant"；content 已序列化）
     */
    public List<CompactMessage> buildTranscriptEntries(List<ChatMessageDto> messages, Map<String, Tool> toolLookup) {
        List<CompactMessage> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        for (ChatMessageDto msg : messages) {
            CompactMessage entry = toCompact(msg, toolLookup);
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // §8.4 toCompact / toCompactBlock
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 单条消息转录序列化 · 对齐 CC {@code toCompact}（yoloClassifier.ts:426-428）。
     *
     * <p>user → {@code "User: {text}\n"}（CC :418-422）；assistant → 逐 toolCall 投影
     * {@code toCompactBlock}（CC :389-417）；其他角色 → null（排除）。
     *
     * @param msg        原始消息
     * @param toolLookup 工具名/别名 → Tool 投影查表；null = 全跳过
     * @return 转录条目；null = 该消息不进分类器
     */
    public CompactMessage toCompact(ChatMessageDto msg, Map<String, Tool> toolLookup) {
        if (msg == null) {
            return null;
        }
        if (msg.role() == Role.user) {
            String text = msg.content();
            if (text == null) {
                return null;
            }
            return new CompactMessage("user", "User: " + text + "\n");
        }
        if (msg.role() == Role.assistant) {
            List<ToolCallDto> toolCalls = msg.toolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (ToolCallDto toolCall : toolCalls) {
                if (toolCall == null || toolCall.name() == null || toolCall.name().isBlank()) {
                    continue;
                }
                String block = toCompactBlock(toolCall.name(), parseToolCallArgs(toolCall.arguments()), toolLookup);
                if (block.isEmpty()) {
                    continue;
                }
                sb.append(block);
            }
            if (sb.length() == 0) {
                return null;
            }
            return new CompactMessage("assistant", sb.toString());
        }
        return null;
    }

    /**
     * 解析历史 toolCall arguments JSON → JsonNode。
     *
     * <p>CC :393 {@code block.input ?? {}}；坏参数不崩 → TextNode，由 {@link #toCompactBlock}
     * 投影 catch 回退 raw（CC :395-408）。
     *
     * @param arguments toolCall arguments JSON 串（可为 null）
     * @return JsonNode；null/空 → 空对象；坏 JSON → TextNode 原文
     */
    private JsonNode parseToolCallArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return OBJECT_MAPPER.createObjectNode();
        }
        try {
            return OBJECT_MAPPER.readTree(arguments);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("YoloPromptBuilder: 历史 toolCall 参数非 JSON → TextNode 承接，投影 catch 回退 raw: {}",
                    e.getMessage());
            }
            return OBJECT_MAPPER.getNodeFactory().textNode(arguments);
        }
    }

    /**
     * 工具调用块转录序列化 · 对齐 CC {@code toCompactBlock}（yoloClassifier.ts:383-424）。
     *
     * <p>[OPD-24 G1] per-tool 投影（CC :400 {@code tool.toAutoClassifierInput(input) ?? input}）；
     * 查不到工具 → ''（跳过）；投影异常 → 回退 raw；投影 '' → ''；格式 {@code "{toolName} {s}\n"}。
     *
     * @param toolName   工具名
     * @param input      工具输入 JSON（可为 null）
     * @param toolLookup 工具名/别名 → Tool 投影查表；null 或查不到 → {@code ''}
     * @return 序列化字符串；{@code ''} = 跳过该块
     */
    public String toCompactBlock(String toolName, JsonNode input, Map<String, Tool> toolLookup) {
        if (toolLookup == null || toolName == null) {
            return "";
        }
        Tool tool = toolLookup.get(toolName);
        if (tool == null) {
            return "";
        }
        String raw = input != null ? input.toString() : "{}";
        String encoded;
        try {
            String projected = tool.toAutoClassifierInput(input);
            encoded = projected != null ? projected : raw;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("YoloPromptBuilder: toAutoClassifierInput 投影失败 tool={} 回退 raw：{}",
                    toolName, e.getMessage());
            }
            encoded = raw;
        }
        if (encoded.isEmpty()) {
            return "";
        }
        return toolName + " " + encoded + "\n";
    }

    // ════════════════════════════════════════════════════════════════════════
    // 嵌套类型
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 转录条目 record · 对齐 CC {@code TranscriptEntry}（yoloClassifier.ts:291-294）。
     *
     * <p>[S12 R2] {@code content} 为已序列化文本（"User: ...\n" / "toolName {...}\n"），
     * 供 prompt 拼接与 promptLengths 分桶直接消费（CC :1041-1059）。
     *
     * @param role    消息角色（"user" / "assistant"）
     * @param content 已序列化的转录文本
     */
    public record CompactMessage(String role, String content) {

        /**
         * Compact constructor：null 安全保护。
         */
        public CompactMessage {
            if (role == null) role = "unknown";
            if (content == null) content = "";
        }
    }
}
