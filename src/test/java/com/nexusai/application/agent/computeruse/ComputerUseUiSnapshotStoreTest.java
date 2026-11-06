package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 观察绑定与陈旧拒绝单测 · 对齐 dsh {@code dsh-computer-use/src/service.ts:435-478} 的 {@code actNow}。
 *
 * <h2>WHY 这些断言重要（规则 9 · 测试验证意图）</h2>
 * <p>macOS 的 AX 元素<b>没有稳定 id</b>（{@code AXIdentifier} 实测基本为空），
 * 只能靠「本次观察里的序号 / 子节点路径」定位 —— 那是<b>相对一次观察</b>的坐标。
 * 用户中途打开新窗口、切换标签页，同一序号就会指向完全不同的控件。
 * <p>⇒ 若不做「观察绑定 + 陈旧拒绝」，模型的一次点击就可能打到<b>别的应用的别的按钮</b>上。
 * 这些用例钉住的正是这条安全边界，而不是某个字段的取值。
 */
class ComputerUseUiSnapshotStoreTest {

    private static ComputerUseUiSnapshotStore.ObservedElement element(int index, String locator, String role, String name) {
        return new ComputerUseUiSnapshotStore.ObservedElement(
                index, "handle-" + index, locator, role, name, null, true, List.of("AXPress"), 0, 0, 0, 0);
    }

    private static ComputerUseUiSnapshotStore.Observation recordSample(ComputerUseUiSnapshotStore store,
                                                                       String sessionId,
                                                                       String stateHash) {
        return store.record(sessionId, "Finder", "com.apple.finder", 4242L, "Documents",
                stateHash, "[0] Window\n[1] Button \"Open\"", false,
                List.of(element(0, "", "Window", "Documents"),
                        element(1, "0", "Button", "Open")),
                true);
    }

    @Test
    @DisplayName("未知 observationId ⇒ 拒绝（不是「按遇到的第一个元素猜」）")
    void unknownObservationIsRejected() {
        ComputerUseUiSnapshotStore store = new ComputerUseUiSnapshotStore();
        ComputerUseElementResolver.Resolution r =
                ComputerUseElementResolver.resolveObserved(store, "s1", "not-exist", 0, null, false, "click");

        assertThat(r.failed()).isTrue();
        assertThat(r.errorCode()).isEqualTo(ComputerUseUiSnapshotStore.ERR_STALE_OBSERVATION);
        assertThat(r.error()).contains("observe again");
    }

    @Test
    @DisplayName("index 不在该观察内 ⇒ COMPUTER_ELEMENT_UNAVAILABLE")
    void indexOutsideObservationIsRejected() {
        ComputerUseUiSnapshotStore store = new ComputerUseUiSnapshotStore();
        ComputerUseUiSnapshotStore.Observation obs = recordSample(store, "s1", "h1");

        ComputerUseElementResolver.Resolution r =
                ComputerUseElementResolver.resolveObserved(store, "s1", obs.observationId(), 99, null, false, "click");

        assertThat(r.errorCode()).isEqualTo(ComputerUseUiSnapshotStore.ERR_ELEMENT_UNAVAILABLE);
        assertThat(r.error()).contains("99");
    }

    @Test
    @DisplayName("index 与 targetHandle 指向不同元素 ⇒ 拒绝（防止两套选择器打架）")
    void mismatchedIndexAndHandleIsRejected() {
        ComputerUseUiSnapshotStore store = new ComputerUseUiSnapshotStore();
        ComputerUseUiSnapshotStore.Observation obs = recordSample(store, "s1", "h1");

        // index=1 是 Button "Open"，却给 Window 的 handle
        ComputerUseElementResolver.Resolution r = ComputerUseElementResolver.resolveObserved(
                store, "s1", obs.observationId(), 1, "handle-0", false, "click");

        assertThat(r.errorCode()).isEqualTo(ComputerUseUiSnapshotStore.ERR_TARGET_UNAVAILABLE);
        assertThat(r.error()).contains("different elements");
    }

