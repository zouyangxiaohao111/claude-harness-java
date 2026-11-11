package com.nexusai.kb.app.workspace;

import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库工作区目录解析 · 三级降级。
 *
 * <p><b>WHY</b>：这条链的每一级都是「要么用别的会话的项目、要么建到用户仓库里」那类静默
 * 错误的防线。判据必须钉到<b>具体级次 + 具体路径</b>，⛔ 只断言「返回了个路径」会放过
 * 「用错级次」这一整类回归。
 */
@DisplayName("[KB工作区] 目录解析三级降级")
class KbWorkspacePathsTest {

    @TempDir
    Path tmp;

    /** 每例都覆写 configHome 到临时目录，防污染真实 ~/.nexusai。 */
    @AfterEach
    void resetConfigHome() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    /** 构造一个假的「打包后安装目录」：{@code <root>/backend/} 内含 {@code jre/}。 */
    private Path fakeInstallDir(String name) throws IOException {
        Path install = tmp.resolve(name);
        Path backend = install.resolve("backend");
        Files.createDirectories(backend.resolve("jre"));
        return install;
    }

    private static Map<String, String> env(String value) {
        return Map.of(KbWorkspacePaths.INSTALL_DIR_ENV, value);
    }

    /** 用 Map 当 env 读取器（⛔ 不改进程环境）。 */
    private static java.util.function.Function<String, String> reader(Map<String, String> map) {
        return map::get;
    }

    @Test
    @DisplayName("第 1 级：环境变量设了且目录建得出来 ⇒ 用它，不碰后两级")
    void envWins() throws IOException {
        Path install = fakeInstallDir("env-install");
        NexusaiPaths.setConfigHomeDirOverride(tmp.resolve("confighome").toString());

        KbWorkspacePaths.Resolved r = KbWorkspacePaths.resolve(
            reader(env(install.toString())), java.util.List.of());

        assertThat(r.tier()).isEqualTo(KbWorkspacePaths.Tier.INSTALL_DIR_ENV);
        assertThat(r.path()).isEqualTo(install.resolve("workspace/kb").toAbsolutePath().normalize());
        assertThat(r.path()).exists().isDirectory();
    }

