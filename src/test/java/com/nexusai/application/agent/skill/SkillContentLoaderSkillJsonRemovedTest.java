package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * X1 guard 测试：skill.json v1 兼容加载路径已删除（CC 无此格式）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）：
 * <ol>
 *   <li><b>skill.json 不再被读取</b>——X1 删除后，模型/子 agent 技能正文唯一文件来源是
 *       SKILL.md（CC loadSkillsFromSkillsDir loadSkillsDir.ts:430-445 仅 read SKILL.md、
 *       ENOENT 即 skip，无 skill.json 概念）。仅建 skill.json 的目录必须落到 getContent()
 *       回退（null → ""），绝不返回 json content 字段。若未来有人恢复 skill.json 加载，
 *       本测试 RED 拦截（旧代码该场景返回 skill.json.content → RED）。</li>
 *   <li><b>SKILL.md 正向回归</b>——正常路径不受影响：baseDir 下建 SKILL.md，
 *       loadContent 返回去除 frontmatter 的 body（对齐 loadSkillsDir.ts:433-447）。</li>
 * </ol>
 */
class SkillContentLoaderSkillJsonRemovedTest {

    private final SkillContentLoader loader = new SkillContentLoader();

    @Test
    @DisplayName("guard：仅存在 skill.json 时 loadContent 返回空串（不再读该格式）")
    void skillJsonOnly_returnsEmpty(@TempDir Path baseDir) throws IOException {
        // 旧 v1 格式 fixture：SKILL.md 缺失、仅 skill.json 含 content 字段
        Files.writeString(baseDir.resolve("skill.json"),
            "{\"content\":\"旧 skill.json 正文，X1 后不得再读取\"}", StandardCharsets.UTF_8);

        Command cmd = new Command();
        cmd.setBaseDir(baseDir.toString());
        // contentPath=null + content=null → 步骤1/2 文件加载均落空，落到 getContent()==null → ""
        assertThat(loader.loadContent(cmd)).isEqualTo("");
    }

    @Test
    @DisplayName("正向：baseDir 下 SKILL.md 存在时 loadContent 返回去除 frontmatter 的 body")
    void skillMd_returnsBody(@TempDir Path baseDir) throws IOException {
        Files.writeString(baseDir.resolve("SKILL.md"),
            "---\nname: demo\n---\n# Demo\n\nbody text", StandardCharsets.UTF_8);

        Command cmd = new Command();
        cmd.setBaseDir(baseDir.toString());

        String result = loader.loadContent(cmd);
        assertThat(result).contains("# Demo");
        assertThat(result).contains("body text");
        assertThat(result).doesNotContain("name: demo");
    }
}
