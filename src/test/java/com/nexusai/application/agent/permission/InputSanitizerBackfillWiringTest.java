package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [FIX-A backfill-observable] 生产可达验证 · 锁定 {@link InputSanitizer#backfill} 与 hook 匹配链。
 *
 * <p>WHY（规则九 · 验证意图）：backfill 修复前的假接线是「{@code InputSanitizer.backfill}
 * 存在但 0 调用方 + {@code Tool.backfillObservableInput} 恒 identity + hook 拿原始
 * {@code t.call.input()}」。本测试锁定两段关键链路，证明 backfill 生产可达且安全属性成立：
 * <ol>
 *   <li>{@link InputSanitizer#backfill}（原 0 调用方）经真实 {@link ReadFileTool} 的
 *       {@code backfillObservableInput} override 把 {@code ~}/相对路径绝对化；</li>
 *   <li>hook 内容匹配器（{@code HookMatcherEngine.prepareContentMatcher} 调用的
 *       {@code tool.preparePermissionMatcher(input)}）<b>只在 input 被 backfill 后命中</b>
 *       绝对路径 allowlist，对原始相对/~路径不命中 —— 即 CC「hooks/canUseTool 看 backfilled 版」
 *       语义的等价断言。</li>
 * </ol>
 *
 * <p>StreamingToolExecutor 的接线点（{@code backfilledInput = inputSanitizer.backfill(...)} 后
 * 把 backfilledInput 传入 {@code executePreToolUse}）由主 agent grep 复验（hard_metrics）：
 * {@code grep -n 'inputSanitizer.backfill' StreamingToolExecutor.java ≥1}。
 */
@DisplayName("FIX-A · InputSanitizer.backfill 生产可达 + hook 匹配链命中绝对路径")
class InputSanitizerBackfillWiringTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("backfill 经 ReadFileTool override 把 ~/相对路径绝对化（原 0 调用方已可达）")
    void backfillProducesAbsolutePath(@TempDir Path workspace) {
        PathGuard guard = new PathGuard(workspace);
        ReadFileTool tool = new ReadFileTool(guard);
        InputSanitizer sanitizer = new InputSanitizer();

        ObjectNode relative = JSON.createObjectNode();
        relative.put("file_path", "~/secret.txt");
        ObjectNode srcRelative = JSON.createObjectNode();
        srcRelative.put("file_path", "src/secret.txt");

        JsonNode backfilledTilde = sanitizer.backfill(tool, relative);
        JsonNode backfilledSrc = sanitizer.backfill(tool, srcRelative);

        assertThat(backfilledTilde.path("file_path").asText())
            .isEqualTo(Paths.get(System.getProperty("user.home")).resolve("secret.txt").normalize().toString());
        assertThat(backfilledSrc.path("file_path").asText())
            .isEqualTo(guard.workdir().resolve("src/secret.txt").normalize().toString());
        // 原 input 未被 in-place 改动（CC "original never mutated" 契约）
        assertThat(relative.path("file_path").asText()).isEqualTo("~/secret.txt");
        assertThat(srcRelative.path("file_path").asText()).isEqualTo("src/secret.txt");
    }

    @Test
    @DisplayName("hook 匹配器：backfill 后捕获绝对路径，原始输入仍捕获相对路径")
    void matcherHitsOnlyBackfilledInput(@TempDir Path workspace) {
        PathGuard guard = new PathGuard(workspace);
        ReadFileTool tool = new ReadFileTool(guard);
        InputSanitizer sanitizer = new InputSanitizer();

        String absPath = guard.workdir().resolve("src/secret.txt").normalize().toString();

        ObjectNode rawRelative = JSON.createObjectNode();
        rawRelative.put("file_path", "src/secret.txt");

        JsonNode backfilled = sanitizer.backfill(tool, rawRelative);

        // HookMatcherEngine.prepareContentMatcher 用 tool.preparePermissionMatcher(event.input())
        // preparePermissionMatcher 闭包捕获 input.file_path（原样字符串）供 matchWildcardPattern 比对。
        Predicate<String> matcherOnRaw = tool.preparePermissionMatcher(rawRelative);
        Predicate<String> matcherOnBackfilled = tool.preparePermissionMatcher(backfilled);

        // 未 backfill：捕获的是相对路径 → 相对串命中，绝对串不命中（安全假接线时 hook 拿原始相对路径）
        assertThat(matcherOnRaw.test("src/secret.txt")).isTrue();
        assertThat(matcherOnRaw.test(absPath)).isFalse();
        // backfill 后：捕获的是绝对路径 → 绝对串命中，相对串不命中（接线后 hook 能正确命中 allowlist）
        assertThat(matcherOnBackfilled.test(absPath)).isTrue();
        assertThat(matcherOnBackfilled.test("src/secret.txt")).isFalse();
    }

    @Test
    @DisplayName("backfill 对未 override 的工具（默认 identity）不做无谓变更（防御性 deepCopy 隔离）")
    void backfillIsolatedForIdentityTool() {
        InputSanitizer sanitizer = new InputSanitizer();
        // 匿名 Tool 未 override backfillObservableInput → 默认 identity
        com.nexusai.application.agent.tool.Tool identityTool = new com.nexusai.application.agent.tool.Tool() {
            @Override public String name() { return "Noop"; }
            @Override public String description() { return "noop"; }
            @Override public JsonNode inputSchema() { return JSON.createObjectNode(); }
            @Override public com.nexusai.application.agent.tool.AgentToolResult<?> execute(
                    com.nexusai.application.agent.tool.ToolUseBlock call) {
                return null;
            }
        };
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", "~/a.txt");

        JsonNode result = sanitizer.backfill(identityTool, input);

        // identity 工具不改变值，但 backfill 的防御性 deepCopy 隔离了原 input
        assertThat(result.path("file_path").asText()).isEqualTo("~/a.txt");
        assertThat(input.path("file_path").asText()).isEqualTo("~/a.txt");
        assertThat(result).isNotSameAs(input);
    }

    // ── [P4 OPD-WF4-BC-04] stripInternalFields 按 CC 收敛：仅 Bash 剥 _simulatedSedEdit ──

    @Test
    @DisplayName("[P4] Bash + _simulatedSedEdit → 剥离（CC toolExecution.ts:762-773）")
    void strip_removesSimulatedSedEditForBash() {
        InputSanitizer sanitizer = new InputSanitizer();
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "ls");
        input.put("_simulatedSedEdit", "injected");

        JsonNode result = sanitizer.stripInternalFields("Bash", input);

        assertThat(result.has("_simulatedSedEdit"))
            .as("CC toolExecution.ts:768-772 —— Bash 输入中 _simulatedSedEdit 被解构剥离（defense-in-depth）")
            .isFalse();
        assertThat(result.get("command").asText()).isEqualTo("ls");
    }

    @Test
    @DisplayName("[P4] Bash + _internal/__ 前缀 → 不再剥（旧 Java 超剥已移除）")
    void strip_keepsOtherInternalPrefixesForBash() {
        InputSanitizer sanitizer = new InputSanitizer();
        ObjectNode input = JSON.createObjectNode();
        input.put("command", "ls");
        input.put("_internal", "marker");
        input.put("__hidden", "x");

        JsonNode result = sanitizer.stripInternalFields("Bash", input);

        assertThat(result.has("_internal"))
            .as("CC toolExecution.ts:762-773 仅剥 _simulatedSedEdit；_internal 前缀旧 Java 超剥已移除（OPD-WF4-BC-04 拍板）")
            .isTrue();
        assertThat(result.has("__hidden")).isTrue();
    }

    @Test
    @DisplayName("[P4] 非 Bash 工具 + _simulatedSedEdit → 不剥（CC 仅对 Bash）")
    void strip_keepsSimulatedSedEditForNonBash() {
        InputSanitizer sanitizer = new InputSanitizer();
        ObjectNode input = JSON.createObjectNode();
        input.put("file_path", "/tmp/a.txt");
        input.put("_simulatedSedEdit", "injected");

        JsonNode result = sanitizer.stripInternalFields("Edit", input);

        assertThat(result.has("_simulatedSedEdit"))
            .as("CC toolExecution.ts:763 `tool.name === BASH_TOOL_NAME` 门 —— 非 Bash 工具不剥任何字段")
            .isTrue();
    }
}
