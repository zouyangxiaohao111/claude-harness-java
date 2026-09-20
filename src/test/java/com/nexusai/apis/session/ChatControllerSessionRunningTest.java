package com.nexusai.apis.session;

import com.nexusai.application.agent.LlmAgentLoop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [C6] 会话运行态查询（{@code GET /api/v1/sessions/{id}/running}）·
 * 停止键可见性的<b>服务端真源</b>（用户需求「能够 UI 中手动终止会话循环」）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：前端此前判「要不要显示停止键」只靠本地簿记
 * （App.tsx activeStreams 仅登记「本页发送」的 turn + 有无流式块）。后台 drain（排队命令 / cron /
 * 任务通知）起的 run 两者皆假 ⇒ 停止键不出现、用户无从终止；F5 更会清空本地簿记。
 * 本端点把「该会话是否有服务端 run 存活」暴露出来，前端在载入 / 切会话 / 重连时据此重建。
 * 因此断言的不是「方法返回了个布尔」，而是<b>两个方向都必须对</b>：
 * 在跑 → true（否则该显示的停止键不出现）；不在跑 → false（否则点下去取消的是空气）。
 *
 * <p>真源 = {@link LlmAgentLoop#isSessionActive}（与队列消费闸门同一判据）。注册表是<b>静态</b>的
 * ⇒ 本测试显式构造运行态（{@code markRunning} / {@code reserve}），不依赖真跑一个 run；
 * 每个用例用独立 sessionId 且在 {@code @AfterEach} 归零，防污染同 JVM 其他用例。
 */
@DisplayName("[C6] 会话运行态查询 —— 停止键可见性的服务端真源")
class ChatControllerSessionRunningTest {

    private ChatController controller;
    private String sid;

    @BeforeEach
    void setUp() {
        // 本端点零注入依赖（只读 LlmAgentLoop 静态注册表 + 日志）⇒ 无需 ReflectionTestUtils 装配
        controller = new ChatController();
        sid = "sess-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        // 静态注册表进程级共享：两个桶都必须清（reserve 未命中时 cancelReservation 是 no-op，安全）
        LlmAgentLoop.cancelReservation(sid);
        LlmAgentLoop.markIdle(sid);
    }

    @Test
    @DisplayName("会话在跑（RUNNING_SESSIONS 有该会话）→ running=true")
    void running_trueWhenSessionRunning() {
        LlmAgentLoop.markRunning(sid);

        Map<String, Boolean> resp = controller.running(sid);

        assertThat(resp.get("running"))
            .as("服务端有 run 存活 ⇒ 前端必须据此显示停止键（否则后台 drain 起的 run 无从终止）")
            .isTrue();
    }

    @Test
    @DisplayName("会话不在跑（无 run、无 dispatching 保留）→ running=false")
    void running_falseWhenIdle() {
        Map<String, Boolean> resp = controller.running(sid);

        assertThat(resp.get("running"))
            .as("无 run 存活 ⇒ 前端不得显示停止键（否则点下去取消的是空气）")
            .isFalse();
    }

    @Test
    @DisplayName("run 收口（markIdle 计数归零）→ 回到 false（不是「一次 true 永久 true」）")
    void running_backToFalseAfterIdle() {
        LlmAgentLoop.markRunning(sid);
        assertThat(controller.running(sid).get("running"))
            .as("前置：run 在飞时必须是 true")
            .isTrue();

        LlmAgentLoop.markIdle(sid);

        assertThat(controller.running(sid).get("running"))
            .as("run 收口 ⇒ 停止键必须消失（否则 UI 永久停在「运行中」、Esc 永久走停止分支）")
            .isFalse();
    }

    @Test
    @DisplayName("条目已出队、异步链尚未开跑（dispatching 保留）→ 也算运行中")
    void running_trueWhileDispatching() {
        assertThat(LlmAgentLoop.reserve(sid))
            .as("前置：本次 reserve 必须成功（未被其他用例污染）")
            .isTrue();

        assertThat(controller.running(sid).get("running"))
            .as("CC QueryGuard.isActive 对 dispatching 同样返回 true（队列处理器同一判据）——"
                + "「已出队未开跑」这段缺口里前端也必须能停")
            .isTrue();
    }
}
