package com.nexusai.application.agent.permission.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionBehavior;
import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.PermissionRuleValue;
import com.nexusai.application.agent.permission.PermissionUpdate;
import com.nexusai.application.agent.permission.PermissionUpdatePersister;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SettingsJsonParser 合并写回测试 · 对齐 CC {@code updateSettingsForSource}
 * （settings.ts:416-524，mergeWith 数组整体替换语义 :473-495）。
 *
 * <p><b>WHY（Session S01 / O16 / P0 #4）</b>: 旧 {@code serialize(List)} 仅输出
 * {@code {"permissions": {...}}}，整文件覆盖写会抹除 settings.json 中 hooks/env 等
 * 全部非权限 key。本测试锁定读-改-写合并语义：
 * <ol>
 *   <li>非权限 key（hooks/env/未知 key）内容与顺序全部保留</li>
 *   <li>重复写回幂等（二次输出字节一致）</li>
 *   <li>空权限桶写 {@code []}（对齐 CC removeRules 清空语义，PermissionUpdate.ts:289-293，
 *       避免旧规则残留复活）</li>
 *   <li>JSON 语法损坏 → 抛异常不覆盖写（对齐 CC settings.ts:453-463）</li>
 *   <li>生产写盘链（Persister → loader.save → serialize 合并）端到端保留 hooks</li>
 * </ol>
 */
class SettingsJsonMergeWriteBackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SettingsJsonParser PARSER =
        new SettingsJsonParser(MAPPER, new PermissionRuleValueParser());

    @TempDir
    Path tempDir;

    /** 含 hooks/env/未知 key 的典型 settings.json 内容（顺序敏感：hooks → env → permissions → unknown）。 */
    private static final String TYPICAL_SETTINGS = """
        {
          "hooks": {
            "PreToolUse": [
              {
                "matcher": "Bash",
                "hooks": [ { "type": "command", "command": "echo hi" } ]
              }
            ]
          },
          "env": {
            "FOO": "bar"
          },
          "permissions": {
            "allow": [ "Bash" ]
          },
          "unknownTop": {
            "nested": [ 1, 2, 3 ],
            "flag": true
          }
        }
        """;

    private static PermissionRule rule(PermissionBehavior behavior, String toolName, String content) {
        return new PermissionRule(
            PermissionRuleSource.USER_SETTINGS, behavior,
            content == null ? PermissionRuleValue.wholeTool(toolName)
                : PermissionRuleValue.withContent(toolName, content));
    }

    @Test
    @DisplayName("合并写回: hooks/env/未知 key 内容与顺序保留, 仅替换权限桶")
    void nonPermissionKeys_preserved_contentAndOrder() throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, TYPICAL_SETTINGS);

        List<PermissionRule> rules = List.of(
            rule(PermissionBehavior.ALLOW, "Bash", "npm publish:*"),
            rule(PermissionBehavior.ASK, "Edit", null));

        String merged = PARSER.serialize(file, rules);

        JsonNode root = MAPPER.readTree(merged);
        // 非权限 key 全部保留
        assertThat(root.has("hooks")).isTrue();
        assertThat(root.has("env")).isTrue();
        assertThat(root.has("unknownTop")).isTrue();
        assertThat(root.path("env").path("FOO").asText()).isEqualTo("bar");
        assertThat(root.path("unknownTop").path("flag").asBoolean()).isTrue();
        assertThat(root.path("hooks").path("PreToolUse").isArray()).isTrue();
        // 仅替换权限桶
        assertThat(root.path("permissions").path("allow"))
            .hasSize(1).extracting(JsonNode::asText).containsExactly("Bash(npm publish:*)");
        assertThat(root.path("permissions").path("ask"))
            .hasSize(1).extracting(JsonNode::asText).containsExactly("Edit");
        assertThat(root.path("permissions").path("deny")).isEmpty();
        // 顶层 key 顺序不被破坏: hooks → env → permissions → unknownTop
        int hooks = merged.indexOf("\"hooks\"");
        int env = merged.indexOf("\"env\"");
        int permissions = merged.indexOf("\"permissions\"");
        int unknown = merged.indexOf("\"unknownTop\"");
        assertThat(hooks).isLessThan(env);
        assertThat(env).isLessThan(permissions);
        assertThat(permissions).isLessThan(unknown);
        // 结尾换行（对齐 CC jsonStringify(updated, null, 2) + '\\n'）
        assertThat(merged).endsWith("\n");
    }

    @Test
    @DisplayName("幂等: 同一输入二次写回输出字节一致")
    void idempotent_secondWriteSameOutput() throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, TYPICAL_SETTINGS);

        List<PermissionRule> rules = List.of(
            rule(PermissionBehavior.ALLOW, "Bash", null),
            rule(PermissionBehavior.DENY, "Bash", "rm -rf /*"));

        String first = PARSER.serialize(file, rules);
        String second = PARSER.serialize(file, rules);
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("未知 key 原样保留: 嵌套对象/数组/数值/布尔值逐字不变")
    void unknownKeys_preservedVerbatim() throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, TYPICAL_SETTINGS);
        JsonNode originalUnknown = MAPPER.readTree(TYPICAL_SETTINGS).path("unknownTop");

        String merged = PARSER.serialize(file, List.of(rule(PermissionBehavior.ALLOW, "Bash", null)));
        JsonNode mergedUnknown = MAPPER.readTree(merged).path("unknownTop");

        assertThat(mergedUnknown).isEqualTo(originalUnknown);
        assertThat(mergedUnknown.path("nested")).hasSize(3);
    }

    @Test
    @DisplayName("空权限桶: 规则列表为空 → allow/deny/ask 全部写 []（不残留旧规则）")
    void emptyRules_writeEmptyBuckets() throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, TYPICAL_SETTINGS);

        String merged = PARSER.serialize(file, List.of());

        JsonNode root = MAPPER.readTree(merged);
        assertThat(root.path("permissions").path("allow")).isEmpty();
        assertThat(root.path("permissions").path("deny")).isEmpty();
        assertThat(root.path("permissions").path("ask")).isEmpty();
        // 非权限 key 仍保留
        assertThat(root.path("hooks").isObject()).isTrue();
        assertThat(root.path("env").path("FOO").asText()).isEqualTo("bar");
    }

    @Test
    @DisplayName("permissions 内非桶 key（additionalDirectories/defaultMode）保留")
    void permissions_extraKeys_preserved() throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, """
            {
              "permissions": {
                "allow": [ "Bash" ],
                "additionalDirectories": [ "/workspace/a" ],
                "defaultMode": "plan"
              }
            }
            """);

        String merged = PARSER.serialize(file, List.of(rule(PermissionBehavior.ALLOW, "Bash", null)));

        JsonNode permissions = MAPPER.readTree(merged).path("permissions");
        assertThat(permissions.path("additionalDirectories"))
            .extracting(JsonNode::asText).containsExactly("/workspace/a");
        assertThat(permissions.path("defaultMode").asText()).isEqualTo("plan");
        assertThat(permissions.path("allow")).hasSize(1);
    }

    @Test
    @DisplayName("文件不存在 → 以空对象为基底创建权限桶（对齐 CC mergeWith existing || {}）")
    void missingFile_createsPermissionsOnly() {
        Path file = tempDir.resolve("not-exist").resolve("settings.json");

        String merged = PARSER.serialize(file, List.of(rule(PermissionBehavior.ALLOW, "Bash", null)));

        assertThat(merged).contains("\"permissions\"");
        assertThat(merged).contains("\"allow\"");
        assertThat(merged).contains("\"Bash\"");
        assertThat(merged).endsWith("\n");
    }

    @Test
    @DisplayName("permissions 非 object → 替换为新对象（对齐 CC mergeWith 默认合并）")
    void nonObjectPermissions_replaced() throws IOException {
        Path file = tempDir.resolve("settings.json");
        Files.writeString(file, """
            {
              "permissions": "not-an-object",
              "env": { "KEEP": "1" }
            }
            """);

        String merged = PARSER.serialize(file, List.of(rule(PermissionBehavior.ALLOW, "Bash", null)));

        JsonNode root = MAPPER.readTree(merged);
        assertThat(root.path("permissions").isObject()).isTrue();
        assertThat(root.path("permissions").path("allow"))
            .extracting(JsonNode::asText).containsExactly("Bash");
        assertThat(root.path("env").path("KEEP").asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("JSON 语法损坏 → 抛异常且不覆盖写（对齐 CC settings.ts:453-463）")
    void invalidJson_throwsWithoutOverwrite() throws IOException {
        Path file = tempDir.resolve("settings.json");
        String broken = "{\"hooks\": {\"broken\": ";
        Files.writeString(file, broken);

        assertThatThrownBy(() -> PARSER.serialize(file, List.of(rule(PermissionBehavior.ALLOW, "Bash", null))))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Invalid JSON syntax");
        // 文件内容未被覆盖
        assertThat(Files.readString(file)).isEqualTo(broken);
    }

    @Test
    @DisplayName("生产写盘链端到端: Persister → UserSettingsLoader 增量 addRules（仅追加 allow 桶，保留 hooks/未知 key）")
    void userSettingsLoader_save_preservesHooks() throws IOException {
        Path userHome = tempDir.resolve("user");
        Files.createDirectories(userHome);
        Path settingsFile = userHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, TYPICAL_SETTINGS);

        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", userHome.toString());
        try {
            PermissionUpdatePersister persister = new PermissionUpdatePersister(
                new UserSettingsLoader(PARSER),
                new ProjectSettingsLoader(PARSER, () -> tempDir.resolve("project").toString()),
                new LocalSettingsLoader(PARSER, () -> tempDir.resolve("project").toString()),
                new PermissionRuleValueParser());

            PermissionRule npmPublish = rule(PermissionBehavior.ALLOW, "Bash", "npm publish:*");
            persister.persist(
                new PermissionUpdate.AddRules(
                    PermissionUpdate.Destination.USER_SETTINGS, List.of(npmPublish), PermissionBehavior.ALLOW));

            JsonNode root = MAPPER.readTree(settingsFile.toFile());
            // hooks/env/未知 key 保留
            assertThat(root.path("hooks").isObject()).isTrue();
            assertThat(root.path("env").path("FOO").asText()).isEqualTo("bar");
            assertThat(root.path("unknownTop").isObject()).isTrue();
            // 增量语义：仅 allow 桶追加（去重），deny/ask 桶原样不动（不存在）
            assertThat(root.path("permissions").path("allow"))
                .extracting(JsonNode::asText)
                .containsExactly("Bash", "Bash(npm publish:*)");
            assertThat(root.path("permissions").has("deny")).isFalse();
            assertThat(root.path("permissions").has("ask")).isFalse();
        } finally {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    @DisplayName("生产写盘链端到端: Persister → ProjectSettingsLoader 增量 addRules（仅追加 ask 桶，保留未知 key）")
    void projectSettingsLoader_save_preservesUnknownKeys() throws IOException {
        Path projectHome = tempDir.resolve("project");
        Path settingsFile = projectHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, TYPICAL_SETTINGS);

        PermissionUpdatePersister persister = new PermissionUpdatePersister(
            new UserSettingsLoader(PARSER),
            new ProjectSettingsLoader(PARSER, () -> projectHome.toString()),
            new LocalSettingsLoader(PARSER, () -> projectHome.toString()),
            new PermissionRuleValueParser());

        PermissionRule askEdit = rule(PermissionBehavior.ASK, "Edit", null);
        persister.persist(
            new PermissionUpdate.AddRules(
                PermissionUpdate.Destination.PROJECT_SETTINGS, List.of(askEdit), PermissionBehavior.ASK));

        JsonNode root = MAPPER.readTree(settingsFile.toFile());
        assertThat(root.path("hooks").isObject()).isTrue();
        assertThat(root.path("unknownTop").isObject()).isTrue();
        // 增量语义：仅 ask 桶新增，allow 桶原样保留
        assertThat(root.path("permissions").path("ask"))
            .extracting(JsonNode::asText).containsExactly("Edit");
        assertThat(root.path("permissions").path("allow"))
            .extracting(JsonNode::asText).containsExactly("Bash");
    }
}
