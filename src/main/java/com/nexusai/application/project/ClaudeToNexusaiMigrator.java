package com.nexusai.application.project;

import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.stream.Stream;

/**
 * [T8/D6] 项目级 {@code .claude/} → 项目内 {@code .nexusai/} 一次性导入器。
 *
 * <p><b>决策 D6</b>（nexusai 复刻版 .claude 改造）：项目 {@code .claude/} 内容<b>一次性</b>导入到
 * 项目内 {@code .nexusai/}（全新无 {@code .nexusai/} 才导入，已存在跳过不覆盖）；排除
 * {@code settings.json} / {@code settings.local.json}（D2 不读 claude 配置）/
 * {@code worktrees/}（D7 git 级共享不迁移）。
 *
 * <p><b>白名单</b>（依据 {@code claude-dir-io-register.md} §3 项目级读写清单）：
 * <ul>
 *   <li>读清单（§3.1）：{@code CLAUDE.md}、{@code rules/}、{@code skills/}、{@code commands/}、
 *       {@code agents/}、{@code output-styles/}、{@code workflows/}、{@code launch.json}</li>
 *   <li>写清单（§3.2）：{@code workflow-runs/}、{@code agent-memory/}、{@code agent-memory-local/}、
 *       {@code agent-memory-snapshots/}</li>
 * </ul>
 * 排除（不加入白名单）：{@code settings.json} / {@code settings.local.json}（D2 不读 claude 配置）+
 * {@code worktrees/}（D7 git 级共享，不迁移）。
 *
 * <p><b>CC 参照</b>：无 CC 真源（D6 是 nexusai 自有迁移特性，CC 无 .claude→.nexusai 导入器）。
 *
 * <p><b>接线</b>：{@link ProjectSessionBindingService#bind(String, com.nexusai.model.project.dto.ProjectBindRequest)}
 * 首个绑定会话（project.bound false→true）时调用 {@link #migrateOnce(Path)}；{@code @Async} 提交到
 * chatExecutor（虚拟线程）——大目录复制不阻塞绑定响应。
 */
@Component
public class ClaudeToNexusaiMigrator {

    private static final Logger log = LoggerFactory.getLogger(ClaudeToNexusaiMigrator.class);

    /** 项目级 claude 目录名（源）。 */
    private static final String CLAUDE_DIR = ".claude";
    // [批 appname-dyn 追加 2026-09-23] 原为 `private static final String NEXUSAI_DIR =
    //   NexusaiPaths.getProjectDirName();` —— ⛔ static final 在**类加载期**冻结，而 appName 由
    //   NexusaiAppNameInitializer 的 @PostConstruct（另一 bean，时序不保证先于本类加载）写入
    //   ⇒ appName=nexusai-scene 时该常量会冻成默认 ".nexusai"，迁移目标目录错位（与
    //   StatuslineCommand#ALLOWED_TOOLS / NexusaiPaths#getAppTempDirName 的时序纪律同族）。
    //   故删常量，改在**取值点**现读（见 migrateOnce 内 nexusaiDir）。

    /**
     * D6 白名单（依据 claude-dir-io-register §3 项目级读写清单）。
     * 读清单（§3.1）：CLAUDE.md / rules / skills / commands / agents / output-styles / workflows / launch.json；
     * 写清单（§3.2）：workflow-runs / agent-memory / agent-memory-local / agent-memory-snapshots。
     * 已剔除：settings.json / settings.local.json（D2）+ worktrees/（D7）。
     */
    private static final Set<String> WHITELIST = Set.of(
        "CLAUDE.md", "rules", "skills", "commands", "agents", "output-styles",
        "workflows", "launch.json", "workflow-runs",
        "agent-memory", "agent-memory-local", "agent-memory-snapshots"
    );

    /**
     * 一次性导入：项目 {@code .claude/} 白名单内容 → {@code .nexusai/} 同名结构（幂等，绝不覆盖）。
     *
     * <p>判定顺序：
     * <ol>
     *   <li>projectRoot 无效（null / 非目录）→ 跳过</li>
     *   <li>{@code .nexusai/} 已存在 → 跳过（幂等不覆盖；含用户手建空目录，nexusai 优先）</li>
     *   <li>{@code .claude/} 不存在 → 跳过</li>
     *   <li>白名单逐项递归复制到 {@code .nexusai/} 同名结构</li>
     * </ol>
     *
     * <p>单项目仅首个绑定会话触发一次；失败项记 error 继续其余白名单项，不阻断绑定响应（@Async）。
     *
     * @param projectRoot 绑定项目根（绝对路径）
     */
    @Async("chatExecutor")
    public void migrateOnce(Path projectRoot) {
        if (projectRoot == null) {
            log.warn("[ClaudeToNexusaiMigrator] projectRoot 为 null，跳过一次性导入");
            return;
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            log.warn("[ClaudeToNexusaiMigrator] 项目根不是有效目录，跳过一次性导入: {}", root);
            return;
        }
        Path claudeDir = root.resolve(CLAUDE_DIR);
        // 取值点现读（⛔ 不得缓存为 static final —— 时序纪律见类首注释）
        Path nexusaiDir = root.resolve(NexusaiPaths.getProjectDirName());

        // 幂等：.nexusai/ 已存在 → 跳过（绝不覆盖）
        if (Files.exists(nexusaiDir)) {
            log.info("[ClaudeToNexusaiMigrator] {} 已存在，跳过一次性导入（幂等不覆盖）", nexusaiDir);
            return;
        }
        // .claude/ 不存在 → 跳过
        if (!Files.isDirectory(claudeDir)) {
            if (log.isDebugEnabled()) {
                log.debug("[ClaudeToNexusaiMigrator] {} 不存在，无内容可导入", claudeDir);
            }
            return;
        }

        // [批 appname-dyn 追加 2026-09-23] 日志文案里的目标目录名与实际写入目录同源（现读，不写死）
        log.info("[ClaudeToNexusaiMigrator] 项目级 .claude → {} 一次性导入开始: projectRoot={}",
            NexusaiPaths.getProjectDirName(), root);
        int copied = 0;
        for (String item : WHITELIST) {
            Path src = claudeDir.resolve(item);
            if (!Files.exists(src)) {
                if (log.isDebugEnabled()) {
                    log.debug("[ClaudeToNexusaiMigrator] 白名单项不存在，跳过: {}", src);
                }
                continue;
            }
            Path target = nexusaiDir.resolve(item);
            try {
                copyRecursively(src, target);
                copied++;
                log.info("[ClaudeToNexusaiMigrator] 已导入: {} → {}", src, target);
            } catch (IOException e) {
                log.error("[ClaudeToNexusaiMigrator] 导入失败（继续其余白名单项）: source={}", src, e);
            }
        }
        log.info("[ClaudeToNexusaiMigrator] 项目级 .claude → {} 一次性导入完成: projectRoot={} copied={}",
            NexusaiPaths.getProjectDirName(), root, copied);
    }

    /**
     * 递归复制 source → target（保留目录结构与文件属性）。
     *
     * @param source 源文件/目录（.claude/ 下白名单项）
     * @param target 目标路径（.nexusai/ 下同名结构）
     */
    private static void copyRecursively(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            Files.createDirectories(target);
            try (Stream<Path> children = Files.list(source)) {
                for (Path child : children.toList()) {
                    copyRecursively(child, target.resolve(child.getFileName()));
                }
            }
        } else {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
        }
    }
}
