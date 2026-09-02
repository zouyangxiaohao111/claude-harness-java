package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.permission.check.RuleQuery;
import com.nexusai.application.agent.permission.hook.RegisterSkillHooks;
import com.nexusai.application.agent.plugin.PluginIdentifier;
import com.nexusai.application.agent.plugin.PluginSchemas;
import com.nexusai.application.agent.skill.ArgumentSubstitution;
import com.nexusai.application.agent.skill.MalformedCommandException;
import com.nexusai.application.agent.skill.PromptShellExecutor;
import com.nexusai.application.agent.skill.SkillContentLoader;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.skill.SkillUsageTracking;
import com.nexusai.application.agent.subagent.AgentContext;
import com.nexusai.application.agent.subagent.SkillModelOverrideResolver;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.application.agent.tool.*;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.util.PluginOnlyPolicy;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import com.nexusai.model.command.PromptFnContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * SkillTool — 技能调用工具 · 对齐 CC tools/SkillTool/SkillTool.ts (1109 行)
 *
 * <h2>CC 对齐对照表</h2>
 * <table>
 *   <tr><th>CC 字段/方法</th><th>CC 行号</th><th>Java 实现</th></tr>
 *   <tr><td>name = SKILL_TOOL_NAME</td><td>constants.ts</td><td>{@link #name()}</td></tr>
 *   <tr><td>maxResultSizeChars = 100_000</td><td>SkillTool.ts:334</td><td>{@link #maxResultSizeChars()}</td></tr>
 *   <tr><td>inputSchema (skill + args)</td><td>SkillTool.ts:291-298</td><td>{@link #inputSchema()}</td></tr>
 *   <tr><td>outputSchema</td><td>SkillTool.ts:301-326</td><td>{@link #outputSchema()}</td></tr>
 *   <tr><td>description(input)</td><td>SkillTool.ts:342</td><td>{@link #description()} (无参兜底) + {@link #description(JsonNode)} (input-aware)</td></tr>
 *   <tr><td>prompt()</td><td>SkillTool.ts:344 / prompt.ts:173-196</td><td>{@link #prompt()}</td></tr>
 *   <tr><td>toAutoClassifierInput</td><td>SkillTool.ts:352</td><td>{@link #toAutoClassifierInput(JsonNode)}</td></tr>
 *   <tr><td>validateInput</td><td>SkillTool.ts:354-430</td><td>{@link #validateInput(JsonNode, ToolUseContext)}</td></tr>
 *   <tr><td>checkPermissions</td><td>SkillTool.ts:432-578</td><td>{@link #checkPermissions(JsonNode, ToolUseContext)}</td></tr>
 *   <tr><td>call() — inline skill expansion</td><td>SkillTool.ts:500+</td><td>{@link #execute(ToolUseBlock)}</td></tr>
 *   <tr><td>call() — fork routing</td><td>SkillTool.ts:622-632</td><td>{@link #doExecute} fork 分支 (context='fork' → executeForkedSkill)</td></tr>
 *   <tr><td>executeForkedSkill + onProgress</td><td>SkillTool.ts:122-289</td><td>{@link SubagentExecutor#executeForkedSkill} + {@link #buildForkProgressSink}</td></tr>
 * </table>
 *
 * <h2>Inline 技能扩展（对齐 CC inline context）</h2>
 * <p>技能内容展开到当前对话中（不启动 sub-agent）。这匹配大部分用户技能
 * 和 bundled 技能的行为（commit, review-pr, plan 等）。
 *
 * <h2>Fork 模式（对齐 CC SkillTool.ts:122-289 executeForkedSkill）</h2>
 * <p>context='fork' 技能真实启动隔离子 sub-agent 执行（subagentExecutor @Bean 注入,
 * CC 无 inline 降级）。fork 子代理收到技能内容指令（非用户 args）, 逐消息上报
 * skill_progress（子任务 a）, skill effort 合并进 agentDefinition（子任务 c）。
 */
public class SkillToolImpl implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SkillToolImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String SKILL_TOOL_NAME = "Skill";

    /**
     * CC original: {@code PLUGIN_ID_HASH_SALT}（utils/telemetry/pluginTelemetry.ts:39）·
     * plugin_id_hash 固定盐（跨仓跨站点相同，客户可反算已知插件名匹配遥测）。
     */
    private static final String PLUGIN_ID_HASH_SALT = "claude-plugin-telemetry-v1";

    /**
     * CC original: {@code BUILTIN_MARKETPLACE_NAME}（pluginTelemetry.ts:33，builtinPlugins.ts 内联
     * 常量绕过 commands.js 循环依赖）· 内置插件保留 marketplace 名。
     */
    private static final String BUILTIN_MARKETPLACE_NAME = "builtin";

    private final SkillRegistry registry;
    private final SkillContentLoader contentLoader;

    /**
     * [P0-1] SubagentExecutor · 用于 fork mode 真实化（启动隔离 sub-agent 执行 skill）.
     * 由 ToolRegistrationConfig.subagentExecutor() @Bean 经 setter 注入; fork 分支缺失时
     * 显式抛 IllegalStateException (fail loud, 对齐 CC fork 无降级), 不再 inline fallback
     * (旧 P2.2 fallback 语义已删除).
     */
    private SubagentExecutor subagentExecutor;

    /**
     * [Session H12 v2 Gap1 修复] RegisterSkillHooks · 技能执行时注册 frontmatter hooks
     * (对齐 CC processSlashCommand.tsx:877). 可选注入; 未注入时 skill frontmatter hooks
     * 不注册 (仅日志 skip, 不破坏既有路径).
     */
    private RegisterSkillHooks registerSkillHooks;

    /**
     * [P0-5] PromptShellExecutor · skill 内联 shell 注入 (对齐 CC promptShellExecution.ts:69-143).
     * 由 {@link com.nexusai.application.agent.config.ToolRegistrationConfig#promptShellExecutor} @Bean
     * 经 setter 注入; 构造器兜底 {@code new PromptShellExecutor()} (默认实现可运行,
     * PermissionPipeline 空则 fail-closed: 权限预检非 allow → 抛 MalformedCommandException).
     */
    private PromptShellExecutor promptShellExecutor;

    /**
     * [P1-15] SkillUsageTracking · 技能使用记录（对齐 CC SkillTool.ts:619 recordSkillUsage).
     * 由 SkillUsageTracking @Component 经 setter 注入; 可选注入 (POJO/测试可手动注入或留空),
     * 未注入时记录侧 debug 日志 skip, 不破坏既有执行链.
     */
    private SkillUsageTracking skillUsageTracking;

    public SkillToolImpl(SkillRegistry registry) {
        this.registry = registry;
        this.contentLoader = new SkillContentLoader();
        this.promptShellExecutor = new PromptShellExecutor();
    }

    /**
     * [P0-1] setter 注入 SubagentExecutor · 由 {@link com.nexusai.application.agent.config.ToolRegistrationConfig#subagentExecutor}
     * @Bean 注入 (required=false 保留测试手动注入能力). fork 分支执行期恒非 null.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSubagentExecutor(SubagentExecutor executor) {
        this.subagentExecutor = executor;
    }

    /**
     * [Session H12 v2 Gap1 修复] setter 注入 RegisterSkillHooks（Spring setter 注入,
     * 可选 required=false）· 测试可手动注入.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRegisterSkillHooks(RegisterSkillHooks registerSkillHooks) {
        this.registerSkillHooks = registerSkillHooks;
    }

    /**
     * [P1-6-CLEANUP-1] session AgentState 访问器 · sessionId → 该 session 的 AgentState.
     *
     * <p>fork finally（CC SkillTool.ts:287 {@code clearInvokedSkillsForAgent(agentId)}）需触达
     * invokedSkills 落点才能清理 fork 子 agent 的 skill 全文。与 P1-6 写入侧
     * {@code addInvokedSkill} 共用本访问器；写入侧接线确定后由调用方（ToolRegistrationConfig /
     * P1-6 写入侧）注入。
     *
     * <p>[session-id-short] 访问器入参 {@code Object}：String（short sessionId）→ 主会话 sessions map；
     * UUID（ctx.agentId()，后台化主会话 EVD-B 归因）→ 后台 agents map（SessionAgentStateRegistry
     * 双 map 按参类型路由）。
     *
     * <p>⚠ 泛型擦除规避：不用 {@code @Autowired} 注解注入 {@code Function<Object,AgentState>}
     * （Spring 按 Function 类型擦除匹配，可能误中无关 Function bean）—— 采用普通 setter +
     * null-safe 降级：未注入时 fork finally 仅日志 skip，不 NPE（权威清理在
     * {@link SubagentExecutor#cleanSubagentInvokedSkills}，runSubagentQueryLoop finally 接线）。
     */
    private java.util.function.Function<Object, AgentState> sessionStateResolver;

    public void setSessionStateResolver(java.util.function.Function<Object, AgentState> sessionStateResolver) {
        this.sessionStateResolver = sessionStateResolver;
    }

    /**
     * [P0-5] setter 注入 PromptShellExecutor · 由
     * {@link com.nexusai.application.agent.config.ToolRegistrationConfig#promptShellExecutor}
     * @Bean 注入 (required=false 保留测试手动注入 / 构造器兜底能力).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPromptShellExecutor(PromptShellExecutor promptShellExecutor) {
        this.promptShellExecutor = promptShellExecutor;
    }

    /**
     * [P1-15] setter 注入 SkillUsageTracking · SkillUsageTracking @Component 经 Spring 自动注入
     * (required=false 保留测试手动注入 / 留空跳过能力).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSkillUsageTracking(SkillUsageTracking skillUsageTracking) {
        this.skillUsageTracking = skillUsageTracking;
    }

    /**
     * [P3-4] Telemetry · 遥测适配层 (对齐 CC utils/analytics index.ts logEvent).
     * 由 Telemetry @Component 经 setter 注入 (required=false 保留测试手动注入 / 留空跳过能力).
     * 未注入时遥测点 debug 日志 skip, 不破坏既有执行链 (对齐 CC logEvent best-effort 语义).
     */
    private volatile Telemetry telemetry;

    /**
     * [P3-4] setter 注入 Telemetry · Telemetry @Component 经 Spring 自动注入
     * (required=false 保留测试手动注入 / 留空跳过能力; 未注入时遥测点 null-safe 跳过).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTelemetry(Telemetry telemetry) {
        this.telemetry = telemetry;
    }

    /**
     * [W6-1] 安装 {@link SkillModelOverrideResolver} 强档(opus)模型 DB 来源 · 注入
     * {@link ModelConfigResolver}（内含 SettingsMapper）后经
     * {@link SkillModelOverrideResolver#installDefaultOpusSource} 把强档模型源切换为
     * DB settings.strongModelId 反查（ANTHROPIC_DEFAULT_OPUS_MODEL env 路删除，用户拍板）。
     * {@code @Autowired(required=false)}：测试/孤立运行不注入 → 保持默认 CANONICAL_DEFAULT_OPUS。
     * 本类为 {@code resolveSkillModelOverride} 的生产消费方，作为 Spring 接线点（同层）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setModelConfigResolver(ModelConfigResolver modelConfigResolver) {
        if (modelConfigResolver != null) {
            SkillModelOverrideResolver.installDefaultOpusSource(modelConfigResolver);
        }
    }

    /**
     * [ALIGN-HOOKS-1 △-8] plugin-only 权限闸 settings supplier · 对齐 CC
     * processSlashCommand.tsx:874 {@code isRestrictedToPluginOnly('hooks')} 读
     * policySettings.strictPluginOnlyCustomization。
     *
     * <p>由 ToolRegistrationConfig#skillTool 注入 {@code ManagedPolicySettingsSupplier::all}
     * （模式照抄 HooksSettings.setManagedPolicySettingsSupplier :94-103）；缺省
     * {@code Map::of} → 不锁（PluginOnlyPolicy.isRestrictedToPluginOnly 对 null/空 policy
     * 返回 false，等价 CC 无政策不锁）。
     */
    private Supplier<Map<String, Object>> pluginOnlySettingsSupplier = Map::of;

    /**
     * [ALIGN-HOOKS-1 △-8] setter 注入 plugin-only settings supplier（Spring setter 注入,
     * 可选 required=false）· 测试可手动注入; null 忽略保留缺省 Map::of.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPluginOnlySettingsSupplier(Supplier<Map<String, Object>> supplier) {
        if (supplier != null) {
            this.pluginOnlySettingsSupplier = supplier;
        }
    }

    /**
     * [ALIGN-ST-1 △-2] EXPERIMENTAL_SKILL_SEARCH 门控 supplier · 对齐 CC
     * {@code SkillTool.ts:139-146 / :661-668}：{@code was_discovered} 字段仅当
     * {@code feature('EXPERIMENTAL_SKILL_SEARCH') && isSkillSearchEnabled()} 为真时发射，
     * feature-off 时整字段省略（CC {@code ...wasDiscoveredField} spread 为空对象）。
     *
     * <p>Java 等价 {@code isSkillSearchEnabled()} = {@code SkillDiscoveryPrefetch.isEnabled()}
     * （SkillDiscoveryPrefetch Javadoc 明示其为 isSkillSearchEnabled 映射，被 LlmAgentLoop A8
     * filterToBundledAndMcp 门控消费）；其底层 flag 为 {@code FeatureFlags.skillPrefetch()}
     * （CC query.ts:66 EXPERIMENTAL_SKILL_SEARCH）。由组合根（ToolRegistrationConfig）接线
     * {@code skillDiscoveryPrefetch::isEnabled}。
     *
     * <p>缺省 {@code () -> false}：SkillDiscoveryPrefetch 为 POJO（非 @Bean），生产未接线时
     * 恒 false → {@code was_discovered} 省略 —— 对齐 CC flag-off 可观测行为（字段不存在）。
     * 与旧「恒发方案 a」差异：旧实现无条件 put {@code was_discovered=false}，可观测行为
     * 与 CC feature-off（字段省略）不一致；本字段修正该 △-2 偏移。
     */
    private java.util.function.BooleanSupplier skillSearchEnabled = () -> false;

    /**
     * [ALIGN-ST-1 △-2] setter 注入 EXPERIMENTAL_SKILL_SEARCH 门控 supplier（Spring setter 注入,
     * 可选 required=false）· 测试可手动注入; null 忽略保留缺省 {@code () -> false}.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSkillSearchEnabled(java.util.function.BooleanSupplier supplier) {
        if (supplier != null) {
            this.skillSearchEnabled = supplier;
        }
    }

    /**
     * CommandSource 枚举 → CC source 字符串 · 对齐 CC command.ts:32
     * {@code SettingSource | 'builtin' | 'mcp' | 'plugin' | 'bundled'}, 供
     * {@link PluginOnlyPolicy#isSourceAdminTrusted(String)} (CC pluginOnlyPolicy.ts
     * ADMIN_TRUSTED_SOURCES) 判定。枚举名即 CC 值: BUILTIN→builtin / USER→user /
     * PLUGIN→plugin / MCP→mcp / BUNDLED→bundled; null → null (isSourceAdminTrusted
     * null → false, 等价 CC undefined → false)。
     *
     * <p>[P3-22] POLICY_SETTINGS 特殊映射为 {@code "policySettings"}（camelCase）· CC original:
     * {@code loadSkillsDir.ts:688} {@code loadSkillsFromSkillsDir(managedSkillsDir, 'policySettings')}
     * — managed 技能的 CC source 字面量是 {@code 'policySettings'}，而
     * {@code POLICY_SETTINGS.name().toLowerCase()} 会漂移为 {@code "policy_settings"}（snake_case），
     * ∉ CC ADMIN_TRUSTED_SOURCES 的 {@code 'policySettings'}（pluginOnlyPolicy.ts:40-46），导致
     * hooks 面被 strictPluginOnlyCustomization 锁定且执行 managed 技能时 Java 漏注册 frontmatter hooks
     * （WF6-01 △-1，EV-WF6-SH-101）。其余枚举名恒等于 CC 值，维持 name().toLowerCase()。
     */
    private static String toCcSource(CommandSource source) {
        if (source == null) {
            return null;
        }
        if (source == CommandSource.POLICY_SETTINGS) {
            return "policySettings";
        }
        return source.name().toLowerCase(Locale.ROOT);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tool 接口 core 方法
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public String name() {
        return SKILL_TOOL_NAME;
    }

    @Override
    public String description() {
        return "Execute a skill within the main conversation";
    }

    /**
     * 输入感知描述 · 对齐 CC {@code SkillTool.ts:342 description}（input-aware 函数）.
     *
     * <p><b>CC 真源（Read 实证）</b>：
     * <pre>
     * description: async ({ skill }) =&gt; `Execute skill: ${skill}`,   // SkillTool.ts:342
     * </pre>
     *
     * <p>权限弹窗消费方（CC useCanUseTool.tsx:56-60 {@code await tool.description(input, {...})};
     * Java {@code ToolPermissionGate.describe} / {@code WebSocketPermissionPrompter.describeFallback}
     * 均 {@code input != null} 时调用 {@code tool.description(input)}）据此展示
     * "Execute skill: &lt;skill&gt;"，用户能区分正在允许/拒绝哪个技能 —— 替代静态文案
     * "Execute a skill within the main conversation"。
     *
     * <p><b>兜底策略</b>：CC 权限流程 input 恒有值（LLM 按 schema 生成）；Java 测试/直调可能传
     * null 或缺 skill 字段 → 回退无参 {@link #description()}（Java Tool 接口强制抽象方法，
     * 不可删除），不改变 CC 语义。
     *
     * @param input 工具输入参数（{@code {skill, args}}，对齐 CC Tool.ts:386-393 description 首参）
     * @return "Execute skill: &lt;skill&gt;"；input null / skill 缺失时回退 {@link #description()}
     */
    @Override
    public String description(JsonNode input) {
        if (input != null) {
            JsonNode skillNode = input.get("skill");
            if (skillNode != null && !skillNode.asText().isBlank()) {
                if (log.isDebugEnabled()) {
                    log.debug("[SkillTool] description(input) 动态描述: skill={} (CC SkillTool.ts:342)",
                            skillNode.asText());
                }
                return "Execute skill: " + skillNode.asText();
            }
        }
        return description();
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode skill = props.putObject("skill");
        skill.put("type", "string");
        skill.put("description", "The skill name. E.g., \"commit\", \"review-pr\", or \"pdf\"");
        ObjectNode args = props.putObject("args");
        args.put("type", "string");
        args.put("description", "Optional arguments for the skill");
        schema.set("required", MAPPER.createArrayNode().add("skill"));
        return schema;
    }

    /**
     * 工具输出 Schema（union：inline / forked）· 对齐 CC {@code SkillTool.ts:301-326 outputSchema}.
     *
     * <p><b>CC 真源（SkillTool.ts:301-326，Read 实证）</b>：
     * <pre>
     * const inlineOutputSchema = z.object({                                                          // :303
     *   success: z.boolean().describe('Whether the skill is valid'),                                  // :304
     *   commandName: z.string().describe('The name of the skill'),                                    // :305
     *   allowedTools: z.array(z.string()).optional().describe('Tools allowed by this skill'),         // :306-309
     *   model: z.string().optional().describe('Model override if specified'),                         // :310
     *   status: z.literal('inline').optional().describe('Execution status'),                          // :311
     * })
     * const forkedOutputSchema = z.object({                                                          // :315
     *   success: z.boolean().describe('Whether the skill completed successfully'),                    // :316
     *   commandName: z.string().describe('The name of the skill'),                                    // :317
     *   status: z.literal('forked').describe('Execution status'),                                     // :318
     *   agentId: z.string().describe('The ID of the sub-agent that executed the skill'),              // :319-321
     *   result: z.string().describe('The result from the forked skill execution'),                    // :322
     * })
     * return z.union([inlineOutputSchema, forkedOutputSchema])                                        // :325
     * </pre>
     *
     * <p><b>zod v4 toJSONSchema 映射（zodToJsonSchema.ts:17-26 native toJSONSchema）</b>：
     * <ul>
     *   <li>{@code z.union} → 根 {@code anyOf} 两分支（分支顺序严格对齐 CC :325 参数序
     *       {@code [inline, forked]}）</li>
     *   <li>{@code z.literal('inline'/'forked')} → {@code {type:string, const:'inline'/'forked'}}</li>
     *   <li>{@code z.object}（zod v4 默认闭包）→ {@code additionalProperties:false}（对齐
     *       outputschema-strict-v1 先例 TodoWriteTool.java:512/:562）</li>
     * </ul>
     *
     * <p><b>分支字段严格隔离</b>（CC :303-312 / :315-323 逐字段核实）：inline 分支不含
     * result/agentId；forked 分支不含 allowedTools/model。Java 旧扁平 schema 把 result 放共享顶层
     * 偏离此契约（探查 §4.3 S1 已登记 schema-运行时漂移），本改动修复——Java 运行时数据已匹配
     * 两分支（SkillToolImpl.java:694-707 inline data{success,commandName,allowedTools?,model} 无 status；
     * :746-751 forkData{success,commandName,status:'forked',agentId,result}）。
     *
     * @return JSON Schema：根 anyOf 两分支（inline + forked）
     */
    @Override
    public JsonNode outputSchema() {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode anyOf = root.putArray("anyOf");
        // 分支顺序严格对齐 CC z.union 参数序 [inline, forked]（SkillTool.ts:325）
        anyOf.add(buildInlineOutputSchema());
        anyOf.add(buildForkedOutputSchema());

        if (log.isDebugEnabled()) {
            log.debug("[SkillTool] outputSchema() 构建完成: anyOf 2 分支 (inline + forked)，对齐 CC SkillTool.ts:301-326");
        }
        return root;
    }

    /**
     * inline 分支 JSON Schema · 对齐 CC {@code SkillTool.ts:303-312 inlineOutputSchema}.
     *
     * <p>required=[success, commandName]（CC 仅 allowedTools/model/status 为
     * {@code .optional()}）；{@code additionalProperties=false} 对齐 zod v4 z.object 默认闭包
     * （toJSONSchema 输出）。分支字段严格隔离：本分支不含 result/agentId（CC :303-312 逐字段核实）。
     *
     * @return inline 分支 object schema
     */
    private ObjectNode buildInlineOutputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        // CC original: success（SkillTool.ts:304）— z.boolean().describe('Whether the skill is valid')
        props.set("success", booleanType("Whether the skill is valid"));
        // CC original: commandName（SkillTool.ts:305）— z.string().describe('The name of the skill')
        props.set("commandName", stringType("The name of the skill"));
        // CC original: allowedTools（SkillTool.ts:306-309）— z.array(z.string()).optional()（Tools allowed by this skill）
        props.set("allowedTools", stringArrayType("Tools allowed by this skill"));
        // CC original: model（SkillTool.ts:310）— z.string().optional()（Model override if specified）
        props.set("model", stringType("Model override if specified"));
        // CC original: status（SkillTool.ts:311）— z.literal('inline').optional()（Execution status）
        props.set("status", buildLiteral("inline", "Execution status"));

        // CC inline required=[success, commandName]（仅 allowedTools/model/status 为 optional）
        schema.set("required", MAPPER.createArrayNode().add("success").add("commandName"));
        // zod v4 z.object 默认闭包 → additionalProperties:false
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * forked 分支 JSON Schema · 对齐 CC {@code SkillTool.ts:315-323 forkedOutputSchema}.
     *
     * <p>required=全 5 字段（CC 全必填）；{@code additionalProperties=false} 对齐 zod v4
     * z.object 默认闭包。分支字段严格隔离：本分支不含 allowedTools/model（CC :315-323 逐字段核实）。
     *
     * @return forked 分支 object schema
     */
    private ObjectNode buildForkedOutputSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        // CC original: success（SkillTool.ts:316）— z.boolean().describe('Whether the skill completed successfully')
        props.set("success", booleanType("Whether the skill completed successfully"));
        // CC original: commandName（SkillTool.ts:317）— z.string().describe('The name of the skill')
        props.set("commandName", stringType("The name of the skill"));
        // CC original: status（SkillTool.ts:318）— z.literal('forked')（Execution status）
        props.set("status", buildLiteral("forked", "Execution status"));
        // CC original: agentId（SkillTool.ts:319-321）— z.string().describe('The ID of the sub-agent that executed the skill')
        props.set("agentId", stringType("The ID of the sub-agent that executed the skill"));
        // CC original: result（SkillTool.ts:322）— z.string().describe('The result from the forked skill execution')
        props.set("result", stringType("The result from the forked skill execution"));

        // CC forked required=全 5 字段（success/commandName/status/agentId/result 全必填）
        schema.set("required", MAPPER.createArrayNode()
                .add("success").add("commandName").add("status").add("agentId").add("result"));
        // zod v4 z.object 默认闭包 → additionalProperties:false
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * boolean 属性 JSON Schema 辅助 · CC original: {@code z.boolean().describe(desc)}
     * （SkillTool.ts:304/:316）。参考 SubagentTool.java:726-736 helper 风格。
     */
    private static ObjectNode booleanType(String description) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("type", "boolean");
        n.put("description", description);
        return n;
    }

    /**
     * string 属性 JSON Schema 辅助 · CC original: {@code z.string().describe(desc)}
     * （SkillTool.ts:305/:310/:317/:319-321/:322）。参考 SubagentTool.java:726-730 stringType 风格。
     */
    private static ObjectNode stringType(String description) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("type", "string");
        n.put("description", description);
        return n;
    }

    /**
     * string[] 属性 JSON Schema 辅助 · CC original: {@code z.array(z.string()).describe(desc)}
     * （SkillTool.ts:306-309 allowedTools）。
     */
    private static ObjectNode stringArrayType(String description) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("type", "array");
        n.putObject("items").put("type", "string");
        n.put("description", description);
        return n;
    }

    /**
     * z.literal → {type:string, const:...} JSON Schema 辅助 · CC original:
     * {@code z.literal(value).describe(desc)}（SkillTool.ts:311 status:'inline' / :318 status:'forked'）。
     * zod v4 toJSONSchema 把 {@code z.literal} 物化为 {@code {type:string, const:value}}。
     */
    private static ObjectNode buildLiteral(String value, String description) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("type", "string");
        n.put("const", value);
        n.put("description", description);
        return n;
    }

    @Override
    public long maxResultSizeChars() {
        return 100_000L;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return false; // 技能扩展不应并发
    }

    @Override
    public boolean isReadOnly(JsonNode input) {
        return true; // 技能本身不修改文件，由展开后的工具调用决定
    }

    // ════════════════════════════════════════════════════════════════════════
    // s05 新增接口方法（对齐 CC Tool.ts buildTool）
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public String userFacingName() {
        return SKILL_TOOL_NAME;
    }

    @Override
    public String prompt() {
        // 对齐 CC SkillTool.ts:344 prompt: async () => getPrompt(getProjectRoot())
        // 静态文本迁入 SkillToolPrompt.getPrompt()（P2-3，含 ms-office-suite:pdf 示例行，逐字对齐 prompt.ts:174-195）
        return SkillToolPrompt.getPrompt();
    }

    @Override
    public String toAutoClassifierInput(JsonNode input) {
        // 对齐 CC SkillTool.ts:352 toAutoClassifierInput: ({ skill }) => skill ?? ''
        // input==null / skill 缺失 / 显式 null 字面量 → 空串（CC nullish 合并）；有值 → asText()（不加 trim）
        // ⚠ NullNode.asText() 本仓库 Jackson 版本返回字面量 "null"（非空串），故显式 isNull() 单独兜底
        if (input == null) return "";
        JsonNode skillNode = input.get("skill");
        String result = (skillNode == null || skillNode.isNull()) ? "" : skillNode.asText();
        if (log.isDebugEnabled()) {
            log.debug("[SkillTool] toAutoClassifierInput: skill 解析完成, 长度={} (CC SkillTool.ts:352 skill ?? '')",
                result.length());
        }
        return result;
    }

    @Override
    public String renderToolUseMessage(JsonNode input) {
        if (input == null) return null;
        JsonNode skillNode = input.get("skill");
        if (skillNode != null) {
            return "Invoking skill: " + skillNode.asText();
        }
        return null;
    }

    /**
     * 搜索提示 · 对齐 CC SkillTool.ts:333 searchHint = 'invoke a slash-command skill'
     *
     * <p>Tool 接口契约成员 override（G5 提升，CC Tool.ts:378）。消费方为 CC ToolSearchTool.ts:243/:264
     * （hintNormalized 关键词匹配，:283-285 命中 +4 评分）；Java 侧 ToolSearchTool.java:484/:500/:518-520
     * 已接线（关键词匹配 +4 分，探查 skill-tool R1 实证，OPD-23 已闭环）。
     */
    @Override
    public String searchHint() {
        return "invoke a slash-command skill";
    }

    // ════════════════════════════════════════════════════════════════════════
    // mapToToolResultBlockParam · 对齐 CC SkillTool.ts:843-862
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 技能工具结果 → Anthropic {@code tool_result} 块参数 · 对齐 CC
     * {@code SkillTool.ts:843-862 mapToolResultToToolResultBlockParam}.
     *
     * <p><b>CC 真源（Read 实证）</b>：
     * <pre>
     * mapToolResultToToolResultBlockParam(result: Output, toolUseID: string): ToolResultBlockParam {
     *   if ('status' in result &amp;&amp; result.status === 'forked') {       // :848
     *     return { type: 'tool_result', tool_use_id: toolUseID,          // :850-851
     *              content: `Skill "${result.commandName}" completed (forked execution).\n\nResult:\n${result.result}` }  // :852
     *   }
     *   return { type: 'tool_result', tool_use_id: toolUseID,            // :858-859
     *            content: `Launching skill: ${result.commandName}` }     // :860
     * }
     * </pre>
     *
     * <p>Java 端消费：{@code result.data()} 为 SkillTool 生产的数据（inline
     * {@code {success,commandName,allowedTools?,model?}} 无 status / forked
     * {@code {success,commandName,status:'forked',agentId,result}}），以 JSON 串形式承载
     * （{@link ToolResult#success(String, String)}）。本实现解析 data 后按 {@code status}
     * 判别两分支，输出 3 键 Map（{@code tool_use_id}/{@code type}/{@code content}，无
     * {@code is_error}），键名对齐 {@link ToolResultBlockParam} 的 {@code @JsonProperty} 序列化
     * （{@code tool_use_id}/{@code type}/{@code content}，ToolResultBlockParam.java:35-39）。
     *
     * <p><b>isError / 不可解析防御</b>：CC mapper 仅在成功路径被调
     * （{@code toolExecution.ts:1292-1295} 位于 {@code endToolExecutionSpan({success:true})}
     * (:1282) 之后），故 {@code isError==true} 或 data 不可解析 / 缺 {@code commandName}
     * 时返回 {@code Map.of()}（不渲染 "Launching skill: X" 文案）—— 对齐 CC 无错误路径调用。
     * commandName 缺失防御返回空 Map 而非模拟 CC JS 'undefined' 文本
     * （CC zod union 保证必填；Java 端无生产调用暂不 fail-loud）。
     *
     * @param result 工具执行结果（{@link ToolResult ToolResult&lt;?&gt;}；SkillTool 的 data 为元数据 JSON 串）
     * @return 3 键 {@code {tool_use_id, type:'tool_result', content}}；isError / 不可解析 → 空 Map
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(AgentToolResult<?> result, String toolUseId, boolean isError) {
        // ① isError → null（CC mapper 仅成功路径被调 toolExecution.ts:1292-1295）
        if (isError) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillTool] mapToToolResultBlockParam 跳过: isError=true (CC mapper 仅成功路径被调 toolExecution.ts:1292-1295)");
            }
            return null;
        }
        Object rawData = result.data();
        if (rawData == null) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillTool] mapToToolResultBlockParam 跳过: data=null");
            }
            return null;
        }

        // ② 解析 data（SkillTool 生产 data 为 ToolResult<String> JSON 串；泛型 ? 兼容 String + JsonNode）
        JsonNode node;
        if (rawData instanceof String dataStr) {
            try {
                node = MAPPER.readTree(dataStr);
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[SkillTool] mapToToolResultBlockParam 跳过: data 不可解析为 JSON, err={} (CC 输出 union 保证合法)",
                            e.getMessage());
                }
                return null;
            }
        } else if (rawData instanceof JsonNode dataNode) {
            node = dataNode;
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[SkillTool] mapToToolResultBlockParam 跳过: data 非 String/JsonNode ({}), 类型不受支持",
                        rawData.getClass().getName());
            }
            return null;
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillTool] mapToToolResultBlockParam 跳过: data 为空 JSON 节点");
            }
            return null;
        }

        // ③ commandName 缺失 → Map.of()（防御；CC zod union 保证必填，Java 端不模拟 'undefined' 文本）
        String commandName = node.path("commandName").asText(null);
        if (commandName == null || commandName.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillTool] mapToToolResultBlockParam 跳过: data 缺 commandName (CC SkillTool.ts:305/:317 必填)");
            }
            return null;
        }

        // ④ status 判别两分支（CC SkillTool.ts:848-854 forked / :857-861 inline 默认）
        String status = node.path("status").asText(null);
        String content;
        if ("forked".equals(status)) {
            // CC original: `Skill "${result.commandName}" completed (forked execution).\n\nResult:\n${result.result}`（SkillTool.ts:852）
            content = "Skill \"" + commandName + "\" completed (forked execution).\n\nResult:\n"
                    + node.path("result").asText("");
        } else {
            // CC original: `Launching skill: ${result.commandName}`（SkillTool.ts:860）
            content = "Launching skill: " + commandName;
        }

        // ⑤ ToolResultBlockParam（CC :849-861；is_error 成功路径恒 false）
        if (log.isDebugEnabled()) {
            log.debug("[SkillTool] mapToToolResultBlockParam 完成: commandName={} status={} content长度={} (CC SkillTool.ts:843-862)",
                    commandName, status != null ? status : "(inline 无 status)", content.length());
        }
        return new ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }

    // ════════════════════════════════════════════════════════════════════════
    // validateInput · 对齐 CC SkillTool.ts:354-430
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public ValidationResult validateInput(JsonNode input, ToolUseContext context) {
        JsonNode skillNode = input.get("skill");
        if (skillNode == null || skillNode.asText().isBlank()) {
            return ValidationResult.fail("1", "Skill name is required");
        }

        String skillName = skillNode.asText().trim();

        // [P3-4 T5] 移除前导 / · 对齐 CC SkillTool.ts:366-372：
        //   hasLeadingSlash 为 true 时先 logEvent('tengu_skill_tool_slash_prefix', {})，再 substring(1)。
        //   CC 遥测采集"用户带了斜杠前缀"的信号（前端展示优化 / 统计用户习惯）。
        boolean hasLeadingSlash = skillName.startsWith("/");
        if (hasLeadingSlash) {
            // 对齐 CC SkillTool.ts:368 logEvent('tengu_skill_tool_slash_prefix', {}) — 空属性 Map
            if (telemetry != null) {
                telemetry.recordEvent("tengu_skill_tool_slash_prefix", Map.of());
                if (log.isDebugEnabled()) {
                    log.debug("[SkillTool] 遥测 T5: 前导斜杠触发 tengu_skill_tool_slash_prefix (CC SkillTool.ts:366-369)");
                }
            }
            skillName = skillName.substring(1);
        }

        // [P1-7 TODO] remote canonical 拦截槽位 · CC SkillTool.ts:377-396（ant-only 实验特性）：
        //   feature('EXPERIMENTAL_SKILL_SEARCH') && USER_TYPE==='ant' 时，在本地命令查找前
        //   stripCanonicalPrefix(normalizedCommandName) 非 null → getDiscoveredRemoteSkill(slug) 无 meta
        //   → errorCode 6「Remote skill X was not discovered...」；有 meta → pass。Java 无 remote execution
        //   + 非 ant → 不实现；契约骨架见 skillsearch/SkillSearchRemoteExecution.java（接线待对接）。
        // 查找命令（P2-9: 含 MCP thread-in · CC SkillTool.ts:399-402 以 getAllCommands(context) 为搜索基座）
        Command cmd = registry.findCommandIncludingMcp(skillName);
        if (cmd == null) {
            return ValidationResult.fail("2", "Unknown skill: " + skillName);
        }

        // 检查 disableModelInvocation
        if (Boolean.TRUE.equals(cmd.getDisableModelInvocation())) {
            return ValidationResult.fail("4",
                "Skill " + skillName + " cannot be used with Skill tool due to disable-model-invocation");
        }

        // [P2-1] 检查是否为 prompt 类型技能 · 对齐 CC SkillTool.ts:420-427 errorCode 5
        //   真源：if (foundCommand.type !== 'prompt') { return { result: false,
        //   message: `Skill ${normalizedCommandName} is not a prompt-based skill`, errorCode: 5 } }
        //   位于 errorCode 4（:412-418）之后、return { result: true }（:429）之前。
        //   当前生产全部命令 type='prompt'（Command.java:104 默认），非 prompt 命令（如未来
        //   workflow 技能）不得被 Skill 工具当作 prompt 技能展开 → 防御性契约完整化。
        if (!"prompt".equals(cmd.getType())) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillTool] validateInput 拒绝: skill={} type={} 非 prompt (CC SkillTool.ts:421-427 errorCode 5)",
                        skillName, cmd.getType());
            }
            return ValidationResult.fail("5", "Skill " + skillName + " is not a prompt-based skill");
        }

        return ValidationResult.pass();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [P3-4] tengu_skill_tool_invocation 遥测 helpers · 对齐 CC SkillTool.ts:152-203 (fork) / :675-726 (inline)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 构建 {@code tengu_skill_tool_invocation} 遥测属性 Map · 对齐 CC SkillTool.ts:152-203 (fork)
     * + :675-726 (inline) 共用字段集，两路径共用一个构建方法（避免双实现漂移）。
     *
     * <p><b>字段清单（CC snake_case 逐字段标注）</b>：
     * <ul>
     *   <li>{@code command_name} — sanitized（builtin/bundled/官方 marketplace plugin→原名，否则
     *       'custom'，CC :654-659/:136-137；官方 marketplace 判别依赖 pluginInfo，见下方 △-1 注记）</li>
     *   <li>{@code _PROTO_skill_name} — 未脱敏技能名，路由特权 BQ 列（CC :155-159/:678-682）</li>
     *   <li>{@code execution_context} — 'inline' / 'fork'（CC :161/:684）</li>
     *   <li>{@code invocation_trigger} — queryDepth&gt;0 ? 'nested-skill' : 'claude-proactive'（CC :162-164/:685-687）</li>
     *   <li>{@code query_depth} — ctx.queryTracking() Map 的 depth 键，缺省 0（CC :150/:673）</li>
     *   <li>{@code parent_agent_id} — AgentContext.getAgentContext()?.agentId，null 省略（CC :151/:166-169/:674/:689-692）</li>
     *   <li>{@code was_discovered} — EXPERIMENTAL_SKILL_SEARCH 门控发射；ctx.discoveredSkillNames()
     *       含 commandName（CC :139-146/:661-668；feature-off 时整字段省略，见 △-2 注记）</li>
     * </ul>
     *
     * <p><b>决策注记</b>：
     * <ul>
     *   <li><b>[ALIGN-ST-1 △-2] was_discovered 门控发射（已修复）</b>：CC 以
     *       {@code feature('EXPERIMENTAL_SKILL_SEARCH') && isSkillSearchEnabled()} 门控该字段，
     *       feature-off 时省略（spread 空对象）。Java 旧实现恒 put {@code was_discovered=false}
     *       （「恒发方案 a」）偏离 CC 可观测行为；现改经 {@link #skillSearchEnabled} 门控
     *       （缺省 false → 省略），与 CC flag-off 一致。</li>
     *   <li><b>[FIX-B5 拍板#8] sanitize 官方 marketplace 判别（已接线）</b>：CC
     *       {@code isOfficialSkill = command.type==='prompt' && isOfficialMarketplaceSkill(command)}
     *       （SkillTool.ts:656-657/:935-942），依赖 {@code command.pluginInfo.repository} 判
     *       marketplace 是否官方。Java {@link Command} pluginInfo 字段已由 CI-04 落地
     *       （Command.java:203 + LoadPluginCommands.createPluginCommand:411 置值），本方法经
     *       {@link #isOfficialMarketplaceSkill} 判别：plugin 源 + repository 解析出 marketplace
     *       且 ∈ ALLOWED_OFFICIAL_MARKETPLACE_NAMES（PluginSchemas，CC schemas.ts:19-28）→ 保留原名，
     *       否则 'custom'（对齐 CC :658-659）。</li>
     *   <li><b>[FIX-B5 拍板#8] plugin 字段块（已回填）</b>：CC 对 plugin 源技能发射
     *       {@code _PROTO_plugin_name/_PROTO_marketplace_name/plugin_name/plugin_repository/
     *       buildPluginCommandTelemetryFields}（:185-202/:710-725，含第三方 plugin 技能，不 gate
     *       于 ant）。Java 经 {@link #appendPluginTelemetryFields} 消费 {@code command.pluginInfo}
     *       （pluginManifest.name + repository）回填整块（pluginInfo 读侧 NEW-GAP-V-CI-1-2 闭环）。</li>
     *   <li><b>ant-only 字段省略</b>：skill_name/skill_source/skill_loaded_from/skill_kind 为 CC
     *       USER_TYPE==='ant' 门控（:171-184/:694-709）；Java 默认非 ant（QueryConfigAutoConfiguration.isAnt
     *       未接入 skill-search）→ 省略（等价 CC 非 ant 部署）。</li>
     * </ul>
     *
     * @param commandName      技能名（已剥前导 /；CC commandName）
     * @param cmd              已解析命令（sanitized 判别源；null → 'custom'）
     * @param executionContext 'inline' 或 'fork'
     * @param ctx              工具调用上下文（可为 null → query_depth=0 / was_discovered 不发射 / 无 parent）
     * @return 遥测属性 Map（LinkedHashMap 保持键序，供 recordEvent 消费）
     */
    private Map<String, Object> buildInvocationTelemetry(String commandName, Command cmd,
                                                         String executionContext, ToolUseContext ctx) {
        // CC :654-659 / :136-137 sanitizedCommandName:
        //   isBuiltIn = builtInCommandNames().has(commandName)（Java 等价 source==BUILTIN）
        //   isBundled = command.source === 'bundled'（Java source==BUNDLED）
        //   isOfficialSkill = isOfficialMarketplaceSkill(command)（SkillTool.ts:656-657/:935-942，
        //       FIX-B5 拍板#8 已接线：plugin 源 + repository 解析 marketplace ∈ 官方名单 → 保留原名）
        //   sanitized = isBuiltIn || isBundled || isOfficialSkill ? commandName : 'custom'
        CommandSource source = cmd != null ? cmd.getSource() : null;
        boolean isBuiltIn = source == CommandSource.BUILTIN;
        boolean isBundled = source == CommandSource.BUNDLED;
        boolean isOfficialSkill = isOfficialMarketplaceSkill(cmd);
        String sanitized = (isBuiltIn || isBundled || isOfficialSkill) ? commandName : "custom";

        // CC :150/:673 queryDepth = context.queryTracking?.depth ?? 0（Integer 兜底 Number）
        int queryDepth = 0;
        if (ctx != null && ctx.queryTracking() != null) {
            Object depth = ctx.queryTracking().get("depth");
            if (depth instanceof Integer i) {
                queryDepth = i;
            } else if (depth instanceof Number n) {
                queryDepth = n.intValue();
            }
        }
        // CC :162-164/:685-687 invocation_trigger
        String invocationTrigger = queryDepth > 0 ? "nested-skill" : "claude-proactive";

        // CC :151/:674 parentAgentId = getAgentContext()?.agentId（null/blank → 省略字段）
        String parentAgentId = resolveParentAgentId();

        // CC :139-146/:661-668 was_discovered（ALIGN-ST-1 △-2 门控发射：
        //   CC 仅当 feature('EXPERIMENTAL_SKILL_SEARCH') && isSkillSearchEnabled() 时发射，
        //   feature-off 时字段省略；Java 经 skillSearchEnabled 门控（缺省 false → 省略）。
        //   值为 ctx.discoveredSkillNames 含 commandName。）
        boolean wasDiscovered = ctx != null && ctx.discoveredSkillNames() != null
                && ctx.discoveredSkillNames().contains(commandName);

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("command_name", sanitized);                       // CC original: command_name（SkillTool.ts:154/:677）
        attrs.put("_PROTO_skill_name", commandName);                // CC original: _PROTO_skill_name（SkillTool.ts:158/:681）
        attrs.put("execution_context", executionContext);           // CC original: execution_context（SkillTool.ts:160-161/:683-684）
        attrs.put("invocation_trigger", invocationTrigger);         // CC original: invocation_trigger（SkillTool.ts:162-164/:685-687）
        attrs.put("query_depth", queryDepth);                       // CC original: query_depth（SkillTool.ts:165/:688）
        if (parentAgentId != null && !parentAgentId.isBlank()) {
            attrs.put("parent_agent_id", parentAgentId);            // CC original: parent_agent_id（SkillTool.ts:166-169/:689-692）
        }
        if (skillSearchEnabled.getAsBoolean()) {
            attrs.put("was_discovered", wasDiscovered);             // CC original: was_discovered（SkillTool.ts:139-146/:661-668，feature-gated）
        }
        // CC :185-202/:710-725 plugin 字段块（FIX-B5 拍板#8 回填）· command.pluginInfo truthy 时
        //   spread：_PROTO_plugin_name/_PROTO_marketplace_name/plugin_name/plugin_repository +
        //   buildPluginCommandTelemetryFields（plugin_id_hash/plugin_scope/...）。不 gate 于 ant。
        appendPluginTelemetryFields(attrs, cmd, isOfficialSkill);
        return attrs;
    }

    /**
     * 追加 plugin 遥测字段块 · CC original: SkillTool.ts:185-202（fork）/ :710-725（inline）
     * {@code ...(command.pluginInfo && {...})}。
     *
     * <p><b>CC 字段清单（逐字段标注）</b>：
     * <ul>
     *   <li>{@code _PROTO_plugin_name} — 未脱敏 pluginManifest.name，路由特权 BQ 列
     *       （PII-tagged，:189-190/:712-713）</li>
     *   <li>{@code _PROTO_marketplace_name} — 未脱敏 marketplace（repo 解析，:191-194/:714-717，
     *       仅 marketplace 非空时）</li>
     *   <li>{@code plugin_name} — redacted 脱敏：isOfficialSkill ? 原名 : 'third-party'
     *       （:195-197/:718-720）</li>
     *   <li>{@code plugin_repository} — redacted 脱敏：isOfficialSkill ? repository : 'third-party'
     *       （:198-200/:721-723）</li>
     *   <li>{@code buildPluginCommandTelemetryFields} — plugin_id_hash/plugin_scope/
     *       plugin_name_redacted/marketplace_name_redacted/is_official_plugin
     *       （:201/:724，pluginTelemetry.ts:133-183）</li>
     * </ul>
     *
     * <p>前置条件（写侧契约）：plugin 源命令由 {@code LoadPluginCommands.createPluginCommand}
     * （loadPluginCommands.ts:317-320）置 {@code new PluginInfo(new PluginManifest(pluginName), sourceName)}，
     * pluginManifest 恒非 null —— 本方法仅在 pluginInfo + pluginManifest 均非 null 时发射（CC 直接访问
     * pluginManifest.name，等同契约）。第三方 plugin 技能（非官方 marketplace）照发（不 gate 于 ant，
     * CC :185-202 无 ant 守卫）。
     *
     * @param attrs          遥测属性 Map（LinkedHashMap，保持键序）
     * @param cmd            已解析命令（pluginInfo 读源）
     * @param isOfficialSkill isOfficialMarketplaceSkill 判别结果（CC :134/:656-657）
     */
    private static void appendPluginTelemetryFields(Map<String, Object> attrs, Command cmd, boolean isOfficialSkill) {
        if (cmd == null || cmd.getPluginInfo() == null || cmd.getPluginInfo().pluginManifest() == null) {
            return;
        }
        Command.PluginInfo pluginInfo = cmd.getPluginInfo();
        String pluginName = pluginInfo.pluginManifest().name();
        String repository = pluginInfo.repository();
        // CC :147-149/:669-672 pluginMarketplace = parsePluginIdentifier(repository).marketplace
        String marketplace = PluginIdentifier.parse(repository != null ? repository : "").marketplace();
        // CC :189-190/:712-713 _PROTO_plugin_name（PII-tagged 特权 BQ 列，未脱敏）
        attrs.put("_PROTO_plugin_name", pluginName);
        // CC :191-194/:714-717 _PROTO_marketplace_name（仅 marketplace 非空时 spread）
        if (marketplace != null && !marketplace.isEmpty()) {
            attrs.put("_PROTO_marketplace_name", marketplace);
        }
        // CC :195-197/:718-720 plugin_name（redacted：官方保留原名，第三方 → 'third-party'）
        attrs.put("plugin_name", isOfficialSkill ? pluginName : "third-party");
        // CC :198-200/:721-723 plugin_repository（redacted：官方保留原名，第三方 → 'third-party'）
        attrs.put("plugin_repository", isOfficialSkill ? repository : "third-party");
        // CC :201/:724 ...buildPluginCommandTelemetryFields（plugin_id_hash/plugin_scope/...）
        attrs.putAll(buildPluginCommandTelemetryFields(pluginName, marketplace));
    }

    /**
     * CC original: {@code isOfficialMarketplaceSkill}（SkillTool.ts:935-942）。
     *
     * <p>plugin 源 + {@code pluginInfo.repository} 非空 → 解析 marketplace ∈ 官方名单
     * （{@link PluginSchemas#ALLOWED_OFFICIAL_MARKETPLACE_NAMES}，CC schemas.ts:19-28）才判官方。
     *
     * @param cmd 待判别命令（null / 非 plugin 源 / 无 pluginInfo / repository 空 → false）
     * @return 是否官方 marketplace 保留名技能
     */
    private static boolean isOfficialMarketplaceSkill(Command cmd) {
        if (cmd == null || cmd.getSource() != CommandSource.PLUGIN || cmd.getPluginInfo() == null) {
            return false;
        }
        String repository = cmd.getPluginInfo().repository();
        if (repository == null || repository.isEmpty()) {
            return false;
        }
        return isOfficialMarketplaceName(PluginIdentifier.parse(repository).marketplace());
    }

    /**
     * CC original: {@code isOfficialMarketplaceName}（pluginIdentifier.ts:75-82）。
     *
     * <p>marketplace 非 undefined（Java null 等价 undefined；空串 ∈ set 天然 false）且
     * lower-case 后 ∈ {@link PluginSchemas#ALLOWED_OFFICIAL_MARKETPLACE_NAMES}。
     */
    private static boolean isOfficialMarketplaceName(String marketplace) {
        return marketplace != null && !marketplace.isEmpty()
                && PluginSchemas.ALLOWED_OFFICIAL_MARKETPLACE_NAMES.contains(marketplace.toLowerCase(Locale.ROOT));
    }

    /**
     * CC original: {@code buildPluginCommandTelemetryFields} + {@code buildPluginTelemetryFields}
     * （utils/telemetry/pluginTelemetry.ts:133-183）· 共享 plugin 遥测字段构建器（SkillTool 调用方
     * 传 managedNames=null，pluginTelemetry.ts:166-171 注释：per-invocation 行可经 plugin_id_hash
     * join 会话级 tengu_plugin_enabled_for_session 恢复 plugin_scope）。
     *
     * @param pluginName 插件名（CC pluginManifest.name）
     * @param marketplace marketplace（parsePluginIdentifier(repository).marketplace，可 null）
     * @return plugin_id_hash/plugin_scope/plugin_name_redacted/marketplace_name_redacted/is_official_plugin
     */
    private static Map<String, Object> buildPluginCommandTelemetryFields(String pluginName, String marketplace) {
        Map<String, Object> fields = new LinkedHashMap<>();
        // CC :144 getTelemetryPluginScope(name, marketplace, managedNames=null)
        String scope = telemetryPluginScope(pluginName, marketplace);
        // CC :147-148 isAnthropicControlled = scope==='official' || scope==='default-bundle'
        boolean isAnthropicControlled = "official".equals(scope) || "default-bundle".equals(scope);
        fields.put("plugin_id_hash", hashPluginId(pluginName, marketplace));          // CC :150-153
        fields.put("plugin_scope", scope);                                            // CC :154-155
        fields.put("plugin_name_redacted", isAnthropicControlled ? pluginName : "third-party");  // CC :156-158
        fields.put("marketplace_name_redacted", isAnthropicControlled && marketplace != null
                && !marketplace.isEmpty() ? marketplace : "third-party");             // CC :159-161
        fields.put("is_official_plugin", isAnthropicControlled);                      // CC :162
        return fields;
    }

    /**
     * CC original: {@code getTelemetryPluginScope}（pluginTelemetry.ts:72-81）。
     *
     * <p>managedNames=null（per-invocation 调用方）→ 'org' 分支不命中。'builtin' marketplace
     * 保留名（CC pluginTelemetry.ts:33 BUILTIN_MARKETPLACE_NAME 内联常量，绕过 schemas.ts 循环
     * 依赖）→ 'default-bundle'；官方 marketplace → 'official'；其余 → 'user-local'。
     */
    private static String telemetryPluginScope(String name, String marketplace) {
        // CC :77 marketplace === 'builtin' → 'default-bundle'
        if (BUILTIN_MARKETPLACE_NAME.equals(marketplace)) {
            return "default-bundle";
        }
        // CC :78 isOfficialMarketplaceName(marketplace) → 'official'
        if (isOfficialMarketplaceName(marketplace)) {
            return "official";
        }
        // CC :80 managedNames?.has(name) → 'org'（Java per-invocation 传 null → 不命中）
        return "user-local";
    }

    /**
     * CC original: {@code hashPluginId}（pluginTelemetry.ts:48-54）。
     *
     * <p>不透明 per-plugin 聚合键：sha256(name@marketplace.lowercase + FIXED_SALT) hex 前 16 位
     * （BQ GROUP BY 基数可控）。盐跨仓跨站点固定（claude-plugin-telemetry-v1），客户可按已知
     * 插件名反算匹配自己的遥测。name 大小写保留（enabledPlugins 键大小写敏感），marketplace 后缀
     * lower-case 保可复现。空 marketplace（JS falsy）→ 纯 name。
     */
    private static String hashPluginId(String name, String marketplace) {
        // CC :49 key = marketplace ? `${name}@${marketplace.toLowerCase()}` : name
        String key = (marketplace != null && !marketplace.isEmpty())
                ? name + "@" + marketplace.toLowerCase(Locale.ROOT)
                : name;
        // CC :50-53 sha256(key + salt) hex 前 16 位
        return sha256Hex(key + PLUGIN_ID_HASH_SALT).substring(0, 16);
    }

    /** sha256 hex 摘要（UTF-8）· 复用仓库 HexFormat 先例（Download.java:148）。 */
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 解析当前 agent context 的 agentId · 对齐 CC {@code getAgentContext()?.agentId}
     * （SkillTool.ts:151/:674）。
     *
     * <p>主会话无 AgentContext → null（对齐 CC undefined，父 agent id 字段省略）；
     * SubagentContext/TeammateAgentContext 各自取 agentId（agentContext.ts:34/:62）。
     *
     * @return 当前 agent 的 id；无 agent context 或类型不匹配 → null
     */
    private static String resolveParentAgentId() {
        AgentContext agentContext = AgentContext.getAgentContext();
        if (agentContext == null) {
            return null;
        }
        if (agentContext instanceof AgentContext.SubagentContext sc) {
            return sc.agentId();
        }
        if (agentContext instanceof AgentContext.TeammateAgentContext tc) {
            return tc.agentId();
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    // checkPermissions · 对齐 CC SkillTool.ts:432-578（五段流程）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 技能权限决策 · 对齐 CC {@code SkillTool.ts:432-578 checkPermissions} 五段流程.
     *
     * <p><b>CC 真源顺序（Read 实证，逐段 grep 自验）</b>：
     * <ol>
     *   <li>{@code skill.trim()} + 剥前导 {@code /} → commandName（SkillTool.ts:437-440）</li>
     *   <li>deny 循环：{@code getRuleByContentsForTool(permCtx, SkillTool, 'deny')} →
     *       {@code Map<ruleContent,rule>}，{@link #ruleMatches} 命中 → Deny(
     *       "Skill execution blocked by permission rules", Rule 归因)（SkillTool.ts:470-486）</li>
     *   <li>allow 循环：behavior='allow' → 命中 → Allow(updatedInput, Rule 归因)
     *       （SkillTool.ts:507-523）</li>
     *   <li>safe-properties auto-allow：{@code commandObj?.type==='prompt'} 守卫 +
     *       {@link #skillHasOnlySafeProperties} → Allow(decisionReason: undefined)
     *       （SkillTool.ts:529-538）</li>
     *   <li>默认 Ask："Execute skill: X" + 2 条 AddRules suggestions（精确 + {@code :*} 前缀，
     *       destination=localSettings）+ metadata.command（SkillTool.ts:540-577）</li>
     * </ol>
     *
     * <p><b>守卫双保险（对齐 CC，见 JavaDoc 行内注明）</b>：
     * <ul>
     *   <li>CC {@code commandObj?.type==='prompt'}（SkillTool.ts:530）：validateInput 已实装
     *       errorCode-5 type 检查（P2-1，SkillTool.ts:421-427），本守卫
     *       {@code "prompt".equals(commandObj.getType())}（Command.java:104 默认 'prompt'，
     *       P1-9 加 type 字段）为双保险 —— 防 checkPermissions 被直调（绕过 validateInput，
     *       如权限预检路径）时对非 prompt 命令误放行。</li>
     *   <li>CC default Ask {@code decisionReason: undefined}（SkillTool.ts:573）：Java
     *       {@link PermissionResult.Ask}.reason 可空（对齐 CC {@code decisionReason?:
     *       PermissionDecisionReason} 可选，PermissionResult.java:206）→ 传 null（P1-6 拍板：
     *       默认 Ask 对齐 CC bypass-immune 语义 —— 非 Rule/SafetyCheck 归因即非 bypass-immune，
     *       HookPermissionResolver:302-303 视作无异议；null 与旧占位
     *       {@code Other("skill default ask")} 的 bypass-immune 判定一致，仅去除占位字符串工件，
     *       弹窗 reason 经 ToolPermissionGate.promptReasonOf 落通用 {@code "permission requested"}）</li>
     *   <li>CC 远程 canonical 技能 auto-grant（SkillTool.ts:488-504）gate 于
     *       {@code feature('EXPERIMENTAL_SKILL_SEARCH') && process.env.USER_TYPE==='ant'} ——
     *       ant-only 实验特性，Java 无对应 feature flag / USER_TYPE，本项不实现（记录偏离）</li>
     * </ul>
     *
     * @param input   LLM 传入的 Skill 工具参数（{@code {skill, args}}）
     * @param context 工具调用上下文（permissionContext 可能为 null → 视为空规则集）
     * @return Allow / Deny / Ask
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext context) {
        // ① skill.trim() + 剥前导 `/`（CC SkillTool.ts:437-440）
        String trimmed = input.get("skill").asText().trim();
        String commandName = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;

        // ② 权限上下文（null → 视为空规则集，CC permissionContext 恒有值但 Java 测试可缺省）
        ToolPermissionContext permCtx = context != null ? context.permissionContext() : null;
        if (permCtx == null && log.isDebugEnabled()) {
            log.debug("[SkillTool] checkPermissions permissionContext 为空，按空规则集处理 (CC SkillTool.ts:442-443)");
        }

        // ③ 命令对象（供 safe-properties 检查 + default Ask metadata.command）
        //    P2-9: 含 MCP thread-in · CC SkillTool.ts:446-447 getAllCommands(context) + findCommand
        Command commandObj = registry.findCommandIncludingMcp(commandName);

        // ④ deny 循环（CC SkillTool.ts:470-486）· deny 规则最高优先
        Map<String, PermissionRule> denyRules = permCtx == null
                ? Map.of()
                : RuleQuery.getRuleContentsByBehavior(permCtx, SKILL_TOOL_NAME, PermissionBehavior.DENY);
        for (Map.Entry<String, PermissionRule> entry : denyRules.entrySet()) {
            if (ruleMatches(entry.getKey(), commandName)) {
                if (log.isDebugEnabled()) {
                    log.debug("[SkillTool] checkPermissions deny 规则命中: rule={} command={} (CC SkillTool.ts:470-486)",
                            RuleQuery.ruleToString(entry.getValue()), commandName);
                }
                return new PermissionResult.Deny(
                        "Skill execution blocked by permission rules",
                        new PermissionDecisionReason.Rule(entry.getValue()), null);
            }
        }

        // ⑤ allow 循环（CC SkillTool.ts:507-523）
        Map<String, PermissionRule> allowRules = permCtx == null
                ? Map.of()
                : RuleQuery.getRuleContentsByBehavior(permCtx, SKILL_TOOL_NAME, PermissionBehavior.ALLOW);
        for (Map.Entry<String, PermissionRule> entry : allowRules.entrySet()) {
            if (ruleMatches(entry.getKey(), commandName)) {
                if (log.isDebugEnabled()) {
                    log.debug("[SkillTool] checkPermissions allow 规则命中: rule={} command={} (CC SkillTool.ts:507-523)",
                            RuleQuery.ruleToString(entry.getValue()), commandName);
                }
                return new PermissionResult.Allow(
                        input, new PermissionDecisionReason.Rule(entry.getValue()),
                        null, false, null, null);
            }
        }

        // ⑥ safe-properties auto-allow（CC SkillTool.ts:529-538）
        //    CC 守卫 commandObj?.type === 'prompt'（SkillTool.ts:530）· Java 等价
        //    "prompt".equals(commandObj.getType())（Command.java:104 默认 'prompt'，P1-9 加 type）。
        //    validateInput 已实装 errorCode-5 type 检查（P2-1），此处守卫为双保险 —— 防
        //    checkPermissions 被直调（绕过 validateInput，如权限预检路径）时对非 prompt 命令误放行。
        if (commandObj != null && "prompt".equals(commandObj.getType())
                && skillHasOnlySafeProperties(commandObj)) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillTool] checkPermissions safe-properties auto-allow: command={} (CC SkillTool.ts:529-538)",
                        commandName);
            }
            return new PermissionResult.Allow(input, null, null, false, null, null);
        }

        // ⑦ 默认 Ask + 2 条 AddRules suggestions（CC SkillTool.ts:540-577）
        if (log.isDebugEnabled()) {
            log.debug("[SkillTool] checkPermissions 默认 Ask: command={} (CC SkillTool.ts:570-577)",
                    commandName);
        }
        return new PermissionResult.Ask(
                "Execute skill: " + commandName,
                null, // CC SkillTool.ts:573 decisionReason: undefined（P1-6 对齐：默认 Ask 非 bypass-immune）
                defaultAskSuggestions(commandName),
                null,
                input,
                commandObj != null
                    ? new PermissionResult.PermissionMetadata.CommandMetadata(
                            commandObj.getName(), commandObj.getDescription())
                    : null,
                false, null, null);
    }

    /**
     * 30 项安全属性白名单 · 对齐 CC {@code SkillTool.ts:875-908 SAFE_SKILL_PROPERTIES}.
     *
     * <p>CC 语义：技能只要含"不在白名单且有意义值"的属性就需权限（fail-closed，新属性默认
     * 需审查后才 auto-allow）。本 Set 为权威白名单参照，{@link #skillHasOnlySafeProperties} 的
     * 字段→CC 属性映射表逐项对齐。
     *
     * <p>14 项 PromptCommand + 16 项 CommandBase（CC 行号逐项标注）：
     * <pre>
     * PromptCommand (:877-890): type / progressMessage / contentLength / argNames / model /
     *                          effort / source / pluginInfo / disableNonInteractive / skillRoot /
     *                          context / agent / getPromptForCommand / frontmatterKeys
     * CommandBase   (:893-907): name / description / hasUserSpecifiedDescription / isEnabled /
     *                          isHidden / aliases / isMcp / argumentHint / whenToUse / paths /
     *                          version / disableModelInvocation / userInvocable / loadedFrom /
     *                          immediate / userFacingName
     * </pre>
     */
    private static final Set<String> SAFE_SKILL_PROPERTIES = Set.of(
            // PromptCommand 14（CC SkillTool.ts:877-890）
            "type",                       // CC PromptCommand.type
            "progressMessage",            // CC PromptCommand.progressMessage
            "contentLength",              // CC PromptCommand.contentLength
            "argNames",                   // CC PromptCommand.argNames
            "model",                      // CC PromptCommand.model
            "effort",                     // CC PromptCommand.effort
            "source",                     // CC PromptCommand.source
            "pluginInfo",                 // CC PromptCommand.pluginInfo
            "disableNonInteractive",      // CC PromptCommand.disableNonInteractive
            "skillRoot",                  // CC PromptCommand.skillRoot
            "context",                    // CC PromptCommand.context
            "agent",                      // CC PromptCommand.agent
            "getPromptForCommand",        // CC PromptCommand.getPromptForCommand
            "frontmatterKeys",            // CC PromptCommand.frontmatterKeys
            // CommandBase 16（CC SkillTool.ts:893-907）
            "name",                       // CC CommandBase.name
            "description",                // CC CommandBase.description
            "hasUserSpecifiedDescription",// CC CommandBase.hasUserSpecifiedDescription
            "isEnabled",                  // CC CommandBase.isEnabled
            "isHidden",                   // CC CommandBase.isHidden
            "aliases",                    // CC CommandBase.aliases
            "isMcp",                      // CC CommandBase.isMcp
            "argumentHint",               // CC CommandBase.argumentHint
            "whenToUse",                  // CC CommandBase.whenToUse
            "paths",                      // CC CommandBase.paths
            "version",                    // CC CommandBase.version
            "disableModelInvocation",     // CC CommandBase.disableModelInvocation
            "userInvocable",              // CC CommandBase.userInvocable
            "loadedFrom",                 // CC CommandBase.loadedFrom
            "immediate",                  // CC CommandBase.immediate
            "userFacingName"              // CC CommandBase.userFacingName
    );

    /**
     * skill 仅含安全属性 · 对齐 CC {@code SkillTool.ts:910-933 skillHasOnlySafeProperties}
     * + :875-908 {@code SAFE_SKILL_PROPERTIES} 30 白名单.
     *
     * <p><b>显式字段→CC 属性映射（非反射）</b> —— Java {@link Command} 字段逐项判定：
     * <table>
     *   <tr><th>Java 字段</th><th>CC 属性</th><th>判定</th></tr>
     *   <tr><td>name / description / version</td><td>name(:893) / description(:894) / version(:906)</td><td>safe（∈白名单）</td></tr>
     *   <tr><td>aliases / argumentHint / whenToUse</td><td>aliases(:899) / argumentHint(:902) / whenToUse(:903)</td><td>safe</td></tr>
     *   <tr><td>userInvocable / disableModelInvocation / isHidden / immediate</td><td>userInvocable(:908) / disableModelInvocation(:906) / isHidden(:898) / immediate(:910)</td><td>safe</td></tr>
     *   <tr><td>context / agent / model / effort / paths / progressMessage</td><td>context(:890) / agent(:891) / model(:884) / effort(:885) / paths(:907) / progressMessage(:880)</td><td>safe</td></tr>
     *   <tr><td>source</td><td>source(:886) / loadedFrom(:909)</td><td>safe</td></tr>
     *   <tr><td>content</td><td>getPromptForCommand(:892, CC 懒加载属安全)</td><td>safe</td></tr>
     *   <tr><td>baseDir</td><td>skillRoot(:889)</td><td>safe</td></tr>
     *   <tr><td>enabled</td><td>isEnabled(:897)</td><td>safe</td></tr>
     *   <tr><td>allowedTools</td><td>—（不在 30 白名单）</td><td>非空 → false（需权限）</td></tr>
     *   <tr><td>hooks</td><td>—（不在 30 白名单）</td><td>非空 → false（需权限）</td></tr>
     *   <tr><td>id / contentPath / builtin / config / isSensitive / kind</td><td>CC Command 无对应属性（Java-only 基础设施）</td><td>排除（不参与检查）</td></tr>
     * </table>
     *
     * <p><b>WHY 显式映射（非反射）</b>：CC 逐键反射会遍历 Java Command 的全部字段，把
     * content/id/enabled 等 Java-only 或懒加载字段判为"非白名单有意义值" → 所有技能落入 Ask，
     * 破坏 CC 大多数技能 auto-allow（CC content 经 getPromptForCommand 懒加载属安全，
     * Command.java:42）。显式映射 + 排除 Java-only 字段让普通技能（无 allowedTools/hooks）
     * 保持 auto-allow，带 allowedTools/hooks 的技能落入 default Ask（对齐 CC：allowedTools/hooks
     * 不在 30 白名单，属需权限属性）。
     *
     * @param cmd 技能命令对象（非 null）
     * @return true = 仅含安全属性（auto-allow）；false = 含需权限属性
     */
    private static boolean skillHasOnlySafeProperties(Command cmd) {
        // allowedTools: 不在 30 白名单（CC PromptCommand.allowedTools）→ 非空需权限
        if (cmd.getAllowedTools() != null && !cmd.getAllowedTools().isEmpty()) {
            return false;
        }
        // hooks: 不在 30 白名单（CC PromptCommand.hooks）→ 非空需权限
        if (cmd.getHooks() != null && !cmd.getHooks().isBlank()) {
            return false;
        }
        // 其余字段全部映射到 30 白名单（safe）或 Java-only 排除 → safe
        return true;
    }

    /**
     * ruleContent 匹配 · 对齐 CC {@code SkillTool.ts:451-467 ruleMatches}.
     *
     * <p>CC 真源：ruleContent 剥前导 {@code /} 后（归一化规则），与归一化 commandName 比较——
     * <ul>
     *   <li><b>精确相等</b>：{@code normalizedRule === commandName}</li>
     *   <li><b>前缀匹配</b>：{@code normalizedRule.endsWith(':*')} → 剥 {@code :*} 后
     *       {@code commandName.startsWith(prefix)}（如 {@code "review:*"} 匹配 {@code "review-pr"}）</li>
     * </ul>
     *
     * @param ruleContent 规则内容（如 {@code "commit:*"} / {@code "/commit"}）
     * @param commandName 归一化后的技能名（无前导 {@code /}）
     * @return true = 精确或前缀命中
     */
    private static boolean ruleMatches(String ruleContent, String commandName) {
        if (ruleContent == null) {
            return false;
        }
        String normalizedRule = ruleContent.startsWith("/") ? ruleContent.substring(1) : ruleContent;
        // 精确匹配（归一化 commandName）
        if (normalizedRule.equals(commandName)) {
            return true;
        }
        // 前缀匹配（如 "review:*" 匹配 "review-pr"，CC SkillTool.ts:462-464）
        if (normalizedRule.endsWith(":*")) {
            String prefix = normalizedRule.substring(0, normalizedRule.length() - 2);
            return commandName != null && commandName.startsWith(prefix);
        }
        return false;
    }

    /**
     * default Ask 的 2 条 AddRules suggestions · 对齐 CC {@code SkillTool.ts:542-567}.
     *
     * <ul>
     *   <li>精确：{@code Skill(commandName)} · destination=localSettings · behavior=allow</li>
     *   <li>前缀：{@code Skill(commandName:*)}（允许任意参数）· destination=localSettings · behavior=allow</li>
     * </ul>
     *
     * @param commandName 归一化后的技能名（无前导 {@code /}，CC 注释:540-541 用归一化名建规则）
     * @return 2 条 AddRules
     */
    private static List<PermissionUpdate> defaultAskSuggestions(String commandName) {
        return List.of(
                new PermissionUpdate.AddRules(
                        PermissionUpdate.Destination.LOCAL_SETTINGS,
                        List.of(new PermissionRule(
                                PermissionRuleSource.LOCAL_SETTINGS,
                                PermissionBehavior.ALLOW,
                                PermissionRuleValue.withContent(SKILL_TOOL_NAME, commandName))),
                        PermissionBehavior.ALLOW),
                new PermissionUpdate.AddRules(
                        PermissionUpdate.Destination.LOCAL_SETTINGS,
                        List.of(new PermissionRule(
                                PermissionRuleSource.LOCAL_SETTINGS,
                                PermissionBehavior.ALLOW,
                                PermissionRuleValue.withContent(SKILL_TOOL_NAME, commandName + ":*"))),
                        PermissionBehavior.ALLOW));
    }

    // ════════════════════════════════════════════════════════════════════════
    // execute · 对齐 CC SkillTool.ts call() — inline skill expansion
    // ════════════════════════════════════════════════════════════════════════

    @Override
    public AgentToolResult execute(ToolUseBlock block) {
        return doExecute(block, null, null);
    }

    /**
     * s05 [P1] + P1-4: 带 ToolUseContext 的执行入口 · 从 ctx 取 sessionId 供
     * {@code ${CLAUDE_SESSION_ID}} 替换 (对齐 CC loadSkillsDir.ts:365-369 getSessionId()).
     *
     * <p>StreamingToolExecutor 走 {@code execute(call, ctx, onProgress)} → default 委托到本方法,
     * 生产路径必经此处拿到真实 sessionId; 无 ctx 时 (旧 1 参 execute) sessionId=null.
     */
    @Override
    public AgentToolResult execute(ToolUseBlock block, ToolUseContext ctx) {
        return doExecute(block, ctx, null);
    }

    /**
     * [P0-1] 3 参 execute override · 对齐 CC {@code Tool.ts:379-385} onProgress 参数 +
     * SkillTool.ts:122-289 executeForkedSkill 的 onProgress 上报 (子任务 a).
     *
     * <p>fork 分支据此上报 skill_progress (CC SkillTool.ts:240-261); inline 路径保持现有行为
     * 不变. StreamingToolExecutor.java:1482 3 参 dispatch 生产必经本方法 (R32-b15 C8 wrappedCallback
     * 已保证原回调安全).
     */
    @Override
    public AgentToolResult execute(ToolUseBlock block, ToolUseContext ctx, Consumer<ToolProgress> onProgress) {
        return doExecute(block, ctx, onProgress);
    }

    private AgentToolResult doExecute(ToolUseBlock block, ToolUseContext ctx, Consumer<ToolProgress> onProgress) {
        JsonNode input = block.input();
        String skillName = input.get("skill").asText().trim();
        if (skillName.startsWith("/")) skillName = skillName.substring(1);
        String args = input.has("args") ? input.get("args").asText() : "";
        // [P0-1] 透传真实 ctx 至 fork 分支 (旧实现仅收 sessionId 字符串, fork 分支用随机 UUID 丢弃父 ctx)
        // [session-id-short] ctx.sessionId() 已为 short String，恒等直传。
        String sessionId = (ctx != null) ? ctx.sessionId() : null;

        try {
            // [P1-7 TODO] remote canonical 执行路由槽位 · CC SkillTool.ts:605-613（ant-only 实验特性）：
            //   feature('EXPERIMENTAL_SKILL_SEARCH') && USER_TYPE==='ant' 时，本地命令查找前
            //   stripCanonicalPrefix(commandName) 非 null → executeRemoteSkill(slug, commandName, block, ctx)
            //   （含 remote recordSkillUsage :1059 + addInvokedSkill :1088 调用点）。Java 无 remote execution
            //   + 非 ant → 不接线；契约骨架见 skillsearch/SkillSearchRemoteExecution.java（接线待对接）。
            // P2-9: 含 MCP thread-in · CC SkillTool.ts:615-616 getAllCommands(context) + findCommand
            // ── [P1-15] 技能使用追踪 ──
            // CC 真源（SkillTool.ts:615-619，Read 实证）：getAllCommands + findCommand 之后、
            // fork 路由 if 之前无条件 recordSkillUsage(commandName) —— inline+fork 共用一个 invoke
            // 计一次，内容加载失败也计。P3-7 对齐：CC 对 findCommand 未命中（command undefined）
            // 同样 record（记录原始 skillName，接受未命中技能名进入排行数据）→ Java 把本调用置于
            // cmd==null 提前 return 之前，未命中技能同样计一次。
            if (skillUsageTracking != null) {
                skillUsageTracking.recordSkillUsage(skillName);
                if (log.isDebugEnabled()) {
                    log.debug("[SkillTool] 记录技能使用: {} (CC SkillTool.ts:619 recordSkillUsage)", skillName);
                }
            }

            Command cmd = registry.findCommandIncludingMcp(skillName);
            if (cmd == null) {
                return ToolResult.error(block.id(), "Unknown skill: " + skillName);
            }

            // ── [Session H12 v2 Gap1 修复] 技能加载时注册 frontmatter hooks ──
            // 对齐 CC processSlashCommand.tsx:877 registerSkillHooks(...) — 加载 skill 时
            // 把 frontmatter 的 hooks (once/skillRoot 语义) 注册为 session hooks.
            // 之前 registerForSkill 生产零调用方, skill frontmatter hooks 运行时永不注册.
            //
            // [ALIGN-HOOKS-1 △-8] 注册权限门控 · 对齐 CC processSlashCommand.tsx:874-875:
            //   const hooksAllowedForThisSkill =
            //     !isRestrictedToPluginOnly('hooks') || isSourceAdminTrusted(command.source);
            //   if (command.hooks && hooksAllowedForThisSkill) { registerSkillHooks(...) }
            // policy (strictPluginOnlyCustomization) 锁 hooks 面且来源非 admin-trusted
            // (user/mcp) → 不注册; plugin/builtin/bundled 来源始终可注册 (admin-approved
            // surface). 与 runAgent.ts agent frontmatter 门控同一语义.
            if (registerSkillHooks != null && cmd.getHooks() != null && !cmd.getHooks().isBlank()) {
                boolean hooksAllowedForThisSkill =
                        !PluginOnlyPolicy.isRestrictedToPluginOnly(PluginOnlyPolicy.SURFACE_HOOKS,
                                pluginOnlySettingsSupplier)
                        || PluginOnlyPolicy.isSourceAdminTrusted(toCcSource(cmd.getSource()));
                if (hooksAllowedForThisSkill) {
                    int registered = registerSkillHooks.registerSkillHooks(sessionId, cmd);
                    if (log.isDebugEnabled()) {
                        log.debug("[SkillTool] 技能 '{}' frontmatter hooks 注册 {} 个 (session={})",
                                skillName, registered, sessionId);
                    }
                } else if (log.isDebugEnabled()) {
                    log.debug("[SkillTool] 权限门控跳过技能 '{}' frontmatter hooks 注册 "
                                    + "(source={}, strictPluginOnlyCustomization 锁 hooks 面) "
                                    + "(CC processSlashCommand.tsx:874-875)",
                            skillName, cmd.getSource());
                }
            }

            // 加载技能内容
            // [P2-8] 内容源双路径 · 对齐 CC processSlashCommand.tsx:869/:884：
            //   - bundled skill（Command.promptFn 非 null，CC getPromptForCommand 闭包）→ 闭包输出
            //     text 块 join('\n\n')（CC :884 skillContent = result.filter(text).map(text).join('\n\n')），
            //     非 SKILL.md 文件 —— 关闭 R1「bundled skill has no content」。
            //   - 磁盘 skill（SKILL.md/contentPath/content）→ contentLoader.loadContent（既有管线不变）。
            //   cwd 取自 ctx.effectiveCwd()（ToolUseContext:77）；1 参 execute(block) 旧路径 ctx=null → cwd=null
            //   （bundled 闭包现均不依赖 cwd，null-safe 由闭包自担，concern P2-8-1）。
            String content;
            // P2-16: promptFn 返回内容块数组（对齐 CC getPromptForCommand → ContentBlockParam[]）。
            // text 块 join 为 content（CC processSlashCommand.tsx:884 skillContent 语义，供文本管线）；
            // image 块（MCP prompt 产物）单独收集，注入 newMessage contentBlocks（图片块通道）。
            List<ContentBlockParam> promptFnBlocks = List.of();
            if (cmd.getPromptFn() != null) {
                // [拍板#9 part2] promptFn 会话通道：把 cwd + messages + sessionId 组装为 PromptFnContext
                // 传入闭包 —— 对齐 CC getPromptForCommand(args, context)（skillify.ts:179-195 用
                // context.messages + sessionId；CC ToolUseContext.context 含会话消息）。
                String cwd = (ctx != null && ctx.effectiveCwd() != null)
                        ? ctx.effectiveCwd().toString() : null;
                List<ChatMessageDto> messages = (ctx == null || ctx.messages() == null) ? List.of()
                        : ctx.messages().stream()
                            .filter(ChatMessageDto.class::isInstance)
                            .map(ChatMessageDto.class::cast)
                            .toList();
                promptFnBlocks = cmd.getPromptFn().apply(args,
                        new PromptFnContext(cwd, messages, sessionId));
                // CC processSlashCommand.tsx:884 skillContent = result.filter(text).map(text).join('\n\n')
                content = promptFnBlocks.stream()
                        .filter(b -> b instanceof ContentBlockParam.TextBlockParam)
                        .map(b -> ((ContentBlockParam.TextBlockParam) b).text())
                        .collect(Collectors.joining("\n\n"));
                if (log.isDebugEnabled()) {
                    log.debug("[SkillTool] 技能 '{}' 内容来自 getPromptForCommand 闭包: {} chars, "
                                    + "textBlocks={} imageBlocks={} sessionId={} messages={} (CC bundledSkills.ts:97 + processSlashCommand.tsx:884)",
                            skillName, content.length(),
                            promptFnBlocks.stream().filter(b -> b instanceof ContentBlockParam.TextBlockParam).count(),
                            promptFnBlocks.stream().filter(b -> b instanceof ContentBlockParam.ImageBlockParam).count(),
                            sessionId, messages.size());
                }
            } else {
                content = contentLoader.loadContent(cmd);
                if (content.isEmpty()) {
                    return ToolResult.error(block.id(), "Skill '" + skillName + "' has no content");
                }
            }

            // P0-4: base directory 前缀注入 · 对齐 CC loadSkillsDir.ts:345-347 + prependBaseDir (bundledSkills.ts:66-72)
            //   顺序严格对齐 CC getPromptForCommand 闭包 (loadSkillsDir.ts:344-369):
            //   prefix → substituteArguments → ${CLAUDE_SKILL_DIR} → ${CLAUDE_SESSION_ID}
            content = contentLoader.withBaseDirPrefix(cmd, content);

            // [P2-8] bundled skill（promptFn 非 null）跳过磁盘专属管线（concern P2-8-1）：
            //   CC bundled getPromptForCommand 闭包自含 args 处理（如 simplify 追加 '## Additional Focus'，
            //   SimplifySkillRegistrar.java:108-114），磁盘闭包才做 substituteArguments/shell 注入
            //   （loadSkillsDir.ts:344-396）；若 bundled 输出再过 substituteArguments(appendIfNoPlaceholder=true)
            //   会双重追加 args。bundled 分支仅保留 withBaseDirPrefix（=CC prependBaseDir 包装）。
            if (cmd.getPromptFn() == null) {
                // P0-4: 参数替换（5 替换 + append）· 对齐 CC loadSkillsDir.ts:349-354
                //   substituteArguments(finalContent, args, appendIfNoPlaceholder=true, argumentNames)
                content = ArgumentSubstitution.substituteArguments(
                    content, args, true,
                    cmd.getArgNames() != null ? cmd.getArgNames() : java.util.List.of());

                // P1-4（GAP-PC-1/2）：plugin 命令内容链补 ${CLAUDE_PLUGIN_ROOT}/${CLAUDE_PLUGIN_DATA} +
                //   ${user_config.X} 替换 · 对齐 CC loadPluginCommands.ts:340-354（getPromptForCommand 闭包在
                //   substituteArguments 之后、${CLAUDE_SKILL_DIR} 之前）。非 plugin 源命令 pluginRoot=null →
                //   保持字面（对齐 CC substitutePluginVariables path 缺省不替换）；userConfig 空 → 未知键字面
                //   （对齐 CC substituteUserConfigInContent :399-402）。
                content = contentLoader.replacePluginVariables(content, cmd.getPluginRoot(), cmd.getPluginSource());
                content = contentLoader.replaceUserConfig(content, cmd.getUserConfig(), cmd.getSensitiveKeys());

                // P0-4: ${CLAUDE_SKILL_DIR} 独立步骤 · 对齐 CC loadSkillsDir.ts:359-363
                //   win32 反斜杠→正斜杠规范化（P0-3 修复的 bash 转义回归线）
                content = contentLoader.replaceSkillDir(content, cmd.getBaseDir());

                // P0-4: ${CLAUDE_SESSION_ID} 独立步骤 · 对齐 CC loadSkillsDir.ts:366-369
                content = contentLoader.replaceSessionId(content, sessionId);

                // ── [P0-5] MCP 安全闸 + shell 注入 ──
                // 对齐 CC loadSkillsDir.ts:371-396: if (loadedFrom !== 'mcp') { finalContent =
                //   await executeShellCommandsInPrompt(...) } (getPromptForCommand 闭包最后一步).
                //   安全: MCP 技能是远程不可信内容, 永不执行其 markdown body 中的内联 shell 命令
                //   (!`…` / ```! … ```); ${CLAUDE_SKILL_DIR} 对 MCP 技能也无意义 (CC :371-373 注释).
                //   Java 等价: cmd.getLoadedFrom()==CommandLoadedFrom.MCP (setLoadedFrom 点:
                //   McpToolPool.buildSkillCommand → CreateSkillCommand loadedFrom=MCP + JsonRpcMcpClient prompts
                //   不设 loadedFrom —— 非 skill，CC client.ts:2072). P2-21: 独立 loadedFrom 判别（M20 △ 修复）.
                //   P1-5: shell 参数由恒 null 改为 cmd.getShell() —— 激活 PromptShellExecutor:175-176 已就绪的
                //   pwsh 路由 (shell: powershell 且 CLAUDE_CODE_USE_POWERSHELL_TOOL 门控开启时走 PowerShell,
                //   否则 Bash; 对齐 CC loadSkillsDir.ts:393-394 getPromptForCommand 把 shell 传入 executeShellCommandsInPrompt).
                if (cmd.getLoadedFrom() != CommandLoadedFrom.MCP) {
                    if (ctx == null) {
                        // 1 参 execute(block) 旧路径无 ToolUseContext → 无法权限预检 → fail-loud 日志跳过
                        // (生产 StreamingToolExecutor 3 参 dispatch 恒有 ctx, 不受影响)
                        log.warn("[SkillTool] 技能 '{}' 内嵌 shell 命令跳过: ctx=null 无法权限预检 "
                                + "(CC promptShellExecution.ts:98)", skillName);
                    } else {
                        content = promptShellExecutor.executeShellCommandsInPrompt(
                                content, ctx, "/" + skillName, cmd.getShell(), cmd.getAllowedTools());
                        if (log.isDebugEnabled()) {
                            log.debug("[SkillTool] 技能 '{}' shell 注入完成: 长度={} (CC loadSkillsDir.ts:374-396)",
                                    skillName, content.length());
                        }
                    }
                }
            }

            if (log.isDebugEnabled()) {
                log.debug("[SkillTool] 技能 '{}' 组合替换完成: args='{}' 替换后长度={} (CC loadSkillsDir.ts:344-369)",
                        skillName, args, content.length());
            }

            // 构建技能指令文本 (含 <command-name> 标签 + 正文) · 作为 newMessage 注入对话
            String systemPrompt = buildSkillSystemPrompt(cmd, content, args);

            // 构建返回结果 JSON (metadata) · 对齐 CC call() 返回的 data 字段 (不含指令正文)
            ObjectNode data = MAPPER.createObjectNode();
            data.put("success", true);
            data.put("commandName", skillName);

            // 注入 allowedTools
            if (cmd.getAllowedTools() != null && !cmd.getAllowedTools().isEmpty()) {
                ArrayNode toolArr = data.putArray("allowedTools");
                for (String t : cmd.getAllowedTools()) toolArr.add(t);
            }

            // 模型覆盖
            if (cmd.getModel() != null) {
                data.put("model", cmd.getModel());
            }

            // s07-P2-2 + P2.2 修复: 检查 cmd.context 区分 fork/inline 分支
            // CC SkillTool.ts:622-632 if (command.context === 'fork') executeForkedSkill()
            // CC SkillTool.ts:276-284 fork result: {success, commandName, status: 'forked', agentId, result}
            // CC SkillTool.ts:766-773 inline result: 不含 status 字段
            String context = cmd.getContext();

            if ("fork".equals(context)) {
                // [P0-1] fork 真实执行 · 对齐 CC SkillTool.ts:622-632: context==='fork' 直接
                // executeForkedSkill, CC 无 inline 降级语义. 生产 subagentExecutor 现为必注 @Bean
                // (ToolRegistrationConfig.subagentExecutor); 缺失时显式抛 IllegalStateException
                // (fail loud, 对齐 CC fork 无 fallback) — 旧 :351-354 inline fallback+warn 分支已删除.
                if (subagentExecutor == null) {
                    throw new IllegalStateException(
                            "Fork mode 需要 SubagentExecutor bean (未注册). skill='" + skillName + "'");
                }
                log.info("[SkillTool] Fork mode: 启动隔离 sub-agent 执行 skill '{}'", skillName);
                // [P3-4 T1 fork] 对齐 CC SkillTool.ts:152-203: fork 分支在 executeForkedSkill 入口
                //   发射 tengu_skill_tool_invocation (execution_context='fork')。与 inline 共用
                //   buildInvocationTelemetry 字段集 (command_name/_PROTO_skill_name/query_depth/...).
                if (telemetry != null) {
                    telemetry.recordEvent("tengu_skill_tool_invocation",
                            buildInvocationTelemetry(skillName, cmd, "fork", ctx));
                    if (log.isDebugEnabled()) {
                        log.debug("[SkillTool] 遥测 T1 fork: skill={} (CC SkillTool.ts:152-203 tengu_skill_tool_invocation)",
                                skillName);
                    }
                }
                // 对齐 CC SkillTool.ts:226-229: runAgent toolUseContext = {...context, getAppState: modifiedGetAppState}
                //   fork 子代理继承父 ctx (agentId/sessionId/availableTools), 供隔离与工具池;
                //   不再用随机 UUID 丢弃父 ctx (旧 :338). getAppState 授权链为 P1-18 独立 P-item.
                ToolUseContext effectiveCtx = ctx != null
                        ? ctx
                        // [session-id-short] 兜底 ctx 的 sessionId 统一 short 形态（sess-xxx）
                        : ToolUseContext.of(UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8));
                // 对齐 CC forkedAgent.ts:224: promptMessages = [createUserMessage({content: skillContent})]
                //   fork 子代理收到的用户消息 = 技能内容 (untagged, getPromptForCommand 输出), 非用户 args
                //   (旧 :340 传 args 是 BUG). content 变量 = :307 withBaseDirPrefix+substituteArguments 后的
                //   原始技能内容 = CC skillContent (loadSkillsDir.ts:344-379).
                SubagentExecutor.SubagentResult result = null;
                try {
                    result = subagentExecutor.executeForkedSkill(
                            content, cmd, args, effectiveCtx,
                            buildForkProgressSink(onProgress, block.id(), content));
                    // 渲染 CC-style fork result: {success, commandName, status: 'forked', agentId, result}
                    //   CC SkillTool.ts:276-284 return {data:{success, commandName, status:'forked', agentId, result}}
                    //   result = extractResultText(agentMessages, 'Skill execution completed') (forkedAgent.ts:237-250
                    //   = 末尾 assistant 消息文本). Java SubagentResult.summaryText() 已等价
                    //   (SubagentExecutor.extractConclusionFromMessages 取最后一条 assistant 文本),
                    //   仅默认兜底文案不同 ('Subagent completed without final answer.'), 语义等价不改逻辑.
                    ObjectNode forkData = MAPPER.createObjectNode();
                    forkData.put("success", true);
                    forkData.put("commandName", skillName);
                    forkData.put("status", "forked");
                    forkData.put("agentId", result.agentId());
                    forkData.put("result", result.summaryText() != null ? result.summaryText() : "");
                    return ToolResult.success(block.id(), forkData.toString());
                } finally {
                    // [P1-6-CLEANUP-1] CC SkillTool.ts:287 finally { clearInvokedSkillsForAgent(agentId) }
                    //   fork 子 agent 结束后释放其 invokedSkills 全文；异常路径 result=null 跳过
                    //   （异常路径清理由 SubagentExecutor.runSubagentQueryLoop finally 兜底）。
                    cleanupForkAgentInvokedSkills(result, sessionId);
                }
            }

            // [P3-4 T1 inline] 对齐 CC SkillTool.ts:675-726: inline 遥测在 fork 路由之后
            //   （CC :622-632 fork 分支已 return，到达此处必为 inline 路径）、newMessages 构建
            //   之前发射 tengu_skill_tool_invocation (execution_context='inline')。CC 位于
            //   getToolUseID/tagMessagesWithToolUseID (:728-755) 之前 —— 技能内容处理完成的时点，
            //   与 CC 发射位置语义等价（放在 fork 路由之后避免 fork 双重发射）。
            if (telemetry != null) {
                telemetry.recordEvent("tengu_skill_tool_invocation",
                        buildInvocationTelemetry(skillName, cmd, "inline", ctx));
                if (log.isDebugEnabled()) {
                    log.debug("[SkillTool] 遥测 T1 inline: skill={} (CC SkillTool.ts:675-726 tengu_skill_tool_invocation)",
                            skillName);
                }
            }

            // ── [P1-6] 记录已调用 skill（压缩存活·写入侧）· 对齐 CC processSlashCommand.tsx:880-885 ──
            // CC 真源（processSlashCommand.tsx:883-885，Read 实证）：
            //   const skillPath = command.source ? `${command.source}:${command.name}` : command.name;
            //   const skillContent = result.filter(text block).map(b=>b.text).join('\n\n');  // getPromptForCommand 输出
            //   addInvokedSkill(command.name, skillPath, skillContent, getAgentContext()?.agentId ?? null);
            //
            // <ul>
            //   <li>仅 inline 路径写入：fork 分支 :714-758 提前 return，天然满足 CC SkillTool.ts:622-632
            //       fork 路由先于 processPromptSlashCommand + SkillTool.ts:761-764 注释
            //       "addInvokedSkill and registerSkillHooks are called inside processPromptSlashCommand" —
            //       fork 不重复注册。</li>
            //   <li>content = 渲染后全文（getPromptForCommand 输出等价：:644 baseDir 头 +
            //       :648 substituteArguments + :654 ${CLAUDE_SKILL_DIR} + :657 ${CLAUDE_SESSION_ID} +
            //       :668-682 MCP 安全闸 shell 注入），非 systemPrompt（CC 存 result 而非含 command-name 标签的 newMessage）。</li>
            //   <li>skillPath = `${source}:${name}`（source 小写对齐 CC 'bundled'/'user'/'mcp'，Java CommandSource
            //       enum name 大写 → name().toLowerCase(Locale.ROOT)）；source 为 null 时回退裸 name（CC :883 三目）。</li>
            //   <li>agentId = 当前 agent context 的 agentId（CC processSlashCommand.tsx:885
            //       {@code getAgentContext()?.agentId ?? null} 的 Java 等价，落共享会话 AgentState）：
            //       后台化主会话任务以 agentId=agentUuid 注册进 registry（LlmAgentLoop:1650-1657 EVD-B
            //       改造按 agentId 注册分支；CC LocalMainSessionTask.ts:368-375 runWithAgentContext
            //       {agentId:taskId}），写入侧以 ctx.agentId()（工具线程上恒为后台 loop 的真实 agentUuid）
            //       命中已注册后台 AgentState 即判定为后台 agent → 归因 agentUuid，使 /clear
            //       preservedAgentIds={task.agentId()}（conversation.ts:93-106 / CommandController:370-373）
            //       能匹配保留（state.ts:1543-1555）——EVD-B 归因链修复。
            //       [session-id-short] 主会话 ctx.agentId()=null（agentId 兜底已删）→ agents map 查询必空 → null；fork 未注册 → null。</li>
            //   <li>null-safe 不抛错：sessionStateResolver 未注入 / 会话不可达 → debug 日志 skip（CC addInvokedSkill 无校验无抛错）。</li>
            // </ul>
            if (ctx != null && ctx.sessionId() != null && sessionStateResolver != null) {
                AgentState sessionState = sessionStateResolver.apply(ctx.sessionId());
                if (sessionState != null) {
                    String skillPath = cmd.getSource() != null
                            ? cmd.getSource().name().toLowerCase(Locale.ROOT) + ":" + skillName
                            : skillName;
                    // EVD-B: skill 归属 agentId = 当前 agent context 的 agentId（CC processSlashCommand.tsx:885
                    //   {@code getAgentContext()?.agentId ?? null}）。后台化主会话任务以 agentId=agentUuid
                    //   注册进 registry（LlmAgentLoop:1650-1657 按 agentId 注册分支）；此处以 ctx.agentId()
                    //   （工具线程上恒为该后台 loop 的真实 agentUuid，ToolUseContext 透传 state.agentId()）
                    //   命中已注册后台 AgentState 即判定为后台 agent → 归因 agentUuid，条目落共享会话
                    //   AgentState，使 /clear preservedAgentIds={task.agentId()}（conversation.ts:93-106 /
                    //   CommandController:370-373）能匹配保留（state.ts:1543-1555）——EVD-B 归因链修复。
                    //   [session-id-short] 主会话 ctx.agentId()=null（agentId 兜底已删）→ agentId 分支不命中
                    //   → skillAgentId=null（既有行为不变）；fork 子 agent 不注册 → null。
                    UUID skillAgentId = null;
                    if (ctx.agentId() != null && sessionStateResolver.apply(ctx.agentId()) != null) {
                        skillAgentId = ctx.agentId();
                    }
                    sessionState.addInvokedSkill(skillName, skillPath, content, skillAgentId);
                    if (log.isDebugEnabled()) {
                        log.debug("[P1-6] 记录已调用 skill: name={} path={} chars={} agentId={} "
                                        + "(CC processSlashCommand.tsx:883-885)",
                                skillName, skillPath, content.length(), skillAgentId);
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("[P1-6] 会话 AgentState 不可达，跳过 addInvokedSkill: sessionId={}",
                                ctx.sessionId());
                    }
                }
            }

            // ── P0-1 修复: inline 路径返回 ExtendedToolResult (携带技能指令 newMessage) ──
            // CC SkillTool.ts:767-774 call() 返回 {data, newMessages, contextModifier}:
            //   - data       = 元数据 (success/commandName/allowedTools/model)
            //   - newMessages = 技能指令文本 (含 <command-name> 标签 + 正文), 注入对话历史供 LLM 消费
            // 之前 (真 dead code): systemPrompt 构造后被丢弃, 仅返回 data → SkillTool 对 LLM 零有效内容.
            // 现在: 把 systemPrompt 作为 user newMessage 塞进 ExtendedToolResult,
            //       LlmAgentLoop 经 ExtendedToolResultApplier 注入 state.messages() (跨 turn 持久).
            // [A1·退役 ExtendedToolResult] base 折入 successWithNewMessages (CC Tool.ts:323 newMessages)
            //
            // P2-16 图片块通道：promptFn（MCP prompt）返回的 image 块经 newMessage.contentBlocks
            // 注入对话（对齐 CC processSlashCommand.tsx:890 mainMessageContent=[...result]，image 块
            // 保留）。LLM 渲染路径（AnthropicSdkProvider:1454-1462）contentBlocks 非空时只发
            // contentBlocks 不发 content → 需把 systemPrompt（含 <command-name> 标签，供模型理解
            // 命令语义）前置为 text 块，再跟 image 块（CC 双消息：metadata 标签 + mainMessageContent
            // 原文；Java 单消息合并表达）。无 image 块 → contentBlocks 空（旧行为，文本走 content）。
            List<JsonNode> skillMessageContentBlocks;
            if (promptFnBlocks.isEmpty()) {
                skillMessageContentBlocks = java.util.List.of();
            } else {
                List<JsonNode> imageBlocks = promptFnBlocks.stream()
                        .filter(b -> b instanceof ContentBlockParam.ImageBlockParam)
                        .map(b -> (JsonNode) MAPPER.valueToTree(b))
                        .toList();
                if (imageBlocks.isEmpty()) {
                    skillMessageContentBlocks = java.util.List.of();
                } else {
                    List<JsonNode> blocks = new ArrayList<>();
                    blocks.add(MAPPER.valueToTree(new ContentBlockParam.TextBlockParam(systemPrompt)));
                    blocks.addAll(imageBlocks);
                    skillMessageContentBlocks = blocks;
                }
            }
            ChatMessageDto skillMessage = new ChatMessageDto(
                    UUID.randomUUID().toString(), null, Role.user, "user",
                    systemPrompt, null, null, null, null, null, null, null, null, null,
                    null,                          // R32-b9 acceptFeedback
                    skillMessageContentBlocks,     // R32-b9 contentBlocks（P2-16 图片块通道：image 块 + systemPrompt 前置 text 块）
                    java.util.List.of());          // R32-b9 imagePasteIds

            if (log.isDebugEnabled()) {
                log.debug("[SkillTool] Invoked skill '{}' inline ({} chars → newMessage 注入)",
                        skillName, systemPrompt.length());
            }

            // ── P2-22: 对齐 CC SkillTool.ts:729-755 tagMessagesWithToolUseID ──
            // CC 真源（Read 实证，不信注释）：
            //   toolUseID = getToolUseIDFromParentMessage(parentMessage, SKILL_TOOL_NAME)  // :729-732
            //   newMessages = tagMessagesWithToolUseID(processedCommand.messages.filter(...), toolUseID)  // :735-755
            // 语义：给技能内容 user 消息打 sourceToolUseID = 本次 Skill tool_use id，使该消息
            //   transient（与正在运行的 Skill 工具调用绑定；消费点 messages.ts:2777-2780 getToolUseID
            //   user 分支 `if (message.sourceToolUseID) return message.sourceToolUseID`，UI 经此关联，
            //   不落历史独立条目）。
            // Java 等价：block.id() 即 Skill tool_use id（ToolUseBlock 就是被执行的那个 tool_use 块，
            //   等价 CC getToolUseIDFromParentMessage(parentMessage,'Skill')）。fork 路径 CC 不 tag
            //   （executeForkedSkill 返回 {data} 无 newMessages，SkillTool.ts:276-284），Java fork 已
            //   return ToolResult.success（本方法 :1118）一致，不动。
            List<ChatMessageDto> taggedSkillMessages =
                    tagMessagesWithToolUseID(List.of(skillMessage), block.id());
            if (log.isDebugEnabled()) {
                log.debug("[SkillTool] inline newMessage 打标 sourceToolUseID={} (CC SkillTool.ts:729-755)",
                        block.id());
            }

            // [P0-2] 对齐 CC SkillTool.ts:767-774 call() 返回 {data, newMessages, contextModifier}:
            //   contextModifier 三件套 (allowedTools/model/effort) 经 setAppState 落入会话
            //   appStateRef, 由后续轮次 toolExecContext / getModelForCall 消费 (见 buildContextModifier).
            //   data 形状不变 (success/commandName/allowedTools/model, CC inline data 亦不含 effort).
            return ToolResult.successWithNewMessagesWithContextModifier(
                    block.id(), data.toString(), taggedSkillMessages, buildContextModifier(cmd));

        } catch (MalformedCommandException e) {
            // [P0-5] fail loud · 对齐 CC MalformedCommandError 透传 (promptShellExecution.ts:133-134):
            //   shell 权限预检失败 / 执行失败原样上抛, 不被泛型 catch 吞成 ToolResult.error
            //   (权限失败变普通技能失败 = 静默降级). StreamingToolExecutor 外层 catch
            //   (StreamingToolExecutor.java:1776) 已转带 errorCategory 的 ToolResult.error,
            //   上抛不会造成未捕获崩溃.
            throw e;
        } catch (IllegalStateException e) {
            // [P0-1] fail loud · 对齐 CC fork 无降级语义: executor 缺失等致命配置错误不静默转
            //   ToolResult.error (LLM 会误以为只是普通技能失败), 原样上抛 — StreamingToolExecutor
            //   外层 catch (StreamingToolExecutor.java:1776) 已转带 errorCategory 的 ToolResult.error,
            //   上抛不会造成未捕获崩溃.
            throw e;
        } catch (Exception e) {
            log.error("[SkillTool] Failed to execute skill '{}': {}", skillName, e.getMessage(), e);
            return ToolResult.error(block.id(), "Skill execution failed: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 构建技能系统提示 · 对齐 CC inline skill expansion 的 prompt 注入
     */
    private String buildSkillSystemPrompt(Command skill, String content, String args) {
        StringBuilder sb = new StringBuilder();

        // 技能标识（对齐 CC COMMAND_NAME_TAG）
        sb.append("<command-name>").append(skill.getName()).append("</command-name>\n");

        // 参数提示
        if (args != null && !args.isBlank()) {
            sb.append("<command-args>").append(args).append("</command-args>\n");
        }

        // 如果指定了 allowedTools，告诉模型
        if (skill.getAllowedTools() != null && !skill.getAllowedTools().isEmpty()) {
            sb.append("<allowed-tools>").append(String.join(", ", skill.getAllowedTools())).append("</allowed-tools>\n");
        }

        sb.append("\n");
        sb.append(content);

        return sb.toString();
    }

    /**
     * 给 user 消息打 sourceToolUseID 标记 · 对齐 CC {@code tools/utils.ts:12-25 tagMessagesWithToolUseID}.
     *
     * <p><b>CC 真源（Read 实证，不信注释）</b>：
     * <pre>
     * if (!toolUseID) { return messages }                                  // :16-18
     * return messages.map(m => {                                           // :19
     *   if (m.type === 'user') { return { ...m, sourceToolUseID: toolUseID } }  // :20-21
     *   return m                                                           // :23
     * })                                                                   // :24
     * </pre>
     *
     * <p>语义：toolUseID 为 null/blank 时<b>原样返回</b>（CC :16-18，无工具调用可关联时不得打标）；
     * 否则仅对 {@code role==user} 的消息 {@link ChatMessageDto#withSourceToolUseID(String)} 打标
     * （镜像 CC spread {@code {...m, sourceToolUseID}}，utils.ts:21），其余角色不变（CC 只 tag
     * {@code user} type，attachment/system 不动）。
     *
     * <p><b>filter 文档化非适用</b>：CC 在 tag 前 filter（SkillTool.ts:736-753）丢 {@code progress}
     * 消息（:738）与含 {@code <command-message>} 的 user 消息（:742-749，COMMAND_MESSAGE_TAG 见
     * xml.ts:3）。Java inline 路径结构性 N/A —— Java 只产 1 条 user 消息（content 由
     * {@link #buildSkillSystemPrompt} 生成，含 {@code <command-name>} 而非 {@code <command-message>}），
     * 无 progress 消息、无 processPromptSlashCommand 消息列表；本 helper 不实现该 filter 是
     * 文档化非适用，非行为跳过。
     *
     * @param messages  待打标消息列表（Java inline 路径恒 1 条 user 消息）
     * @param toolUseID CC original: toolUseID（SkillTool.ts:729 getToolUseIDFromParentMessage 返回的
     *                  本次 Skill tool_use id；Java 侧 = block.id()）
     * @return 打标后的消息列表（toolUseID null/blank 时原列表不变，否则返回新实例）
     */
    private static List<ChatMessageDto> tagMessagesWithToolUseID(
            List<ChatMessageDto> messages, String toolUseID) {
        if (toolUseID == null || toolUseID.isBlank()) {
            return messages;
        }
        return messages.stream()
                .map(m -> m.role() == Role.user ? m.withSourceToolUseID(toolUseID) : m)
                .toList();
    }

    /**
     * [P0-1] fork skill_progress sink · 对齐 CC SkillTool.ts:240-261 onProgress 上报.
     *
     * <p>CC 真源: assistant/user 消息的 content 含 tool_use/tool_result 块时
     * {@code onProgress({toolUseID: 'skill_'+parentMessage.message.id, data:{message, type:'skill_progress',
     * prompt: skillContent, agentId}})} (SkillTool.ts:250-258).
     *
     * <p>[P1-18] 两处残留偏差已修复:
     * <ol>
     *   <li><b>tool-content 启发式 → 精确过滤</b>: 旧实现对全部 assistant/user 消息上报
     *       (启发式 :1112-1123), 现仅对 {@link SubagentMessage#toolContent()}==true 的消息上报 —
     *       对齐 CC SkillTool.ts:246-248 {@code content.some(c => c.type === 'tool_use' ||
     *       c.type === 'tool_result')}. toolContent 由 {@link SubagentExecutor#toSubagentMessage}
     *       根据 ChatMessageDto.toolCalls()/toolCallId() 判定 (assistant tool_use / role=tool result).</li>
     *   <li><b>agentId null → 真实 fork agentId</b>: 旧实现 SkillProgressData.agentId 硬编码 null
     *       (:1122, fork agentId 在 SubagentExecutor 内部创建、sink 构建期不可知). 现经
     *       {@link SubagentMessage#agentId()} 载体透传 (toSubagentMessage 在消息发射点
     *       SubagentExecutor:2146 注入 agentId), sink 读取回填 — 对齐 CC SkillTool.ts:256
     *       {@code agentId} 传真实 fork 子代理 id.</li>
     * </ol>
     *
     * @param onProgress   工具执行进度回调 (StreamingToolExecutor 3 参 dispatch 注入; null = 非流式)
     * @param parentMsgId  父消息 id → toolUseID 'skill_'+id (CC SkillTool.ts:251)
     * @param skillContent 技能内容 → data.prompt (CC SkillTool.ts:255)
     * @return SubagentMessage 消费端; onProgress=null 返回 null
     */
    private Consumer<SubagentMessage> buildForkProgressSink(Consumer<ToolProgress> onProgress,
                                                            String parentMsgId, String skillContent) {
        if (onProgress == null) {
            return null;
        }
        return message -> {
            // [P1-18] CC SkillTool.ts:246-248: 仅 content 含 tool_use/tool_result 块的消息上报,
            //   不再对全部 assistant/user 消息上报 (旧启发式偏差). toolContent 由 toSubagentMessage 判定.
            if (!message.toolContent()) {
                return;
            }
            if (log.isDebugEnabled()) {
                log.debug("[SkillTool] [P1-18] fork skill_progress 上报: msgType={} toolUseId='skill_{}' "
                        + "agentId={} (CC SkillTool.ts:240-261)",
                        message.getClass().getSimpleName(), parentMsgId, message.agentId());
            }
            // [P1-18] agentId 经消息载体透传 (CC SkillTool.ts:256 真实 fork agentId), 不再硬编码 null
            onProgress.accept(new ToolProgress(
                    "skill_" + parentMsgId,
                    new SkillProgressData("skill_progress", skillContent, message.agentId(), message)));
        };
    }

    /**
     * [P1-6-CLEANUP-1] fork finally 清理 invokedSkills · 对齐 CC SkillTool.ts:287
     * {@code finally { clearInvokedSkillsForAgent(agentId) }}。
     *
     * <p>fork 子 agent 结束后释放其 skill 全文（每条含完整 skill content，防累积泄漏 / stale skill
     * 注入）。null-safe 降级链：
     * <ol>
     *   <li>sessionStateResolver 未注入（P1-6 写入侧未接线）→ 跳过 —— 权威清理在
     *       {@link SubagentExecutor#cleanSubagentInvokedSkills}（runSubagentQueryLoop finally）</li>
     *   <li>result=null（executeForkedSkill 异常路径）→ 跳过 —— 异常路径清理由 SubagentExecutor finally 兜底</li>
     *   <li>result.agentId() 非合法 UUID（防御）→ 跳过</li>
     *   <li>session AgentState 不可达 → 跳过</li>
     * </ol>
     *
     * @param result     fork 执行结果（异常路径为 null，无法捕获 fork agentId）
     * @param sessionId  fork 继承的父 sessionId（CC SkillTool.ts:226-229 toolUseContext 透传）
     */
    private void cleanupForkAgentInvokedSkills(SubagentExecutor.SubagentResult result, String sessionId) {
        if (sessionStateResolver == null) {
            return;
        }
        if (result == null || sessionId == null) {
            return;
        }
        UUID forkAgentId;
        try {
            forkAgentId = UUID.fromString(result.agentId());
        } catch (IllegalArgumentException e) {
            if (log.isDebugEnabled()) {
                log.debug("[P1-6-CLEANUP-1] fork 子 agent id 非 UUID，跳过 invokedSkills 清理: {}",
                        result.agentId());
            }
            return;
        }
        // [session-id-short] sessionId 已 short，直传（不再 UUID.fromString —— short 会抛 IAE 硬边界）。
        AgentState sessionState = sessionStateResolver.apply(sessionId);
        if (sessionState == null) {
            if (log.isDebugEnabled()) {
                log.debug("[P1-6-CLEANUP-1] session AgentState 不可达，跳过 fork invokedSkills 清理: sessionId={}",
                        sessionId);
            }
            return;
        }
        sessionState.clearInvokedSkillsForAgent(forkAgentId);
    }

    /**
     * [P0-1] fork skill_progress 数据载体 · 对齐 CC SkillTool.ts:250-258 onProgress.data 结构
     * {@code {message, type: 'skill_progress', prompt: skillContent, agentId}}.
     *
     * @param type     CC original: type ('skill_progress', SkillTool.ts:254)
     * @param prompt   CC original: prompt (技能内容, SkillTool.ts:255)
     * @param agentId  CC original: agentId (fork 子代理 id, SkillTool.ts:256); Java 端 fork agentId
     *                 在 SubagentExecutor 内部创建 (createSubagentContext), sink 构建期不可知 → 当前 null
     * @param message  CC original: message (归一化后含 tool 块的消息, SkillTool.ts:253)
     */
    public record SkillProgressData(String type, String prompt, String agentId, Object message) {}

    // ════════════════════════════════════════════════════════════════════════
    // [P0-2] inline contextModifier 三件套 · 对齐 CC SkillTool.ts:775-839
    // ════════════════════════════════════════════════════════════════════════

    /** appStateRef key · CC original: mainLoopModel（SkillTool.ts:810-821 / handlePromptSubmit.ts:566）. */
    private static final String KEY_MAIN_LOOP_MODEL = "mainLoopModel";

    /** appStateRef key · CC original: effortValue（SkillTool.ts:824-836 / query.ts:694）. */
    private static final String KEY_EFFORT_VALUE = "effortValue";

    /** appStateRef key · CC original: toolPermissionContext（SkillTool.ts:790-801）. */
    private static final String KEY_TOOL_PERMISSION_CONTEXT = "toolPermissionContext";

    /**
     * [P0-2] 构建 inline 技能 contextModifier · 对齐 CC SkillTool.ts:775-839 三件套.
     *
     * <p><b>CC 真源（Read 实证）</b>:
     * <ol>
     *   <li>{@code allowedTools 非空 → appState.toolPermissionContext.alwaysAllowRules.command
     *       去重合入}（SkillTool.ts:779-806, {@code [...new Set([...(existing||[]), ...allowedTools])]}）</li>
     *   <li>{@code model 非空 → options.mainLoopModel = resolveSkillModelOverride(model, currentMainLoopModel)}
     *       （SkillTool.ts:808-821 + model.ts:523-540，[1m] 后缀顺延防 200K 窗口塌缩）</li>
     *   <li>{@code effort !== undefined → appState.effortValue = effort}（SkillTool.ts:823-836）</li>
     * </ol>
     *
     * <p><b>Java 桥接</b>: CC 用 {@code modifiedContext = {...ctx, getAppState(){...}}} 包装返回
     * 新 ctx（toolOrchestration.ts:53-61 currentContext 续传）；Java 端 {@code ToolUseContext}
     * 已有 {@code getAppState}/{@code setAppState} 函数式桥（Stage 3.2 C2, 绑定 LlmAgentLoop
     * {@code appStateRef}），故本方法用 {@code tuc.setAppState().accept(updater)} 把副作用写入
     * 会话 {@code appStateRef}（跨 turn 保持），返回原 tuc 即可 —— 后续轮次的
     * {@code AgentLoopContext.toolExecContext}（command 授权合并）与
     * {@code LlmAgentLoop.getModelForCall}（skill-model 优先级层）从 appStateRef 读取，
     * 与 CC "appState 会话内存 + per-turn 派生" 语义等价.
     *
     * <p><b>debug 日志</b>: 逐件 if(log.isDebugEnabled()) 包裹（CLAUDE.md 日志规范）.
     *
     * @param cmd 已解析的技能命令（含 allowedTools / model / effort frontmatter 三件）
     * @return contextModifier 函数；三件套全空时返回恒等函数（对齐 CC 空 allowedTools 时
     *         contextModifier 仍返回（无 getAppState 包装）的上下文）
     */
    private Function<ToolUseContext, ToolUseContext> buildContextModifier(Command cmd) {
        final List<String> allowedTools = cmd.getAllowedTools() != null ? cmd.getAllowedTools() : List.of();
        final String model = cmd.getModel();
        final String effort = cmd.getEffort();
        return tuc -> {
            if (tuc == null) {
                return null;
            }
            // (a) allowedTools 非空 → 去重合入 appState.toolPermissionContext.alwaysAllowRules.command
            if (!allowedTools.isEmpty()) {
                tuc.setAppState().accept(prev -> mergeAllowedToolsIntoAppState(prev, allowedTools));
                if (log.isDebugEnabled()) {
                    log.debug("[SkillTool] contextModifier 注入 allowedTools={} (CC SkillTool.ts:779-806)",
                            allowedTools);
                }
            }
            // (b) model 非空 → mainLoopModel = resolveSkillModelOverride(model, prev 当前 mainLoopModel)
            if (model != null && !model.isBlank()) {
                tuc.setAppState().accept(prev -> {
                    Map<String, Object> next = new LinkedHashMap<>(prev != null ? prev : Map.of());
                    Object current = prev != null ? prev.get(KEY_MAIN_LOOP_MODEL) : null;
                    next.put(KEY_MAIN_LOOP_MODEL, SkillModelOverrideResolver.resolveSkillModelOverride(
                            model, current != null ? String.valueOf(current) : null));
                    return next;
                });
                if (log.isDebugEnabled()) {
                    log.debug("[SkillTool] contextModifier 覆盖 mainLoopModel: {} (CC SkillTool.ts:808-821)",
                            model);
                }
            }
            // (c) effort 非空 → appState.effortValue = effort (CC effort !== undefined 语义)
            if (effort != null) {
                tuc.setAppState().accept(prev -> {
                    Map<String, Object> next = new LinkedHashMap<>(prev != null ? prev : Map.of());
                    next.put(KEY_EFFORT_VALUE, effort);
                    return next;
                });
                // [C-31] 同步写入会话 AgentState.effortValue（LLM 消费点 LlmAgentLoop ModelRequest
                //   构造读 state.effortValue() · 对齐 CC SkillTool.ts:823-836 getAppState 包装
                //   {..., effortValue: effort} + query.ts:694 appState.effortValue）。
                //   同一修改点保证 appStateRef 与 AgentState 不漂移（concern ⑤ 单一权威源）。
                //   null-safe：resolver / sessionId / state 任一缺失 → debug 日志 skip 不抛错
                //   （复用 addInvokedSkill :1322-1340 同款模式）。
                if (tuc.sessionId() != null && sessionStateResolver != null) {
                    AgentState sessionState = sessionStateResolver.apply(tuc.sessionId());
                    if (sessionState != null) {
                        sessionState.setEffortValue(effort);
                    } else if (log.isDebugEnabled()) {
                        log.debug("[C-31] 会话 AgentState 不可达，跳过 effortValue 同步: sessionId={}",
                                tuc.sessionId());
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("[SkillTool] contextModifier 注入 effortValue={} (CC SkillTool.ts:823-836)",
                            effort);
                }
            }
            return tuc;
        };
    }

    /**
     * [P0-2] 把技能 allowedTools 去重合入 appState 的 toolPermissionContext.alwaysAllowRules.command ·
     * 对齐 CC SkillTool.ts:790-801（{@code [...new Set([...(existing||[]), ...allowedTools])]}）.
     *
     * <p>[P1-18] 访问级别 {@code private} → package-private：供同包
     * {@link SubagentExecutor#createForkGetAppStateWithAllowedTools} 复用同一合并语义
     * （CC forkedAgent.ts:160-166 createGetAppStateWithAllowedTools 的 command 桶去重合并），
     * 避免 fork 授权链出现第二份合并实现（双实现漂移）。
     *
     * <p><b>Java 表示</b>: appStateRef 的 {@code toolPermissionContext} 值存
     * {@link ToolPermissionContext} record（Java 侧权限上下文类型）; command 桶 =
     * {@code alwaysAllowRules[COMMAND]}（{@code Set<PermissionRule>}），每工具名映射为
     * whole-tool ALLOW rule（对齐 CC command 桶是工具名 string[]）。
     *
     * <p><b>既有 appState 存在 toolPermissionContext</b>: 保留全部既有字段，仅替换 command 桶
     * （去重合并）; <b>不存在</b>: 构建仅含 command 桶的最小 ToolPermissionContext（mode=DEFAULT，
     * 该 mode 不被消费 —— 消费方仅读 command 规则）。
     *
     * @param prev        setAppState 传入的 prev appState snapshot（可为 null）
     * @param allowedTools 技能声明的 allowed tools（非空）
     * @return 合并后的 appState（含更新后的 toolPermissionContext）
     */
    static Map<String, Object> mergeAllowedToolsIntoAppState(
            Map<String, Object> prev, List<String> allowedTools) {
        Map<String, Object> next = new LinkedHashMap<>(prev != null ? prev : Map.of());
        Object existing = prev != null ? prev.get(KEY_TOOL_PERMISSION_CONTEXT) : null;
        ToolPermissionContext tpc;
        if (existing instanceof ToolPermissionContext existingTpc) {
            Set<PermissionRule> commandRules = new LinkedHashSet<>(
                    existingTpc.alwaysAllowRules().getOrDefault(PermissionRuleSource.COMMAND, Set.of()));
            for (String toolName : allowedTools) {
                if (toolName == null || toolName.isBlank()) continue;
                commandRules.add(new PermissionRule(PermissionRuleSource.COMMAND, PermissionBehavior.ALLOW,
                        PermissionRuleValue.wholeTool(toolName)));
            }
            Map<PermissionRuleSource, Set<PermissionRule>> allow =
                    new EnumMap<>(existingTpc.alwaysAllowRules());
            allow.put(PermissionRuleSource.COMMAND, commandRules);
            tpc = new ToolPermissionContext(existingTpc.mode(), allow,
                    existingTpc.alwaysDenyRules(), existingTpc.alwaysAskRules(),
                    existingTpc.additionalWorkingDirectories(),
                    existingTpc.isBypassPermissionsModeAvailable(), existingTpc.isAutoModeAvailable(),
                    existingTpc.strippedDangerousRules(), existingTpc.shouldAvoidPermissionPrompts(),
                    existingTpc.awaitAutomatedChecksBeforeDialog(), existingTpc.prePlanMode());
        } else {
            Set<PermissionRule> commandRules = new LinkedHashSet<>();
            for (String toolName : allowedTools) {
                if (toolName == null || toolName.isBlank()) continue;
                commandRules.add(new PermissionRule(PermissionRuleSource.COMMAND, PermissionBehavior.ALLOW,
                        PermissionRuleValue.wholeTool(toolName)));
            }
            Map<PermissionRuleSource, Set<PermissionRule>> allow =
                    new EnumMap<>(PermissionRuleSource.class);
            allow.put(PermissionRuleSource.COMMAND, commandRules);
            tpc = new ToolPermissionContext(PermissionMode.DEFAULT, allow, Map.of(), Map.of(),
                    Map.of(), false, false, Map.of(), false, false, null);
        }
        next.put(KEY_TOOL_PERMISSION_CONTEXT, tpc);
        return next;
    }
}
