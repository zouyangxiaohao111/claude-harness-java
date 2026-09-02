package com.nexusai.application.agent.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verify bundled skill content · 对齐 CC skills/bundled/verifyContent.ts.
 *
 * <p>L1 语义: SKILL_MD（verify/SKILL.md 全文）+ SKILL_FILES（2 个 path → content 映射，
 *            CC 用 Bun text loader build-time inline）.
 *
 * <p><b>正文 N/A 登记（CC 源文件缺失, 同 DEC-15 处置）</b>:
 * verifyContent.ts:4-6 引用的 3 个 .md 源文件（verify/SKILL.md + verify/examples/cli.md +
 * verify/examples/server.md）在本 checkout 全部缺失（DCE 剔除, git history 亦无; 2026-08-13
 * find + git log 实证）。CC 用 Bun text-loader 构建期内联 .md 为字符串, 本仓库既无构建产物也无源文件,
 * 真实正文不可获取。按任务硬约束「显式标注 N/A 而非伪造」: 键结构（2 path, 顺序同 CC 字面量
 * verifyContent.ts:11-12）与 SKILL_MD/SKILL_FILES 常量按 verifyContent.ts 真实实现, 正文一律显式
 * N/A 标注, 不伪造文档内容。旧实现伪造 "# CLI verify example\nRun `npm test`..." 等简化占位内容
 * （违反 DEC-15 不伪造铁律, 非 CC 真实 verify SKILL.md/cli.md/server.md）, 已删除。待 CC checkout
 * 恢复源文件后, 用真实 .md 内容替换 marker 即可（键与顺序不变, registrar 无需改动）.
 *
 * <p>L3 (Java idiom): LinkedHashMap 保序（CC Record 字面量顺序, 同 ClaudeApiSkillContent 处置）;
 * Collections.unmodifiableMap 不可变（Map.copyOf 迭代序未定义, 会破坏 CC Object.keys 可观测序）.
 */
public final class VerifySkillContent {

    private VerifySkillContent() {}

    /** CC verifyContent.ts:8 — SKILL_MD = skillMd（verify/SKILL.md 全文）. 正文 N/A：源文件缺失（不伪造）. */
    public static final String SKILL_MD =
        "N/A — CC 源文件缺失：Open-ClaudeCode/src/skills/bundled/verify/SKILL.md 未随 checkout 提供"
        + "（verifyContent.ts:6 Bun text-loader build-time inline，DCE 剔除；DEC-15 同处置，不伪造正文）。";

    /** CC verifyContent.ts:10-12 — 2 个 path → content 映射. 正文 N/A：源文件缺失（不伪造）. */
    public static final Map<String, String> SKILL_FILES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // CC verifyContent.ts:11-12 字面量顺序：examples/cli.md → examples/server.md
        m.put("examples/cli.md", na("examples/cli.md"));
        m.put("examples/server.md", na("examples/server.md"));
        SKILL_FILES = Collections.unmodifiableMap(m);
    }

    /** 统一 N/A marker：正文不可伪造（CC 源文件 DCE 缺失），显式标注并登记恢复路径. */
    private static String na(String path) {
        return "N/A — CC 源文件缺失：Open-ClaudeCode/src/skills/bundled/verify/" + path
            + " 未随 checkout 提供（verifyContent.ts:10-12 Bun text-loader inline 依赖 .md，DCE 剔除；"
            + "DEC-15 同处置：不伪造正文，待 CC 源恢复后按真实 .md 替换）。";
    }
}
