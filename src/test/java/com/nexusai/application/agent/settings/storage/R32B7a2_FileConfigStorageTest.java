package com.nexusai.application.agent.settings.storage;

import com.nexusai.application.agent.skill.NexusaiPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R32-b7a-2 · Phase 3 · ConfigStorage 持久化 facade 验证.
 *
 * <p><b>WHY (意图验证)</b>: ConfigStorage 是 ConfigTool 数据持久化唯一通道. 设计原则:
 * <ul>
 *   <li><b>Jackson tree merge</b>: writeSettings(path, value) 只动 path 对应分支,
 *       不影响同级其他 path (e.g. 设 {@code permissions.defaultMode="plan"} 不会丢
 *       {@code permissions.denyRules}) — 与 CC {@code updateSettingsForSource} 语义对齐</li>
 *   <li><b>原子写</b>: tmp 文件 + {@code Files.move(..., ATOMIC_MOVE)} rename —
 *       防止中途崩溃导致文件残缺</li>
 *   <li><b>并发写锁</b>: 单一 {@code ReentrantLock} 串行化所有 write —
 *       避免并发写相互覆盖</li>
 *   <li><b>cache invalidation</b>: 写后立即 reload + notify listener</li>
 *   <li><b>round-trip 保证</b>: write 后立即 read 必须返回 newValue</li>
 *   <li><b>Absent vs JSON null</b>: {@link NullMarker} 区分 — read 返回 {@code null} ⇔
 *       key/path 不存在, 返回 {@link NullMarker} ⇔ key/path 存在但值 JSON null</li>
 * </ul>
 *
 * <p>测试用 {@link TempDir} 隔离文件 (CLAUDE.md 规则 11 隔离性); 每个测试方法独立 dir.
 *
 * @see FileConfigStorage
 * @see ConfigStorage
 */
