package com.nexusai.model.mcp;

/**
 * Domain entity: McpServer 聚合根。
 *
 * <p>DDD 分层：纯 POJO，无 {@code @Table} 注解，持久化由
 * {@link com.nexusai.model.mcp.persistence.McpServerRecord} 负责。
 */
public class McpServer {
    private String id;
    private String name;
    private String command;
    private String args;            // JSON array
    private String env;             // JSON object
    private String lastError;
    private Integer pid;
    private Boolean enabled;
    /**
     * [V295] 启动自启 · 本仓对 CC 的扩展层（CC 无独立字段，语义是「enabled 即启动即连」）。
     * enabled=总开关（允许连接）；autoStart=应用启动时是否自动连。
     */
    private Boolean autoStart;
    private String createdAt;
    private String type;            // 'stdio'|'sse'|'sse-ide'|'ws-ide'|'http'|'ws'|'sdk'|'claudeai-proxy'
    private String approvalStatus;  // 'approved'|'rejected'|'pending'
    private String scope;           // 'project'|'user'|'local'|'enterprise'|'dynamic'|'claudeai'（DB 唯一源 V59）

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getArgs() { return args; }
    public void setArgs(String args) { this.args = args; }
    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Integer getPid() { return pid; }
    public void setPid(Integer pid) { this.pid = pid; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getAutoStart() { return autoStart; }
    public void setAutoStart(Boolean autoStart) { this.autoStart = autoStart; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
}
