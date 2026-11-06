package com.nexusai.application.agent.subagent.external;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行时目录（自动感知）回归。
 *
 * <p>WHY（真正的缺陷面）：这些测试锁的是**「探测结果与默认配置的一致性」**，
 * 不是「能返回一串 JSON」。历史上这类功能最常见的失败是：
 * <ul>
 *   <li>前端把「清单里有」当成「能用」⇒ 用户选了一个没装的 CLI；</li>
 *   <li>默认运行时配了但 catalog 里没有 ⇒ 派发时 AgentNotFound，用户在很晚才看到；</li>
 *   <li>预置的 argv 与真机不符 ⇒ 跑起来挂住等 stdin（真机踩过）。</li>
 * </ul>
 */
@DisplayName("[external-agent] ExternalRuntimeCatalog 自动感知")
class ExternalRuntimeCatalogTest {

    @Test
    @DisplayName("每个预设的 argv 都含 {prompt}（否则任务根本传不进 CLI）")
    void everyPresetCarriesPromptPlaceholder() {
        for (var p : ExternalRuntimeCatalog.presets()) {
            assertThat(p.args())
                    .as("runtime=%s 的 args 必须含 {prompt}", p.runtime())
                    .anyMatch(a -> a.contains("{prompt}"));
        }
    }

    @Test
    @DisplayName("每个预设都能构造出合法 agent 定义（build 不抛）")
    void everyPresetBuildsValidDefinition() {
        for (var p : ExternalRuntimeCatalog.presets()) {
            var def = ExternalRuntimeCatalog.toAgentDefinition(p);
            assertThat(def.runtime()).isEqualTo(p.runtime());
            assertThat(def.streamFormat()).isEqualTo(p.streamFormat());
            // 能力位默认保守：禁递归（外部 CLI 不能无限再派）
            assertThat(def.capabilities().depthLimit()).isZero();
        }
    }

    @Test
    @DisplayName("runtime id 唯一（重复会让 find 静默取第一个，配置选谁都一样）")
    void runtimeIdsAreUnique() {
        List<String> ids = ExternalRuntimeCatalog.presets().stream()
                .map(ExternalRuntimeCatalog.RuntimePreset::runtime).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("find 大小写不敏感且裁空白（设置在 DB 里被手改也不至于失配）")
    void findIsLenient() {
        assertThat(ExternalRuntimeCatalog.find("  CLAUDE ")).isNotNull();
        assertThat(ExternalRuntimeCatalog.find("codex")).isNotNull();
        assertThat(ExternalRuntimeCatalog.find("nope")).isNull();
        assertThat(ExternalRuntimeCatalog.find(null)).isNull();
    }

    @Test
    @DisplayName("detect 对每个预设都给齐字段（前端表格不会出现 undefined 单元格）")
    void detectReturnsCompleteRows() {
        var rows = ExternalRuntimeCatalog.detect();
        assertThat(rows).hasSameSizeAs(ExternalRuntimeCatalog.presets());
        for (var row : rows) {
            assertThat(row).containsKeys(
                    "runtime", "displayName", "notes",
                    "configDirEnv", "baseUrlEnv", "apiKeyEnvs", "binEnv",
                    "configurable", "supportsReasoning", "reasoningOptions",
                    "reasoningStyle", "fallbackModels", "supportsModelList",
                    "available", "resolvedPath", "apiKeyPresent");
            // available 与 resolvedPath 必须一致：说可用却没路径 = 前端点了必炸
            boolean available = (boolean) row.get("available");
            Object path = row.get("resolvedPath");
            if (available) {
                assertThat(path).as("available=true 必须有 resolvedPath").isNotNull();
            } else {
                assertThat(path).isNull();
            }
        }
    }

    @Test
    @DisplayName("探测到的是绝对路径（裸名交给 ProcessBuilder 在 Windows 会 CreateProcess error=2）")
    void resolvedPathIsAbsolute() {
        var rows = ExternalRuntimeCatalog.detect();
        for (var row : rows) {
            Object p = row.get("resolvedPath");
            if (p != null) {
                assertThat(java.nio.file.Path.of((String) p).isAbsolute())
                        .as("runtime=%s 解析出的路径必须是绝对路径: %s", row.get("runtime"), p)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("四个内置预设都在（claude/codex/opencode/pi）")
    void shipsExpectedPresets() {
        assertThat(ExternalRuntimeCatalog.presets())
                .extracting(ExternalRuntimeCatalog.RuntimePreset::runtime)
                .contains("claude", "codex", "opencode", "pi");
    }
}
