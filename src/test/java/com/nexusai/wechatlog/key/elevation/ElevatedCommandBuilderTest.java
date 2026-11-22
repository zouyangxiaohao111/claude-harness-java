package com.nexusai.wechatlog.key.elevation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 提权子进程命令行：生产走 -jar，开发走 -cp，两者都带哨兵。 */
class ElevatedCommandBuilderTest {

    @Test
    @DisplayName("fat jar 形态：-jar + 哨兵，不指定 main class")
    void jarMode() {
        List<String> cmd = ElevatedCommandBuilder.assemble(
                "javaw.exe", "C:\\app\\nexusai-backend.jar", true, 25,
                Path.of("D:\\wx\\acct"), Path.of("C:\\tmp\\out.txt"));
        assertEquals("javaw.exe", cmd.get(0));
        assertEquals("--enable-native-access=ALL-UNNAMED", cmd.get(1));
        assertEquals("-jar", cmd.get(2));
        assertEquals("C:\\app\\nexusai-backend.jar", cmd.get(3));
        assertEquals(ElevatedKeyExtractProtocol.SENTINEL, cmd.get(4));
        assertEquals("D:\\wx\\acct", cmd.get(5));
        assertEquals("C:\\tmp\\out.txt", cmd.get(6));
        assertFalse(cmd.contains(ElevatedCommandBuilder.MAIN_CLASS));
    }

    @Test
    @DisplayName("classpath 形态：-cp + 入口类 + 哨兵")
    void classpathMode() {
        List<String> cmd = ElevatedCommandBuilder.assemble(
                "java.exe", "target/classes;deps.jar", false, 21,
                Path.of("acct"), Path.of("out.txt"));
        assertEquals("java.exe", cmd.get(0));
        assertEquals("-cp", cmd.get(1));
        assertEquals("target/classes;deps.jar", cmd.get(2));
        assertEquals(ElevatedCommandBuilder.MAIN_CLASS, cmd.get(3));
        assertEquals(ElevatedKeyExtractProtocol.SENTINEL, cmd.get(4));
        assertFalse(cmd.contains("--enable-native-access=ALL-UNNAMED"),
                "JDK 21 不认识该选项，绝不能加");
    }

    @Test
    @DisplayName("单 jar 判定：只有单个 .jar 才算生产形态")
    void singleJarDetection() {
        assertTrue(ElevatedCommandBuilder.isSingleJar("C:\\app\\x.jar"));
        assertFalse(ElevatedCommandBuilder.isSingleJar("target/classes"));
        assertFalse(ElevatedCommandBuilder.isSingleJar("a.jar;b.jar"));
        assertFalse(ElevatedCommandBuilder.isSingleJar(null));
    }
}
