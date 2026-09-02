package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * FIX-E · AskUserQuestion 答案收集通道 · WebSocketPermissionPrompter 合并语义。
 *
 * <p><b>WHY (意图验证)</b>: CC {@code AskUserQuestionPermissionRequest.tsx:398-407}
 * {@code submitAnswers} 把用户答案合并进 {@code updatedInput}（{@code {...input, answers,
 * ...(annotations && {annotations})}}）后经 {@code onAllow(updatedInput)} 透传，成为工具最终执行
 * 输入。Java 无前端组件，合并点迁移到后端 {@link WebSocketPermissionPrompter#onResponse}。验证：
 * <ul>
 *   <li>用户 Allow + answers 后，{@link PermissionResult.Allow#updatedInput()} 同时含
 *       questions / answers / annotations —— 这是 AskUserQuestionTool.execute 成功路径
 *       （不再恒 fail-loud）的必要前提；</li>
 *   <li>修复既有 bug：promptInputs.remove 先于 get 读取导致 originalInput 恒 null（questions
 *       丢失）—— 无 answers 时 updatedInput 仍保留原 questions。</li>
 * </ul>
 *
 * <p>若只改字段透传（PermissionController → prompter）不改合并，本测试仍红 —— 断言的是
 * 合并后的 updatedInput 到达 Allow，而非仅"onResponse 不抛异常"。
 */
class WebSocketPermissionPrompterAskUserAnswersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("7 参 onResponse allow: updatedInput 合并 questions + answers + annotations")
    void allowMergesAnswersIntoUpdatedInput() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5000);

        String requestId = "ask-user-1";
        CompletableFuture<PermissionResult> future = new CompletableFuture<>();
        registerPendingFuture(prompter, requestId, future);

        JsonNode questionsInput = JSON.readTree(
            "{\"questions\":[{\"question\":\"目标平台?\",\"header\":\"平台\","
                + "\"options\":[{\"label\":\"Web\"},{\"label\":\"移动端\"}]}]}");
        registerPromptInput(prompter, requestId, questionsInput);

        JsonNode answers = JSON.readTree("{\"目标平台?\":\"Web\"}");
        JsonNode annotations = JSON.readTree("{\"目标平台?\":{\"notes\":\"选 Web\"}}");

        prompter.onResponse(requestId, "allow", null, null, null, answers, annotations);

        PermissionResult result = future.get();
        assertThat(result).isInstanceOf(PermissionResult.Allow.class);
        PermissionResult.Allow allow = (PermissionResult.Allow) result;
        JsonNode updatedInput = allow.updatedInput();
        assertThat(updatedInput.has("questions")).isTrue();
        assertThat(updatedInput.get("answers").get("目标平台?").asText()).isEqualTo("Web");
        assertThat(updatedInput.get("annotations").get("目标平台?").get("notes").asText())
            .isEqualTo("选 Web");
    }

    @Test
    @DisplayName("2 参 onResponse allow 无 answers: updatedInput 仍保留原 questions（修复 promptInputs bug）")
    void allowWithoutAnswersKeepsOriginalInput() throws Exception {
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WebSocketPermissionPrompter prompter = new WebSocketPermissionPrompter(ws, 5000);

        String requestId = "ask-user-2";
        CompletableFuture<PermissionResult> future = new CompletableFuture<>();
        registerPendingFuture(prompter, requestId, future);

        JsonNode questionsInput = JSON.readTree(
            "{\"questions\":[{\"question\":\"q1?\",\"header\":\"h\","
                + "\"options\":[{\"label\":\"a\"},{\"label\":\"b\"}]}]}");
        registerPromptInput(prompter, requestId, questionsInput);

        prompter.onResponse(requestId, "allow");

        PermissionResult.Allow allow = (PermissionResult.Allow) future.get();
        JsonNode updatedInput = allow.updatedInput();
        // 修复前 originalInput 恒 null → updatedInput 为空对象、questions 丢失；修复后应保留。
        assertThat(updatedInput.has("questions")).isTrue();
        assertThat(updatedInput.has("answers")).isFalse();
    }

    // ─────────── reflection helpers ───────────

    @SuppressWarnings("unchecked")
    private static void registerPendingFuture(WebSocketPermissionPrompter prompter,
                                              String requestId,
                                              CompletableFuture<PermissionResult> future) throws Exception {
        Field pendingField = WebSocketPermissionPrompter.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        Map<String, CompletableFuture<PermissionResult>> pending =
            (Map<String, CompletableFuture<PermissionResult>>) pendingField.get(prompter);
        pending.put(requestId, future);
    }

    @SuppressWarnings("unchecked")
    private static void registerPromptInput(WebSocketPermissionPrompter prompter,
                                            String requestId,
                                            JsonNode input) throws Exception {
        Field inputField = WebSocketPermissionPrompter.class.getDeclaredField("promptInputs");
        inputField.setAccessible(true);
        Map<String, JsonNode> promptInputs = (Map<String, JsonNode>) inputField.get(prompter);
        promptInputs.put(requestId, input);
    }
}
