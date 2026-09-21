package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.agent.LlmAgentLoop;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolResultBlockParam;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-H3 · BriefTool 重写 SendUserMessage 契约验证（组 4-2 拍板）。
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * 旧 BriefTool 返回 <b>session 快照</b>（session_id/plan_mode/…），与 CC SendUserMessage
 * 「给用户发消息」完全错位（M-H2-5 最高优先差异）。本测试验证重写后：
 * <ol>
 *   <li><b>工具名 = SendUserMessage + alias Brief</b>（CC BRIEF_TOOL_NAME/LEGACY_BRIEF_TOOL_NAME，
 *       prompt.ts:1-2）—— 对齐后 LLM 调 SendUserMessage，历史 transcript 老名 Brief 走 alias 回退。</li>
 *   <li><b>execute 消息投递</b>（CC call BriefTool.ts:186-203）—— 返回 {message, sentAt}，
 *       attachments 存在时 {message, attachments:[{path,size,isImage}], sentAt}，<b>不再返回 session 快照</b>。</li>
 *   <li><b>validateInput 附件路径校验</b>（CC validateAttachmentPaths，BriefTool.ts:163-168）——
 *       不存在路径 → errorCode 1。</li>
 *   <li><b>mapToToolResultBlockParam 投递确认</b>（CC BriefTool.ts:175-183）——
 *       'Message delivered to user.' + '(n attachment(s) included)' 后缀。</li>
 *   <li><b>只读/并发/分类契约</b>：isConcurrencySafe/isReadOnly = true，userFacingName = ''，
 *       toAutoClassifierInput = message（BriefTool.ts:143-162）。</li>
 * </ol>
 */
@DisplayName("IMP-H3 · BriefTool 重写 SendUserMessage 契约（组 4-2）")
class BriefToolTest {

    private final BriefTool tool = new BriefTool();

    private static ToolUseBlock call(JsonNode input) {
        return new ToolUseBlock("brief-1", BriefTool.NAME, input);
    }

    private static ToolUseContext ctx() {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    }

    private static ObjectNode input(String message, List<String> attachments, String status) {
        ObjectNode in = JsonNodeFactory.instance.objectNode();
        in.put("message", message);
        if (attachments != null && !attachments.isEmpty()) {
            ArrayNode arr = in.putArray("attachments");
            attachments.forEach(arr::add);
        }
        if (status != null) {
            in.put("status", status);
        }
        return in;
    }

    @Test
    @DisplayName("工具名 = SendUserMessage + alias Brief（CC BRIEF_TOOL_NAME/LEGACY_BRIEF_TOOL_NAME）")
    void nameAndAliasesMatchCc() {
        // WHY: CC BriefTool.ts:137-138 name=BRIEF_TOOL_NAME('SendUserMessage'), aliases=[LEGACY_BRIEF_TOOL_NAME('Brief')]
        assertThat(tool.name()).as("CC BRIEF_TOOL_NAME=SendUserMessage").isEqualTo("SendUserMessage");
        assertThat(tool.aliases()).as("CC LEGACY_BRIEF_TOOL_NAME=Brief alias").containsExactly("Brief");
    }

    @Test
    @DisplayName("inputSchema: message 必填 + attachments 可选数组 + status normal/proactive 枚举（strictObject）")
    void inputSchemaMatchesCc() {
        JsonNode schema = tool.inputSchema();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        // z.strictObject → additionalProperties:false（未知键拒绝，BriefTool.ts:20-37）
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        // message 必填
        JsonNode required = schema.path("required");
        assertThat(required.isArray()).isTrue();
        assertThat(required).extracting(JsonNode::asText).contains("message");
        // attachments 可选数组 string
        JsonNode attachments = schema.path("properties").path("attachments");
        assertThat(attachments.path("type").asText()).isEqualTo("array");
        assertThat(attachments.path("items").path("type").asText()).isEqualTo("string");
        // status 枚举
        JsonNode status = schema.path("properties").path("status");
        assertThat(status.path("type").asText()).isEqualTo("string");
        assertThat(status.path("enum")).extracting(JsonNode::asText)
            .containsExactly("normal", "proactive");
    }

