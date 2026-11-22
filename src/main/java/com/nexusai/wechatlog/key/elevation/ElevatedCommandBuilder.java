package com.nexusai.wechatlog.key.elevation;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 构造提权子进程的命令行。
 *
 * <h2>两种启动形态</h2>
 * <ul>
 *   <li><b>生产</b>：后端由 Tauri 以 {@code javaw -jar nexusai-backend.jar} 启动，
 *       {@code java.class.path} 只有 jar 本身 ⇒ 子进程同样走 {@code -jar}，
 *       靠 {@link ElevatedKeyExtractProtocol#SENTINEL} 让 {@code NexusAiApplication.main}
 *       转入取密钥分支。</li>
 *   <li><b>开发</b>：IDE 以 classpath 启动 ⇒ 子进程复用同一串 classpath，
 *       入口仍是 {@code com.nexusai.NexusAiApplication}（同样靠哨兵分流）。</li>
 * </ul>
 *
 * <p>这样两种形态共用同一条入口，不需要单独维护一个小工具 jar。
 */
@FunctionalInterface
public interface ElevatedCommandBuilder {

    /** 提权子进程的入口类（classpath 形态使用）。 */
    String MAIN_CLASS = "com.nexusai.NexusAiApplication";

    /** JDK 24+ 才认识 {@code --enable-native-access}（JDK 21 会直接启动失败）。 */
    int NATIVE_ACCESS_MIN_FEATURE = 24;

    /** 用当前 JVM 的 java 可执行文件与 classpath 构造命令。 */
    static ElevatedCommandBuilder forCurrentJvm() {
        String javaExe = currentJavaExecutable();
        String classPath = System.getProperty("java.class.path", "");
        boolean jarMode = isSingleJar(classPath);
        int feature = Runtime.version().feature();
        return (accountDir, outputFile)
                -> assemble(javaExe, classPath, jarMode, feature, accountDir, outputFile);
    }

    /**
     * 纯函数：按显式给定的元素拼装命令行（便于单测）。
     *
     * @param javaExe   java/javaw 可执行文件
     * @param classPath 当前进程的 classpath（jar 形态下即 jar 路径）
     * @param jarMode   true ⇒ {@code -jar}；false ⇒ {@code -cp}
     * @param feature   JVM major 版本
     */
    static List<String> assemble(String javaExe, String classPath, boolean jarMode,
                                 int feature, Path accountDir, Path outputFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        if (feature >= NATIVE_ACCESS_MIN_FEATURE) {
            cmd.add("--enable-native-access=ALL-UNNAMED");
        }
        if (jarMode) {
            cmd.add("-jar");
            cmd.add(classPath.trim());
        } else {
            cmd.add("-cp");
            cmd.add(classPath);
            cmd.add(MAIN_CLASS);
        }
        cmd.add(ElevatedKeyExtractProtocol.SENTINEL);
        cmd.add(accountDir == null ? "" : accountDir.toString());
        cmd.add(outputFile == null ? "" : outputFile.toString());
        return cmd;
    }

    /**
     * classpath 是否只有单个 jar（生产 fat jar 的形态）。
     *
     * <p>单个目录（开发时的 {@code target/classes}）不算 —— 那种情况要用 {@code -cp}。
     */
    static boolean isSingleJar(String classPath) {
        if (classPath == null) {
            return false;
        }
        String t = classPath.trim();
        if (t.isEmpty() || t.contains(File.pathSeparator)) {
            return false;
        }
        return t.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    /** 当前 JVM 的可执行文件；取不到时回落 {@code java.home/bin}。 */
    static String currentJavaExecutable() {
        try {
            Optional<String> command = ProcessHandle.current().info().command();
            if (command.isPresent() && !command.get().isBlank()
                    && Files.isRegularFile(Path.of(command.get()))) {
                return command.get();
            }
        } catch (Throwable ignored) {
            // 平台不支持 / 权限不足 → 回落
        }
        Path home = Path.of(System.getProperty("java.home", ""));
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
        Path preferred = home.resolve("bin").resolve(windows ? "javaw.exe" : "java");
        if (Files.isRegularFile(preferred)) {
            return preferred.toString();
        }
        return home.resolve("bin").resolve(windows ? "java.exe" : "java").toString();
    }

    /**
     * 构造命令行。
     *
     * @param accountDir 微信账号目录（子进程当前不使用，但保留在协议里）
     * @param outputFile 子进程写入候选的临时文件
     */
    List<String> build(Path accountDir, Path outputFile);
}
