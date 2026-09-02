package com.nexusai.application.agent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 命名 workflow 文件解析/列举 · CC original: {@code resolveNamedWorkflow} / {@code listNamedWorkflows}
 * (Open-ClaudeCode/packages/workflow-engine/src/engine/namedWorkflows.ts:14-46)。
 *
 * <p>{@code WORKFLOW_SCRIPT_EXTENSIONS} = ['.ts','.js','.mjs'] 优先级序（namedWorkflows.ts:8-12）。
 * {@code resolve} 双重防护：路径必须落在 workflowDir 内（外层 sanitize 之外再拦一道遍历逃逸，
 * namedWorkflows.ts:22-23）。{@code list} 只列脚本文件、去扩展名、字典序排序
 * （namedWorkflows.ts:32-46）。目录缺失 → 空列表（不抛，namedWorkflows.ts:39-41）。
 */
public final class NamedWorkflows {

    private static final Logger log = LoggerFactory.getLogger(NamedWorkflows.class);

    private NamedWorkflows() {
    }

    /** 解析结果 · CC original: {@code {path, content}} (namedWorkflows.ts:19)。 */
    public record NamedWorkflow(String path, String content) {
    }

    /**
     * 按优先级 {@code .ts → .js → .mjs} 解析命名 workflow · CC original: namedWorkflows.ts:14-30。
     *
     * @param workflowDir workflow 目录（如 {@code <projectRoot>/.claude/workflows}）
     * @param name        工作流名（不含扩展名）
     * @return 找到的 {@code {path, content}}；未找到 null
     */
    public static NamedWorkflow resolve(String workflowDir, String name) {
        if (workflowDir == null || name == null) {
            return null;
        }
        Path base = Path.of(workflowDir).toAbsolutePath().normalize();
        for (String ext : WorkflowConstants.WORKFLOW_SCRIPT_EXTENSIONS) {
            Path p = base.resolve(name + ext).normalize();
            // 双重防护：阻止外层 sanitize 漏掉的路径逃逸到 workflowDir 之外（namedWorkflows.ts:22-23）
            if (!p.startsWith(base)) {
                log.warn("NamedWorkflows.resolve 拒绝越界路径：{}（不在 workflowDir {} 内，namedWorkflows.ts:22-23）",
                        p, base);
                return null;
            }
            try {
                if (Files.isRegularFile(p)) {
                    String content = Files.readString(p);
                    if (log.isDebugEnabled()) {
                        log.debug("NamedWorkflows.resolve 命中：name={} ext={} path={}（namedWorkflows.ts:18-28）",
                                name, ext, p);
                    }
                    return new NamedWorkflow(p.toString(), content);
                }
            } catch (IOException e) {
                // try the next extension（namedWorkflows.ts:25-27）
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("NamedWorkflows.resolve 未命中：name={}（.ts/.js/.mjs 均不存在）", name);
        }
        return null;
    }

    /**
     * 列举目录下全部命名 workflow · CC original: namedWorkflows.ts:32-46。
     *
     * @param workflowDir workflow 目录
     * @return 去扩展名、字典序排序的 workflow 名列表（目录缺失 → 空列表）
     */
    public static List<String> list(String workflowDir) {
        if (workflowDir == null) {
            return List.of();
        }
        try (var stream = Files.list(Path.of(workflowDir))) {
            List<String> names = new ArrayList<>();
            stream.filter(Files::isRegularFile).forEach(p -> {
                String fileName = p.getFileName().toString();
                int dot = fileName.lastIndexOf('.');
                String ext = dot >= 0 ? fileName.substring(dot) : "";
                if (WorkflowConstants.WORKFLOW_SCRIPT_EXTENSIONS.contains(ext.toLowerCase())) {
                    names.add(dot >= 0 ? fileName.substring(0, dot) : fileName);
                }
            });
            names.sort(String::compareTo);
            if (log.isDebugEnabled()) {
                log.debug("NamedWorkflows.list：dir={} 命中 {} 个（namedWorkflows.ts:42-45）", workflowDir, names.size());
            }
            return List.copyOf(names);
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("NamedWorkflows.list 目录缺失或不可读：dir={} → 空列表（namedWorkflows.ts:39-41）", workflowDir);
            }
            return List.of();
        }
    }

    /**
     * 命名 workflow 解析 · nexusai 优先 + claude 回落（决策 D6 目录迁移收口）。
     *
     * <p>先在 {@code <projectRoot>/<WORKFLOW_DIR_NAME>}（{@code .{appName}/workflows}，nexusai 自有目录）
     * 解析；未命中回落 {@code <projectRoot>/.claude/workflows}（CC 既有兼容目录，未迁移用户的读取回落源）。
     * 两目录均未命中 → null（namedWorkflows.ts:26-29 等价）。</p>
     *
     * @param projectRoot 项目根目录
     * @param name        工作流名（不含扩展名）
     * @return 找到的 {@code {path, content}}；未找到 null
     */
    public static NamedWorkflow resolveWithFallback(String projectRoot, String name) {
        if (projectRoot == null || name == null) {
            return null;
        }
        NamedWorkflow found = resolve(Path.of(projectRoot, WorkflowConstants.WORKFLOW_DIR_NAME).toString(), name);
        if (found != null) {
            return found;
        }
        if (log.isDebugEnabled()) {
            log.debug("NamedWorkflows.resolveWithFallback：nexusai 目录未命中 name={}，回落 .claude/workflows 重试", name);
        }
        return resolve(Path.of(projectRoot, ".claude/workflows").toString(), name);
    }

    /**
     * 列举命名 workflow · nexusai 优先 + claude 回落（决策 D6 目录迁移收口）。
     *
     * <p>合并 {@code <projectRoot>/<WORKFLOW_DIR_NAME>}（nexusai 自有目录）与回落目录
     * {@code <projectRoot>/.claude/workflows}（CC 既有兼容目录）：nexusai 名字在前、同名 nexusai
     * 覆盖 claude；claude 仅补充 nexusai 缺失的名字。两目录都缺失 → 空列表（不抛，
     * namedWorkflows.ts:39-41）。</p>
     *
     * @param projectRoot 项目根目录
     * @return 去扩展名、去重的 workflow 名列表（nexusai 优先排序）
     */
    public static List<String> listWithFallback(String projectRoot) {
        if (projectRoot == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        seen.addAll(list(Path.of(projectRoot, WorkflowConstants.WORKFLOW_DIR_NAME).toString()));
        seen.addAll(list(Path.of(projectRoot, ".claude/workflows").toString()));
        return List.copyOf(seen);
    }
}
