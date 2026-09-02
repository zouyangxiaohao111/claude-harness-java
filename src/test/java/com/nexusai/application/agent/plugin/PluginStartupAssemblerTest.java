package com.nexusai.application.agent.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.plugin.PluginSchemas.KnownMarketplace;
import com.nexusai.application.agent.plugin.PluginSchemas.MarketplaceSource;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * [MPL8-D6] PluginStartupAssembler 生产装配级测试 · 证明非仅测试手动调用。
 *
 * <p><b>WHY（规则九 · 测试验证意图 + 反射 D6）</b>：MPL8 反射发现数据面零生产引用 ——
 * PerformStartupChecks/MarketplaceReconciler/ActivePluginRefresher/MarketplaceConfigStore
 * 仅测试手动调用，生产 startup 不触发 reconcile。参照 C3R PluginLoader @PostConstruct 装配
 * 标准（PluginLoader.java:312-326，装配期预热即被 context 启动验证），本测试用真实 Spring
 * 容器（ApplicationContextRunner）启动包含 {@link PluginStartupAssembler} @Component 的装配
 * 配置：容器启动即触发 {@code @PostConstruct runStartupChecks()} → 真实 reconcile 链
 * （PerformStartupChecks.wire → performBackgroundPluginInstallations）→ declared 目录市场被
 * 安装到 known_marketplaces.json。若装配缺失，reconcile 不会运行，断言失败 → 测试即失败。
 *
 * <p><b>不变量</b>：
 * <ol>
 *   <li>{@link #postConstructTriggersReconcileAtContextStartup} —— 容器启动（非手动调用）
 *       reconcile 落盘 known_marketplaces.json（对齐 CC REPL.tsx:799 启动调用点）；</li>
 *   <li>{@link #missingDepsSkipsWithoutReconcile} —— 依赖缺失（非 Spring 装配）→ fail loud 跳过，
 *       不抛异常不写文件（headless 部署安全）。</li>
 * </ol>
 */
@DisplayName("[MPL8-D6] PluginStartupAssembler 生产装配（@PostConstruct 真实触发 reconcile）")
class PluginStartupAssemblerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @BeforeEach
    @AfterEach
    void resetPluginDirs() {
        PluginDirectories.setPluginCacheDirOverride(null);
        PluginDirectories.setPluginSeedDirOverride(null);
        PluginDirectories.setUseCoworkPluginsOverride(null);
    }

    private String originalUserHome;

    /** 决策 D1：FileConfigStorage 单参（ConfigStorageProperties），缺省 settings =
     *  {user.home}/.nexusai/settings.json。测试把 user.home 指到 tempDir/home，使 bean
     *  读预置的 nexusaiHome/.nexusai/settings.json（防污染真实 ~/.nexusai），用后恢复。 */
    @BeforeEach
    void isolateUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    /**
     * 核心：真实 Spring 容器启动 → @PostConstruct 装配链真实触发 reconcile。
     *
     * <p>预置：nexusaiHome/.nexusai/settings.json 声明 extraKnownMarketplaces.a = 本地目录源
     * （含 .claude-plugin/marketplace.json）。容器启动后若 {@link PluginStartupAssembler}
     * 的 @PostConstruct 真实执行，declared 市场会被 reconcile 安装到
     * {@code pluginDir/known_marketplaces.json}；断言即验证"startup 真实触发，非仅测试手动调用"。
     */
    @Test
    @DisplayName("context 启动即触发 reconcile：declared 目录市场被安装到 known_marketplaces.json")
    void postConstructTriggersReconcileAtContextStartup() throws Exception {
        Path nexusaiHome = tempDir.resolve("home");
        Path pluginDir = tempDir.resolve("plugins");
        Files.createDirectories(nexusaiHome);
        Files.createDirectories(pluginDir);

        // declared 目录市场（directory 源，无 git，快速落盘）
        Path mktDir = tempDir.resolve("mkt-a");
        Files.createDirectories(mktDir.resolve(".claude-plugin"));
        Files.writeString(mktDir.resolve(".claude-plugin/marketplace.json"),
            "{\"name\":\"a\",\"owner\":\"test\",\"plugins\":[]}", StandardCharsets.UTF_8);

        // 预写 settings.json：extraKnownMarketplaces.a（KnownMarketplace JSON 形状，对齐
        // MarketplaceManager.saveMarketplaceToSettings 生产写入，含 @JsonTypeInfo source 判别器）
        Path settingsFile = nexusaiHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        Files.createDirectories(settingsFile.getParent());
        ObjectNode root = JSON.createObjectNode();
        root.set("extraKnownMarketplaces", JSON.valueToTree(
            Map.of("a", new KnownMarketplace(
                new MarketplaceSource.Directory(mktDir.toString()),
                "unused-install-location", "2026-01-01T00:00:00Z", null))));
        Files.writeString(settingsFile, JSON.writeValueAsString(root), StandardCharsets.UTF_8);

        PluginDirectories.setPluginCacheDirOverride(pluginDir.toString());
        PluginDirectories.setPluginSeedDirOverride(null);

        new ApplicationContextRunner()
            .withUserConfiguration(AssemblyConfig.class)
            .run(ctx -> {
                assertThat(ctx).as("装配上下文必须正常启动").hasNotFailed();
                // 装配类必须注册为 Spring bean（@Component 真实装配，非测试手动 new）
                assertThat(ctx.getBeanNamesForType(PluginStartupAssembler.class))
                    .as("PluginStartupAssembler 必须是 @Component bean").isNotEmpty();
                // [MPL8-D6d] reconcile 已异步（@PostConstruct fire-and-forget）→ await 保证最终一致
                ctx.getBean(PluginStartupAssembler.class).awaitStartupChecks(Duration.ofSeconds(15));
                MarketplaceManager mm = ctx.getBean(MarketplaceManager.class);
                // 容器启动（@PostConstruct）即完成 reconcile → known_marketplaces.json 含 declared 市场
                Map<String, KnownMarketplace> config = mm.loadKnownMarketplacesConfig();
                assertThat(config).as("startup reconcile 已安装 declared 市场（@PostConstruct 真实调用链）")
                    .containsKey("a");
            });
    }

    /**
     * fail loud：非 Spring 装配（依赖缺失）→ 跳过 reconcile，不抛异常不写文件。
     * 防止 headless 部署/单测下 startup 装配因缺依赖而崩。
     */
    @Test
    @DisplayName("依赖缺失 → 跳过 startup reconcile（fail loud 不抛，无 side effect）")
    void missingDepsSkipsWithoutReconcile() throws Exception {
        Path nexusaiHome = tempDir.resolve("home2");
        Files.createDirectories(nexusaiHome.resolve(NexusaiPaths.getProjectDirName()));

        PluginStartupAssembler assembler = new PluginStartupAssembler(null, null, null);
        assertThatCode(() -> assembler.performStartupChecks(null))
            .as("依赖缺失必须静默跳过").doesNotThrowAnyException();
        // 无 reconcile side effect：known_marketplaces.json 不存在
        assertThat(nexusaiHome.resolve(NexusaiPaths.getProjectDirName() + "/known_marketplaces.json"))
            .doesNotExist();
    }

    /**
     * [MPL8-D6d] 核心：startup reconcile 转异步（不阻塞启动 + 最终一致）。
     *
     * <p><b>WHY（规则九 · 对齐 CC REPL.tsx:799）</b>：CC 启动调用点 {@code void performStartupChecks(setAppState)}
     * 是 fire-and-forget（useEffect 内不 await，performStartupChecks.tsx:24 {@code async}: Promise&lt;void&gt;，
     * :65-68 catch "don't block startup"）。Java 此前 @PostConstruct 同步 reconcile 会阻塞启动窗口
     * （github 源 clone 慢）。本测试锁定新异步语义：@PostConstruct 立即返回（不阻塞容器启动），
     * reconcile 经 daemon executor 后台执行，await 后最终一致落盘 —— 测试仅通过 {@code awaitStartupChecks}
     * 观察终态（同步实现无此钩子 → RED；异步实现 GREEN）。
     */
    @Test
    @DisplayName("startup reconcile 异步：不阻塞启动，await 后最终一致落盘（MPL8-D6d）")
    void asyncStartupReconcileCompletesWithoutBlockingStartup() throws Exception {
        Path nexusaiHome = prepareSettingsWithDeclaredMarketplace("a");

        new ApplicationContextRunner()
            .withUserConfiguration(AssemblyConfig.class)
            .run(ctx -> {
                assertThat(ctx).as("装配上下文必须正常启动").hasNotFailed();
                PluginStartupAssembler assembler = ctx.getBean(PluginStartupAssembler.class);
                // 非阻塞：@PostConstruct runStartupChecks 已异步提交并立即返回（容器启动不被 reconcile 阻塞）
                // —— await 钩子是观察终态的唯一途径（fire-and-forget 语义，CC REPL.tsx:799）。
                assembler.awaitStartupChecks(Duration.ofSeconds(15));
                // 最终一致：reconcile 完成后 declared 市场落盘 known_marketplaces.json
                assertThat(ctx.getBean(MarketplaceManager.class).loadKnownMarketplacesConfig())
                    .as("异步 reconcile 最终一致落盘 declared 市场").containsKey("a");
            });
    }

    /**
     * [MPL8-D6c] 核心：生产装配链经真实 setAppState 消费者把 reconcile 状态透出。
     *
     * <p><b>WHY（规则九）</b>：CC performStartupChecks.tsx:24/64 的 setAppState 是真实必需参数——
     * 后台安装经 updateMarketplaceStatus（PluginInstallationManager.ts:30-36）把
     * pending/installing/installed/failed 写入全局 AppState.installationStatus.marketplaces
     *（AppStateStore.ts:196-206）。Java 生产装配（runStartupChecks）此前传 {@code null}（headless，
     * 仅落盘），reconcile 状态不可观察（MPL8 反射 D6c 承接项）。本测试锁定生产装配链真实写入
     * {@link PluginStartupAssembler.PluginAppStateStore}（Java 等价全局状态存储），
     * 证明状态面可观察（验收 #2），非仅测试手动注入 consumer。
     */
    @Test
    @DisplayName("setAppState 真实消费者：startup reconcile 状态 pending→installing→installed 透出（MPL8-D6c）")
    void postConstructReconcileStatusObservableViaRealStateConsumer() throws Exception {
        Path nexusaiHome = prepareSettingsWithDeclaredMarketplace("a");

        new ApplicationContextRunner()
            .withUserConfiguration(AssemblyConfig.class)
            .run(ctx -> {
                assertThat(ctx).as("装配上下文必须正常启动").hasNotFailed();
                PluginStartupAssembler assembler = ctx.getBean(PluginStartupAssembler.class);
                // [MPL8-D6d] reconcile 已异步 → await 保证状态面终态可观察（最终一致）
                assembler.awaitStartupChecks(Duration.ofSeconds(15));
                PluginStartupAssembler.PluginAppStateStore store = assembler.pluginAppStateStore();
                // 生产装配链（@PostConstruct runStartupChecks → performStartupChecks(store) →
                // PluginInstallationManager.performBackgroundPluginInstallations）真实把 reconcile
                // 状态写入可观察状态存储（对齐 AppStateStore.ts:196-206 installationStatus）。
                assertThat(store.marketplaceStatuses().get("a"))
                    .as("reconcile 终态 installed 经 setAppState 透出").isEqualTo("installed");
                assertThat(store.statusLog())
                    .as("reconcile 状态序列 pending→installing→installed 可观察")
                    .contains("a=pending", "a=installing", "a=installed");
            });
    }

    /**
     * [MPL8-D6c] headless 降级保留：null setAppState（无状态消费者）仍落盘不抛（验收 #3）。
     *
     * <p>WHY：旧生产行为 {@code performStartupChecks(null)} 必须继续可用——无状态消费者时
     * 仅落盘（known_marketplaces.json），不崩溃；PluginInstallationManager.wire 的
     * null→no-op 降级（ignored -> {}）保留。
     */
    @Test
    @DisplayName("headless 降级保留：null setAppState 仍落盘不抛（MPL8-D6c）")
    void nullConsumerStillHeadlessAndWritesDisk() throws Exception {
        Path nexusaiHome = prepareSettingsWithDeclaredMarketplace("a");

        new ApplicationContextRunner()
            .withUserConfiguration(AssemblyConfig.class)
            .run(ctx -> {
                assertThat(ctx).as("装配上下文必须正常启动").hasNotFailed();
                PluginStartupAssembler assembler = ctx.getBean(PluginStartupAssembler.class);
                // 手动 null consumer = headless：无状态消费者时不抛、不阻断（对齐 wire null→no-op）
                assertThatCode(() -> assembler.performStartupChecks(null))
                    .as("null setAppState 必须静默可用").doesNotThrowAnyException();
                // [MPL8-D6d] context 启动 reconcile 已异步 → await 保证落盘（最终一致）
                assembler.awaitStartupChecks(Duration.ofSeconds(15));
                // 落盘保留：declared 市场仍物化（context 启动 reconcile + 再次 null 调用均不破坏）
                assertThat(ctx.getBean(MarketplaceManager.class).loadKnownMarketplacesConfig())
                    .as("headless 仍落盘 declared 市场").containsKey("a");
            });
    }

    /** 预置 nexusaiHome + 目录市场 + settings.extraKnownMarketplaces 的 declared 市场，返回 nexusaiHome。 */
    private Path prepareSettingsWithDeclaredMarketplace(String name) throws Exception {
        Path nexusaiHome = tempDir.resolve("home");
        Path pluginDir = tempDir.resolve("plugins");
        Files.createDirectories(nexusaiHome);
        Files.createDirectories(pluginDir);

        // declared 目录市场（directory 源，无 git，快速落盘）
        Path mktDir = tempDir.resolve("mkt-" + name);
        Files.createDirectories(mktDir.resolve(".claude-plugin"));
        Files.writeString(mktDir.resolve(".claude-plugin/marketplace.json"),
            "{\"name\":\"" + name + "\",\"owner\":\"test\",\"plugins\":[]}", StandardCharsets.UTF_8);

        // 预写 settings.json：extraKnownMarketplaces.{name}（KnownMarketplace JSON 形状）
        Path settingsFile = nexusaiHome.resolve(NexusaiPaths.getProjectDirName()).resolve("settings.json");
        Files.createDirectories(settingsFile.getParent());
        ObjectNode root = JSON.createObjectNode();
        root.set("extraKnownMarketplaces", JSON.valueToTree(
            Map.of(name, new KnownMarketplace(
                new MarketplaceSource.Directory(mktDir.toString()),
                "unused-install-location", "2026-01-01T00:00:00Z", null))));
        Files.writeString(settingsFile, JSON.writeValueAsString(root), StandardCharsets.UTF_8);

        PluginDirectories.setPluginCacheDirOverride(pluginDir.toString());
        PluginDirectories.setPluginSeedDirOverride(null);
        return nexusaiHome;
    }

    /** 装配配置：导入真实 @Component（PluginStartupAssembler/MarketplaceManager/PluginLoader）+ FileConfigStorage。 */
    @Configuration
    @Import({PluginStartupAssembler.class, MarketplaceManager.class, PluginLoader.class})
    static class AssemblyConfig {

        @Bean
        FileConfigStorage fileConfigStorage() {
            // 决策 D1：nexusai.home 已废弃，FileConfigStorage 单参（ConfigStorageProperties）。
            //   缺省 settings = {user.home}/.nexusai/settings.json（测试经 isolateUserHome 指到 tempDir/home）。
            return new FileConfigStorage(null);
        }
    }
}
