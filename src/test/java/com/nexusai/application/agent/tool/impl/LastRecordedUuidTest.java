package com.nexusai.application.agent.tool.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S4] lastRecordedUuid parent chain RED-GREEN 双证测试 (P1 差异项 4).
 *
 * <p>规则九 (验证意图): CC {@code runAgent.ts:745} {@code lastRecordedUuid =
 * initialMessages.at(-1)?.uuid ?? null} — 取 initialMessages 末尾消息 uuid 作 transcript parent
 * chain 起点. Java 旧代码 :767-769 {@code UUID.randomUUID()} 随机生成 (注释声称对齐 CC :745
 * 实为撒谎, Pattern #9 典型案例): 随机 uuid 不对应任何已录制消息, resume 时消息链重建错乱.
 *
 * <p>测试方式 (seam 模式): {@link SubagentExecutor#assignInitialMessageUuids(List)} +
 * {@link SubagentExecutor#lastInitialMessageUuid(List)} 是 package-private static seam
 * (executeStreaming Step 19.5 真实调用). RED 依据: 回退 random 实现 → lastInitialMessageUuid
 * 与末尾消息 uuid 不等 (断言红).
 */
@DisplayName("[S4] lastRecordedUuid parent chain (lastInitialMessageUuid seam)")
class LastRecordedUuidTest {

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @Test
    @DisplayName("lastInitialMessageUuid 取末尾消息 uuid (CC runAgent.ts:745 initialMessages.at(-1)?.uuid)")
    void lastInitialMessageUuid_shouldUseLastMessageUuid_notRandom() {
        // WHY: parent chain 连续性 — 末尾初始消息是后续录制消息的父节点. 随机 uuid 断裂链条.
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("user", "系统上下文"));
        messages.add(msg("user", "用户 prompt"));
        SubagentExecutor.assignInitialMessageUuids(messages);
        String lastUuid = String.valueOf(messages.get(messages.size() - 1).get("uuid"));

        String lastRecordedUuid = SubagentExecutor.lastInitialMessageUuid(messages);

        assertThat(lastRecordedUuid)
            .as("lastRecordedUuid 必须等于 initialMessages 末尾消息的 uuid, 非随机生成")
            .isEqualTo(lastUuid);
        // 可复现性: 同一 initialMessages 两次计算 → 相同结果 (非随机)
        assertThat(SubagentExecutor.lastInitialMessageUuid(messages))
            .isEqualTo(lastRecordedUuid);
    }

    @Test
    @DisplayName("assignInitialMessageUuids 给每条消息补 uuid 键 (CC Message.uuid 语义)")
    void assignInitialMessageUuids_shouldAddUuidToEachMessage() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("system", "s"));
        messages.add(msg("user", "u"));
        messages.add(msg("user", "p"));

        SubagentExecutor.assignInitialMessageUuids(messages);

        assertThat(messages).allSatisfy(m ->
            assertThat(m.get("uuid"))
                .as("每条 initialMessage 必须携带 uuid 键 (CC Message.uuid)")
                .isNotNull());
        // 幂等: 已有 uuid 不覆盖
        String firstUuid = String.valueOf(messages.get(0).get("uuid"));
        SubagentExecutor.assignInitialMessageUuids(messages);
        assertThat(String.valueOf(messages.get(0).get("uuid")))
            .as("幂等 — 已有 uuid 键不被覆盖")
            .isEqualTo(firstUuid);
    }

    @Test
    @DisplayName("空 initialMessages → lastRecordedUuid null (CC ?? null 兜底)")
    void lastInitialMessageUuid_empty_shouldReturnNull() {
        assertThat(SubagentExecutor.lastInitialMessageUuid(List.of()))
            .as("空列表 → null (对齐 CC initialMessages.at(-1)?.uuid ?? null)")
            .isNull();
        assertThat(SubagentExecutor.lastInitialMessageUuid(null))
            .as("null → null")
            .isNull();
    }

    @Test
    @DisplayName("末尾消息无 uuid 键 → null (不伪造, 对齐 CC ?.uuid 可选链)")
    void lastInitialMessageUuid_missingKey_shouldReturnNull() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("user", "无 uuid 键的消息"));

        assertThat(SubagentExecutor.lastInitialMessageUuid(messages))
            .as("末尾消息无 uuid 键 → null (CC ?.uuid 可选链)")
            .isNull();
    }

    @Test
    @DisplayName("uuid 是确定性 UUID 格式 (非 random 占位)")
    void assignInitialMessageUuids_shouldProduceValidUuids() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("user", "x"));
        SubagentExecutor.assignInitialMessageUuids(messages);

        String uuid = String.valueOf(messages.get(0).get("uuid"));
        assertThat(UUID.fromString(uuid))
            .as("uuid 必须是合法 UUID (可 parse)")
            .isNotNull();
    }
}
