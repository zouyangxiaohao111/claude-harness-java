package com.nexusai.application.agent.computeruse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 修饰键（modifier chord）解析 · computer-use Phase 2b 的<b>修饰键点击</b>底座。
 *
 * <p><b>CCB 对照</b>：CCB 的 {@code left_click} / {@code double_click} / … 五个点击变体都接受
 * {@code text} 参数（{@code packages/@ant/computer-use-mcp/src/tools.ts:146-152} 的
 * {@code clickModifierText}），CCB 的处理器把 {@code args.text} 交给
 * {@code parseKeyChord}（{@code toolCalls.ts:748-753}）拆成键名数组，再由执行器在点击<b>期间</b>
 * 按住这些键（macOS 侧 {@code executor.ts:202-222} 的 {@code withModifiers}：
 * <b>正序按下 → 执行 → 逆序释放</b>）。
 *
 * <p><b>⚠️ CCB-Windows 的实现差异（如实标注）</b>：CCB 的<b>跨平台</b>执行器
 * （{@code src/utils/computerUse/executorCrossPlatform.ts:363-387}）的 {@code click()} 形参是
 * {@code _modifiers?}（<b>下划线前缀 = 未使用</b>）⇒ <b>Windows 上修饰键根本没被按住</b>，
 * 点击是「裸点击」。本仓<b>按 CCB 描述与 macOS 语义实现</b>（修饰键真的被按住），
 * 因为工具描述明写 “Modifier keys <b>to hold during the click</b>” ——
 * 描述既然这么写，行为必须真的如此（否则等于对模型说谎）。
 *
 * <p><b>放行规则</b>：只允许<b>修饰键</b>（{@code shift / ctrl / alt / meta / win} 及其左右变体）——
 * 非修饰键（如 {@code "a"}）不是「按住修饰键」，而是「打字」，语义不同 ⇒ 显式拒绝（fail loud）。
 */
public final class ComputerUseModifierChord {

    private ComputerUseModifierChord() {
        throw new AssertionError("utility class");
    }

    /**
     * 解析修饰键串 → 有序的按下事件列表。
     *
     * <p>逐字对齐 CCB {@code parseKeyChord}（{@code toolCalls.ts:748-753}）：
     * {@code text.split('+').map(trim).filter(Boolean)}。
     *
     * @param text 修饰键串（如 {@code "ctrl+shift"}）；null / 空白 ⇒ 空列表
     * @return 按下事件（按下顺序 = 串里的顺序）
     * @throws IllegalArgumentException 串里有非修饰键
     */
    public static List<ComputerUseKeyEvent> parse(String text) {
        List<ComputerUseKeyEvent> events = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return events;
        }
        for (String raw : text.split("\\+")) {
            String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            String lower = part.toLowerCase(Locale.ROOT);
            if (!ComputerUseVirtualKeys.MODIFIER_KEYS.contains(lower)) {
                throw new IllegalArgumentException(
                        "修饰键点击只支持修饰键（shift / ctrl / alt / meta / win），收到: " + part);
            }
            events.add(ComputerUseKeyEvent.named(lower, true));
        }
        return events;
    }

    /**
     * 释放事件列表 = 按下事件的<b>逆序</b>。
     *
     * <p>逐字对齐 CCB {@code executor.ts:188-197} 的 {@code releasePressed}：
     * 弹栈式释放 ⇒ 后按下的先释放。
     *
     * @param pressEvents {@link #parse(String)} 的结果
     * @return 释放事件（逆序）
     */
    public static List<ComputerUseKeyEvent> releaseOrder(List<ComputerUseKeyEvent> pressEvents) {
        List<ComputerUseKeyEvent> release = new ArrayList<>();
        for (int i = pressEvents.size() - 1; i >= 0; i--) {
            ComputerUseKeyEvent press = pressEvents.get(i);
            release.add(new ComputerUseKeyEvent(press.name(), press.virtualKey(), false));
        }
        return release;
    }
}
