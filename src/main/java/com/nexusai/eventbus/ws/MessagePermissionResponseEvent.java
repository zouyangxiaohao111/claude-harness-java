package com.nexusai.eventbus.ws;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.eventbus.ws.MessagePermissionRequestEvent;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限响应事件 · 前端 → 服务端 STOMP 发送。
 *
 * <p>destination: {@code /app/sessions/{sessionId}/permission-response}
 * （{@code /app/...} 是 STOMP SEND 前缀，由 {@code @MessageMapping("/sessions/{id}/permission-response")}
 * 映射到 {@link com.nexusai.apis.permission.PermissionController#handlePermissionResponse}）。
 *
 * <h2>触发流程</h2>
 * <p>前端 {@code app.js} 收到 {@link MessagePermissionRequestEvent} 弹窗后，用户点
 * Allow / Deny 按钮 → 前端组装本事件 STOMP SEND → 后端 {@code PermissionController}
 * 调 {@link com.nexusai.application.agent.permission.WebSocketPermissionPrompter#onResponse}
 * 完成对应 {@code CompletableFuture<PermissionResult>} → {@link com.nexusai.application.agent.LlmAgentLoop}
 * 阻塞的方法 {@code prompt()} 返回。
 *
 * <h2>字段</h2>
 * <ul>
 *   <li>{@code requestId} — 关联 {@link MessagePermissionRequestEvent#getRequestId()}
 *       （通常 = {@code ToolUseBlock.id}）</li>
 *   <li>{@code decision} — 用户决策（{@code "allow"} 或 {@code "deny"}，大小写不敏感）</li>
 *   <li>{@code acceptFeedback} — [R32-b9] 用户允许时填写的反馈文本(对齐 CC PermissionAllowDecision.acceptFeedback)</li>
 *   <li>{@code contentBlocks} — [R32-b9] 用户允许时上传的图片/附件块
 *       (对齐 CC permissionDecision.contentBlocks,透传 List<JsonNode> 给后端)</li>
 *   <li>{@code updatedPermissions} — [S16] 用户点击"Always allow / Allow forever"等建议时
 *       回传的已批准权限更新（原始 JSON 数组，CC 判别联合形状）——后端
 *       {@link com.nexusai.application.agent.permission.WebSocketPermissionPrompter#onResponse}
 *       转成 {@code PermissionUpdate} 后 apply + persist（对齐 CC onAllow 第 2 参
 *       {@code permissionUpdates}，interactiveHandler.ts:154-167）</li>
 * </ul>
 *
 * <h2>为什么用字符串 decision 而非 enum</h2>
 * <p>前端是 JS，序列化 enum 麻烦（前端发 {@code "ALLOW"} 还是 {@code "allow"}？
 * 后端反序列化严格程度？）。字符串大小写不敏感 + 后端规范化更稳健。
 *
 * <h2>[R32-b9] 字段扩展与向后兼容</h2>
 * <p>加 {@code acceptFeedback} + {@code contentBlocks} 两个可选字段,使用
 * {@code @JsonInclude(NON_NULL)} 序列化时省略 null 字段。
 * 旧前端(不传新字段)的 STOMP 消息仍可被新后端接收;新前端(传新字段)的
 * 旧后端接收后忽略未知字段(Jackson 默认行为)。两端均向后兼容。
 * [S16] 同法加 {@code updatedPermissions} 可选字段（null 省略；旧前端不传 → 无权限更新）。
 *
 * <h2>[R32-b9-fix · Fix B] contentBlocks 校验</h2>
 * <ul>
 *   <li>{@code @Size(max = 20)} — 限制单次响应的 content block 数量上限(防滥用与畸形负载)</li>
 *   <li>{@code getContentBlocks()} 校验:null 元素 → {@link IllegalArgumentException} (Fail loud,
 *       CLAUDE.md 规则 12);非 ObjectNode → 同样 fail loud</li>
 *   <li>为何不在构造器抛:Jackson 反序列化绕过构造器 — 在 getter 处校验保证运行时访问必安全</li>
 * </ul>
 *
 * @see MessagePermissionRequestEvent
 * @see com.nexusai.apis.permission.PermissionController
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessagePermissionResponseEvent {

    /** [R32-b9-fix] 单次响应的 content block 上限(防滥用 + 畸形负载 fail loud)。 */
    public static final int MAX_CONTENT_BLOCKS = 20;

    private final String requestId;
    private final String decision;
    // [R32-b9] 接受反馈文本 · CC PermissionAllowDecision.acceptFeedback
    private final String acceptFeedback;
    // [R32-b9] 内容块列表 (image 等) · CC permissionDecision.contentBlocks
    @Size(max = MAX_CONTENT_BLOCKS, message = "contentBlocks size exceeds {max}")
    private final List<JsonNode> contentBlocks;
    // [S16] 用户批准的权限更新（原始 JSON 数组，CC 判别联合形状）· CC onAllow 第 2 参 permissionUpdates
    private final List<JsonNode> updatedPermissions;
    // [FIX-E askuser-answers] 用户对 AskUserQuestion 的答案（{questionText: "optionLabel"}，
    //   multi-select 逗号拼接）· CC original: answers (Record<string,string>)
    //   (Open-ClaudeCode/src/tools/AskUserQuestionTool/AskUserQuestionTool.tsx:56，
    //    Open-ClaudeCode/src/components/permissions/AskUserQuestionPermissionRequest/AskUserQuestionPermissionRequest.tsx:398-407)
    private final JsonNode answers;
    // [FIX-E askuser-answers] 每问注解（preview/notes）· CC original: annotations
    //   (AskUserQuestionTool.tsx:57，AskUserQuestionPermissionRequest.tsx:398-407)
    private final JsonNode annotations;

    /**
     * 兼容旧调用:仅 requestId + decision(后端按 null 处理新字段)。
     *
     * <p>WHY 保留 2-arg 构造器:旧测试/旧外部调用直接 new, 不需逐处修改。
     */
    public MessagePermissionResponseEvent(String requestId, String decision) {
        this(requestId, decision, null, null, null, null, null);
    }

    /**
     * [R32-b9] 4 参兼容构造器 · 无 updatedPermissions（旧前端/旧测试语义）。
     *
     * <p>WHY 保留:既有测试/旧调用直接 new 4 参;null updatedPermissions = 未批准规则变更。
     */
    public MessagePermissionResponseEvent(String requestId, String decision,
                                          String acceptFeedback, List<JsonNode> contentBlocks) {
        this(requestId, decision, acceptFeedback, contentBlocks, null, null, null);
    }

    /**
     * [S16] 5 参兼容构造器 · 无 answers/annotations（旧前端/旧测试语义）。
     *
     * <p>WHY 保留:既有 5 参调用方不逐处改;null answers/annotations = 非 AskUserQuestion 工具。
     */
    public MessagePermissionResponseEvent(String requestId, String decision,
                                          String acceptFeedback, List<JsonNode> contentBlocks,
                                          List<JsonNode> updatedPermissions) {
        this(requestId, decision, acceptFeedback, contentBlocks, updatedPermissions, null, null);
    }

    /**
     * [R32-b9-fix] 主构造器 · Jackson 反序列化入口(对齐 STOMP inbound 真实链路)。
     *
     * <p>{@link JsonCreator} + {@link JsonProperty} 让 Jackson 在没有 default constructor 的
     * 情况下也能反序列化本类 —— Spring STOMP {@code MappingJackson2MessageConverter} 反序列化
     * 前端 {@code /app/sessions/{id}/permission-response} 消息依赖此入口。
     *
     * <p>[S16] 新增 {@code updatedPermissions}（原始 JSON 数组）——Jackson 对 sealed interface
     * 的多态反序列化歧义由 {@code WebSocketPermissionPrompter.parseUpdatedPermissions} 显式
     * 判别联合解决（CC PermissionUpdateSchema 同款 type 判别）。
     *
     * <p>[FIX-E askuser-answers] 新增 {@code answers}/{@code annotations}（JsonNode 对象）——
     * AskUserQuestion 答案收集通道（CC answers Record<string,string> + annotations）。
     *
     * <p>WHY 不依赖 default constructor: 本类是 final-field 不可变 DTO,加 default ctor 会破坏
     * 不变性;{@code @JsonCreator} 是惯用解决方案。
     */
    @JsonCreator
    public MessagePermissionResponseEvent(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("decision") String decision,
            @JsonProperty("acceptFeedback") String acceptFeedback,
            @JsonProperty("contentBlocks") List<JsonNode> contentBlocks,
            @JsonProperty("updatedPermissions") List<JsonNode> updatedPermissions,
            @JsonProperty("answers") JsonNode answers,
            @JsonProperty("annotations") JsonNode annotations) {
        this.requestId = requestId;
        this.decision = decision;
        this.acceptFeedback = acceptFeedback;
        this.contentBlocks = contentBlocks;
        this.updatedPermissions = updatedPermissions;
        this.answers = answers;
        this.annotations = annotations;
    }

    public String getRequestId() { return requestId; }
    public String getDecision() { return decision; }
    /** [R32-b9] 用户允许时填写的反馈文本(可空)。 */
    public String getAcceptFeedback() { return acceptFeedback; }
    /** [S16] 用户批准的权限更新（原始 JSON 数组；可为 null = 未批准规则变更）。 */
    public List<JsonNode> getUpdatedPermissions() { return updatedPermissions; }
    /**
     * [FIX-E askuser-answers] 用户对 AskUserQuestion 的答案（可为 null = 非 AskUserQuestion 工具）。
     *
     * <p>CC original: {@code answers}（Record&lt;string,string&gt;，AskUserQuestionTool.tsx:56，
     * 由权限组件 AskUserQuestionPermissionRequest.tsx:398-407 submitAnswers 注入 updatedInput）。
     * Java 端由 PermissionController 透传 → WebSocketPermissionPrompter 合并进 Allow.updatedInput。
     */
    public JsonNode getAnswers() { return answers; }
    /**
     * [FIX-E askuser-answers] 每问注解（preview/notes；可为 null）。
     *
     * <p>CC original: {@code annotations}（AskUserQuestionTool.tsx:57，annotationsSchema）。
     */
    public JsonNode getAnnotations() { return annotations; }
    /**
     * [R32-b9] 用户允许时上传的内容块(可空)。
     *
     * <p>[R32-b9-fix · Fix B] 运行时校验:
     * <ul>
     *   <li>列表 size ≤ {@link #MAX_CONTENT_BLOCKS} — 超限 fail loud</li>
     *   <li>每个 element 必须是非 null 的 JsonNode(实际 Jackson 反序列化产生 ObjectNode) —
     *       null 元素 fail loud(避免 Provider 序列化时 NPE)</li>
     * </ul>
     * 校验在 getter 触发(Jackson 反序列化绕过构造器,getter 访问是运行时必经路径)。
     */
    public List<JsonNode> getContentBlocks() {
        if (contentBlocks == null) return null;
        if (contentBlocks.size() > MAX_CONTENT_BLOCKS) {
            throw new IllegalArgumentException(
                "contentBlocks size " + contentBlocks.size()
                    + " exceeds limit " + MAX_CONTENT_BLOCKS);
        }
        // 校验每个 element(失败 loud — CLAUDE.md 规则 12)
        for (JsonNode node : contentBlocks) {
            if (node == null || node.isNull()) {
                throw new IllegalArgumentException(
                    "contentBlocks contains null element (fail loud · 防止 Provider 序列化 NPE)");
            }
            if (!node.isObject()) {
                throw new IllegalArgumentException(
                    "contentBlocks element must be JSON object (got " + node.getNodeType() + ")");
            }
        }
        // 返回 defensive copy — 防止 caller 修改内部状态
        List<JsonNode> copy = new ArrayList<>(contentBlocks.size());
        copy.addAll(contentBlocks);
        return copy;
    }
}