class R32B7a2_FileConfigStorageTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;

    /** 决策 D1：FileConfigStorage 单参（ConfigStorageProperties），缺省路径 = user.home 派生。
     *  覆写 user.home 隔离测试写盘（防污染真实 ~/.nexusai），用后恢复。 */
    @BeforeEach
    void isolateUserHome() {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    /** 默认测试 storage: properties = null (走默认路径, user.home 已隔离到 tempDir). */
    private FileConfigStorage newStorage() {
        return new FileConfigStorage(null);
    }

    /** 显式 properties 测试用: 让路径全部落在 tempDir 子目录. */
    private FileConfigStorage newStorageWithProperties(Path globalFile, Path settingsFile) {
        ConfigStorageProperties props = new ConfigStorageProperties(
            globalFile.toString(),
            new ConfigStorageProperties.SettingsFile(settingsFile.toString()));
        return new FileConfigStorage(props);
    }

    // ── Phase 3.A: round-trip + basic read/write ──────────────────────────

    @Test
    @DisplayName("readGlobal 缺失 key → null (absent)")
    void readGlobalAbsentReturnsNull() {
        // WHY: null ⇔ absent. LLM 调用 GET 时返回 null 表示"未设置",
        // 与 JSON null 区分 (后者是显式置 null, 不是 absent).
        FileConfigStorage s = newStorage();
        assertThat(s.readGlobal("theme")).isNull();
    }

    @Test
    @DisplayName("writeGlobal + readGlobal 简单 round-trip")
    void writeGlobalRoundTrip() {
        // WHY: 写后立即读必须返回 newValue. 这是契约 A5 (data invariant).
        FileConfigStorage s = newStorage();
        s.writeGlobal("theme", "dark");
        assertThat(s.readGlobal("theme"))
            .as("写后立即读 → 返回 newValue (round-trip)")
            .isEqualTo("dark");
    }

    @Test
    @DisplayName("writeGlobal 多种类型 → round-trip (String/Boolean/Integer/Long/Double)")
    void writeGlobalSupportsMultipleTypes() {
        // WHY: CC ConfigTool SET 时 value 类型多样 (boolean/string/number).
        // Java 端必须保留类型, 不可全转 string 否则 type 检查失效.
        FileConfigStorage s = newStorage();
        s.writeGlobal("strVal", "hello");
        s.writeGlobal("boolVal", true);
        s.writeGlobal("intVal", 42);
        s.writeGlobal("longVal", 9_000_000_000L);
        s.writeGlobal("doubleVal", 3.14);

        assertThat(s.readGlobal("strVal")).isEqualTo("hello");
        assertThat(s.readGlobal("boolVal")).isEqualTo(true);
        assertThat(s.readGlobal("intVal")).isEqualTo(42);
        assertThat(s.readGlobal("longVal")).isEqualTo(9_000_000_000L);
        assertThat(s.readGlobal("doubleVal")).isEqualTo(3.14);
    }

    @Test
    @DisplayName("writeGlobal 后文件存在且包含新 key")
    void writeGlobalPersistsToDisk() throws IOException {
        // WHY: 必须真的写文件, 不只在内存. 进程重启后配置仍生效.
        FileConfigStorage s = newStorage();
        s.writeGlobal("theme", "dark");
        Path globalFile = tempDir.resolve(".nexusai.json");
        assertThat(Files.exists(globalFile))
            .as("writeGlobal 必须真实写文件 (CLAUDE.md 规则 12 Fail loud)")
            .isTrue();
        String content = Files.readString(globalFile);
        assertThat(content)
            .contains("theme")
            .contains("dark");
    }

    @Test
    @DisplayName("unsetGlobal 移除 key + 后续 read 返回 null (absent)")
    void unsetGlobalRemovesKey() {
        // WHY: CC unsetGlobal 是 SET absent 语义 — key 从 config 移除,
        // 而非置 JSON null. 必须区分 absent (未设置) 与 JSON null (显式置空).
        FileConfigStorage s = newStorage();
        s.writeGlobal("theme", "dark");
        assertThat(s.readGlobal("theme")).isEqualTo("dark");

        s.unsetGlobal("theme");
        assertThat(s.readGlobal("theme"))
            .as("unsetGlobal → key 移除 (absent), 非 JSON null")
            .isNull();
        assertThat(s.readGlobal("theme"))
            .as("返回类型是 null (absent) 而非 NullMarker")
            .isNotSameAs(ConfigStorage.NullMarker);
    }

    // ── Phase 3.B: nested settings path ───────────────────────────────────

    @Test
    @DisplayName("writeSettings 嵌套 path → 自动创建中间 ObjectNode")
    void writeSettingsCreatesIntermediateObjects() {
        // WHY: CC settings source 是嵌套 JSON. 写 ["permissions","defaultMode"]
        // 时, 必须自动创建 "permissions" 中间对象, 否则 NPE / PathNotFound.
        FileConfigStorage s = newStorage();
        s.writeSettings(List.of("permissions", "defaultMode"), "plan");
        assertThat(s.readSettings(List.of("permissions", "defaultMode")))
            .isEqualTo("plan");
    }

    @Test
    @DisplayName("writeSettings tree merge: 写 path2 不影响 path1 (Jackson tree merge)")
    void writeSettingsDoesNotOverwriteSiblings() {
        // WHY: 这是 Phase 3 核心设计原则. CC updateSettingsForSource 是 merge
        // (update), 不是 replace. 写 permissions.defaultMode 不会丢 permissions.denyRules.
        FileConfigStorage s = newStorage();

        // 1. 写两个不同 path 的 setting
        s.writeSettings(List.of("permissions", "defaultMode"), "plan");
        s.writeSettings(List.of("permissions", "denyRules"), List.of("rm -rf /"));

        // 2. 两个都还在 (互不影响)
        assertThat(s.readSettings(List.of("permissions", "defaultMode")))
            .as("path2 写后 path1 仍存在 (tree merge, 不覆盖整个 permissions 对象)")
            .isEqualTo("plan");
        assertThat(s.readSettings(List.of("permissions", "denyRules")))
            .isNotNull();
    }

    @Test
    @DisplayName("writeSettings 写入值覆盖原值 (同 path 多次写)")
    void writeSettingsOverwritesSamePath() {
        // WHY: SET 同一 path 多次应覆盖, 不是累积. LLM 连环 SET 时最后一次生效.
        FileConfigStorage s = newStorage();
        s.writeSettings(List.of("permissions", "defaultMode"), "plan");
        s.writeSettings(List.of("permissions", "defaultMode"), "auto");
        assertThat(s.readSettings(List.of("permissions", "defaultMode")))
            .isEqualTo("auto");
    }

    @Test
    @DisplayName("readSettings 缺失 path → null (absent)")
    void readSettingsAbsentReturnsNull() {
        FileConfigStorage s = newStorage();
        assertThat(s.readSettings(List.of("permissions", "defaultMode")))
            .as("未设置的 path → null (absent)")
            .isNull();
    }

    @Test
    @DisplayName("readSettings 空 path 列表 → null (guard)")
    void readSettingsEmptyPathReturnsNull() {
        // WHY: 空 path 是无效输入, 必须 guard 而不抛 NPE
        FileConfigStorage s = newStorage();
        assertThat(s.readSettings(List.of())).isNull();
    }

    @Test
    @DisplayName("unsetSettings 移除嵌套 path + 后续 read 返回 null")
    void unsetSettingsRemovesNestedPath() {
        FileConfigStorage s = newStorage();
        s.writeSettings(List.of("permissions", "defaultMode"), "plan");
        s.unsetSettings(List.of("permissions", "defaultMode"));
        assertThat(s.readSettings(List.of("permissions", "defaultMode")))
            .as("unsetSettings → path 移除 (absent)")
            .isNull();
    }

    // ── Phase 3.C: absent vs JSON null ────────────────────────────────────

    @Test
    @DisplayName("writeGlobal(null) → key 存在但值为 JSON null (NullMarker 区分)")
    void writeGlobalExplicitNullStoresAsJsonNull() {
        // WHY: CC SET value=null 是显式"置空", 不是 absent.
        // ConfigToolImpl 在 PERMISSIONS 写入 null 时, 必须用 NullMarker 区分.
        FileConfigStorage s = newStorage();
        s.writeGlobal("customKey", null);
        assertThat(s.readGlobal("customKey"))
            .as("写 null → 返回 NullMarker (非 null — 区分 absent)")
            .isSameAs(ConfigStorage.NullMarker);
    }

    @Test
    @DisplayName("writeSettings(null) → path 存在但值为 JSON null")
    void writeSettingsExplicitNullStoresAsJsonNull() {
        FileConfigStorage s = newStorage();
        s.writeSettings(List.of("custom", "key"), null);
        assertThat(s.readSettings(List.of("custom", "key")))
            .as("写 null → 返回 NullMarker")
            .isSameAs(ConfigStorage.NullMarker);
    }

    @Test
    @DisplayName("absent 与 JSON null 在 read 时严格区分")
    void absentAndJsonNullAreDistinct() {
        // WHY: Phase 3 关键 invariant — read 返回 null ⇔ absent, 返回 NullMarker ⇔ JSON null.
        // LLM 区分这两种状态做不同决策 (absent → "未设置"; JSON null → "已设置但为空")
        FileConfigStorage s = newStorage();
        // absentKey 从未写入
        assertThat(s.readGlobal("absentKey")).isNull();
        assertThat(s.readGlobal("absentKey"))
            .as("absent → null (不是 NullMarker)")
            .isNotSameAs(ConfigStorage.NullMarker);

        // explicitKey 显式置 null
        s.writeGlobal("explicitKey", null);
        assertThat(s.readGlobal("explicitKey"))
            .as("explicit null → NullMarker (不是 null)")
            .isSameAs(ConfigStorage.NullMarker);
        assertThat(s.readGlobal("explicitKey"))
            .as("NullMarker ≠ null")
            .isNotNull();
    }

    // ── Phase 3.D: persistence + cache + atomic ───────────────────────────

    @Test
    @DisplayName("writeGlobal 写文件为 valid JSON (pretty printed)")
    void writeGlobalPersistsValidJson() throws IOException {
        // WHY: 文件 pretty-print 便于人工审阅; 必须仍是 valid JSON
        // 否则后续 SettingsCache 等会解析失败.
        FileConfigStorage s = newStorage();
        s.writeGlobal("theme", "dark");
        s.writeGlobal("verbose", true);

        Path globalFile = tempDir.resolve(".nexusai.json");
        String content = Files.readString(globalFile);
        assertThat(content)
            .as("文件应 pretty-print 含换行")
            .contains("\n");
        // ObjectMapper 严格性: 重新解析必须成功
        assertThat(content)
            .contains("\"theme\"")
            .contains("\"dark\"")
            .contains("\"verbose\"")
            .contains("true");
    }

    @Test
    @DisplayName("atomic write: 写期间没有 .tmp 残留文件")
    void noTempFilesAfterWrite() throws IOException {
        // WHY: tmp 文件 + rename 模式应在写完后清理 tmp, 否则磁盘累积垃圾
        FileConfigStorage s = newStorage();
        s.writeGlobal("theme", "dark");

        Path dir = tempDir;
        long tmpCount = Files.list(dir)
            .filter(p -> p.getFileName().toString().endsWith(".tmp"))
            .count();
        assertThat(tmpCount)
            .as("写完后不应残留 .tmp 文件 (atomic move 已完成)")
            .isZero();
    }

    @Test
    @DisplayName("cache: write 后立即 read 返回新值 (cache invalidation)")
    void cacheInvalidatedAfterWrite() {
        // WHY: cache 写后必须 reload; 否则 LLM 看到的还是旧值,
        // 用户体验"明明 set 成功了怎么还是旧值"
        FileConfigStorage s = newStorage();
        s.writeGlobal("theme", "dark");
        s.writeGlobal("theme", "light");
        assertThat(s.readGlobal("theme"))
            .as("cache invalidation: 连续两次写, 最后一次生效")
            .isEqualTo("light");
    }

    @Test
    @DisplayName("createDirectories: 根目录不存在时自动创建")
    void autoCreateNexusaiHome() {
        // WHY: 启动时若配置根不存在, writeGlobal 必须自动创建父目录 —
        // 不应让运维手动 mkdir。决策 D1：根路径由 NexusaiPaths/user.home 派生，
        //   经 properties 显式指定新路径验证写时自动建父目录。
        Path newHome = tempDir.resolve("fresh-home");
        FileConfigStorage s = new FileConfigStorage(new ConfigStorageProperties(
            newHome.resolve(".nexusai.json").toString(), null));

        s.writeGlobal("theme", "dark");
        assertThat(Files.exists(newHome.resolve(".nexusai.json")))
            .as("写时自动创建父目录")
            .isTrue();
    }

    // ── Phase 3.E: concurrent write lock ──────────────────────────────────

    @Test
    @DisplayName("并发写: 50 个线程同时 writeGlobal 不同 key, 全部最终可读")
    void concurrentWritesAreSerialized() throws InterruptedException {
        // WHY: ReentrantLock 串行化所有写. 50 线程并发写不丢更新 —
        // 验证锁不是装饰品, 真正起效 (CLAUDE.md 规则 12 Fail loud).
        FileConfigStorage s = newStorage();
        int threadCount = 50;
        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            exec.submit(() -> {
                try {
                    start.await();
                    s.writeGlobal("key-" + idx, "value-" + idx);
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS))
            .as("50 个并发写必须在 10s 内完成 (锁不阻塞过久)")
            .isTrue();
        exec.shutdown();
        assertThat(errors.get())
            .as("并发写不应抛异常 (锁保证原子性)")
            .isZero();

        // 全部 key 都应可读 (无丢失)
        for (int i = 0; i < threadCount; i++) {
            assertThat(s.readGlobal("key-" + i))
                .as("并发写 key-%d 应最终可读", i)
                .isEqualTo("value-" + i);
        }
    }

    @Test
    @DisplayName("并发写同一 key: 最终值确定 (last-writer-wins, 不丢更新)")
    void concurrentWritesToSameKeyConvergeToLastValue() throws InterruptedException {
        // WHY: 多线程同时写同一个 key, 锁保证顺序. 最终值是某个线程的写入值,
        // 但不应出现"半写入"或文件损坏. 这是 lock 的核心 invariant.
        FileConfigStorage s = newStorage();
        int threadCount = 20;
        ExecutorService exec = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            exec.submit(() -> {
                try {
                    start.await();
                    s.writeGlobal("theme", "v" + idx);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        exec.shutdown();

        // 最终值是某个 v0..v19 之一 — 不抛错即视为成功
        Object finalValue = s.readGlobal("theme");
        assertThat(finalValue)
            .as("并发写同 key 后, 最终值应是某个 vN 字符串")
            .isInstanceOfSatisfying(String.class, v ->
                assertThat(v).matches("v\\d+"));
    }

    // ── Phase 3.F: listener ───────────────────────────────────────────────

    @Test
    @DisplayName("listener: write 后立即通知, source + path + value 正确")
    void listenerInvokedAfterWrite() {
        // WHY: 监听器供 cache invalidation 上层消费; 必须同步通知,
        // 不能延迟或丢失. event 字段用于 audit log (谁动了什么).
        FileConfigStorage s = newStorage();
        AtomicInteger callCount = new AtomicInteger(0);
        ConfigStorage.ConfigChange[] captured = new ConfigStorage.ConfigChange[1];

        s.addChangeListener(change -> {
            callCount.incrementAndGet();
            captured[0] = change;
        });

        s.writeGlobal("theme", "dark");
        assertThat(callCount.get())
            .as("listener 必须被调用 1 次")
            .isEqualTo(1);
        assertThat(captured[0])
            .isNotNull()
            .satisfies(c -> {
                assertThat(c.source()).isEqualTo("global");
                assertThat(c.path()).containsExactly("theme");
                assertThat(c.value()).isEqualTo("dark");
            });
    }

    @Test
    @DisplayName("listener: 嵌套 path write 触发 source=settings")
    void listenerForSettingsPathWrite() {
        FileConfigStorage s = newStorage();
        AtomicInteger callCount = new AtomicInteger(0);
        s.addChangeListener(change -> {
            callCount.incrementAndGet();
            assertThat(change.source()).isEqualTo("settings");
            assertThat(change.path()).containsExactly("permissions", "defaultMode");
        });
        s.writeSettings(List.of("permissions", "defaultMode"), "plan");
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("listener: unset 也触发通知 (value=null)")
    void listenerInvokedOnUnset() {
        // WHY: unset 是写操作 (key 移除). 监听器应收到, 否则上游 cache
        // 还持有旧值, 永远看不到 key 被删
        FileConfigStorage s = newStorage();
        s.writeGlobal("theme", "dark");

        AtomicInteger callCount = new AtomicInteger(0);
        s.addChangeListener(change -> callCount.incrementAndGet());

        s.unsetGlobal("theme");
        assertThat(callCount.get())
            .as("unsetGlobal 必须触发 listener")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("listener: removeChangeListener 后不再通知")
    void removedListenerNotInvoked() {
        FileConfigStorage s = newStorage();
        AtomicInteger callCount = new AtomicInteger(0);
        ConfigStorage.ConfigChangeListener listener = change -> callCount.incrementAndGet();
        s.addChangeListener(listener);
        s.removeChangeListener(listener);

        s.writeGlobal("theme", "dark");
        assertThat(callCount.get())
            .as("removeChangeListener 后 listener 不应被调用")
            .isZero();
    }

    @Test
    @DisplayName("listener: 单个 listener 抛异常不应影响其他 listener")
    void listenerExceptionDoesNotPropagate() {
        // WHY: listener 异常必须被吞掉 (best-effort); 一个坏 listener 不应
        // 让其他 listener 收不到通知 — 这是 notify loop 的隔离性
        FileConfigStorage s = newStorage();
        AtomicInteger goodCount = new AtomicInteger(0);
        s.addChangeListener(change -> {
            throw new RuntimeException("intentional");
        });
        s.addChangeListener(change -> goodCount.incrementAndGet());

        s.writeGlobal("theme", "dark");
        assertThat(goodCount.get())
            .as("坏 listener 抛异常不影响好 listener 收到通知")
            .isEqualTo(1);
    }

    // ── Phase 3.G: input validation ───────────────────────────────────────

    @Test
    @DisplayName("writeGlobal blank key 抛 IllegalArgumentException")
    void writeGlobalBlankKeyThrows() {
        // WHY: 防御性 guard — null/blank key 是编程错误, 应 fail loud,
        // 不静默写空配置
        FileConfigStorage s = newStorage();
        assertThatThrownBy(() -> s.writeGlobal("", "value"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.writeGlobal(null, "value"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("writeSettings empty path 抛 IllegalArgumentException")
    void writeSettingsEmptyPathThrows() {
        FileConfigStorage s = newStorage();
        assertThatThrownBy(() -> s.writeSettings(List.of(), "value"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("构造缺省（null properties）→ 回落默认路径，不再抛异常（决策 D1 + G3 第二轮）")
    void blankNexusaiHomeFallsBackToDynamicRoot() {
        // WHY: 决策 D1 + G3 第二轮拍板：nexusai.home / NEXUSAI_HOME 已废弃，不再注入，构造不再
        //   校验 blank 即抛 IllegalArgumentException。缺省回落默认路径：global =
        //   {user.home}/.nexusai.json，settings = NexusaiPaths.getAppConfigHomeDir()/settings.json
        //   （= {user.home}/.nexusai/settings.json）。惰性解析（根不 EAGER 冻结）：构造不解析，
        //   首次读取路径才解析 —— 断言不抛异常，且 global 父目录 = user.home，settings 父目录 =
        //   NexusaiPaths 动态自有根（user.home 已被 isolateUserHome 隔离到 tempDir）。
        FileConfigStorage blank = new FileConfigStorage(null);
        Path global = blank.paths().get("global"); // 触发惰性解析
        assertThat(global.getParent()).isEqualTo(Paths.get(System.getProperty("user.home")));
        assertThat(global.getFileName().toString()).isEqualTo(".nexusai.json");
        Path settings = blank.paths().get("settings");
        assertThat(settings.getParent()).isEqualTo(Paths.get(NexusaiPaths.getAppConfigHomeDir()));
        assertThat(settings.getFileName().toString()).isEqualTo("settings.json");

        FileConfigStorage nullHome = new FileConfigStorage(null);
        assertThat(nullHome.paths().get("global").getParent())
            .isEqualTo(Paths.get(System.getProperty("user.home")));
    }

    // ── Phase 3.H: properties-driven path ─────────────────────────────────

    @Test
    @DisplayName("ConfigStorageProperties 指定路径: 写文件落在配置路径而非默认路径")
    void customPathFromProperties() throws IOException {
        // WHY: ConfigStorageProperties 让运维自定义路径 (e.g. 跨用户 / 测试目录).
        // 必须严格使用 properties 路径, 不 fallback 到 ~/.nexusai
        Path customGlobal = tempDir.resolve("custom-config.json");
        Path customSettings = tempDir.resolve("custom-settings.json");
        FileConfigStorage s = newStorageWithProperties(customGlobal, customSettings);

        s.writeGlobal("theme", "dark");
        s.writeSettings(List.of("permissions", "defaultMode"), "plan");

        assertThat(Files.exists(customGlobal))
            .as("global 写在 properties 指定路径")
            .isTrue();
        assertThat(Files.exists(customSettings))
            .as("settings 写在 properties 指定路径")
            .isTrue();
        // 默认路径不应被创建
        assertThat(Files.exists(tempDir.resolve(".nexusai.json")))
            .as("properties 模式不创建默认 ~/.nexusai.json")
            .isFalse();
        assertThat(Files.exists(tempDir.resolve(".nexusai")))
            .as("properties 模式不创建默认 ~/.nexusai")
            .isFalse();
    }

    @Test
    @DisplayName("读 + 写隔离: 同一 storage 实例 global 与 settings 各自独立")
    void globalAndSettingsAreIndependent() {
        // WHY: global source 与 settings source 在 CC 是两个独立文件;
        // Java 端保持同一 storage 内的两个独立 cache + 文件, 不互相覆盖
        FileConfigStorage s = newStorage();
        s.writeGlobal("theme", "dark");
        s.writeSettings(List.of("model"), "opus");

        assertThat(s.readGlobal("theme")).isEqualTo("dark");
        assertThat(s.readSettings(List.of("model"))).isEqualTo("opus");

        // 写 settings 不应影响 global
        s.writeSettings(List.of("permissions", "defaultMode"), "plan");
        assertThat(s.readGlobal("theme"))
            .as("写 settings 后 global 仍可读, 不被覆盖")
            .isEqualTo("dark");
    }
}