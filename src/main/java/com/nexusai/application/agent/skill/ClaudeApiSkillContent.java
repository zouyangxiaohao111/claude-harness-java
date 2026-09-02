package com.nexusai.application.agent.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * claude-api bundled skill content · 对齐 CC skills/bundled/claudeApiContent.ts（75 行）.
 *
 * <p>L1 语义: SKILL_MODEL_VARS（7 模型常量）+ SKILL_PROMPT（SKILL.md 全文）+ SKILL_FILES
 *            （25 个 path → content 映射, CC 用 Bun text loader build-time inline）.
 *
 * <p><b>正文 N/A 登记（CC 源文件缺失, 待拍板 OQ-12/BD-15/16/29 处置）</b>:
 * claudeApiContent.ts:4-29 共 26 行 import 引用的 25 个 .md 源文件（Open-ClaudeCode/src/skills/
 * bundled/claude-api/**）与 SKILL.md 在本 checkout 全部缺失（DCE 剔除, git history 亦无; 2026-08-13
 * find + git log 实证）。CC 用 Bun text-loader 构建期内联 .md 为字符串, 本仓库既无构建产物也无源文件,
 * 真实正文不可获取。按任务硬约束「显式标注 N/A 而非伪造」: 键结构（25 path, 顺序同 CC 字面量）与模型常量
 * 按 claudeApiContent.ts 真实实现, 正文一律显式 N/A 标注, 不伪造文档内容。待 CC checkout 恢复源文件后,
 * 用真实 .md 内容替换 marker 即可（键与顺序不变, registrar 无需改动）.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: SKILL_MODEL_VARS 7 字段 + SKILL_PROMPT 字符串 + SKILL_FILES 25 entries</li>
 *   <li><b>A2 Golden Trace</b>: OPUS_ID='claude-opus-4-6'; SKILL_FILES 含 'python/claude-api/README.md'</li>
 *   <li><b>A3</b>: Collections.unmodifiableMap 不可变（SKILL_FILES）; SKILL_MODEL_VARS 经 Map.of 不可变（字段 final）</li>
 *   <li><b>A4</b>: SKILL_PROMPT 非空; 25 entries 路径符合预期 pattern（csharp/curl/go/java/php/python/ruby/typescript/shared 前缀）</li>
 *   <li><b>A5</b>: 真实 SKILL_FILES keys — 单语言 6（csharp/curl/go/java/php/ruby, :50-54+:62）
 *       + python 7（agent-sdk 2 :55-56 + claude-api 5 :57-61）+ typescript 7（agent-sdk 2 :68-69
 *       + claude-api 5 :70-74）+ shared 5（:63-67）= 25（claudeApiContent.ts:50-74 逐行点算）</li>
 * </ul>
 *
 * <p>L3 (Java idiom): 实现 {@link ClaudeApiSkillRegistrar.SkillContent} 接口——注册链唯一真实内容实现,
 * 经 {@code ClaudeApiSkillRegistrar(SkillContentSupplier)} 注入; CC Bun build-time import './foo.md'
 * → 源缺失故正文为显式 N/A marker; LinkedHashMap 保插入顺序（CC Record 字面量顺序）.
 */
public final class ClaudeApiSkillContent implements ClaudeApiSkillRegistrar.SkillContent {

    private static final ClaudeApiSkillContent INSTANCE = new ClaudeApiSkillContent();

    private ClaudeApiSkillContent() {}

    /** CC claudeApiContent.ts:36-45 — 7 个模型常量（{{var}} 替换源, 值逐一实测）. */
    private static final Map<String, String> SKILL_MODEL_VARS = Map.of(
        "OPUS_ID", "claude-opus-4-6",
        "OPUS_NAME", "Claude Opus 4.6",
        "SONNET_ID", "claude-sonnet-4-6",
        "SONNET_NAME", "Claude Sonnet 4.6",
        "HAIKU_ID", "claude-haiku-4-5",
        "HAIKU_NAME", "Claude Haiku 4.5",
        "PREV_SONNET_ID", "claude-sonnet-4-5");

    /** CC claudeApiContent.ts:47 — SKILL.md 全文. 正文 N/A：源文件缺失（见类头登记, 不伪造）. */
    private static final String SKILL_PROMPT =
        "N/A — CC 源文件缺失：Open-ClaudeCode/src/skills/bundled/claude-api/SKILL.md 未随 checkout 提供"
        + "（claudeApiContent.ts:47 Bun text-loader build-time inline，DCE 剔除；OQ-12/BD-15/16/29 待拍板，不伪造正文）。";

