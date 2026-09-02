package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.source.LocalSettingsLoader;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.permission.source.ProjectSettingsLoader;
import com.nexusai.application.agent.permission.source.SettingsJsonParser;
import com.nexusai.application.agent.permission.source.UserSettingsLoader;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 增量写盘测试 · 对齐 CC {@code persistPermissionUpdate}（PermissionUpdate.ts:222-342）
 * 6 case per-type 分发：各更新类型落盘后<b>仅影响对应桶</b>，未知 key 保留。
 *
 * <p><b>WHY（WF1-02）</b>: 旧 {@code collectRules} 整源收集重写会"改一个桶抹掉其它桶"——
 * 例如 addRules 到 allow 桶会把 deny/ask/additionalDirectories/defaultMode 全部按 ctx 重写。
 * CC 是增量 per-type 写盘（单字段 patch）。本测试锁定增量语义：每个 type 只 patch 对应字段，
 * 其余桶与未知 key 逐字不变。
 */
@DisplayName("[WF1-02] Persister 增量写盘：per-type 仅影响对应桶，未知 key 保留")
class PermissionUpdatePersisterIncrementalTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SettingsJsonParser PARSER =
        new SettingsJsonParser(JSON, new PermissionRuleValueParser());

    private static final String RICH_SETTINGS = """
        {
          "hooks": { "PreToolUse": [ { "matcher": "Bash" } ] },
          "env": { "FOO": "bar" },
          "permissions": {
            "allow": [ "Read", "Bash(npm run test)" ],
            "deny": [ "Bash(rm -rf /*)" ],
            "ask": [ "Edit" ],
            "additionalDirectories": [ "/workspace/a" ],
            "defaultMode": "default"
          },
          "unknownTop": { "x": 1 }
        }
        """;

    private static PermissionRule rule(PermissionBehavior behavior, String toolName, String content) {
        return new PermissionRule(
            PermissionRuleSource.PROJECT_SETTINGS, behavior,
            content == null ? PermissionRuleValue.wholeTool(toolName)
                : PermissionRuleValue.withContent(toolName, content));
    }

    /** 装配 persister，写盘目标是 {@code <projectHome>/.nexusai/settings.json}。 */
    private static PermissionUpdatePersister persister(Path projectHome) {
        return new PermissionUpdatePersister(
            new UserSettingsLoader(PARSER),
            new ProjectSettingsLoader(PARSER, () -> projectHome.toString()),
            new LocalSettingsLoader(PARSER, () -> projectHome.toString()),
            new PermissionRuleValueParser());
    }

    private static Path writeSettings(Path projectHome) throws IOException {
        Path settingsFile = projectHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, RICH_SETTINGS);
        return settingsFile;
    }

    private static JsonNode permissions(Path settingsFile) throws IOException {
        return JSON.readTree(settingsFile.toFile()).path("permissions");
    }

    @Test
    @DisplayName("addRules 仅追加 allow 桶：deny/ask/additionalDirectories/defaultMode 不变，未知 key 保留")
    void addRules_onlyTouchesAllowBucket(@TempDir Path projectHome) throws IOException {
        Path file = writeSettings(projectHome);

        persister(projectHome).persist(new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.PROJECT_SETTINGS,
            List.of(rule(PermissionBehavior.ALLOW, "Bash", "npm publish:*")),
            PermissionBehavior.ALLOW));

        JsonNode root = JSON.readTree(file.toFile());
        JsonNode p = root.path("permissions");
        assertThat(p.path("allow"))
            .extracting(JsonNode::asText)
            .containsExactly("Read", "Bash(npm run test)", "Bash(npm publish:*)");
        assertThat(p.path("deny"))
            .extracting(JsonNode::asText).containsExactly("Bash(rm -rf /*)");
        assertThat(p.path("ask"))
            .extracting(JsonNode::asText).containsExactly("Edit");
        assertThat(p.path("additionalDirectories"))
            .extracting(JsonNode::asText).containsExactly("/workspace/a");
        assertThat(p.path("defaultMode").asText()).isEqualTo("default");
        // 未知 key 保留
        assertThat(root.path("hooks").isObject()).isTrue();
        assertThat(root.path("env").path("FOO").asText()).isEqualTo("bar");
        assertThat(root.path("unknownTop").path("x").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("addRules 去重：已存在规则（roundtrip 归一化）不重复追加")
    void addRules_dedupNormalized(@TempDir Path projectHome) throws IOException {
        Path file = writeSettings(projectHome);

        // "Bash(npm run test)" 已在 allow 桶 → 归一化后判定重复，跳过写盘
        persister(projectHome).persist(new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.PROJECT_SETTINGS,
            List.of(rule(PermissionBehavior.ALLOW, "Bash", "npm run test")),
            PermissionBehavior.ALLOW));

        JsonNode p = permissions(file);
        assertThat(p.path("allow"))
            .extracting(JsonNode::asText)
            .containsExactly("Read", "Bash(npm run test)");
    }

    @Test
    @DisplayName("removeRules 仅删指定单桶（deny）：allow/ask 不变")
    void removeRules_onlyTouchesDenyBucket(@TempDir Path projectHome) throws IOException {
        Path file = writeSettings(projectHome);

        persister(projectHome).persist(new PermissionUpdate.RemoveRules(
            PermissionUpdate.Destination.PROJECT_SETTINGS,
            List.of(rule(PermissionBehavior.DENY, "Bash", "rm -rf /*")),
            PermissionBehavior.DENY));

        JsonNode p = permissions(file);
        assertThat(p.path("deny")).isEmpty();
        assertThat(p.path("allow"))
            .extracting(JsonNode::asText)
            .containsExactly("Read", "Bash(npm run test)");
        assertThat(p.path("ask"))
            .extracting(JsonNode::asText).containsExactly("Edit");
    }

    @Test
    @DisplayName("replaceRules 仅整桶替换 ask：allow/deny 不变")
    void replaceRules_onlyTouchesAskBucket(@TempDir Path projectHome) throws IOException {
        Path file = writeSettings(projectHome);

        persister(projectHome).persist(new PermissionUpdate.ReplaceRules(
            PermissionUpdate.Destination.PROJECT_SETTINGS,
            List.of(rule(PermissionBehavior.ASK, "Write", null)),
            PermissionBehavior.ASK));

        JsonNode p = permissions(file);
        assertThat(p.path("ask"))
            .extracting(JsonNode::asText).containsExactly("Write");
        assertThat(p.path("allow"))
            .extracting(JsonNode::asText)
            .containsExactly("Read", "Bash(npm run test)");
        assertThat(p.path("deny"))
            .extracting(JsonNode::asText).containsExactly("Bash(rm -rf /*)");
    }

    @Test
    @DisplayName("setMode 仅写 defaultMode：allow/deny/ask/additionalDirectories 不变")
    void setMode_onlyWritesDefaultMode(@TempDir Path projectHome) throws IOException {
        Path file = writeSettings(projectHome);

        persister(projectHome).persist(new PermissionUpdate.SetMode(
            PermissionUpdate.Destination.PROJECT_SETTINGS, PermissionMode.PLAN));

        JsonNode p = permissions(file);
        assertThat(p.path("defaultMode").asText()).isEqualTo("plan");
        assertThat(p.path("allow"))
            .extracting(JsonNode::asText)
            .containsExactly("Read", "Bash(npm run test)");
        assertThat(p.path("deny"))
            .extracting(JsonNode::asText).containsExactly("Bash(rm -rf /*)");
        assertThat(p.path("ask"))
            .extracting(JsonNode::asText).containsExactly("Edit");
        assertThat(p.path("additionalDirectories"))
            .extracting(JsonNode::asText).containsExactly("/workspace/a");
    }

    @Test
    @DisplayName("setMode 内部 AUTO/BUBBLE → 降级写 defaultMode=default（对齐 CC toExternalPermissionMode）")
    void setMode_internalModesDegradeToDefault(@TempDir Path projectHome) throws IOException {
        Path file = writeSettings(projectHome);

        persister(projectHome).persist(new PermissionUpdate.SetMode(
            PermissionUpdate.Destination.PROJECT_SETTINGS, PermissionMode.AUTO));

        JsonNode p = permissions(file);
        assertThat(p.path("defaultMode").asText()).isEqualTo("default");
    }

    @Test
    @DisplayName("addDirectories 仅追加 additionalDirectories：规则桶与 defaultMode 不变")
    void addDirectories_onlyTouchesAdditionalDirectories(@TempDir Path projectHome) throws IOException {
        Path file = writeSettings(projectHome);

        persister(projectHome).persist(new PermissionUpdate.AddDirectories(
            PermissionUpdate.Destination.PROJECT_SETTINGS, List.of("/workspace/b")));

        JsonNode p = permissions(file);
        assertThat(p.path("additionalDirectories"))
            .extracting(JsonNode::asText)
            .containsExactly("/workspace/a", "/workspace/b");
        assertThat(p.path("allow"))
            .extracting(JsonNode::asText)
            .containsExactly("Read", "Bash(npm run test)");
        assertThat(p.path("defaultMode").asText()).isEqualTo("default");
    }

    @Test
    @DisplayName("removeDirectories 仅删 additionalDirectories 指定目录")
    void removeDirectories_onlyTouchesAdditionalDirectories(@TempDir Path projectHome) throws IOException {
        Path file = writeSettings(projectHome);

        persister(projectHome).persist(new PermissionUpdate.RemoveDirectories(
            PermissionUpdate.Destination.PROJECT_SETTINGS, List.of("/workspace/a")));

        JsonNode p = permissions(file);
        assertThat(p.path("additionalDirectories")).isEmpty();
        assertThat(p.path("allow"))
            .extracting(JsonNode::asText)
            .containsExactly("Read", "Bash(npm run test)");
    }

    @Test
    @DisplayName("非可持久化 destination（SESSION）→ 不写盘")
    void nonPersistableDestination_skips(@TempDir Path projectHome) throws IOException {
        Path file = writeSettings(projectHome);
        String before = Files.readString(file);

        persister(projectHome).persist(new PermissionUpdate.AddRules(
            PermissionUpdate.Destination.SESSION,
            List.of(rule(PermissionBehavior.ALLOW, "Bash", "git status")),
            PermissionBehavior.ALLOW));

        assertThat(Files.readString(file)).isEqualTo(before);
    }
}
