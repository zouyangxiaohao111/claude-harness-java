package com.nexusai.application.agent.permission.classifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [prompt-align TOOLS-02] 分类器 autoMode 四源合并测试 · 对齐 CC
 * {@code getAutoModeConfig}（settings.ts:936-982）。
 *
 * <p><b>WHY (意图验证)</b>: CC 真源
 * <pre>
 *   for (const source of ['userSettings','localSettings','flagSettings','policySettings']) {
 *     const settings = getSettingsForSource(source)
 *     if (!settings) continue
 *     const result = schema.safeParse(settings.autoMode)
 *     if (result.success) {
 *       if (result.data.allow) allow.push(...result.data.allow)
 *       ...
 *     }
 *   }
 * </pre>
 * 按序合并 [user/local/flag/policy] 四源（projectSettings 显式排除防 RCE）；源缺失
 * lenient 跳过；全空 → null。flagSettings Java web 恒空（FlagSettingsLoader:43 先例）。
 */
class YoloAutoModeConfigMergeTest {

    @TempDir
    Path tempDir;

    private final YoloPromptBuilder builder = new YoloPromptBuilder();

    private Path writeSettings(String fileName, String content) throws Exception {
        Path p = tempDir.resolve(fileName);
        Files.writeString(p, content);
        return p;
    }

    @Test
    @DisplayName("TOOLS-02-1: 四源按序合并 allow/soft_deny/environment（user + local）")
    void userAndLocal_mergedInOrder() throws Exception {
        Path user = writeSettings("settings.json",
            "{\"autoMode\":{\"allow\":[\"git status\"],\"soft_deny\":[\"rm -rf\"],\"environment\":[\"prod\"]}}");
        Path local = writeSettings("settings.local.json",
            "{\"autoMode\":{\"allow\":[\"git diff\",\"npm test\"],\"environment\":[\"staging\"]}}");
        builder.setUserSettingsPath(user);
        builder.setLocalSettingsPath(local);

        YoloPromptBuilder.AutoModeRules rules = builder.readAutoModeConfig();

        assertThat(rules).isNotNull();
        assertThat(rules.allow()).as("user 先 local 后，顺序合并").containsExactly("git status", "git diff", "npm test");
        assertThat(rules.softDeny()).as("soft_deny 仅 user 源").containsExactly("rm -rf");
        assertThat(rules.environment()).containsExactly("prod", "staging");
    }

    @Test
    @DisplayName("TOOLS-02-2: local 缺失 → user 单独生效（lenient 跳过，CC :954-956 if(!settings) continue）")
    void localMissing_userAlone() throws Exception {
        Path user = writeSettings("settings.json",
            "{\"autoMode\":{\"allow\":[\"git status\"]}}");
        builder.setUserSettingsPath(user);
        builder.setLocalSettingsPath(tempDir.resolve("no-such-settings.local.json"));

        YoloPromptBuilder.AutoModeRules rules = builder.readAutoModeConfig();

        assertThat(rules).isNotNull();
        assertThat(rules.allow()).containsExactly("git status");
    }

    @Test
    @DisplayName("TOOLS-02-3: 全空/源全缺 → null（等价 CC undefined，settings.ts:973-981）")
    void allEmpty_returnsNull() throws Exception {
        Path user = writeSettings("settings.json", "{\"autoMode\":{}}");
        builder.setUserSettingsPath(user);
        builder.setLocalSettingsPath(tempDir.resolve("no-such.local.json"));

        assertThat(builder.readAutoModeConfig()).isNull();
    }

    @Test
    @DisplayName("TOOLS-02-4: 单源损坏 → lenient 跳过（文件损坏不阻断其它源）")
    void corruptSource_lenientSkip() throws Exception {
        Path user = writeSettings("settings.json", "{\"autoMode\":{\"allow\":[\"ok\"]}}");
        Path corrupt = writeSettings("broken.local.json", "{ not json !!");
        builder.setUserSettingsPath(user);
        builder.setLocalSettingsPath(corrupt);

        YoloPromptBuilder.AutoModeRules rules = builder.readAutoModeConfig();

        assertThat(rules).isNotNull();
        assertThat(rules.allow()).containsExactly("ok");
    }
}
