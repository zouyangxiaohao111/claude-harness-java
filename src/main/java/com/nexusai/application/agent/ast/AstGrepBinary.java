package com.nexusai.application.agent.ast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;

/**
 * 随包 ast-grep CLI 二进制的定位与落盘 · 与 {@code RipgrepRunner.ensureRgBinary} 同构。
 *
 * <h2>为什么是「随包二进制 + 解压到 tmp」</h2>
 * <ol>
 *   <li>ast-grep 没有 Java 绑定（Maven 上的 {@code io.github.bonede:tree-sitter-*} 不含 ast-grep
 *       核心匹配器），结构化搜索/改写只能调真实 CLI；本仓 GrepTool 已有「用真实 rg 二进制替代
 *       Java 近似实现」的既定取舍。</li>
 *   <li>Spring Boot fat-jar 内的资源不可直接执行 ⇒ 首次调用解压到
 *       {@code ${java.io.tmpdir}/nexusai-ast-grep/<VERSION>/<平台>/ast-grep[.exe]} 再执行。</li>
 *   <li>资源以 gzip 入库（单平台 55MB → 8.5MB，六平台 ≈51MB；裸二进制 333MB 会拖垮 SVN 工作副本），
 *       由 inbound gzip 解压，不引入额外构建步骤。</li>
 * </ol>
 *
 * <h2>资源缺失时的行为</h2>
 * {@link #available()} 按「classpath 上是否存在本平台 gz」判定，false ⇒ AstGrep/AstEdit 的
 * {@code isEnabled()} 为 false，注册表不向模型暴露这两个工具，调用侧给出明确错误文案（不崩）。
 * 资源由 {@code backend/scripts/fetch-ast-grep.mjs} 生成（版本与 {@link #VERSION} 绑定）。
 */
public final class AstGrepBinary {

    private static final Logger log = LoggerFactory.getLogger(AstGrepBinary.class);

    /**
     * ast-grep CLI 版本 · 与 {@code scripts/fetch-ast-grep.mjs} 的 VERSION 常量、
     * tmp 缓存目录层级一一绑定（升级 = 改三处 + 重新生成资源）。
     */
    public static final String VERSION = "0.51.3";

    /** 解压后的二进制最小合法尺寸（防半写/损坏缓存；实测 55,559,680 B）。 */
    private static final long MIN_BINARY_BYTES = 20_000_000L;

    /** 进程内 memoize 的可执行路径（double-checked）。 */
    private static volatile Path cachedBinary;

    /** classpath 资源存在性 memoize（null = 未判定）。 */
    private static volatile Boolean available;

    private AstGrepBinary() {}

    /** 本平台是否带二进制（false ⇒ 两个工具 not-enabled；不抛异常）。 */
    public static boolean available() {
        Boolean v = available;
        if (v != null) {
            return v;
        }
        boolean exists;
        try (InputStream in = AstGrepBinary.class.getResourceAsStream(PlatformKey.gzResource())) {
            exists = in != null;
        } catch (IOException e) {
            exists = false;
        }
        available = exists;
        if (!exists) {
            log.warn("AstGrepBinary: classpath 缺少 {}（平台 {}），AstGrep/AstEdit 将不可用；"
                    + "先在 backend/ 下执行 node scripts/fetch-ast-grep.mjs {}",
                PlatformKey.gzResource(), PlatformKey.dirName(), PlatformKey.dirName());
        }
        return exists;
    }

    /**
     * 解压并返回可执行文件路径（首次调用解压，之后命中缓存）。
     *
     * @return 可执行路径
     * @throws IOException 资源缺失 / 解压失败 / 落盘失败（调用方转成工具错误文案）
     */
    public static Path ensureBinary() throws IOException {
        Path p = cachedBinary;
        if (p != null && isValidCache(p)) {
            return p;
        }
        synchronized (AstGrepBinary.class) {
            if (cachedBinary != null && isValidCache(cachedBinary)) {
                return cachedBinary;
            }
            cachedBinary = extract();
            return cachedBinary;
        }
    }

    private static boolean isValidCache(Path p) {
        try {
            return Files.isRegularFile(p) && Files.size(p) > MIN_BINARY_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path extract() throws IOException {
        String resource = PlatformKey.gzResource();
        Path cacheDir = Path.of(System.getProperty("java.io.tmpdir", "/tmp"))
            .resolve("nexusai-ast-grep").resolve(VERSION).resolve(PlatformKey.dirName());
        Path bin = cacheDir.resolve(PlatformKey.exeName());

        if (isValidCache(bin)) {
            return bin;
        }
        try (InputStream raw = AstGrepBinary.class.getResourceAsStream(resource)) {
            if (raw == null) {
                throw new IOException("ast-grep binary not bundled for " + PlatformKey.dirName()
                    + "（先执行 node scripts/fetch-ast-grep.mjs " + PlatformKey.dirName() + "）");
            }
            Files.createDirectories(cacheDir);
            Path tmp = cacheDir.resolve(PlatformKey.exeName() + ".tmp" + System.nanoTime());
            try (GZIPInputStream in = new GZIPInputStream(raw)) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                deleteQuietly(tmp);
                throw e;
            }
            long size = Files.size(tmp);
            if (size <= MIN_BINARY_BYTES) {
                deleteQuietly(tmp);
                throw new IOException("ast-grep 解压结果过小（" + size + " B），疑似资源损坏: " + resource);
            }
            makeExecutable(tmp);
            try {
                Files.move(tmp, bin, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, bin, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("AstGrepBinary: ast-grep {} 就绪 → {}（来源 classpath {}）", VERSION, bin, resource);
        }
        return bin;
    }

    private static void deleteQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // 清理失败不掩盖原始错误
        }
    }

    /** POSIX 置可执行位（Windows no-op）。 */
    private static void makeExecutable(Path p) {
        try {
            if (!p.toFile().setExecutable(true, false) && !PlatformKey.isWindows()) {
                log.warn("AstGrepBinary: 设置可执行位失败（{}）", p);
            }
        } catch (SecurityException e) {
            log.warn("AstGrepBinary: 设置可执行位被安全策略拒绝（{}）", p, e);
        }
    }
}
