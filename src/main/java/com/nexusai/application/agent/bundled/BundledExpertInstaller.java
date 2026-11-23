package com.nexusai.application.agent.bundled;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.impl.SubagentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Supplier;

/**
 * 内置专家安装器 · 把 classpath 里的一个专家复制到 {@code ~/.nexusai/agents/octop/<division>/<slug>.md}。
 *
 * <p><b>激活模型 = 按需安装</b>（用户 2026-11-23 裁定 A）：内置 272 个默认不激活，
 * 用户从「专家 → 内置专家」列表点安装才落到用户目录（next registry 重建即可见）。
 * ⛔ 不写其他目录、不新增数据库表。
 *
 * <p><b>冲突语义</b>：目标文件已存在且内容与内置版本不同 → {@link Result#CONFLICT}
 * （需显式 {@code overwrite=true} 才覆盖）；内容一致 → {@link Result#UNCHANGED}（幂等 no-op，
 * 不触发注册表刷新）。
 *
 * <p><b>刷新链</b>：真正写盘后调用 {@link SubagentTool#invalidateAgentRegistries()}
 * （它内部连带 {@code loadAgentsDir.clearCache()} 清 MarkdownConfigLoader 文件发现层），
 * 否则新专家在下一次 registry 构建时不可见。
 */
@Component
public class BundledExpertInstaller {

    private static final Logger log = LoggerFactory.getLogger(BundledExpertInstaller.class);

    /** 安装结果（供 API 映射与测试断言）。 */
    public enum Result {
        /** 首次安装。 */
        INSTALLED,
        /** 已存在且内容不同，按 overwrite 覆盖。 */
        OVERWRITTEN,
        /** 已存在且与内置版本逐字节一致（幂等 no-op）。 */
        UNCHANGED,
        /** 已存在且内容不同，未允许覆盖。 */
        CONFLICT
    }

    private final BundledExpertsCatalog catalog;
    private final Supplier<Path> configHomeSupplier;
    /** 测试 seam：非 null 时优先于 {@link #subagentTool}。 */
    private final Runnable registryRefresher;

    /** 生产装配（field injection 以允许 plain JUnit 为 null）。 */
    @Autowired(required = false)
    private SubagentTool subagentTool;

    @Autowired
    public BundledExpertInstaller(BundledExpertsCatalog catalog) {
        this(catalog, () -> Path.of(NexusaiPaths.getAppConfigHomeDir()), null);
    }

    /** 测试/显式装配入口：可注入临时配置根与刷新回调。 */
    public BundledExpertInstaller(BundledExpertsCatalog catalog,
                                  Supplier<Path> configHomeSupplier,
                                  Runnable registryRefresher) {
        this.catalog = catalog;
        this.configHomeSupplier = configHomeSupplier;
        this.registryRefresher = registryRefresher;
    }

    /** 目标路径 = {@code <configHome>/agents/octop/<division>/<slug>.md}。 */
    public Path targetPath(BundledExpert expert) {
        return configHomeSupplier.get()
            .resolve("agents")
            .resolve("octop")
            .resolve(expert.division())
            .resolve(expert.slug() + ".md");
    }

    /** 该专家当前是否已安装（只看目标文件是否存在；内容是否被改过由安装时判定）。 */
    public boolean isInstalled(BundledExpert expert) {
        return Files.isRegularFile(targetPath(expert));
    }

    /**
     * 安装一个专家。
     *
     * @param expert    目录条目（来自 {@link BundledExpertsCatalog}）
     * @param overwrite 目标已存在且内容不同时是否覆盖
     * @return 安装结果
     * @throws IOException 写盘失败
     */
    public Result install(BundledExpert expert, boolean overwrite) throws IOException {
        Path target = targetPath(expert);
        byte[] content = expert.content().getBytes(StandardCharsets.UTF_8);

        if (Files.isRegularFile(target)) {
            byte[] existing = Files.readAllBytes(target);
            if (Arrays.equals(existing, content)) {
                if (log.isDebugEnabled()) {
                    log.debug("[BundledExperts] 已安装且内容一致，跳过: {} -> {}", expert.slug(), target);
                }
                return Result.UNCHANGED;
            }
            if (!overwrite) {
                log.info("[BundledExperts] 安装冲突（目标已存在且内容不同，需 overwrite=true）: slug={} target={}",
                    expert.slug(), target);
                return Result.CONFLICT;
            }
            write(target, content);
            refreshRegistry();
            log.info("[BundledExperts] 覆盖安装完成: slug={} -> {}", expert.slug(), target);
            return Result.OVERWRITTEN;
        }

        write(target, content);
        refreshRegistry();
        log.info("[BundledExperts] 安装完成: slug={} -> {}", expert.slug(), target);
        return Result.INSTALLED;
    }

    private static void write(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    private void refreshRegistry() {
        if (registryRefresher != null) {
            registryRefresher.run();
            return;
        }
        SubagentTool tool = subagentTool;
        if (tool != null) {
            tool.invalidateAgentRegistries();
        } else {
            log.info("[BundledExperts] SubagentTool 未注入，跳过注册表刷新（下一次 registry 构建仍会扫描新文件）");
        }
    }

    /** 目录（只读）· 供 controller 校验 slug。 */
    public BundledExpertsCatalog catalog() {
        return catalog;
    }
}
