package com.nexusai.application.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Verify bundled skill 注册器 · 对齐 CC skills/bundled/verify.ts registerVerifySkill.
 *
 * <p>L1 语义: 解析 SKILL_MD frontmatter → 提取 description → 在 USER_TYPE='ant' 时注册 bundled skill
 *            'verify' (user-invocable=true), getPromptForCommand 返回 SKILL_BODY + 可选 ## User Request.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `register(SkillRegistry, Predicate&lt;String&gt; userType)` 签名</li>
 *   <li><b>A2 Golden Trace</b>: USER_TYPE!=ant → no-op (CC verify.ts:13 早返); USER_TYPE=ant → 注册 skill</li>
 *   <li><b>A3</b>: SKILL_MD frontmatter description 优先; 缺则用 fallback (CC verify.ts:7-10)</li>
 *   <li><b>A4</b>: getPromptForCommand 无 args → [SKILL_BODY.trimStart()]; 有 args → [body + ## User Request]</li>
 *   <li><b>A5</b>: 真实 ant 调用 args="test login flow" → 返回 2 段 (body + ## User Request + 内容)</li>
 * </ul>
 *
 * <p>L3 (Java idiom): Predicate&lt;String&gt; userType 替代 process.env.USER_TYPE 全局读;
 *                    BiFunction&lt;String, String, List&lt;PromptBlock&gt;&gt; 替代 getPromptForCommand 回调;
 *                    无 SkillRegistry 时直接 log warn 而非抛 NPE (CC 同宽容).
 */
public class VerifySkillRegistrar {

    private static final Logger log = LoggerFactory.getLogger(VerifySkillRegistrar.class);

    /** CC frontmatter.description 缺省 fallback */
    public static final String FALLBACK_DESCRIPTION =
        "Verify a code change does what it should by running the app.";

    private final String skillName = "verify";

    /**
     * 注册 verify skill (CC registerVerifySkill) · 统一产出 BundledSkillDefinition（P1-4）.
     *
     * @param registrar        统一注册入口 Consumer (允许 null, 为空仅 log warn · fail-soft)
     * @param isAntUser        当前是否 USER_TYPE=ant (CC verify.ts:13 早返条件；P2-12 由 Bootstrapper
     *                         注入真实 isAntSupplier，旧 "ant"::equals 恒真已删——非 ant 环境不注册)
     * @param frontmatterDesc  frontmatter.description (CC 解析 SKILL_MD frontmatter)
     * @param skillBody        SKILL.md body (SKILL_MD 去除 frontmatter 后内容)
     * @param files            skill 附属文件 (CC SKILL_FILES, 保 P1-3 解压端到端)
     * @return true 实际注册; false 因 USER_TYPE!=ant 跳过
     */
    public boolean register(Consumer<BundledSkillDefinition> registrar,
                            BooleanSupplier isAntUser,
                            String frontmatterDesc,
                            String skillBody,
                            Map<String, String> files) {
        if (!isAntUser.getAsBoolean()) {
            log.debug("[VerifySkillRegistrar] USER_TYPE!=ant, skipping registration");
            return false;
        }
        String description = frontmatterDesc != null && !frontmatterDesc.isBlank()
            ? frontmatterDesc
            : FALLBACK_DESCRIPTION;
        if (registrar == null) {
            log.warn("[VerifySkillRegistrar] registrar=null, logging intent only");
            log.info("  skill={} description={} userInvocable=true files={}",
                skillName, description, files == null ? 0 : files.size());
            return true;
        }
        BundledSkillDefinition def = new BundledSkillDefinition(
            skillName,
            description,
            null,   // aliases
            null,   // whenToUse
            null,   // argumentHint
            null,   // allowedTools
            null,   // model
            null,   // disableModelInvocation (CC undefined → default false)
            true,   // userInvocable (CC verify.ts:20)
            null,   // isEnabled
            null,   // hooks
            null,   // context
            null,   // agent
            files,  // files (CC verify.ts:21 files: SKILL_FILES)
            (args, cwd) -> {
                // CC verify.ts:22-27 — 单 text 块: SKILL_BODY.trimStart() + (args) \n\n## User Request\n\nargs
                String trimmed = skillBody == null ? "" : skillBody.trim();
                if (args == null || args.isBlank()) {
                    return List.of(PromptBlock.text(trimmed));
                }
                return List.of(PromptBlock.text(trimmed + "\n\n## User Request\n\n" + args));
            }
        );
        registrar.accept(def);
        log.info("[VerifySkillRegistrar] registered skill={}", skillName);
        return true;
    }
}