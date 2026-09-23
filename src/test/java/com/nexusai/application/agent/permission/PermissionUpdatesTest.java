package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IMP-4 G2 + GAP-3 · {@link PermissionUpdates} 单元测试（CC {@code PermissionUpdate.ts:30-43
 * extractRules} + {@code :361-389 createReadRuleSuggestion} + {@code filesystem.ts:1414-1478
 * generateSuggestions} 等价物）。
 *
 * <p><b>WHY（对齐意图）</b>：
 * <ul>
 *   <li>{@code extractRules} 只抽 addRules 型规则——这是"用户始终允许时落盘哪些规则"
 *       的语义边界：mode/directory/replace/remove 更新都不该被当作可写盘规则抽出；</li>
 *   <li>{@code createReadRuleSuggestion} 根目录返回 empty——把 {@code /**} 规则写到
 *       根目录等于授权整个文件系统，CC 拒绝这个过宽目标；绝对路径前置 {@code /} 形成
 *       {@code //path/**} 是防 {@code /path/**} 丢根锚点的规则模式语义；</li>
 *   <li>{@code generateSuggestions} 三分支 + {@code shouldSuggestAcceptEdits}——这是
 *       "default/plan mode 才建议 acceptEdits"的语义边界：auto/bypass/acceptEdits 时
 *       建议会被 SDK host 静默降级（CC :1440-1444 注释），故不建。</li>
 * </ul>
 *
 * <p><b>平台无关性</b>：generateSuggestions 分支选择由 {@code isOutsideWorkingDir} 布尔
 * 直接驱动，与实际文件系统无关；断言只比对更新<b>类型</b>与 mode（SetMode/AddRules/
 * AddDirectories），不比对具体目录字符串（{@link CommandHookExecutor#windowsPathToPosixPath}
 * 对纯正斜杠相对路径原样透传，但 {@code getDirectoryForPath} 的父目录字符串在 Windows/Linux
 * 分隔符不同），故断言在双平台一致。
 */
@DisplayName("IMP-4 G2 + GAP-3 · PermissionUpdates 辅助函数（CC PermissionUpdate.ts / filesystem.ts）")
class PermissionUpdatesTest {

    private static PermissionRule rule(String toolName, String content) {
        return new PermissionRule(
            PermissionRuleSource.SESSION,
            PermissionBehavior.ALLOW,
            PermissionRuleValue.withContent(toolName, content));
    }

    // ── extractRules ────────────────────────────────────────────────────────

    @Test
    @DisplayName("null（CC undefined）→ 空列表")
    void extractRules_null_returnsEmpty() {
        assertThat(PermissionUpdates.extractRules(null)).isEmpty();
    }

    @Test
    @DisplayName("混合更新只抽 addRules 型规则，其余 type 一律忽略")
    void extractRules_onlyAddRules_survive() {
        PermissionRule readRule = rule("Read", "/home/user/**");
        PermissionRule editRule = rule("Edit", "/home/user/**");

        List<PermissionUpdate> updates = List.of(
            new PermissionUpdate.AddRules(
                PermissionUpdate.Destination.SESSION, List.of(readRule), PermissionBehavior.ALLOW),
            new PermissionUpdate.RemoveRules(
                PermissionUpdate.Destination.SESSION, List.of(editRule), PermissionBehavior.ALLOW),
            new PermissionUpdate.ReplaceRules(
                PermissionUpdate.Destination.SESSION, List.of(editRule), PermissionBehavior.ALLOW),
            new PermissionUpdate.SetMode(
                PermissionUpdate.Destination.SESSION, PermissionMode.DEFAULT),
            new PermissionUpdate.AddDirectories(
                PermissionUpdate.Destination.SESSION, List.of("/home/user")),
            new PermissionUpdate.RemoveDirectories(
                PermissionUpdate.Destination.SESSION, List.of("/home/user"))
        );

        assertThat(PermissionUpdates.extractRules(updates))
            .containsExactly(readRule);
    }

    @Test
    @DisplayName("多个 addRules 型更新 → flatMap 拼接保序")
    void extractRules_multipleAddRules_flatMapped() {
        PermissionRule r1 = rule("Read", "/a/**");
        PermissionRule r2 = rule("Read", "/b/**");
        PermissionRule r3 = rule("Read", "/c/**");

        List<PermissionUpdate> updates = List.of(
            new PermissionUpdate.AddRules(
                PermissionUpdate.Destination.SESSION, List.of(r1, r2), PermissionBehavior.ALLOW),
            new PermissionUpdate.AddRules(
                PermissionUpdate.Destination.SESSION, List.of(r3), PermissionBehavior.ALLOW)
        );

        assertThat(PermissionUpdates.extractRules(updates))
            .containsExactly(r1, r2, r3);
    }

    @Test
    @DisplayName("空列表 → 空列表")
    void extractRules_empty_returnsEmpty() {
        assertThat(PermissionUpdates.extractRules(List.of())).isEmpty();
    }

    // ── createReadRuleSuggestion ─────────────────────────────────────────────

    @Test
    @DisplayName("根目录 '/' → empty（太宽，非合理权限目标，CC :369-371）")
    void createReadRuleSuggestion_root_returnsEmpty() {
        assertThat(PermissionUpdates.createReadRuleSuggestion("/", PermissionUpdate.Destination.SESSION))
            .isEmpty();
    }

    @Test
    @DisplayName("绝对路径 → ruleContent='//path/**'（前置 / 防丢根锚点，CC :374-376）")
    void createReadRuleSuggestion_absolute_prependsSlash() {
        Optional<PermissionUpdate.AddRules> suggestion =
            PermissionUpdates.createReadRuleSuggestion("/home/user", PermissionUpdate.Destination.SESSION);

        assertThat(suggestion).isPresent();
        PermissionUpdate.AddRules addRules = suggestion.get();
        assertThat(addRules.destination()).isEqualTo(PermissionUpdate.Destination.SESSION);
        assertThat(addRules.behavior()).isEqualTo(PermissionBehavior.ALLOW);

        PermissionRule rule = addRules.rules().get(0);
        assertThat(rule.source()).isEqualTo(PermissionRuleSource.SESSION);
        assertThat(rule.ruleBehavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(rule.ruleValue().toolName()).isEqualTo("Read");
        assertThat(rule.ruleValue().ruleContent()).isEqualTo("//home/user/**");
    }

    @Test
    @DisplayName("相对路径 → ruleContent='path/**'（无前置 /）")
    void createReadRuleSuggestion_relative_noSlashPrefix() {
        Optional<PermissionUpdate.AddRules> suggestion =
            PermissionUpdates.createReadRuleSuggestion("home/user", PermissionUpdate.Destination.SESSION);

        assertThat(suggestion).isPresent();
        assertThat(suggestion.get().rules().get(0).ruleValue().ruleContent())
            .isEqualTo("home/user/**");
    }

    @Test
    @DisplayName("destination 透传（非 session）")
    void createReadRuleSuggestion_destinationPreserved() {
        Optional<PermissionUpdate.AddRules> suggestion =
            PermissionUpdates.createReadRuleSuggestion("/home/user", PermissionUpdate.Destination.USER_SETTINGS);

        assertThat(suggestion).isPresent();
        assertThat(suggestion.get().destination()).isEqualTo(PermissionUpdate.Destination.USER_SETTINGS);
    }

    @Test
    @DisplayName("单参重载默认 destination=SESSION（对齐 CC 缺省参数 'session'）")
    void createReadRuleSuggestion_singleArg_defaultsToSession() {
        Optional<PermissionUpdate.AddRules> suggestion =
            PermissionUpdates.createReadRuleSuggestion("/home/user");

        assertThat(suggestion).isPresent();
        assertThat(suggestion.get().destination()).isEqualTo(PermissionUpdate.Destination.SESSION);
    }

    @Test
    @DisplayName("null dirPath → empty（不抛 NPE）")
    void createReadRuleSuggestion_null_returnsEmpty() {
        assertThat(PermissionUpdates.createReadRuleSuggestion(null, PermissionUpdate.Destination.SESSION))
            .isEmpty();
    }

    // ── generateSuggestions（CC filesystem.ts:1414-1478，GAP-3 三分支） ─────────

    private static List<PermissionUpdate> suggestions(
            String filePath, PermissionUpdates.OperationType op, PermissionMode mode, boolean outside) {
        return PermissionUpdates.generateSuggestions(filePath, op, mode, outside);
    }

    @Test
    @DisplayName("read + outside → Read 规则建议（AddRules，无视 mode）")
    void generateSuggestions_readOutside_returnsReadRules() {
        List<PermissionUpdate> result = suggestions(
            "a/b/file.txt", PermissionUpdates.OperationType.READ, PermissionMode.DEFAULT, true);

        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(update -> {
            assertThat(update).isInstanceOf(PermissionUpdate.AddRules.class);
            PermissionUpdate.AddRules addRules = (PermissionUpdate.AddRules) update;
            assertThat(addRules.destination()).isEqualTo(PermissionUpdate.Destination.SESSION);
            assertThat(addRules.behavior()).isEqualTo(PermissionBehavior.ALLOW);
            assertThat(addRules.rules())
                .allSatisfy(r -> assertThat(r.ruleValue().toolName()).isEqualTo("Read"));
        });
    }

    @Test
    @DisplayName("write + outside + default → SetMode(acceptEdits) + AddDirectories")
    void generateSuggestions_writeOutsideDefault_returnsSetModeAndAddDirectories() {
        List<PermissionUpdate> result = suggestions(
            "a/b/file.txt", PermissionUpdates.OperationType.WRITE, PermissionMode.DEFAULT, true);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(
            new PermissionUpdate.SetMode(PermissionUpdate.Destination.SESSION, PermissionMode.ACCEPT_EDITS));
        assertThat(result.get(1)).isInstanceOf(PermissionUpdate.AddDirectories.class);
        PermissionUpdate.AddDirectories dirs = (PermissionUpdate.AddDirectories) result.get(1);
        assertThat(dirs.destination()).isEqualTo(PermissionUpdate.Destination.SESSION);
        assertThat(dirs.paths()).isNotEmpty();
    }

    @Test
    @DisplayName("create 与 write 同分支：create + outside + plan → SetMode + AddDirectories")
    void generateSuggestions_createOutsidePlan_returnsSetModeAndAddDirectories() {
        List<PermissionUpdate> result = suggestions(
            "a/b/file.txt", PermissionUpdates.OperationType.CREATE, PermissionMode.PLAN, true);

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo(
            new PermissionUpdate.SetMode(PermissionUpdate.Destination.SESSION, PermissionMode.ACCEPT_EDITS));
        assertThat(result.get(1)).isInstanceOf(PermissionUpdate.AddDirectories.class);
    }

    @Test
    @DisplayName("write + inside + default → 仅 SetMode(acceptEdits)")
    void generateSuggestions_writeInsideDefault_returnsOnlySetMode() {
        List<PermissionUpdate> result = suggestions(
            "a/b/file.txt", PermissionUpdates.OperationType.WRITE, PermissionMode.DEFAULT, false);

        assertThat(result).containsExactly(
            new PermissionUpdate.SetMode(PermissionUpdate.Destination.SESSION, PermissionMode.ACCEPT_EDITS));
    }

    @Test
    @DisplayName("read + inside + default → 仅 SetMode(acceptEdits)")
    void generateSuggestions_readInsideDefault_returnsOnlySetMode() {
        List<PermissionUpdate> result = suggestions(
            "a/b/file.txt", PermissionUpdates.OperationType.READ, PermissionMode.DEFAULT, false);

        assertThat(result).containsExactly(
            new PermissionUpdate.SetMode(PermissionUpdate.Destination.SESSION, PermissionMode.ACCEPT_EDITS));
    }

    @Test
    @DisplayName("write + outside + acceptEdits → 不建 SetMode（静默降级），仅 AddDirectories")
    void generateSuggestions_writeOutsideAcceptEdits_noSetMode() {
        List<PermissionUpdate> result = suggestions(
            "a/b/file.txt", PermissionUpdates.OperationType.WRITE, PermissionMode.ACCEPT_EDITS, true);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(PermissionUpdate.AddDirectories.class);
    }

    @Test
    @DisplayName("read + inside + acceptEdits → 空列表（shouldSuggestAcceptEdits=false）")
    void generateSuggestions_readInsideAcceptEdits_empty() {
        List<PermissionUpdate> result = suggestions(
            "a/b/file.txt", PermissionUpdates.OperationType.READ, PermissionMode.ACCEPT_EDITS, false);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("write + inside + bypass → 空列表（无 SetMode 无 AddDirectories）")
    void generateSuggestions_writeInsideBypass_empty() {
        List<PermissionUpdate> result = suggestions(
            "a/b/file.txt", PermissionUpdates.OperationType.WRITE, PermissionMode.BYPASS_PERMISSIONS, false);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("null filePath → 空列表")
    void generateSuggestions_nullFilePath_empty() {
        assertThat(PermissionUpdates.generateSuggestions(
            null, PermissionUpdates.OperationType.READ, PermissionMode.DEFAULT, true)).isEmpty();
    }

    // ── 自有设置专用档（CC permissionOptions.tsx:105-113 + usePermissionHandler.ts:104-129） ──

    /** 自有根内的目标路径（家目录形；{@code .{appName}} 从生产同源取，⛔ 不写死字面量）。 */
    private static String homeOwnRootFile() {
        return java.nio.file.Paths.get(System.getProperty("user.home"),
            NexusaiPaths.getProjectDirName(), "teams", "proj-a", "notes.md").toString();
    }

    /** 工作目录内的自有根目标路径。 */
    private static String cwdOwnRootFile(String cwd) {
        return java.nio.file.Paths.get(cwd, NexusaiPaths.getProjectDirName(), "teams", "notes.md").toString();
    }

    @Test
    @DisplayName("自有设置专用档：~/.{appName} 内 → addRules(Edit,'~/.{appName}/**',allow,session)（照 CC）")
    void selfConfigRoot_globalRoot_producesSessionEditRule() {
        PermissionUpdate.AddRules addRules =
            PermissionUpdates.selfConfigRootRuleSuggestion(homeOwnRootFile(), "D:/repo")
                .orElseThrow(() -> new AssertionError("自有根内必须产出专用档（CC permissionOptions.tsx:105-113）"));

        assertThat(addRules.destination())
            .as("destination=session（CC usePermissionHandler.ts:123）")
            .isEqualTo(PermissionUpdate.Destination.SESSION);
        assertThat(addRules.behavior())
            .as("behavior=allow（CC :122）").isEqualTo(PermissionBehavior.ALLOW);
        assertThat(addRules.rules()).hasSize(1);
        PermissionRuleValue rv = addRules.rules().get(0).ruleValue();
        assertThat(rv.toolName()).as("toolName=Edit（CC FILE_EDIT_TOOL_NAME，constants.ts:2）").isEqualTo("Edit");
        assertThat(rv.ruleContent())
            .as("ruleContent = '~/.{appName}/**'（CC GLOBAL_CLAUDE_FOLDER_PERMISSION_PATTERN 的对应物）")
            .isEqualTo("~/" + NexusaiPaths.getProjectDirName() + "/**");
        assertThat(addRules.rules().get(0).source())
            .as("规则 source=SESSION（会话级，不落盘）").isEqualTo(PermissionRuleSource.SESSION);
    }

    @Test
    @DisplayName("自有设置专用档：<cwd>/.{appName} 内 → addRules(Edit,'/.{appName}/**',allow,session)")
    void selfConfigRoot_projectRoot_producesProjectScopedRule() {
        String cwd = java.nio.file.Paths.get(System.getProperty("user.home"), "some-repo").toString();
        PermissionUpdate.AddRules addRules =
            PermissionUpdates.selfConfigRootRuleSuggestion(cwdOwnRootFile(cwd), cwd)
                .orElseThrow(() -> new AssertionError("工作目录自有根内必须产出专用档"));

        assertThat(addRules.rules().get(0).ruleValue().ruleContent())
            .as("ruleContent = '/.{appName}/**'（CC CLAUDE_FOLDER_PERMISSION_PATTERN 的对应物）")
            .isEqualTo("/" + NexusaiPaths.getProjectDirName() + "/**");
        assertThat(addRules.destination()).isEqualTo(PermissionUpdate.Destination.SESSION);
    }

    @Test
    @DisplayName("自有设置专用档：不在自有根内（含自有根目录本身）→ empty（维持通用档）")
    void selfConfigRoot_outside_returnsEmpty() {
        String home = System.getProperty("user.home");
        String appDir = NexusaiPaths.getProjectDirName();
        // ⛔ 「严格在内」：自有根目录**本身**不算命中（CC 两个判定都是 startsWith(dir + sep)）
        assertThat(PermissionUpdates.selfConfigRootRuleSuggestion(
            java.nio.file.Paths.get(home, appDir).toString(), home))
            .as("自有根目录本身 → empty（CC isInClaudeFolder 要求严格在内，permissionOptions.tsx:24-26）")
            .isEmpty();
        // 同名前缀但不是自有根（<home>/.nexusaiX）——不得被 startsWith 前缀误判
        assertThat(PermissionUpdates.selfConfigRootRuleSuggestion(
            java.nio.file.Paths.get(home, appDir + "X", "a.md").toString(), home))
            .as("'<home>/.{appName}X' 与 '<home>/.{appName}' 只共享字符串前缀，不是自有根内 → empty")
            .isEmpty();
        // 普通路径
        assertThat(PermissionUpdates.selfConfigRootRuleSuggestion("/tmp/plain.txt", "/tmp"))
            .isEmpty();
    }

    @Test
    @DisplayName("writeAskSuggestions：自有根内 → **只给专用档**（替换通用档，不是追加）")
    void writeAskSuggestions_selfRoot_replacesGenericOption() {
        List<PermissionUpdate> result = PermissionUpdates.writeAskSuggestions(
            homeOwnRootFile(), PermissionMode.DEFAULT, false, "D:/repo");

        assertThat(result)
            .as("替换语义（CC permissionOptions.tsx:105 的 if/else）：自有根内**不产出** SetMode 通用会话档")
            .hasSize(1);
        assertThat(result.get(0)).isInstanceOf(PermissionUpdate.AddRules.class);
        assertThat(result)
            .as("⛔ 不得同时出现 SetMode(acceptEdits)（那就是「追加」而不是「替换」）")
            .noneMatch(u -> u instanceof PermissionUpdate.SetMode);
    }

    @Test
    @DisplayName("writeAskSuggestions：非自有根 → 回落通用档（与 generateSuggestions 逐项相同）")
    void writeAskSuggestions_outsideRoot_fallsBackToGeneric() {
        List<PermissionUpdate> result = PermissionUpdates.writeAskSuggestions(
            "a/b/file.txt", PermissionMode.DEFAULT, true, "D:/repo");

        assertThat(result)
            .as("非自有根 ⇒ 回落 generateSuggestions(write)（CC :114-150 的 else 支），行为不变")
            .isEqualTo(PermissionUpdates.generateSuggestions(
                "a/b/file.txt", PermissionUpdates.OperationType.WRITE, PermissionMode.DEFAULT, true));
    }
}
