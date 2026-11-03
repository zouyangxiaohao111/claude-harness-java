package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目标解析（三级 · fail-closed）单测 · 对齐 dsh {@code target-resolver.ts}。
 *
 * <h2>WHY 这些断言重要（规则 9）</h2>
 * <p>macOS 的 AX 元素没有稳定 id（{@code AXIdentifier} 实测基本为空）⇒ 语义重绑是常见路径。
 * 而「重绑到错误的元素」= <b>点击了用户没打算点的按钮</b>。
 * <p>⇒ 多个候选时<b>必须拒绝并报歧义</b>，让模型重新观察，而不是挑一个。
 */
class ComputerUseTargetResolverTest {

    private static ComputerUseUiSnapshotStore.ObservedElement element(int index,
                                                                      String locator,
                                                                      String role,
                                                                      String name,
                                                                      List<String> actions) {
        return new ComputerUseUiSnapshotStore.ObservedElement(
                index, "h" + index, locator, role, name, null, true, actions, 0, 0, 0, 0);
    }

    private static ComputerUseUiSnapshotStore.Observation observation(
            List<ComputerUseUiSnapshotStore.ObservedElement> elements) {
        return new ComputerUseUiSnapshotStore.Observation(
                "obs-1", "s1", "Finder", "com.apple.finder", 4242L, "Documents",
                System.currentTimeMillis(), Long.MAX_VALUE, "hash", "full", "text", false, elements, 1);
    }

    @Test
    @DisplayName("级别 1：locator 相同且稳定字段一致 ⇒ exact-locator，confidence=1")
    void exactLocatorHit() {
        var target = element(1, "0.2", "Button", "Save", List.of("AXPress"));
        var obs = observation(List.of(element(0, "0", "Group", "", List.of()), target));

        var r = ComputerUseTargetResolver.resolve(obs, obs, target, false, true, true);

        assertThat(r.failed()).isFalse();
        assertThat(r.mode()).isEqualTo("exact-locator");
        assertThat(r.confidence()).isEqualTo(1.0d);
        assertThat(r.targetChanged()).isFalse();
    }

    @Test
    @DisplayName("不允许重绑且 locator 变了 ⇒ 拒绝（陈旧观察）")
    void staleWithoutRebindIsRejected() {
        // 原观察里目标在 "0.2"；新观察里它挪到了 "0.7"（树重排）
        var target = element(1, "0.2", "Button", "Save", List.of("AXPress"));
        var original = observation(List.of(element(0, "0", "Group", "", List.of()), target));
        var fresh = observation(List.of(element(0, "0", "Group", "", List.of()),
                element(5, "0.7", "Button", "Save", List.of("AXPress"))));

        var r = ComputerUseTargetResolver.resolve(original, fresh, target, false, true, true);

        assertThat(r.failed()).isTrue();
        assertThat(r.errorCode()).isEqualTo("COMPUTER_STALE_OBSERVATION");
        assertThat(r.error()).contains("rebinding was not allowed");
    }

    @Test
    @DisplayName("级别 3：语义唯一命中 ⇒ semantic-rebind，confidence=0.9，targetChanged=true")
    void semanticRebindHit() {
        // locator 变了（树重排），但角色+名字+动作+祖先指纹都一致 ⇒ 唯一候选
        var expected = element(1, "0.2", "Button", "Save", List.of("AXPress"));
        var moved = element(5, "0.7", "Button", "Save", List.of("AXPress"));
        var obs = observation(List.of(element(0, "0", "Group", "", List.of()), moved));

        var r = ComputerUseTargetResolver.resolve(obs, obs, expected, true, true, true);

        assertThat(r.failed()).isFalse();
        assertThat(r.mode()).isEqualTo("semantic-rebind");
        assertThat(r.confidence()).isEqualTo(0.9d);
        assertThat(r.targetChanged()).isTrue();
    }

    @Test
    @DisplayName("级别 3：多个语义候选 ⇒ 拒绝并报歧义（不挑一个）")
    void multipleSemanticCandidatesAreAmbiguous() {
        var expected = element(1, "0.2", "Button", "Save", List.of("AXPress"));
        var original = observation(List.of(element(0, "0", "Group", "", List.of()), expected));
        // 两个同名同角色同动作的按钮 ⇒ 歧义
        var a = element(5, "0.7", "Button", "Save", List.of("AXPress"));
        var b = element(6, "0.8", "Button", "Save", List.of("AXPress"));
        var fresh = observation(List.of(element(0, "0", "Group", "", List.of()), a, b));

        var r = ComputerUseTargetResolver.resolve(original, fresh, expected, true, true, true);

        assertThat(r.failed()).isTrue();
        assertThat(r.errorCode()).isEqualTo(ComputerUseTargetResolver.ERR_TARGET_AMBIGUOUS);
        assertThat(r.error()).contains("2 candidates");
    }

