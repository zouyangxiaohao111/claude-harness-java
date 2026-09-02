package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-3 files 解压机制测试（RED→GREEN）· 对齐 CC bundledSkills.ts:131-220 + filesystem.ts:365-370。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图）:
 * <ol>
 *   <li><b>解压机制此前不存在</b>——Bootstrapper.sink() 丢弃 files（旧架构），参考文件永不解压，
 *       prompt 无 base-dir 前缀，模型无法按需 Read/Grep 技能参考文件（对齐目标 B0-6/B0-7 缺口）。
 *       实施前这些断言必红（无 extractBundledSkillFiles/safeWriteFile/resolveSkillFilePath）。</li>
 *   <li><b>安全语义是意图核心</b>——O_EXCL（CREATE_NEW）不覆盖已存在文件（CC bundledSkills.ts:169-175
 *       刻意不 unlink+retry）+ 穿越路径拒绝（:196-206）+ per-process nonce 主防御（filesystem.ts:352-363
 *       SECURITY 注释）。这些是 CC 安全设计，不是实现细节。</li>
 *   <li><b>端到端接线</b>——VerifySkillRegistrar 经 sink 传真实 files → 解压 + baseDir 设置 +
 *       withBaseDirPrefix 前缀（对齐 CC registerBundledSkill :59-72 + prependBaseDir :208-220）。</li>
 * </ol>
 */
class BundledSkillFileExtractorTest {

    private final BundledSkillFileExtractor extractor = new BundledSkillFileExtractor();

    /** 记录用真实 nonce 根解压的 skill 目录，测试后清理（per-process nonce 目录进程隔离）。 */
    private final List<Path> realRootDirsToClean = new ArrayList<>();

