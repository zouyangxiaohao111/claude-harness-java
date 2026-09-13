package com.nexusai.domain.provider;

import com.nexusai.infra.exception.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProviderService#validateExtraHeaders} 写侧校验单测（规范 §6.4 / 用户决策 D5）。
 *
 * <p><b>WHY（规则九 · 测试验证意图而非行为）</b>：T2 桩实测证明两个 SDK 的
 * {@code putHeader} 能<b>顶掉</b> SDK 依 {@code .apiKey()} 自动注入的凭据头，且与调用顺序无关
 * —— 也就是说「写侧拒绝」是<b>唯一防线</b>，没有任何第二道防线。本测试锁住这条防线：
 * 少拒一个敏感头名、少拒一类毒值，用户就能静默劫持自己的凭据 / 注入 header。
 *
 * <p><b>异常类型说明（与计划示例代码的有意偏离）</b>：计划示例断言
 * {@code IllegalArgumentException}，本实现抛 {@link ValidationException}。原因是<b>实测</b>：
 * 本仓 {@code IllegalArgumentException} 没有任何全局处理分支（{@code GlobalExceptionHandler}
 * 只映射 NotFound/Forbidden/Conflict/Validation/BadGateway 等，IAE 落
 * {@code @ExceptionHandler(Exception.class)} → <b>HTTP 500</b>），而 {@code ProviderService}
 * 既有的写侧校验（{@code validateProviderName} 的 '/' 规则）走的是 {@code ValidationException}
 * → 400，且其 javadoc 明文写着「GlobalExceptionHandler → 400」。规范 §7 要求「写侧 400」，
 * 故照既有做法办（不新增异常处理范式）。改动经任务书 4.3「先 grep 现有做法…照它办」授权。
 */
class ProviderHeaderValidationTest {

    @Test
    @DisplayName("敏感头被拒，且消息指明是哪个头")
    void forbiddenCredentialHeaderRejectedAndNamed() {
        Map<String, String> h = Map.of("x-ok", "1", "Authorization", "Bearer x");
        ValidationException ex = assertThrows(ValidationException.class,
            () -> ProviderService.validateExtraHeaders(h));
        assertTrue(ex.getMessage().contains("Authorization"),
            "消息应指明违规头名（否则用户不知道改哪一行）: " + ex.getMessage());
    }

    @Test
    @DisplayName("报文完整性头被拒")
    void forbiddenIntegrityHeadersRejected() {
        for (String n : new String[]{"content-length", "host", "transfer-encoding", "content-type",
                // expect：JDK 受限头（HttpRequest.Builder.header 对其抛 IllegalArgumentException）。
                // 危害路径见 DynamicHeaderExpander 清单 javadoc：会被「测试连接」吞成误导性失败。
                "expect"}) {
            assertThrows(ValidationException.class,
                () -> ProviderService.validateExtraHeaders(Map.of(n, "x")), "应被拒: " + n);
        }
    }

    @Test
    @DisplayName("协议类头放行（负向守护：别把清单开太大）")
    void protocolHeadersAllowed() {
        assertDoesNotThrow(() -> ProviderService.validateExtraHeaders(Map.of(
            "anthropic-version", "2023-06-01", "anthropic-beta", "x", "accept", "application/json")));
    }

    @Test
    @DisplayName("非法名与含换行的值被拒")
    void invalidNameAndNewlineValueRejected() {
        assertThrows(ValidationException.class,
            () -> ProviderService.validateExtraHeaders(Map.of("a b", "1")),
            "含空格的 header 名不是 RFC 7230 token");
        assertThrows(ValidationException.class,
            () -> ProviderService.validateExtraHeaders(Map.of("x", "a\r\nX-Evil: 1")),
            "含 CRLF 的值会 header 注入");
    }

    // 【2026-09-12 质量审查后补】null key / null value 必须在写侧被拒（→400），
    // 否则会落到「序列化静默存 null 值」那一层（规范 §6.5 的三层处置：①拒 ②跳过+warn ③跳过+warn）。
    // 实测升级记录：Jackson 对 null key 会抛「Null key for a Map not allowed in JSON」→ 被 catch
    // → 返回 null ⇒ 一个 null key 会让整组 header 全部丢失（不是只丢那一条）。
    // 用 HashMap，Map.of 不允许 null。
    @Test
    @DisplayName("null 键或 null 值被拒")
    void nullKeyOrNullValueRejected() {
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("x-ok", null);
        assertThrows(ValidationException.class,
            () -> ProviderService.validateExtraHeaders(nullValue), "null 值应被拒");

        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "v");
        assertThrows(ValidationException.class,
            () -> ProviderService.validateExtraHeaders(nullKey), "null 键应被拒");
    }

    @Test
    @DisplayName("null 与空 Map 放行")
    void nullAndEmptyMapAllowed() {
        assertDoesNotThrow(() -> ProviderService.validateExtraHeaders(null));
        assertDoesNotThrow(() -> ProviderService.validateExtraHeaders(Map.of()));
    }

    @Test
    @DisplayName("典型 opencode 配置放行")
    void typicalOpencodeConfigAllowed() {
        assertDoesNotThrow(() -> ProviderService.validateExtraHeaders(Map.of(
            "x-opencode-session", "${session_id}", "x-opencode-client", "nexusai")));
    }

    // 【2026-09-12 规范审查后补】占位符拼写守卫（fail loud）
    // 占位符 token 大小写敏感，写错会静默当字面量发出去 —— 症状是「又 400 了」且毫无线索。

    @Test
    @DisplayName("占位符拼写错误被拒，且消息给出正确写法")
    void malformedPlaceholderRejectedWithCorrectSpellingInMessage() {
        for (String bad : new String[]{"${SESSION_ID}", "${Session_Id}", "${session-id}", "${session_id }"}) {
            ValidationException ex = assertThrows(ValidationException.class,
                () -> ProviderService.validateExtraHeaders(Map.of("x-opencode-session", bad)),
                "应被拒: " + bad);
            assertTrue(ex.getMessage().contains("${session_id}"),
                "消息必须给出正确写法，否则用户无从修: " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("精确占位符与无占位符的值都放行")
    void exactPlaceholderAndPlainValueAllowed() {
        assertDoesNotThrow(() -> ProviderService.validateExtraHeaders(Map.of("x", "${session_id}")));
        assertDoesNotThrow(() -> ProviderService.validateExtraHeaders(Map.of("x", "nexusai-session")));
        assertDoesNotThrow(() -> ProviderService.validateExtraHeaders(Map.of("x", "a${session_id}b")));
    }
}