    @Test
    @DisplayName("第 1 级：环境变量是空串 ⇒ 当未设，落到后级（⛔ 不把空串拼成相对路径）")
    void blankEnvIsTreatedAsUnset() throws IOException {
        Path install = fakeInstallDir("env-blank-install");
        NexusaiPaths.setConfigHomeDirOverride(tmp.resolve("confighome-blank").toString());

        KbWorkspacePaths.Resolved r = KbWorkspacePaths.resolve(
            reader(env("   ")), java.util.List.of(install.resolve("backend").resolve("nexusai-backend.jar")));

        assertThat(r.tier()).isEqualTo(KbWorkspacePaths.Tier.INSTALL_DIR_DETECTED);
        assertThat(r.path()).isEqualTo(install.resolve("workspace/kb").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("第 1 级：环境变量指向不可创建的位置 ⇒ 降级到第 2 级")
    void envUnusableFallsThrough() throws IOException {
        Path install = fakeInstallDir("env-bad-install");
        NexusaiPaths.setConfigHomeDirOverride(tmp.resolve("confighome-fall").toString());
        // 用一个「父路径是文件」的位置：createDirectories 必然失败
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "x");

        KbWorkspacePaths.Resolved r = KbWorkspacePaths.resolve(
            reader(env(blocker.toString())), java.util.List.of(install.resolve("backend").resolve("nexusai-backend.jar")));

        assertThat(r.tier()).isEqualTo(KbWorkspacePaths.Tier.INSTALL_DIR_DETECTED);
        assertThat(r.path()).isEqualTo(install.resolve("workspace/kb").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("第 2 级：classpath 目录名 backend 且同级有 jre/ ⇒ 取 parent 为安装目录")
    void detectsInstallDirFromBackendLayout() throws IOException {
        Path install = fakeInstallDir("detect-install");
        NexusaiPaths.setConfigHomeDirOverride(tmp.resolve("confighome-detect").toString());

        KbWorkspacePaths.Resolved r = KbWorkspacePaths.resolve(
            reader(Map.of()), java.util.List.of(install.resolve("backend").resolve("nexusai-backend.jar")));

        assertThat(r.tier()).isEqualTo(KbWorkspacePaths.Tier.INSTALL_DIR_DETECTED);
        assertThat(r.path()).isEqualTo(install.resolve("workspace/kb").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("第 2 级：目录名不是 backend（dev 的 target/classes）⇒ 不认，落第 3 级")
    void devClassesDirIsNotAnInstallDir() throws IOException {
        Path classes = tmp.resolve("repo/backend/target/classes");
        Files.createDirectories(classes);
        Path configHome = tmp.resolve("confighome-dev");
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());

        KbWorkspacePaths.Resolved r = KbWorkspacePaths.resolve(reader(Map.of()), java.util.List.of(classes));

        assertThat(r.tier()).isEqualTo(KbWorkspacePaths.Tier.CONFIG_HOME);
        // ⭐ 关键：绝不能把工作区建进「看起来像仓库」的父目录链里
        assertThat(r.path()).isEqualTo(configHome.resolve("workspace/kb").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("第 2 级：目录名是 backend 但没有 jre/ ⇒ 不认（防同名目录误判）")
    void backendDirWithoutJreIsRejected() throws IOException {
        Path fake = tmp.resolve("fake-install/backend");
        Files.createDirectories(fake);   // 故意不建 jre/
        Path configHome = tmp.resolve("confighome-nojre");
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());

        KbWorkspacePaths.Resolved r = KbWorkspacePaths.resolve(reader(Map.of()), java.util.List.of(fake.resolve("nexusai-backend.jar")));

        assertThat(KbWorkspacePaths.detectInstallDir(fake)).isNull();
        assertThat(r.tier()).isEqualTo(KbWorkspacePaths.Tier.CONFIG_HOME);
    }

    @Test
    @DisplayName("第 2 级：jarDir 为 null（无 CodeSource）⇒ 不崩，落第 3 级")
    void nullJarDirFallsToConfigHome() {
        Path configHome = tmp.resolve("confighome-null");
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());

        KbWorkspacePaths.Resolved r = KbWorkspacePaths.resolve(reader(Map.of()), java.util.List.of());

        assertThat(r.tier()).isEqualTo(KbWorkspacePaths.Tier.CONFIG_HOME);
        assertThat(r.path()).isEqualTo(configHome.resolve("workspace/kb").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("第 3 级：产出的是 configHome 的子目录，不是 configHome 本身（不撞红线）")
    void configHomeTierIsSubdirectoryNotConfigHomeItself() {
        Path configHome = tmp.resolve("confighome-redline");
        NexusaiPaths.setConfigHomeDirOverride(configHome.toString());

        KbWorkspacePaths.Resolved r = KbWorkspacePaths.resolve(reader(Map.of()), java.util.List.of());

        assertThat(r.path()).isNotEqualTo(configHome.toAbsolutePath().normalize());
        assertThat(r.path().startsWith(configHome.toAbsolutePath().normalize())).isTrue();
    }

    // ── 新增：Fat jar 形态（3499 真机实测的根因）────────────────────────────

    @Test
    @DisplayName("第 2 级：classpath 条目是 jar 文件（fat jar 常态）⇒ 取 jar 所在目录判断布局")
    void jarEntryResolvedByItsDirectory() throws IOException {
        Path install = fakeInstallDir("jar-entry-install");
        // 造出真实存在的 jar 文件（fat jar 启动时 java.class.path 就是它）
        Path jar = install.resolve("backend/nexusai-backend.jar");
        Files.writeString(jar, "x");
        NexusaiPaths.setConfigHomeDirOverride(tmp.resolve("confighome-jar").toString());

        KbWorkspacePaths.Resolved r = KbWorkspacePaths.resolve(reader(Map.of()), java.util.List.of(jar));

        assertThat(r.tier()).isEqualTo(KbWorkspacePaths.Tier.INSTALL_DIR_DETECTED);
        assertThat(r.path()).isEqualTo(install.resolve("workspace/kb").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("codeSourceUrlToPath：三种真机形态都要能转（fat jar 嵌套 URL 是主要目标）")
    void codeSourceUrlForms() {
        // 1) Spring Boot fat jar 下的常态（实测路径：Paths.get(uri) 会抛异常）
        assertThat(KbWorkspacePaths.codeSourceUrlToPath(
            "jar:nested:file:/C:/app/backend/nexusai-backend.jar/!BOOT-INF/classes/!/").toString()
            .replace('\\', '/'))
            .isEqualTo("C:/app/backend/nexusai-backend.jar");
        // 2) 普通可执行 jar
        assertThat(KbWorkspacePaths.codeSourceUrlToPath(
            "jar:file:/C:/app/backend/nexusai-backend.jar!/").toString().replace('\\', '/'))
            .isEqualTo("C:/app/backend/nexusai-backend.jar");
        // 3) dev
        assertThat(KbWorkspacePaths.codeSourceUrlToPath(
            "file:/D:/repo/backend/target/classes/").toString().replace('\\', '/'))
            .isEqualTo("D:/repo/backend/target/classes");
        // 4) 非本地 scheme ⇒ null（⛔ 不能当安装目录）
        assertThat(KbWorkspacePaths.codeSourceUrlToPath("http://example.com/x.jar")).isNull();
        assertThat(KbWorkspacePaths.codeSourceUrlToPath(null)).isNull();
    }

    @Test
    @DisplayName("第 2 级：多个候选时取第一个成立的（classpath 里混着依赖 jar 也不影响）")
    void firstUsableCandidateWins() throws IOException {
        Path install = fakeInstallDir("multi-install");
        Path unrelated = tmp.resolve("libs/commons.jar");
        Files.createDirectories(unrelated.getParent());
        Files.writeString(unrelated, "x");
        Path jar = install.resolve("backend/nexusai-backend.jar");
        Files.writeString(jar, "x");
        NexusaiPaths.setConfigHomeDirOverride(tmp.resolve("confighome-multi").toString());

        KbWorkspacePaths.Resolved r = KbWorkspacePaths.resolve(
            reader(Map.of()), java.util.List.of(unrelated, jar));

        assertThat(r.tier()).isEqualTo(KbWorkspacePaths.Tier.INSTALL_DIR_DETECTED);
        assertThat(r.path()).isEqualTo(install.resolve("workspace/kb").toAbsolutePath().normalize());
    }
}
