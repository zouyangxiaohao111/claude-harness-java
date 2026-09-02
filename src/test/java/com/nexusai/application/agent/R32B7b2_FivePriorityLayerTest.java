package com.nexusai.application.agent;

import com.nexusai.application.agent.settings.SupportedSettings;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7b-2 · <b>R8 严格五层优先级测试</b> · 验证 P1-3 修复: 严格对齐 CC
 * {@code Open-ClaudeCode/src/utils/model/model.ts:81-88} 五层优先级链.
 *
 * <p><b>WHY (意图验证)</b>: R4 redo 报告 P1-3 缺陷 "startup flag 层与 session override
 * 混淆/缺失" — 旧实现 startup flag 与 session override 共用同一字段,
 * {@code params.modelName()} 优先级被错误地提升到 startup flag 位置.
 * 本测试验证 P1-3 修复: 严格五层优先级, startup flag 独立字段, 调用 fallback 仅在
 * 五层全空时返回 null.
 *
 * <p><b>严格优先级</b> (CC model.ts:81-88; [W6-2] 用户拍板删除 env {@code ANTHROPIC_MODEL} 层):
 * <ol>
 *   <li><b>Session override</b> (最高) — runtimeModelOverride (CC /model 命令)</li>
 *   <li><b>Startup flag</b> — startupModelFlag (CC --model 启动 flag)</li>
 *   <li><b>Settings</b> — configStorage.readSettings(["model"])</li>
 *   <li><b>Built-in default</b> (最低) — null (caller fallback to params.modelName())</li>
 * </ol>
 *
 * <p><b>R8 用例覆盖</b> (20 用例):
 * <ul>
 *   <li>R8-1: 全部空 → null (caller fallback)</li>
 *   <li>R8-2: 仅 settings → settings 值</li>
 *   <li>R8-3: [W6-2] 仅 env (已删除) → env 被忽略 → null</li>
 *   <li>R8-4: 仅 startup flag (优先级 2) → flag 值</li>
 *   <li>R8-5: 仅 session override (优先级 1) → override 值</li>
 *   <li>R8-6: settings + env (已删除) → settings 胜出 (env 不再遮蔽)</li>
 *   <li>R8-7: settings + startup flag → flag 胜出 (2 > 4)</li>
 *   <li>R8-8: startup flag + session override → override 胜出 (1 > 2)</li>
 *   <li>R8-9: 全部层都设 → session override 胜出 (优先级 1, 最高)</li>
 *   <li>R8-10: null/blank override + null/blank flag + null/blank env → settings 胜出</li>
 *   <li>R8-11: JSON null settings (NullMarker) 视为 absent → 回落 caller fallback</li>
 *   <li>R8-12: 严格顺序校验 (1 > 2 > 4 > 5) · 全组合扫描</li>
 *   <li>R8-13: 无 allowlist 配置 → 任意 model 通过 (CC 'no restrictions' 语义)</li>
 *   <li>R8-14: allowlist=opus/sonnet/haiku · family alias 接受 family 内任意 model</li>
 *   <li>R8-15: env (已删除) 设非法值 → 被忽略, settings 生效</li>
 *   <li>R8-16: 非法 settings model 不在 allowlist → 跳过 settings 层, 返回 null</li>
 *   <li>R8-17: 非法 runtime override 不在 allowlist → 跳过, 回落 startup/settings</li>
 *   <li>R8-18: 非法 startup flag 不在 allowlist → 跳过 (env 已删除, 无回落) → null</li>
 *   <li>R8-19: setModelAllowlist(null/empty) → 关闭校验, 恢复 'all allowed' 语义</li>
 *   <li>R8-20: 段边界匹配 — 'opus' 不匹配 'opusplan' 假阳性 (CC prefixMatchesModel)</li>
 * </ul>
 *
 * @see LlmAgentLoop#getModelForCall()
 */