    @Test
    @DisplayName("级别 3：无候选 ⇒ 低置信度拒绝")
    void noSemanticCandidateIsLowConfidence() {
        var expected = element(1, "0.2", "Button", "Save", List.of("AXPress"));
        var original = observation(List.of(element(0, "0", "Group", "", List.of()), expected));
        var other = element(5, "0.7", "Button", "Cancel", List.of("AXPress"));
        var obs = observation(List.of(element(0, "0", "Group", "", List.of()), other));

        var r = ComputerUseTargetResolver.resolve(original, obs, expected, true, true, true);

        assertThat(r.failed()).isTrue();
        assertThat(r.errorCode()).isEqualTo(ComputerUseTargetResolver.ERR_TARGET_LOW_CONFIDENCE);
    }

    @Test
    @DisplayName("级别 3：没有可访问名 ⇒ 低置信度拒绝（无从判断唯一性）")
    void unnamedTargetIsLowConfidence() {
        // locator 已变（走不到 exact）且目标无名 ⇒ 语义重绑无从判断 ⇒ 低置信度
        var expected = element(1, "0.2", "Button", "", List.of("AXPress"));
        var original = observation(List.of(element(0, "0", "Group", "", List.of()), expected));
        var fresh = observation(List.of(element(0, "0", "Group", "", List.of()),
                element(5, "0.7", "Button", "", List.of("AXPress"))));

        var r = ComputerUseTargetResolver.resolve(original, fresh, expected, true, true, true);

        assertThat(r.failed()).isTrue();
        assertThat(r.errorCode()).isEqualTo(ComputerUseTargetResolver.ERR_TARGET_LOW_CONFIDENCE);
        assertThat(r.error()).contains("no native identifier or accessible name");
    }

    @Test
    @DisplayName("动作集不同 ⇒ 不是同一元素（稳定字段含 actions）")
    void differentActionsMeanDifferentElement() {
        var expected = element(1, "0.2", "Button", "Save", List.of("AXPress"));
        var original = observation(List.of(element(0, "0", "Group", "", List.of()), expected));
        // 新树里同名同角色，但动作集不同 ⇒ 不是同一元素 ⇒ 无语义候选
        var fresh = observation(List.of(element(0, "0", "Group", "", List.of()),
                element(5, "0.7", "Button", "Save", List.of("AXPress", "AXShowMenu"))));

        var r = ComputerUseTargetResolver.resolve(original, fresh, expected, true, true, true);

        assertThat(r.failed()).isTrue();
        assertThat(r.errorCode()).isEqualTo(ComputerUseTargetResolver.ERR_TARGET_LOW_CONFIDENCE);
    }

    @Test
    @DisplayName("应用换了 ⇒ 陈旧观察拒绝（不能把确认/点击用到别的进程）")
    void differentAppIsRejected() {
        var target = element(1, "0.2", "Button", "Save", List.of("AXPress"));
        var obs = observation(List.of(element(0, "0", "Group", "", List.of()), target));

        var r = ComputerUseTargetResolver.resolve(obs, obs, target, false, false, true);

        assertThat(r.failed()).isTrue();
        assertThat(r.errorCode()).isEqualTo("COMPUTER_STALE_OBSERVATION");
        assertThat(r.error()).contains("different process");
    }

    @Test
    @DisplayName("窗口换了 ⇒ 陈旧观察拒绝")
    void differentWindowIsRejected() {
        var target = element(1, "0.2", "Button", "Save", List.of("AXPress"));
        var obs = observation(List.of(element(0, "0", "Group", "", List.of()), target));

        var r = ComputerUseTargetResolver.resolve(obs, obs, target, false, true, false);

        assertThat(r.failed()).isTrue();
        assertThat(r.errorCode()).isEqualTo("COMPUTER_STALE_OBSERVATION");
        assertThat(r.error()).contains("window changed");
    }

    @Test
    @DisplayName("截断的观察 ⇒ 无法建立唯一性 ⇒ 低置信度拒绝（不猜）")
    void truncatedObservationIsRejected() {
        var expected = element(1, "0.2", "Button", "Save", List.of("AXPress"));
        var original = observation(List.of(element(0, "0", "Group", "", List.of()), expected));
        var moved = element(5, "0.7", "Button", "Save", List.of("AXPress"));
        var obs = new ComputerUseUiSnapshotStore.Observation(
                "obs-1", "s1", "Finder", "com.apple.finder", 4242L, "Documents",
                System.currentTimeMillis(), Long.MAX_VALUE, "hash", "full", "text", true,
                List.of(element(0, "0", "Group", "", List.of()), moved), 1);

        var r = ComputerUseTargetResolver.resolve(original, obs, expected, true, true, true);

        assertThat(r.failed()).isTrue();
        assertThat(r.errorCode()).isEqualTo(ComputerUseTargetResolver.ERR_TARGET_LOW_CONFIDENCE);
        assertThat(r.error()).contains("truncated");
    }
}
