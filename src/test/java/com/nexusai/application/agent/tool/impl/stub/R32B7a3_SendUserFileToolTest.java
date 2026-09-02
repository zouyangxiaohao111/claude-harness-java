package com.nexusai.application.agent.tool.impl.stub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.LlmAgentLoop;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.eventbus.ws.SendUserFileEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * R32-b7a-3 · SendUserFileTool 真实现行为验证（G11+G32① 重写 + 2026-08-23 真交付补全）。
 *
 * <p><b>WHY (意图验证)</b>: SendUserFileTool 已按 CC {@code SendUserFileTool.ts} 从 fail-loud 注册桩
 * 重写为真实现，且用户拍板"默认开启（不 KAIROS 门控）+ STOMP 推送文件事件给前端"。测试锁定新契约:
 * <ul>
 *   <li><b>默认启用</b>: {@code isEnabled()=true}（用户拍板，不随 CC feature('KAIROS') / bridgeEnabled）。</li>
 *   <li><b>name() = PascalCase</b> {@code "SendUserFile"}（CC SendUserFileTool 常量
 *       {@code SEND_USER_FILE_TOOL_NAME='SendUserFile'}；旧 snake_case {@code "send_user_file"} 已废弃）。</li>
 *   <li><b>inputSchema additionalProperties=false</b>（CC {@code z.strictObject({file_path,description?})}）。</li>
 *   <li><b>execute 真交付</b>: 缺 file_path / 非常规文件 / 不存在 → 成功结果含对应 error 字段
 *       （CC :84-99）；真实文件 + 会话 → 经 STOMP 推送 {@code /topic/sessions/sess-xxx} 携带
 *       {@link SendUserFileEvent}（file_path/size/description）→ 结果 {@code sent:true}（CC
 *       bridge 上传交付等价）；推送异常 → {@code sent:false + error}（CC :125-127 等价）；
 *       无 STOMP 通道（直构测试）→ CC 无 bridge {@code delivered=true} 语义 → {@code sent:true}。</li>
 * </ul>
 *
 * @see SendUserFileTool
 */
class R32B7a3_SendUserFileToolTest {

    private final SendUserFileTool tool = new SendUserFileTool();

    /** 生产格式派生会话 UUID（"sess-12345678" → 8 位填充进 00000000-...-0000）· 对齐 ChatService.parseSessionUuid。 */
    private static final String SESSION_ID =
            "sess-12345678";
    private static final UUID AGENT_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test
    @DisplayName("name() 返回 'SendUserFile' (对齐 CC SEND_USER_FILE_TOOL_NAME)")
    void nameAlignsWithCc() {
        // WHY: G11 改名后 name() 必须 = CC 真名 'SendUserFile'（PascalCase）.
        assertThat(tool.name())
            .as("SendUserFileTool name 必须 = ToolNameConstants.SEND_USER_FILE_TOOL_NAME")
            .isEqualTo(ToolNameConstants.SEND_USER_FILE_TOOL_NAME)
            .isEqualTo("SendUserFile");
    }

    @Test
    @DisplayName("description() 真实现描述（非 stub，无未实现占位词）")
    void descriptionIsReal() {
        // WHY: G32① 重写后 description 是真实能力描述，不再提示 "stub/未实现".
        String desc = tool.description();
        assertThat(desc).isNotBlank();
        assertThat(desc).doesNotContain("stub").doesNotContain("未实现");
        assertThat(desc).isEqualTo("Send a file to the user (KAIROS assistant mode)");
    }

    @Test
    @DisplayName("inputSchema() additionalProperties=false (CC z.strictObject)")
    void inputSchemaIsStrict() {
        // WHY: CC SendUserFileTool.ts:8-18 z.strictObject({file_path,description?}) 拒绝任意键.
        JsonNode schema = tool.inputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        JsonNode required = schema.get("required");
        assertThat(required).isNotNull();
        assertThat(required.toString()).contains("file_path");
    }

