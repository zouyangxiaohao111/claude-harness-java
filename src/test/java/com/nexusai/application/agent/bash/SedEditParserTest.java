package com.nexusai.application.agent.bash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexusai.application.agent.bash.SedEditParser.SedEditInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SedEditParser 行为回归测试（批 NUL1）。
 *
 * <p><b>WHY 本测试存在</b>：{@code applySedSubstitution} 做 BRE→ERE 转换时，用 7 个占位符
 * （BACKSLASH / PLUS / QUESTION / PIPE / LPAREN / RPAREN / AMP）在待转换文本里打桩，
 * 每个占位符前后各夹一个 U+0000 作边界。这 28 个边界字符原先是以<b>字面控制字节</b>
 * 写在源码里的，git 因此把整个文件判为二进制（{@code Bin N -> M bytes}）⇒ 该文件
 * <b>永久无法 diff / review / 合并</b>。
 *
 * <p>改成 Java 的 u0000 unicode 转义后，因为 unicode 转义是<b>预词法</b>展开，
 * 字符串字面量在运行时拿到的仍是同一个 U+0000，语义必须逐字节不变。
 *
 * <p>所以本测试断言的是<b>占位符往返之后的替换结果</b>：任何一处占位符的边界被写坏
 * （例如漏掉一侧边界），或转义写错，替换结果就会偏离下面的期望值。所有期望值都取自
 * 改前（HEAD）版本真实跑出来的输出，不是推导出来的。
 */
class SedEditParserTest {

    /** 走「完整 sed 命令 → 解析 → 替换」的真实链路。 */
    private static String sub(String command, String content) {
        SedEditInfo info = SedEditParser.parseSedEditCommand(command);
        assertNotNull(info, "应能解析为 sed -i 就地编辑: " + command);
        return SedEditParser.applySedSubstitution(content, info);
    }

    @Test
    @DisplayName("BRE 元字符按字面处理：+ ? | ( ) 只有转义后才作元字符")
    void breMetacharactersAreLiteralUnlessEscaped() {
        // WHY: BRE→ERE 转换的全部存在理由就是这个 —— PLUS / QUESTION / PIPE / LPAREN / RPAREN
        // 五个占位符各往返一次。边界写坏时裸的 + 会被当成量词、裸的 | 会被当成或，
        // 于是「字面 +」的输入不再被匹配，下面这四条立刻变红。
        assertEquals("xXy", sub("sed -i 's/a+b/X/' f.txt", "xa+by"));
        assertEquals("xXy", sub("sed -i 's/a?b/X/' f.txt", "xa?by"));
        assertEquals("xXy", sub("sed -i 's/a|b/X/' f.txt", "xa|by"));
        assertEquals("xXy", sub("sed -i 's/(a)/X/' f.txt", "x(a)y"));

        // 反向：转义之后必须恢复元字符语义（否则转换把 ERE 元字符一起打死）
        assertEquals("xXy", sub("sed -i 's/a\\+b/X/' f.txt", "xaaaby"));
        assertEquals("xXy", sub("sed -i 's/a\\?b/X/' f.txt", "xaby"));
        assertEquals("xXy", sub("sed -i 's/a\\|b/X/' f.txt", "xby"));
        assertEquals("xXy", sub("sed -i 's/\\(a\\)/X/' f.txt", "xay"));
    }

    @Test
    @DisplayName("BACKSLASH 占位符往返：模式里的字面反斜杠仍匹配一个反斜杠")
    void backslashPlaceholderRoundTrips() {
        // WHY: 占位符还原时若漏掉一侧边界，正则就变成「边界字符 + BACKSLASH + 边界字符」
        // 这个字面串，真反斜杠不再被匹配 —— 第一条变红。
        assertEquals("xaXb", sub("sed -i 's/\\\\/X/' f.txt", "xa\\b"));
        // WHY 第二条: 内容里真出现 BACKSLASH 这个词时必须原样保留 ——
        // 证明 BACKSLASH 只是内部的桩，绝不能被当成待匹配文本吃掉。
        assertEquals("xXBACKSLASH y", sub("sed -i 's/\\\\/X/' f.txt", "x\\BACKSLASH y"));
    }

    @Test
    @DisplayName("AMP 占位符往返：转义的 & 是字面 &，内容里的 AMP 不被吃掉")
    void ampPlaceholderRoundTrips() {
        // WHY: & 在 sed 替换串里是「整个匹配」的反向引用，转义与否必须区分开；
        // AMP 占位符就是为隔离这件事而存在（源码里另外还有裸 & → 反向引用的分支）。
        assertEquals("Y&Zax", sub("sed -i 's/x/Y\\&Z/' f.txt", "xax"));
        // 内容里真出现 AMP 这个词时必须原样保留（证明桩未泄漏进结果）
        assertEquals("amp mY&Zre AMP", sub("sed -i 's/o/Y\\&Z/' f.txt", "amp more AMP"));
    }

    @Test
    @DisplayName("其它 BRE 转义与 -E 扩展模式不受影响")
    void otherBreEscapesAndExtendedMode() {
        assertEquals("xX y", sub("sed -i 's/a\\.b/X/' f.txt", "xa.b y"));
        assertEquals("xXy", sub("sed -E -i 's/a+b/X/' f.txt", "xaaaby"));
        assertEquals("xXy", sub("sed -i 's/extended\\|regex/X/' f.txt", "xextendedy"));
    }

    @Test
    @DisplayName("非 sed -i 就地编辑的命令不得被解析")
    void nonSedInPlaceCommandsAreNotParsed() {
        assertNull(SedEditParser.parseSedEditCommand("ls -la"));
        assertNull(SedEditParser.parseSedEditCommand("sed -i 's/a+b/X/'"));
        assertFalse(SedEditParser.isSedInPlaceEdit("sed -n '1p' f.txt"));
    }

    @Test
    @DisplayName("占位符边界以 unicode 转义书写，而不是字面控制字节")
    void placeholderBoundariesUseUnicodeEscape() throws IOException {
        String src = Files.readString(sourceFile(), StandardCharsets.UTF_8);
        // WHY: 「文件里没有非法字面控制字节」这条通用卫生守卫由 SourceHygieneTest 统一负责；
        // 这里只钉住本类特有的那件事 —— 边界必须是转义写法，而不是「碰巧现在没有控制字节」。
        assertTrue(src.contains("\\u0000BACKSLASH\\u0000"),
            "占位符边界必须以 u0000 unicode 转义书写，而不是字面控制字节");
    }

    /**
     * 定位本类源码。surefire 的 cwd 通常是模块根（backend/），但用 {@code -f backend/pom.xml}
     * 从仓库根启动时也可能是仓库根，故两个候选都试；都找不到就<b>显式失败</b>，不静默跳过。
     */
    private static Path sourceFile() {
        String rel = "src/main/java/com/nexusai/application/agent/bash/SedEditParser.java";
        Path[] candidates = {Path.of(rel), Path.of("backend").resolve(rel)};
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) return candidate.toAbsolutePath();
        }
        throw new AssertionError("找不到 SedEditParser.java 源码，cwd=" + Path.of("").toAbsolutePath());
    }
}
