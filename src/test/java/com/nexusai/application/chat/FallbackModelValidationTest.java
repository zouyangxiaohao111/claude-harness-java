package com.nexusai.application.chat;

import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.SendMessageRequest;
import com.nexusai.repository.provider.mapper.ModelMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.settings.mapper.SettingsMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [P-26] fallback==main 请求体校验测试 · 对齐 CC main.tsx:1336-1340
 * （read 自验：{@code if (fallbackModel && options.model && fallbackModel === options.model)}
 * → stderr 'Error: Fallback model cannot be the same as the main model...' + exit(1)）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: CC 在 CLI 启动期校验 fallback 模型 ≠ 主模型；
 * Java 按调用传入（SendMessageRequest.fallbackModel），等效校验点落 HTTP 请求体层
 * （决策 P-26：两入口同步 400）。语义面 = CC 短路条件：
 * <ol>
 *   <li><b>双值非空且相等 → 拒绝</b>（fallback==main 会让降级失去意义）。</li>
 *   <li><b>不相等 → 放行</b>。</li>
 *   <li><b>单侧 null/blank → 放行</b>（CC {@code fallbackModel && options.model &&} 短路）。</li>
 * </ol>
 *
 * <p>纯单测：{@code new ChatService()} + {@link ReflectionTestUtils} 注入 mock 的
 * sessionMapper / settingsMapper / modelMapper（其余字段 null，被测方法不触达）。
 * 被测符号为 public {@code validateFallbackModelDistinct}，主模型经 package-visible
 * {@code resolveModelNameForSession} 四层链解析（req.modelName → session override →
 * settings mainModelId → DEFAULT_MODEL）。
 */
@DisplayName("[P-26] ChatService.validateFallbackModelDistinct（fallback==main 同步 400）")
class FallbackModelValidationTest {

    private ChatService newService(SessionMapper sessionMapper) {
        ChatService service = new ChatService();
        ReflectionTestUtils.setField(service, "sessionMapper", sessionMapper);
        ReflectionTestUtils.setField(service, "settingsMapper", mock(SettingsMapper.class));
        ReflectionTestUtils.setField(service, "modelMapper", mock(ModelMapper.class));
        return service;
    }

    private static SendMessageRequest req(String modelName, String fallbackModel) {
        return new SendMessageRequest("hello", modelName, null, java.util.List.of(),
            null, fallbackModel, null, null, null);
    }

    @Test
    @DisplayName("请求体 modelName == fallbackModel → ValidationException（CC 文案）")
    void equalModels_rejected() {
        ChatService service = newService(mock(SessionMapper.class));
        assertThatThrownBy(() -> service.validateFallbackModelDistinct("sess-1",
            req("claude-opus-4-6", "claude-opus-4-6")))
            .as("fallback==main 必须同步拒绝（CC main.tsx:1337-1339 等价）")
            .isInstanceOf(ValidationException.class)
            .hasMessage("Fallback model cannot be the same as the main model. Please specify a different model for --fallback-model.");
    }

    @Test
    @DisplayName("fallbackModel == 会话 override 主模型 → ValidationException（request 无 modelName 时按会话解析）")
    void equalViaSessionOverride_rejected() {
        SessionMapper sessionMapper = mock(SessionMapper.class);
        com.nexusai.repository.session.entity.SessionRecord session =
            new com.nexusai.repository.session.entity.SessionRecord();
        session.setId("sess-1");
        session.setModelName("claude-sonnet-4-5-20250929");
        when(sessionMapper.selectOneById("sess-1")).thenReturn(session);

        ChatService service = newService(sessionMapper);
        assertThatThrownBy(() -> service.validateFallbackModelDistinct("sess-1",
            req(null, "claude-sonnet-4-5-20250929")))
            .as("主模型经 resolveModelNameForSession 会话层解析，相等即拒")
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("主模型 != fallbackModel → 放行")
    void differentModels_allowed() {
        ChatService service = newService(mock(SessionMapper.class));
        assertThatCode(() -> service.validateFallbackModelDistinct("sess-1",
            req("claude-opus-4-6", "claude-sonnet-4-5-20250929")))
            .as("不相等必须放行（CC 短路条件不成立）")
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fallbackModel null → 放行（单侧 null）")
    void nullFallback_allowed() {
        ChatService service = newService(mock(SessionMapper.class));
        assertThatCode(() -> service.validateFallbackModelDistinct("sess-1",
            req("claude-opus-4-6", null)))
            .as("CC fallbackModel && ... 短路：null 不校验")
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fallbackModel blank → 放行（单侧空白）")
    void blankFallback_allowed() {
        ChatService service = newService(mock(SessionMapper.class));
        assertThatCode(() -> service.validateFallbackModelDistinct("sess-1",
            req("claude-opus-4-6", "  ")))
            .as("CC fallbackModel 为 truthy 才校验：空白等价缺省")
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("req null → 放行（无请求体无校验）")
    void nullReq_allowed() {
        ChatService service = newService(mock(SessionMapper.class));
        assertThatCode(() -> service.validateFallbackModelDistinct("sess-1", null))
            .doesNotThrowAnyException();
    }
}
