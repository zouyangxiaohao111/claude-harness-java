package com.nexusai.application.agent.skill;

import com.nexusai.domain.command.CommandService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.command.Command;
import com.nexusai.model.skill.dto.SkillContentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 本机技能正文接口：按名取、大小上限、路径越界拒绝、未安装 404。 */
class SkillContentServiceTest {

    @TempDir
    Path temp;

    private SkillRegistry registry;
    private CommandService commandService;
    private SkillContentService service;

    @BeforeEach
    void setUp() {
        registry = mock(SkillRegistry.class);
        commandService = mock(CommandService.class);
        service = new SkillContentService(registry, commandService);
        when(commandService.listAllDomain()).thenReturn(List.of());
    }

    @Test
    @DisplayName("内存 content 直接返回（含元数据）")
    void returnsInMemoryContent() {
        Command c = command("demo", "# Hello\nbody", null);
        when(registry.getAllCommands(null)).thenReturn(List.of(c));

        SkillContentDto dto = service.content("demo", null);

        assertThat(dto.content()).isEqualTo("# Hello\nbody");
        assertThat(dto.displayName()).isEqualTo("Demo");
        assertThat(dto.whenToUse()).isEqualTo("when needed");
        assertThat(dto.allowedTools()).containsExactly("Bash");
    }

    @Test
    @DisplayName("content 为空时按 contentPath 读 SKILL.md（限 512KB）")
    void readsFromContentPath() throws Exception {
        Path file = temp.resolve("SKILL.md");
        Files.writeString(file, "# From disk");
        Command c = command("demo", null, file.toString());
        when(registry.getAllCommands(null)).thenReturn(List.of(c));

        assertThat(service.content("demo", null).content()).isEqualTo("# From disk");
    }

    @Test
    @DisplayName("技能名含路径分隔符 → 400（越界拒绝）")
    void rejectsTraversalName() {
        assertThatThrownBy(() -> service.content("../etc/passwd", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("技能名非法");
        assertThatThrownBy(() -> service.content("a/b", null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("contentPath 含 .. / 非 SKILL.md → 400")
    void rejectsBadContentPath() {
        Command dotdot = command("demo", null, "C:/skills/../secret/SKILL.md");
        when(registry.getAllCommands(null)).thenReturn(List.of(dotdot));
        assertThatThrownBy(() -> service.content("demo", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("..");

        Command wrongName = command("demo2", null, "C:/skills/demo2/NOTES.md");
        when(registry.getAllCommands(null)).thenReturn(List.of(wrongName));
        assertThatThrownBy(() -> service.content("demo2", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("SKILL.md");
    }

    @Test
    @DisplayName("正文超过 512KB → 400")
    void rejectsOversizeContent() {
        Command c = command("big", "x".repeat(512 * 1024 + 1), null);
        when(registry.getAllCommands(null)).thenReturn(List.of(c));
        assertThatThrownBy(() -> service.content("big", null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("512KB");
    }

    @Test
    @DisplayName("未安装/未知技能 → 404")
    void notFound() {
        when(registry.getAllCommands(null)).thenReturn(List.of());
        assertThatThrownBy(() -> service.content("ghost", null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("ghost");
    }

    private static Command command(String name, String content, String contentPath) {
        Command c = new Command();
        c.setName(name);
        c.setDisplayName("Demo");
        c.setDescription("desc");
        c.setVersion("1.0.0");
        c.setContent(content);
        c.setContentPath(contentPath);
        c.setBaseDir("C:/skills/" + name);
        c.setWhenToUse("when needed");
        c.setAllowedTools(List.of("Bash"));
        return c;
    }
}
