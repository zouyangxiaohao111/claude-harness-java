package com.nexusai.model.command.dto;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandSource;

import java.util.List;

/**
 * 内置命令专用响应 DTO（DEC-9）· 补 {@link CommandDto} 缺 type 字段的 gap。
 *
 * <p><b>WHY type 必需</b>：CC COMMANDS 内置命令类型为 'local' / 'local-jsx' / 'prompt'
 * （types/command.ts:26 联合），React 需 type 区分渲染/触发方式 —— 'prompt' 型（init）触发
 * CLAUDE.md 生成流程，'local'/'local-jsx' 型触发 web 本地行为（清会话/配置面板等）。
 * {@link CommandDto} 无 type 字段（CommandService.toDto 显式字段构造不映射 type），若复用
 * CommandDto 则前端无法区分 → 新建本 DTO 携带 type（concern DEC-9：扩展 CommandDto 本体
 * 需改 record 签名 + 波及 CommandService.toDto，本计划规避以守 writeScope）。
 *
 * <p>字段对齐 CC CommandBase 子集 + source：
 * <ul>
 *   <li>name —— CommandBase.name</li>
 *   <li>type —— PromptCommand.type（'local' | 'local-jsx' | 'prompt'）</li>
 *   <li>description —— CommandBase.description</li>
 *   <li>aliases —— CommandBase.aliases</li>
 *   <li>argumentHint —— CommandBase.argumentHint</li>
 *   <li>isHidden —— CommandBase.isHidden（output-style 恒 true，React 默认不渲染隐藏命令）</li>
 *   <li>source —— Command.source（恒 BUILTIN）</li>
 * </ul>
 *
 * @param name 命令名
 * @param type 命令类型（'local' | 'local-jsx' | 'prompt'）
 * @param description 描述
 * @param aliases 别名（可为 null）
 * @param argumentHint 参数提示（可为 null）
 * @param isHidden 是否隐藏
 * @param source 命令来源（恒 BUILTIN）
 */
public record BuiltInCommandDto(
    String name,
    String type,
    String description,
    List<String> aliases,
    String argumentHint,
    boolean isHidden,
    CommandSource source
) {
    /**
     * 领域 Command → 内置命令 DTO · 显式字段构造（对齐 CommandService.toDto 模式，防 Jackson
     * 序列化 isEnabled/availability 等 local-only 字段 —— BudgetTracker 红线）。
     *
     * @param c 领域命令（期望 source=BUILTIN）
     * @return 内置命令 DTO
     */
    public static BuiltInCommandDto from(Command c) {
        return new BuiltInCommandDto(
            c.getName(),
            c.getType(),
            c.getDescription(),
            c.getAliases(),
            c.getArgumentHint(),
            Boolean.TRUE.equals(c.getIsHidden()),
            c.getSource()
        );
    }
}
