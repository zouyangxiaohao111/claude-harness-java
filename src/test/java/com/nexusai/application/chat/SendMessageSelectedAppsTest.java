package com.nexusai.application.chat;

import com.nexusai.application.agent.tool.SessionToolVisibility;
import com.nexusai.model.session.dto.SendMessageRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [app-selection] 请求体 {@code selectedApps} 的接线：DTO 三态 + 并入 requestExtras。
 *
 * <p>每轮语义（唯一真相源 = 请求体）：null = 未指定（不裁剪）；[] = 显式全不选；
 * 非空 = allowlist。ChatService 侧空列表必须原样写入（⛔ 不能被判空丢掉）。
 */
@DisplayName("app-selection · SendMessageRequest.selectedApps 接线（per-turn）")
class SendMessageSelectedAppsTest {

    @Test
    @DisplayName("旧 9 参 / 10 参构造器：selectedApps 缺省为 null（既有调用点零改动）")
    void legacyConstructorsDefaultToNull() {
        SendMessageRequest nine = new SendMessageRequest(
            "hi", null, null, null, null, null, null, null, null);
        assertThat(nine.selectedApps()).isNull();

        SendMessageRequest ten = new SendMessageRequest(
            "hi", null, null, null, null, null, null, null, null, Map.of("kbScope", "x"));
        assertThat(ten.selectedApps()).isNull();
        assertThat(ten.requestExtras()).containsKey("kbScope");
    }

    @Test
    @DisplayName("Jackson：缺省 vs [] 两条路径（契约三态）")
    void jacksonDistinguishesMissingFromEmpty() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om =
            new com.fasterxml.jackson.databind.ObjectMapper();

        SendMessageRequest missing = om.readValue("{\"content\":\"hi\"}", SendMessageRequest.class);
        assertThat(missing.selectedApps()).isNull();

        SendMessageRequest empty = om.readValue(
            "{\"content\":\"hi\",\"selectedApps\":[]}", SendMessageRequest.class);
        assertThat(empty.selectedApps()).isEmpty();

        SendMessageRequest listed = om.readValue(
            "{\"content\":\"hi\",\"selectedApps\":[\"A-app\"]}", SendMessageRequest.class);
        assertThat(listed.selectedApps()).containsExactly("A-app");
    }

    @Test
    @DisplayName("null：requestExtras 原样返回（零行为变化）")
    void withSelectedApps_nullKeepsExtras() {
        Map<String, Object> extras = Map.of("kbScope", "x");
        assertThat(ChatService.withSelectedApps(extras, null)).isSameAs(extras);
        assertThat(ChatService.withSelectedApps(null, null)).isNull();
    }

    @Test
    @DisplayName("非 null（含空列表）：写入 selectedApps 键，原有键保留")
    void withSelectedApps_writesKey() {
        Map<String, Object> merged = ChatService.withSelectedApps(
            Map.of("kbScope", "x"), List.of("A-app", "B-app"));
        assertThat(merged).containsKey("kbScope");
        assertThat(merged.get(SessionToolVisibility.SELECTED_APPS_KEY))
            .isEqualTo(List.of("A-app", "B-app"));

        Map<String, Object> empty = ChatService.withSelectedApps(null, List.of());
        assertThat(empty.get(SessionToolVisibility.SELECTED_APPS_KEY)).isEqualTo(List.of());
    }

    @Test
    @DisplayName("客户端脏值（null / 空白）：清洗后再写入，且不抛 NPE")
    void withSelectedApps_sanitizes() {
        Map<String, Object> merged = ChatService.withSelectedApps(null,
            Arrays.asList("  A-app ", null, "", "  "));
        assertThat(merged.get(SessionToolVisibility.SELECTED_APPS_KEY))
            .isEqualTo(List.of("A-app"));
    }

    @Test
    @DisplayName("extras 读取：键缺失 ⇒ null（未指定）；空列表 ⇒ 空（显式不选）")
    void selectedAppsFromExtras_threeStates() {
        assertThat(SessionToolVisibility.selectedAppsFromExtras(Map.of())).isNull();
        assertThat(SessionToolVisibility.selectedAppsFromExtras(null)).isNull();

        Map<String, Object> selected = ChatService.withSelectedApps(null, List.of("A-app"));
        assertThat(SessionToolVisibility.selectedAppsFromExtras(selected))
            .containsExactly("A-app");

        Map<String, Object> none = ChatService.withSelectedApps(null, List.of());
        assertThat(SessionToolVisibility.selectedAppsFromExtras(none)).isEmpty();
    }
}
