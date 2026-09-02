package com.nexusai.application.agent.remote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RemoteTaskType 枚举守卫语义定向测试 · 对齐 CC RemoteAgentTask/RemoteAgentTask.tsx:60-64。
 *
 * <p><b>WHY（意图验证，规则九）</b> — 这些行为为何重要：
 * <ul>
 *   <li><b>5 值完整性（:60）</b>——completionCheckers 注册表（:78）按 RemoteTaskType 键分发
 *       completionChecker；枚举漏值 = 该类型任务在 poll 期静默丢 completionChecker（如 ultraplan 不判完成）。</li>
 *   <li><b>isRemoteTaskType 守卫（:62-64）</b>——restoreRemoteAgentTasks（:514）从 sidecar 元数据
 *       <b>未经校验的字符串</b>重建任务；脏值/旧版值若不拦下会被直接写入任务状态，导致 poll 走错分支。</li>
 *   <li><b>unknown → 回退 remote-agent（:514）</b>——sidecar 版本漂移时恢复不崩溃，降级到最保守类型。</li>
 *   <li><b>undefined/null 等价（:62 v ?? ''）</b>——老版本 sidecar 缺 remoteTaskType 字段（undefined）
 *       同样被守卫拦下回退，而非 NPE。</li>
 * </ul>
 */
@DisplayName("[W6-01] RemoteTaskType 5 值枚举 + isRemoteTaskType 守卫（对齐 CC :60-64）")
class RemoteTaskTypeTest {

    @Test
    @DisplayName("5 个枚举 wire 值完整且有序（CC :60）")
    void enumHasAllFiveWireValues() {
        // WHY: completionCheckers 注册表（:78）按这 5 个键分发；containsExactly 锁死全集防漏值
        assertThat(RemoteTaskType.values())
            .extracting(RemoteTaskType::value)
            .containsExactly("remote-agent", "ultraplan", "ultrareview", "autofix-pr", "background-pr");
    }

    @Test
    @DisplayName("isRemoteTaskType: 5 个合法 wire 值返回 true（CC :62-64）")
    void guardAcceptsAllFive() {
        // WHY: register 写入的合法值必须能被守卫放行，否则恢复路径把合法任务也降级成 remote-agent
        for (RemoteTaskType t : RemoteTaskType.values()) {
            assertThat(RemoteTaskType.isRemoteTaskType(t.value()))
                .as("wire value '%s' 应被守卫放行", t.value())
                .isTrue();
        }
    }

    @Test
    @DisplayName("isRemoteTaskType: 未知/空/null 返回 false（CC :62 v ?? ''）")
    void guardRejectsUnknownEmptyNull() {
        // WHY: sidecar 脏值/老版本缺字段若被放行，恢复路径会写入非法类型破坏状态机与 completionChecker 分发
        assertThat(RemoteTaskType.isRemoteTaskType("ultra-plan")).isFalse();
        assertThat(RemoteTaskType.isRemoteTaskType("Remote-Agent")).isFalse();
        assertThat(RemoteTaskType.isRemoteTaskType("")).isFalse();
        assertThat(RemoteTaskType.isRemoteTaskType(null)).isFalse();
    }

    @Test
    @DisplayName("fromValue→Optional; 脏值回退 remote-agent 等价 CC :514")
    void fromValueAndRestoreFallback() {
        // WHY: restore（:514）语义 = isRemoteTaskType(v) ? v : 'remote-agent'，脏值必须回退最保守类型
        assertThat(RemoteTaskType.fromValue("ultrareview")).contains(RemoteTaskType.ULTRAREVIEW);
        assertThat(RemoteTaskType.fromValue("remote-agent")).contains(RemoteTaskType.REMOTE_AGENT);
        assertThat(RemoteTaskType.fromValue(null)).isEmpty();

        String dirty = "unknown-type";
        RemoteTaskType restored = RemoteTaskType.isRemoteTaskType(dirty)
            ? RemoteTaskType.fromValue(dirty).orElseThrow()
            : RemoteTaskType.REMOTE_AGENT;
        assertThat(restored).isEqualTo(RemoteTaskType.REMOTE_AGENT);
    }
}
