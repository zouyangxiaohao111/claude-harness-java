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
 * <p><b>本仓为什么具名键走 CoreGraphics、文本走剪贴板</b>：
 * <ol>
 *   <li><b>具名键</b>（{@link #sendKeyEvent}）：本仓的组合键被拆成<b>逐事件</b>序列
 *       （{@link ComputerUseKeyCombo#parse(String)}：修饰键 down → 主键 down → 主键 up → 修饰键 up），
 *       每次是<b>独立的一次进程外调用</b> ⇒ 「当前按住哪些修饰键」必须由<b>本进程</b>记住并写进
 *       后续事件的 flags（{@code CGEventSetFlags}）—— AppleScript 的 {@code using {…}} 语法
 *       <b>无法跨调用表达</b>。这一条仍然成立，所以具名键继续走 CoreGraphics。</li>
 *   <li><b>文本</b>（{@link #typeText}）：原本想用 CoreGraphics 的
 *       {@code CGEventSetUnicodeString} 做「字符直投」（对齐 Windows 的 {@code KEYEVENTF_UNICODE}），
 *       但<b>实测 JXA 桥根本不支持</b> —— 该函数不存在，正式 API 名
 *       {@code CGEventKeyboardSetUnicodeString} 也传不进 {@code UniChar*}（详见
 *       {@link MacOsJxa#unicodeKey(String, String)} 的废弃说明与
 *       {@link MacOsJxa#pasteText(String)}）。
 *       <br>⇒ 文本改走<b>剪贴板 + ⌘V</b>（macOS 上唯一能保证任意 Unicode 的路径），
 *       并在同一次 JXA 调用内<b>还原用户原剪贴板</b>（含图片 / RTF / HTML 等非文本类型），
 *       见 {@link MacOsJxa#pasteText(String)}。</li>
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
        typeText(new String(Character.toChars(codePoint)));
    }

    /**
     * 文本输入 · <b>剪贴板 + ⌘V</b>（macOS 的唯一可行路径）。
     *
     * <h2>⛔ 为什么不用 CoreGraphics {@code CGEventSetUnicodeString}</h2>
     * <p>本类原本的设计（见类注释的两条理由）依赖该函数，但实测在本机
     * macOS 15.7.9 的 JXA 桥上：
     * <ul>
     *   <li>{@code $.CGEventSetUnicodeString} <b>不存在</b>（{@code typeof} = undefined）⇒
     *       直接抛 {@code TypeError}（这正是「{@code type} 完全不可用」的现场报错）；</li>
     *   <li>换成正式 API 名 {@code CGEventKeyboardSetUnicodeString} 虽然有函数、不报错，
     *       但 <b>JS 字符串传不进 {@code UniChar*}</b> —— 传 {@code "BBB"} 实际打出 {@code "aaa"}。</li>
     * </ul>
     * ⇒ JXA 无法注入任意 Unicode；AppleScript {@code keystroke} 也只对 ASCII 有效
     * （{@code keystroke "中文"} 什么都不输入）。<b>剪贴板是唯一能保证任意 Unicode 的路径</b>。
     *
     * <h2>剪贴板必须还原</h2>
     * <p>用户剪贴板可能是图片 / RTF / HTML。快照与还原都在
     * {@link MacOsJxa#pasteText(String)} 的同一次 JXA 调用里完成（按类型逐项重建），
     * 不在 Java 侧用 {@code pbpaste}/{@code pbcopy} 中转（那会把非文本内容丢掉）。
     *
     * <p><b>⚠️ 语义代价（如实标注）</b>：⌘V 粘贴与「直投字符」不完全等价 ——
     * 目标控件若屏蔽粘贴（如某些密码框、终端的安全输入），粘贴会失败而字符直投不会。
     * 该失败是<b>显式报错</b>（脚本输出 {@code pasted:false} 或 {@code restored:false}），不静默。
     *
     * @param text 要输入的文本（调用方保证不含换行 / 制表符）
     * @throws Exception 输入失败（fail loud）
     */
    @Override
    public void typeText(String text) throws Exception {
        if (text == null || text.isEmpty()) {
            return;
        }
        tcc.requireAccessibility();
        // 修饰键按住时 ⌘V 会变成别的组合键 ⇒ 先临时释放（对齐「物理按住 ctrl 再打字」的语义例外）
        Set<MacOsVirtualKeys.Modifier> resumeHeld = holdDownModifiersForPaste();
        String raw;
        try {
            raw = MacOsExec.jxa(runner, MacOsJxa.pasteText(text));
        } finally {
            for (MacOsVirtualKeys.Modifier modifier : resumeHeld) {
                held.add(modifier);
            }
        }
        // 脚本输出单行 JSON：{"restored":bool,"pasted":bool,"snapshotItems":n}
        boolean restored = raw.contains("\"restored\":true");
        boolean pasted = raw.contains("\"pasted\":true");
        if (!pasted) {
            throw new IllegalStateException(
                    "[computer-use] macOS 剪贴板粘贴失败（目标控件可能不接受 ⌘V）: " + raw);
        }
        if (!restored) {
            // 粘贴成功但还原失败 ⇒ 用户的剪贴板已被我们的文本占用 ⇒ 必须显式警告
            log.warn("[computer-use] macOS 剪贴板还原失败（用户原剪贴板内容已被本次输入覆盖）: {}", raw);
        }
        if (log.isDebugEnabled()) {
            log.debug("[computer-use] macOS 已通过剪贴板粘贴 {} 字符（剪贴板已还原={}）",
                    text.length(), restored);
        }
    }

    /**
     * 粘贴前把所有按住的修饰键抬起来（返回需要恢复的集合）。
     *
     * <p>⌘V 自身带 command flag；若用户/模型此前按住了 ctrl/shift/option，
     * 合成的 ⌘V 会变成 ⌘⌃V 之类的另一个命令 ⇒ 先发 up 事件释放，粘贴后再补 down 事件。
     *
     * @return 原本按住、需要在粘贴后恢复的修饰键
     */
    private Set<MacOsVirtualKeys.Modifier> holdDownModifiersForPaste() throws Exception {
        if (held.isEmpty()) {
            return Set.of();
        }
        Set<MacOsVirtualKeys.Modifier> snapshot = EnumSet.copyOf(held);
        for (MacOsVirtualKeys.Modifier modifier : snapshot) {
            held.remove(modifier);
            // 该 up 事件不带自己的 flag（与 sendKeyEvent 的状态机一致）
            MacOsExec.jxaVoid(runner,
                    MacOsJxa.keyEvent(modifier.keyCode(), false, flagsExpression()));
        }
        return snapshot;
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
