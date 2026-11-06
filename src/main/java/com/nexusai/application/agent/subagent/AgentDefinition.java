package com.nexusai.application.agent.subagent;

import com.nexusai.infra.util.PromptCategory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Agent 定义 · 对齐 CC loadAgentsDir.ts BaseAgentDefinition
 *
 * <p>CC 类型结构（继承层级）：
 * <pre>
 * BaseAgentDefinition (interface)
 *   ├── BuiltInAgentDefinition
 *   ├── CustomAgentDefinition
 *   └── PluginAgentDefinition
 * </pre>
 */
public sealed interface AgentDefinition permits AgentDefinition.BuiltInAgentDefinition,
                                                       AgentDefinition.CustomAgentDefinition,
                                                       AgentDefinition.PluginAgentDefinition,
                                                       AgentDefinition.ExternalAgentDefinition {
    
    String agentType();
    String whenToUse();
    Optional<List<String>> tools();
    Optional<List<String>> disallowedTools();
    Optional<List<String>> skills();
    Optional<List<Map<String, Object>>> mcpServers();
    Optional<Map<String, Object>> hooks();
    Optional<String> color();
    Optional<String> model();
    Optional<String> effort();
    Optional<String> permissionMode();
    Optional<Integer> maxTurns();
    Optional<String> filename();
    Optional<String> baseDir();
    Optional<String> criticalSystemReminder_EXPERIMENTAL();
    Optional<List<String>> requiredMcpServers();
    Optional<Boolean> background();
    Optional<String> initialPrompt();
    Optional<String> memory();
    Optional<String> isolation(); // 'worktree' | 'remote'
    /** CC original: pendingSnapshotUpdate（loadAgentsDir.ts:128）· 新快照待同步时间戳（前端 dialog 消费，Java 仅存数据字段）。 */
    Optional<String> pendingSnapshotUpdate();
    Optional<Boolean> omitClaudeMd();

    /**
     * 主线程模型覆盖（{@code provider/name}）· **本仓扩展，CC 无此字段**。
     *
     * <p>WHY：CC 的 {@code model:} 只用于子代理（{@code resumeAgent.ts:151} 的注释明写
     * 「Resolve model for analytics metadata」，主循环模型由 {@code --model} / {@code /model} 决定）。
     * 本仓用户 2026-11-05 裁定「主线程应该也可以支持，拿不到再回默认」⇒ 新增本字段，
     * 只从 MD frontmatter 的 {@code main-model} 读，⛔ 不从任何 CC 字段推导。
     *
     * <p>缺省 {@code Optional.empty()} = 不覆盖（走会话当前模型）。
     *
     * @return 主线程模型名（{@code provider/name}）
     */
    default Optional<String> mainModel() { return Optional.empty(); }
    
    /**
     * 获取系统提示 · 对齐 CC getSystemPrompt()
     * 内置 Agent 使用动态 prompt（依赖 modelId + additionalWorkingDirectories），
     * 自定义/插件 Agent 使用静态内容（忽略两入参）。
     *
     * @param modelId 完整 model id（CC original: resolvedAgentModel, runAgent.ts:340，逐调用传递，
     *                无进程级静态槽）；内置 Agent 用于渲染 env 块 modelDescription；null → 抑制模型描述行
     * @param additionalWorkingDirectories 附加工作目录路径列表（CC original: string[]，
     *                runAgent.ts:504-506 由 toolPermissionContext.additionalWorkingDirectories.keys() 派生，
     *                逐调用下传 computeEnvInfo 渲染 Additional working directories 行）
     */
    String getSystemPrompt(String modelId, List<String> additionalWorkingDirectories);
    
    /**
     * Agent 来源
     * 对齐 CC source: 'built-in' | 'userSettings' | 'projectSettings' | 'policySettings' | 'flagSettings' | 'plugin'
     */
    String source();
    
    /**
     * Built-in Agent 定义 · 对齐 CC BuiltInAgentDefinition
     */
    record BuiltInAgentDefinition(
        String agentType,
        String whenToUse,
        Optional<List<String>> tools,
        Optional<List<String>> disallowedTools,
        Optional<List<String>> skills,
        Optional<List<Map<String, Object>>> mcpServers,
        Optional<Map<String, Object>> hooks,
        Optional<String> color,
        Optional<String> model,
        Optional<String> effort,
        Optional<String> permissionMode,
        Optional<Integer> maxTurns,
        Optional<String> criticalSystemReminder_EXPERIMENTAL,
        Optional<List<String>> requiredMcpServers,
        Optional<Boolean> background,
        Optional<String> initialPrompt,
        Optional<String> memory,
        Optional<String> isolation,
        Optional<String> pendingSnapshotUpdate,
        Optional<Boolean> omitClaudeMd,
        /** 动态系统提示函数 · 对齐 CC getSystemPrompt；入参 (modelId, additionalWorkingDirectories)（逐调用传递，无静态槽） */
        BiFunction<String, List<String>, String> systemPromptFn,
        /**
         * 内置 Agent 完成 query 循环后的回调 · 对齐 CC callback?: () => void
         * <p>CC original: callback (loadAgentsDir.ts:139) — 函数类型 {@code () => void},
         * 仅在 {@code BuiltInAgentDefinition} 上存在; CC runAgent.ts:812-814 在 query 循环
         * 结束后、finally 清理前调用 {@code if (isBuiltInAgent && callback) callback()}。
         * CC builtInAgents.ts 当前 0 个内置 agent 提供 callback, 故 Java 端保持空实现
         * (Optional.empty(), 对齐 CC undefined), 仅对齐类型语义。
         */
        Optional<Runnable> callback
    ) implements AgentDefinition {
        
        @Override
        public String source() { return "built-in"; }
        
        @Override
        public Optional<String> baseDir() { return Optional.of("built-in"); }
        
        @Override
        public Optional<String> filename() { return Optional.empty(); }
        
        @Override
        public String getSystemPrompt(String modelId, List<String> additionalWorkingDirectories) {
            return systemPromptFn != null ? systemPromptFn.apply(modelId, additionalWorkingDirectories) : "";
        }
        
        /**
         * 旧 4 参工厂 · 保留向后兼容 (委托 {@link #builder}).
         *
         * <p>WHY: 5 个 caller (BuiltInAgents 5 处 + SubagentExecutorForkModeTest) 历史调用点,
         * 用户授权可破约但本期保留委托避免无谓 churn. 新代码应优先用 {@link #builder}.
         *
         * @param agentType      CC original: agentType (loadAgentsDir.ts:109)
         * @param whenToUse      CC original: whenToUse (loadAgentsDir.ts:110)
         * @param tools          CC original: tools (loadAgentsDir.ts:111); null -> Optional.empty()
         * @param systemPromptFn 动态系统提示函数 · 对齐 CC getSystemPrompt
         */
        public static BuiltInAgentDefinition create(
                String agentType, String whenToUse,
                List<String> tools, BiFunction<String, List<String>, String> systemPromptFn) {
            return builder(agentType, whenToUse, systemPromptFn).tools(tools).build();
        }

        /**
         * Builder 入口 · 对齐 CC 对象字面量 (CC 无构造器, Java 习语补齐 fluent 设置).
         *
         * <p>WHY (S1-2 决策): 21 参 canonical 构造器可读性差, CC 用对象字面量直接声明字段,
         * Java 端用 Builder 等价表达. 必填三项 (agentType/whenToUse/systemPromptFn) 作入参,
         * 其余 18 字段 fluent 设置, build() 调 canonical 构造器.
         *
         * @param agentType      CC original: agentType (loadAgentsDir.ts:109)
         * @param whenToUse      CC original: whenToUse (loadAgentsDir.ts:110)
         * @param systemPromptFn 动态系统提示函数 · 对齐 CC getSystemPrompt
         */
        public static Builder builder(String agentType, String whenToUse,
                                       BiFunction<String, List<String>, String> systemPromptFn) {
            return new Builder(agentType, whenToUse, systemPromptFn);
        }

        /**
         * 可变 Builder · fluent 设置 18 个可选字段, build() 产出不可变 {@link BuiltInAgentDefinition}.
         *
         * <p>所有 Optional 字段缺省 {@code Optional.empty()} (对齐 CC undefined).
         */
        public static final class Builder {
            private final String agentType;
            private final String whenToUse;
            private final BiFunction<String, List<String>, String> systemPromptFn;
            private Optional<List<String>> tools = Optional.empty();
            private Optional<List<String>> disallowedTools = Optional.empty();
            private Optional<List<String>> skills = Optional.empty();
            private Optional<List<Map<String, Object>>> mcpServers = Optional.empty();
            private Optional<Map<String, Object>> hooks = Optional.empty();
            private Optional<String> color = Optional.empty();
            private Optional<String> model = Optional.empty();
            private Optional<String> effort = Optional.empty();
            private Optional<String> permissionMode = Optional.empty();
            private Optional<Integer> maxTurns = Optional.empty();
            private Optional<String> criticalSystemReminder = Optional.empty();
            private Optional<List<String>> requiredMcpServers = Optional.empty();
            private Optional<Boolean> background = Optional.empty();
            private Optional<String> initialPrompt = Optional.empty();
            private Optional<String> memory = Optional.empty();
            private Optional<String> isolation = Optional.empty();
            private Optional<String> pendingSnapshotUpdate = Optional.empty();
            private Optional<Boolean> omitClaudeMd = Optional.empty();
            private Optional<Runnable> callback = Optional.empty();

            private Builder(String agentType, String whenToUse,
                             BiFunction<String, List<String>, String> systemPromptFn) {
                this.agentType = agentType;
                this.whenToUse = whenToUse;
                this.systemPromptFn = systemPromptFn;
            }

            /** CC original: tools (loadAgentsDir.ts:111); null -> empty (全部工具, 对齐 CC undefined). */
            public Builder tools(List<String> v) { this.tools = Optional.ofNullable(v); return this; }
            /** CC original: disallowedTools (loadAgentsDir.ts:112). */
            public Builder disallowedTools(List<String> v) { this.disallowedTools = Optional.ofNullable(v); return this; }
            /** CC original: skills (loadAgentsDir.ts:113). */
            public Builder skills(List<String> v) { this.skills = Optional.ofNullable(v); return this; }
            /** CC original: mcpServers (loadAgentsDir.ts:114). */
            public Builder mcpServers(List<Map<String, Object>> v) { this.mcpServers = Optional.ofNullable(v); return this; }
            /** CC original: hooks (loadAgentsDir.ts:115). */
            public Builder hooks(Map<String, Object> v) { this.hooks = Optional.ofNullable(v); return this; }
            /** CC original: color (loadAgentsDir.ts:116). */
            public Builder color(String v) { this.color = Optional.ofNullable(v); return this; }
            /** CC original: model (loadAgentsDir.ts:117). */
            public Builder model(String v) { this.model = Optional.ofNullable(v); return this; }
            /** CC original: effort (loadAgentsDir.ts:118). */
            public Builder effort(String v) { this.effort = Optional.ofNullable(v); return this; }
            /** CC original: permissionMode (loadAgentsDir.ts:119). */
            public Builder permissionMode(String v) { this.permissionMode = Optional.ofNullable(v); return this; }
            /** CC original: maxTurns (loadAgentsDir.ts:120). */
            public Builder maxTurns(Integer v) { this.maxTurns = Optional.ofNullable(v); return this; }
            /** CC original: criticalSystemReminder_EXPERIMENTAL (loadAgentsDir.ts:123). */
            public Builder criticalSystemReminder_EXPERIMENTAL(String v) { this.criticalSystemReminder = Optional.ofNullable(v); return this; }
            /** CC original: requiredMcpServers (loadAgentsDir.ts:125). */
            public Builder requiredMcpServers(List<String> v) { this.requiredMcpServers = Optional.ofNullable(v); return this; }
            /** CC original: background (loadAgentsDir.ts:127). */
            public Builder background(Boolean v) { this.background = Optional.ofNullable(v); return this; }
            /** CC original: initialPrompt (loadAgentsDir.ts:128). */
            public Builder initialPrompt(String v) { this.initialPrompt = Optional.ofNullable(v); return this; }
            /** CC original: memory (loadAgentsDir.ts:129). */
            public Builder memory(String v) { this.memory = Optional.ofNullable(v); return this; }
            /** CC original: isolation (loadAgentsDir.ts:130) 'worktree' | 'remote'. */
            public Builder isolation(String v) { this.isolation = Optional.ofNullable(v); return this; }
            /** CC original: pendingSnapshotUpdate (loadAgentsDir.ts:128) · 快照待同步时间戳. */
            public Builder pendingSnapshotUpdate(String v) { this.pendingSnapshotUpdate = Optional.ofNullable(v); return this; }
            /** CC original: omitClaudeMd (loadAgentsDir.ts:132). */
            public Builder omitClaudeMd(Boolean v) { this.omitClaudeMd = Optional.ofNullable(v); return this; }
            /** CC original: callback?: () => void (loadAgentsDir.ts:139) · 内置 Agent query 循环结束后回调 (runAgent.ts:812-814); CC 0 内置 agent 使用, 保持空实现. */
            public Builder callback(Runnable v) { this.callback = Optional.ofNullable(v); return this; }

            public BuiltInAgentDefinition build() {
                return new BuiltInAgentDefinition(
                    agentType, whenToUse, tools, disallowedTools, skills,
                    mcpServers, hooks, color, model, effort,
                    permissionMode, maxTurns, criticalSystemReminder,
                    requiredMcpServers, background, initialPrompt, memory,
                    isolation, pendingSnapshotUpdate, omitClaudeMd, systemPromptFn, callback
                );
            }
        }
    }
    
    /**
     * Custom Agent 定义 · 对齐 CC CustomAgentDefinition
     * 来源：userSettings, projectSettings, policySettings, flagSettings
     */
    record CustomAgentDefinition(
        String agentType,
        String whenToUse,
        Optional<List<String>> tools,
        Optional<List<String>> disallowedTools,
        Optional<List<String>> skills,
        Optional<List<Map<String, Object>>> mcpServers,
        Optional<Map<String, Object>> hooks,
        Optional<String> color,
        Optional<String> model,
        Optional<String> effort,
        Optional<String> permissionMode,
        Optional<Integer> maxTurns,
        Optional<String> filename,
        Optional<String> baseDir,
        Optional<String> criticalSystemReminder_EXPERIMENTAL,
        Optional<List<String>> requiredMcpServers,
        Optional<Boolean> background,
        Optional<String> initialPrompt,
        Optional<String> memory,
        Optional<String> isolation,
        Optional<String> pendingSnapshotUpdate,
        Optional<Boolean> omitClaudeMd,
        /** 主线程模型覆盖（本仓扩展；MD frontmatter {@code main-model}）· empty = 不覆盖 */
        Optional<String> mainModel,
        /** 自定义 Agent 来源 · 对齐 CC source */
        String customSource,  // 'userSettings' | 'projectSettings' | 'policySettings' | 'flagSettings'
        /** 系统提示内容（静态）· 对齐 CC getSystemPrompt */
        String systemPromptContent
    ) implements AgentDefinition {
        
        @Override
        public String source() { return customSource; }
        
        @Override
        public String getSystemPrompt(String modelId, List<String> additionalWorkingDirectories) {
            return systemPromptContent != null ? systemPromptContent : "";
        }

        /**
         * 旧 5 参工厂 · 保留向后兼容 (委托 {@link #builder}).
         *
         * <p>WHY: loadAgentFile 历史调用点. 新代码 (loadAgentFile 16+ 字段解析) 应优先用
         * {@link #builder} 逐字段 fluent 设置, 对齐 CC parseAgentFromMarkdown 返回的 16+ 字段.
         *
         * @param agentType     CC original: name (loadAgentsDir.ts:549)
         * @param whenToUse     CC original: description (loadAgentsDir.ts:550)
         * @param tools         CC original: tools; null -> empty (全部工具)
         * @param systemPrompt  静态系统提示内容 (body)
         * @param source        CC original: source ('userSettings'|'projectSettings'|'policySettings'|'flagSettings')
         */
        public static CustomAgentDefinition create(
                String agentType, String whenToUse,
                List<String> tools, String systemPrompt,
                String source) {
            return builder(agentType, whenToUse, source, systemPrompt).tools(tools).build();
        }

        /**
         * Builder 入口 · 对齐 CC parseAgentFromMarkdown (loadAgentsDir.ts:541-755) 16+ 字段解析.
         *
         * <p>WHY (S1-2 决策): 23 参 canonical 构造器可读性差, CC parseAgentFromMarkdown 用对象字面量
         * 逐字段条件展开, Java 端用 Builder 等价表达. 必填四项 (agentType/whenToUse/customSource/
         * systemPromptContent) 作入参, 其余字段 fluent 设置.
         *
         * @param agentType           CC original: name (loadAgentsDir.ts:549)
         * @param whenToUse           CC original: description (loadAgentsDir.ts:550)
         * @param customSource        CC original: source
         * @param systemPromptContent 静态系统提示内容 (body, loadAgentsDir.ts:729)
         */
        public static Builder builder(String agentType, String whenToUse,
                                       String customSource, String systemPromptContent) {
            return new Builder(agentType, whenToUse, customSource, systemPromptContent);
        }

        /**
         * 可变 Builder · fluent 设置 19 个可选字段, build() 产出不可变 {@link CustomAgentDefinition}.
         *
         * <p>所有 Optional 字段缺省 {@code Optional.empty()} (对齐 CC undefined).
         * <p>注意: CC parseAgentFromMarkdown 不解析 omitClaudeMd (仅内置 agent 有), 故 Builder
         * 暴露 omitClaudeMd 但 loadAgentFile 不设置 (缺省 empty, 对齐 CC).
         */
        public static final class Builder {
            private final String agentType;
            private final String whenToUse;
            private final String customSource;
            private final String systemPromptContent;
            private Optional<List<String>> tools = Optional.empty();
            private Optional<List<String>> disallowedTools = Optional.empty();
            private Optional<List<String>> skills = Optional.empty();
            private Optional<List<Map<String, Object>>> mcpServers = Optional.empty();
            private Optional<Map<String, Object>> hooks = Optional.empty();
            private Optional<String> color = Optional.empty();
            private Optional<String> model = Optional.empty();
            private Optional<String> effort = Optional.empty();
            private Optional<String> permissionMode = Optional.empty();
            private Optional<Integer> maxTurns = Optional.empty();
            private Optional<String> filename = Optional.empty();
            private Optional<String> baseDir = Optional.empty();
            private Optional<String> criticalSystemReminder = Optional.empty();
            private Optional<List<String>> requiredMcpServers = Optional.empty();
            private Optional<Boolean> background = Optional.empty();
            private Optional<String> initialPrompt = Optional.empty();
            private Optional<String> memory = Optional.empty();
            private Optional<String> isolation = Optional.empty();
            private Optional<String> pendingSnapshotUpdate = Optional.empty();
            private Optional<Boolean> omitClaudeMd = Optional.empty();
            private Optional<String> mainModel = Optional.empty();

            private Builder(String agentType, String whenToUse, String customSource,
                             String systemPromptContent) {
                this.agentType = agentType;
                this.whenToUse = whenToUse;
                this.customSource = customSource;
                this.systemPromptContent = systemPromptContent;
            }

            /** CC original: tools (loadAgentsDir.ts:678); null -> empty (全部工具, 对齐 CC undefined). */
            public Builder tools(List<String> v) { this.tools = Optional.ofNullable(v); return this; }
            /** CC original: disallowedTools (loadAgentsDir.ts:690). */
            public Builder disallowedTools(List<String> v) { this.disallowedTools = Optional.ofNullable(v); return this; }
            /** CC original: skills (loadAgentsDir.ts:694). */
            public Builder skills(List<String> v) { this.skills = Optional.ofNullable(v); return this; }
            /** CC original: mcpServers (loadAgentsDir.ts:707). */
            public Builder mcpServers(List<Map<String, Object>> v) { this.mcpServers = Optional.ofNullable(v); return this; }
            /** CC original: hooks (loadAgentsDir.ts:719 parseHooksFromFrontmatter). */
            public Builder hooks(Map<String, Object> v) { this.hooks = Optional.ofNullable(v); return this; }
            /** CC original: color (loadAgentsDir.ts:558). */
            public Builder color(String v) { this.color = Optional.ofNullable(v); return this; }
            /** CC original: model (loadAgentsDir.ts:560-566). */
            public Builder model(String v) { this.model = Optional.ofNullable(v); return this; }
            /** CC original: effort (loadAgentsDir.ts:603). */
            public Builder effort(String v) { this.effort = Optional.ofNullable(v); return this; }
            /** CC original: permissionMode (loadAgentsDir.ts:620). */
            public Builder permissionMode(String v) { this.permissionMode = Optional.ofNullable(v); return this; }
            /** CC original: maxTurns (loadAgentsDir.ts:636). */
            public Builder maxTurns(Integer v) { this.maxTurns = Optional.ofNullable(v); return this; }
            /** CC original: filename (loadAgentsDir.ts:638 basename without .md). */
            public Builder filename(String v) { this.filename = Optional.ofNullable(v); return this; }
            /** CC original: baseDir (loadAgentsDir.ts:544 入参). */
            public Builder baseDir(String v) { this.baseDir = Optional.ofNullable(v); return this; }
            /** CC original: criticalSystemReminder_EXPERIMENTAL. */
            public Builder criticalSystemReminder_EXPERIMENTAL(String v) { this.criticalSystemReminder = Optional.ofNullable(v); return this; }
            /** CC original: requiredMcpServers. */
            public Builder requiredMcpServers(List<String> v) { this.requiredMcpServers = Optional.ofNullable(v); return this; }
            /** CC original: background (loadAgentsDir.ts:575). */
            public Builder background(Boolean v) { this.background = Optional.ofNullable(v); return this; }
            /** CC original: initialPrompt (loadAgentsDir.ts:699). */
            public Builder initialPrompt(String v) { this.initialPrompt = Optional.ofNullable(v); return this; }
            /** CC original: memory (loadAgentsDir.ts:584). */
            public Builder memory(String v) { this.memory = Optional.ofNullable(v); return this; }
            /** CC original: isolation (loadAgentsDir.ts:596) 'worktree' | 'remote'. */
            public Builder isolation(String v) { this.isolation = Optional.ofNullable(v); return this; }
            /** CC original: pendingSnapshotUpdate (loadAgentsDir.ts:128) · 快照待同步时间戳. */
            public Builder pendingSnapshotUpdate(String v) { this.pendingSnapshotUpdate = Optional.ofNullable(v); return this; }
            /** CC 不解析 (仅内置 agent 有); 暴露供扩展, loadAgentFile 不设置. */
            public Builder omitClaudeMd(Boolean v) { this.omitClaudeMd = Optional.ofNullable(v); return this; }
            /** 主线程模型覆盖（本仓扩展）· MD frontmatter {@code main-model}；null → empty. */
            public Builder mainModel(String v) { this.mainModel = Optional.ofNullable(v); return this; }

            public CustomAgentDefinition build() {
                return new CustomAgentDefinition(
                    agentType, whenToUse, tools, disallowedTools, skills,
                    mcpServers, hooks, color, model, effort,
                    permissionMode, maxTurns, filename, baseDir,
                    criticalSystemReminder, requiredMcpServers, background,
                    initialPrompt, memory, isolation, pendingSnapshotUpdate, omitClaudeMd,
                    mainModel,
                    customSource, systemPromptContent
                );
            }
        }
    }
    
    /**
     * Plugin Agent 定义 · 对齐 CC PluginAgentDefinition
     */
    record PluginAgentDefinition(
        String agentType,
        String whenToUse,
        Optional<List<String>> tools,
        Optional<List<String>> disallowedTools,
        Optional<List<String>> skills,
        Optional<List<Map<String, Object>>> mcpServers,
        Optional<Map<String, Object>> hooks,
        Optional<String> color,
        Optional<String> model,
        Optional<String> effort,
        Optional<String> permissionMode,
        Optional<Integer> maxTurns,
        Optional<String> filename,
        Optional<String> baseDir,
        Optional<String> criticalSystemReminder_EXPERIMENTAL,
        Optional<List<String>> requiredMcpServers,
        Optional<Boolean> background,
        Optional<String> initialPrompt,
        Optional<String> memory,
        Optional<String> isolation,
        Optional<String> pendingSnapshotUpdate,
        Optional<Boolean> omitClaudeMd,
        String pluginName,
        String systemPromptContent
    ) implements AgentDefinition {
        
        @Override
        public String source() { return "plugin"; }

        @Override
        public String getSystemPrompt(String modelId, List<String> additionalWorkingDirectories) {
            return systemPromptContent != null ? systemPromptContent : "";
        }

        /**
         * [ODF-C3] Builder 入口 · 对齐 CC loadPluginAgents.ts:70-214 loadAgentFromFile
         * 返回的 AgentDefinition 字段（agentType/plugin/source/systemPrompt 等）。
         *
         * <p>WHY: 23 参 canonical 构造器可读性差 + 参数易错位。新增 LoadPluginAgents 扫描器
         * 需要 fluent 等价（对齐 CustomAgentDefinition.Builder 既有范式，CLAUDE.md 规则 11）。
         *
         * @param agentType           CC original: agentType（loadPluginAgents.ts:121
         *                            {@code [pluginName, ...namespace, baseAgentName].join(':')}）
         * @param whenToUse           CC original: whenToUse（:123-125 description/when-to-use）
         * @param pluginName          CC original: plugin（:213）
         * @param systemPromptContent 系统提示内容（静态 body）
         */
        public static Builder builder(String agentType, String whenToUse,
                                       String pluginName, String systemPromptContent) {
            return new Builder(agentType, whenToUse, pluginName, systemPromptContent);
        }

        /**
         * 可变 Builder · fluent 设置可选字段, build() 产出不可变 {@link PluginAgentDefinition}.
         *
         * <p>收窄安全边界（IMP-SUB-22 #8）: 仅暴露 CC 解析字段的 setter。
         * mcpServers/hooks/permissionMode/criticalSystemReminder_EXPERIMENTAL/requiredMcpServers/
         * initialPrompt/pendingSnapshotUpdate/omitClaudeMd 已删除公开暴露——CC loadPluginAgents.ts:153-168
         * 插件安全边界不解析这些字段（插件是第三方 marketplace 代码，逐 agent 声明会越权）。对应 record
         * 组件恒 {@code Optional.empty()}（满足 sealed interface 访问器契约）。
         */
        public static final class Builder {
            private final String agentType;
            private final String whenToUse;
            private final String pluginName;
            private final String systemPromptContent;
            private Optional<List<String>> tools = Optional.empty();
            private Optional<List<String>> disallowedTools = Optional.empty();
            private Optional<List<String>> skills = Optional.empty();
            private Optional<String> color = Optional.empty();
            private Optional<String> model = Optional.empty();
            private Optional<String> effort = Optional.empty();
            private Optional<Integer> maxTurns = Optional.empty();
            private Optional<String> filename = Optional.empty();
            private Optional<String> baseDir = Optional.empty();
            private Optional<Boolean> background = Optional.empty();
            private Optional<String> memory = Optional.empty();
            private Optional<String> isolation = Optional.empty();

            private Builder(String agentType, String whenToUse, String pluginName,
                            String systemPromptContent) {
                this.agentType = agentType;
                this.whenToUse = whenToUse;
                this.pluginName = pluginName;
                this.systemPromptContent = systemPromptContent;
            }

            /** CC original: tools（loadPluginAgents.ts:128）；null -> empty（全部工具）. */
            public Builder tools(List<String> v) { this.tools = Optional.ofNullable(v); return this; }
            /** CC original: disallowedTools（:224）. */
            public Builder disallowedTools(List<String> v) { this.disallowedTools = Optional.ofNullable(v); return this; }
            /** CC original: skills（:130）. */
            public Builder skills(List<String> v) { this.skills = Optional.ofNullable(v); return this; }
            /** CC original: color（:133）. */
            public Builder color(String v) { this.color = Optional.ofNullable(v); return this; }
            /** CC original: model（:135-140 trim；'inherit' 保留小写）. */
            public Builder model(String v) { this.model = Optional.ofNullable(v); return this; }
            /** CC original: effort（:162-166）. */
            public Builder effort(String v) { this.effort = Optional.ofNullable(v); return this; }
            /** CC original: maxTurns（:176-183）. */
            public Builder maxTurns(Integer v) { this.maxTurns = Optional.ofNullable(v); return this; }
            /** CC original: filename（:211 basename without .md）. */
            public Builder filename(String v) { this.filename = Optional.ofNullable(v); return this; }
            /** CC original: plugin path（loadPluginAgents.ts:92 pluginPath 入参）. */
            public Builder baseDir(String v) { this.baseDir = Optional.ofNullable(v); return this; }
            /** CC original: background（:147）. */
            public Builder background(Boolean v) { this.background = Optional.ofNullable(v); return this; }
            /** CC original: memory（:152-158）. */
            public Builder memory(String v) { this.memory = Optional.ofNullable(v); return this; }
            /** CC original: isolation（:161）. */
            public Builder isolation(String v) { this.isolation = Optional.ofNullable(v); return this; }

            public PluginAgentDefinition build() {
                // CC 不解析字段（mcpServers/hooks/permissionMode/criticalSystemReminder_EXPERIMENTAL/
                // requiredMcpServers/initialPrompt/pendingSnapshotUpdate/omitClaudeMd）恒 Optional.empty()
                // —— 对齐 CC loadPluginAgents.ts:153-168 插件安全边界不解析这些字段（IMP-SUB-22 #8 已删
                // Builder 公开暴露，记录组件保留以满足 sealed interface 访问器契约）。
                return new PluginAgentDefinition(
                    agentType, whenToUse, tools, disallowedTools, skills,
                    Optional.empty(), Optional.empty(), color, model, effort,
                    Optional.empty(), maxTurns, filename, baseDir,
                    Optional.empty(), Optional.empty(), background,
                    Optional.empty(), memory, isolation, Optional.empty(), Optional.empty(),
                    pluginName, systemPromptContent
                );
            }
        }
    }

    /**
     * 外部 CLI 运行时型 Agent 定义 · 本仓扩展（无 CC 对应物）。
     *
     * <p><b>WHY 需要第 4 种 permit</b>：{@code Agent} 工具的 agent 清单来自
     * {@code AgentDefinitionRegistry.listAgents()}，但注册中心经
     * {@link loadAgentsDir#getActiveAgentsFromList} 做**六组 source 白名单**折叠
     * （loadAgentsDir.ts:193-221 同构）——没落在这 6 个 source 值里的定义会被**静默丢弃**，
     * 工具描述里看不到、模型永远选不到。故本类型的 {@code source()} <b>必须复用六组之一</b>
     * （由 md 所在配置根决定 userSettings / projectSettings / policySettings / flagSettings），
     * ⛔ 不得新增 source 字面量。
     *
     * <p><b>能力位语义（不假装）</b>：外部进程不认本仓的工具白名单 / hooks / MCP / resume，
     * 故 {@link Capabilities} 四个布尔显式声明后端能提供什么，消费侧按位降级。
     * {@code tools()/disallowedTools()/hooks()/mcpServers()/skills()/isolation()} 一律
     * 返回 {@code Optional.empty()} = 「本后端不支持」，⛔ 不用空列表冒充「支持但为空」。
     *
     * @param agentType          唯一类型名（md frontmatter {@code name}）
     * @param whenToUse          给模型看的选择依据（md frontmatter {@code description}）
     * @param externalSource     六组 source 之一（userSettings / projectSettings / ...）
     * @param systemPromptContent md 正文（作为任务前缀/角色说明）
     * @param runtime            外部运行时 id（claude / codex / opencode / pi / acp / 任意 recipe id）
     * @param bin                可执行文件名（null → 取 runtime 注册表默认）
     * @param fallbackBins       备用可执行文件名（探测顺序）
     * @param versionArgs        探测 argv（默认 {@code --version}）
     * @param args               argv 模板，支持 {@code {prompt}} / {model} / {cwd} 占位
     * @param promptVia          {@code argv}（任务作为最后一个参数）或 {@code stdin}
     * @param streamFormat       输出协议（claude-stream-json / jsonl / plain）
     * @param timeoutMs          硬超时（毫秒，到点杀进程树）
     * @param capabilities       能力位
     */
    record ExternalAgentDefinition(
        String agentType,
        String whenToUse,
        String externalSource,
        String systemPromptContent,
        String runtime,
        Optional<String> bin,
        List<String> fallbackBins,
        List<String> versionArgs,
        List<String> args,
        String promptVia,
        String streamFormat,
        long timeoutMs,
        Capabilities capabilities,
        Optional<String> model,
        Optional<String> permissionMode,
        Optional<Integer> maxTurns,
        Optional<Boolean> background,
        Optional<String> effort,
        Optional<String> color,
        Optional<String> filename,
        Optional<String> baseDir,
        /**
         * 追加在内置 argv **之前**的前缀参数（高级用法）。
         *
         * <p>为什么是前缀而不是追加在末尾：各 CLI 的约定不同，插在末尾常会破坏
         * 「最后一个位置参数是任务」的形态（如 {@code claude -p "<task>"}）。
         */
        List<String> argsPrefix
    ) implements AgentDefinition {

        /**
         * 外部后端能力位 · 消费侧必须按位降级，⛔ 不得假定全部为 true。
         *
         * @param streaming    能否解析中间事件（thinking / tool-call / text-delta）
         * @param resume       是否支持续跑同一会话
         * @param outputSchema 是否支持结构化输出校验
         * @param depthLimit   允许的最大递归深度（0 = 禁止它再派子代理）
         */
        public record Capabilities(boolean streaming, boolean resume, boolean outputSchema, int depthLimit) {
            /** 保守默认：只认最终文本，不续跑、不校验、禁止递归。 */
            public static final Capabilities MINIMAL = new Capabilities(false, false, false, 0);
        }

        @Override
        public String source() { return externalSource; }

        @Override
        public String getSystemPrompt(String modelId, List<String> additionalWorkingDirectories) {
            return systemPromptContent != null ? systemPromptContent : "";
        }

        // ── 本后端**不支持**的 CC 字段：恒 empty（= 不支持，不是「空值」）──
        @Override public Optional<List<String>> tools() { return Optional.empty(); }
        @Override public Optional<List<String>> disallowedTools() { return Optional.empty(); }
        @Override public Optional<List<String>> skills() { return Optional.empty(); }
        @Override public Optional<List<Map<String, Object>>> mcpServers() { return Optional.empty(); }
        @Override public Optional<Map<String, Object>> hooks() { return Optional.empty(); }
        @Override public Optional<String> criticalSystemReminder_EXPERIMENTAL() { return Optional.empty(); }
        @Override public Optional<List<String>> requiredMcpServers() { return Optional.empty(); }
        @Override public Optional<String> initialPrompt() { return Optional.empty(); }
        @Override public Optional<String> memory() { return Optional.empty(); }
        @Override public Optional<String> isolation() { return Optional.empty(); }
        @Override public Optional<String> pendingSnapshotUpdate() { return Optional.empty(); }
        @Override public Optional<Boolean> omitClaudeMd() { return Optional.empty(); }

        public static Builder builder(String agentType, String whenToUse,
                                      String externalSource, String systemPromptContent) {
            return new Builder(agentType, whenToUse, externalSource, systemPromptContent);
        }

        public static final class Builder {
            private final String agentType;
            private final String whenToUse;
            private final String externalSource;
            private final String systemPromptContent;
            private String runtime;
            private Optional<String> bin = Optional.empty();
            private List<String> fallbackBins = List.of();
            private List<String> versionArgs = List.of("--version");
            private List<String> args = List.of();
            private String promptVia = "argv";
            /**
             * 空串 = 用户未指定 ⇒ 由 {@code baseRuntime} 预设提供（再没有则回落 plain）。
             *
             * <p>⛔ 默认值不能写 {@code "plain"}：那会让「继承预设的 streamFormat」永远不生效
             * —— 用户写了 {@code baseRuntime: claude} 却按 plain 解析，静默拿不到结构化事件。
             * （2026-11-04 由 {@code ExternalRuntimeInheritanceTest} 抓到。）
             */
            private String streamFormat = "";
            private long timeoutMs = 600_000L;
            private Capabilities capabilities = Capabilities.MINIMAL;
            private Optional<String> model = Optional.empty();
            private Optional<String> permissionMode = Optional.empty();
            private Optional<Integer> maxTurns = Optional.empty();
            private Optional<Boolean> background = Optional.empty();
            private Optional<String> effort = Optional.empty();
            private Optional<String> color = Optional.empty();
            private Optional<String> filename = Optional.empty();
            private Optional<String> baseDir = Optional.empty();
            private List<String> argsPrefix = List.of();

            private Builder(String agentType, String whenToUse,
                            String externalSource, String systemPromptContent) {
                this.agentType = agentType;
                this.whenToUse = whenToUse;
                this.externalSource = externalSource;
                this.systemPromptContent = systemPromptContent;
            }

            /** 外部运行时 id（必填；缺失时 build() 抛 IllegalArgumentException）。 */
            public Builder runtime(String v) { this.runtime = v; return this; }
            public Builder bin(String v) { this.bin = Optional.ofNullable(v); return this; }
            public Builder fallbackBins(List<String> v) { this.fallbackBins = v == null ? List.of() : List.copyOf(v); return this; }
            public Builder versionArgs(List<String> v) { this.versionArgs = v == null ? List.of() : List.copyOf(v); return this; }
            public Builder args(List<String> v) { this.args = v == null ? List.of() : List.copyOf(v); return this; }
            public Builder promptVia(String v) { if (v != null && !v.isBlank()) this.promptVia = v; return this; }
            public Builder streamFormat(String v) { if (v != null && !v.isBlank()) this.streamFormat = v; return this; }
            public Builder timeoutMs(long v) { if (v > 0) this.timeoutMs = v; return this; }
            public Builder capabilities(Capabilities v) { if (v != null) this.capabilities = v; return this; }
            public Builder model(String v) { this.model = Optional.ofNullable(v); return this; }
            public Builder permissionMode(String v) { this.permissionMode = Optional.ofNullable(v); return this; }
            public Builder maxTurns(Integer v) { this.maxTurns = Optional.ofNullable(v); return this; }
            public Builder background(Boolean v) { this.background = Optional.ofNullable(v); return this; }
            public Builder effort(String v) { this.effort = Optional.ofNullable(v); return this; }
            public Builder color(String v) { this.color = Optional.ofNullable(v); return this; }
            public Builder filename(String v) { this.filename = Optional.ofNullable(v); return this; }
            public Builder baseDir(String v) { this.baseDir = Optional.ofNullable(v); return this; }

            /** 追加在内置 argv 之前的前缀参数。 */
            public Builder argsPrefix(List<String> v) { this.argsPrefix = v == null ? List.of() : List.copyOf(v); return this; }

            public ExternalAgentDefinition build() {
                if (runtime == null || runtime.isBlank()) {
                    throw new IllegalArgumentException(
                        "外部运行时 agent '" + agentType + "' 缺 sub-runtime 字段（frontmatter 写 sub-runtime: claude|codex|opencode|pi，表示子代理用哪个外部 CLI）");
                }
                // argv 校验（用户 2026-11-04 简化口径：只选名字即可）
                //   - args 为空 ⇒ 合法：运行时由本机扫描到的预设提供完整 argv
                //   - args 非空 ⇒ 必须含 {prompt}，否则任务传不进 CLI
                if ("argv".equals(promptVia) && !args.isEmpty()) {
                    boolean hasPromptPlaceholder = args.stream()
                            .anyMatch(a -> a != null && a.contains("{prompt}"));
                    if (!hasPromptPlaceholder) {
                        throw new IllegalArgumentException(
                            "外部运行时 agent '" + agentType + "' 的 args 缺少 {prompt} 占位（promptVia=argv 时任务无处传递）。"
                                + "若只想用内置预设，请删掉 args 只留 sub-runtime");
                    }
                }
                return new ExternalAgentDefinition(
                    agentType, whenToUse, externalSource, systemPromptContent,
                    runtime, bin, fallbackBins, versionArgs, args,
                    promptVia, streamFormat, timeoutMs, capabilities,
                    model, permissionMode, maxTurns, background, effort, color, filename, baseDir,
                    argsPrefix
                );
            }
        }
    }

    /**
     * 类型守卫 · 对齐 CC isBuiltInAgent/isCustomAgent/isPluginAgent
     */
    static boolean isBuiltIn(AgentDefinition agent) {
        return agent instanceof BuiltInAgentDefinition;
    }

    static boolean isCustom(AgentDefinition agent) {
        return agent instanceof CustomAgentDefinition;
    }

    static boolean isPlugin(AgentDefinition agent) {
        return agent instanceof PluginAgentDefinition;
    }

    /** 是否外部 CLI 运行时型（本仓扩展，非 CC 概念）。 */
    static boolean isExternal(AgentDefinition agent) {
        return agent instanceof ExternalAgentDefinition;
    }

    /**
     * 检查是否使用所有工具 · 对齐 CC hasWildcard 精确判定
     * <p>CC original: agentToolUtils.ts:163-165 {@code hasWildcard =
     * agentTools.length === 1 && agentTools[0] === '*'}（resolveAgentTools 内联）。
     * <p>精确单元素 {@code ['*']} 才为 wildcard；工具列表包含其它工具
     * （如 {@code ['*', 'read_file']}）按 by-name 精确解析，不得全量放行。
     * Optional.empty()（tools undefined）由调用方 SubagentExecutor:2659
     * {@code tools().isEmpty()} 前置覆盖，本方法不处理。
     */
    default boolean usesAllTools() {
        return tools().map(t -> t.size() == 1 && "*".equals(t.get(0))).orElse(false);
    }

    /**
     * 计算本 Agent 的 querySource 值 · 对齐 CC utils/promptCategory.ts:16-28 getQuerySourceForAgent
     * + AgentTool.tsx:609（子代理 querySource 唯一来源：
     * {@code toolUseContext.options.querySource ?? getQuerySourceForAgent(agentType, isBuiltInAgent)}）。
     *
     * <p>CC 行为（唯一真源，promptCategory.ts:16-28，不含注释转述）：
     * <pre>
     *   if (isBuiltInAgent) return agentType ? `agent:builtin:${agentType}` : 'agent:default'
     *   else return 'agent:custom'
     * </pre>
     *
     * <p><b>WHY（D13/D9 querySource 值面双向漂移修复）</b>：旧 SubagentExecutor:1361 用
     * {@code "agent:" + source() + ":" + agentType()} 拼值，两分支均与 CC 值面不符：
     * <ul>
     *   <li>builtin → {@code agent:built-in:<type>}（连字符，source()="built-in"）vs CC
     *       {@code agent:builtin:<type>}（无连字符，promptCategory.ts:23）</li>
     *   <li>custom/plugin → {@code agent:<customSource>:<type>}（非常量）vs CC 恒常量
     *       {@code agent:custom}（promptCategory.ts:26）</li>
     * </ul>
     * 本方法不修改 source()（source 字段仍返回 CC 原值面，loadAgentsDir:1182-1185 过滤依赖），
     * 而是由 querySource 派生侧直接委托 {@link PromptCategory#getQuerySourceForAgent}，
     * 对齐 CC 以 isBuiltInAgent 布尔分支（而非 source 值面）决定 querySource。
     *
     * <p><b>[IMP2-05 值域复活]</b>：本方法产出的是 <b>agentType 级精确值</b>——即
     * QueryParams.querySourceValue 应承载的运行时值（loop 发射侧经
     * {@link com.nexusai.application.agent.QuerySource#effectiveValue(QuerySource, String)}
     * 优先取用）。它替代 {@code QuerySource.SUBAGENT.canonical()}（{@code agent:subagent}
     * 聚合占位）作为发射侧精确值；枚举 SUBAGENT 保留仅作守卫类别（autocompact 递归守卫 /
     * persist gate / 529 / main-thread）。接线路径：SubagentExecutor.resolveQuerySource
     * （:3560）→ AgentRunOptions.querySource（:3522）→ withQuerySourceValue（已接线：
     * SubagentExecutor:3995 withQuerySourceValue 注入，对齐 CC promptCategory.ts:16-28 → AgentTool.tsx:609）。
     *
     * @return CC 对齐的 querySource 字符串：builtin → {@code agent:builtin:<agentType>}（type 空 →
     *         {@code agent:default}）；custom/plugin → {@code agent:custom}
     */
    default String querySourceForAgent() {
        return PromptCategory.getQuerySourceForAgent(agentType(), isBuiltIn(this));
    }
}
