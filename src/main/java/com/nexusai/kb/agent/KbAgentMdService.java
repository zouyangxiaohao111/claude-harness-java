package com.nexusai.kb.agent;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.AgentDefinitionRegistry;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 智能体 MD 文件账本 · 批次 W10。
 *
 * <p><b>⭐ MD 是本体</b>（用户 2026-11-05 裁定）：
 * <pre>
 *   agents/&lt;id&gt;.md   ← 系统提示词 / 模型 / 工具 / 权限模式 / 技能（这里读写）
 *   kb_agent（DB）      ← 只存 MD 装不下的扩展（{@link KbAgentService}）
 * </pre>
 * ⇒ 界面「新建智能体」必须**同时**写 MD 与本表，否则会出现「DB 有行、实际不存在」的孤儿。
 *
 * <p><b>目录</b>：用户级 = {@code ~/.{appName}/agents}（{@link NexusaiPaths#getAppConfigHomeDir()}），
 * 与 {@code loadAgentsDir} 扫描的 userSettings 源同源。
 *
 * <p><b>⛔ 不直接读注册表来写文件</b>：注册表是「加载后的内存视图」，写文件后需要重新扫描
 * 才生效。本类只负责文件字节，刷新由调用方（{@link AgentDefinitionRegistry#merge}）决定。
 */
@Service
public class KbAgentMdService {

    private static final Logger log = LoggerFactory.getLogger(KbAgentMdService.class);

    /**
     * 「可直接当文件名」的 id 形态（ASCII 字母数字 + 连字符/下划线）。
     *
     * <p>⚠️ 这是**文件名规则**，不是 id 规则 —— agent id 就是 MD 的 frontmatter {@code name}
     * （{@code loadAgentsDir.java:517} 用 name 当 agentType），**可以是中文**
     * （现有 {@code 农污助手} 就是）。中文 id 走 {@link #fileNameFor} 的哈希后缀分支。
     */
    private static final Pattern FILENAME_SAFE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$");

    /** id 长度上限（frontmatter 是 YAML 单行，过长没有意义）。 */
    private static final int MAX_ID_LENGTH = 100;

    /**
     * Agent 注册表缓存失效入口（{@code SubagentTool}）。
     *
     * <p>⚠️ 之前这里注入的是 {@link AgentDefinitionRegistry} 本身 —— 它**不是 Spring bean**
     * （由 {@code SubagentTool} 内部按 cwd 构造），字段恒 null，等于没有刷新。
     * 真正要清的缓存住在 {@code SubagentTool.registriesByCwd}。
     */
    @Autowired(required = false)
    private com.nexusai.application.agent.tool.impl.SubagentTool subagentTool;

    /**
     * agents 目录（用户级）。
     *
     * @return {@code ~/.{appName}/agents}
     */
    public Path agentsDir() {
        return Paths.get(NexusaiPaths.getAppConfigHomeDir()).resolve("agents");
    }

    /**
     * 校验并规范化 agent id。
     *
     * <p><b>⭐ id = frontmatter {@code name} = agentType</b>（{@code loadAgentsDir.java:517}）。
     * 所以它**可以带中文、空格**（现有 {@code 农污助手} 就是），只是不能含路径分隔符或
     * 控制字符 —— 那是安全边界：id 最终会映射成文件名，{@code ../} 能写出 agents 目录。
     *
     * @param agentId 原始 id
     * @return trim 后的 id
     */
    public String requireSafeId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new ValidationException("agent id 不能为空");
        }
        String id = agentId.trim();
        if (id.length() > MAX_ID_LENGTH) {
            throw new ValidationException("agent id 最长 " + MAX_ID_LENGTH + " 字符");
        }
        if (id.contains("/") || id.contains("\\") || id.contains("..")) {
            throw new ValidationException("agent id 不能包含路径分隔符或 '..'（收到: " + id + "）");
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new ValidationException("agent id 不能包含控制字符");
            }
        }
        return id;
    }

    /**
     * id → 文件名（不含目录）。
     *
     * <p>ASCII 安全 id 直接用；含中文等字符时用「净化后的可读前缀 + id 哈希前 8 位」，
     * 保证**确定性且不碰撞**（同一 id 永远同一文件名，不同 id 不会撞车）。
     *
     * @param agentId 已校验的 id
     * @return 文件名（{@code <slug>.md}）
     */
    static String fileNameFor(String agentId) {
        if (FILENAME_SAFE.matcher(agentId).matches()) {
            return agentId + ".md";
        }
        StringBuilder slug = new StringBuilder();
        for (int i = 0; i < agentId.length() && slug.length() < 40; i++) {
            char c = agentId.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                slug.append(c);
            } else if (c == '-' || c == '_') {
                slug.append(c);
            } else if (slug.length() > 0 && slug.charAt(slug.length() - 1) != '-') {
                slug.append('-');
            }
        }
        String readable = slug.length() == 0 ? "agent" : slug.toString();
        return readable + "-" + shortHash(agentId) + ".md";
    }

    /** id 的短哈希（MD5 前 8 位十六进制）· 只为文件名唯一性，不用于安全判定。 */
    private static String shortHash(String id) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(id.hashCode());
        }
    }

    /**
     * MD 文件路径。
     *
     * @param agentId 已校验的 id
     * @return {@code ~/.{appName}/agents/<slug>.md}
     */
    public Path mdPath(String agentId) {
        return agentsDir().resolve(fileNameFor(requireSafeId(agentId)));
    }

    /**
     * MD 是否存在。
     *
     * @param agentId id
     * @return true = 文件存在
     */
    public boolean exists(String agentId) {
        try {
            return Files.isRegularFile(mdPath(agentId));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 列出磁盘上全部智能体的 id（= frontmatter {@code name}）。
     *
     * <p>⚠️ <b>不能拿文件名当 id</b>：id 可能是中文（{@code 农污助手}），
     * 而文件名会被净化成 {@code ---<hash>.md}。必须读每个文件的 frontmatter。
     * 读不出 name 的文件**跳过**（{@code loadAgentsDir} 也会跳过它，两侧保持一致）。
     *
     * @return 按 id 排序的列表
     */
    public List<String> listMdIds() {
        Path dir = agentsDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .toList()) {
                try {
                    String name = Frontmatter.parse(
                        Files.readString(p, StandardCharsets.UTF_8)).string("name");
                    if (name != null && !name.isBlank()) {
                        out.add(name.trim());
                    }
                } catch (IOException e) {
                    log.warn("[kb_agent] 读 {} 失败，跳过: {}", p, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("[kb_agent] 列 agents 目录失败 dir={}: {}", dir, e.toString());
            return List.of();
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * 从注册表取已加载的定义（用于「界面显示但要的是加载后的真实状态」的场景）。
     *
     * @param agentId id
     * @return 定义；注册表不可用或未加载返回 {@code null}
     */
    public AgentDefinition loadedDefinition(String agentId) {
        if (subagentTool == null || agentId == null) {
            return null;
        }
        try {
            return subagentTool.agentRegistry(null).findAgent(agentId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 让新建/删除的 MD 立即对现有会话可见。
     *
     * <p>不调用的话：写完文件后 {@code PATCH /sessions/{id}} 会报「agent 类型不存在」，
     * 删掉的智能体也还能继续绑定 —— 都不报「文件没刷新」。
     */
    private void invalidateRegistries() {
        if (subagentTool == null) {
            return;
        }
        try {
            subagentTool.invalidateAgentRegistries();
        } catch (RuntimeException e) {
            log.warn("[kb_agent] 失效 Agent 注册表缓存失败（文件已写入，重启后生效）: {}", e.toString());
        }
    }

    /**
     * 写（或覆盖）一个智能体 MD。
     *
     * @param agentId 已校验的 id
     * @param req     MD 内容
     * @return 写入的文件路径
     */
    public Path write(String agentId, MdContent req) {
        String id = requireSafeId(agentId);
        if (req == null || req.body() == null || req.body().isBlank()) {
            throw new ValidationException("系统提示词（MD 正文）不能为空");
        }
        // ⚠️ description 是**必需**的：loadAgentsDir.java:544-552 缺 description 会**整文件跳过**
        //    （对齐 CC parseAgentFromMarkdown:557）。不在这里拦，用户会看到「创建成功但智能体不生效」。
        if (req.description() == null || req.description().isBlank()) {
            throw new ValidationException("description（一句话描述）不能为空 —— 缺它智能体不会被加载");
        }
        Path dir = agentsDir();
        // ⚠️ 必须与 mdPath() 同一映射（中文 id 会走哈希文件名）—— 两处各写一遍就会分叉。
        Path file = dir.resolve(fileNameFor(id));
        try {
            Files.createDirectories(dir);
            String content = renderMd(id, req);
            Files.writeString(file, content, StandardCharsets.UTF_8);
            log.info("[kb_agent] MD 已写入 id={} path={} 字节={}", id, file, content.length());
            invalidateRegistries();
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("写智能体文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取 MD 原文。
     *
     * @param agentId id
     * @return 原文
     */
    public String readRaw(String agentId) {
        Path file = mdPath(agentId);
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("智能体文件不存在: " + agentId);
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读智能体文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 MD 的简化视图（frontmatter 常用键 + 正文）。
     *
     * <p>⚠️ 这是**展示用**的宽松解析：真正的 frontmatter 解析真源是
     * {@code loadAgentsDir}（含 CC 兼容的多种写法）。本方法只取界面要显示的键，
     * 解析不到就不给（⛔ 不猜）。
     *
     * @param agentId id
     * @return 视图
     */
    public MdView readView(String agentId) {
        String raw = readRaw(agentId);
        Frontmatter fm = Frontmatter.parse(raw);
        return new MdView(
            agentId,
            fm.string("name"),
            fm.string("description"),
            fm.string("tools"),
            fm.string("model"),
            fm.firstOf("main-model", "mainModel"),
            fm.string("color"),
            fm.body());
    }

    /**
     * 删除 MD（不存在则无操作）。
     *
     * @param agentId id
     * @return true = 真的删了文件
     */
    public boolean delete(String agentId) {
        Path file = mdPath(agentId);
        try {
            boolean removed = Files.deleteIfExists(file);
            if (removed) {
                log.info("[kb_agent] MD 已删除 id={} path={}", agentId, file);
                invalidateRegistries();
            }
            return removed;
        } catch (IOException e) {
            throw new IllegalStateException("删除智能体文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 复制 MD（新 id 必须不存在）。
     *
     * @param sourceId 源 id
     * @param targetId 目标 id
     * @param newName  新显示名（null = 沿用源名）
     * @return 新文件路径
     */
    public Path copy(String sourceId, String targetId, String newName) {
        MdView src = readView(sourceId);
        if (exists(targetId)) {
            throw new ConflictException("智能体已存在: " + targetId);
        }
        String name = newName == null || newName.isBlank() ? src.name() : newName;
        return write(targetId, new MdContent(
            name,
            src.description(),
            src.tools(),
            src.model(),
            src.mainModel(),
            src.color(),
            src.body()));
    }

    // ── MD 渲染 ────────────────────────────────────────────

    /**
     * 渲染 MD 文本。
     *
     * <p>只写**有值**的键 —— 空值不写，让下游「缺省 = 继承/通配」语义成立
     * （⛔ 不要写 {@code tools: ""}，那会被解析成「空工具集」而不是「全部」）。
     */
    private String renderMd(String id, MdContent c) {
        StringBuilder sb = new StringBuilder("---\n");
        // ⭐ name 恒等于 id：它就是 agentType（loadAgentsDir.java:517），不是另一个显示名。
        //   写别的值会让注册表里的 agentType 与接口返回的 id 对不上 ⇒ 绑定时「类型不存在」。
        appendKey(sb, "name", id);
        appendKey(sb, "description", c.description());
        appendKey(sb, "tools", c.tools());
        appendKey(sb, "model", c.model());
        appendKey(sb, "main-model", c.mainModel());
        appendKey(sb, "color", c.color());
        sb.append("---\n\n");
        sb.append(c.body().strip()).append('\n');
        return sb.toString();
    }

    private static void appendKey(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        // ⚠️ 值里有换行会打断 frontmatter ⇒ 压成单行（description 常见多行）
        String oneLine = value.replace("\r", " ").replace("\n", " ").trim();
        sb.append(key).append(": ").append(oneLine).append('\n');
    }

    // ── 载体 ───────────────────────────────────────────────

    /**
     * 写入 MD 的内容。
     *
     * @param name        显示名（frontmatter {@code name}）
     * @param description 一句话描述（列表里显示的）
     * @param tools       工具白名单（逗号分隔；null/空 = 通配全部通用工具）
     * @param model       子代理模型（{@code provider/name}；null/空 = 继承父线程）
     * @param mainModel   主线程模型（{@code provider/name}；null/空 = 用会话当前模型）
     * @param color       列表颜色标记
     * @param body        系统提示词（MD 正文）
     */
    public record MdContent(
        String name, String description, String tools,
        String model, String mainModel, String color, String body
    ) {
    }

    /**
     * MD 的展示视图。
     *
     * @param id          智能体 id
     * @param name        显示名
     * @param description 描述
     * @param tools       工具白名单原文
     * @param model       子代理模型原文
     * @param mainModel   主线程模型原文
     * @param color       颜色
     * @param body        系统提示词
     */
    public record MdView(
        String id, String name, String description, String tools,
        String model, String mainModel, String color, String body
    ) {
    }

    // ── 极简 frontmatter 解析 ──────────────────────────────

    /** 展示用的宽松 frontmatter 解析（只认 {@code key: value} 单行形式）。 */
    static final class Frontmatter {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final String body;

        private Frontmatter(Map<String, String> values, String body) {
            this.values.putAll(values);
            this.body = body;
        }

        static Frontmatter parse(String raw) {
            String text = raw == null ? "" : raw.replace("\r\n", "\n");
            if (!text.startsWith("---\n")) {
                return new Frontmatter(Map.of(), text);
            }
            int end = text.indexOf("\n---", 4);
            if (end < 0) {
                return new Frontmatter(Map.of(), text);
            }
            String head = text.substring(4, end);
            int bodyStart = text.indexOf('\n', end + 4);
            String body = bodyStart < 0 ? "" : text.substring(bodyStart + 1).strip();
            Map<String, String> m = new LinkedHashMap<>();
            for (String line : head.split("\n")) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String k = line.substring(0, colon).trim();
                String v = line.substring(colon + 1).trim();
                if (!k.isEmpty() && !v.isEmpty()) {
                    m.put(k, v);
                }
            }
            return new Frontmatter(m, body);
        }

        String string(String key) {
            return values.get(key);
        }

        String firstOf(String... keys) {
            for (String k : keys) {
                String v = values.get(k);
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
            return null;
        }

        String body() {
            return body;
        }

        /** 已解析出的键（调试用）。 */
        List<String> keys() {
            return new ArrayList<>(values.keySet());
        }
    }
}
