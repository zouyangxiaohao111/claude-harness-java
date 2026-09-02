package com.nexusai.application.agent.tool.impl.stub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.eventbus.ws.SendUserFileEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * SendUserFile Tool 真实现 · 对齐 CC {@code Open-ClaudeCode/src/tools/SendUserFileTool/SendUserFileTool.ts}
 * （G32① 按真源重写，G11 改名 PascalCase；本批补全"文件发送给前端"真交付）。
 *
 * <p><b>WHY（G32① 真行为）</b>: CC 真源已就位（SendUserFileTool.ts，131 行）——给用户设备发文件
 * （KAIROS assistant mode），输出 {@code {sent, file_path, size?, file_uuid?, error?}}。
 * 原 fail-loud 注册桩（WFI-R1）替换为真实现。
 *
 * <p><b>门控语义（用户拍板 2026-08-23 改：配置门控默认关闭，暂不接 claude.ai、计划接微信）</b>:
 * <ul>
 *   <li><b>注册门控</b>: {@code @ConditionalOnProperty(nexusai.mcp.features.send-user-file-tool)}——默认
 *       {@code false} 不注册（对齐 CC {@code feature('KAIROS')} 关时工具为 null；暂不接 claude.ai bridge、
 *       后续接微信时开启本配置，见 #66 遗留待办）。</li>
 *   <li><b>{@link #isEnabled()}</b>: 读同配置（双保险，对齐 CC {@code isEnabled() = isBridgeEnabled()}
 *       SendUserFileTool.ts:46-48——Java web 端"bridge 等价物"是 STOMP 通道，但当前工具整体关闭）。</li>
 * </ul>
 *
 * <p><b>交付语义（Java 等价）</b>: CC stat 校验后经 {@code uploadBriefAttachment} 上传
 * {@code /api/oauth/file_upload} 拿 {@code file_uuid}（SendUserFileTool.ts:101-116）；Java web 端
 * 文件就在后端服务器，故"发送给用户"= 经 {@code SimpMessagingTemplate} 推送
 * {@link SendUserFileEvent} 到 {@code /topic/sessions/{sess-xxx}}。推送成功 → {@code sent:true}；
 * 推送异常 → {@code sent:false + error}（对齐 CC bridge 上传失败 :125-127）；无 STOMP 通道
 * （测试直构 / topic 不可反解）→ 对齐 CC 无 bridge {@code delivered=true}（本地路径可用, :118）
 * 返回 {@code sent:true} 并 warn 日志。
 *
 * <p><b>受控残留 / 差异登记</b>:
 * <ul>
 *   <li>CC bridge 上传拿 {@code file_uuid} 供 web viewer 下载 —— Java 无 Remote Control bridge 客户端，
 *       改用 STOMP 推送文件元数据（{@code file_path + size + description}）；前端读取实际字节经
 *       {@code SendUserFileController#download} 文件服务端点（{@code GET /api/v1/sessions/{sess-xxx}
 *       /send-user-file/download?path=filePath}，Q3 返工 2026-08-23 接线）。事件消费分支（前端
 *       {@code send_user_file} consumer）属待前端对接项，已登记 待前端对接.md。</li>
 *   <li>CC 注册点: Open-ClaudeCode/src/tools.ts:239；Java 注册经 ToolRegistry 收集 {@code Tool} bean
 *       （ToolRegistry.java:157 位次，不再受 kairos 条件约束）。</li>
 * </ul>
 *
 * <p>CC 真源: {@code Open-ClaudeCode/src/tools/SendUserFileTool/SendUserFileTool.ts}（prompt.ts:1
 * {@code SEND_USER_FILE_TOOL_NAME='SendUserFile'}；upload 语义见 {@code tools/BriefTool/upload.ts}）。
 */
@Component
@ConditionalOnProperty(name = "nexusai.mcp.features.send-user-file-tool", havingValue = "true", matchIfMissing = false)
public class SendUserFileTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SendUserFileTool.class);

    /** CC 工具名 · {@code SendUserFileTool/prompt.ts} SEND_USER_FILE_TOOL_NAME='SendUserFile'。 */
    public static final String NAME = ToolNameConstants.SEND_USER_FILE_TOOL_NAME;

    /** CC original: maxResultSizeChars=5_000（SendUserFileTool.ts:27）。 */
    private static final long MAX_RESULT_SIZE_CHARS = 5_000L;

    /**
     * STOMP 模板 · 交付通道（Java 等价 CC bridge）。{@code required=false}：生产 Spring 注入；
     * 测试直接构造（new SendUserFileTool()）→ null → 走 CC 无 bridge delivered=true 语义
     * （对齐 SkillImprovementHook.setWsTemplate 注入模式）。
     */
    @Autowired(required = false)
    private SimpMessagingTemplate wsTemplate;

    /** 测试用注入点 · 与 {@code SkillImprovementHook.setWsTemplate} 同模式（package-private）。 */
    void setWsTemplate(SimpMessagingTemplate wsTemplate) {
        this.wsTemplate = wsTemplate;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Send a file to the user (KAIROS assistant mode)";
    }

    /** 搜索提示 · 对齐 CC SendUserFileTool.ts:26 searchHint。 */
    @Override
    public String searchHint() {
        return "send file to user mobile device upload share";
    }

    @Override
    public long maxResultSizeChars() {
        return MAX_RESULT_SIZE_CHARS;
    }

    /** 配置门控 · {@code nexusai.mcp.features.send-user-file-tool}（默认 false 关闭，对齐 CC isBridgeEnabled）。 */
    @Value("${nexusai.mcp.features.send-user-file-tool:false}")
    private boolean sendUserFileEnabled;

    /**
     * 是否启用 · 读配置 {@code nexusai.mcp.features.send-user-file-tool}（默认 false 关闭，用户拍板
     * 2026-08-23 改：暂不接 claude.ai、计划接微信 → 关闭本工具）。
     *
     * <p>CC {@code isEnabled() = isBridgeEnabled()}（SendUserFileTool.ts:46-48，BRIDGE_MODE +
     * claude.ai 订阅者）；Java web 端 bridge 等价物 = 面向前端的 STOMP 通道，但当前工具整体关闭
     * （配置门控 + 注册层 @ConditionalOnProperty 双保险，开启时 ToolRegistry isEnabled 过滤才暴露）。
     */
    @Override
    public boolean isEnabled() {
        return sendUserFileEnabled;
    }

    /** 可并发 · 对齐 CC SendUserFileTool.ts:49-51 isConcurrencySafe() → true。 */
    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    /** 只读 · 对齐 CC SendUserFileTool.ts:52-54 isReadOnly() → true。 */
    @Override
    public boolean isReadOnly(JsonNode input) {
        return true;
    }

    /** 用户可见名 · 对齐 CC SendUserFileTool.ts:56-58 userFacingName() → 'SendFile'。 */
    @Override
    public String userFacingName() {
        return "SendFile";
    }

    /** 工具提示词 · 对齐 CC SendUserFileTool.ts:38-44 prompt()（逐字）。 */
    @Override
    public String prompt() {
        return """
                Send a file to the user's device. Use this in assistant mode when the user requests a file or when a file is relevant to the conversation.

                Guidelines:
                - Use absolute paths
                - The file must exist and be readable
                - Large files may take time to transfer""";
    }

    /** 工具使用消息渲染 · 对齐 CC SendUserFileTool.ts:60-62（file_path 缺省 '...'）。 */
    @Override
    public String renderToolUseMessage(JsonNode input) {
        String filePath = input != null && input.has("file_path")
                ? input.get("file_path").asText() : "...";
        return "Send file: " + filePath;
    }

    /** 输入 schema · 对齐 CC SendUserFileTool.ts:8-18 {@code z.strictObject({file_path, description?})}。 */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode filePath = props.putObject("file_path");
        filePath.put("type", "string");
        filePath.put("description", "Absolute path to the file to send to the user.");

        ObjectNode description = props.putObject("description");
        description.put("type", "string");
        description.put("description", "Optional description of the file being sent.");

        schema.putArray("required").add("file_path");
        schema.put("additionalProperties", false);
        return schema;
    }

    /**
     * 结果块渲染 · 对齐 CC SendUserFileTool.ts:64-75 mapToolResultToToolResultBlockParam：
     * sent → 'File sent: {path}'；否则 'Failed to send file: {path}'。
     */
    @Override
    public ToolResultBlockParam mapToToolResultBlockParam(
            AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (isError) {
            String msg = result instanceof ToolResult<?> tr
                    ? ToolResult.renderToolResultPayloadText(tr) : "Failed to send file.";
            return new ToolResultBlockParam(toolUseId, "tool_result", msg, true);
        }
        boolean sent = false;
        String filePath = "";
        if (result != null && result.data() instanceof String s) {
            try {
                JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
                sent = node.path("sent").asBoolean(false);
                filePath = node.path("file_path").asText("");
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("[SendUserFile] 结果解析失败: {}", e.toString());
                }
            }
        }
        String content = sent
                ? "File sent: " + filePath
                : "Failed to send file: " + filePath;
        return new ToolResultBlockParam(toolUseId, "tool_result", content, false);
    }

    @Override
    public AgentToolResult<?> execute(ToolUseBlock call) {
        return execute(call, null);
    }

    /**
     * CC SendUserFileTool.ts:77-130 call — stat 校验 + 交付（Java = STOMP 推送文件事件）。
     *
     * <p>文件不存在/不可读 → {@code {sent:false, file_path, error:...}}（CC :84-99）；
     * 目录/非常规文件 → {@code {sent:false, file_path, error:'Path is not a file.'}}（CC :85-89）。
     * 校验通过后交付（{@link #deliver}）：推送成功/无通道 → {@code {sent:true, file_path, size}}；
     * 推送异常 → {@code {sent:false, file_path, size, error}}（CC bridge 上传失败 :125-127 等价）。
     */
    @Override
    public AgentToolResult<?> execute(ToolUseBlock call, ToolUseContext ctx) {
        JsonNode input = call.input();
        String filePath = input != null && input.has("file_path")
                ? input.get("file_path").asText() : null;
        String description = input != null && input.has("description")
                ? input.get("description").asText(null) : null;
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("file_path", filePath == null ? "" : filePath);

        if (filePath == null || filePath.isBlank()) {
            out.put("sent", false);
            out.put("error", "missing required input: file_path");
            return ToolResult.success(call.id(), out.toString());
        }

        long fileSize;
        try {
            Path p = Path.of(filePath);
            // CC 用 fs.stat(file_path)（SendUserFileTool.ts:83-90）：不存在/不可读 → 抛错 →
            // "File does not exist or is not readable."；存在但不是文件（目录）→ isFile()=false →
            // "Path is not a file."。Java Files.isRegularFile 对不存在路径返回 false 而非抛错，
            // 会错误地落入 "Path is not a file." 分支 —— 故用 readAttributes（不存在时抛
            // NoSuchFileException）精确复刻 CC stat 语义.
            java.nio.file.attribute.BasicFileAttributes attrs =
                    Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
            if (!attrs.isRegularFile()) {
                out.put("sent", false);
                out.put("error", "Path is not a file.");
                return ToolResult.success(call.id(), out.toString());
            }
            fileSize = attrs.size();
        } catch (Exception e) {
            out.put("sent", false);
            out.put("error", "File does not exist or is not readable.");
            if (log.isDebugEnabled()) {
                log.debug("[SendUserFile] stat 失败: path={} err={}", filePath, e.toString());
            }
            return ToolResult.success(call.id(), out.toString());
        }

        // 交付：文件信息经 WebSocket/STOMP 推送前端（Java 等价 CC bridge upload 交付）
        DeliveryResult delivery = deliver(filePath, fileSize, description, ctx);
        out.put("sent", delivery.sent());
        out.put("size", fileSize);
        if (delivery.error() != null) {
            out.put("error", delivery.error());
        }
        if (log.isInfoEnabled()) {
            log.info("[SendUserFile] 执行完成: file_path={} size={} sent={} description={}（CC "
                    + "SendUserFileTool.ts:77-130 call 语义）",
                    filePath, fileSize, delivery.sent(), description);
        }
        return ToolResult.success(call.id(), out.toString());
    }

    /**
     * 交付文件事件到前端 · Java 等价 CC {@code SendUserFileTool.ts:101-116} bridge 上传。
     *
     * <p>STOMP 通道可用（wsTemplate 注入 + session topic 可反解）→ 推送 {@link SendUserFileEvent}
     * 到 {@code /topic/sessions/{sess-xxx}}，成功 {@code sent:true}，异常 {@code sent:false+error}
     * （CC bridge 上传失败 :125-127 等价，error='Bridge upload failed. File available at local path.'）；
     * 通道不可用（测试直构 wsTemplate=null / 非生产 session 无法反解 topic）→ 对齐 CC 无 bridge
     * {@code delivered=true}（本地路径可用, :118）返回 {@code sent:true}（服务器路径可用）并 warn。
     */
    private DeliveryResult deliver(String filePath, long fileSize, String description,
                                   ToolUseContext ctx) {
        // [session-id-short] ctx.sessionId() 已 short 恒等直拼 topic 键（sessionTopicKey 复刻删除）
        String topicKey = ctx != null ? ctx.sessionId() : null;
        if (wsTemplate == null || topicKey == null) {
            // CC 无 bridge delivered=true 语义（SendUserFileTool.ts:118）——本地/服务器路径始终可用
            if (log.isWarnEnabled()) {
                log.warn("[SendUserFile] 未推送文件事件: wsTemplate={} topicKey={} —— 按 CC 无 bridge "
                        + "delivered=true 语义返回 sent:true（服务器路径可用, SendUserFileTool.ts:118）",
                        wsTemplate != null, topicKey);
            }
            return new DeliveryResult(true, null);
        }
        String topic = "/topic/sessions/" + topicKey;
        try {
            wsTemplate.convertAndSend(topic,
                    new SendUserFileEvent(ctx.sessionId(), filePath, fileSize, description));
            if (log.isInfoEnabled()) {
                log.info("[SendUserFile] 文件事件已推送前端: topic={} file_path={} size={}",
                        topic, filePath, fileSize);
            }
            return new DeliveryResult(true, null);
        } catch (Exception e) {
            // 对齐 CC bridge 上传失败（SendUserFileTool.ts:125-127 error='Bridge upload failed...'）
            if (log.isWarnEnabled()) {
                log.warn("[SendUserFile] 文件事件推送失败: topic={} file_path={} err={}",
                        topic, filePath, e.toString());
            }
            return new DeliveryResult(false,
                    "File event push failed. File available at server path.");
        }
    }

    /** 交付结果 · sent + 可选 error（error 非 null 时才写入结果 error 字段）。 */
    private record DeliveryResult(boolean sent, String error) {}
}
