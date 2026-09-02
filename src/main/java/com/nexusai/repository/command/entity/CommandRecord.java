package com.nexusai.repository.command.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;

import java.util.List;

/**
 * MyBatis-Flex 持久化记录：{@code command} 表行 · 对齐 CC command.ts Command 28 个 DB 列
 *
 * <p>DDD 分层：这是 persistence 关注点（带 {@code @Table}），
 * 与 {@link Command}（domain POJO）通过 {@link #toDomain()} 与 {@link #fromDomain(Command)} 互转。
 *
 * <p>表结构来自 CC command.ts:204-205 {@code Command = CommandBase & (PromptCommand | LocalCommand)}，
 * 完整映射 28 列到 SQLite TEXT/INTEGER。
 */
@Table("command")
public class CommandRecord {
    @Id private String id;
    private String name;
    private String description;
    private String aliases;              // JSON array
    private String allowedTools;         // JSON array
    private String model;
    private String source;               // 'builtin'|'user'|'plugin'|'mcp'|'bundled'
    //                                      P2-19 拆分后另含 'project_settings'|'local_settings'|'flag_settings'|'policy_settings'（CommandSource 全枚举 name().toLowerCase()）
    private String context;              // 'inline'|'fork'
    private String agent;
    private Integer userInvocable;
    private Integer disableModelInvocation;
    private String hooks;                // JSON
    private String version;
    private String baseDir;
    private String paths;                // JSON array
    private String content;
    private String contentPath;
    private Integer enabled;
    private Integer builtin;
    private String argumentHint;
    private String whenToUse;
    private Integer isHidden;
    private Integer isSensitive;
    private Integer immediate;
    private String progressMessage;
    private String effort;
    private String kind;

    // ════════════════════════════════════════════════════════════════════════
    // Domain 互转
    // ════════════════════════════════════════════════════════════════════════

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    public Command toDomain() {
        Command c = new Command();
        c.setId(id);
        c.setName(name);
        c.setDescription(description);
        c.setVersion(version);
        c.setSource(CommandSource.fromString(source));
        c.setContext(context);
        c.setAgent(agent);
        c.setModel(model);
        c.setHooks(hooks);
        c.setContent(content);
        c.setContentPath(contentPath);
        c.setBaseDir(baseDir);
        c.setProgressMessage(progressMessage);
        c.setEffort(effort);
        c.setKind(kind);
        // bool → Boolean
        c.setEnabled(toBool(enabled, true));
        c.setBuiltin(toBool(builtin, false));
        c.setUserInvocable(toBool(userInvocable, true));
        c.setDisableModelInvocation(toBool(disableModelInvocation, false));
        c.setIsHidden(toBool(isHidden, false));
        c.setIsSensitive(toBool(isSensitive, false));
        c.setImmediate(toBool(immediate, false));
        // JSON → List
        c.setAliases(parseList(aliases));
        c.setAllowedTools(parseList(allowedTools));
        c.setPaths(parseList(paths));
        c.setArgumentHint(argumentHint);
        c.setWhenToUse(whenToUse);
        return c;
    }

    public static CommandRecord fromDomain(Command c) {
        CommandRecord r = new CommandRecord();
        r.setId(c.getId());
        r.setName(c.getName());
        r.setDescription(c.getDescription());
        r.setVersion(c.getVersion());
        r.setSource(c.getSource() != null ? c.getSource().name().toLowerCase() : "user");
        r.setContext(c.getContext());
        r.setAgent(c.getAgent());
        r.setModel(c.getModel());
        r.setHooks(c.getHooks());
        r.setContent(c.getContent());
        r.setContentPath(c.getContentPath());
        r.setBaseDir(c.getBaseDir());
        r.setProgressMessage(c.getProgressMessage());
        r.setEffort(c.getEffort());
        r.setKind(c.getKind());
        r.setEnabled(toInt(c.getEnabled()));
        r.setBuiltin(toInt(c.getBuiltin()));
        r.setUserInvocable(toInt(c.getUserInvocable()));
        r.setDisableModelInvocation(toInt(c.getDisableModelInvocation()));
        r.setIsHidden(toInt(c.getIsHidden()));
        r.setIsSensitive(toInt(c.getIsSensitive()));
        r.setImmediate(toInt(c.getImmediate()));
        r.setAliases(toJson(c.getAliases()));
        r.setAllowedTools(toJson(c.getAllowedTools()));
        r.setPaths(toJson(c.getPaths()));
        r.setArgumentHint(c.getArgumentHint());
        r.setWhenToUse(c.getWhenToUse());
        return r;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private static Boolean toBool(Integer i, boolean defaultVal) {
        return i == null ? defaultVal : i != 0;
    }

    private static Integer toInt(Boolean b) {
        return b == null ? null : (b ? 1 : 0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, List.class);
        } catch (Exception e) { return null; }
    }

    private static String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(list);
        } catch (Exception e) { return null; }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Getters/Setters
    // ════════════════════════════════════════════════════════════════════════

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAliases() { return aliases; }
    public void setAliases(String aliases) { this.aliases = aliases; }
    public String getAllowedTools() { return allowedTools; }
    public void setAllowedTools(String allowedTools) { this.allowedTools = allowedTools; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }
    public Integer getUserInvocable() { return userInvocable; }
    public void setUserInvocable(Integer userInvocable) { this.userInvocable = userInvocable; }
    public Integer getDisableModelInvocation() { return disableModelInvocation; }
    public void setDisableModelInvocation(Integer disableModelInvocation) { this.disableModelInvocation = disableModelInvocation; }
    public String getHooks() { return hooks; }
    public void setHooks(String hooks) { this.hooks = hooks; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getBaseDir() { return baseDir; }
    public void setBaseDir(String baseDir) { this.baseDir = baseDir; }
    public String getPaths() { return paths; }
    public void setPaths(String paths) { this.paths = paths; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentPath() { return contentPath; }
    public void setContentPath(String contentPath) { this.contentPath = contentPath; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public Integer getBuiltin() { return builtin; }
    public void setBuiltin(Integer builtin) { this.builtin = builtin; }
    public String getArgumentHint() { return argumentHint; }
    public void setArgumentHint(String argumentHint) { this.argumentHint = argumentHint; }
    public String getWhenToUse() { return whenToUse; }
    public void setWhenToUse(String whenToUse) { this.whenToUse = whenToUse; }
    public Integer getIsHidden() { return isHidden; }
    public void setIsHidden(Integer isHidden) { this.isHidden = isHidden; }
    public Integer getIsSensitive() { return isSensitive; }
    public void setIsSensitive(Integer isSensitive) { this.isSensitive = isSensitive; }
    public Integer getImmediate() { return immediate; }
    public void setImmediate(Integer immediate) { this.immediate = immediate; }
    public String getProgressMessage() { return progressMessage; }
    public void setProgressMessage(String progressMessage) { this.progressMessage = progressMessage; }
    public String getEffort() { return effort; }
    public void setEffort(String effort) { this.effort = effort; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
}
