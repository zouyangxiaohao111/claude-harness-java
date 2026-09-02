package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-AL-02 · read 路径 step5 edit-implies-read 对齐（CC {@code filesystem.ts:1124-1134}）。
 *
 * <p><b>对齐锚点（CC 真源，行号当次 read 自验）</b>：
 * <ol>
 *   <li>{@code checkReadPermissionForTool} step5（filesystem.ts:1124-1134）：
 *       read-specific deny/ask 规则之后、working-dir 检查之前调用
 *       {@code checkWritePermissionForTool(tool, input, ctx, pathsToCheck)}，
 *       {@code editResult.behavior === 'allow'} 时直接返回 editResult（edit 授权蕴含 read 授权）；
 *       deny/ask 结果<b>忽略</b>（继续后续步骤）——read-specific 规则优先，显式 read 限制不被覆盖。</li>
 *   <li>checkWritePermissionForTool 的 allow 来源（filesystem.ts:1205-1412）：
 *       edit 桶规则匹配仅限<b>带 ruleContent 的 content 规则</b>
 *       （matchingRuleForInput → getPatternsByRoot → getRuleByContentsForToolName 过滤
 *       {@code ruleContent !== undefined}，permissions.ts:380-388）——whole-tool 'Edit' allow
 *       <b>不</b>蕴含 read allow（CC 语义，测试锁定）。</li>
 * </ol>
 *
 * <p><b>路径选择注意</b>：
 * <ul>
 *   <li>本机 {@code %TEMP%} 含 8.3 短名（{@code ADMINI~1.DES}），命中 CC 对齐的
 *       suspicious-Windows-pattern 提前 ask（ReadPermissionChecker step2，与 CC
 *       hasSuspiciousWindowsPathPattern 的 {@code ~\d} 一致）——测试统一用项目
 *       {@code target/} 下路径，避免短名污染。</li>
 *   <li>{@code ToolUseContext} compact ctor 把 null effectiveCwd 归一为进程 CWD
 *       （ToolUseContext.java:311-312）——因此用<b>兄弟目录</b>作 cwd（{@code pal02-cwd-*}），
 *       让目标路径（{@code pal02-*}）真正落在工作目录外，复现 DEL-33 的
 *       "已授 Edit 的文件 Read 仍 ask" 缺口场景。</li>
 *   <li>input 路径用绝对路径（Java content-rule 匹配为 glob 直比，不做 root-relative
 *       归一——ReadPermissionChecker 既有近似约定）。</li>
 * </ul>
 *
 * <p><b>RED 双证</b>：本测试在 step5 实现前运行（{@code ReadPermissionChecker} 1 参构造器）
 * 必须失败（工作目录外 + Edit 内容 allow → 旧实现兜底 Ask/workingDir-Allow，而非
 * Allow(Rule)）；实现后改 2 参构造器（注入 {@link WritePermissionChecker}）转 GREEN。
 */
