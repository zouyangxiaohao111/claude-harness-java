package com.nexusai.wechatlog.key.elevation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 提权交接临时文件：收紧权限、读后即删。 */
class PrivateTempDirTest {

    @Test
    @DisplayName("创建时对目录与文件各收紧一次；读取后文件与目录都被删除")
    void aclAppliedAndDeletedAfterRead() throws IOException {
        Path base = Files.createTempDirectory("nexusai-wxkey-ok-");
        List<String> calls = new ArrayList<>();
        AclRestrictor acl = (path, directory) ->
                calls.add((directory ? "dir:" : "file:") + path.getFileName());

        PrivateTempDir tmp = PrivateTempDir.create(base, acl);
        assertEquals(2, calls.size(), "应分别收紧目录与文件: " + calls);
        assertTrue(calls.get(0).startsWith("dir:"));
        assertTrue(calls.get(1).startsWith("file:"));
        assertTrue(Files.isRegularFile(tmp.outputFile()));

        Files.writeString(tmp.outputFile(), "secret");
        assertEquals("secret", tmp.readText());
        tmp.deleteAllQuietly();
        assertFalse(Files.exists(tmp.outputFile()), "读后必须立即删除");
        assertFalse(Files.exists(tmp.outputFile().getParent()), "临时目录也必须删除");
    }

    @Test
    @DisplayName("收紧权限失败 ⇒ 抛错且不留下临时目录（绝不在未收紧时交接）")
    void aclFailureCleansUpAndThrows() throws IOException {
        Path base = Files.createTempDirectory("nexusai-wxkey-fail-");
        AclRestrictor acl = (path, directory) -> {
            throw new IOException("ACL unsupported");
        };

        assertThrows(IOException.class, () -> PrivateTempDir.create(base, acl));

        try (var s = Files.list(base)) {
            assertEquals(0, s.count(), "失败时不能留下临时目录");
        }
    }
}
