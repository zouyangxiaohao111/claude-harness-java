package com.nexusai.model.command;

import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;

/**
 * getPromptForCommand 运行时上下文 · 对齐 CC {@code getPromptForCommand(args, context)}
 * （bundledSkills.ts:37-40 / skillify.ts:179-195 {@code async getPromptForCommand(args, context)}）。
 *
 * <p><b>为什么存在（skill 复验决策 拍板#9 part2 · NG-CDB-2）</b>：旧 Java {@code Command.promptFn}
 * 签名 {@code (args, cwd) -> List<String>} 只有 cwd 通道，CC 的 {@code context} 通道（含
 * {@code context.messages} 会话消息 + sessionId 会话标识）在 Java 侧无载体 —— skillify 等
 * 依赖会话上下文的 bundled skill 生产恒空会话数据（探查 EV-V-CDB-018/019 记录为「架构性签名
 * 缺口」）。本 record 是跨 {@link Command} / {@code BundledSkillDefinition} /
 * {@code SkillToolImpl} 共享的会话通道类型（CC ToolUseContext 在 Java 的 promptFn 侧投影）：
 * <ul>
 *   <li>{@link #cwd()} —— 旧 {@code (args, cwd)} 的 cwd 通道（CC getCwd() 等价，保留既有行为）</li>
 *   <li>{@link #messages()} —— CC {@code context.messages}（skillify.ts:182-184
 *       {@code getMessagesAfterCompactBoundary(context.messages)} 输入）</li>
 *   <li>{@link #sessionId()} —— 会话 ID（Java 端供 SessionMemoryService.getSessionMemoryContent(sessionId)
 *       解析 CC getSessionMemoryContent() 的会话 memory 内容）</li>
 * </ul>
 *
 * <p><b>不变量</b>：messages 不可为 null（compact ctor 兜底 {@code List.of()} + copyOf 防御性拷贝）。
 * 位于 model 层（com.nexusai.model.command）避免 model→application 层倒置（Command.java 既有
 * {@code promptFn} 字段同约束）。
 */
public record PromptFnContext(
        /** 工作目录 · CC original: cwd / getCwd()（旧 promptFn (args,cwd) 通道保留） */
        String cwd,
        /** 会话消息 · CC original: context.messages（skillify.ts:183） */
        List<ChatMessageDto> messages,
        /** 会话 ID · Java 端解析 session memory 用（SessionMemoryService.getSessionMemoryContent） */
        String sessionId) {

    /**
     * compact ctor：messages null → 空列表 + 防御性不可变拷贝（对齐 ToolUseContext messages
     * {@code List.copyOf} 同款，防外部 mutate 污染闭包上下文）。
     */
    public PromptFnContext {
        if (messages == null) {
            messages = List.of();
        } else {
            messages = List.copyOf(messages);
        }
    }

    /** 便利工厂 · 参数为 null 时由 compact ctor 兜底。 */
    public static PromptFnContext of(String cwd, List<ChatMessageDto> messages, String sessionId) {
        return new PromptFnContext(cwd, messages, sessionId);
    }
}
