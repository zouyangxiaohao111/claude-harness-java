package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.Notification;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P3 · OPD-WF3-01-09] 分类器错误 ant 通知消费测试 · CC permissions.ts:704-716
 * {@code if (USER_TYPE==='ant' && classifierResult.errorDumpPath && context.addNotification) { addNotification({...}) }}。
 *
 * <p>WHY（意图验证）：拍板选定统一 ant 标签门控（{@code System.getenv("USER_TYPE")==='ant'}，
 * 复用 MockRateLimits/SpeculationEngine 先例，不新建 feature）。JVM 环境变量无法在单测内修改
 * （JDK 25+ 反射 add-opens 受限，R32B7a1 先例跳过 env 测试）→ 本测试直接调用包级
 * {@link PermissionPipeline#pushClassifierErrorDumpNotification} 验证通知 payload（CC :710-715
 * key/text/priority/color → Java id/title/body/level 契约）；ant 门为薄封装（CC :705 明文锁定）。
 *
 * <p>errorDumpPath 生产侧在 {@code classifier.YoloClassifierErrorDumpPathTest} 覆盖。
 */
@DisplayName("[P3] 分类器错误 ant 通知 payload（CC permissions.ts:704-716）")
class PermissionPipelineClassifierErrorDumpNotificationTest {

    @Test
    @DisplayName("[P3] 通知 payload 对齐 CC：id 前缀 / title / body（含 /share）/ error 级别")
    void pushClassifierErrorDumpNotification_carriesCcPayload() {
        AtomicReference<Notification> captured = new AtomicReference<>();
        PermissionPipeline pipeline = new PermissionPipeline();

        pipeline.pushClassifierErrorDumpNotification(
            tucWithNotification(captured::set), "/tmp/dump.txt");

        Notification n = captured.get();
        assertThat(n).as("ant 分类器错误必须经 addNotification 推前端（CC :710-715）").isNotNull();
        assertThat(n.id())
            .as("CC key 'auto-mode-error-dump' —— Java id 前缀对齐（防 React dedupe 吞新通知）")
            .startsWith("auto-mode-error-dump-");
        assertThat(n.title()).isEqualTo("Auto mode classifier error");
        assertThat(n.body())
            .as("CC :712 text —— prompts dumped to {errorDumpPath} (included in /share)")
            .contains("prompts dumped to /tmp/dump.txt")
            .contains("included in /share");
        assertThat(n.level())
            .as("CC :714 color:'error' → Java Notification.Level.ERROR")
            .isEqualTo(Notification.Level.ERROR);
    }

    @Test
    @DisplayName("[P3] ctx 为 null → 静默跳过（CC context.addNotification 真值/可选链语义）")
    void pushNotification_nullSafeWhenCtxNull() {
        PermissionPipeline pipeline = new PermissionPipeline();
        // 无异常即通过（CC addNotification 仅在存在时调用）
        pipeline.pushClassifierErrorDumpNotification(null, "/tmp/dump.txt");
    }

    /** 32 参 ToolUseContext 携带真实 addNotification 回调（LlmAgentLoopStopHookFailureCatchTest 同款）。 */
    private static ToolUseContext tucWithNotification(Consumer<Notification> addNotification) {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            Map.of(), List.of(), null, AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
            Map.of(), false, null, null, null, Map.of(), p -> {},
            null, null, null, null,
            addNotification, null, null, null, null, null, null, null, null, null);
    }
}
