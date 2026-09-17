package com.nexusai.application.agent.bash;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 源码卫生守卫（批 NUL1）：源文件里不得出现「非法的字面控制字节」。
 *
 * <p><b>WHY 这条守卫必须存在</b>：源码里混入不可见控制字节是极容易复现的写入事故。
 * 后果分轻重两档：
 * <ul>
 *   <li><b>含 U+0000</b>：git 只看前 8000 字节里有没有 U+0000 —— 有一个，整个文件就退化成
 *       {@code Bin N -> M bytes}，该文件<b>永久无法 diff / review / 合并</b>
 *       （{@code SedEditParser.java} 改前正是这样：28 个 NUL 当占位符边界）；</li>
 *   <li><b>含其它控制字节</b>：文件仍可 diff，但控制字符在编辑器里不可见，
 *       改一处看不见的字符就能改掉语义，review 时无从察觉。</li>
 * </ul>
 *
 * <p>⭐ 判据是<b>纯字节级</b>的（{@code Files.readAllBytes} 后逐字节判定），
 * <b>刻意不用</b>字符串或正则匹配 —— 字符串层面正是转义最容易被混淆的地方
 * （一个 NUL 字节与六个字符的反斜杠-u-0-0-0-0 在字符串里"看起来"都能命中），用字节判可绕开这一层。
 *
 * <p>⚠️ <b>本守卫拦不住什么（如实登记）</b>：
 * <ul>
 *   <li><b>写入侧的转义吞并</b> —— 编辑器/工具链把转义还原成真控制字节，发生在写盘<b>之前</b>；
 *       本守卫只能在写盘<b>之后</b>看结果，抓不到过程；</li>
 *   <li><b>本次列出的文件之外</b> —— 只扫 {@link #FILES} 列到的路径。仓库里另有
 *       {@code workflow/script/RestrictedScriptExecutor.java}、
 *       {@code workflow/script/WorkflowScriptParser.java} 含 {@code 0x0b}，
 *       以及 9 个测试文件含 {@code 0x02}，均<b>未</b>纳入本守卫；</li>
 *   <li><b>非控制类不可见字符</b> —— 零宽空格、双向控制符、U+00A0 等不在判据内。</li>
 * </ul>
 */
class SourceHygieneTest {

    /** 待守卫的源文件，相对模块根（{@code backend/}）。 */
    private static final List<String> FILES = List.of(
        "src/main/java/com/nexusai/application/agent/bash/SedEditParser.java"
    );

    /**
     * 判据：{@code b < 0x20} 且不是 Tab(0x09)/LF(0x0a)/CR(0x0d)，或 {@code b == 0x7f}（DEL）
     * ⇒ 命中即非法。纯字节级，返回命中处的字节偏移。
     */
    private static List<Integer> offenders(byte[] bytes) {
        List<Integer> bad = new ArrayList<>();
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xff;
            if (b == 0x7f || (b < 0x20 && b != 0x09 && b != 0x0a && b != 0x0d)) bad.add(i);
        }
        return bad;
    }

    @Test
    @DisplayName("扫描器本身有效（否则守卫会静默失效，永远绿）")
    void scannerDetectsAndIgnoresCorrectly() {
        // WHY: 一条永远返回「无命中」的守卫比没有守卫更坏 —— 它给人虚假的安全感。
        // 这里正反两面都钉住：该命中的要命中，Tab/LF/CR 要放过。
        assertEquals(List.of(1), offenders(new byte[] {0x41, 0x00, 0x42}));
        assertEquals(List.of(1), offenders(new byte[] {0x41, 0x1b, 0x42}));
        assertEquals(List.of(1), offenders(new byte[] {0x41, 0x7f, 0x42}));
        assertEquals(List.of(), offenders(new byte[] {0x41, 0x09, 0x0a, 0x0d, 0x42}));
        assertEquals(List.of(), offenders("a中Z9".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("目标源文件不含非法字面控制字节")
    void sourcesAreClean() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String rel : FILES) {
            Path path = resolve(rel);
            if (path == null) {
                // 找不到就显式失败，绝不静默跳过（规则十二 显式失败）
                problems.add("找不到源文件 " + rel + "，cwd=" + Path.of("").toAbsolutePath());
                continue;
            }
            byte[] bytes = Files.readAllBytes(path);
            List<Integer> bad = offenders(bytes);
            if (!bad.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int offset : bad) {
                    sb.append(String.format("offset %d=0x%02x; ", offset, bytes[offset] & 0xff));
                }
                problems.add(rel + " 命中 " + bad.size() + " 处: " + sb);
            }
        }
        assertEquals(List.of(), problems, String.join("\n", problems));
    }

    /**
     * 定位源文件。surefire 的 cwd 通常是模块根（{@code backend/}），但用
     * {@code -f backend/pom.xml} 从仓库根启动时也可能是仓库根，故两个候选都试。
     */
    private static Path resolve(String rel) {
        for (Path candidate : new Path[] {Path.of(rel), Path.of("backend").resolve(rel)}) {
            if (Files.exists(candidate)) return candidate.toAbsolutePath();
        }
        return null;
    }
}
