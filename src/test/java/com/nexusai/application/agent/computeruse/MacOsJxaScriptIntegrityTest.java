package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * macOS AX 脚本<b>可执行性</b>单测 · computer-use。
 *
 * <h2>WHY（规则 9 · 钉住的是「脚本能不能跑」而不是「脚本长什么样」）</h2>
 * <p>这三个缺陷都是 2026-11-02 真机实测出来的，共同点是：
 * <b>Java 单测全绿、编译全绿，但工具在真机上 100% 不可用</b>。
 * <pre>
 * ① rebuildTree 里根记录 loc="" 且 parentLocator("")="" ⇒ 自指 → StackOverflowError
 * ② accessibilityInvoke 的脚本里有顶层 `return;` ⇒ osascript 报
 *    SyntaxError: Return statements are only valid inside functions
 * ③ accessibilityInvoke 漏拼 axHashJsFunction() ⇒ 运行时 Can't find variable: axHash
 * </pre>
 * <p>① 已在 {@code MacOsUiaServiceTreeTest} 钉住；本类钉住 ② ③ ——
 * 二者都是「脚本语法/符号层面」的错误，用<b>静态断言</b>就能在无 macOS 的机器上拦住。
 */
class MacOsJxaScriptIntegrityTest {

    @Test
    @DisplayName("陈旧校验脚本必须自带 axHash（否则运行时 Can't find variable: axHash）")
    void invokeScriptDefinesAxHash() {
        String script = MacOsJxa.accessibilityInvoke("0.1", "abc123:42");
        // 用到了就必须定义：调用点与定义必须同时出现在同一段脚本里
        assertThat(script).contains("axHash(axSignatures(");
        assertThat(script).contains("function axHash(");
        // observe 侧同样要能算同一个指纹（跨进程比对的前提）
        String snapshotScript = MacOsJxa.accessibilitySnapshot(14, 500, 64_000);
        assertThat(snapshotScript).contains("function axHash(");
        assertThat(snapshotScript).contains("stateHash: axHash(axSignatures(");
    }

    @Test
    @DisplayName("osascript 脚本不得出现裸 return（顶层/函数内裸 return 均为语法或逻辑错误）")
    void noBareReturn() {
        // accessibilityInvoke 曾经在「陈旧分支」里写 `return;` 提前退出。
        // ⛔ 注意：缩进毫无意义 —— 那段代码是「顶层 if 块内缩进 20 空格的 return」，
        //   按缩进去判会漏掉（本用例第一版就漏了）。这里按「裸 return」判：
        //   本仓所有 JXA 片段里的 return 都带表达式，裸 return 一律视为缺陷。
        for (String script : List.of(
                MacOsJxa.accessibilityInvoke("0", "h:1"),
                MacOsJxa.accessibilityPerformAction("0", "AXPress"),
                MacOsJxa.accessibilitySetValue("0", "v"),
                MacOsJxa.accessibilitySnapshot(14, 500, 64_000))) {
            assertThat(bareReturns(script))
                    .as("裸 return 会让 osascript 报 SyntaxError（顶层）或提前退出（块内）")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("陈旧与新鲜的判定都在同一段脚本内完成，不靠提前 return 分支")
    void staleBranchStillReportsThroughJson() {
        String script = MacOsJxa.accessibilityInvoke("0.1", "expected:1");
        // 陈旧 ⇒ 报告 -25209 并继续走到统一的 JSON.stringify
        assertThat(script).contains("-25209");
        assertThat(script).contains("stateMatched");
        // 且动作只在「未判陈旧」时执行（守卫是 if/else，不是 return）
        assertThat(script).contains("out.stateMatched === false");
    }

    /** 收集「不带返回值的 return」（{@code return;} 或行尾仅 return），与缩进、注释无关。 */
    private static List<String> bareReturns(String script) {
        return script.lines()
                .map(line -> {
                    int comment = line.indexOf("//");
                    return comment >= 0 ? line.substring(0, comment) : line;
                })
                .filter(line -> line.matches(".*\\breturn\\s*;.*") || line.matches(".*\\breturn\\s*$"))
                .toList();
    }
}
