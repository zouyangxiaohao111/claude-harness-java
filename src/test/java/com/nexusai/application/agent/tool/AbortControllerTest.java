package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-HOOKS-S2 E8] AbortController removeOnCancel + listenerCount 测试。
 *
 * <p>对齐 CC {@code removeEventListener('abort', listener)}（combinedAbortSignal.ts:40-44 /
 * execAgentHook.ts:229-230/:305-306 / execPromptHook.ts:102/:184 / execHttpHook.ts:219/:232）：
 * 父 abort 监听器在 exec 结束后必须移除，防长会话累积（内存泄漏 + 取消风暴）。
 */
@DisplayName("[E8] AbortController removeOnCancel 清理语义")
class AbortControllerTest {

    @Test
    @DisplayName("removeOnCancel 移除后 abort 不再触发该 listener")
    void removeOnCancel_stopsListenerDelivery() {
        AbortController controller = new AbortController();
        AtomicInteger fires = new AtomicInteger();
        java.util.function.Consumer<AbortController> listener = ac -> fires.incrementAndGet();

        controller.onCancel(listener);
        assertThat(controller.listenerCount()).isEqualTo(1);

        controller.removeOnCancel(listener);
        assertThat(controller.listenerCount()).as("移除后 listener 数归零").isZero();

        controller.abort("parent_cancelled");
        assertThat(fires.get()).as("移除后的 listener 不得被触发").isZero();
        assertThat(controller.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("removeOnCancel 幂等：重复移除 + 移除未注册 listener 均为 no-op")
    void removeOnCancel_idempotent() {
        AbortController controller = new AbortController();
        java.util.function.Consumer<AbortController> listener = ac -> {};

        controller.onCancel(listener);
        controller.removeOnCancel(listener);
        controller.removeOnCancel(listener);   // 重复移除 no-op
        controller.removeOnCancel(null);       // null no-op
        assertThat(controller.listenerCount()).isZero();
    }

    @Test
    @DisplayName("正常路径: 未移除的 listener 在 abort 时触发一次（once 语义由幂等检查承担）")
    void onCancel_stillDeliversWhenNotRemoved() {
        AbortController controller = new AbortController();
        AtomicInteger fires = new AtomicInteger();
        controller.onCancel(ac -> fires.incrementAndGet());
        controller.abort("x");
        controller.abort("y");
        assertThat(fires.get()).isEqualTo(1);
        assertThat(controller.listenerCount()).isEqualTo(1);
    }
}
