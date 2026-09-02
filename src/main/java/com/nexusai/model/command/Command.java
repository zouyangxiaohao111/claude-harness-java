package com.nexusai.model.command;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.nexusai.application.agent.tool.ContentBlockParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

/**
 * Command 领域聚合根 · 对齐 CC command.ts Command = CommandBase & PromptCommand
 *
 * <h2>CC 字段映射（19 核心字段）</h2>
 * <table>
 *   <tr><th>CC 字段</th><th>CC 类型</th><th>Java 字段</th></tr>
 *   <tr><td>name</td><td>CommandBase</td><td>{@link #name}</td></tr>
 *   <tr><td>type</td><td>PromptCommand（恒 'prompt'）</td><td>{@link #type}</td></tr>
 *   <tr><td>displayName</td><td>CommandBase</td><td>{@link #displayName}</td></tr>
 *   <tr><td>hasUserSpecifiedDescription</td><td>CommandBase</td><td>{@link #hasUserSpecifiedDescription}</td></tr>
 *   <tr><td>shell</td><td>PromptCommand</td><td>{@link #shell}</td></tr>
 *   <tr><td>description</td><td>CommandBase</td><td>{@link #description}</td></tr>
 *   <tr><td>aliases</td><td>CommandBase</td><td>{@link #aliases}</td></tr>
 *   <tr><td>version</td><td>CommandBase</td><td>{@link #version}</td></tr>
 *   <tr><td>userInvocable</td><td>CommandBase</td><td>{@link #userInvocable}</td></tr>
 *   <tr><td>disableModelInvocation</td><td>CommandBase</td><td>{@link #disableModelInvocation}</td></tr>
 *   <tr><td>isHidden</td><td>CommandBase</td><td>{@link #isHidden}</td></tr>
 *   <tr><td>argumentHint</td><td>CommandBase</td><td>{@link #argumentHint}</td></tr>
 *   <tr><td>whenToUse</td><td>CommandBase</td><td>{@link #whenToUse}</td></tr>
 *   <tr><td>isSensitive</td><td>CommandBase</td><td>{@link #isSensitive}</td></tr>
 *   <tr><td>immediate</td><td>CommandBase</td><td>{@link #immediate}</td></tr>
 *   <tr><td>kind</td><td>CommandBase</td><td>{@link #kind}</td></tr>
 *   <tr><td>isMcp</td><td>CommandBase</td><td>{@link #isMcp}</td></tr>
 *   <tr><td>source</td><td>PromptCommand</td><td>{@link #source}</td></tr>
 *   <tr><td>pluginInfo</td><td>PromptCommand</td><td>{@link #pluginInfo}</td></tr>
 *   <tr><td>loadedFrom</td><td>CommandBase</td><td>{@link #loadedFrom}</td></tr>
 *   <tr><td>allowedTools</td><td>PromptCommand</td><td>{@link #allowedTools}</td></tr>
 *   <tr><td>model</td><td>PromptCommand</td><td>{@link #model}</td></tr>
 *   <tr><td>context</td><td>PromptCommand</td><td>{@link #context}</td></tr>
 *   <tr><td>agent</td><td>PromptCommand</td><td>{@link #agent}</td></tr>
 *   <tr><td>skillRoot</td><td>PromptCommand</td><td>{@link #baseDir}</td></tr>
 *   <tr><td>paths</td><td>PromptCommand</td><td>{@link #paths}</td></tr>
 * </table>
 *
 * <p>额外字段（非 CC 核心但必需）：
 * <table>
 *   <tr><td>id</td><td>DB 主键</td></tr>
 *   <tr><td>enabled</td><td>启用/禁用开关（CC isEnabled()）</td></tr>
 *   <tr><td>builtin</td><td>是否为内置命令（不可删除）</td></tr>
 *   <tr><td>content</td><td>SKILL.md 正文（CC getPromptForCommand 返回值来源）</td></tr>
 *   <tr><td>promptFn</td><td>CC PromptCommand.getPromptForCommand（bundledSkills.ts:97，bundled 内容源，
 *       {@code (args, PromptFnContext)→List<String>} 文本块，@JsonIgnore 不序列化）</td></tr>
 *   <tr><td>hooks</td><td>CC PromptCommand.hooks</td></tr>
 *   <tr><td>effort</td><td>CC PromptCommand.effort（EffortValue）</td></tr>
 *   <tr><td>progressMessage</td><td>CC PromptCommand.progressMessage</td></tr>
 * </table>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Command {
    // ════════════════════════════════════════════════════════════════════════
    // 核心标识（CC CommandBase）
    // ════════════════════════════════════════════════════════════════════════

    private String id;
    private String name;

    /**
     * CC original: displayName（loadSkillsDir.ts:238-239，
     * {@code displayName: frontmatter.name != null ? String(frontmatter.name) : undefined}）。
     *
     * <p>与 {@link #name} 不同：name 恒=磁盘目录名（loadSkillsDir.ts:452 {@code const skillName = entry.name}），
     * displayName 是 frontmatter.name 的可读展示名，经 {@link #userFacingName()}（CC :337-339）优先取用。
     */
    private String displayName;

    /**
     * CC original: hasUserSpecifiedDescription（loadSkillsDir.ts:241，
     * {@code hasUserSpecifiedDescription: validatedDescription !== null}）。
     *
     * <p>frontmatter 显式声明合法 description 标量时为 true（coerce 结果非 null），供 UI 区分
     * 「用户显式描述」与「从 markdown 首行提取的回退描述」。默认 false（CC 该字段恒为布尔）。
     */
    private Boolean hasUserSpecifiedDescription = Boolean.FALSE;

    /**
     * CC original: shell（FrontmatterShell，frontmatterParser.ts:339）——inline {@code !`cmd`} /
     * {@code ```!}``` 注入的 shellTool 选择（bash/powershell，CC promptShellExecution.ts:80-83）。
     *
     * <p>由 frontmatter {@code shell} 经 parseShellFrontmatter（frontmatterParser.ts:351-370）解析；
     * null 等价 CC undefined（默认回退 bash）。消费点：SkillToolImpl → PromptShellExecutor
     * executeShellCommandsInPrompt 第 4 参（CC loadSkillsDir.ts:393-394）。
     */
    private String shell;

    private String description;
    private String version;

    // ════════════════════════════════════════════════════════════════════════
    // 来源分类（CC CommandBase.loadedFrom + PromptCommand.source）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * CC original: type（types/command.ts:26，{@code PromptCommand.type = 'prompt'}）。
     *
     * <p>命令类型判别 · 对齐 CC getSkillToolCommands（commands.ts:568 {@code cmd.type === 'prompt'}）。
     * Java 现仅有 prompt 型命令（local / local-jsx 全集属 M23 范围），默认恒 {@code "prompt"}；
     * MCP 接入通道 {@link McpServerService#getMcpSkillCommands()} 过滤链也用
     * {@code "prompt".equals(type)} 判别（P1-17 起为纯过滤器，commands.ts:551-556；旧的
     * {@code tool.prompt() != null} TOOLS 生产机制已删除）。空串等价 CC undefined（falsy），
     * 被过滤链 {@code "prompt".equals(type)} 排除。
     */
    private String type = "prompt";

    private CommandSource source;

    /**
     * CC original: loadedFrom（types/command.ts:191-197 类型联合 {@code 'commands_DEPRECATED'|
     * 'skills'|'plugin'|'managed'|'bundled'|'mcp'} + skills/loadSkillsDir.ts:67-74 LoadedFrom type）。
     *
     * <p>与 {@link #source}（CC PromptCommand.source，command.ts:32）是<b>两个独立字段</b>：
     * source 表达「谁定义的命令」（SettingSource|'builtin'|'mcp'|'plugin'|'bundled'），loadedFrom
     * 表达「命令从哪个渠道加载」。Java 旧架构把两者合一为 source（M20 △ 根因，managed 误当 bundled /
     * commands_DEPRECATED 折叠进 USER），本字段独立建模后 SkillRegistry 过滤（CC commands.ts:568-598）
     * 与 MCP 安全闸（CC loadSkillsDir.ts:374 {@code loadedFrom !== 'mcp'}）改以本字段判别。
     *
     * <p>null 等价 CC undefined（默认构造留 null；CommandRecord/CommandDto 均显式字段构造，
     * 本字段不参与序列化外泄 —— 对齐 BudgetTracker local-only 红线）。
     */
    private CommandLoadedFrom loadedFrom;

    // ════════════════════════════════════════════════════════════════════════
    // 行为控制（CC CommandBase）
    // ════════════════════════════════════════════════════════════════════════

    private List<String> aliases;
    private String argumentHint;
    private String whenToUse;
    private Boolean userInvocable;
    private Boolean disableModelInvocation;
    private Boolean isHidden;
    private Boolean isSensitive;
    private Boolean immediate;
    private String kind;  // 'workflow' 或 null

    /**
     * CC original: isMcp（types/command.ts:185，{@code CommandBase.isMcp?: boolean}）——
     * 标记命令是否源自 MCP 服务器。
     *
     * <p>CC 语义：{@code isMcp} 是 CommandBase 上的布尔标志（与 {@link #loadedFrom}='mcp'
     * 是两个独立字段），由 MCP 技能加载路径置 true；消费方 {@code isMcpCommand}
     * （services/mcp/utils.ts:255 {@code name.startsWith('mcp__') || isMcp === true}）据此识别
     * MCP 命令。Java 侧 MCP 技能经 {@code McpServerService} thread-in 创建时置 true（归属
     * DISC-SKILL-05 mcp-skills 域），本字段仅为模型层契约补齐。
     *
     * <p>null 等价 CC undefined（默认构造不赋值；非 MCP 命令恒 null）。
     */
    private Boolean isMcp;

    /**
     * CC original: availability（types/command.ts:176，{@code CommandBase.availability?:
     * CommandAvailability[]}）——命令可用的认证/供应商声明（'claude-ai' | 'console'）。
     *
     * <p>null 等价 CC undefined = universal（所有用户可用，commands.ts:418
     * {@code if (!cmd.availability) return true}）。非 null 时仅当用户命中至少一个声明类型才可见
     * （SkillRegistry.meetsAvailabilityRequirement，commands.ts:417-443；
     * types/command.ts:164-168 注释「shown if the user matches at least one of the listed auth types」）。
     * 门控在 {@code getAllCommands()} 单点应用（availability 先于 isEnabled，commands.ts:411-416 注释
     * 「This runs before isEnabled()… provider-gated commands are hidden regardless of feature-flag
     * state」），MCP thread-in 合并路径（findCommandIncludingMcp / getModelInvocableCommandsForListing）
     * 不过 gate（CC getMcpSkillCommands commands.ts:547-559 无 availability 检查）。
     *
     * <p><b>不参与序列化</b>：CommandDto/CommandRecord 均显式字段构造（CommandService.toDto/fromDomain
     * 用显式 getter），本字段不自动外泄（对齐 BudgetTracker local-only 红线）；web 前端无 availability
     * 消费者，符合 CC availability 为 CLI/UI 静态声明语义（若未来 REST 需要另行决策）。
     */
    private List<CommandAvailability> availability;

    // ════════════════════════════════════════════════════════════════════════
    // 技能运行时（CC PromptCommand）
    // ════════════════════════════════════════════════════════════════════════

    private String context;       // 'inline' | 'fork'
    private String agent;         // fork 模式下的 agent 类型
    private List<String> allowedTools;
    private String model;         // 模型覆盖

    /**
     * CC original: pluginInfo（types/command.ts:33-36，{@code PromptCommand.pluginInfo?: {
     * pluginManifest: PluginManifest, repository: string }}）——plugin 源命令的插件清单信息。
     *
     * <p>消费方：{@code formatDescriptionWithSource}（commands.ts:737-743，取
     * {@code pluginInfo.pluginManifest.name} 作展示名前缀）+ SkillTool 遥测（SkillTool.ts:185-202/
     * :710-725，取 pluginManifest.name + repository 发射 plugin 字段块）。Java 侧 plugin 命令由
     * {@code LoadPluginCommands.createPluginCommand} 创建时置本字段（归属 plugin 域，
     * loadPluginCommands.ts:317-320），本字段仅为模型层契约补齐（repository = CC sourceName，
     * pluginManifest.name = 插件名）。
     *
     * <p>null 等价 CC undefined（非 plugin 源命令恒 null）。不参与序列化（CommandDto 显式字段
     * 构造，无本字段，对齐 BudgetTracker local-only 红线）。
     */
    private PluginInfo pluginInfo;

    private String effort;        // EffortValue
    private List<String> paths;   // 文件路径 glob 模式
    private String hooks;         // HooksSettings JSON
    /**
     * 命名参数数组 · CC original: argNames（loadSkillsDir.ts:324，
     * {@code argNames: argumentNames.length > 0 ? argumentNames : undefined}）。
     *
     * <p>null 等价 CC undefined（空数组时不赋值）。由 frontmatter {@code arguments} 字段经
     * {@code SkillsLoader.applyFrontmatter} → {@link ArgumentSubstitution#parseArgumentNames} 填充，
     * 供 {@code $name} 命名替换（CC argumentSubstitution.ts:111-121）按索引映射 parsedArgs 位置。
     * <b>不参与 CommandDto 序列化</b>（CommandDto 为显式字段构造，CommandService.toDto 无此字段）。
     */
    private List<String> argNames;

    // ════════════════════════════════════════════════════════════════════════
    // 内容与路径（CC PromptCommand.skillRoot + getPromptForCommand 数据源）
    // ════════════════════════════════════════════════════════════════════════

    private String content;       // SKILL.md 正文（去除 frontmatter）
    private String contentPath;   // SKILL.md 文件路径
    private String baseDir;       // CC skillRoot：技能目录（用于 CLAUDE_PLUGIN_ROOT）
    private String progressMessage;

    // ════════════════════════════════════════════════════════════════════════
    // 状态（CC isEnabled() + extras）
    // ════════════════════════════════════════════════════════════════════════

    private Boolean enabled;
    private Boolean builtin;

    /**
     * CC original: isEnabled（types/command.ts:214-215，{@code cmd.isEnabled?: () => boolean}）——惰性启用判定
     * 函数 · 对齐 CC registerBundledSkill 直传 definition.isEnabled（bundledSkills.ts:94）。
     *
     * <p>由 {@link BundledSkillDefinition#toCommand()} 透传（如 loop: isKairosCronEnabled / remember:
     * isAutoMemoryEnabled），{@link #isCommandEnabled()} 每次调用新鲜求值（CC commands.ts:478 注释
     * 「isEnabled checks run fresh every call」）；null 时回退 {@link #enabled} 字段（CC
     * {@code isEnabled?.() ?? true}）。DB/Web toggle 仅写 {@link #enabled}，不触碰本 supplier。
     *
     * <p><b>不参与序列化</b>（{@link CommandDto} 为显式字段构造，CommandService.toDto 无此字段；
     * {@code @JsonIgnore} 双保险防 Jackson 直接序列化本字段/访问器）。
     */
    @JsonIgnore
    private BooleanSupplier isEnabled;

    /**
     * CC original: getPromptForCommand（bundledSkills.ts:97，registerBundledSkill 把 prompt 闭包
     * 直挂 Command）——bundled skill 的内容生成函数 {@code (args, context) => Promise<ContentBlockParam[]>}。
     *
     * <p><b>内容源双路径</b>（对齐 CC processSlashCommand.tsx:869 {@code command.getPromptForCommand(args, context)}
     * + :884 {@code skillContent = result.filter(text).map(text).join('\n\n')}）：
     * <ul>
     *   <li>bundled skill（本字段非 null）：内容 = 闭包输出 text 块 {@code join('\n\n')}，<b>非 SKILL.md 文件</b>；
     *       由 {@code BundledSkillDefinition#toCommand()} 适配器透传（P2-8，bundledSkills.ts:97）。</li>
     *   <li>磁盘 skill（source=USER/PLUGIN）：本字段 null，内容走 {@link #content} / {@link #contentPath} /
     *       {@link #baseDir}（SkillContentLoader.loadContent 路径，既有管线不变）。</li>
     * </ul>
     *
     * <p><b>类型取舍（P2-16 更新）</b>：CC 返回 {@code Promise<ContentBlockParam[]>}（bundledSkills.ts:37-40、
     * client.ts:2094 MCP getPromptForCommand），Java 对齐为 {@code List<ContentBlockParam>}——
     * 语义上忠实复刻 CC 内容块数组（含 image 块通道）。bundled skill 为 text 单形态（PromptBlock），
     * 适配器（{@code BundledSkillDefinition#toCommand()}）把 text 升格为 {@link ContentBlockParam.TextBlockParam}；
     * MCP prompt（{@code McpToolPool.executePrompt}）产出含 image 块的内容块数组。Command 层引入
     * {@link ContentBlockParam}（application 层，model→application 依赖）——已有先例：model 层
     * {@code ChatMessageDto} 已 import {@code com.nexusai.application.agent.tool.AgentUsage}；P2-16
     * 需跨 Command 边界携带图片内容块，List&lt;String&gt; 文本降级无法表达（原 concern P2-8-4 撤销）。
     *
     * <p><b>会话通道（skill 复验决策 拍板#9 part2 · NG-CDB-2）</b>：第二参由旧 {@code String cwd} 升级为
     * {@link PromptFnContext}（cwd + messages + sessionId）——对齐 CC {@code getPromptForCommand(args, context)}
     * 的 context 通道（skillify.ts:179-195 用 {@code context.messages} + sessionId 解析会话 memory）。
     * 跨 {@link Command} / {@code BundledSkillDefinition} / {@code SkillToolImpl} 共享类型改造。
     *
     * <p><b>不参与序列化</b>（{@code @JsonIgnore}，复用 {@link #isEnabled}(BooleanSupplier) 的函数字段先例）；
     * CommandDto/CommandRecord 均显式字段构造（toDto/fromDomain 用显式 getter），本字段不自动外泄
     * （对齐 BudgetTracker local-only 红线）。
     */
    @JsonIgnore
    private BiFunction<String, PromptFnContext, List<ContentBlockParam>> promptFn;

    // ════════════════════════════════════════════════════════════════════════
    // 默认值构造
    // ════════════════════════════════════════════════════════════════════════

    public Command() {
        this.source = CommandSource.USER;
        this.enabled = Boolean.TRUE;
        this.builtin = Boolean.FALSE;
        this.userInvocable = Boolean.TRUE;
        this.disableModelInvocation = Boolean.FALSE;
        this.isHidden = Boolean.FALSE;
        this.isSensitive = Boolean.FALSE;
        this.immediate = Boolean.FALSE;
        this.context = "inline";
    }

    // ════════════════════════════════════════════════════════════════════════
    // Getters/Setters
    // ════════════════════════════════════════════════════════════════════════

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Boolean getHasUserSpecifiedDescription() { return hasUserSpecifiedDescription; }
    public void setHasUserSpecifiedDescription(Boolean hasUserSpecifiedDescription) {
        this.hasUserSpecifiedDescription = hasUserSpecifiedDescription;
    }
    public String getShell() { return shell; }
    public void setShell(String shell) { this.shell = shell; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public CommandSource getSource() { return source; }
    public void setSource(CommandSource source) { this.source = source; }

    public CommandLoadedFrom getLoadedFrom() { return loadedFrom; }
    public void setLoadedFrom(CommandLoadedFrom loadedFrom) { this.loadedFrom = loadedFrom; }

    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> aliases) { this.aliases = aliases; }
    public String getArgumentHint() { return argumentHint; }
    public void setArgumentHint(String argumentHint) { this.argumentHint = argumentHint; }
    public String getWhenToUse() { return whenToUse; }
    public void setWhenToUse(String whenToUse) { this.whenToUse = whenToUse; }
    public Boolean getUserInvocable() { return userInvocable; }
    public void setUserInvocable(Boolean userInvocable) { this.userInvocable = userInvocable; }
    public Boolean getDisableModelInvocation() { return disableModelInvocation; }
    public void setDisableModelInvocation(Boolean disableModelInvocation) { this.disableModelInvocation = disableModelInvocation; }
    public Boolean getIsHidden() { return isHidden; }
    public void setIsHidden(Boolean isHidden) { this.isHidden = isHidden; }
    public Boolean getIsSensitive() { return isSensitive; }
    public void setIsSensitive(Boolean isSensitive) { this.isSensitive = isSensitive; }
    public Boolean getImmediate() { return immediate; }
    public void setImmediate(Boolean immediate) { this.immediate = immediate; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Boolean getIsMcp() { return isMcp; }
    public void setIsMcp(Boolean isMcp) { this.isMcp = isMcp; }
    public List<CommandAvailability> getAvailability() { return availability; }
    public void setAvailability(List<CommandAvailability> availability) { this.availability = availability; }

    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }
    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public PluginInfo getPluginInfo() { return pluginInfo; }
    public void setPluginInfo(PluginInfo pluginInfo) { this.pluginInfo = pluginInfo; }

    /**
     * CC original: getPromptForCommand 闭包捕获的 {@code pluginPath}（loadPluginCommands.ts:340-343
     * substitutePluginVariables 的 {@code {path: pluginPath, source: sourceName}}）——plugin 源命令的
     * 插件安装根目录，用于 {@code ${CLAUDE_PLUGIN_ROOT}} 内容替换（skill 正文引用插件根路径）。
     *
     * <p>由 {@code LoadPluginCommands.createPluginCommand} 置位（plugin.localPath）；null 等价 CC
     * undefined（非 plugin 源命令恒 null）→ 内容链 {@code ${CLAUDE_PLUGIN_ROOT}} 保持字面（对齐 CC
     * substitutePluginVariables 对无 path 的保持字面语义）。<b>不参与序列化</b>（@JsonIgnore，
     * CommandDto 显式字段构造，对齐 BudgetTracker local-only 红线）。
     */
    @JsonIgnore
    private String pluginRoot;

    /** @see #pluginRoot */
    @JsonIgnore
    public String getPluginRoot() { return pluginRoot; }

    /** @see #pluginRoot */
    @JsonIgnore
    public void setPluginRoot(String pluginRoot) { this.pluginRoot = pluginRoot; }

    /**
     * CC original: getPromptForCommand 闭包捕获的 {@code sourceName}（loadPluginCommands.ts:340-343
     * substitutePluginVariables 的 {@code source}）——plugin 源命令的 source 标识，用于
     * {@code ${CLAUDE_PLUGIN_DATA}} 内容替换（CC getPluginDataDir(source)，pluginDirectories.ts:119-127）。
     *
     * <p>由 {@code LoadPluginCommands.createPluginCommand} 置位（sourceName(plugin)，与
     * {@link PluginInfo#repository()} 同值投影）；null → {@code ${CLAUDE_PLUGIN_DATA}} 保持字面
     * （对齐 CC substitutePluginVariables 对 source 缺省不替换）。<b>不参与序列化</b>（@JsonIgnore）。
     */
    @JsonIgnore
    private String pluginSource;

    /** @see #pluginSource */
    @JsonIgnore
    public String getPluginSource() { return pluginSource; }

    /** @see #pluginSource */
    @JsonIgnore
    public void setPluginSource(String pluginSource) { this.pluginSource = pluginSource; }

    /**
     * CC original: pluginOptionsStorage {@code loadPluginOptions(source)}（pluginOptionsStorage.ts:56-80）
     * 的 plugin 选项值——{@code ${user_config.X}} 内容替换（substituteUserConfigInContent :385-419）。
     *
     * <p>由 {@code LoadPluginCommands.createPluginCommand} 经 pluginOptionsStorage 等价物置位；
     * 空 map + 空 sensitiveKeys → {@code ${user_config.X}} 全部保持字面（对齐 CC 未知键不抛，:399-402）。
     * <b>不参与序列化</b>（@JsonIgnore）。
     */
    @JsonIgnore
    private java.util.Map<String, Object> userConfig = java.util.Map.of();

    /** @see #userConfig */
    @JsonIgnore
    public java.util.Map<String, Object> getUserConfig() { return userConfig; }

    /** @see #userConfig */
    @JsonIgnore
    public void setUserConfig(java.util.Map<String, Object> userConfig) {
        this.userConfig = userConfig != null ? userConfig : java.util.Map.of();
    }

    /**
     * CC original: pluginManifest.userConfig schema 的敏感键集合（pluginOptionsStorage.ts:405-413）——
     * {@code ${user_config.X}} 命中敏感键 → 描述性占位符（密钥不进模型 prompt）。
     *
     * <p><b>不参与序列化</b>（@JsonIgnore）。
     */
    @JsonIgnore
    private java.util.Set<String> sensitiveKeys = java.util.Set.of();

    /** @see #sensitiveKeys */
    @JsonIgnore
    public java.util.Set<String> getSensitiveKeys() { return sensitiveKeys; }

    /** @see #sensitiveKeys */
    @JsonIgnore
    public void setSensitiveKeys(java.util.Set<String> sensitiveKeys) {
        this.sensitiveKeys = sensitiveKeys != null ? sensitiveKeys : java.util.Set.of();
    }
    public String getEffort() { return effort; }
    public void setEffort(String effort) { this.effort = effort; }
    public List<String> getPaths() { return paths; }
    public void setPaths(List<String> paths) { this.paths = paths; }
    public String getHooks() { return hooks; }
    public void setHooks(String hooks) { this.hooks = hooks; }
    public List<String> getArgNames() { return argNames; }
    public void setArgNames(List<String> argNames) { this.argNames = argNames; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentPath() { return contentPath; }
    public void setContentPath(String contentPath) { this.contentPath = contentPath; }
    public String getBaseDir() { return baseDir; }
    public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    public String getProgressMessage() { return progressMessage; }
    public void setProgressMessage(String progressMessage) { this.progressMessage = progressMessage; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getBuiltin() { return builtin; }
    public void setBuiltin(Boolean builtin) { this.builtin = builtin; }

    @JsonIgnore
    public BooleanSupplier getIsEnabled() { return isEnabled; }

    @JsonIgnore
    public void setIsEnabled(BooleanSupplier isEnabled) { this.isEnabled = isEnabled; }

    @JsonIgnore
    public BiFunction<String, PromptFnContext, List<ContentBlockParam>> getPromptFn() { return promptFn; }

    @JsonIgnore
    public void setPromptFn(BiFunction<String, PromptFnContext, List<ContentBlockParam>> promptFn) { this.promptFn = promptFn; }

    // ════════════════════════════════════════════════════════════════════════
    // 便捷方法
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 对齐 CC Command.userFacingName()（loadSkillsDir.ts:337-339，
     * {@code userFacingName() { return displayName || skillName }}）。
     *
     * <p>displayName 空串（CC falsy）也回退 name。
     *
     * @return displayName 非空 ? displayName : name
     */
    public String userFacingName() {
        return (displayName != null && !displayName.isEmpty()) ? displayName : name;
    }

    /**
     * 对齐 CC isCommandEnabled()（types/command.ts:214-215，
     * {@code isCommandEnabled = cmd.isEnabled?.() ?? true}）——惰性启用判定入口。
     *
     * <p>isEnabled supplier 非 null → 新鲜求值（覆盖 {@link #enabled} 兜底，对齐 CC isEnabled 优先）；
     * null → 回退 {@code !Boolean.FALSE.equals(enabled)}（enabled 构造默认 TRUE → {@code ?? true} 语义）。
     */
    public boolean isCommandEnabled() {
        return isEnabled != null ? isEnabled.getAsBoolean() : !Boolean.FALSE.equals(enabled);
    }

    /** 对齐 CC：构建 PromptCommand 内容估算 */
    public int getContentLength() {
        return content != null ? content.length() : 0;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 嵌套值对象（CC PromptCommand.pluginInfo）
    // ════════════════════════════════════════════════════════════════════════

    /**
     * CC original: PromptCommand.pluginInfo（types/command.ts:33-36）· plugin 源命令的插件清单信息。
     *
     * @param pluginManifest 插件清单（CC {@code PluginManifest}，此处为 name 投影——消费方仅取
     *                       {@code pluginManifest.name}，完整 Zod schema 不落模型层）
     * @param repository    插件来源名（CC {@code repository}，= sourceName，loadPluginCommands.ts:319）
     */
    public record PluginInfo(PluginManifest pluginManifest, String repository) {}

    /**
     * CC original: PluginManifest（utils/plugins/schemas.ts）的 name 投影。
     *
     * <p>模型层仅承载消费方所需字段（formatDescriptionWithSource commands.ts:738 取
     * {@code pluginManifest.name}；SkillTool.ts:189/:196 取 name），完整 PluginManifest Zod schema
     * 属 plugin 域，不在此投影（防 model→application 层倒置）。
     *
     * @param name 插件名（CC {@code PluginManifest.name}）
     */
    public record PluginManifest(String name) {}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Command c)) return false;
        return Objects.equals(id, c.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Command{id='" + id + "', name='" + name + "'}";
    }
}
