package com.nexusai.wechatlog.key;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 校验 {@link MacOsKeyExtractor} 生成的 lldb / Python 脚本确实可用。
 *
 * <h2>⚠️ 为什么必须有这个测试（真实事故）</h2>
 * 第一版用了 Java 的 {@code String.formatted()}，而脚本正文里有 Python 的
 * {@code %s} / {@code %d}（打印 lldb 状态用），于是 Java 把它当自己的格式符解析，
 * 运行到取密钥那一步才抛 {@code Format specifier '%s'} —— 用户真机执行时才暴露。
 *
 * <p>我先前「验证过 Python 语法」用的是<b>手抄的模板副本</b>，不是真实 Java 产物，
 * 所以完全没抓到。这个测试改成<b>调用真实的生成方法</b>并把结果交给 python3 编译，
 * 让「生成的脚本本身」成为被断言的对象。
 *
 * <h2>断言分层</h2>
 * <ol>
 *   <li>生成不抛异常（直接防住 formatted 那类崩溃）；</li>
 *   <li>产物是<b>合法 Python</b>（{@code python3 -m py_compile}）；</li>
 *   <li>占位符已全部替换（没有残留 {@code __XXX__}）；</li>
 *   <li>两个断点名都在产物里（否则 lldb 什么也抓不到）。</li>
 * </ol>
 */
class MacOsKeyExtractorScriptTest {

    @Test
    @DisplayName("生成 Python 脚本不抛异常（防住 Java 格式符冲突）")
    void buildsWithoutException() {
        MacOsKeyExtractor ex = new MacOsKeyExtractor(30);
        String script = assertDoesNotThrow(ex::buildPythonCaptureScript);
        assertNotNull(script);
        assertFalse(script.isBlank());
    }

    @Test
    @DisplayName("产物里没有未替换的占位符")
    void noPlaceholderLeft() {
        String script = new MacOsKeyExtractor(30).buildPythonCaptureScript();
        for (String ph : List.of("__ARCH__", "__CRYPT_KEY_REG__", "__CRYPT_LEN_REG__",
                "__KDF_KEY_REG__", "__KDF_LEN_REG__")) {
            assertFalse(script.contains(ph), "占位符未替换: " + ph);
        }
    }

    @Test
    @DisplayName("两个断点与寄存器都出现在产物里")
    void containsBothBreakpoints() {
        String script = new MacOsKeyExtractor(30).buildPythonCaptureScript();

        assertTrue(script.contains("CCCryptorCreate"), "缺少 CCCryptorCreate 断点");
        assertTrue(script.contains("CCKeyDerivationPBKDF"), "缺少 CCKeyDerivationPBKDF 断点");

        // 按当前架构断言对应寄存器
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (arm) {
            assertTrue(script.contains("\"x3\"") || script.contains("'x3'"), "arm64 应用 x3");
            assertTrue(script.contains("\"x1\"") || script.contains("'x1'"), "arm64 应用 x1");
        } else {
            assertTrue(script.contains("\"rcx\"") || script.contains("'rcx'"),
                    "x86_64 应用 rcx（第 4 个整型参数）");
            assertTrue(script.contains("\"r8\"") || script.contains("'r8'"),
                    "x86_64 应用 r8（第 5 个整型参数）");
            assertTrue(script.contains("\"rsi\"") || script.contains("'rsi'"),
                    "x86_64 应用 rsi（PBKDF 的 password）");
        }
    }

