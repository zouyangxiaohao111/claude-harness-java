package com.nexusai.application.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusai.application.agent.init.InitCommand;
import com.nexusai.model.command.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * /init 对齐 CCB（NEW_INIT_PROMPT）测试（规则九：测意图）。
 *
 * <p><b>WHY（意图）</b>：用户 2026-10-26 裁定「/init 对齐 CC 或 CCB」。本仓此前注册的 /init 用
 * OLD 提示词（只生成 CLAUDE.md），模型因此**不知道 skills 目录、无法安装技能**。
 * CCB 的 NEW 提示词显式给出 {@code .nexusai/skills/<skill-name>/SKILL.md} 等路径 ——
 * 这条路径就是「AI 知道 skill 位置并能安装」的唯一来源，测试必须钉住它不能被改回 OLD。
 */
@DisplayName("/init：注册命令使用 CCB NEW 提示词（含 skills 安装路径）")
class InitCommandAlignmentTest {

    @Test
    @DisplayName("注册表中的 /init 使用 NEW 提示词：含 skills 安装路径 + CLAUDE.local.md + 新描述")
    void registeredInitUsesNewPrompt() {
        Command init = BuiltInCommands.findByName("init");

        assertThat(init).isNotNull();
        assertThat(init.getContent()).contains("Set up a minimal CLAUDE.md");
        // 核心断言：模型据此知道技能该写到哪里（CCB Phase 6）
        assertThat(init.getContent()).contains("skills/<skill-name>/SKILL.md");
        assertThat(init.getContent()).contains("CLAUDE.local.md");
        assertThat(init.getContent()).contains("CLAUDE.md");
        // 不能退回 OLD（OLD 没有技能路径）
        assertThat(init.getContent())
            .doesNotContain("Please analyze this codebase and create a CLAUDE.md file");
        assertThat(init.getDescription()).contains("optional skills/hooks");
    }

    @Test
    @DisplayName("hooks 阶段引用已注册的 update-config 技能（本仓存在，BundledSkillsBootstrapper 无条件注册）")
    void promptReferencesUpdateConfigSkill() {
        InitCommand command = new InitCommand(() -> true, () -> "web", () -> true, null);

        // WHY：update-config 编辑的是 settings.json（hooks/permissions/env 等文件型设置），
        // 与 DB 设置页（PUT /api/v1/settings）是两条通道；技能存在，/init 的 hooks 阶段应复用它。
        assertThat(command.getPrompt()).contains("update-config");
    }

    @Test
    @DisplayName("提示词不得残留 Claude/claude 品牌（用户 2026-10-26：换成 NexusAI；CLAUDE.md 文件名除外）")
    void promptUsesNexusaiBranding() {
        InitCommand command = new InitCommand(() -> true, () -> "web", () -> true, null);

        assertThat(command.getPrompt()).doesNotContain("Claude");
        assertThat(command.getPrompt()).doesNotContain("claude");
        assertThat(command.getPrompt()).contains("NexusAI");
    }

    @Test
    @DisplayName("OLD 分支仍保留：feature/env 关闭时回落 OLD（CC 门控语义不丢）")
    void oldBranchStillAvailable() {
        InitCommand old = new InitCommand(() -> false, () -> "web", () -> false, null);

        assertThat(old.getPrompt()).startsWith("Please analyze this codebase");
    }
}