    @AfterEach
    void cleanupRealRoot() throws IOException {
        for (Path dir : realRootDirsToClean) {
            if (Files.exists(dir)) {
                deleteRecursively(dir);
            }
        }
        realRootDirsToClean.clear();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    // ── resolveSkillFilePath：穿越拒绝（对齐 CC bundledSkills.ts:196-206）──

    @Test
    @DisplayName("resolveSkillFilePath 拒绝穿越路径（../escape / a/../../escape / 绝对路径）")
    void resolveSkillFilePathRejectsTraversal() throws IOException {
        Path base = Files.createTempDirectory("skill-resolve-");
        try {
            // 穿越：父级逃逸
            assertThatThrownBy(() -> extractor.resolveSkillFilePath(base, "../escape"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes skill dir");
            // 穿越：多级 + 归约后仍含 '..'
            assertThatThrownBy(() -> extractor.resolveSkillFilePath(base, "a/../../escape"))
                .isInstanceOf(IllegalArgumentException.class);
            // 单独 '..'
            assertThatThrownBy(() -> extractor.resolveSkillFilePath(base, ".."))
                .isInstanceOf(IllegalArgumentException.class);
            // 绝对路径（Windows C:\… 与 /… 均视为绝对）
            assertThatThrownBy(() -> extractor.resolveSkillFilePath(base, Paths.get(".").toAbsolutePath() + "/x.md"))
                .isInstanceOf(IllegalArgumentException.class);
        } finally {
            deleteRecursively(base);
        }
    }

    @Test
    @DisplayName("resolveSkillFilePath 放行合法路径；a/../b 归约为 b 落回根内（与 CC Node normalize 一致）")
    void resolveSkillFilePathAllowsSafePaths() throws IOException {
        Path base = Files.createTempDirectory("skill-resolve-");
        try {
            assertThat(extractor.resolveSkillFilePath(base, "refs/guide.md"))
                .isEqualTo(base.resolve("refs").resolve("guide.md"));
            // normalize 折叠 a/../b → b（CC bundledSkills.ts:197 normalize 同，不穿越）
            assertThat(extractor.resolveSkillFilePath(base, "a/../b.md"))
                .isEqualTo(base.resolve("b.md"));
            assertThat(extractor.resolveSkillFilePath(base, "deep/nested/file.txt"))
                .isEqualTo(base.resolve("deep").resolve("nested").resolve("file.txt"));
        } finally {
            deleteRecursively(base);
        }
    }

    // ── safeWriteFile：O_EXCL 不覆盖（对齐 CC bundledSkills.ts:169-193）──

    @Test
    @DisplayName("safeWriteFile 已存在文件抛 FileAlreadyExistsException 不覆盖（CC 刻意不 unlink+retry）")
    void safeWriteFileDoesNotOverwriteExisting() throws IOException {
        Path dir = Files.createTempDirectory("skill-safe-write-");
        try {
            Path f = dir.resolve("exists.txt");
            Files.writeString(f, "original", StandardCharsets.UTF_8);
            assertThatThrownBy(() -> extractor.safeWriteFile(f, "new content"))
                .isInstanceOf(FileAlreadyExistsException.class);
            assertThat(Files.readString(f, StandardCharsets.UTF_8))
                .as("O_EXCL 语义：已存在文件必须保持原内容不被覆盖")
                .isEqualTo("original");
        } finally {
            deleteRecursively(dir);
        }
    }

    // ── writeSkillFiles：嵌套目录 + 内容写入（对齐 CC bundledSkills.ts:147-167）──

    @Test
    @DisplayName("writeSkillFiles 按父目录分组创建嵌套目录并写入内容")
    void writeSkillFilesCreatesNestedDirsAndWrites() throws IOException {
        Path dir = Files.createTempDirectory("skill-write-");
        try {
            extractor.writeSkillFiles(dir, Map.of(
                "refs/guide.md", "# Guide",
                "refs/checklist.md", "- [ ] done",
                "top.txt", "top content"
            ));
            assertThat(Files.readString(dir.resolve("refs").resolve("guide.md"), StandardCharsets.UTF_8))
                .isEqualTo("# Guide");
            assertThat(Files.readString(dir.resolve("refs").resolve("checklist.md"), StandardCharsets.UTF_8))
                .isEqualTo("- [ ] done");
            assertThat(Files.readString(dir.resolve("top.txt"), StandardCharsets.UTF_8))
                .isEqualTo("top content");
        } finally {
            deleteRecursively(dir);
        }
    }

    // ── extractBundledSkillFiles：fail-soft（对齐 CC bundledSkills.ts:131-145）──

    @Test
    @DisplayName("extractBundledSkillFiles 穿越键 → 返回 null 不抛出（fail-soft，skill 继续可用仅无前缀）")
    void extractBundledSkillFilesFailSoftOnTraversal() {
        Path dir = extractor.extractBundledSkillFiles("fail-soft-skill", Map.of("../escape", "evil"));
        assertThat(dir).as("穿越路径解压必须 fail-soft 返回 null，而非抛出阻断注册").isNull();
    }

    @Test
    @DisplayName("extractBundledSkillFiles 合法文件 → 返回确定性目录且文件落盘")
    void extractBundledSkillFilesExtractsToDeterministicDir() throws IOException {
        String skillName = "extract-skill-" + System.nanoTime();
        Path dir = extractor.extractBundledSkillFiles(skillName, Map.of("docs/api.md", "# API"));
        assertThat(dir).isNotNull();
        realRootDirsToClean.add(dir);
        assertThat(dir).isEqualTo(extractor.getBundledSkillExtractDir(skillName));
        assertThat(Files.exists(dir.resolve("docs").resolve("api.md"))).isTrue();
        assertThat(Files.readString(dir.resolve("docs").resolve("api.md"))).isEqualTo("# API");
    }

    // ── getBundledSkillsRoot：per-process nonce（对齐 CC filesystem.ts:365-370）──

    @Test
    @DisplayName("getBundledSkillsRoot 进程内确定性 + nonce 32 hex + 版本/前缀组件")
    void getBundledSkillsRootIsMemoizedPerProcess() {
        Path root1 = extractor.getBundledSkillsRoot();
        Path root2 = extractor.getBundledSkillsRoot();
        assertThat(root1).isEqualTo(root2).as("memoize：进程内多次调用返回同一根目录");
        String nonce = root1.getFileName().toString();
        assertThat(nonce).matches("[0-9a-f]{32}").as("nonce = randomBytes(16).toString('hex') → 32 hex");
        assertThat(root1.getParent().getFileName().toString())
            .isEqualTo("0.2.33").as("版本隔离组件 = MACRO.VERSION（filesystem.ts:368）");
        assertThat(root1.getParent().getParent().getFileName().toString())
            .isEqualTo("bundled-skills");
        // getBundledSkillExtractDir = join(root, skillName)（bundledSkills.ts:120-122）
        assertThat(extractor.getBundledSkillExtractDir("verify"))
            .isEqualTo(root1.resolve("verify"));
    }

    // ── withBaseDirPrefix：前缀复用（对齐 CC prependBaseDir bundledSkills.ts:208-220）──

    @Test
    @DisplayName("withBaseDirPrefix 有 baseDir 加前缀、无 baseDir 原样返回")
    void withBaseDirPrefixAddsOrOmitsPrefix() {
        SkillContentLoader loader = new SkillContentLoader();
        Command withDir = new Command();
        withDir.setBaseDir("C:/skills/verify");
        assertThat(loader.withBaseDirPrefix(withDir, "body"))
            .isEqualTo("Base directory for this skill: C:/skills/verify\n\nbody");
        Command withoutDir = new Command();
        assertThat(loader.withBaseDirPrefix(withoutDir, "body")).isEqualTo("body");
    }

    // ── VerifySkillRegistrar 经统一 Consumer 传真实 files → 解压 + baseDir 设置（对齐 CC :59-72）──

    @Test
    @DisplayName("VerifySkillRegistrar 经统一 Consumer 传真实 files → 解压 + baseDir + 前缀（端到端接线）")
    void verifySkillRegistrarExtractsFilesAndSetsBaseDir() throws IOException {
        BundledSkillFileExtractor localExtractor = new BundledSkillFileExtractor();
        List<Command> captured = new ArrayList<>();
        Consumer<BundledSkillDefinition> registrar = def -> {
            Command cmd = def.toCommand();
            if (def.files() != null && !def.files().isEmpty()) {
                Path dir = localExtractor.extractBundledSkillFiles(def.name(), def.files());
                if (dir != null) {
                    cmd.setBaseDir(dir.toString());
                    realRootDirsToClean.add(dir);
                }
            }
            captured.add(cmd);
        };

        boolean registered = new VerifySkillRegistrar().register(
            registrar, () -> true, "Verify description", "# Verify body",
            Map.of("refs/checklist.md", "# Checklist\n- [ ] run"));

        assertThat(registered).isTrue();
        assertThat(captured).hasSize(1);
        Command verify = captured.get(0);
        assertThat(verify.getBaseDir())
            .as("files 非空注册必须解压并 setBaseDir（对齐 CC skillRoot bundledSkills.ts:91）")
            .isNotNull();
        Path checklist = Paths.get(verify.getBaseDir(), "refs", "checklist.md");
        assertThat(Files.exists(checklist)).isTrue();
        assertThat(Files.readString(checklist, StandardCharsets.UTF_8)).isEqualTo("# Checklist\n- [ ] run");
        // 前缀断言（对齐 CC prependBaseDir bundledSkills.ts:208-220）
        String prefixed = new SkillContentLoader().withBaseDirPrefix(verify, "# Verify body");
        assertThat(prefixed)
            .startsWith("Base directory for this skill: " + verify.getBaseDir() + "\n\n")
            .endsWith("# Verify body");
    }

    @Test
    @DisplayName("Consumer 收到空 files 不触发解压（现有注册零影响）")
    void emptyFilesSkipsExtraction() {
        BundledSkillFileExtractor localExtractor = new BundledSkillFileExtractor();
        List<Command> captured = new ArrayList<>();
        Consumer<BundledSkillDefinition> registrar = def -> {
            Command cmd = def.toCommand();
            if (def.files() != null && !def.files().isEmpty()) {
                Path dir = localExtractor.extractBundledSkillFiles(def.name(), def.files());
                if (dir != null) {
                    cmd.setBaseDir(dir.toString());
                }
            }
            captured.add(cmd);
        };

        new VerifySkillRegistrar().register(
            registrar, () -> true, "desc", "body", Map.of());

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getBaseDir()).as("files 空 → 不设 baseDir，注册零影响").isNull();
    }

    // ── lazyExtract：惰性解压 + memoize（对齐 CC extractionPromise bundledSkills.ts:64-72）──

    @Test
    @DisplayName("lazyExtract 首调前不解压、首调解压、二次 get() 复用同一结果（CC extractionPromise memoize）")
    void lazyExtractDefersUntilFirstGetAndMemoizes() throws IOException {
        String skillName = "lazy-skill-" + System.nanoTime();
        Map<String, String> files = Map.of("docs/api.md", "# API");
        Supplier<Path> lazy = extractor.lazyExtract(skillName, files);
        Path dir = extractor.getBundledSkillExtractDir(skillName);
        // 惰性：首调 get() 前未解压（目录不存在）——对齐 CC :60 注册期仅定值 skillRoot 不写盘
        assertThat(Files.exists(dir)).as("惰性解压：首调 get() 前目录必须不存在").isFalse();
        // 首调解压
        Path extracted = lazy.get();
        assertThat(extracted).isNotNull();
        realRootDirsToClean.add(extracted);
        assertThat(Files.readString(dir.resolve("docs").resolve("api.md"), StandardCharsets.UTF_8))
            .isEqualTo("# API");
        // memoize：二次 get() 复用同一结果
        assertThat(lazy.get()).isEqualTo(extracted).as("memoize：二次 get() 复用同一解压目录");
    }

    @Test
    @DisplayName("lazyExtract 解压失败 fail-soft 返回 null 且 memoize 不重试")
    void lazyExtractMemoizesFailSoftNull() {
        // 穿越键 → extractBundledSkillFiles 返回 null；memoize 缓存 null（不重试）
        Supplier<Path> lazy = extractor.lazyExtract(
            "lazy-fail-" + System.nanoTime(), Map.of("../escape", "evil"));
        assertThat(lazy.get()).isNull();
        assertThat(lazy.get()).isNull();
    }

    // ── safeWriteFile：O_NOFOLLOW 等价（对齐 CC bundledSkills.ts:176/:184）──

    @Test
    @DisplayName("safeWriteFile 拒绝符号链接目标（CC O_NOFOLLOW）——不写穿符号链接污染真实文件")
    void safeWriteFileDoesNotFollowSymlink() throws IOException {
        Path dir = Files.createTempDirectory("skill-symlink-");
        try {
            Path real = dir.resolve("real.txt");
            Files.writeString(real, "original", StandardCharsets.UTF_8);
            Path link = dir.resolve("link.txt");
            try {
                Files.createSymbolicLink(link, real);
            } catch (UnsupportedOperationException | IOException | SecurityException e) {
                // 平台不支持符号链接（Windows 非开发者模式）→ 跳过（CC Windows 'wx' 分支无 O_NOFOLLOW）
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "符号链接在此平台不可用，跳过 O_NOFOLLOW 测试");
                return;
            }
            // 关键安全性质：safeWriteFile 必须拒绝写入符号链接，且 real 文件内容不被污染
            assertThatThrownBy(() -> extractor.safeWriteFile(link, "evil"))
                .isInstanceOf(IOException.class);
            assertThat(Files.readString(real, StandardCharsets.UTF_8))
                .as("O_NOFOLLOW：不得写穿符号链接污染 real 文件")
                .isEqualTo("original");
        } finally {
            deleteRecursively(dir);
        }
    }
}
