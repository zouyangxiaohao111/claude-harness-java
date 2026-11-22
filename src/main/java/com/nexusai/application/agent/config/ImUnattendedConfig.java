package com.nexusai.application.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Predicate;

/**
 * 「无人值守会话」工具池裁剪（对齐 CC Ask 可用性门 {@code tfi()} 的<b>非交互</b>分支）。
 *
 * <h2>CC 怎么做的</h2>
 * <pre>
 *   isEnabled() {
 *     if ((feature('KAIROS') || feature('KAIROS_CHANNELS')) &amp;&amp; getAllowedChannels().length &gt; 0) {
 *       return false;   // 非交互 + 有渠道 ⇒ 选择题对话框会挂住（键盘前没人）
 *     }
 *     return true;
 *   }
 * </pre>
 * CC 两个 false 分支都要求 {@code Se()}（非交互）。<b>交互式 + 仅 channels 不摘 Ask</b>。
 * CC 是单用户单进程，所以判据是进程级；
 * 我们是多会话（同一进程里既有无人值守会话、也有普通会话），
 * 照抄那一行会把所有会话的 AskUserQuestion 都关掉 ⇒ 必须<b>按会话</b>判定。
 *
 * <h2>我们的判据（2026-11-20 R6/R15/R16 实施）</h2>
 * 会话<b>显式</b>开启无人值守（{@code sessions.unattended_mode = 1}，见
 * {@link SessionModeState#isUnattended(String)}）⇒ 该会话剔除 AskUserQuestion。
 * <p>⛔ <b>不再</b>用 {@code im_chat.bound_session_id} 隐式判定：绑定渠道本身不禁 Ask
 * （交互式 + 仅 channels 按 CC 不摘）。这就是 R6 要求删掉的产品偏离。
 *
 * <h2>为什么是静态 + setter</h2>
 * 调用点 {@code LlmAgentLoop.sessionVisibleTools} 是 static 方法（工具池每轮求值），
 * 拿不到 Spring Bean。仓内同款范式：{@link SessionToolDisableConfig} /
 * {@link SessionModeState}。⛔ 不要在这些静态方法里 new Service。
 *
 * <h2>为什么裁到 schema 层而不是权限层</h2>
 * 我们已经在 {@code CheckLayer1e_RequiresUserInteraction} 里对交互式工具<b>强制 Ask</b>
 * （bypass 免疫）。若只在权限层拦，模型仍会看到工具、调一次、吃到 Ask、再放弃 ——
 * 白吃一轮 token 且对话框可能真的挂住。CC 也是直接把它从 schema 里拿掉（`isEnabled()=false`）。
 */
public class ImUnattendedConfig {

    private static final Logger log = LoggerFactory.getLogger(ImUnattendedConfig.class);

    /** 被无人值守会话剔除的工具名（目前只有交互式选择题）。 */
    public static final String ASK_USER_QUESTION_TOOL = "AskUserQuestion";

    /** 测试注入的判定函数（sessionId → 是否无人值守）；null = 走 {@link SessionModeState}。 */
    private static volatile Predicate<String> unattendedChecker;

    private ImUnattendedConfig() {}

    /**
     * 测试注入判定函数（不依赖 Spring / 真库）。
     *
     * <p>生产路径不调用；生产判定唯一来源 = {@link SessionModeState#isUnattended(String)}。
     *
     * @param checker 判定函数；null = 恢复生产来源
     */
    public static void installChecker(Predicate<String> checker) {
        unattendedChecker = checker;
    }

    /**
     * 该会话是否无人值守（显式会话状态）。
     *
     * <p>任何异常/未接线 ⇒ false（fail-open 到「有人值守」）：
     * 宁可让 agent 多问一句，也不要让它在该问的时候闭嘴。
     */
    public static boolean isUnattended(String sessionId) {
        Predicate<String> checker = unattendedChecker;
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            if (checker != null) {
                return checker.test(sessionId);
            }
            return SessionModeState.isUnattended(sessionId);
        } catch (RuntimeException e) {
            log.warn("[ImUnattended] 判定无人值守抛异常，按有人值守处理: session={} err={}",
                    sessionId, e.toString());
            return false;
        }
    }

    /**
     * 该工具在无人值守会话里是否应被剔除（目前只有 AskUserQuestion）。
     *
     * <p>供面板 / ToolSearch / 执行入口与 schema 同源使用。
     *
     * @param toolName  工具名（可 null）
     * @param sessionId 会话 id
     * @return true = 应剔除
     */
    public static boolean isExcludedInUnattended(String toolName, String sessionId) {
        return toolName != null
                && ASK_USER_QUESTION_TOOL.equals(toolName)
                && isUnattended(sessionId);
    }

    /**
     * 裁剪工具池：无人值守会话里剔除交互式选择题。
     *
     * <p>为什么不用 `isEnabled()`（Tool 接口上的无参方法）：那是**全局**的，
     * 而我们同一进程里有不同形态的会话 —— 全局关会误伤普通会话。
     *
     * @param tools     当前可用工具（可 null）
     * @param sessionId 会话 id（可 null ⇒ 原样返回）
     * @return 裁剪后的列表（原列表不可变时返回新列表；无需裁剪时**原样返回**同一个实例）
     */
    public static List<com.nexusai.application.agent.tool.Tool> filterForUnattended(
            List<com.nexusai.application.agent.tool.Tool> tools, String sessionId) {
        if (tools == null || tools.isEmpty() || !isUnattended(sessionId)) {
            return tools;
        }
        List<com.nexusai.application.agent.tool.Tool> out = tools.stream()
                .filter(t -> t == null || !ASK_USER_QUESTION_TOOL.equals(t.name()))
                .toList();
        if (log.isDebugEnabled() && out.size() != tools.size()) {
            log.debug("[ImUnattended] 无人值守会话剔除 {} 个工具（AskUserQuestion）session={}",
                    tools.size() - out.size(), sessionId);
        }
        return out;
    }
}
