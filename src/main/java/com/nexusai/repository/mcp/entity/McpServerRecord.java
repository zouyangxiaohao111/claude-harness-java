package com.nexusai.repository.mcp.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;
import com.nexusai.model.mcp.McpServer;

/**
 * MyBatis-Flex 持久化记录：{@code mcp_servers} 表行。
 *
 * <p>DDD 严格分层：这是 persistence 关注点（带 {@code @Table}），与
 * {@link com.nexusai.model.mcp.McpServer}（domain POJO）通过
 * {@link #toDomain()} 与 {@link #fromDomain(McpServer)} 互转。
 * 应用层（{@code McpServerService}）应只持有 {@link McpServer}，不直接依赖 Record。
 */
@Table("mcp_servers")
public class McpServerRecord {
    @Id private String id;
    private String name;
    private String command;
    private String args;            // JSON array
    private String env;             // JSON object
    private String status;          // 'running'|'stopped'|'error'
    private String lastError;
    private Integer pid;
    private Boolean enabled;
    private String createdAt;
    private String type;            // 'stdio'|'sse'|'sse-ide'|'ws-ide'|'http'|'ws'|'sdk'|'claudeai-proxy'
    private String approvalStatus;  // 'approved'|'rejected'|'pending'
    private String scope;           // 'project'|'user'|'local'|'enterprise'|'dynamic'|'claudeai'（V59，DB 唯一源）

    // ============== domain 互转 ==============

    public McpServer toDomain() {
        McpServer s = new McpServer();
        s.setId(id);
        s.setName(name);
        s.setCommand(command);
        s.setArgs(args);
        s.setEnv(env);
        s.setStatus(status);
        s.setLastError(lastError);
        s.setPid(pid);
        s.setEnabled(enabled);
        s.setCreatedAt(createdAt);
        s.setType(type);
        s.setApprovalStatus(approvalStatus);
        s.setScope(scope);
        return s;
    }

    public static McpServerRecord fromDomain(McpServer s) {
        McpServerRecord r = new McpServerRecord();
        r.setId(s.getId());
        r.setName(s.getName());
        r.setCommand(s.getCommand());
        r.setArgs(s.getArgs());
        r.setEnv(s.getEnv());
        r.setStatus(s.getStatus());
        r.setLastError(s.getLastError());
        r.setPid(s.getPid());
        r.setEnabled(s.getEnabled());
        r.setCreatedAt(s.getCreatedAt());
        r.setType(s.getType());
        r.setApprovalStatus(s.getApprovalStatus());
        r.setScope(s.getScope());
        return r;
    }

    // ============== getters/setters ==============

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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Integer getPid() { return pid; }
    public void setPid(Integer pid) { this.pid = pid; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
}
