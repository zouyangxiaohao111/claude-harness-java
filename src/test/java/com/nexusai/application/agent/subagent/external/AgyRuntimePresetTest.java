package com.nexusai.application.agent.subagent.external;

import com.nexusai.application.agent.subagent.AgentDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * agy（Google Antigravity CLI）接入回归。
 *
 * <p><b>WHY 锁这些</b>（2026-11-26 真机实测口径）：
 * <ul>
 *   <li>参数写错 CLI <b>不报错、只是静默失效</b> —— 用户会以为「选了 high 没效果」，
 *       所以 {@code --effort}/{@code --model} 的拼法必须逐字锁死；</li>
 *   <li>{@code agy models} 的输出是 {@code modelId<TAB>Display Name}，按空白切会把
 *       显示名切碎；未登录时是错误行 —— 两种情况都必须不产出假模型 id；</li>
 *   <li>agy 访问 Google 必须走应用配置的代理（本机直连不通），但代理未配置时
 *       <b>不能注入空串</b>（部分 CLI 会当成「显式直连」覆盖自身默认）。</li>
 * </ul>
 *
 * <p>⛔ 本测试不发起真实网络请求、不执行 agy、不读任何凭据：模型输出用真实样本 fixture，
 * argv 用临时空文件占位（只做路径解析，不 spawn）。
 */
@DisplayName("[external-agent] agy 预设 / argv / 模型解析 / 代理 env")
class AgyRuntimePresetTest {

    private static final ExternalRuntimeCatalog.RuntimePreset AGY =
            ExternalRuntimeCatalog.find("agy");

    @AfterEach
    void clearRuntimeOverrides() {
        // 本类会替换运行时配置快照（纯内存），跑完恢复，避免污染同 JVM 的其它用例
        ExternalRuntimeEnvStore.replace(null);
    }

