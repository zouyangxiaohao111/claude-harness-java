package com.nexusai.domain.project;

import com.nexusai.application.agent.worktree.GitCommandRunner;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.project.dto.FileContentDto;
import com.nexusai.model.project.dto.FileNodeDto;
import com.nexusai.model.project.dto.ProjectCreateRequest;
import com.nexusai.model.project.dto.ProjectDto;
import com.nexusai.repository.project.entity.ProjectRecord;
import com.nexusai.repository.project.mapper.ProjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Project 聚合根业务逻辑（domain 层，单 ctx）：
 * - list / getById / register / remove
 *
 * <p>注意：跨 project+session 的 bind/unbind 业务（写 session.mainProjectId）已在 DDD
 * 重构中移到 {@link com.nexusai.application.project.ProjectSessionBindingService}。
 * 本类只管 Project 聚合自身的 CRUD，严格不跨 ctx 引用。
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    @Autowired private ProjectMapper projectMapper;

    /**
     * 项目文件树（IDE 项目结构视图）· git ls-files 列出仓库全部文件，组装目录树。
     *
     * <p>数据源：在项目 path 下执行 {@code git ls-files}（GitCommandRunner 不抛异常，
     * 60s 超时）。git 失败/非 git 仓库 → 返回空树（前端空态提示），不 fail-loud。
     *
     * @param id 项目 id
     * @return 根目录下 children 列表（顶层节点）
     */
    public List<FileNodeDto> listFiles(String id) {
        ProjectRecord p = projectMapper.selectOneById(id);
        if (p == null) throw new NotFoundException("Project " + id + " not found");
        String projectPath = p.getPath();
        if (projectPath == null || projectPath.isBlank()) {
            log.warn("listFiles: project {} 无 path，返回空树", id);
            return List.of();
        }
        GitCommandRunner.Result res = GitCommandRunner.run(Path.of(projectPath),
            "-c", "core.quotePath=false", "ls-files");
        if (!res.isSuccess()) {
            // [FIX] 非 git 项目 / git 命令失败 → 降级文件系统扫描（Files.walk 递归列出），而非空树
            log.warn("listFiles: git ls-files 失败 project={} exit={} stderr={} → 降级文件系统扫描",
                id, res.exitCode(), truncate(res.stderr()));
            return buildTreeFromFs(Path.of(projectPath), id);
        }
        if (log.isDebugEnabled()) {
            log.debug("listFiles: project={} git ls-files 成功，文件数={}", id,
                res.stdout() == null ? 0 : res.stdout().split("\\n").length);
        }
        return buildTree(res.stdout());
    }

    /** git ls-files stdout（每行一个相对路径）→ 目录树（目录在前，文件在后，稳定序）。 */
    static List<FileNodeDto> buildTree(String stdout) {
        if (stdout == null || stdout.isBlank()) return List.of();
        // 父目录 → 直接子路径集合（目录 + 文件；目录也可能被文件前缀重复，用 Set 去重）
        Map<String, Set<String>> byParent = new LinkedHashMap<>();
        // 遍历每个路径，把每段前缀目录 + 叶子文件登记到其父
        for (String line : stdout.split("\\n")) {
            String p = line.trim();
            if (p.isEmpty()) continue;
            String[] segs = p.split("/");
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < segs.length; i++) {
                if (i > 0) cur.append('/');
                cur.append(segs[i]);
                String curPath = cur.toString();
                String parent = parentDir(curPath);
                byParent.computeIfAbsent(parent, k -> new LinkedHashSet<>()).add(curPath);
            }
        }
        return buildLevel(byParent, "");
    }

    /**
     * 读取项目内文件内容（前端文件树点击查看真实内容 · 非 mock diff）。
     *
     * <p>安全：relativePath 拼接到项目根后 normalize，校验 resolved 仍以项目根开头
     * （防路径穿越 `../`）。文件不存在/不可读/超限 → NotFoundException。
     *
     * @param id           项目 id
     * @param relativePath 仓库相对路径（与文件树 FileNodeDto.path 一致，用 / 分隔）
     * @return 文件内容 + 大小
     */
    public FileContentDto readFile(String id, String relativePath) {
        ProjectRecord p = projectMapper.selectOneById(id);
        if (p == null) throw new NotFoundException("Project " + id + " not found");
        String projectPath = p.getPath();
        if (projectPath == null || projectPath.isBlank()) {
            throw new NotFoundException("Project " + id + " 无 path");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new NotFoundException("文件路径为空");
        }
        Path root = Paths.get(projectPath).toAbsolutePath().normalize();
        Path resolved = resolveProjectFile(root, relativePath);
        if (!Files.isRegularFile(resolved)) {
            log.warn("readFile: 非文件 project={} path={}", id, relativePath);
            throw new NotFoundException("文件不存在或不可读");
        }
        try {
            long size = Files.size(resolved);
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            if (log.isDebugEnabled()) {
                log.debug("readFile: project={} path={} size={}", id, relativePath, size);
            }
            return new FileContentDto(relativePath, content, size);
        } catch (IOException e) {
            log.warn("readFile: 读取失败 project={} path={} err={}", id, relativePath, e.getMessage());
            throw new NotFoundException("文件读取失败");
        }
    }

    /** 项目文件写入（Monaco 编辑保存 · 路径安全校验同 readFile）。 */
    public FileContentDto writeFile(String id, String relativePath, String content) {
        ProjectRecord p = projectMapper.selectOneById(id);
        if (p == null) throw new NotFoundException("Project " + id + " not found");
        String projectPath = p.getPath();
        if (projectPath == null || projectPath.isBlank()) {
            throw new NotFoundException("Project " + id + " 无 path");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new NotFoundException("文件路径为空");
        }
        Path root = Paths.get(projectPath).toAbsolutePath().normalize();
        Path resolved = resolveProjectFile(root, relativePath);
        Path parent = resolved.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            log.warn("writeFile: 父目录不存在 project={} path={}", id, relativePath);
            throw new NotFoundException("父目录不存在");
        }
        String body = content == null ? "" : content;
        try {
            Files.writeString(resolved, body, StandardCharsets.UTF_8);
            long size = Files.size(resolved);
            if (log.isDebugEnabled()) {
                log.debug("writeFile: project={} path={} size={}", id, relativePath, size);
            }
            return new FileContentDto(relativePath, body, size);
        } catch (IOException e) {
            log.warn("writeFile: 写入失败 project={} path={} err={}", id, relativePath, e.getMessage());
            throw new NotFoundException("文件写入失败");
        }
    }

    /**
     * 项目根 + 相对路径安全拼接（防路径穿越）。
     *
     * @param root         项目根绝对路径（已 normalize）
     * @param relativePath 相对路径（/ 分隔）
     * @return resolved 路径（保证在 root 内）
     * @throws NotFoundException 路径越界时
     */
    static Path resolveProjectFile(Path root, String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new NotFoundException("文件路径非法");
        }
        return resolved;
    }

    /** 递归构建一层：parentPath 下的直接子节点（目录在前，文件在后）。 */
    private static List<FileNodeDto> buildLevel(
            Map<String, Set<String>> byParent, String parentPath) {
        List<FileNodeDto> dirs = new ArrayList<>();
        List<FileNodeDto> files = new ArrayList<>();
        for (String child : byParent.getOrDefault(parentPath, Set.of())) {
            String name = child.substring(child.lastIndexOf('/') + 1);
            boolean isDir = byParent.containsKey(child); // 有子项即目录
            if (isDir) {
                dirs.add(new FileNodeDto(name, child, "dir", buildLevel(byParent, child)));
            } else {
                files.add(new FileNodeDto(name, child, "file", null));
            }
        }
        dirs.addAll(files);
        return dirs;
    }

    /**
     * 非 git 项目兜底：文件系统递归扫描（Files.walk 列出全部文件相对路径）→ 复用 buildTree 组装。
     *
     * <p>git 是优先通道（列出 git 跟踪文件，含未跟踪目录结构）；非 git 项目 / git 命令失败时
     * 降级为全文件系统扫描，保证 IDE 项目结构视图在任意目录可用（用户联调反馈：非 git 项目也要展示）。
     * 排除隐藏目录（.git/.idea/node_modules 等，避免噪音）与子目录（buildTree 按文件建树）。
     *
     * @param root 项目根目录
     * @param id   项目 id（日志用）
     * @return 文件树；扫描失败 → 空树
     */
    static List<FileNodeDto> buildTreeFromFs(Path root, String id) {
        try {
            if (!Files.isDirectory(root)) {
                log.warn("buildTreeFromFs: 非目录 project={} path={}", id, root);
                return List.of();
            }
            Path absRoot = root.toAbsolutePath().normalize();
            List<String> lines = new ArrayList<>();
            try (java.util.stream.Stream<Path> walk = Files.walk(absRoot)) {
                walk.filter(Files::isRegularFile)
                    .forEach(p -> {
                        String rel = absRoot.relativize(p).toString().replace('\\', '/');
                        if (rel.isBlank() || isHiddenPath(rel)) return;
                        lines.add(rel);
                    });
            }
            if (log.isDebugEnabled()) {
                log.debug("buildTreeFromFs: project={} 文件系统扫描文件数={}", id, lines.size());
            }
            return buildTree(String.join("\n", lines));
        } catch (IOException e) {
            log.warn("buildTreeFromFs: 文件系统扫描失败 project={} path={} err={}",
                id, root, e.getMessage());
            return List.of();
        }
    }

    /** 排除隐藏目录（git 内部 / IDE / 依赖等）：.git/.idea/.vs/node_modules/dist/target 等前缀段。 */
    private static boolean isHiddenPath(String rel) {
        for (String seg : rel.split("/")) {
            if (seg.equals(".git") || seg.equals(".idea") || seg.equals(".vs")
                || seg.equals("node_modules") || seg.equals("dist") || seg.equals("target")
                || seg.equals(".gradle") || seg.equals("build")) {
                return true;
            }
        }
        return false;
    }

    /** 路径的直接父目录（"a/b/c" → "a/b"；"a" → ""）。 */
    private static String parentDir(String path) {
        int idx = path.lastIndexOf('/');
        return idx < 0 ? "" : path.substring(0, idx);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    public List<ProjectDto> listAll() {
        List<ProjectRecord> all = projectMapper.selectAll();
        return all.stream().map(this::toDto).toList();
    }

    public ProjectDto getById(String id) {
        ProjectRecord p = projectMapper.selectOneById(id);
        if (p == null) throw new NotFoundException("Project " + id + " not found");
        return toDto(p);
    }

    public ProjectDto register(ProjectCreateRequest req) {
        // [2026-08-24 cwd 污染修复 · 用户拍板源头根治] 项目路径恒绝对（对齐 CC getOriginalCwd 启动目录）：
        //   前端可能传相对路径（如「抓包流程」）→ 转绝对 + 校验目录存在，否则绑定后 CwdResolution
        //   返回无效 cwd 致 Bash/Glob/Read 全失败。CwdResolution/setForSession 校验保留为防御层。
        String absPath = normalizeProjectPath(req.path());
        ProjectRecord p = new ProjectRecord();
        p.setId(generateId("proj"));
        p.setName(req.name());
        p.setPath(absPath);
        p.setBranch("main");
        p.setDirty(0);
        p.setAgents(0);
        p.setLastIndexedAt(null);
        p.setBound(Boolean.FALSE);
        projectMapper.insert(p);
        return toDto(p);
    }

    /** 项目路径归一化 · 相对 → 绝对（JVM cwd）+ normalize；目录不存在/非法 → 400（对齐 CC 启动 cwd 恒绝对）。 */
    private static String normalizeProjectPath(String path) {
        if (path == null || path.isBlank()) {
            throw new ValidationException("project path is required");
        }
        try {
            Path abs = Path.of(path).toAbsolutePath().normalize();
            if (!Files.isDirectory(abs)) {
                throw new ValidationException("project directory does not exist: " + abs);
            }
            return abs.toString();
        } catch (InvalidPathException e) {
            throw new ValidationException("invalid project path: " + path);
        }
    }

    public void remove(String id) {
        ProjectRecord p = projectMapper.selectOneById(id);
        if (p == null) throw new NotFoundException("Project " + id + " not found");
        projectMapper.deleteById(id);
    }

    // ============== helpers ==============

    private ProjectDto toDto(ProjectRecord p) {
        return new ProjectDto(
            p.getId(),
            p.getName(),
            p.getPath(),
            p.getBranch(),
            p.getDirty(),
            p.getAgents(),
            parseDateTime(p.getLastIndexedAt()),
            Boolean.TRUE.equals(p.getBound())
        );
    }

    private static String generateId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static OffsetDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try { return OffsetDateTime.parse(s); } catch (Exception e) { return null; }
    }
}
