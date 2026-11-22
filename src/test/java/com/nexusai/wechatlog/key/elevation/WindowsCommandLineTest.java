package com.nexusai.wechatlog.key.elevation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Windows 参数转义（ShellExecuteEx 的 lpParameters 是一整条字符串）。 */
class WindowsCommandLineTest {

    @Test
    @DisplayName("无空格原样；有空格加引号；引号与末尾反斜杠按 MSVC 规则转义")
    void quotesPerWindowsRules() {
        assertEquals("abc", WindowsCommandLine.quote("abc"));
        assertEquals("\"a b\"", WindowsCommandLine.quote("a b"));
        assertEquals("\"\"", WindowsCommandLine.quote(""));
        assertEquals("\"a\\\"b\"", WindowsCommandLine.quote("a\"b"));
        assertEquals("\"C:\\Program Files\\x\"",
                WindowsCommandLine.quote("C:\\Program Files\\x"));
    }

    @Test
    @DisplayName("join 只在参数之间加空格")
    void joinsWithSpaces() {
        assertEquals("java.exe -jar \"a b.jar\"",
                WindowsCommandLine.join(List.of("java.exe", "-jar", "a b.jar")));
        assertEquals("", WindowsCommandLine.join(List.of()));
    }
}
