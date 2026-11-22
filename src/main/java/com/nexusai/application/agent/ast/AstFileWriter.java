package com.nexusai.application.agent.ast;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 写盘约束与原子替换 · 照 {@code EditFileTool} 的写盘语义（普通文件 + 非符号链接 + 临时文件原子移动）。
 *
 * <p>AstEdit 的写盘对象来自 CLI 报告的文件路径，必须自行校验：符号链接 / 目录 / 设备文件
 * 直接拒绝（防把 blob 写进符号链接指向的任意位置）；临时文件与被替换文件同目录，保证
 * {@code ATOMIC_MOVE} 在同一文件系统内成立。
 */
public final class AstFileWriter {

    private AstFileWriter() {}

    /**
     * 校验目标可写：必须是普通文件且不是符号链接。
     *
     * @param p 目标路径
     * @throws IOException 非普通文件 / 符号链接 / 不存在
     */
    public static void requireRegularFile(Path p) throws IOException {
        if (!Files.exists(p)) {
            throw new IOException("file not found: " + p);
        }
        if (Files.isSymbolicLink(p)) {
            throw new IOException("refusing to edit a symbolic link: " + p);
        }
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a regular file: " + p);
        }
    }

    /**
     * 整文件原子替换（同目录临时文件 → {@code ATOMIC_MOVE}，不支持时降级 {@code REPLACE_EXISTING}）。
     *
     * @param target  目标文件
     * @param content 新内容（原样字节，含 BOM/CRLF 语义）
     * @throws IOException 写入/移动失败（临时文件会被清理）
     */
    public static void writeAtomic(Path target, byte[] content) throws IOException {
        Path abs = target.toAbsolutePath().normalize();
        Path dir = abs.getParent();
        // POSIX 权限原样带过去：createTempFile 是 0600，直接 move 会把 0644 源码 / 0755 脚本降权
        // （macOS/Linux 上丢执行位）。Windows 无 POSIX 视图 → UnsupportedOperation，跳过。
        java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = null;
        try {
            perms = Files.getPosixFilePermissions(abs);
        } catch (UnsupportedOperationException | IOException ignored) {
            // 非 POSIX 文件系统 / 读权限失败 → 保持默认
        }
        Path tmp = Files.createTempFile(dir, ".ast-edit-", ".tmp");
        try {
            try {
                Files.write(tmp, content);
            } catch (IOException e) {
                deleteQuietly(tmp);
                throw e;
            }
            if (perms != null) {
                try {
                    Files.setPosixFilePermissions(tmp, perms);
                } catch (UnsupportedOperationException ignored) {
                    // 非 POSIX 文件系统
                }
            }
            moveWithRetry(tmp, abs);
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw e;
        }
    }

    /**
     * 临时文件 → 目标（优先原子移动）。
     *
     * <p>Windows 上刚写完的临时文件可能被杀软/索引器短暂占用，{@code ATOMIC_MOVE} 会抛
     * {@code AccessDeniedException}（「另一个程序正在使用此文件」）。这类占用是瞬时的，重试几次即可；
     * 仍不行才降级为非原子替换（至少保证内容写对，而不是让用户看到「写盘失败」）。
     */
    private static void moveWithRetry(Path tmp, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (java.nio.file.AccessDeniedException e) {
                last = e;
                sleepQuietly(50L * (attempt + 1));
            }
        }
        deleteQuietly(tmp);
        throw last == null ? new IOException("move failed: " + tmp + " -> " + target) : last;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // 清理失败不掩盖原始错误
        }
    }
}
