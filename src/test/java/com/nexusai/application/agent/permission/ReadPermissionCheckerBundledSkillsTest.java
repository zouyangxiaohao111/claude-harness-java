package com.nexusai.application.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.skill.BundledSkillFileExtractor;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.PathGuard;
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
 * V-BD-5 · bundled-skills 根读 allowlist（对齐 CC filesystem.ts:1759-1774）。
 *
 * <p><b>验证的验收标准（WHY 安全缺口）</b>：CC 在 {@code checkReadableInternalPath}
 * （filesystem.ts:1764-1765）末尾对 {@code normalizedPath.startsWith(getBundledSkillsRoot() + sep)}
 * 静默放行（reason='Bundled skill reference files are allowed for reading'，filesystem.ts:1771），
 * 使模型注册 bundled skill 后可无感 Read/Grep 其参考文件（bundledSkills.ts:33）。Java 端此前
 * 无该白名单 → 模型读内置技能参考文件落到兜底 ask（ReadPermissionChecker :311-318），
 * bundled-skill 参考文件能力半失效。本测试锁定三条安全不变式：
 * <ol>
 *   <li>根下参考文件静默 allow（reason 对齐 CC）；</li>
 *   <li>nonce 前缀攻击路径（{@code root+"-evil"}）仍兜底 ask——尾分隔符防前缀攻击
 *       （CC {@code + sep}，filesystem.ts:1764）；</li>
 *   <li>注入缺失（extractor==null）→ fail-closed 兜底 ask，不静默 allow（防绕过）。</li>
 * </ol>
 */
@DisplayName("V-BD-5 · bundled-skills 根读 allowlist（CC filesystem.ts:1759-1774）")
class ReadPermissionCheckerBundledSkillsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode input(String path) {
        return JSON.createObjectNode().put("file_path", path);
    }

    /** 13 参工厂：显式 effectiveCwd（非 null，且刻意不包含 tmpdir 下的 bundled-skills 根）。 */
    private static ToolUseContext ctx(ToolPermissionContext permCtx, Path effectiveCwd) {
        return ToolUseContext.of(UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
            List.of(), "", AbortController.NOOP, List.of(), permCtx, PermissionMode.DEFAULT,
            Map.of(), false, "", effectiveCwd);
    }

    /** 空规则上下文（无 deny/ask/allow，使检查能落到 bundled-skills carve-out 与兜底 ask）。 */
    private static ToolPermissionContext emptyRulesCtx() {
        return ToolPermissionContext.of(PermissionMode.DEFAULT,
            Map.<PermissionRuleSource, Set<PermissionRule>>of(),
            Map.<PermissionRuleSource, Set<PermissionRule>>of(),
            Map.<PermissionRuleSource, Set<PermissionRule>>of(),
            Map.of());
    }

    /** 有效 cwd：项目 target/ 下，绝对路径且绝不包含 tmpdir 下的 bundled-skills 根。 */
    private static Path cwd() {
        return Paths.get("target", "rp-bundled-" + UUID.randomUUID()).toAbsolutePath();
    }

    private static ReadFileTool readTool() {
        return new ReadFileTool(new PathGuard(cwd()));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1. 根下参考文件 → 静默 allow（reason 对齐 CC）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bundled-skills 根下参考文件 → 静默 Allow（reason='Bundled skill reference files are allowed for reading'）")
    void bundledSkillsReferenceFile_silentAllow() {
        BundledSkillFileExtractor extractor = new BundledSkillFileExtractor();
        Path root = extractor.getBundledSkillsRoot();
        Path refFile = root.resolve("verify").resolve("refs").resolve("guide.md");

        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());
        checker.setBundledSkillFileExtractor(extractor);

        PermissionResult result = checker.check(readTool(), input(refFile.toString()),
            ctx(emptyRulesCtx(), cwd()));

        assertThat(result)
            .as("CC filesystem.ts:1765 根下参考文件 startsWith(bundledSkillsRoot+sep) 应静默 allow")
            .isInstanceOf(PermissionResult.Allow.class);
        PermissionDecisionReason reason = ((PermissionResult.Allow) result).reason();
        assertThat(reason)
            .as("CC filesystem.ts:1770-1771 decisionReason.type='other' reason='Bundled skill reference files are allowed for reading'")
            .isInstanceOf(PermissionDecisionReason.Other.class);
        assertThat(((PermissionDecisionReason.Other) reason).reason())
            .isEqualTo("Bundled skill reference files are allowed for reading");
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. nonce 前缀攻击（root+"-evil"）→ 兜底 ask（尾分隔符防御）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("nonce 前缀攻击路径（root+\"-evil\"）→ 兜底 Ask（尾分隔符防前缀攻击）")
    void noncePrefixAttack_fallsToAsk() {
        BundledSkillFileExtractor extractor = new BundledSkillFileExtractor();
        Path root = extractor.getBundledSkillsRoot();
        // 攻击：在 nonce 后拼 "-evil" 而非 "/"，尝试借前缀匹配混入 allowlist
        Path outside = Paths.get(root.toString() + "-evil", "x.md");

        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());
        checker.setBundledSkillFileExtractor(extractor);

        PermissionResult result = checker.check(readTool(), input(outside.toString()),
            ctx(emptyRulesCtx(), cwd()));

        assertThat(result)
            .as("CC filesystem.ts:1764 `+ sep` 尾分隔符：root+\"-evil\" 不匹配 root/ 前缀，须兜底 ask（防 nonce 前缀攻击）")
            .isInstanceOf(PermissionResult.Ask.class);
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. 注入缺失 → fail-closed 兜底 ask（不静默 allow）
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bundledSkillFileExtractor 未注入（null）→ 根下路径仍兜底 Ask（fail-closed 防绕过）")
    void missingInjection_failClosedToAsk() {
        BundledSkillFileExtractor extractor = new BundledSkillFileExtractor();
        Path root = extractor.getBundledSkillsRoot();
        Path refFile = root.resolve("verify").resolve("refs").resolve("guide.md");

        // 刻意不调用 setBundledSkillFileExtractor → 注入缺失（@Autowired(required=false) 空）
        ReadPermissionChecker checker = new ReadPermissionChecker(new WritePermissionChecker());

        PermissionResult result = checker.check(readTool(), input(refFile.toString()),
            ctx(emptyRulesCtx(), cwd()));

        assertThat(result)
            .as("注入缺失时不得静默 allow（无法验证路径确在 bundled-skills 根下），须 fail-closed 兜底 ask")
            .isInstanceOf(PermissionResult.Ask.class);
    }
}