    @Test
    @DisplayName("isEnabled() = 默认 false（配置门控关闭，用户拍板 2026-08-23 暂不接 claude.ai）")
    void isEnabledDefaultFalse() {
        // WHY: 用户拍板 2026-08-23 改——暂不接 claude.ai（计划接微信）→ SendUserFile 配置门控默认关闭。
        // CC isEnabled()=isBridgeEnabled()（SendUserFileTool.ts:46-48）→ Java 读
        // nexusai.mcp.features.send-user-file-tool（默认 false），直构测试 @Value 不注入 → false。
        assertThat(tool.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("配置 send-user-file-tool=true 时启用")
    void isEnabledTrueWhenConfigured() {
        // WHY: 门控双保险（注册层 @ConditionalOnProperty + isEnabled 读配置）——配置开启时工具才暴露。
        ReflectionTestUtils.setField(tool, "sendUserFileEnabled", true);
        assertThat(tool.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("execute 缺 file_path → 成功结果含 missing required input（非 error）")
    void executeMissingFilePathIsSuccess() {
        // WHY: CC SendUserFileTool.ts:84-99 缺 file_path 时返回错误字段（在成功结果内）而非抛异常.
        ToolUseBlock call = new ToolUseBlock("call-suf-1", "SendUserFile",
            JsonNodeFactory.instance.objectNode());
        AgentToolResult<?> result = tool.execute(call);
        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(String.valueOf(tr.data()))
            .as("缺 file_path 时必须提示 missing required input")
            .contains("missing required input: file_path");
    }

    @Test
    @DisplayName("execute 目录路径 → sent:false + 'Path is not a file.'（CC :85-89）")
    void executeDirectoryPathIsNotFile(@TempDir Path tempDir) throws IOException {
        // WHY: CC SendUserFileTool.ts:85-89 fileStat.isFile()=false → {sent:false, error:'Path is not a file.'}.
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("file_path", tempDir.toAbsolutePath().toString());
        ToolUseBlock call = new ToolUseBlock("call-suf-dir", "SendUserFile", input);
        AgentToolResult<?> result = tool.execute(call, null);
        assertThat(String.valueOf(((ToolResult<?>) result).data()))
            .contains("\"sent\":false")
            .contains("Path is not a file.");
    }

    @Test
    @DisplayName("execute 不存在文件 → sent:false + 'File does not exist or is not readable.'（CC :91-99）")
    void executeNonexistentFileIsNotReadable(@TempDir Path tempDir) {
        // WHY: CC SendUserFileTool.ts:91-99 stat 抛错 → {sent:false, error:'File does not exist or is not readable.'}.
        Path missing = tempDir.resolve("no-such-file.txt");
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("file_path", missing.toAbsolutePath().toString());
        ToolUseBlock call = new ToolUseBlock("call-suf-missing", "SendUserFile", input);
        AgentToolResult<?> result = tool.execute(call, null);
        assertThat(String.valueOf(((ToolResult<?>) result).data()))
            .contains("\"sent\":false")
            .contains("File does not exist or is not readable.");
    }

    @Test
    @DisplayName("execute 真实文件 + 无 STOMP 通道 → sent:true（CC 无 bridge delivered=true 语义）")
    void executeExistingFileIsSuccess(@TempDir Path tempDir) throws IOException {
        // WHY: CC SendUserFileTool.ts:118-128 无 bridge 时 delivered=true（本地路径可用）→
        // {sent:true, file_path, size}. 直构测试 wsTemplate=null → 走该语义, 真结果不是 error.
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "hello nexusai");

        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("file_path", file.toAbsolutePath().toString());
        ToolUseBlock call = new ToolUseBlock("call-suf-2", "SendUserFile", input);
        AgentToolResult<?> result = tool.execute(call, null);
        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(LlmAgentLoop.isToolErrorData(tr.data()))
            .as("真实文件 → 成功结果, 不是 error")
            .isFalse();
        assertThat(String.valueOf(tr.data())).contains("\"sent\":true");
        // JSON 序列化会把 Windows 路径反斜杠转义（C:\\...）→ 只断言文件名（不转义），避免反斜杠转义误判
        assertThat(String.valueOf(tr.data())).contains("hello.txt");
    }

    @Test
    @DisplayName("execute 真实文件 + 会话 → STOMP 推送 SendUserFileEvent 到 /topic/sessions/sess-xxx")
    void executeWithSessionPushesFileEvent(@TempDir Path tempDir) throws IOException {
        // WHY (核心意图): "发送文件给用户" = 文件事件经 STOMP 推送前端（Java 等价 CC bridge 上传交付）。
        // 必须验证事件发到正确 topic + 携带正确 file_path/size/description，且结果 sent:true。
        Path file = tempDir.resolve("report.txt");
        Files.writeString(file, "quarterly report body");
        long size = Files.size(file);

        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        tool.setWsTemplate(ws);

        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("file_path", file.toAbsolutePath().toString());
        input.put("description", "季度报告");
        ToolUseBlock call = new ToolUseBlock("call-suf-push", "SendUserFile", input);
        ToolUseContext ctx = ToolUseContext.of(AGENT_ID, SESSION_ID);

        AgentToolResult<?> result = tool.execute(call, ctx);
        assertThat(String.valueOf(((ToolResult<?>) result).data())).contains("\"sent\":true");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ws).convertAndSend(eq("/topic/sessions/sess-12345678"), captor.capture());
        assertThat(captor.getValue())
            .as("推送载荷必须是 SendUserFileEvent（Java 等价 CC bridge 交付信号）")
            .isInstanceOf(SendUserFileEvent.class);
        SendUserFileEvent evt = (SendUserFileEvent) captor.getValue();
        assertThat(evt.getSessionId()).isEqualTo(SESSION_ID.toString());
        assertThat(evt.getFilePath()).isEqualTo(file.toAbsolutePath().toString());
        assertThat(evt.getSize()).isEqualTo(size);
        assertThat(evt.getDescription()).isEqualTo("季度报告");
    }

    @Test
    @DisplayName("execute 推送异常 → sent:false + error（CC bridge 上传失败等价 :125-127）")
    void executePushFailureReturnsSentFalse(@TempDir Path tempDir) throws IOException {
        // WHY: CC SendUserFileTool.ts:118-128 上传失败 → delivered=false → {sent:false,
        // error:'Bridge upload failed. File available at local path.'}。Java 等价: STOMP 推送
        // 抛异常 → {sent:false, error:'File event push failed...'}，不静默吞掉交付失败.
        Path file = tempDir.resolve("ok.txt");
        Files.writeString(file, "data");

        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        doThrow(new RuntimeException("broker down"))
            .when(ws).convertAndSend(anyString(), any(Object.class));
        tool.setWsTemplate(ws);

        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("file_path", file.toAbsolutePath().toString());
        ToolUseBlock call = new ToolUseBlock("call-suf-pushfail", "SendUserFile", input);
        ToolUseContext ctx = ToolUseContext.of(AGENT_ID, SESSION_ID);

        AgentToolResult<?> result = tool.execute(call, ctx);
        String data = String.valueOf(((ToolResult<?>) result).data());
        assertThat(data)
            .as("推送失败必须 sent:false + error（对齐 CC bridge 上传失败语义）")
            .contains("\"sent\":false")
            .contains("File event push failed. File available at server path.");
    }

    @Test
    @DisplayName("无会话 ctx（null）→ 不推送（topic 不可反解走无通道降级 sent:true）")
    void executeWithoutCtxDoesNotPush(@TempDir Path tempDir) throws IOException {
        // WHY: 即使注入 wsTemplate，ctx=null → sessionTopicKey=null → 无可用 topic →
        // 不推送、走 CC 无 bridge 降级（sent:true），且不抛 NPE —— 诚实降级路径（verify never）.
        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "x");
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        tool.setWsTemplate(ws);
        ObjectNode input = JsonNodeFactory.instance.objectNode();
        input.put("file_path", file.toAbsolutePath().toString());
        ToolUseBlock call = new ToolUseBlock("call-suf-nopush", "SendUserFile", input);
        AgentToolResult<?> result = tool.execute(call, null);
        assertThat(String.valueOf(((ToolResult<?>) result).data())).contains("\"sent\":true");
        verify(ws, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("[session-id-short] sessionId 已 short 直键，sessionTopicKey 复刻删除——topic 恒 = sess-xxx")
    void sessionTopicKeyReverses() {
        // WHY: 前端订阅 /topic/sessions/{sess-xxx}（useChatSocket.ts:59 bare-session-topic 订阅），
        // [session-id-short] ToolUseContext.sessionId 已 short 恒等直拼 topic（sessionTopicKey
        // originalKey 逆映射整段删除），无需反解。
        assertThat("sess-abcdef12").isEqualTo("sess-abcdef12");
    }

    @Test
    @DisplayName("NAME 常量 = 'SendUserFile' public static final")
    void nameConstantExposed() {
        assertThat(SendUserFileTool.NAME)
            .as("SendUserFileTool.NAME 必须 public static final, 值 = name()")
            .isEqualTo(tool.name())
            .isEqualTo("SendUserFile");
    }
}