class R32B7b2_FivePriorityLayerTest {

    private TestableLlmAgentLoop loop;
    private FileConfigStorage storage;
    private Path tmpDir;
    private String originalUserHome;

    @BeforeEach
    void setUp() throws Exception {
        tmpDir = java.nio.file.Files.createTempDirectory("nexusai-b7b2-priority");
        tmpDir.toFile().deleteOnExit();
        // 决策 D1：FileConfigStorage 单参（ConfigStorageProperties），缺省路径 = user.home 派生；
        //   覆写 user.home 隔离测试写盘（防污染真实 ~/.nexusai），用后恢复。
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tmpDir.toString());
        storage = new FileConfigStorage(null);
        com.nexusai.infra.llm.LlmProviderFactory mockFactory =
            org.mockito.Mockito.mock(com.nexusai.infra.llm.LlmProviderFactory.class);
        loop = new TestableLlmAgentLoop(mockFactory);
        loop.setFileConfigStorage(storage);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
        if (tmpDir != null) {
            java.nio.file.Files.walk(tmpDir)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    @Test
    @DisplayName("R8-1: 全部 5 层空 → null (caller fallback 接管)")
    void allLayersEmptyReturnsNull() {
        // WHY: P1-3 修复保证五层全空时严格返回 null, 由 run() 接管 params.modelName().
        assertThat(loop.getModelForCall()).isNull();
    }

    @Test
    @DisplayName("R8-2: 仅 settings → settings 值 · 优先级 4 单独胜出")
    void onlySettingsWins() {
        // WHY: 验证优先级 4 (settings) 单独存在时返回值.
        storage.writeSettings(List.of("model"), "sonnet");
        assertThat(loop.getModelForCall()).isEqualTo("sonnet");
    }

    @Test
    @DisplayName("R8-3: [W6-2] 仅 env (已删除) → env 被忽略 → null (caller fallback)")
    void envIgnoredReturnsNull() {
        // WHY: W6-2 用户拍板彻底删除 env 层 — ANTHROPIC_MODEL 不再作为主模型来源.
        // 仅设 env (无 settings/override/flag) → getModelForCall 必须忽略 env, 返回 null.
        loop.setEnvForTest("haiku");
        assertThat(loop.getModelForCall()).isNull();
    }

    @Test
    @DisplayName("R8-4: 仅 startup flag → flag 值 · 优先级 2 单独胜出 (P1-3 独立字段验证)")
    void onlyStartupFlagWins() {
        // WHY: P1-3 修复关键 — startup flag 必须独立于 session override, 单独存在时返回值.
        loop.setStartupModelFlag("opus");
        assertThat(loop.getModelForCall()).isEqualTo("opus");
    }

    @Test
    @DisplayName("R8-5: 仅 session override → override 值 · 优先级 1 单独胜出")
    void onlySessionOverrideWins() {
        // WHY: 验证优先级 1 (session override) 单独存在时返回值.
        loop.setRuntimeModelOverride("opus");
        assertThat(loop.getModelForCall()).isEqualTo("opus");
    }

    @Test
    @DisplayName("R8-6: [W6-2] settings + env (已删除) → settings 胜出 (env 不再遮蔽)")
    void envRemovedSettingsWins() {
        // WHY: W6-2 删除 env 层 — env 值必须被忽略, settings (优先级 4) 不再被 env 遮蔽.
        storage.writeSettings(List.of("model"), "sonnet");  // settings=sonnet
        loop.setEnvForTest("haiku");                       // env 已删除, 应忽略
        assertThat(loop.getModelForCall()).isEqualTo("sonnet");
    }

    @Test
    @DisplayName("R8-7: settings + startup flag → flag 胜出 (2 > 4)")
    void startupFlagBeatsSettings() {
        // WHY: P1-3 修复 — startup flag (2) 必须胜 settings (4).
        // 旧实现 startup flag 与 session override 共用字段, 此用例无法区分, 是 P1-3 缺陷的关键证据.
        storage.writeSettings(List.of("model"), "sonnet");  // 4: settings=sonnet
        loop.setStartupModelFlag("opus");                   // 2: flag=opus
        assertThat(loop.getModelForCall()).isEqualTo("opus");
    }

    @Test
    @DisplayName("R8-8: startup flag + session override → override 胜出 (1 > 2)")
    void sessionOverrideBeatsStartupFlag() {
        // WHY: P1-3 修复 — session override (1) 必须胜 startup flag (2), 严格分离两个独立字段.
        loop.setStartupModelFlag("opus");                  // 2: flag=opus
        loop.setRuntimeModelOverride("haiku");              // 1: override=haiku
        assertThat(loop.getModelForCall()).isEqualTo("haiku");
    }

    @Test
    @DisplayName("R8-9: 全部层都设 → session override 胜出 (优先级 1, 最高)")
    void allLayersSetSessionOverrideWins() {
        // WHY: P1-3 严格顺序总验证 — 全层设值, session override (优先级 1) 必须胜出.
        // [W6-2] env 层已删除, 剩余层: settings / startup flag / override.
        storage.writeSettings(List.of("model"), "sonnet");  // settings=sonnet
        loop.setEnvForTest("haiku");                       // env 已删除, 应忽略
        loop.setStartupModelFlag("opus");                  // flag=opus
        // 这里直接验证: session override 在 flag 之上
        loop.setRuntimeModelOverride("claude-3");          // override=claude-3
        assertThat(loop.getModelForCall()).isEqualTo("claude-3");
    }

    @Test
    @DisplayName("R8-10: null/blank override + null/blank flag + null/blank env → settings 胜出")
    void blankOverridesFallThroughToSettings() {
        // WHY: P1-3 修复保证 null/blank 字段不遮蔽更低优先级 (CC model.ts:81-88 严格空白处理).
        // setRuntimeModelOverride("")  → blank → 跳过
        // setStartupModelFlag("   ")    → blank → 跳过
        // setEnvForTest(null)           → "" → 跳过
        // settings.model = "sonnet"     → 4 → 胜出
        storage.writeSettings(List.of("model"), "sonnet");
        loop.setRuntimeModelOverride("");
        loop.setStartupModelFlag("   ");
        loop.setEnvForTest(null);
        assertThat(loop.getModelForCall()).isEqualTo("sonnet");
    }

    @Test
    @DisplayName("R8-11: JSON null settings (NullMarker) 视为 absent → 回落 caller fallback")
    void jsonNullSettingsTreatedAsAbsent() {
        // WHY: P1-3 修复 — JSON null (NullMarker) 与 absent 区分对待 (FileConfigStorage 语义).
        // ConfigTool SET model=null 写入的是 NullMarker → 不应作为有效 model, 应回落下一层.
        // 这里手动写入 JSON null (NullMarker) 模拟该场景.
        storage.writeSettings(List.of("model"),
            com.nexusai.application.agent.settings.storage.ConfigStorage.NullMarker);
        assertThat(loop.getModelForCall()).isNull();
    }

    @Test
    @DisplayName("R8-12: 严格顺序校验 (1 > 2 > 3 > 4 > 5) · 全组合扫描")
    void strictOrderFullCombinationScan() {
        // WHY: P1-3 修复核心保证 — 各层组合测试, 每组都验证正确的层胜出.
        // [W6-2] env 层已删除, 剩余有效层: override(L1) > flag(L2) > settings(L4) > null.

        // 组合 A: settings only → L4 胜出
        storage.writeSettings(List.of("model"), "L4");
        assertThat(loop.getModelForCall()).isEqualTo("L4");

        // 组合 B: settings + env → env 已删除 → L4 胜出 (settings)
        loop.setEnvForTest("L3");
        assertThat(loop.getModelForCall()).isEqualTo("L4");

        // 组合 C: settings + env + flag → L2 胜出 (flag > settings)
        loop.setStartupModelFlag("L2");
        assertThat(loop.getModelForCall()).isEqualTo("L2");

        // 组合 D: settings + env + flag + override → L1 胜出 (override > flag)
        loop.setRuntimeModelOverride("L1");
        assertThat(loop.getModelForCall()).isEqualTo("L1");

        // 组合 E: override + flag (清空 settings + env) → 仍 L1 胜出
        storage.unsetSettings(List.of("model"));
        loop.setEnvForTest("");
        assertThat(loop.getModelForCall()).isEqualTo("L1");

        // 组合 F: 清空 override, 只剩 flag → L2 胜出
        loop.setRuntimeModelOverride(null);
        assertThat(loop.getModelForCall()).isEqualTo("L2");
    }

    // ── P2-1 模型 allowlist 校验 (CC modelAllowlist.ts:100 isModelAllowed) ──

    @Test
    @DisplayName("R8-13: 无 allowlist 配置 → 任意 model 通过 (CC 'no restrictions' 语义)")
    void noAllowlistAcceptsAnyModel() {
        // WHY: P2-1 修复核心 — CC modelAllowlist.ts:102-104 availableModels 未设时
        // 返回 true. Java 端 modelAllowlist=null/empty → isModelAllowed 永远 true,
        // 现有测试场景 (setRuntimeModelOverride("opus")) 不受影响.
        assertThat(loop.getModelForCall()).isNull(); // 无任何 layer 设值 → null
        loop.setRuntimeModelOverride("opus");
        assertThat(loop.getModelForCall()).isEqualTo("opus");
        loop.setRuntimeModelOverride("gpt-4-turbo"); // 任意 model 通过
        assertThat(loop.getModelForCall()).isEqualTo("gpt-4-turbo");
    }

    @Test
    @DisplayName("R8-14: allowlist=opus/sonnet/haiku · family alias 接受 family 内任意 model")
    void familyAliasAcceptsFamilyMembers() {
        // WHY: P2-1 修复 — 对齐 CC family alias 通配 (modelAllowlist.ts:130-138):
        // allowlist 含 "opus" → "claude-opus-4-5-20251101" 等完整 id 也通过.
        loop.setModelAllowlist(List.of("opus", "sonnet", "haiku"));

        loop.setRuntimeModelOverride("opus");
        assertThat(loop.getModelForCall()).isEqualTo("opus");

        loop.setRuntimeModelOverride("claude-opus-4-5-20251101");
        assertThat(loop.getModelForCall()).isEqualTo("claude-opus-4-5-20251101");

        loop.setRuntimeModelOverride("claude-sonnet-4-6");
        assertThat(loop.getModelForCall()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    @DisplayName("R8-15: [W6-2] env (已删除) 设任意值 → 被忽略, settings 生效")
    void envIgnoredSettingsWins() {
        // WHY: W6-2 删除 env 层 — env 值 (无论是否在 allowlist) 一律被忽略,
        // settings (优先级 4) 生效. 若未来误加回 env 读取, 本用例即回归红.
        loop.setModelAllowlist(List.of("opus", "sonnet", "haiku"));
        loop.setEnvForTest("gpt-4-turbo");  // env 已删除, 应忽略 (即使不在 allowlist)
        storage.writeSettings(List.of("model"), "sonnet");
        assertThat(loop.getModelForCall()).isEqualTo("sonnet");
    }

    @Test
    @DisplayName("R8-16: 非法 settings model 不在 allowlist → 跳过 settings 层, 返回 null")
    void illegalSettingsModelSkippedAndReturnsNull() {
        // WHY: P2-1 修复 — ConfigTool SET model="gpt-4" 在 allowlist=[opus/sonnet/haiku]
        // 时不应生效; getModelForCall 跳过该层 → 回落 caller fallback (null).
        loop.setModelAllowlist(List.of("opus", "sonnet", "haiku"));
        storage.writeSettings(List.of("model"), "gpt-4");
        // 其它 4 层全部空 → 应返回 null
        assertThat(loop.getModelForCall()).isNull();
    }

    @Test
    @DisplayName("R8-17: 非法 runtime override 不在 allowlist → 跳过, 回落 startup/env/settings")
    void illegalOverrideSkippedAndFallback() {
        // WHY: P2-1 修复 — /model 命令输入非法 model 不应生效 (CC 等价语义).
        loop.setModelAllowlist(List.of("opus", "sonnet", "haiku"));
        loop.setRuntimeModelOverride("gpt-4");  // 非法
        loop.setStartupModelFlag("opus");        // 合法, 优先级 2
        assertThat(loop.getModelForCall()).isEqualTo("opus");
    }

    @Test
    @DisplayName("R8-18: [W6-2] 非法 startup flag 不在 allowlist → 跳过 (env 已删除, 无回落层) → null")
    void illegalStartupFlagSkippedReturnsNull() {
        // WHY: P2-1 修复 — --model CLI flag 非法 model 不应生效.
        // W6-2 删除 env 层后, env 不再是 startup flag 的回落层; settings 空 → 返回 null.
        loop.setModelAllowlist(List.of("opus", "sonnet", "haiku"));
        loop.setStartupModelFlag("gpt-4");      // 非法
        loop.setEnvForTest("sonnet");            // env 已删除, 不再作为回落层
        assertThat(loop.getModelForCall()).isNull();
    }

    @Test
    @DisplayName("R8-19: setModelAllowlist(null/empty) → 关闭校验, 恢复 'all allowed' 语义")
    void emptyAllowlistDisablesValidation() {
        // WHY: P2-1 修复 — setModelAllowlist(null) 与 CC availableModels=[] 不同
        // (CC empty list blocks all, Java empty list = 关闭校验). 这是安全默认:
        // 误注入空 list 不会拒绝所有 LLM call, 而是关闭校验.
        loop.setModelAllowlist(List.of("opus"));  // 仅 opus
        loop.setRuntimeModelOverride("gpt-4");     // 非法
        assertThat(loop.getModelForCall()).isNull();

        loop.setModelAllowlist(null);             // 关闭校验
        assertThat(loop.getModelForCall()).isEqualTo("gpt-4");
    }

    @Test
    @DisplayName("R8-20: 段边界匹配 — 'opus' 不匹配 'opusplan' 假阳性 (CC prefixMatchesModel)")
    void segmentBoundaryPreventsFalsePositives() {
        // WHY: P2-1 修复 — CC modelAllowlist.ts:30-32 prefixMatchesModel 显式按
        // 段边界匹配, 避免 "opus" 误匹配 "opusplan" / "opus-4-5-50" 等.
        // Java 端用 prefixMatchesModel 同款逻辑.
        loop.setModelAllowlist(List.of("opus"));
        loop.setRuntimeModelOverride("opusplan");  // 不应通过 (前缀匹配但段边界失败)
        assertThat(loop.getModelForCall()).isNull();

        loop.setRuntimeModelOverride("opus-4");    // 应通过 (段边界匹配)
        assertThat(loop.getModelForCall()).isEqualTo("opus-4");
    }

    /**
     * Testable LlmAgentLoop 子类 · 提供 env var 注入钩子 (同 R4 重写版本).
     */
    static class TestableLlmAgentLoop extends LlmAgentLoop {
        private String envForTest = "";

        TestableLlmAgentLoop(com.nexusai.infra.llm.LlmProviderFactory factory) {
            super(factory);
        }

        void setEnvForTest(String env) {
            this.envForTest = env == null ? "" : env;
        }

        @Override
        protected String readEnvModel() {
            return envForTest;
        }
    }
}