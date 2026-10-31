package com.nexusai.application.agent.computeruse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * macOS 键盘实现（JXA + CoreGraphics）· computer-use 的键盘注入底座。
 *
 * <h2>⚠️ 与 CCB 的实现差异（逐条标注 · <b>CCB 无对应</b>）</h2>
 * <p>逐行读过 CCB 的 {@code packages/@ant/computer-use-input/src/backends/darwin.ts}
 * 全文件（210 行）后确认：<b>CCB 的 macOS 键盘走 AppleScript System Events</b>，
 * <b>没有</b> {@code CGEventCreateKeyboardEvent}：
 * <ul>
 *   <li>具名键：{@code :99} {@code tell application "System Events" to key code ${keyCode}}；</li>
 *   <li>文本：{@code :102} / {@code :183} {@code … to keystroke "${text}"}；</li>
 *   <li>组合键：{@code :107-128} {@code keys()} —— <b>一次</b>调用里用
 *       {@code using {command down}} 语法挂修饰键。</li>
 * </ul>
 *
 * <p><b>本仓为什么改走 CoreGraphics</b>（两条硬理由）：
 * <ol>
 *   <li><b>事件模型不同</b>：本仓的组合键被拆成<b>逐事件</b>序列
 *       （{@link ComputerUseKeyCombo#parse(String)}：修饰键 down → 主键 down → 主键 up → 修饰键 up），
 *       每次 {@link #sendKeyEvent(ComputerUseKeyEvent)} 是<b>独立的一次进程外调用</b>
 *       ⇒ 「当前按住哪些修饰键」必须由<b>本进程</b>记住并写进后续事件的 flags
 *       （{@code CGEventSetFlags}）—— AppleScript 的 {@code using {…}} 语法
 *       <b>无法跨调用表达</b>。</li>
 *   <li><b>字符语义</b>：AppleScript {@code keystroke} 会走键盘布局 / 修饰键解释路径；
 *       CoreGraphics 的 {@code CGEventSetUnicodeString} 是「这个字符」的直投
 *       —— 与本仓 Windows 侧选 {@code KEYEVENTF_UNICODE}（而非 CCB 的 {@code SendKeys}）
 *       是<b>同一个取舍</b>（见 {@link ComputerUseKeyboard} 类注释）。</li>
 * </ol>
 *
 * <h2>修饰键状态机</h2>
 * <p>本类持有一个 {@code held} 集合（当前按住的修饰键）：
 * <ul>
 *   <li>收到<b>修饰键 down</b> ⇒ 加入集合 ⇒ 该事件带自己的 flag；</li>
 *   <li>收到<b>修饰键 up</b> ⇒ 先移出集合 ⇒ 该事件<b>不带</b>自己的 flag；</li>
 *   <li>主键 / 字符事件 ⇒ 带集合里所有修饰键的 flag。</li>
 * </ul>
 * ⇒ 「{@code ctrl+shift+a}」在本仓的事件序列
 * （{@code ctrl down → shift down → a down → a up → shift up → ctrl up}）
 * 下，主键 {@code a} 会带上 {@code control|shift} 两个 flag ⇒ 语义正确。
 */
public final class MacOsKeyboard implements ComputerUseKeyboard {

    private static final Logger log = LoggerFactory.getLogger(MacOsKeyboard.class);

    private final ComputerUseProcessRunner runner;
    private final MacOsTccChecker tcc;

    /** 当前按住的修饰键（本仓逐事件派发模型的状态机）。 */
    private final Set<MacOsVirtualKeys.Modifier> held =
            EnumSet.noneOf(MacOsVirtualKeys.Modifier.class);

    /** 生产构造器（真起进程）。 */
    public MacOsKeyboard() {
        this(ComputerUseProcessRunner.REAL, new MacOsTccChecker());
    }

    /**
     * @param runner 外部命令执行器
     * @param tcc    TCC 授权检查
     */
    public MacOsKeyboard(ComputerUseProcessRunner runner, MacOsTccChecker tcc) {
        if (runner == null || tcc == null) {
            throw new IllegalArgumentException("[computer-use] MacOsKeyboard 依赖不能为空");
        }
        this.runner = runner;
        this.tcc = tcc;
    }

    @Override
    public void typeChar(int codePoint) throws Exception {
        tcc.requireAccessibility();
        String text = new String(Character.toChars(codePoint));
        // 当前按住的修饰键同样作用到字符上（与「物理按住 ctrl 再打字」同款）
        MacOsExec.jxaVoid(runner, MacOsJxa.unicodeKey(text, flagsExpression()));
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] macOS 已注入字符: U+{} ({})",
                    Integer.toHexString(codePoint), text);
        }
    }

    @Override
    public void sendKeyEvent(ComputerUseKeyEvent event) throws Exception {
        if (event == null) {
            throw new IllegalArgumentException("[computer-use] 按键事件不能为空");
        }
        tcc.requireAccessibility();
        String name = event.name();
        MacOsVirtualKeys.Modifier modifier = MacOsVirtualKeys.modifierOf(name);
        int keyCode = MacOsVirtualKeys.keyCodeOf(name);
        // 状态机：修饰键 down ⇒ 进入 held；修饰键 up ⇒ 先离开 held（该事件不带自己的 flag）
        if (modifier != null) {
            if (event.down()) {
                held.add(modifier);
            } else {
                held.remove(modifier);
            }
        }
        String flags = flagsExpression();
        MacOsExec.jxaVoid(runner, MacOsJxa.keyEvent(keyCode, event.down(), flags));
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] macOS 按键事件已注入: {} · keyCode={} · down={} · flags={}",
                    name, keyCode, event.down(), flags);
        }
    }

    /** 当前按住的修饰键 flags 表达式（空 ⇒ 无修饰键）。 */
    private String flagsExpression() {
        if (held.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (MacOsVirtualKeys.Modifier modifier : held) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(modifier.flagSymbol());
        }
        return sb.toString();
    }

    /**
     * 当前按住的修饰键（测试 / 诊断用）。
     *
     * @return 修饰键集合的快照
     */
    public Set<MacOsVirtualKeys.Modifier> heldModifiers() {
        return EnumSet.copyOf(held.isEmpty()
                ? EnumSet.noneOf(MacOsVirtualKeys.Modifier.class)
                : held);
    }
}
