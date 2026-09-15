package com.nexusai.model.command;

import com.nexusai.model.session.dto.ChatMessageDto;

import java.util.List;
import java.util.function.Supplier;

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
        String sessionId,
        /**
         * 原始工作目录槽 · <b>getOriginalCwdLayer 语义</b>（会话存档锚，对齐 CC
         * {@code getOriginalCwd()}；<b>不受 bash cd 影响</b>，随 worktree 重锚）。
         *
         * <p><b>[批 r10 · 甲项 B1] 为什么是 {@code Supplier} 而不是已解析的 {@code String}</b>：
         * 三个构造点（{@code SlashCommandInterceptor} 斜杠路径 · {@code SkillToolImpl} /
         * {@code SkillPreloader} skill 工具路径）的**异常面不同** ——
         * <ul>
         *   <li>斜杠路径：{@code buildSkillContent} 在本槽之前**已**调
         *       {@code CwdResolution.getCwd(sessionId)}（同样 fail-loud）⇒ 提前求值不新增抛出；</li>
         *   <li>skill 工具路径：该处**不**解析 cwd（用 {@code ctx.effectiveCwd()}）⇒ 提前求值会
         *       **新增**抛出面；而唯一消费点 {@code GroupB.readSessionLogLines} 原先把这次反查放在
         *       自己的 {@code try} 内（抛错被吞 + WARN + 返回空统计）。</li>
         * </ul>
         * 传 {@code Supplier} ⇒ 消费点在自己的 try 内 {@code get()}，<b>异常面与改造前逐点一致</b>；
         * 传已解析值则要么在 skill 路径新增抛出、要么被迫在消费点保留兜底反查。
         * 与 {@link #cwd()} 同样遵循本批铁律：<b>承载必须惰性</b>。
         *
         * <p>⛔ 本槽与 {@link #cwd()} <b>不可互相替代</b>：发生过 bash {@code cd} 的会话里两者必然
         * 不同值（cwd 槽被 cd 覆盖、originalCwd 槽不被覆盖）。
         */
        Supplier<String> originalCwd) {

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
    public static PromptFnContext of(String cwd, List<ChatMessageDto> messages, String sessionId,
                                     Supplier<String> originalCwd) {
        return new PromptFnContext(cwd, messages, sessionId, originalCwd);
    }

    /**
     * 3 参便利构造 · {@code originalCwd} 槽<b>缺省 = 不提供</b>（{@code null}）。
     *
     * <p><b>[批 r10]</b> 供「不消费该槽」的构造点使用（现存 13 处测试夹具 + 非 prompt 路径），
     * 避免为它们逐个补一个用不到的参数。⚠️ 槽为 {@code null} 时消费点
     * （{@code CommandRegistrationConfigGroupB.readSessionLogLines}）按 {@code user.dir} 兜底，
     * <b>不 NPE</b>；<b>生产三处构造点一律显式提供</b>该槽（斜杠路径 = SlashCommandInterceptor，
     * skill 工具路径 = SkillToolImpl / SkillPreloader）。
     */
    public PromptFnContext(String cwd, List<ChatMessageDto> messages, String sessionId) {
        this(cwd, messages, sessionId, null);
    }

    /** 便利工厂（3 参形态）· {@code originalCwd} 槽缺省 = 不提供，见 {@link #PromptFnContext(String, List, String)}。 */
    public static PromptFnContext of(String cwd, List<ChatMessageDto> messages, String sessionId) {
        return new PromptFnContext(cwd, messages, sessionId, null);
    }
}
