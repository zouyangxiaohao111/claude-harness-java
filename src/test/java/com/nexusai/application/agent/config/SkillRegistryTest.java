package com.nexusai.application.agent.config;

import com.nexusai.application.agent.plugin.PluginLoader;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MPL6-WIRE · SkillRegistry.setPluginLoader 生产接线装配级测试。
 *
 * <p><b>WHY（CLAUDE.md 规则 9 · 测试验证意图）</b>：CC 生产 feed 链
 * {@code loadPluginCommands.ts:414 getPluginCommands} / {@code :840 getPluginSkills}
 * 经 {@code loadAllPluginsCacheOnly()} 产出 enabled plugin 的命令/技能，Java 侧由
 * {@link PluginLoader#loadAllEnabledCommands()} / {@link PluginLoader#loadAllEnabledSkills()}
 * 等价实现。若 {@code ToolRegistrationConfig.skillRegistry()} @Bean 装配处不调
 * {@link SkillRegistry#setPluginLoader}，则生产 {@code getAllCommands()} 恒缺 plugin
 * 命令/技能源（plugin 源永远不激活）——CC 对齐在装配层断链。本测试验证：
 * <ol>
 *   <li>装配 skillRegistry() 注入 pluginLoader 后，getAllCommands 含 plugin 命令与技能
 *       （source='plugin'，对齐 CC getPluginCommands/getPluginSkills 产出）</li>
 *   <li>pluginLoader 未注入（required=false 容错）时行为不变——getAllCommands 无 plugin 源</li>
 * </ol>
 */
@DisplayName("[MPL6-WIRE] ToolRegistrationConfig.skillRegistry() 生产装配含 plugin 源")
class SkillRegistryTest {

    private final ToolRegistrationConfig config = new ToolRegistrationConfig();

    /** 最小 plugin 命令（source='plugin'）· 对齐 CC loadPluginCommands.ts:414 产出 */
    private static Command pluginCommand(String name) {
        Command c = new Command();
        c.setName(name);
        c.setType("prompt");
        c.setSource(CommandSource.PLUGIN);
        return c;
    }

    @Test
    @DisplayName("装配注入 pluginLoader → getAllCommands 含 plugin 命令与技能源")
    void wiredSkillRegistry_containsPluginCommandsAndSkills() throws Exception {
        // PluginLoader 为 @Component Spring bean；测试以 Mockito mock 模拟生产 feed 产出。
        PluginLoader pluginLoader = Mockito.mock(PluginLoader.class);
        Mockito.when(pluginLoader.loadAllEnabledCommands())
            .thenReturn(List.of(pluginCommand("plugin:acme:hello")));
        Mockito.when(pluginLoader.loadAllEnabledSkills())
            .thenReturn(List.of(pluginCommand("plugin:acme:skill-x")));

        // 生产由 Spring @Autowired(required=false) 注入；POJO 测试经反射注入等价。
        Field f = ToolRegistrationConfig.class.getDeclaredField("pluginLoader");
        f.setAccessible(true);
        f.set(config, pluginLoader);

        SkillRegistry registry = config.skillRegistry();

        List<String> names = registry.getAllCommands().stream().map(Command::getName).toList();
        assertThat(names)
            .as("生产 getAllCommands 含 plugin 命令 + 技能（CC getPluginCommands/getPluginSkills feed）")
            .contains("plugin:acme:hello", "plugin:acme:skill-x");
    }

    @Test
    @DisplayName("pluginLoader 未注入 → getAllCommands 无 plugin 源（required=false 容错）")
    void unwiredSkillRegistry_keepsBehaviorUnchanged() {
        SkillRegistry registry = config.skillRegistry();

        List<String> names = registry.getAllCommands().stream().map(Command::getName).toList();
        assertThat(names)
            .as("未注入 pluginLoader 时行为不变：getAllCommands 不含 plugin 源")
            .doesNotContain("plugin:acme:hello", "plugin:acme:skill-x");
    }

    @Test
    @DisplayName("refresh() 清 plugin 单一 feed 缓存（CI-21/30 · CC clearCommandsCache commands.ts:534-539）")
    void refresh_clearsPluginFeedCache() throws Exception {
        PluginLoader pluginLoader = Mockito.mock(PluginLoader.class);

        Field f = ToolRegistrationConfig.class.getDeclaredField("pluginLoader");
        f.setAccessible(true);
        f.set(config, pluginLoader);

        SkillRegistry registry = config.skillRegistry();
        registry.refresh();

        // CC clearCommandsCache（commands.ts:534-539）在 clearCommandMemoizationCaches 后
        //   清 clearPluginCommandCache + clearPluginSkillsCache；Java 单一 feed 缓存由
        //   PluginLoader.clearPluginCache(String) 承载（CI-21/30）。refresh() 未串接该清则
        //   插件安装/卸载后不重枚举 plugin 命令/技能。
        Mockito.verify(pluginLoader).clearPluginCache("SkillRegistry.refresh");
    }
}
