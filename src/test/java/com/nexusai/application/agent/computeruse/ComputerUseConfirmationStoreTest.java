package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感动作一次性确认令牌的单测 · 对齐 dsh {@code confirmations.ts}。
 *
 * <h2>WHY 这些断言重要（规则 9）</h2>
 * <p>这条防线一旦失效，模型就能通过「不声明 sensitive」绕过用户确认，直接输密码 / 确认付款。
 * 因此必须钉住：<b>令牌一次性</b>、<b>绑定三元组</b>（应用 / 观察 / 动作）、<b>失败尝试也消耗</b>。
 */
class ComputerUseConfirmationStoreTest {

    private static final String APP = "com.apple.finder:4242";
    private static final String OBS = "obs-1";

    private static String hash(String action, String payload) {
        return ComputerUseConfirmationStore.actionHash(
                ComputerUseConfirmationStore.canonicalParams(OBS, 3, "h1", action, payload));
    }

    @Test
    @DisplayName("正常路径：发放后用同样的参数能消费掉")
    void issuedTokenIsConsumedByMatchingAction() {
        ComputerUseConfirmationStore store = new ComputerUseConfirmationStore();
        String h = hash("click", null);
        ComputerUseConfirmationStore.Issued issued = store.issue("s1", APP, OBS, h);

        assertThat(store.consume("s1", issued.token(), APP, OBS, h)).isNull();
    }

    @Test
    @DisplayName("一次性：同一个令牌不能消费两次")
    void tokenIsSingleUse() {
        ComputerUseConfirmationStore store = new ComputerUseConfirmationStore();
        String h = hash("click", null);
        ComputerUseConfirmationStore.Issued issued = store.issue("s1", APP, OBS, h);

        assertThat(store.consume("s1", issued.token(), APP, OBS, h)).isNull();
        String second = store.consume("s1", issued.token(), APP, OBS, h);
        assertThat(second).contains("unknown, expired, or already consumed");
    }

    @Test
    @DisplayName("绑定应用：给 A 应用的令牌不能用在 B 应用上")
    void tokenIsBoundToApp() {
        ComputerUseConfirmationStore store = new ComputerUseConfirmationStore();
        String h = hash("click", null);
        ComputerUseConfirmationStore.Issued issued = store.issue("s1", APP, OBS, h);

        String rejection = store.consume("s1", issued.token(), "com.apple.Safari:999", OBS, h);
        assertThat(rejection).contains("does not match this app");
    }

    @Test
    @DisplayName("绑定观察：给旧观察的令牌不能用在别的观察上")
    void tokenIsBoundToObservation() {
        ComputerUseConfirmationStore store = new ComputerUseConfirmationStore();
        String h = hash("click", null);
        ComputerUseConfirmationStore.Issued issued = store.issue("s1", APP, OBS, h);

        String rejection = store.consume("s1", issued.token(), APP, "obs-2", h);
        assertThat(rejection).contains("does not match this app");
    }

    @Test
    @DisplayName("绑定动作：确认的是这一个动作，不是这一类动作")
    void tokenIsBoundToExactAction() {
        ComputerUseConfirmationStore store = new ComputerUseConfirmationStore();
        String clickHash = hash("click", null);
        ComputerUseConfirmationStore.Issued issued = store.issue("s1", APP, OBS, clickHash);

        // 同样 index/handle，但动作类型不同（type）⇒ 哈希不同 ⇒ 必须拒绝
        String typeHash = hash("type", "secret");
        String rejection = store.consume("s1", issued.token(), APP, OBS, typeHash);
        assertThat(rejection).contains("does not match this app");
    }

    @Test
    @DisplayName("载荷也进哈希：确认 type(甲) 不能用于 type(乙)")
    void payloadIsPartOfTheHash() {
        ComputerUseConfirmationStore store = new ComputerUseConfirmationStore();
        String hA = hash("type", "甲");
        String hB = hash("type", "乙");
        assertThat(hA).isNotEqualTo(hB);

        ComputerUseConfirmationStore.Issued issued = store.issue("s1", APP, OBS, hA);
        assertThat(store.consume("s1", issued.token(), APP, OBS, hB))
                .contains("does not match this app");
    }

    @Test
    @DisplayName("失败尝试也消耗令牌（先删后校验，dsh 同款）")
    void failedAttemptStillBurnsTheToken() {
        ComputerUseConfirmationStore store = new ComputerUseConfirmationStore();
        String h = hash("click", null);
        ComputerUseConfirmationStore.Issued issued = store.issue("s1", APP, OBS, h);

        // 第一次故意用错的动作 ⇒ 拒绝，但令牌已被消耗
        assertThat(store.consume("s1", issued.token(), APP, OBS, "wrong-hash"))
                .contains("does not match this app");
        // 第二次用对的 ⇒ 令牌已没了 ⇒ 仍然拒绝（不能「先试错、再用对」）
        assertThat(store.consume("s1", issued.token(), APP, OBS, h))
                .contains("unknown, expired, or already consumed");
    }

    @Test
    @DisplayName("TTL 过期后拒绝")
    void expiredTokenIsRejected() {
        ComputerUseConfirmationStore store = new ComputerUseConfirmationStore(1L);
        String h = hash("click", null);
        ComputerUseConfirmationStore.Issued issued = store.issue("s1", APP, OBS, h);

        long deadline = System.currentTimeMillis() + 50;
        while (System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(store.consume("s1", issued.token(), APP, OBS, h)).contains("expired");
    }

    @Test
    @DisplayName("哈希与参数书写顺序无关（键按字典序排序）")
    void actionHashIsOrderIndependent() {
        Map<String, String> a = new java.util.LinkedHashMap<>();
        a.put("action", "click");
        a.put("index", "3");
        Map<String, String> b = new java.util.LinkedHashMap<>();
        b.put("index", "3");
        b.put("action", "click");

        assertThat(ComputerUseConfirmationStore.actionHash(a))
                .isEqualTo(ComputerUseConfirmationStore.actionHash(b));
    }

    @Test
    @DisplayName("敏感动作缺令牌 ⇒ 拒绝；非敏感动作带令牌 ⇒ 也拒绝（令牌不得误用）")
    void gateRejectsMissingAndMisusedTokens() {
        ComputerUseConfirmationStore store = new ComputerUseConfirmationStore();
        ComputerUseUiSnapshotStore snapshots = new ComputerUseUiSnapshotStore();

        // 非敏感 + 无令牌 ⇒ 放行
        assertThat(ComputerUseSensitiveGate.check(store, snapshots, "s1",
                false, null, OBS, 1, null, "click", null).allowed()).isTrue();

        // 非敏感 + 有令牌 ⇒ 拒绝（dsh confirmations.ts:95-99）
        ComputerUseSensitiveGate.Gate misuse = ComputerUseSensitiveGate.check(store, snapshots, "s1",
                false, "some-token", OBS, 1, null, "click", null);
        assertThat(misuse.allowed()).isFalse();
        assertThat(misuse.rejection()).contains("valid only when sensitive is true");

        // 敏感 + 无令牌 ⇒ 拒绝
        ComputerUseSensitiveGate.Gate missing = ComputerUseSensitiveGate.check(store, snapshots, "s1",
                true, null, OBS, 1, null, "click", null);
        assertThat(missing.allowed()).isFalse();
        assertThat(missing.rejection()).contains("requires a token from computer_confirm");
    }
}
