package com.nexusai.application.agent.skill;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-1 技能清单 plugin userFacingName 偏差 debug 日志测试 · 对齐 CC tools/SkillTool/prompt.ts:52-63
 * formatCommandDescription() 的 logForDebugging。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>plugin 技能可能 userFacingName 与 name 不同</b>（如插件前缀剥离，types/command.ts:209-210
 *       {@code getCommandName = cmd.userFacingName?.() ?? cmd.name}）。CC 在 prompt.ts:56-60 用
 *       debug 日志暴露这一偏差（{@code Skill prompt: showing "${name}" (userFacingName="${displayName}")}），
 *       便于排查"清单显示名与调用名不符"。旧 Java formatEntry 静默吞掉 displayName 只输出 name ——
 *       排查 plugin 命令显示名漂移时无日志可依（RED 于子报告 D24）。</li>
 *   <li><b>仅 plugin + prompt 类型触发</b>——USER/bundled 源或非 prompt 类型不得触发（CC 判定
 *       {@code cmd.source === 'plugin' && cmd.type === 'prompt'}）。</li>
 *   <li><b>清单文本格式不变</b>——日志为旁路调试信息，返回串 {@code - name: desc} 不得因加日志而改变
 *       （LlmAgentLoop:2226 等外部消费方依赖 formatListing 文本，P3-1 risks）。</li>
 * </ol>
 */
class SkillCatalogUserFacingNameLogTest {

    private Logger skillCatalogLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        skillCatalogLogger = (Logger) LoggerFactory.getLogger(SkillCatalog.class);
        appender = new ListAppender<>();
        appender.start();
        skillCatalogLogger.addAppender(appender);
        skillCatalogLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        skillCatalogLogger.detachAppender(appender);
        appender.stop();
    }

    /** 空目录 SkillRegistry + SkillCatalog（formatListing 不依赖 registry 内容） */
    private static SkillCatalog catalog(Path tempDir) {
        return new SkillCatalog(new SkillRegistry(tempDir.toString()));
    }

    /** 构造一条 plugin 源命令（type 默认 'prompt'，与 displayName 不同 → 触发 CC :56-60 判定） */
    private static Command pluginPromptMismatchCmd(String name, String displayName) {
        Command c = new Command();
        c.setName(name);
        c.setDisplayName(displayName);
        c.setSource(CommandSource.PLUGIN);
        c.setDescription("desc");
        return c;
    }

    @Test
    @DisplayName("纯谓词：plugin+prompt+userFacingName!=name → true（CC prompt.ts:56-60 判定等价）")
    void predicate_pluginPromptMismatch_true() {
        assertThat(SkillCatalog.isPluginUserFacingNameMismatch(pluginPromptMismatchCmd("plugin-x", "plugin-nice"))).isTrue();
    }

    @Test
    @DisplayName("纯谓词：userFacingName==name 不触发（CC cmd.name===displayName）")
    void predicate_noMismatch_false() {
        assertThat(SkillCatalog.isPluginUserFacingNameMismatch(pluginPromptMismatchCmd("same", "same"))).isFalse();
    }

    @Test
    @DisplayName("纯谓词：USER 源不触发（CC cmd.source==='plugin' 强制）")
    void predicate_userSource_false() {
        Command c = pluginPromptMismatchCmd("plugin-x", "plugin-nice");
        c.setSource(CommandSource.USER);
        assertThat(SkillCatalog.isPluginUserFacingNameMismatch(c)).isFalse();
    }

    @Test
    @DisplayName("纯谓词：非 prompt 类型不触发（CC cmd.type==='prompt' 强制）")
    void predicate_nonPromptType_false() {
        Command c = pluginPromptMismatchCmd("plugin-x", "plugin-nice");
        c.setType("bash");
        assertThat(SkillCatalog.isPluginUserFacingNameMismatch(c)).isFalse();
    }

    @Test
    @DisplayName("formatListing：plugin userFacingName 偏差 → debug 日志触发（RED 于旧 formatEntry 无日志）")
    void formatListing_pluginMismatch_emitsDebugLog(@TempDir Path tempDir) {
        catalog(tempDir).formatListing(List.of(pluginPromptMismatchCmd("plugin-x", "plugin-nice")), 8000);
        List<String> msgs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(msgs).anyMatch(m -> m.contains("plugin-x") && m.contains("userFacingName=\"plugin-nice\""));
    }

    @Test
    @DisplayName("formatListing：USER 源不触发 debug 日志（CC source==='plugin' 限定）")
    void formatListing_userSource_noLog(@TempDir Path tempDir) {
        Command c = pluginPromptMismatchCmd("user-x", "user-nice");
        c.setSource(CommandSource.USER);
        catalog(tempDir).formatListing(List.of(c), 8000);
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("formatListing：清单文本不变 `- name: desc`（日志旁路，外部消费方不受影响）")
    void formatListing_textUnchanged(@TempDir Path tempDir) {
        String text = catalog(tempDir).formatListing(List.of(pluginPromptMismatchCmd("plugin-x", "plugin-nice")), 8000);
        assertThat(text).isEqualTo("- plugin-x: desc");
    }
}
