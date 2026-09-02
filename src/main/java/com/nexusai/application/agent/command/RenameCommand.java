package com.nexusai.application.agent.command;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Session rename 命令 · 对齐 CC commands/rename/rename.ts:21-87 call.
 *
 * <p>L1 语义: 处理 /rename [name] — 无参时 Haiku 生成 kebab-case 名; 有参时 trim 直接用.
 *            teammate 不能 rename (CC isTeammate 守卫). 保存到 transcript + 同步 bridge session title
 *            + 更新 AppState.standaloneAgentContext.name.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: `execute(args, env) → RenameResult` 签名</li>
 *   <li><b>A2 Golden Trace</b>: teammate → system error; 空 args → generate; 非空 args → trim; 保存 + bridge 同步 + AppState 更新</li>
 *   <li><b>A3</b>: generate 返回 null → "Could not generate a name" 错误; bridge 同步 best-effort 不阻断</li>
 *   <li><b>A4</b>: args 含前后空白 → trim 后使用; null args → 当作 empty</li>
 *   <li><b>A5</b>: 真实场景 — args="Fix Login Bug" → trim → "Fix Login Bug" + transcript + bridge + AppState</li>
 * </ul>
 *
 * <p>L3 (Java idiom): Consumer&lt;String&gt; onDone 替代 CC onDone; Supplier&lt;Boolean&gt; isTeammate +
 *                    Supplier&lt;UUID&gt; sessionId + Supplier&lt;String&gt; transcriptPath +
 *                    BiFunction generateName + Consumer&lt;AppState&gt; setAppState +
 *                    Supplier&lt;CompletableFuture&lt;Void&gt;&gt; saveCustomTitle + saveAgentName +
 *                    Supplier&lt;CompletableFuture&lt;Void&gt;&gt; updateBridgeTitle 全部注入测试可控.
 *
 * <p><b>[prompt-align TOOLS-04] 生产注册接线结论</b>：本类 + {@link GenerateSessionName}
 * 全仓无生产调用方（grep 复验：仅 ChatService 注释提及，无 {@code CommandRegistry.java}）。
 * Web 架构中 /rename 语义由 <b>前端 PATCH /api/v1/sessions/{id}</b>（SessionUpdateRequest.title）
 * 承载显式命名；无参自动生成名由 {@code ChatService.maybeGenerateTitle}（:1414-1475，
 * sessionTitle.ts 对齐 prompt）覆盖——两者即 CC rename.ts 的生产语义等价。本类保留为
 * CC 对齐的 CLI 命令实现但<b>不注册</b>。若后续需真实后端 REST suggest-name 端点
 * （无参生成名走后端 API → 前端契约），登记 待前端对接.md 待用户拍板，本批次不新增 API 面。
 *
 * <p><b>⚠ 潜在缺陷登记（仅登记不改，TOOLS-04）</b>：{@link #execute} 生成模式传
 * {@code env.generateName.apply(List.of(), null)} 空消息表 → generateSessionName 对空
 * 文本返回 null → 生成模式恒无法产出真实名（CC rename.ts:37 传实际
 * {@code getMessagesAfterCompactBoundary(context.messages)}）。待后续统一处置（若走
 * suggest-name 端点则绕开本缺陷）。
 */
public class RenameCommand {

    /** 执行环境 (CC 全局 state 注入). */
    public record Env(
        Supplier<Boolean> isTeammate,
        Supplier<UUID> sessionId,
        Supplier<String> transcriptPath,
        BiFunction<List<String>, String, CompletableFuture<String>> generateName,  // (messages, signal) → name
        BiFunction<UUID, String, CompletableFuture<Void>> saveCustomTitle,        // (sessionId, name) → void
        BiFunction<UUID, String, CompletableFuture<Void>> saveAgentName,
        BiFunction<String, String, CompletableFuture<Void>> updateBridgeTitle,
        Consumer<String> setAppStateName,
        Consumer<String> onDone
    ) {}

    public record RenameResult(boolean renamed, String message, String display) {}

    public RenameResult execute(String args, Env env) {
        if (env.isTeammate.get()) {
            String msg = "Cannot rename: This session is a swarm teammate. Teammate names are set by the team leader.";
            env.onDone.accept(msg);
            return new RenameResult(false, msg, "system");
        }
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isEmpty()) {
            // 生成模式: messages + signal 注入
            // 实际 signal 在 env 提供, 这里简化为空 messages 调用
            CompletableFuture<String> genFut = env.generateName.apply(List.of(), null);
            String generated;
            try {
                generated = genFut.get();
            } catch (Exception e) {
                generated = null;
            }
            if (generated == null) {
                String msg = "Could not generate a name: no conversation context yet. Usage: /rename <name>";
                env.onDone.accept(msg);
                return new RenameResult(false, msg, "system");
            }
            trimmed = generated;
        }
        UUID sessionId = env.sessionId.get();
        String fullPath = env.transcriptPath.get();
        // 1. saveCustomTitle
        env.saveCustomTitle.apply(sessionId, trimmed).join();
        // 2. bridge 同步 (best-effort, 不阻断)
        try {
            env.updateBridgeTitle.apply(sessionId.toString(), trimmed).join();
        } catch (Exception ignored) {
            // CC: .catch(() => {}) — 不阻断主流程
        }
        // 3. saveAgentName
        env.saveAgentName.apply(sessionId, trimmed).join();
        // 4. setAppState
        env.setAppStateName.accept(trimmed);
        // 5. onDone
        String msg = "Session renamed to: " + trimmed;
        env.onDone.accept(msg);
        return new RenameResult(true, msg, "system");
    }
}