@DisplayName("P-AL-02 · read step5 edit-implies-read（CC filesystem.ts:1124-1134）")
class EditImpliesReadTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode input(String path) {
        return JSON.createObjectNode().put("file_path", path);
    }

    /** 13 参工厂：显式 effectiveCwd（null 会被 ToolUseContext 归一为进程 CWD）。 */
    private static ToolUseContext ctx(ToolPermissionContext permCtx, Path effectiveCwd) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", effectiveCwd);
    }

    private static ToolPermissionContext allowCtx(Map<PermissionRuleSource, Set<PermissionRule>> allow) {
        return ToolPermissionContext.of(PermissionMode.DEFAULT, allow, Map.of(), Map.of(), Map.of());
    }

    private static ToolPermissionContext denyCtx(Map<PermissionRuleSource, Set<PermissionRule>> deny) {
        return ToolPermissionContext.of(PermissionMode.DEFAULT, Map.of(), deny, Map.of(), Map.of());
    }

    private static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 工作目录（兄弟目录，与目标路径互不包含）。 */
    private static Path cwdDir() {
        return Paths.get("target", "pal02-cwd-" + rand());
    }

    /** 目标文件所在目录（工作目录外；无 8.3 短名/ADS 等可疑模式）。 */
    private static Path targetDir() {
        return Paths.get("target", "pal02-" + rand());
    }

    /** glob 规则内容：绝对路径转 '/' + '/**'（Windows PathMatcher 下 '/' 即分隔符）。 */
    private static String toGlob(Path dir) {
        return dir.toAbsolutePath().toString().replace('\\', '/') + "/**";
    }

    @Test
    @DisplayName("Edit 内容 allow 规则（工作目录外路径）→ read 直接 allow，不再兜底 ask")
    void editContentAllow_impliesReadAllow() {
        Path workspace = targetDir();
        PermissionRule allowRule = new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
            PermissionRuleValue.withContent("Edit", toGlob(workspace)));
        ToolUseContext ctx = ctx(allowCtx(Map.of(PermissionRuleSource.SESSION, Set.of(allowRule))), cwdDir());
        // GREEN 阶段：2 参构造器（step5 已实现，注入 WritePermissionChecker）→ 应 Allow(Rule)
        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());
        Tool tool = new ReadFileTool(new PathGuard(workspace));
        String target = workspace.toAbsolutePath().resolve("notes.txt").toString();

        PermissionResult result = checker.check(tool, input(target), ctx);

        assertThat(result)
            .as("CC: edit 内容 allow 蕴含 read allow（filesystem.ts:1132-1134 editResult.behavior==='allow' → return editResult）")
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(((PermissionResult.Allow) result).reason())
            .as("CC: 返回原 editResult（decisionReason=Rule(edit allow rule)，非 workingDir/Other）")
            .isInstanceOf(PermissionDecisionReason.Rule.class);
    }

    @Test
    @DisplayName("Edit whole-tool allow（无 ruleContent）→ 不蕴含 read allow（CC content-only 匹配）")
    void editWholeToolAllow_doesNotImplyReadAllow() {
        Path workspace = targetDir();
        PermissionRule allowRule = new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.ALLOW,
            PermissionRuleValue.wholeTool("Edit"));
        ToolUseContext ctx = ctx(allowCtx(Map.of(PermissionRuleSource.SESSION, Set.of(allowRule))), cwdDir());
        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());
        Tool tool = new ReadFileTool(new PathGuard(workspace));
        String target = workspace.toAbsolutePath().resolve("notes.txt").toString();

        PermissionResult result = checker.check(tool, input(target), ctx);

        assertThat(result)
            .as("CC: matchingRuleForInput 仅匹配 ruleContent 非空规则（permissions.ts:380-388）→ whole-tool Edit allow 不命中 step5，工作目录外仍 Ask")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    @Test
    @DisplayName("Edit deny 规则 → read 不受阻（CC: step5 仅消费 allow，deny/ask 忽略继续）")
    void editContentDeny_doesNotBlockRead() {
        Path workspace = targetDir();
        PermissionRule denyRule = new PermissionRule(PermissionRuleSource.SESSION, PermissionBehavior.DENY,
            PermissionRuleValue.withContent("Edit", toGlob(workspace)));
        ToolUseContext ctx = ctx(denyCtx(Map.of(PermissionRuleSource.SESSION, Set.of(denyRule))), cwdDir());
        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());
        Tool tool = new ReadFileTool(new PathGuard(workspace));
        String target = workspace.toAbsolutePath().resolve("notes.txt").toString();

        PermissionResult result = checker.check(tool, input(target), ctx);

        assertThat(result)
            .as("CC: read step5 忽略 checkWritePermissionForTool 的 deny（filesystem.ts:1132 仅 behavior==='allow' 返回）→ 工作目录外兜底 Ask，而非 Deny")
            .isInstanceOf(PermissionResult.Ask.class);
        assertThat(result)
            .as("edit deny 不得把 read 拒死（显式 read deny 规则才拒绝 read）")
            .isNotInstanceOf(PermissionResult.Deny.class);
    }
}
