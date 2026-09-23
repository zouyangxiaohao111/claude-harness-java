package com.nexusai.application.agent.skill;

import com.nexusai.application.agent.command.StatuslineCommand;
import com.nexusai.application.agent.mcp.config.McpConfigFileWriter;
import com.nexusai.application.agent.permission.PathValidation;
import com.nexusai.application.agent.settings.storage.FileConfigStorage;
import com.nexusai.application.agent.subagent.BuiltInAgents;
import com.nexusai.application.agent.tool.ConfigToolPrompt;
import com.nexusai.application.agent.workflow.WorkflowConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 appname-dyn（2026-09-23）· 「配置路径/naming 跟着 appName 走」的**可执行不变量**测试。
 *
 * <p><b>本批唯一硬不变量（验收底线，规则九）</b>：{@code appName="nexusai"} 时每一处解析结果
 * 必须与<b>改前的写死值逐字节相同</b> —— 自有根 {@code ~/.nexusai}、全局配置文件
 * {@code ~/.nexusai.json}、DB url {@code ~/.nexusai/nexusai.db}、危险文件名
 * {@code .nexusai.json}、状态栏文案 {@code ~/.nexusai/settings.json}。
 * ⇒ 主线行为必须**零变化**；变的只是「appName 不是 nexusai 时也能跟着走」。
 *
 * <p><b>为什么这些断言值得存在</b>：本批把四处「静态写死」改成「运行时动态派生」
 * （全局文件名 / YAML 占位符 / 危险文件名 / 文案替换）。这类改造的典型失效模式恰恰是
 * <b>主线被悄悄改变</b>（例如静态 Set 移出后无人兜住 {@code .nexusai.json}），
 * 以及**动态根本没生效**（占位符写错 ⇒ 解析出字面量或报错）。故同时钉两个方向：
 * ①{@code nexusai} 逐字节不变；②{@code nexusai-scene} 下确实换名。
 *
 * <p><b>⛔ 本类刻意不含任何 Spring 容器/DB</b>：⛔ 无 {@code @SpringBootTest} /
 * {@code @ContextConfiguration} / {@code @DataJpaTest} / {@code @TestPropertySource}
 * （跑容器会迁移用户真库，绝对禁止）。YAML 那条用
 * {@link YamlPropertySourceLoader} + {@link StandardEnvironment} <b>纯解析</b>
 * —— 只读本仓 {@code application.yml} 的字节并做占位符替换，不建 ApplicationContext、
 * 不触发 DataSource 自动配置、不碰任何库文件。
 *
 * <p><b>appName seam</b>：经 {@link NexusaiPaths#setAppNameOverride(String)} 覆写
 * （生产由 {@code NexusaiAppNameInitializer} 从 {@code spring.application.name} 写入），
 * {@link #restoreDefaultAppName()} 在每例后复原为 {@code nexusai}。
 */
@DisplayName("批 appname-dyn · 配置路径跟着 appName 走（不变量 + 动态生效）")
class AppNameDynamicPathsTest {

    /** 主线 appName（= master 上 spring.application.name 的值）。 */
    private static final String MAIN_APP_NAME = "nexusai";
    /** 场景分支 appName（本批不负责改 spring.application.name，仅作派生验证的对照值）。 */
    private static final String SCENE_APP_NAME = "nexusai-scene";

    @AfterEach
    void restoreDefaultAppName() {
        // 共享 JVM：必须复原，否则污染后续测试的期望（NexusaiPathsTest 的「期望值纪律」）。
        NexusaiPaths.setAppNameOverride(MAIN_APP_NAME);
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    // ────────────────────────────────────────────────────────────────────────
    // (a) ⭐不变量：appName=nexusai 逐字节等于改前写死值；nexusai-scene 随之为新值
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(a) ⭐不变量 appName=nexusai：全局配置文件名/路径/项目目录名 == 改前写死值")
    void invariant_mainAppName_equalsLegacyLiterals() {
        NexusaiPaths.setAppNameOverride(MAIN_APP_NAME);

        assertThat(NexusaiPaths.getGlobalConfigFileName())
            .as("不变量：appName=nexusai ⇒ 全局配置文件名必须逐字节 == 改前的 \".nexusai.json\"")
            .isEqualTo(".nexusai.json");
        assertThat(NexusaiPaths.getProjectDirName())
            .as("不变量：自有根目录名必须仍是 .nexusai")
            .isEqualTo(".nexusai");
        String expectedPath = Path.of(System.getProperty("user.home", "."), ".nexusai.json")
            .toAbsolutePath().normalize().toString();
        assertThat(NexusaiPaths.getGlobalConfigFilePath())
            .as("不变量：全局配置文件路径必须 == {user.home}/.nexusai.json（改前 FileConfigStorage 的写死值）")
            .isEqualTo(expectedPath);
        assertThat(NexusaiPaths.getGlobalConfigFilePath())
            .as("不变量：必须落在 user.home 下、不在自有根子树内")
            .startsWith(System.getProperty("user.home", "."));
        assertThat(NexusaiPaths.getAppConfigHomeDir())
            .as("不变量：自有根（改前已动态）仍 == {user.home}/.nexusai")
            .isEqualTo(Path.of(System.getProperty("user.home", "."), ".nexusai")
                .toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("(a′) appName=nexusai-scene：三者随 appName 派生（.nexusai-scene.json / .nexusai-scene）")
    void sceneAppName_derivesAllNames() {
        NexusaiPaths.setAppNameOverride(SCENE_APP_NAME);

        assertThat(NexusaiPaths.getGlobalConfigFileName()).isEqualTo(".nexusai-scene.json");
        assertThat(NexusaiPaths.getProjectDirName()).isEqualTo(".nexusai-scene");
        assertThat(NexusaiPaths.getGlobalConfigFilePath())
            .as("appName=nexusai-scene ⇒ 全局配置文件路径尾段换名")
            .endsWith(".nexusai-scene.json");
        assertThat(NexusaiPaths.getGlobalConfigFilePath())
            .as("且**不再**是主线名（证明确实跟着 appName 走，而非恒字面量）")
            .doesNotEndWith(".nexusai.json");
        assertThat(NexusaiPaths.getAppConfigHomeDir()).endsWith(".nexusai-scene");
    }

    // ────────────────────────────────────────────────────────────────────────
    // (a″) 共享替换助手 replaceSelfDirLiteral（文案单一真源）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(a″) replaceSelfDirLiteral：nexusai ⇒ 恒等（零变化）；nexusai-scene ⇒ 换名；null ⇒ null")
    void replaceSelfDirLiteral_behaviour() {
        NexusaiPaths.setAppNameOverride(MAIN_APP_NAME);
        assertThat(NexusaiPaths.replaceSelfDirLiteral("Edit(~/.nexusai/settings.json)"))
            .as("不变量：appName=nexusai ⇒ 助手必须恒等（主线文案逐字节不变）")
            .isEqualTo("Edit(~/.nexusai/settings.json)");
        assertThat(NexusaiPaths.replaceSelfDirLiteral(null)).isNull();

        NexusaiPaths.setAppNameOverride(SCENE_APP_NAME);
        assertThat(NexusaiPaths.replaceSelfDirLiteral("Edit(~/.nexusai/settings.json)"))
            .isEqualTo("Edit(~/.nexusai-scene/settings.json)");
        assertThat(NexusaiPaths.replaceSelfDirLiteral("~/.nexusai/keybindings.json"))
            .as("路径段整体换名，不残留主线名")
            .isEqualTo("~/.nexusai-scene/keybindings.json");
        // [批 appname-dyn 追加 2026-09-23] **幂等哨兵**（复验点名项①）：旧实现无尾锚
        //   ⇒ 二次套用得到 ".nexusai-scene-scene"。加负向前瞻后「已含目标串的文本再套一次」不得改写。
        //   WHY 这条值得存在（规则九）：若有人把尾锚拿掉（回到裸 text.replace），下面立刻变红。
        assertThat(NexusaiPaths.replaceSelfDirLiteral(
                NexusaiPaths.replaceSelfDirLiteral("~/.nexusai")))
            .as("⭐幂等：套两次 == 套一次（旧实现是 .nexusai-scene-scene）")
            .isEqualTo("~/.nexusai-scene");
        String once = NexusaiPaths.replaceSelfDirLiteral("~/.nexusai/debug/<session-id>.txt");
        assertThat(once).as("正常形态照常替换（后继 '/'）").isEqualTo("~/.nexusai-scene/debug/<session-id>.txt");
        assertThat(NexusaiPaths.replaceSelfDirLiteral(once))
            .as("⭐幂等：对「已是目标串」的文本再套一次必须不变")
            .isEqualTo(once);
    }

    // ────────────────────────────────────────────────────────────────────────
    // (a‴) ⭐尾锚：前缀碰撞面闭合（复验点名项①的形态）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(a‴) ⭐尾锚：.nexusai2/.nexusaifoo/.nexusai_x 不被咬；.nexusai/.nexusai.json/.nexusai/ 照常替换")
    void replaceSelfDirLiteral_tailAnchorClosesPrefixCollision() {
        NexusaiPaths.setAppNameOverride(SCENE_APP_NAME);

        // 否定面：后继 ∈ [A-Za-z0-9_-] ⇒ 不得匹配（复验点名项①原文举例 .nexusai2）
        assertThat(NexusaiPaths.replaceSelfDirLiteral("~/.nexusai2"))
            .as("⭐.nexusai2 的后继 '2' 在否定类内 ⇒ 不得被咬").isEqualTo("~/.nexusai2");
        assertThat(NexusaiPaths.replaceSelfDirLiteral("~/.nexusaifoo/bar"))
            .as("⭐字母后继（~/.claudefoo 式前缀碰撞）⇒ 不得匹配").isEqualTo("~/.nexusaifoo/bar");
        assertThat(NexusaiPaths.replaceSelfDirLiteral("~/.nexusai_x"))
            .as("⭐下划线后继 ⇒ 不得匹配").isEqualTo("~/.nexusai_x");
        assertThat(NexusaiPaths.replaceSelfDirLiteral("~/.nexusai-scene"))
            .as("⭐目标串自身（幂等性根因）⇒ 不得再匹配").isEqualTo("~/.nexusai-scene");

        // 正向对照：必须照常替换（证明锚没有把正常面一并关掉 —— 反面对照必须可达）
        assertThat(NexusaiPaths.replaceSelfDirLiteral("~/.nexusai"))
            .as("行尾）⇒ 替换").isEqualTo("~/.nexusai-scene");
        assertThat(NexusaiPaths.replaceSelfDirLiteral("~/.nexusai.json"))
            .as("后继 '.'（全局配置文件名形态）⇒ 替换").isEqualTo("~/.nexusai-scene.json");
        assertThat(NexusaiPaths.replaceSelfDirLiteral("~/.nexusai/"))
            .as("后继 '/' ⇒ 替换").isEqualTo("~/.nexusai-scene/");
        assertThat(NexusaiPaths.replaceSelfDirLiteral("自有根 ~/.nexusai 的说明"))
            .as("后继空格（中文文案形态）⇒ 替换").isEqualTo("自有根 ~/.nexusai-scene 的说明");
    }

    // ────────────────────────────────────────────────────────────────────────
    // (b) ⭐YAML 占位符真能解析（纯解析，不建容器、不碰 DB）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(b) ⭐YAML 占位符真能解析：datasource url 与 nexusai.config.global-file 含动态自有根")
    void yamlPlaceholders_resolveToAppNameDerivedPaths() {
        String home = System.getProperty("user.home", ".");

        StandardEnvironment main = loadEnvironment(null);
        assertThat(main.getProperty("spring.datasource.url"))
            .as("不变量：appName=nexusai ⇒ DB url 逐字节含原写死值 {user.home}/.nexusai/nexusai.db")
            .startsWith("jdbc:sqlite:" + home + "/.nexusai/nexusai.db?")
            .contains("journal_mode=WAL")
            .doesNotContain("nexusai-scene");
        assertThat(main.getProperty("nexusai.config.global-file"))
            .as("不变量：appName=nexusai ⇒ global-file == {user.home}/.nexusai.json（原写死值）")
            .isEqualTo(home + "/.nexusai.json");

        StandardEnvironment scene = loadEnvironment(SCENE_APP_NAME);
        assertThat(scene.getProperty("spring.datasource.url"))
            .as("占位符确实跟着 spring.application.name 走（scene ⇒ 独立空库路径）")
            .startsWith("jdbc:sqlite:" + home + "/.nexusai-scene/nexusai.db?");
        assertThat(scene.getProperty("nexusai.config.global-file"))
            .as("占位符确实跟着 spring.application.name 走")
            .isEqualTo(home + "/.nexusai-scene.json");
    }

    // ────────────────────────────────────────────────────────────────────────
    // ⭐ 不变量：全局配置文件路径三处消费方同值（存储 / MCP 报告 / 助手）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("═不变量：FileConfigStorage 缺省 global 文件与 MCP 报告路径同源 == {user.home}/.nexusai.json")
    void globalFilePath_consumersAgreeOnLegacyValue() {
        NexusaiPaths.setAppNameOverride(MAIN_APP_NAME);
        String legacy = Path.of(System.getProperty("user.home", "."), ".nexusai.json")
            .toAbsolutePath().normalize().toString();

        FileConfigStorage storage = new FileConfigStorage(null);
        assertThat(storage.globalFilePath().toString())
            .as("FileConfigStorage 缺省回落（properties=null）必须 == 旧写死值")
            .isEqualTo(legacy);
        assertThat(storage.globalFilePath().getFileName().toString())
            .isEqualTo(".nexusai.json");
        assertThat(new McpConfigFileWriter(null, null).globalConfigFilePath())
            .as("McpConfigFileWriter 的 null 兜底必须与存储层同源（它承诺「报告路径 == 实际写入」）")
            .isEqualTo(legacy);
    }

    @Test
    @DisplayName("═statusline 文案：appName=nexusai ⇒ allowedTools 逐项等于源字面量；scene ⇒ 换名")
    void statuslineAllowedTools_followAppName() {
        NexusaiPaths.setAppNameOverride(MAIN_APP_NAME);
        assertThat(StatuslineCommand.allowedTools())
            .as("不变量：appName=nexusai ⇒ 运行时 allowedTools 必须逐项 == 源字面量常量")
            .isEqualTo(StatuslineCommand.ALLOWED_TOOLS);
        assertThat(StatuslineCommand.allowedTools())
            .containsExactly(StatuslineCommand.ALLOWED_TOOLS.get(0),
                StatuslineCommand.ALLOWED_TOOLS.get(1), "Edit(~/.nexusai/settings.json)");

        NexusaiPaths.setAppNameOverride(SCENE_APP_NAME);
        assertThat(StatuslineCommand.allowedTools())
            .as("scene ⇒ Edit 目标换名（其余项不受影响）")
            .containsExactly(StatuslineCommand.ALLOWED_TOOLS.get(0),
                StatuslineCommand.ALLOWED_TOOLS.get(1), "Edit(~/.nexusai-scene/settings.json)");
    }

    @Test
    @DisplayName("═keybindings 文案：nexusai ⇒ 仍是 ~/.nexusai/...；scene ⇒ 全文换成 ~/.nexusai-scene/...")
    void keybindingsPrompt_followsAppName() {
        NexusaiPaths.setAppNameOverride(MAIN_APP_NAME);
        String mainPrompt = new KeybindingsSkill().formatPrompt("").get(0).text();
        assertThat(mainPrompt)
            .as("不变量：appName=nexusai ⇒ 指引路径逐字节仍是 ~/.nexusai/keybindings.json")
            .contains("~/.nexusai/keybindings.json");
        assertThat(mainPrompt).doesNotContain("nexusai-scene");

        NexusaiPaths.setAppNameOverride(SCENE_APP_NAME);
        String scenePrompt = new KeybindingsSkill().formatPrompt("").get(0).text();
        assertThat(scenePrompt)
            .as("scene ⇒ 指引路径整体换名（证明章节级 replace 确实生效）")
            .contains("~/.nexusai-scene/keybindings.json");
        assertThat(scenePrompt)
            .as("且不再残留主线路径（~/.nexusai/ 后面必须不是场景后缀）")
            .doesNotContain("~/.nexusai/");
        assertThat(new KeybindingsSkill().formatPrompt("").get(0).text())
            .isEqualTo(scenePrompt);
    }

    // ────────────────────────────────────────────────────────────────────────
    // (c) ⭐危险文件名不变量（静态条目移出后必须由动态判定兜住）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(c) ⭐危险文件名：nexusai ⇒ <home>/.nexusai.json 仍危险；scene ⇒ 换名且旧名转 false")
    void dangerousFileName_followsAppName() {
        String home = System.getProperty("user.home", ".");
        String legacyFile = Path.of(home, ".nexusai.json").toString();
        String sceneFile = Path.of(home, ".nexusai-scene.json").toString();

        NexusaiPaths.setAppNameOverride(MAIN_APP_NAME);
        assertThat(PathValidation.isDangerousFilePathToAutoEdit(legacyFile))
            .as("⭐不变量：.nexusai.json 已从静态 DANGEROUS_FILES 移出 ⇒ 必须仍由动态判定兜住为 true")
            .isTrue();
        assertThat(PathValidation.isDangerousFilePathToAutoEdit(sceneFile))
            .as("appName=nexusai ⇒ 非当前 appName 的 .nexusai-scene.json 不危险")
            .isFalse();

        NexusaiPaths.setAppNameOverride(SCENE_APP_NAME);
        assertThat(PathValidation.isDangerousFilePathToAutoEdit(sceneFile))
            .as("scene ⇒ .{appName}.json 必须危险（确实跟着 appName 走）")
            .isTrue();
        assertThat(PathValidation.isDangerousFilePathToAutoEdit(legacyFile))
            .as("scene ⇒ 旧名 .nexusai.json 随之为 false（证明是动态派生，不是静态恒真）")
            .isFalse();
    }

    // ────────────────────────────────────────────────────────────────────────
    // (d) ⭐第三份 DANGEROUS_FILES（PowerShellPathValidator）—— 见同批
    //      PowerShellPathValidatorTest#dangerousGlobalConfigFile_followsAppName
    //      （该类包内可见性所限，断言落在其本包测试类）
    // ────────────────────────────────────────────────────────────────────────

    // ────────────────────────────────────────────────────────────────────────
    // (e) ⭐模型面向文案：stuck skill 的 debug 根随 appName（复验点名项②）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(e) ⭐stuck STUCK_PROMPT：nexusai ⇒ 注册产出逐字节 == 源常量；scene ⇒ ~/.nexusai-scene/debug/")
    void stuckPrompt_debugRootFollowsAppName() {
        NexusaiPaths.setAppNameOverride(MAIN_APP_NAME);
        assertThat(readStuckPrompt())
            .as("不变量：appName=nexusai ⇒ 运行时 prompt 逐字节 == 源常量 STUCK_PROMPT（主线文案零变化）")
            .isEqualTo(StuckSkillRegistrar.STUCK_PROMPT);
        assertThat(readStuckPrompt())
            .as("且仍是原写死形态 ~/.nexusai/debug/<session-id>.txt")
            .contains("~/.nexusai/debug/<session-id>.txt");

        NexusaiPaths.setAppNameOverride(SCENE_APP_NAME);
        String scenePrompt = readStuckPrompt();
        assertThat(scenePrompt)
            .as("⭐scene ⇒ 模型被指引到 ~/.nexusai-scene/debug/...（旧版指错目录）")
            .contains("~/.nexusai-scene/debug/<session-id>.txt");
        assertThat(scenePrompt)
            .as("且不残留主线 debug 路径（证明是动态派生，不是恒字面量）")
            .doesNotContain("~/.nexusai/debug/");
    }

    /** 经注册 sink 取 stuck skill 的运行时 prompt（args=null ⇒ 纯 STUCK_PROMPT 派生形态）。 */
    private static String readStuckPrompt() {
        BundledSkillDefinition[] holder = new BundledSkillDefinition[1];
        boolean registered = new StuckSkillRegistrar().register(def -> holder[0] = def, true);
        assertThat(registered).isTrue();
        return holder[0].getPromptForCommand().apply(null, null).get(0).text();
    }

    // ────────────────────────────────────────────────────────────────────────
    // (f) ⭐模型面向文案：ConfigTool 的 Global Settings 文件路径随 appName
    //     （复验「同族低危」ConfigToolPrompt:192，该文件原本 grep replace|NexusaiPaths == 0）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(f) ⭐ConfigTool prompt：nexusai ⇒ 逐字节含 ~/.nexusai.json；scene ⇒ ~/.nexusai-scene.json")
    void configToolPrompt_globalFileFollowsAppName() {
        NexusaiPaths.setAppNameOverride(MAIN_APP_NAME);
        String mainPrompt = ConfigToolPrompt.generate(
            ConfigToolPrompt::defaultRegistry, ConfigToolPrompt::defaultModelOptions, false);
        assertThat(mainPrompt)
            .as("不变量：appName=nexusai ⇒ 逐字节仍是旧写死值 ~/.nexusai.json")
            .contains("### Global Settings (stored in ~/.nexusai.json)");

        NexusaiPaths.setAppNameOverride(SCENE_APP_NAME);
        String scenePrompt = ConfigToolPrompt.generate(
            ConfigToolPrompt::defaultRegistry, ConfigToolPrompt::defaultModelOptions, false);
        assertThat(scenePrompt)
            .as("⭐scene ⇒ 模型被指到 ~/.nexusai-scene.json（旧版指错文件）")
            .contains("### Global Settings (stored in ~/.nexusai-scene.json)")
            .doesNotContain("~/.nexusai.json");
    }

    // ────────────────────────────────────────────────────────────────────────
    // (g) ⭐模型面向文案：BuiltInAgents statusline-setup prompt 随 appName
    //      （本批范围 1：原为 static final 在**类初始化期**做 replaceSelfDirLiteral
    //       ⇒ appName 注入晚于类加载时被冻死；现改「源字段一字不动 + 运行期取值口」）
    // ────────────────────────────────────────────────────────────────────────

    /** statusline SPECIFIC 的改前实测 UTF-8 字节数（= CC 运行时口径，见 BuiltInAgentsPromptContentTest 同值守卫）。 */
    private static final int STATUSLINE_SPECIFIC_LEGACY_BYTES = 6993;

    @Test
    @DisplayName("(g) ⭐BuiltInAgents.statuslineSetupSpecific：nexusai ⇒ 逐字节 == 源字段；类加载后设 scene ⇒ 仍换名")
    void builtInAgents_statuslinePrompt_followsAppName() throws Exception {
        NexusaiPaths.setAppNameOverride(MAIN_APP_NAME);
        // ⭐惰性证明第 1 步：先强制目标类**类加载**（若字段在类初始化期冻结 appName，冻结点即此处）
        Class.forName("com.nexusai.application.agent.subagent.BuiltInAgents");

        String mainSpecific = BuiltInAgents.statuslineSetupSpecific();
        assertThat(mainSpecific.getBytes(StandardCharsets.UTF_8).length)
            .as("不变量：appName=nexusai ⇒ 字节数 == 改前实测 %d（主线文案零变化）",
                STATUSLINE_SPECIFIC_LEGACY_BYTES)
            .isEqualTo(STATUSLINE_SPECIFIC_LEGACY_BYTES);
        assertThat(mainSpecific)
            .as("不变量：appName=nexusai ⇒ 运行期取值逐字节 == 源字段（replaceSelfDirLiteral 恒等 ⇒ == CC 原文）")
            .isEqualTo(readBuiltInAgentsField("STATUSLINE_SETUP_SPECIFIC"));
        assertThat(BuiltInAgents.STATUSLINE_SETUP_AGENT.getSystemPrompt(null, List.of()))
            .as("不变量：装配全文（唯一生产消费口）仍指 ~/.nexusai/settings.json")
            .contains("~/.nexusai/settings.json");

        // ⭐惰性证明第 2/3 步：**类已加载之后**再设 appName ⇒ 取值必须跟着走（冻结则恒为默认名 ⇒ 本断言红）
        NexusaiPaths.setAppNameOverride(SCENE_APP_NAME);
        assertThat(BuiltInAgents.statuslineSetupSpecific())
            .as("⭐惰性：类加载后再设 appName 仍生效 ⇒ 文案换名")
            .contains("~/.nexusai-scene/settings.json")
            .doesNotContain("~/.nexusai/");
        assertThat(BuiltInAgents.statuslineSetupSpecific().getBytes(StandardCharsets.UTF_8).length)
            .as("⭐恰好 4 处自有根字面换名（每处 +6B：.nexusai→.nexusai-scene）⇒ 6993+24")
            .isEqualTo(STATUSLINE_SPECIFIC_LEGACY_BYTES + 24);
        assertThat(BuiltInAgents.STATUSLINE_SETUP_AGENT.getSystemPrompt(null, List.of()))
            .as("⭐惰性（装配全文口径，即生产实际取的那份）：同样换名")
            .contains("~/.nexusai-scene/settings.json");
    }

    /** 反射读 BuiltInAgents 的私有源字段（= 源字面量；本测试用其作 nexusai 口径的逐字节基准）。 */
    private static String readBuiltInAgentsField(String name) throws Exception {
        Field f = BuiltInAgents.class.getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(null);
    }

    // ────────────────────────────────────────────────────────────────────────
    // (h) ⭐WorkflowConstants 目录名常量消时序冻结（本批范围 2）
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(h) ⭐WorkflowConstants：nexusai ⇒ 方法 == 源字面量；类加载后设 scene ⇒ 仍换名")
    void workflowConstants_dirNames_followAppName() throws Exception {
        NexusaiPaths.setAppNameOverride(MAIN_APP_NAME);
        // ⭐惰性证明第 1 步：先强制类加载（原实现在此处求值 NexusaiPaths.getProjectDirName()）
        Class.forName("com.nexusai.application.agent.workflow.WorkflowConstants");

        assertThat(WorkflowConstants.workflowDirName())
            .as("不变量：appName=nexusai ⇒ 运行期值 == 旧写死值 .nexusai/workflows")
            .isEqualTo(".nexusai/workflows")
            .isEqualTo(WorkflowConstants.WORKFLOW_DIR_NAME);
        assertThat(WorkflowConstants.workflowRunsDir())
            .as("不变量：appName=nexusai ⇒ 运行期值 == 旧写死值 .nexusai/workflow-runs")
            .isEqualTo(".nexusai/workflow-runs")
            .isEqualTo(WorkflowConstants.WORKFLOW_RUNS_DIR);

        // ⭐惰性证明第 2/3 步：类已加载之后再设 appName
        NexusaiPaths.setAppNameOverride(SCENE_APP_NAME);
        assertThat(WorkflowConstants.workflowDirName())
            .as("⭐惰性：类加载后设 appName 仍生效 ⇒ 目录换名（冻结实现恒 .nexusai/workflows）")
            .isEqualTo(".nexusai-scene/workflows");
        assertThat(WorkflowConstants.workflowRunsDir())
            .as("⭐惰性：同上")
            .isEqualTo(".nexusai-scene/workflow-runs");
        assertThat(WorkflowConstants.WORKFLOW_DIR_NAME)
            .as("源字面量常量本身不随 appName 变（它就是默认形态的源串；生产一律走方法）")
            .isEqualTo(".nexusai/workflows");
    }

    // ────────────────────────────────────────────────────────────────────────
    // 测试夹具：纯 YAML 解析环境（⛔ 不建 Spring 容器）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 纯解析环境：载入本仓 {@code application.yml}（+ 叠加 {@code application-prod.yml}，模拟
     * prod profile 覆盖顺序），必要时把 {@code spring.application.name} 覆写为给定值。
     *
     * <p>⛔ 只做「读字节 + 占位符替换」：{@link StandardEnvironment} 是 Spring Core 的纯环境对象，
     * 不创建 ApplicationContext、不触发任何自动配置 / DataSource / 迁移。
     *
     * @param appNameOverride {@code spring.application.name} 覆写值；null = 用 YAML 里的原值
     */
    private static StandardEnvironment loadEnvironment(String appNameOverride) {
        StandardEnvironment env = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        try {
            for (PropertySource<?> ps : loader.load("application.yml",
                    new ClassPathResource("application.yml"))) {
                env.getPropertySources().addLast(ps);
            }
            // prod 覆盖（Spring profile 语义：后加载的 profile 文档优先）⇒ 验证范围 5 那处也解析得出
            for (PropertySource<?> ps : loader.load("application-prod.yml",
                    new ClassPathResource("application-prod.yml"))) {
                env.getPropertySources().addFirst(ps);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("载入 application*.yml 失败", e);
        }
        if (appNameOverride != null) {
            env.getPropertySources().addFirst(new MapPropertySource(
                "appname-dyn-test-override",
                Map.of("spring.application.name", appNameOverride)));
        }
        return env;
    }

    /** 防呆：确认本类没有 Spring 测试注解（跑容器会迁移真库）。 */
    @Test
    @DisplayName("⛔ 防呆：本测试类不带任何 Spring 容器测试注解（不迁移真库）")
    void noSpringContextAnnotations() {
        List<String> forbidden = List.of(
            "org.springframework.boot.test.context.SpringBootTest",
            "org.springframework.test.context.ContextConfiguration",
            "org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest",
            "org.springframework.test.context.TestPropertySource",
            "org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc");
        List<String> declared = java.util.Arrays.stream(
                AppNameDynamicPathsTest.class.getAnnotations())
            .map(a -> a.annotationType().getName())
            .toList();
        for (String f : forbidden) {
            assertThat(declared)
                .as("⛔ 本类不得出现 %s（@SpringBootTest 会迁移用户真库），实测注解=%s", f, declared)
                .doesNotContain(f);
        }
    }
}
