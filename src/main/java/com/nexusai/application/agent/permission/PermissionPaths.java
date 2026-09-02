package com.nexusai.application.agent.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 权限检查路径展开 · 对齐 CC {@code utils/fsOperations.ts:272-382 getPathsForPermissionCheck}。
 *
 * <p><b>CC 语义（fsOperations.ts，本次逐行自验）</b>：返回应检查权限的<b>全部路径</b>——
 * 原始路径 + symlink 链上的全部中间目标 + 最终解析路径。例如
 * {@code test.txt -> /etc/passwd -> /private/etc/passwd} 三跳全部收集：
 * deny 规则命中任一跳即拒绝（防 {@code ./evil.txt -> ~/.ssh/authorized_keys2}、
 * {@code ./data -> /etc/cron.d/} 等悬空/越界 symlink 绕过 deny 规则与工作目录判定）。
 *
 * <p><b>实现对照（fsOperations.ts 行号当次自验）</b>：
 * <ol>
 *   <li>tilde 防御性展开（{@code ~} / {@code ~/}，工具应在 getPath 处理，此处兜底，:289-296）；
 *       注（[FIX-A-R2]）：{@link ReadPermissionChecker#expandPath} 现委托 PathGuard 展开
 *       {@code ~} 与相对路径 → 绝对（含 normalize）；本方法 tilde 分支仅保留为直接调用方
 *       （绕过 expandPath）传原始 {@code ~} 时的防御性兜底，不归一化 {@code ..}（CC path.join
 *       会归一化，Java 保留原始形态后由 {@link #resolveDeepestExistingAncestor} 把 OS 实际落点
 *       追加进集合，拒绝面 ⊇ CC，fail-closed 方向）；</li>
 *   <li>UNC 路径（{@code //} / {@code \\}）在任何文件系统访问前拦截
 *       （防校验期 DNS/SMB 网络请求，:304-308）；</li>
 *   <li>symlink 链跟随：visited 防环 + 深度上限 40（SYMLOOP_MAX 惯例，:313-369）；
 *       <ul>
 *         <li>路径不存在（新建文件 / 悬空 symlink，existsSync 跟随 symlink 两者同达）
 *             → {@link #resolveDeepestExistingAncestor} 解析最深存在祖先，补真实落点
 *             （:325-339）；</li>
 *         <li>特殊文件类型（FIFO/socket/字符设备/块设备）跳过（:344-352，
 *             Java 以 {@link BasicFileAttributes#isOther()} 覆盖）；</li>
 *         <li>相对目标相对 symlink 所在目录解析（:358-364）；</li>
 *       </ul></li>
 *   <li>收尾 safeResolvePath（:374-379）：realpath 最终形态补入集合，覆盖目录组件残余 symlink。</li>
 * </ol>
 *
 * <p><b>消费方</b>：{@link ReadPermissionChecker}（checkReadPermissionForTool 等价物，
 * filesystem.ts:1043-1048 单次计算透传 UNC/suspicious/deny/ask/working-dir 全链）、
 * {@link WritePermissionChecker}（checkWritePermissionForTool 等价物，
 * filesystem.ts:1219-1221 precomputedPathsToCheck 透传 deny/safety/ask/working-dir 全链）。
 */
public final class PermissionPaths {

    private static final Logger log = LoggerFactory.getLogger(PermissionPaths.class);

    /** symlink 链深度上限，防环状链接死循环（对齐 CC fsOperations.ts:316，SYMLOOP_MAX 惯例）。 */
    static final int MAX_SYMLINK_DEPTH = 40;

    private PermissionPaths() {
        // 工具类，禁止实例化
    }

    /**
     * 权限检查路径展开 · 对齐 CC {@code getPathsForPermissionCheck}（fsOperations.ts:288-382）。
     *
     * @param inputPath 待检查路径（工具通常已传绝对路径）
     * @return 应检查权限的全部绝对路径（原始 + symlink 链 + 最终解析；去重保序）
     */
    public static List<String> getPathsForPermissionCheck(String inputPath) {
        // tilde 防御性展开（CC :289-296：'~' 恒展开，'~/' 前缀拼接 home）
        String path = inputPath;
        if ("~".equals(path)) {
            path = homedir();
        } else if (path.startsWith("~/")) {
            path = homedir() + "/" + path.substring(2);
        }

        LinkedHashSet<String> pathSet = new LinkedHashSet<>();
        // 恒检查原始路径（CC :301-302）
        pathSet.add(path);

        // UNC 路径在任何文件系统访问之前拦截（防 Windows 校验期 DNS/SMB 网络请求，CC :304-308）
        if (path.startsWith("//") || path.startsWith("\\\\")) {
            if (log.isDebugEnabled()) {
                log.debug("[PermissionPaths] UNC 路径提前返回（不触碰文件系统）: path={}", path);
            }
            return List.copyOf(pathSet);
        }

        // 沿 symlink 链收集全部中间目标（test.txt -> /etc/passwd -> /private/etc/passwd 全查，CC :310-372）
        try {
            String currentPath = path;
            Set<String> visited = new HashSet<>();
            for (int depth = 0; depth < MAX_SYMLINK_DEPTH; depth++) {
                // 环状 symlink 防死循环（CC :319-323）
                if (visited.contains(currentPath)) {
                    break;
                }
                visited.add(currentPath);

                if (!Files.exists(Paths.get(currentPath))) {
                    // 路径不存在（新建文件场景）：existsSync 跟随 symlink，悬空 symlink 也到达此处。
                    // 解析路径及祖先中的 symlink，使权限检查看到真实落点——否则
                    // `./data -> /etc/cron.d/`（活父 symlink）或 `./evil.txt -> ~/.ssh/authorized_keys2`
                    // （悬空文件 symlink）的写入会逃逸工作目录（CC :325-339）。
                    if (currentPath.equals(path)) {
                        String resolved = resolveDeepestExistingAncestor(currentPath);
                        if (resolved != null) {
                            pathSet.add(resolved);
                        }
                    }
                    break;
                }

                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(
                        Paths.get(currentPath), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    break;
                }
                // 跳过特殊文件类型（FIFO/socket/字符设备/块设备，CC :344-352；Java isOther 覆盖）
                if (attrs.isOther()) {
                    break;
                }
                if (!attrs.isSymbolicLink()) {
                    break;
                }

                // 取 symlink 立即目标；相对目标相对 symlink 所在目录解析（CC :358-364）
                String target = Files.readSymbolicLink(Paths.get(currentPath)).toString();
                Path targetPath = Paths.get(target);
                Path parent = Paths.get(currentPath).getParent();
                Path absoluteTarget = targetPath.isAbsolute()
                    ? targetPath
                    : (parent != null ? parent.resolve(targetPath) : targetPath);
                pathSet.add(absoluteTarget.toString());
                currentPath = absoluteTarget.toString();
            }
        } catch (Exception e) {
            // 链遍历任何失败都带着已收集结果继续（CC :370-372）
            if (log.isDebugEnabled()) {
                log.debug("[PermissionPaths] symlink 链遍历失败，使用已收集路径: path={} err={}",
                    path, e.toString());
            }
        }

        // 追加 realpath 最终解析路径（补目录组件中残余 symlink，CC :374-379）
        ResolveResult r = safeResolvePath(path);
        if (r.isSymlink() && !r.resolvedPath().equals(path)) {
            pathSet.add(r.resolvedPath());
        }

        if (log.isDebugEnabled()) {
            log.debug("[PermissionPaths] 权限检查路径展开（original+symlink）: input={} pathsToCheck={}",
                inputPath, pathSet);
        }
        return List.copyOf(pathSet);
    }

    /**
     * 解析路径最深存在祖先 · 对齐 CC {@code resolveDeepestExistingAncestorSync}
     * （fsOperations.ts:215-270）。
     *
     * <p>lstat 自底向上找第一个存在组件（lstat 不跟随 symlink，悬空 symlink 在此暴露）；
     * 只对最深处做一次 realpath（解析祖先中的链式 symlink）；realpath 失败回退 readlink
     * （悬空 symlink 取其目标）；不存在的尾段重新拼接。
     *
     * <p>返回 undefined 语义（null）：路径的所有存在祖先都解析到自身（无 symlink），
     * 原始逻辑路径已在 pathSet 中（CC :262-266/:269）。
     *
     * @param absolutePath 待解析路径（通常不存在，如新建文件写入目标）
     * @return 解析后的绝对路径（含不存在的尾段），或 null（无可解析的 symlink）
     */
    static String resolveDeepestExistingAncestor(String absolutePath) {
        Path dir = Paths.get(absolutePath);
        List<String> segments = new ArrayList<>();
        // 自底向上（lstat 廉价 O(1)，realpath 昂贵只调一次，CC :224-224）
        while (dir.getParent() != null) {
            BasicFileAttributes st;
            try {
                st = Files.readAttributes(
                    dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                // lstat 失败：真不存在，继续上溯（CC :226-233）
                segments.add(0, dir.getFileName().toString());
                dir = dir.getParent();
                continue;
            }
            if (st.isSymbolicLink()) {
                // 找到 symlink（活或悬空）：先 realpath（解析链式），失败回退 readlink（CC :234-251）
                try {
                    Path resolved = dir.toRealPath();
                    return joinTail(resolved, segments);
                } catch (IOException e) {
                    Path target;
                    try {
                        target = Files.readSymbolicLink(dir);
                    } catch (IOException e2) {
                        // realpath 与 readlink 都失败 → 无法解析（对齐 CC：readlinkSync 抛错由
                        // getPathsForPermissionCheck 外层 catch 吞掉，逻辑路径已在 pathSet）
                        return null;
                    }
                    Path absTarget = target.isAbsolute()
                        ? target
                        : (dir.getParent() != null ? dir.getParent().resolve(target) : target);
                    return joinTail(absTarget, segments);
                }
            }
            // 存在的非 symlink 组件：一次 realpath 解析其祖先中的 symlink；无则返回 null（CC :253-267）
            try {
                Path resolved = dir.toRealPath();
                if (!resolved.equals(dir)) {
                    return joinTail(resolved, segments);
                }
            } catch (IOException e) {
                // realpath 仍可能失败（祖先 EACCES 等）→ 返回 null，逻辑路径已在 pathSet（CC :262-266）
            }
            return null;
        }
        return null;
    }

    /** 把已剥离的不存在尾段重新拼回解析后的基底（CC nodePath.join(resolved, ...segments)）。 */
    private static String joinTail(Path base, List<String> segments) {
        if (segments.isEmpty()) {
            return base.toString();
        }
        Path p = base;
        for (String seg : segments) {
            p = p.resolve(seg);
        }
        return p.toString();
    }

    /**
     * 安全路径解析 · 对齐 CC {@code safeResolvePath}（fsOperations.ts:138-178）。
     *
     * <p>错误策略：文件不存在 → 返回原路径（允许新建）；symlink 解析失败（悬空/权限/环）
     * → 返回原路径且 isSymlink=false（操作继续，CC :172-177）。
     *
     * @param filePath 待解析路径
     * @return 解析结果（resolvedPath / isSymlink / isCanonical）
     */
    static ResolveResult safeResolvePath(String filePath) {
        // UNC 在任何文件系统访问前拦截（防 DNS/SMB，CC :142-146）
        if (filePath.startsWith("//") || filePath.startsWith("\\\\")) {
            return new ResolveResult(filePath, false, false);
        }
        try {
            // 特殊文件类型在 realpath 前跳过（realpath 可能阻塞在 FIFO 等，CC :149-161；
            // Java isOther 覆盖 FIFO/socket/设备）
            BasicFileAttributes stats = Files.readAttributes(
                Paths.get(filePath), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (stats.isOther()) {
                return new ResolveResult(filePath, false, false);
            }
            Path resolved = Paths.get(filePath).toRealPath();
            return new ResolveResult(resolved.toString(), !resolved.toString().equals(filePath), true);
        } catch (Exception e) {
            // lstat/realpath 任何失败（ENOENT/悬空/EACCES/ELOOP）→ 返回原路径（CC :172-177）；
            // 含 InvalidPathException 等运行时异常（如 Windows ADS 路径 "file.txt::$DATA"，
            // Node 端 existsSync 同样失败返回原路径——Java Paths.get 解析即抛，行为等价）
            return new ResolveResult(filePath, false, false);
        }
    }

    /** 用户主目录（NFC 归一，对齐 CC homedir().normalize('NFC')，fsOperations.ts:293）。 */
    private static String homedir() {
        String home = System.getProperty("user.home", "");
        return Normalizer.normalize(home, Normalizer.Form.NFC);
    }

    /** safeResolvePath 结果（对齐 CC {@code {resolvedPath, isSymlink, isCanonical}}）。 */
    record ResolveResult(String resolvedPath, boolean isSymlink, boolean isCanonical) {
    }
}
