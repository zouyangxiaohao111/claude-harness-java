package com.nexusai.repository.session.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.Table;

@Table("tool_calls")
public class ToolCallRecord {
    @Id private String id;
    private String messageId;
    private String toolName;
    private String arguments;       // JSON
    private String result;
    private Boolean isError;
    private String createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getArguments() { return arguments; }
    public void setArguments(String arguments) { this.arguments = arguments; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public Boolean getIsError() { return isError; }
    public void setIsError(Boolean isError) { this.isError = isError; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}