    @Test
    @DisplayName("预设存在且字段与真机一致（agy --help / agy models 实测）")
    void presetMatchesRealCli() {
        assertThat(AGY).as("agy 预设必须能被 find 到").isNotNull();
        assertThat(AGY.displayName()).isEqualTo("Antigravity CLI");
        assertThat(AGY.bin()).isEqualTo("agy");
        assertThat(AGY.fallbackBins()).containsExactly("agy");
        assertThat(AGY.binEnv()).isEqualTo("AGY_BIN");
        // -p <prompt> --output-format stream-json：任务在 argv 里（探测层才能立刻关 stdin）
        assertThat(AGY.args()).containsExactly("-p", "{prompt}", "--output-format", "stream-json");
        assertThat(AGY.streamFormat()).isEqualTo("jsonl");
        assertThat(AGY.listModelsArgs()).containsExactly("models");
        assertThat(AGY.apiKeyEnvs()).containsExactly("GEMINI_API_KEY");
        assertThat(AGY.reasoningOptions()).containsExactly("low", "medium", "high", "xhigh", "max");
        assertThat(AGY.reasoningStyle())
                .isEqualTo(ExternalRuntimeCatalog.ReasoningStyle.AGY_EFFORT);
        assertThat(AGY.supportsReasoning()).isTrue();
        // 27 条实测模型 id（顺序 = agy models 输出顺序）
        assertThat(AGY.fallbackModels()).hasSize(27);
        assertThat(AGY.fallbackModels()).startsWith("gemini-4-neon-high")
                .endsWith("gpt-oss-120b-medium")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("推理强度拼 --effort（不是 --thinking / --variant / -c）")
    void reasoningUsesEffortFlag() {
        assertThat(ExternalRuntimeCatalog.reasoningArgs(AGY, "high"))
                .containsExactly("--effort", "high");
        assertThat(ExternalRuntimeCatalog.reasoningArgs(AGY, "max"))
                .containsExactly("--effort", "max");
    }

    @Test
    @DisplayName("default / 空 / 非法档位 ⇒ 不追加参数")
    void reasoningDefaultsAddNothing() {
        assertThat(ExternalRuntimeCatalog.reasoningArgs(AGY, "default")).isEmpty();
        assertThat(ExternalRuntimeCatalog.reasoningArgs(AGY, "")).isEmpty();
        assertThat(ExternalRuntimeCatalog.reasoningArgs(AGY, null)).isEmpty();
        assertThat(ExternalRuntimeCatalog.reasoningArgs(AGY, "high; rm -rf /")).isEmpty();
    }

    @Test
    @DisplayName("模型拼 --model（agy --help 实测有该旗标）")
    void modelUsesModelFlag() {
        assertThat(ExternalRuntimeCatalog.modelArgs(AGY, "gemini-4-neon-high"))
                .containsExactly("--model", "gemini-4-neon-high");
    }

    @Test
    @DisplayName("完整 argv：模型 + 推理档位都进 argv，且 prompt 占位仍在")
    void resolvedArgvCarriesModelAndEffort() throws Exception {
        Path fakeBin = Files.createTempFile("agy-fake-", ".exe");
        try {
            var def = AgentDefinition.ExternalAgentDefinition.builder(
                            "agy-external", "测试用 agy", "built-in", "")
                    .runtime("agy")
                    .bin(fakeBin.toString())
                    .build();
            ExternalRuntimeEnvStore.replace(Map.of("agy", new ExternalRuntimeEnvStore.Overlay(
                    null, null, Map.of(), fakeBin.toString(), "gemini-4-neon-high", "high")));

            ExternalRuntimeSpec.Resolution r =
                    ExternalRuntimeSpec.tryResolve(def, "gemini-4-neon-high", Path.of("."));
            assertThat(r.isAvailable()).as("可执行文件存在 ⇒ 必须解析成功: %s", r.unavailableReason()).isTrue();
            List<String> argv = r.spec().buildArgv("你好");
            assertThat(argv).contains("-p", "你好", "--output-format", "stream-json",
                    "--model", "gemini-4-neon-high",
                    "--effort", "high");
            // ⛔ prompt 占位必须被替换掉（残留 {prompt} 会把字面量发给 CLI）
            assertThat(argv).noneMatch(a -> a.contains("{prompt}"));
        } finally {
            Files.deleteIfExists(fakeBin);
        }
    }

    @Test
    @DisplayName("没配推理强度 ⇒ argv 里不出现 --effort（不能塞一个 CLI 会静默忽略的默认值）")
    void noEffortWhenUnset() throws Exception {
        Path fakeBin = Files.createTempFile("agy-fake-", ".exe");
        try {
            var def = AgentDefinition.ExternalAgentDefinition.builder(
                            "agy-external", "测试用 agy", "built-in", "")
                    .runtime("agy")
                    .bin(fakeBin.toString())
                    .build();
            ExternalRuntimeEnvStore.replace(Map.of("agy", new ExternalRuntimeEnvStore.Overlay(
                    null, null, Map.of(), fakeBin.toString(), null, null)));

            ExternalRuntimeSpec.Resolution r =
                    ExternalRuntimeSpec.tryResolve(def, null, Path.of("."));
            assertThat(r.isAvailable()).isTrue();
            assertThat(r.spec().argv()).doesNotContain("--effort", "--model");
        } finally {
            Files.deleteIfExists(fakeBin);
        }
    }

    @Test
    @DisplayName("代理配置两态：配了 ⇒ 四个代理变量；没配 ⇒ 空 map（不注入空串）")
    void proxyEnvTwoStates() {
        Map<String, String> on = ExternalRuntimeSpec.proxyEnvFrom("http://127.0.0.1:7897");
        assertThat(on).containsEntry("HTTP_PROXY", "http://127.0.0.1:7897")
                .containsEntry("HTTPS_PROXY", "http://127.0.0.1:7897")
                .containsEntry("http_proxy", "http://127.0.0.1:7897")
                .containsEntry("https_proxy", "http://127.0.0.1:7897");

        assertThat(ExternalRuntimeSpec.proxyEnvFrom(null)).isEmpty();
        assertThat(ExternalRuntimeSpec.proxyEnvFrom("  ")).isEmpty();
    }

    @Test
    @DisplayName("outboundProxyEnv 不抛；有值时必须成对给出大小写两种代理变量")
    void outboundProxyEnvIsConsistent() {
        Map<String, String> env = ExternalRuntimeSpec.outboundProxyEnv();
        if (env.isEmpty()) {
            return; // 未配置代理 = 合法状态（本机 CI 常见）
        }
        assertThat(env).containsKeys("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy");
        assertThat(env.get("HTTP_PROXY")).startsWith("http://").contains(":");
    }

    @Test
    @DisplayName("agy models 真实样本：27 个 id，取 TAB 前第一列，不混入显示名")
    void parsesRealModelsFixture() throws Exception {
        String stdout = readResource("/agy/models.tsv");
        List<ExternalRuntimeProbe.ModelInfo> models = ExternalRuntimeProbe.parseAgyModels(stdout);

        assertThat(models).hasSize(27);
        assertThat(models.get(0).id()).isEqualTo("gemini-4-neon-high");
        assertThat(models.get(models.size() - 1).id()).isEqualTo("gpt-oss-120b-medium");
        assertThat(models).extracting(ExternalRuntimeProbe.ModelInfo::id)
                .doesNotContain("Gemini 4 Neon (High)", "GPT-OSS 120B (Medium)")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("未登录/错误输出 ⇒ 不产出假模型；无 bin ⇒ 空列表（controller 回落 fallbackModels）")
    void unauthenticatedFallsBack() {
        assertThat(ExternalRuntimeProbe.parseAgyModels("Error: Please sign in to continue.\n"))
                .isEmpty();
        // bin 缺失时 listModels 直接返回空列表（不 spawn），绝不抛
        assertThat(ExternalRuntimeProbe.listModels(AGY, null, Map.of())).isEmpty();
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = AgyRuntimePresetTest.class.getResourceAsStream(path)) {
            assertThat(in).as("测试资源缺失: %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}