    @Test
    @DisplayName("allowRebind 但没给 targetHandle ⇒ 拒绝")
    void allowRebindWithoutHandleIsRejected() {
        ComputerUseUiSnapshotStore store = new ComputerUseUiSnapshotStore();
        ComputerUseUiSnapshotStore.Observation obs = recordSample(store, "s1", "h1");

        ComputerUseElementResolver.Resolution r = ComputerUseElementResolver.resolveObserved(
                store, "s1", obs.observationId(), 1, null, true, "click");

        assertThat(r.errorCode()).isEqualTo(ComputerUseUiSnapshotStore.ERR_TARGET_UNAVAILABLE);
        assertThat(r.error()).contains("allowRebind requires a targetHandle");
    }

    @Test
    @DisplayName("正常路径：index 解析出 locator，并带出该观察的状态指纹（供动作侧校验树未变）")
    void resolvedCarriesLocatorAndStateHash() {
        ComputerUseUiSnapshotStore store = new ComputerUseUiSnapshotStore();
        ComputerUseUiSnapshotStore.Observation obs = recordSample(store, "s1", "hash-abc");

        ComputerUseElementResolver.Resolution r = ComputerUseElementResolver.resolveObserved(
                store, "s1", obs.observationId(), 1, null, false, "click");

        assertThat(r.failed()).isFalse();
        assertThat(r.locator()).isEqualTo("0");
        assertThat(r.element().name()).isEqualTo("Open");
        // 动作侧据此拒绝「树已变」的执行（dsh expectedStateHash）
        assertThat(r.stateHash()).isEqualTo("hash-abc");
    }

