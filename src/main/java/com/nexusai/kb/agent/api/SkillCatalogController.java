package com.nexusai.kb.agent.api;

import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.infra.exception.ConflictException;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.command.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 技能目录（Skills catalog）· 批次 W10 · 8 条端点。
 *
 * <p><b>⭐⭐ 为什么不挂 {@code /api/v1/skills}</b>：该路径已被
 * {@code SkillController}（已 {@code @Deprecated} 但仍在服务旧前端）占用。
 * 本批挂 {@code /api/v1/kb/skills}，语义是「知识库场景的技能目录」，也不与旧的混。
 *
 * <p><b>⭐ 用户裁定：走本仓现有文件系统逻辑</b>
 * （「skill 走我们现有的逻辑」）⇒
 * <ul>
 *   <li><b>catalog = 文件系统技能的视图</b>（{@code ~/.claude/skills/<name>/SKILL.md}）</li>
 *   <li><b>install = 落盘即生效</b>（本仓没有沙箱镜像，源侧的「推到沙箱 + 快照」没有对应物）</li>
 * </ul>
 * ⛔ 不新建 DB 目录表 —— 那会与 {@code SkillRegistry} 的文件系统真源形成两份。
 */
@RestController
@RequestMapping("/api/v1/kb/skills")
public class SkillCatalogController {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalogController.class);

    /** 技能名只允许安全字符（会拼进目录名）。 */
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /** 单个文件读取上限（防止一次把超大二进制读进内存）。 */
    private static final long MAX_FILE_BYTES = 512 * 1024;

    @Autowired(required = false)
    private SkillRegistry skillRegistry;

    /**
     * 当前沙箱/本机可执行的技能（源侧 {@code GET /skills}）。
     *
     * <p>⭐ 与 {@link #catalog()} 的区别：这里是**注册表实际加载结果**
     * （含内置、插件、被启停门控过滤后的），catalog 是**磁盘目录视图**。
     * 两者不一致时（文件在但没加载）说明被 feature 门或启用开关挡住了。
     *
     * @param sessionId 可选会话（技能的 project 级发现依赖会话 cwd）
     * @return 可执行技能
     */
    @GetMapping
    public List<SkillItem> executable(@RequestParam(required = false) String sessionId) {
        if (skillRegistry == null) {
            return List.of();
        }
        List<SkillItem> out = new ArrayList<>();
        for (Command c : skillRegistry.getAllCommands(sessionId)) {
            if (c == null || c.getName() == null) {
                continue;
            }
            out.add(new SkillItem(
                c.getName(), c.getDescription(), String.valueOf(c.getSource()),
                c.getModel(), c.getEffort(), true));
        }
        out.sort(Comparator.comparing(SkillItem::name));
        return out;
    }

    /**
     * 技能目录（源侧 {@code GET /skills/catalog}）。
     *
     * <p>扫 {@code <skillsRoot>/<name>/SKILL.md}，读 frontmatter 的 name/description。
     *
     * @return 目录项
     */
    @GetMapping("/catalog")
    public List<CatalogItem> catalog() {
        Path root = skillsRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<CatalogItem> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path md = dir.resolve("SKILL.md");
                // ⚠️ 断链的符号链接（本机实测有）→ isRegularFile 返回 false，跳过即可
                if (!Files.isRegularFile(md)) {
                    continue;
                }
                try {
                    String raw = Files.readString(md, StandardCharsets.UTF_8);
                    SimpleFrontmatter fm = SimpleFrontmatter.parse(raw);
                    String name = fm.value("name");
                    out.add(new CatalogItem(
                        dir.getFileName().toString(),
                        name == null ? dir.getFileName().toString() : name,
                        fm.value("description"),
                        fm.value("version"),
                        hasBundleFiles(dir)));
                } catch (IOException e) {
                    log.warn("[kb skills] 读 {} 失败，跳过: {}", md, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("[kb skills] 列技能目录失败 root={}: {}", root, e.toString());
            return List.of();
        }
        out.sort(Comparator.comparing(CatalogItem::id));
        return out;
    }

    /**
     * 单个技能详情（源侧 {@code GET /skills/catalog/{id}}）。
     *
     * @param id 技能目录名
     * @return 详情（含全文）
     */
    @GetMapping("/catalog/{id}")
    public CatalogDetail get(@PathVariable String id) {
        Path dir = skillDir(id);
        Path md = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(md)) {
            throw new NotFoundException("技能不存在: " + id);
        }
        try {
            String raw = Files.readString(md, StandardCharsets.UTF_8);
            SimpleFrontmatter fm = SimpleFrontmatter.parse(raw);
            return new CatalogDetail(
                id,
                fm.value("name") == null ? id : fm.value("name"),
                fm.value("description"),
                fm.value("version"),
                raw,
                listFiles(dir));
        } catch (IOException e) {
            throw new IllegalStateException("读技能失败：" + e.getMessage(), e);
        }
    }

    /**
     * 技能目录内的文件清单（源侧 {@code GET /skills/catalog/{id}/files}）。
     *
     * @param id 技能目录名
     * @return 相对路径清单（含大小）
     */
    @GetMapping("/catalog/{id}/files")
    public List<FileEntry> files(@PathVariable String id) {
        Path dir = skillDir(id);
        if (!Files.isDirectory(dir)) {
            throw new NotFoundException("技能不存在: " + id);
        }
        return listFiles(dir);
    }

    /**
     * 读技能目录内的一个文件（源侧 {@code GET /skills/catalog/{id}/files/content}）。
     *
     * <p>⚠️ {@code path} 是**相对技能的路径**，必须挡住 {@code ../} —— 否则能读到整台机器的文件。
     *
     * @param id   技能目录名
     * @param path 相对路径
     * @return 内容（文本）
     */
    @GetMapping("/catalog/{id}/files/content")
    public FileContent fileContent(@PathVariable String id, @RequestParam String path) {
        Path dir = skillDir(id);
        Path base = dir.toAbsolutePath().normalize();
        Path target = base.resolve(path).normalize();
        if (!target.startsWith(base)) {
            throw new ValidationException("path 必须位于技能目录内（收到: " + path + "）");
        }
        if (!Files.isRegularFile(target)) {
            throw new NotFoundException("文件不存在: " + path);
        }
        try {
            long size = Files.size(target);
            if (size > MAX_FILE_BYTES) {
                throw new ValidationException("文件过大（" + size + " 字节，上限 " + MAX_FILE_BYTES
                    + "）—— 请直接在本机打开: " + target);
            }
            return new FileContent(id, path, size,
                Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读文件失败：" + e.getMessage(), e);
        }
    }

    /**
     * 新增技能到目录（源侧 {@code POST /skills/catalog}）。
     *
     * <p>本仓语义 = **写 {@code <root>/<name>/SKILL.md} 落盘**，写完即被
     * {@link SkillRegistry} 下次扫描识别（与 MD 智能体同理，需要注册表刷新时由调用方触发）。
     *
     * @param req 请求体
     * @return 创建后的详情
     */
    @PostMapping("/catalog")
    public CatalogDetail create(@RequestBody CreateRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ValidationException("name 不能为空");
        }
        String name = requireSafeName(req.name());
        Path dir = skillDir(name);
        if (Files.exists(dir)) {
            throw new ConflictException("技能已存在: " + name);
        }
        writeSkill(dir, name, req.description(), req.instructions(), req.version());
        log.info("[kb skills] 技能已创建 name={} path={}", name, dir);
        return get(name);
    }

    /**
     * 删除技能（源侧 {@code DELETE /skills/catalog/{id}}）。
     *
     * <p>⚠️ 只删**技能目录**，⛔ 不碰 {@code ~/.claude/skills} 下的符号链接目标
     * （本机实测有 `xxx -> ~/.agents/skills/xxx` 的外部目录，删了会伤到用户全局技能）。
     *
     * @param id 目录名
     * @return 删除结果
     */
    @DeleteMapping("/catalog/{id}")
    public DeleteResult delete(@PathVariable String id) {
        Path dir = skillDir(id);
        if (!Files.exists(dir)) {
            throw new NotFoundException("技能不存在: " + id);
        }
        if (Files.isSymbolicLink(dir)) {
            throw new ValidationException("该技能是外部目录的符号链接（"
                + readLink(dir) + "），不在本仓删除范围内，请到源目录处理");
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new IllegalStateException("删除技能失败：" + e.getMessage(), e);
        }
        log.info("[kb skills] 技能已删除 name={}", id);
        return new DeleteResult(id, true);
    }

    /**
     * 安装技能到沙箱（源侧 {@code POST /skills/catalog/{id}/install}）。
     *
     * <p>⚠️ <b>本仓没有沙箱镜像</b> ⇒ 这里只做**落盘校验**：确认 {@code SKILL.md} 存在且可读，
     * 然后如实返回 {@code installed:false} + 原因。⛔ 不假装安装成功 ——
     * 源侧的「推到沙箱镜像 + 生成快照」在本仓没有对应物。
     *
     * @param id 目录名
     * @return 结果（如实说明本仓无沙箱）
     */
    @PostMapping("/catalog/{id}/install")
    public InstallResult install(@PathVariable String id) {
        Path dir = skillDir(id);
        Path md = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(md)) {
            throw new NotFoundException("技能不存在: " + id);
        }
        log.info("[kb skills] install 请求 name={} —— 本仓无沙箱，文件已在盘上即视为可用", id);
        return new InstallResult(id, false,
            "本仓没有沙箱镜像机制：技能直接放在本机技能目录即可被加载，无需安装步骤。"
                + "文件已存在且可读，SkillRegistry 下次扫描时会识别。");
    }

    // ── 文件系统 ───────────────────────────────────────────

    /** 技能根目录（{@code ~/.{appName}/skills}，与 SkillRegistry 构造参数同源）。 */
    Path skillsRoot() {
        return Paths.get(com.nexusai.application.agent.skill.NexusaiPaths.getAppConfigHomeDir())
            .resolve("skills");
    }

    private Path skillDir(String id) {
        return skillsRoot().resolve(requireSafeName(id));
    }

    private static String requireSafeName(String id) {
        if (id == null || id.isBlank()) {
            throw new ValidationException("技能名不能为空");
        }
        String n = id.trim();
        if (!SAFE_NAME.matcher(n).matches()) {
            throw new ValidationException(
                "技能名只能含字母数字与 . _ -，且不超过 64 字符（收到: " + n + "）");
        }
        return n;
    }

    private static String readLink(Path p) {
        try {
            return Files.readSymbolicLink(p).toString();
        } catch (IOException e) {
            return "?";
        }
    }

    private static boolean hasBundleFiles(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.anyMatch(p -> !p.getFileName().toString().equalsIgnoreCase("SKILL.md"));
        } catch (IOException e) {
            return false;
        }
    }

    private static List<FileEntry> listFiles(Path dir) {
        List<FileEntry> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                long size;
                try {
                    size = Files.size(p);
                } catch (IOException e) {
                    size = -1;
                }
                out.add(new FileEntry(rel, size));
            }
        } catch (IOException e) {
            log.warn("[kb skills] 列文件失败 dir={}: {}", dir, e.toString());
            return List.of();
        }
        out.sort(Comparator.comparing(FileEntry::path));
        return out;
    }

    private void writeSkill(Path dir, String name, String description,
                            String instructions, String version) {
        if (instructions == null || instructions.isBlank()) {
            throw new ValidationException("instructions（技能正文）不能为空");
        }
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("name: ").append(name).append('\n');
        if (description != null && !description.isBlank()) {
            sb.append("description: ")
              .append(description.replace("\r", " ").replace("\n", " ").trim()).append('\n');
        }
        if (version != null && !version.isBlank()) {
            sb.append("version: ").append(version.trim()).append('\n');
        }
        sb.append("---\n\n").append(instructions.strip()).append('\n');
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写技能失败：" + e.getMessage(), e);
        }
    }

    // ── DTO ────────────────────────────────────────────────

    /** 可执行技能项。 */
    public record SkillItem(String name, String description, String source,
                            String model, String effort, boolean loaded) {
    }

    /** 目录项。 */
    public record CatalogItem(String id, String name, String description,
                              String version, boolean hasBundleFiles) {
    }

    /** 详情。 */
    public record CatalogDetail(String id, String name, String description, String version,
                                String content, List<FileEntry> files) {
    }

    /** 文件项。 */
    public record FileEntry(String path, long size) {
    }

    /** 文件内容。 */
    public record FileContent(String id, String path, long size, String content) {
    }

    /** 创建请求。 */
    public record CreateRequest(String name, String description,
                                String instructions, String version) {
    }

    /** 删除结果。 */
    public record DeleteResult(String id, boolean deleted) {
    }

    /** 安装结果（本仓恒 installed=false + 原因）。 */
    public record InstallResult(String id, boolean installed, String note) {
    }

    // ── 极简 frontmatter ───────────────────────────────────

    /** 只认单行 {@code key: value} 的宽松解析（技能 frontmatter 用不到嵌套结构）。 */
    static final class SimpleFrontmatter {
        private final java.util.Map<String, String> values;

        private SimpleFrontmatter(java.util.Map<String, String> values) {
            this.values = values;
        }

        static SimpleFrontmatter parse(String raw) {
            String text = raw == null ? "" : raw.replace("\r\n", "\n");
            if (!text.startsWith("---\n")) {
                return new SimpleFrontmatter(java.util.Map.of());
            }
            int end = text.indexOf("\n---", 4);
            if (end < 0) {
                return new SimpleFrontmatter(java.util.Map.of());
            }
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            for (String line : text.substring(4, end).split("\n")) {
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
            return new SimpleFrontmatter(m);
        }

        String value(String key) {
            return values.get(key);
        }
    }
}