    /** CC claudeApiContent.ts:49-75 — 25 个 path → content 映射. 正文 N/A：源文件缺失（见类头登记, 不伪造）. */
    private static final Map<String, String> SKILL_FILES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // 单语言前 5（CC claudeApiContent.ts:50-54）
        m.put("csharp/claude-api.md", na("csharp/claude-api.md"));
        m.put("curl/examples.md", na("curl/examples.md"));
        m.put("go/claude-api.md", na("go/claude-api.md"));
        m.put("java/claude-api.md", na("java/claude-api.md"));
        m.put("php/claude-api.md", na("php/claude-api.md"));
        // python agent-sdk 2（CC claudeApiContent.ts:55-56）
        m.put("python/agent-sdk/README.md", na("python/agent-sdk/README.md"));
        m.put("python/agent-sdk/patterns.md", na("python/agent-sdk/patterns.md"));
        // python claude-api 5（CC claudeApiContent.ts:57-61）
        m.put("python/claude-api/README.md", na("python/claude-api/README.md"));
        m.put("python/claude-api/batches.md", na("python/claude-api/batches.md"));
        m.put("python/claude-api/files-api.md", na("python/claude-api/files-api.md"));
        m.put("python/claude-api/streaming.md", na("python/claude-api/streaming.md"));
        m.put("python/claude-api/tool-use.md", na("python/claude-api/tool-use.md"));
        // ruby（CC claudeApiContent.ts:62，字面量位于 python 组之后）
        m.put("ruby/claude-api.md", na("ruby/claude-api.md"));
        // shared 5（CC claudeApiContent.ts:63-67）
        m.put("shared/error-codes.md", na("shared/error-codes.md"));
        m.put("shared/live-sources.md", na("shared/live-sources.md"));
        m.put("shared/models.md", na("shared/models.md"));
        m.put("shared/prompt-caching.md", na("shared/prompt-caching.md"));
        m.put("shared/tool-use-concepts.md", na("shared/tool-use-concepts.md"));
        // typescript agent-sdk 2（CC claudeApiContent.ts:68-69）
        m.put("typescript/agent-sdk/README.md", na("typescript/agent-sdk/README.md"));
        m.put("typescript/agent-sdk/patterns.md", na("typescript/agent-sdk/patterns.md"));
        // typescript claude-api 5（CC claudeApiContent.ts:70-74）
        m.put("typescript/claude-api/README.md", na("typescript/claude-api/README.md"));
        m.put("typescript/claude-api/batches.md", na("typescript/claude-api/batches.md"));
        m.put("typescript/claude-api/files-api.md", na("typescript/claude-api/files-api.md"));
        m.put("typescript/claude-api/streaming.md", na("typescript/claude-api/streaming.md"));
        m.put("typescript/claude-api/tool-use.md", na("typescript/claude-api/tool-use.md"));
        // 不可变 + 保插入序：Collections.unmodifiableMap 包裹 LinkedHashMap（Map.copyOf 迭代序未定义，
        // 25 项实测哈希序，会破坏 CC Record 字面量顺序——keySet() 迭代 = CC Object.keys 可观测序）.
        SKILL_FILES = Collections.unmodifiableMap(m);
    }

    /** 统一 N/A marker：正文不可伪造（CC 源文件 DCE 缺失），显式标注并登记恢复路径. */
    private static String na(String path) {
        return "N/A — CC 源文件缺失：Open-ClaudeCode/src/skills/bundled/claude-api/" + path
            + " 未随 checkout 提供（claudeApiContent.ts:49-75 Bun text-loader inline 依赖 .md，DCE 剔除；"
            + "OQ-12/BD-15/16/29 待拍板：不伪造正文，待 CC 源恢复后按真实 .md 替换）。";
    }

    /** 唯一实例（注册链经 {@link ClaudeApiSkillRegistrar.SkillContentSupplier} 注入）. */
    public static ClaudeApiSkillContent getInstance() {
        return INSTANCE;
    }

    @Override
    public Map<String, String> SKILL_FILES() {
        return SKILL_FILES;
    }

    @Override
    public String SKILL_PROMPT() {
        return SKILL_PROMPT;
    }

    @Override
    public Map<String, String> SKILL_MODEL_VARS() {
        return SKILL_MODEL_VARS;
    }
}