    @Test
    @DisplayName("execute 消息投递: 无附件 → data {message, sentAt}（不再返回 session 快照）")
    void executeWithMessageOnlyDeliversMessage() {
        AgentToolResult<?> r = tool.execute(call(input("你好，任务已完成。", null, "normal")), ctx());

        assertThat(r).isInstanceOf(ToolResult.class);
        Object data = ((ToolResult<?>) r).data();
        assertThat(data).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) data;
        assertThat(m.get("message")).isEqualTo("你好，任务已完成。");
        assertThat(m.get("sentAt")).asString().isNotBlank();
        assertThat(m).doesNotContainKey("session_id");
        assertThat(m).doesNotContainKey("in_plan_mode");
        assertThat(m).doesNotContainKey("attachments");
    }

    @Test
    @DisplayName("execute 消息投递: 有附件 → data {message, attachments:[{path,size,isImage}], sentAt}（复用附件链）")
    void executeWithAttachmentsResolvesViaAttachmentChain(@TempDir Path tempDir) throws Exception {
        Path png = tempDir.resolve("photo.png");
        Files.write(png, new byte[] {1, 2, 3, 4});

        AgentToolResult<?> r = tool.execute(
            call(input("看这张图", List.of(png.toString()), "proactive")), ctx());

        Object data = ((ToolResult<?>) r).data();
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) data;
        assertThat(m.get("message")).isEqualTo("看这张图");
        assertThat(m.get("sentAt")).asString().isNotBlank();
        // 附件链解析：path/size/isImage（BRIDGE_MODE 关 → 本地 stat，无 file_uuid）
        assertThat(m.get("attachments")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> atts = (List<Map<String, Object>>) m.get("attachments");
        assertThat(atts).hasSize(1);
        Map<String, Object> a = atts.get(0);
        assertThat(a.get("path")).isEqualTo(png.toString());
        assertThat(((Number) a.get("size")).longValue()).isEqualTo(4L);
        assertThat(a.get("isImage")).isEqualTo(true);
        assertThat(a).doesNotContainKey("file_uuid");
    }

    @Test
    @DisplayName("validateInput: 无附件通过；不存在路径 → errorCode 1（CC validateAttachmentPaths ENOENT）")
    void validateInputRejectsMissingAttachment(@TempDir Path tempDir) {
        // 无附件 → 通过
        assertThat(tool.validateInput(input("hi", null, null), ctx()).ok()).isTrue();
        // 不存在路径 → errorCode 1
        String missing = tempDir.resolve("missing.png").toString();
        Tool.ValidationResult vr = tool.validateInput(
            input("hi", List.of(missing), null), ctx());
        assertThat(vr.ok()).isFalse();
        assertThat(vr.errorCode()).isEqualTo("1");
        assertThat(vr.message()).contains("does not exist");
    }

    @Test
    @DisplayName("validateInput: 附件为目录 → errorCode 1（CC 'not a regular file'）")
    void validateInputRejectsDirectory(@TempDir Path tempDir) {
        Tool.ValidationResult vr = tool.validateInput(
            input("hi", List.of(tempDir.toString()), null), ctx());
        assertThat(vr.ok()).isFalse();
        assertThat(vr.errorCode()).isEqualTo("1");
        assertThat(vr.message()).contains("not a regular file");
    }

    @Test
    @DisplayName("mapToToolResultBlockParam: 投递确认文案（CC 'Message delivered to user.' + 附件后缀）")
    void mapToToolResultBlockParamConfirmsDelivery(@TempDir Path tempDir) throws Exception {
        AgentToolResult<?> noAtt = tool.execute(call(input("hi", null, null)), ctx());
        ToolResultBlockParam block = tool.mapToToolResultBlockParam(noAtt, "brief-1", false);
        assertThat(block.toolUseId()).isEqualTo("brief-1");
        assertThat(block.type()).isEqualTo("tool_result");
        assertThat(block.content()).isEqualTo("Message delivered to user.");
        assertThat(block.isError()).isFalse();

        // 单附件 → '(1 attachment included)'（CC plural('attachment')：n==1 单数）
        Path one = tempDir.resolve("a.txt");
        Files.write(one, new byte[] {1});
        AgentToolResult<?> oneAtt = tool.execute(
            call(input("hi", List.of(one.toString()), null)), ctx());
        assertThat(((ToolResult<?>) oneAtt).data()).isInstanceOf(Map.class);
        ToolResultBlockParam block1 = tool.mapToToolResultBlockParam(oneAtt, "brief-1", false);
        assertThat(block1.content()).isEqualTo("Message delivered to user. (1 attachment included)");

        // 双附件 → '(2 attachments included)'（复数）
        Path two = tempDir.resolve("b.png");
        Files.write(two, new byte[] {1, 2});
        AgentToolResult<?> twoAtt = tool.execute(
            call(input("hi", List.of(one.toString(), two.toString()), null)), ctx());
        ToolResultBlockParam block2 = tool.mapToToolResultBlockParam(twoAtt, "brief-1", false);
        assertThat(block2.content()).isEqualTo("Message delivered to user. (2 attachments included)");
    }

    @Test
    @DisplayName("execute: 附件 TOCTOU 缺失 → ToolResult.error（CC stat 抛错让模型看到，Java 错误不抛约定）")
    void executeWithMissingAttachmentReturnsError(@TempDir Path tempDir) {
        String missing = tempDir.resolve("gone.png").toString();
        AgentToolResult<?> r = tool.execute(call(input("hi", List.of(missing), null)), ctx());
        assertThat(r).isInstanceOf(ToolResult.class);
        Object data = ((ToolResult<?>) r).data();
        assertThat(data).asString().contains("Attachment resolution failed");
        assertThat(data).asString().contains("gone.png");
    }

    @Test
    @DisplayName("[F2] 附件 TOCTOU 失败必须被识别为错误：模型面/前端都不得静默成功")
    void attachmentFailureIsRecognizedAsErrorNotSilentSuccess(@TempDir Path tempDir) {
        // WHY（两条独立受害面）：失败若不进 is_error 判定，则 ①**模型面**收到
        //   「Message delivered to user.」——模型以为附件已送达，继续基于不存在的附件作答；
        //   ②前端 tool_calls.is_error=false → 失败被渲染成投递成功（正文照出、附件退化成 [file] 路径）。
        //   CC 对应形态（错误必须显式失败）：toolExecution.ts:1722 is_error:true + :1726
        //   toolUseResult 加 "Error: " 前缀；Java 侧「正常返回 ToolResult.error」的唯一 isError 推导
        //   入口是 LlmAgentLoop.isToolErrorData（前缀白名单）⇒ 错误串必须带 "Error: " 前缀。
        String missing = tempDir.resolve("gone.png").toString();
        AgentToolResult<?> r = tool.execute(call(input("hi", List.of(missing), null)), ctx());
        Object data = ((ToolResult<?>) r).data();

        // 判据 1：失败 data 命中 isToolErrorData（未命中 ⇒ 执行器 t.isError=false ⇒ 双 silent success）
        assertThat(LlmAgentLoop.isToolErrorData(data))
            .as("失败 data 必须被 isToolErrorData 识别为错误（否则落回成功分支）")
            .isTrue();

        // 判据 2：isError=true 时 mapper 透传错误消息，绝不回投递成功文案
        ToolResultBlockParam errBlock = tool.mapToToolResultBlockParam(r, "brief-1", true);
        assertThat(errBlock.isError()).isTrue();
        assertThat((String) errBlock.content())
            .as("模型面必须是错误消息，不得是投递成功文案")
            .contains("Attachment resolution failed")
            .doesNotContain("Message delivered to user.");
    }

    @Test
    @DisplayName("模型面 tool_result 文本恒为人类文案、不含结构化 JSON（数据/文本分离防线）")
    void modelFacingTextNeverLeaksStructuredPayload(@TempDir Path tempDir) throws Exception {
        // WHY: CC 把「数据」(output {message,attachments,sentAt}) 与「给模型看的文本」
        // (mapToolResultToToolResultBlockParam → 'Message delivered to user.') 分开
        // (BriefTool.ts:42-63 vs :175-183)。本仓 UI 面载荷改走 tool_calls.result（结构化 JSON）后，
        // 这条断言守住「模型面不许看到 JSON」——否则答案会被塞进 tool_result 里污染/膨胀模型上下文。
        Path p = tempDir.resolve("a.png");
        Files.write(p, new byte[] {1, 2, 3, 4});
        AgentToolResult<?> r = tool.execute(
            call(input("## 验收结果\n全部通过", List.of(p.toString()), "normal")), ctx());

        // 数据侧确实带着答案（对照：不是「两边都没有」的假绿）
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ((ToolResult<?>) r).data();
        assertThat(data.get("message")).isEqualTo("## 验收结果\n全部通过");

        // 模型侧：逐字人类文案，且不含任何结构化 JSON 痕迹
        ToolResultBlockParam block = tool.mapToToolResultBlockParam(r, "brief-1", false);
        assertThat((String) block.content()).isEqualTo("Message delivered to user. (1 attachment included)");
        assertThat((String) block.content())
            .doesNotContain("{").doesNotContain("\"message\"").doesNotContain("sentAt");
    }

    @Test
    @DisplayName("isEnabled 保险开关：默认 true（nexusai.brief.enabled 默认开），置 false 即关闭")
    void isEnabledDefaultsTrueAndIsConfigurable() {
        // WHY: 用户拍板「保险开关默认必须是 true（默认开、可按需关）」；且未走 Spring 注入
        // （测试/直接构造）时也必须为 true —— 否则工具在生产外路径静默消失。
        assertThat(tool.isEnabled()).as("默认开（@Value :true + 字段初值 true）").isTrue();
        tool.briefEnabled = false;
        assertThat(tool.isEnabled()).as("置 false → ToolRegistry isEnabled 过滤 → 工具不暴露").isFalse();
    }

    @Test
    @DisplayName("只读/并发/分类/UI 契约（CC isConcurrencySafe/isReadOnly=true, userFacingName='', toAutoClassifierInput=message）")
    void readOnlyConcurrencyAndClassifierContracts() {
        JsonNode input = input("归类我", null, null);
        assertThat(tool.isConcurrencySafe(input)).as("CC BriefTool.ts:154-156 isConcurrencySafe=true").isTrue();
        assertThat(tool.isReadOnly(input)).as("CC BriefTool.ts:157-159 isReadOnly=true").isTrue();
        assertThat(tool.userFacingName()).as("CC BriefTool.ts:143-145 userFacingName=''").isEmpty();
        assertThat(tool.toAutoClassifierInput(input)).as("CC BriefTool.ts:160-162 toAutoClassifierInput=message")
            .isEqualTo("归类我");
    }

    @Test
    @DisplayName("description/prompt/searchHint/outputSchema 契约（CC DESCRIPTION/BRIEF_TOOL_PROMPT/searchHint/outputSchema）")
    void promptDescriptionSearchHintAndOutputSchema() {
        assertThat(tool.description()).as("CC DESCRIPTION=Send a message to the user").isEqualTo("Send a message to the user");
        assertThat(tool.searchHint()).contains("send a message to the user");
        assertThat(tool.prompt()).contains("`message` supports markdown");

        JsonNode os = tool.outputSchema();
        assertThat(os.path("properties").path("message").path("type").asText()).isEqualTo("string");
        assertThat(os.path("properties").path("sentAt").path("type").asText()).isEqualTo("string");
        assertThat(os.path("properties").path("attachments").path("items").path("properties").path("isImage")
            .path("type").asText()).isEqualTo("boolean");
    }
}
