package com.nexusai.wechatlog.key.elevation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 一次提权交接用的私有临时目录。
 *
 * <h2>生命周期</h2>
 * <ol>
 *   <li>在用户私有根目录下建一个随机子目录与候选文件；</li>
 *   <li>创建后<b>立刻</b>收紧 ACL（只允许当前用户）；收紧失败就整体删除并抛错；</li>
 *   <li>提权子进程把候选写进该文件；</li>
 *   <li>父进程读取后<b>立即</b>删除文件与目录（无论成功失败）。</li>
 * </ol>
 *
 * <p>本类不打印文件内容 —— 里面是密钥。
 */
public final class PrivateTempDir {

    private final Path dir;
    private final Path file;

    private PrivateTempDir(Path dir, Path file) {
        this.dir = dir;
        this.file = file;
    }

    public static PrivateTempDir create(Path baseDir, AclRestrictor acl) throws IOException {
        if (baseDir == null) {
            throw new IOException("未指定临时目录根");
        }
        Files.createDirectories(baseDir);
        Path dir = Files.createTempDirectory(baseDir, "wxkey-");
        Path file = dir.resolve("candidates.txt");
        try {
            Files.createFile(file);
            // 顺序：先收紧目录（子项继承），再收紧已建好的文件。
            acl.restrict(dir, true);
            acl.restrict(file, false);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(file);
            deleteQuietly(dir);
            throw e instanceof IOException io ? io : new IOException(e);
        }
        return new PrivateTempDir(dir, file);
    }

    public Path outputFile() {
        return file;
    }

    /** 读候选文本；文件不存在返回空串。 */
    public String readText() throws IOException {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** 删除目录内所有文件与目录本身（幂等、失败不抛）。 */
    public void deleteAllQuietly() {
        deleteQuietly(file);
        // 子进程可能还写了 .reason 旁车文件；目录非空时 delete(dir) 会失败并留下目录
        try (var entries = Files.list(dir)) {
            for (Path p : entries.toList()) {
                deleteQuietly(p);
            }
        } catch (IOException ignored) {
            // 目录不存在或不可列：继续尝试删目录
        }
        deleteQuietly(dir);
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 临时文件删不掉不阻断主流程；下一次会新建随机目录
        }
    }
}