    @Test
    @DisplayName("产物是合法 Python（交给 python3 真正编译）")
    @EnabledOnOs(OS.MAC)
    void generatedScriptIsValidPython(@TempDir Path dir) throws Exception {
        String script = new MacOsKeyExtractor(30).buildPythonCaptureScript();
        Path py = dir.resolve("capture.py");
        Files.writeString(py, script, StandardCharsets.UTF_8);

        // 用 python3 -m py_compile 真正编译（语法错会非 0 退出）
        Process p = new ProcessBuilder("python3", "-m", "py_compile", py.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean done = p.waitFor(30, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            fail("python3 编译超时");
        }
        assertEquals(0, p.exitValue(), "生成的 Python 语法有错:\n" + out);
    }

    @Test
    @DisplayName("lldb 命令脚本引用真实路径且参数正确")
    void lldbCommandsReferenceRealPaths(@TempDir Path dir) {
        Path py = dir.resolve("capture.py");
        Path out = dir.resolve("keys.txt");
        String cmds = new MacOsKeyExtractor(30).buildLldbCommands(1234, 45, py, out);

        assertTrue(cmds.contains("command script import " + py.toAbsolutePath()));
        assertTrue(cmds.contains("nexusai_capture 1234 45 " + out.toAbsolutePath()));
        assertTrue(cmds.contains("quit"), "结尾应有 quit，否则 lldb 会挂着");
    }

    /**
     * 守卫：detach 必须在 lldb 命令层，且不得出现在 Python 侧。
     *
     * <p>真机事故：微信每轮抓取后都被 SIGKILL（{@code exited with status = 9}）。
     * 原因是我原来在 Python 里 {@code process.Stop() → Detach()} 后直接让 lldb 退出；
     * Stop 会把微信置于 SIGSTOP，一旦 Detach 失败，debugserver 就在 lldb 退出时补一刀 SIGKILL。
     * 对照 chatlog（glance.go）的正确做法：<b>命令层</b> {@code process detach} + {@code quit}。
     */
    @Test
    @DisplayName("detach 在 lldb 命令层，Python 里不得调 Stop()/Detach()")
    void detachBelongsToCommandLayer(@TempDir Path dir) {
        MacOsKeyExtractor ex = new MacOsKeyExtractor(30);

        String cmds = ex.buildLldbCommands(1, 10, dir.resolve("c.py"), dir.resolve("k.txt"));
        // ⚠️ 不能是无条件的 `process detach`：target 不存在时会报
        // "Process must be launched" 并让 lldb 以非 0 退出（真机事故 ⇒ 微信被杀）。
        // 必须走条件化的 safe_detach。
        int detachIdx = cmds.indexOf("nexusai_safe_detach");
        int quitIdx = cmds.indexOf("quit");
        assertTrue(detachIdx >= 0, "命令层必须调用 nexusai_safe_detach");
        assertTrue(quitIdx > detachIdx, "detach 必须在 quit 之前");
        assertFalse(cmds.contains("\nprocess detach\n"),
                "不得使用无条件的 process detach（target 为空时会报错并杀进程）");

        String py = ex.buildPythonCaptureScript();
        // ⚠️ 必须剥掉注释再断言：脚本里有一条注释写着「绝对不要调 process.Stop()」，
        // 裸 contains() 会把注释也匹配上（本仓踩过同款坑：裸 grep 把 javadoc 里的
        // 「禁 @SpringBootTest」当成用例）。所以这里只检查真正的代码行。
        String code = stripPythonComments(py);

        // 只约束「抓取流程」内部：它绝不能 Stop/Detach（那是杀的根源）。
        // 但 nexusai_safe_detach 这个「命令层实现」里出现 Detach 是**正确**的，
        // 所以按函数切段断言，而不是全文件扫。
        String captureBody = extractPythonFunction(code, "_do_capture");
        assertFalse(captureBody.isBlank(), "应能定位 _do_capture 函数体");
        assertFalse(captureBody.contains("process.Stop("),
                "_do_capture 内不得调 process.Stop() —— 会给微信发 SIGSTOP，分离失败即被 SIGKILL");
        assertFalse(captureBody.contains("process.Detach("),
                "_do_capture 内不得调 process.Detach() —— 分离交给 nexusai_safe_detach");

        // 反过来：safe_detach 必须存在且真的做了条件判断 + Detach
        String safeBody = extractPythonFunction(code, "nexusai_safe_detach");
        assertFalse(safeBody.isBlank(), "必须有 nexusai_safe_detach（条件化分离）");
        assertTrue(safeBody.contains("GetSelectedTarget") || safeBody.contains("GetProcess"),
                "safe_detach 必须先判断 target/process 是否存在");
        assertTrue(safeBody.contains("Detach("), "safe_detach 最终要真的 Detach");
    }

    /**
     * 守卫：detach 前必须切回<b>同步</b>模式。
     *
     * <p>真机事故（23:44）：日志同时出现
     * {@code Process exited with status = -1 (lost connection)} 与 {@code detached=True}
     * —— 因为 capture 开头调了 {@code SetAsync(True)}，异步模式下
     * {@code SBProcess.Detach()} 返回 True 只代表「请求入队」，不代表已离开目标；
     * 随后 {@code quit} 销毁 target，debugserver 在仍 attached 状态下退出 ⇒ 目标被 SIGKILL。
     *
     * <p>修法：detach 前 {@code SetAsync(False)} 并轮询状态确认。
     * 这条断言防止有人把它当冗余代码删掉。
     */
    @Test
    @DisplayName("safe_detach 必须先 SetAsync(False) 再 Detach，并确认最终状态")
    void detachRequiresSyncMode() {
        String py = new MacOsKeyExtractor(30).buildPythonCaptureScript();
        String code = stripPythonComments(py);
        String safeBody = extractPythonFunction(code, "nexusai_safe_detach");

        assertTrue(safeBody.contains("SetAsync(False)"),
                "detach 前必须切回同步模式（异步 Detach 返回 True 不代表真分离）");
        int setAsyncIdx = safeBody.indexOf("SetAsync(False)");
        int detachIdx = safeBody.indexOf("Detach(");
        assertTrue(setAsyncIdx >= 0 && detachIdx > setAsyncIdx,
                "SetAsync(False) 必须在 Detach 之前");

        assertTrue(safeBody.contains("GetState()"),
                "必须轮询状态确认，而不是只看 Detach 的返回值");
        assertTrue(safeBody.contains("eStateDetached"),
                "必须等最终状态是 eStateDetached / eStateExited");
    }

    /**
     * 守卫：断点必须带「长度 == 32」的条件。
     *
     * <p>真机事故（23:59）：微信自己的 WCDB 看门狗 abort 了进程 ——
     * <pre>
     * thread #8, name = 'WCDB.Operation', stop reason = signal SIGABRT
     * frame #0: libsystem_c.dylib`__abort +176
     * </pre>
     * 根因：{@code CCCryptorCreate} 是 WCDB 解密的热路径，<b>每次命中都暂停整个进程</b>，
     * 看门狗判定操作超时便主动 abort。
     *
     * <p>修法：把长度过滤下推成 <b>lldb 原生条件断点</b>（{@code SetCondition}），
     * 不满足时进程根本不会停 —— 这比在 Python 回调里 return False 便宜一个数量级。
     */
    @Test
    @DisplayName("断点必须带长度==32 的条件（否则 WCDB 看门狗会 abort 微信）")
    void breakpointsAreConditional() {
        String py = new MacOsKeyExtractor(30).buildPythonCaptureScript();
        String code = stripPythonComments(py);

        assertTrue(code.contains("SetCondition("), "必须设置条件断点");
        assertTrue(code.contains("$r8 == 32") || code.contains("$x4 == 32"),
                "CCCryptorCreate 的 keyLength 必须是 32（x86_64=r8 / arm64=x4）");
        assertTrue(code.contains("$rdx == 32") || code.contains("$x2 == 32"),
                "CCKeyDerivationPBKDF 的 passwordLen 必须是 32（x86_64=rdx / arm64=x2）");

        // 条件必须在 SetScriptCallbackFunction 之前/附近设置，且两个断点都要有
        int condCount = countOccurrences(code, "SetCondition(");
        assertEquals(2, condCount, "两个断点都要有条件，实际 " + condCount);
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    /** 截出某个 Python 函数的函数体（从 def 到下一个顶层 def）。 */
    private static String extractPythonFunction(String code, String name) {
        int start = code.indexOf("def " + name + "(");
        if (start < 0) {
            return "";
        }
        int next = code.indexOf("\ndef ", start + 1);
        return next < 0 ? code.substring(start) : code.substring(start, next);
    }

    /** 去掉 Python 的行注释（够用即可：本项目生成的脚本文本里没有 """ 里的 #）。 */
    private static String stripPythonComments(String py) {
        StringBuilder sb = new StringBuilder();
        for (String line : py.split("\n", -1)) {
            int hash = line.indexOf('#');
            sb.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
        }
        return sb.toString();
    }

    /**
     * 守卫：断点回调内不得做文件 I/O。
     *
     * <p>真机事故：微信全程卡顿。因为回调运行在被调试进程的线程上，微信会完整暂停；
     * 原实现在回调里 {@code open()+write()+flush()}，等于让微信为磁盘刷盘买单
     * （每次命中都一次）。改为内存 set 累积、结束时一次性落盘。
     */
    @Test
    @DisplayName("断点回调内不做文件 I/O（否则微信为每次命中付磁盘代价）")
    void grabCallbackDoesNoFileIo() {
        String py = new MacOsKeyExtractor(30).buildPythonCaptureScript();
        int grabStart = py.indexOf("def _grab");
        int grabEnd = py.indexOf("def _cb_crypt");
        assertTrue(grabStart > 0 && grabEnd > grabStart, "应能定位 _grab 函数体");

        String grabBody = py.substring(grabStart, grabEnd);
        assertFalse(grabBody.contains("open("),
                "_grab 里不得 open() 文件 —— 回调在微信线程上运行，I/O 会拖垮它");
        assertFalse(grabBody.contains("flush("),
                "_grab 里不得 flush() —— 同上");
    }

    /**
     * 守卫：必须有「抓到足够数量提前收工」的逻辑。
     *
     * <p>用户要求把停顿控制在 20 秒内。仅靠缩短时长不够 ——
     * 若 5 秒就抓齐了密钥，还应该立刻停，而不是继续让微信被断点打断。
     */
    @Test
    @DisplayName("达到目标密钥数即提前结束，不让微信持续停顿")
    void supportsEarlyStop() {
        MacOsKeyExtractor ex = new MacOsKeyExtractor(30);
        String py = ex.buildPythonCaptureScript();

        assertTrue(py.contains("EARLY_STOP"), "应有提前结束的分支");
        assertTrue(py.contains(">= 14"),
                "应按目标数量（14，本机实测 32 库需 14 把）判断提前结束");
        assertTrue(py.contains("TIMER_DONE"), "超时结束应有独立标记，便于区分两种情况");
    }

    @Test
    @DisplayName("默认抓取时长不超过 20 秒（用户明确要求）")
    void defaultDurationWithinTwentySeconds() {
        // 通过反射读常量，防止有人把默认值改回 60/90
        try {
            var f = MacOsKeyExtractor.class.getDeclaredField("DEFAULT_CAPTURE_SECONDS");
            f.setAccessible(true);
            int v = f.getInt(null);
            assertTrue(v <= 20, "默认时长应 <= 20 秒，实际 " + v);
        } catch (ReflectiveOperationException e) {
            fail("读不到 DEFAULT_CAPTURE_SECONDS: " + e.getMessage());
        }

        // 且默认构造函数用的就是这个值
        String py = new MacOsKeyExtractor().buildPythonCaptureScript();
        assertNotNull(py);
    }
}