    @Test
    @DisplayName("TTL 过期 ⇒ 该观察不再可用（0 = 永不过期，对齐 dsh 默认）")
    void expiredObservationIsRejected() {
        // TTL = 1ms ⇒ 立即过期
        ComputerUseUiSnapshotStore store = new ComputerUseUiSnapshotStore(1L);
        ComputerUseUiSnapshotStore.Observation obs = recordSample(store, "s1", "h1");

        // 等到确实过期
        long deadline = System.currentTimeMillis() + 50;
        while (System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(store.require("s1", obs.observationId())).isNull();
    }

    @Test
    @DisplayName("TTL = 0 ⇒ 永不过期（dsh 默认值）")
    void zeroTtlNeverExpires() {
        ComputerUseUiSnapshotStore store = new ComputerUseUiSnapshotStore();
        ComputerUseUiSnapshotStore.Observation obs = recordSample(store, "s1", "h1");

        assertThat(obs.expiresAt()).isEqualTo(Long.MAX_VALUE);
        assertThat(store.require("s1", obs.observationId())).isNotNull();
    }

    @Test
    @DisplayName("会话隔离：别的会话拿不到本会话的 observationId")
    void observationsAreSessionScoped() {
        ComputerUseUiSnapshotStore store = new ComputerUseUiSnapshotStore();
        ComputerUseUiSnapshotStore.Observation obs = recordSample(store, "s1", "h1");

        assertThat(store.require("s2", obs.observationId())).isNull();
        assertThat(store.require("s1", obs.observationId())).isNotNull();
    }

    @Test
    @DisplayName("每会话最多 64 条观察，超出按插入顺序淘汰最旧（dsh service.ts:799）")
    void oldestObservationsAreEvicted() {
        ComputerUseUiSnapshotStore store = new ComputerUseUiSnapshotStore();
        String firstId = null;
        for (int i = 0; i < ComputerUseUiSnapshotStore.MAX_OBSERVATIONS + 2; i++) {
            ComputerUseUiSnapshotStore.Observation obs = recordSample(store, "s1", "h" + i);
            if (i == 0) {
                firstId = obs.observationId();
            }
        }

        // 最旧的两条已被淘汰
        assertThat(store.require("s1", firstId)).isNull();
        assertThat(store.require("s1", "never")).isNull();
    }

    @Test
    @DisplayName("diff 模式：第二次观察只报变化行，而不是重发整棵树")
    void secondObservationReturnsDiff() {
        ComputerUseUiSnapshotStore store = new ComputerUseUiSnapshotStore();
        recordSample(store, "s1", "h1");
        // 同一应用（bundleId:pid 相同）第二次观察，full=false
        ComputerUseUiSnapshotStore.Observation second = store.record(
                "s1", "Finder", "com.apple.finder", 4242L, "Documents", "h1",
                "[0] Window\n[1] Button \"Open\"", false,
                List.of(element(0, "", "Window", "Documents"),
                        element(1, "0", "Button", "Open")),
                false);

        assertThat(second.treeMode()).isEqualTo("diff");
        assertThat(second.treeText()).contains("no accessibility changes");
    }

    @Test
    @DisplayName("diff 模式：同名元素内容变了算「变化」，名字不同才算「新增/移除」")
    void diffDetectsChangedAndRemoved() {
        // ⭐ WHY 改成「语义身份」而非「位置身份」（对齐 dsh src/diff.ts:7-13）：
        //   dsh 的 identity = role|subrole|title|label|frame —— 即「同一个控件」哪怕在树里
        //   换了位置，仍被认作「同一个，只是变了点东西」；
        //   而按 locator（位置）对齐时，界面轻微重排就会把每个元素都报成「变了」，
        //   模型被告知「到处都变了」却拿不到任何有用信息。
        //   ⛔ 本仓 macOS 拿不到坐标 ⇒ frame 项缺省 ⇒ identity 退化为 role|subrole|name。
        List<ComputerUseUiSnapshotStore.ObservedElement> before = List.of(
                element(0, "", "Window", "W"),
                element(1, "0", "Button", "A"),
                element(2, "1", "Button", "Gone"));
        List<ComputerUseUiSnapshotStore.ObservedElement> after = List.of(
                element(0, "", "Window", "W"),
                element(1, "0", "Button", "B"));

        String diff = ComputerUseUiSnapshotStore.diffElements(before, after);

        // 名字从 "A" 变成 "B" ⇒ 语义身份变了 ⇒ 是「新增 + 移除」，不是「变化」
        //   （同名元素内容变化才判 ~；见下一个用例）
        assertThat(diff).contains("+ [#1] [Button] \"B\"");
        assertThat(diff).contains("- [#1] [Button] \"A\"");
        // 名字 "Gone" 的元素消失了 ⇒ 移除行（-）
        assertThat(diff).contains("- [#2] [Button] \"Gone\"");
        assertThat(diff).doesNotContain("~");
    }

    @Test
    @DisplayName("diff 模式：同名元素内容变了 ⇒ 判「变化」（~）而非新增/移除")
    void diffDetectsSameNameContentChange() {
        // 同名同角色 ⇒ 语义身份相同 ⇒ 是「同一个元素，内容变了」⇒ ~ （对齐 dsh 的 state 比较）
        List<ComputerUseUiSnapshotStore.ObservedElement> before = List.of(
                element(0, "", "Window", "W"),
                element(1, "0", "Button", "Save"));
        List<ComputerUseUiSnapshotStore.ObservedElement> after = List.of(
                element(0, "", "Window", "W"),
                element(1, "0", "Button", "Save"));

        // 内容变了（value 不同）⇒ ~ ；这里用 actions 变化来制造 state 差异
        List<ComputerUseUiSnapshotStore.ObservedElement> afterChanged = List.of(
                element(0, "", "Window", "W"),
                new ComputerUseUiSnapshotStore.ObservedElement(1, "h", "0", "Button", "Save", null,
                        true, List.of("AXPress", "AXShowMenu"), 0, 0, 0, 0, "", ""));

        String diff = ComputerUseUiSnapshotStore.diffElements(before, afterChanged);

        assertThat(diff).contains("~ [#1] [Button] \"Save\"");
        assertThat(diff).doesNotContain("+ [#1]");
        assertThat(diff).doesNotContain("- [#1]");
    }
}
