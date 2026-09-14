package com.nexusai.apis.claudemd;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.common.SessionProjectRoot;
import com.nexusai.domain.project.ClaudeMdIncludeApprovalStore;
import com.nexusai.repository.project.entity.ClaudeMdIncludeApprovalRecord;
import com.nexusai.repository.project.mapper.ClaudeMdIncludeApprovalMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * [acc2] {@link ClaudeMdController} 审批态<b>持久化</b>意图测试 · 前端审批对话框通道的跨重启语义。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：CC 的
 * {@code config.hasClaudeMdExternalIncludesApproved/WarningShown}（config.ts:115-116）宿主是
 * <b>project config（落盘）</b>⇒ 用户在对话框点过之后<b>重启不再被重复弹窗</b>。改造前 Java 侧只有
 * 进程内 {@code ConcurrentHashMap} ⇒ 每次重启都重复弹一次。本测试锁定<b>四条会静默失效的性质</b>：
 * <ol>
 *   <li><b>跨重启存活</b>：POST 审批 → 换一个<b>全新控制器实例 + 全新 store 实例</b>（= 重启后只剩 DB）
 *       → 读路径仍命中 true。⛔ 若 {@code store.save} 变空实现，本用例翻红。</li>
 *   <li><b>读 miss ⇒ 回源并回填</b>：新实例的内存 map 起初为空，一次读之后必须被 DB 值填充。</li>
 *   <li><b>键归一（⭐本批命门）</b>：同一目录的两种写法（Windows 上反斜杠 / 正斜杠 + 尾斜杠）必须
 *       命中同一行 —— 不归一即「<b>批准了但不加载</b>」的静默失效。</li>
 *   <li><b>分区不串</b>：A 项目批准不得让 B 项目（不同项目根）看起来已批准。</li>
 * </ol>
 */
@DisplayName("[acc2] ClaudeMdController 审批态持久化（跨重启 + 键归一）")
class ClaudeMdIncludeApprovalPersistenceTest {

    @TempDir
    Path tempRoot;

    private static ClaudeMdIncludeApprovalMapper mapper;
    private static ClaudeMdIncludeApprovalStore dbStore;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Path dbPath = MybatisFlexDbTestSupport.sharedDbPath();
        Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());

        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate();

        MybatisFlexDbTestSupport.resetAndStart(ds, ClaudeMdIncludeApprovalMapper.class);
        mapper = MybatisFlexBootstrap.getInstance().getMapper(ClaudeMdIncludeApprovalMapper.class);

        dbStore = new ClaudeMdIncludeApprovalStore();
        ReflectionTestUtils.setField(dbStore, "claudeMdIncludeApprovalMapper", mapper);
    }

    @BeforeEach
    void setUp() {
        for (ClaudeMdIncludeApprovalRecord r : mapper.selectAll()) {
            mapper.deleteById(r.getConfigKey());
        }
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
    }

    @AfterEach
    void tearDown() {
        SessionProjectRoot.setDbResolver(null);
        SessionProjectRoot.reset();
    }

    /**
     * 一个「控制器实例」夹具 = 一次 JVM 进程（内存 map 全新）。
     *
     * <p>每个夹具自持一个 store 实例（store 无状态，新实例 = 重启后重建的 bean），共享同一 DB ⇒
     * 「换夹具」正是<b>重启</b>的最小忠实模拟：内存全空，只剩 DB。
     */
    private static final class Instance {
        final ClaudeMdController controller = new ClaudeMdController();
        final ClaudemdEngine engine = mock(ClaudemdEngine.class);

        Instance(ClaudeMdIncludeApprovalStore store) {
            ReflectionTestUtils.setField(controller, "claudemdEngine", engine);
            controller.setStore(store);
        }

        /** 取控制器注册进引擎的审批态闭包（= 生产上引擎两个消费点唯一读到的那个）。 */
        Function<String, Boolean> approvedClosure() {
            ArgumentCaptor<Function<String, Boolean>> cap = ArgumentCaptor.forClass(Function.class);
            verify(engine, atLeastOnce()).setHasClaudeMdExternalIncludesApproved(cap.capture());
            return cap.getValue();
        }

        /** 取控制器注册进引擎的警告已示闭包。 */
        Function<String, Boolean> warningShownClosure() {
            ArgumentCaptor<Function<String, Boolean>> cap = ArgumentCaptor.forClass(Function.class);
            verify(engine, atLeastOnce()).setHasClaudeMdExternalIncludesWarningShown(cap.capture());
            return cap.getValue();
        }

        /** 走生产 POST 端点（前端对话框接受/拒绝）。 */
        void approve(String sessionId, boolean approved) {
            controller.includeApproval(new ClaudeMdController.IncludeApprovalRequest(approved, sessionId));
        }

        /**
         * 走生产读路径：先 GET /include-status（会话激活后前端必调 —— 它同时是
         * {@code wireEngine} 的触发点），再按引擎的读法调用审批态闭包。
         */
        boolean readApproved(String sessionId) {
            controller.includeStatus(sessionId);
            return Boolean.TRUE.equals(approvedClosure().apply(sessionId));
        }

        /** 同 {@link #readApproved}（WarningShown 侧）。 */
        boolean readWarningShown(String sessionId) {
            controller.includeStatus(sessionId);
            return Boolean.TRUE.equals(warningShownClosure().apply(sessionId));
        }

        @SuppressWarnings("unchecked")
        Map<String, Boolean> approvedMemoryMap() {
            return (Map<String, Boolean>) ReflectionTestUtils.getField(controller,
                "externalIncludesApprovedByProject");
        }

        @SuppressWarnings("unchecked")
        Map<String, Boolean> warningMemoryMap() {
            return (Map<String, Boolean>) ReflectionTestUtils.getField(controller,
                "externalIncludesWarningShownByProject");
        }
    }

    /** 建项目根目录并绑定会话（⚠️ 必须先建目录再 bind：setForSession 对无效路径**静默拒绝**）。 */
    private Path project(String name) throws Exception {
        return Files.createDirectories(tempRoot.resolve(name));
    }

    private void bind(String sessionId, String projectRoot) {
        SessionProjectRoot.setForSession(sessionId, projectRoot);
        assertThat(SessionProjectRoot.lookup(sessionId).projectRoot())
            .as("前置：会话已绑定到 %s（绑定失败会让后续断言假红）", projectRoot).isNotNull();
    }

    // ════════════════════════════════════════════════════════════════
    // ① ⭐ 跨重启存活（RE-2-1）
    // ════════════════════════════════════════════════════════════════

    /**
     * <b>反向实验（RE-2-1）</b>：把 {@code store.save} 改成空实现 ⇒ 本用例翻红（重启后读不到）。
     */
    @Test
    @DisplayName("[acc2] ⭐跨重启存活：POST 审批 → 全新实例（只剩 DB）仍读到 true（RE-2-1 守护）")
    void approval_survivesRestart() throws Exception {
        Path dir = project("proj-restart");
        bind("sess-restart", dir.toString());

        // 进程 1：用户点「允许」
        Instance before = new Instance(dbStore);
        before.approve("sess-restart", true);
        assertThat(before.readApproved("sess-restart")).as("同进程内应立即可见").isTrue();

        // 进程 2（重启）：内存全空，只剩 DB
        Instance after = new Instance(dbStore);
        assertThat(after.approvedMemoryMap()).as("前置：重启后内存镜像必须为空（否则不是重启模拟）").isEmpty();
        assertThat(after.readApproved("sess-restart"))
            .as("⭐ 重启后审批态必须从 DB 读回 true —— 空实现/纯内存实现此处为 false（= 重复弹审批）")
            .isTrue();
        assertThat(after.readWarningShown("sess-restart"))
            .as("⭐ WarningShown 同样必须跨重启存活（否则前端重复弹窗，claudemd.ts:1423-1426）").isTrue();
    }

    // ════════════════════════════════════════════════════════════════
    // ② ⭐ 读 miss ⇒ 回源 DB 并回填
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[acc2] ⭐读 miss ⇒ 回源 DB 并回填两份内存镜像（等价 SessionProjectRoot.lookup 范式）")
    void memoryMiss_refillsFromDb_andBackfillsMemory() throws Exception {
        Path dir = project("proj-refill");
        bind("sess-refill", dir.toString());
        new Instance(dbStore).approve("sess-refill", true);

        Instance after = new Instance(dbStore);
        assertThat(after.approvedMemoryMap()).as("前置：内存空").isEmpty();
        assertThat(after.warningMemoryMap()).as("前置：内存空").isEmpty();

        assertThat(after.readApproved("sess-refill")).as("miss 必须回源 DB 命中").isTrue();

        // 回填的判据：读路径结束后两份镜像都已有值（下一次读不再走 DB）
        assertThat(after.approvedMemoryMap())
            .as("回填：approved 镜像必须被 DB 值填充").containsEntry(dir.toString(), true);
        assertThat(after.warningMemoryMap())
            .as("回填：warningShown 镜像必须被 DB 值填充（同一行两列一次查询）")
            .containsEntry(dir.toString(), true);
    }

    /** 无行 ⇒ 读 false 但<b>不回填 false</b>（负缓存会钉死「先读 miss → 后 POST」）。 */
    @Test
    @DisplayName("[acc2] 无行 ⇒ 读 false 且不写负缓存（先读 miss 后 POST 仍须生效）")
    void noRow_readsFalseWithoutNegativeCaching() throws Exception {
        Path dir = project("proj-negative");
        bind("sess-negative", dir.toString());

        Instance inst = new Instance(dbStore);
        assertThat(inst.readApproved("sess-negative")).as("无行 ⇒ CC 缺省 false（config.ts:146）").isFalse();
        assertThat(inst.approvedMemoryMap()).as("⛔ 不得把 false 负缓存进内存").isEmpty();

        inst.approve("sess-negative", true);
        assertThat(inst.readApproved("sess-negative")).as("先读 miss 后 POST ⇒ 必须生效").isTrue();
    }

    // ════════════════════════════════════════════════════════════════
    // ③ ⭐ 键归一（本批命门 · 端到端）
    // ════════════════════════════════════════════════════════════════

    /**
     * <b>反向实验（RE-2-2）</b>：去掉 store 内的 {@code ProjectService.normalizePathKey} ⇒ 本用例翻红。
     *
     * <p>⭐ 这正是「<b>批准了但不加载</b>」的形态：写侧的一个写法落了库，读侧另一个写法查不到 ⇒
     * 用户明明批准过，重启后（或另一个会话）被判「未审批」。
     */
    @Test
    @DisplayName("[acc2] ⭐键归一端到端：同目录两种写法（反斜杠 / 尾斜杠）⇒ 同一行命中（RE-2-2 守护）")
    void keyNormalization_twoSpellingsOfSameDir_shareOneRow() throws Exception {
        Path dir = project("proj-keyspell");
        String plain = dir.toString();
        String slashVariant = plain + "/";   // Windows 上 = 正斜杠+尾斜杠；其它平台 = 尾斜杠
        assertThat(slashVariant).as("前提自证：两种写法必须是不同字符串（否则本断言假绿）")
            .isNotEqualTo(plain);
        assertThat(AutoMemPaths.findCanonicalGitRoot(plain))
            .as("前提自证：非 git 目录 ⇒ canonical 回落项目根本身（两种写法各自成为内存键）").isNull();
        bind("sess-plain", plain);
        bind("sess-slash", slashVariant);

        Instance before = new Instance(dbStore);
        before.approve("sess-plain", true);
        assertThat(mapper.selectAll()).as("审批已写穿 DB").hasSize(1);

        // 重启 + 换另一种写法读 ⇒ 必须命中（键归一）
        Instance after = new Instance(dbStore);
        assertThat(after.readApproved("sess-slash"))
            .as("⭐ 同目录另一种写法必须命中同一行 —— 不归一即「批准了但不加载」的静默失效").isTrue();
        assertThat(after.readWarningShown("sess-slash")).as("WarningShown 侧同样按归一键命中").isTrue();
        assertThat(mapper.selectAll()).as("两种写法必须共用一行（不是各写一行）").hasSize(1);
    }

    // ════════════════════════════════════════════════════════════════
    // ④ 分区不串（持久化不得让「按项目分区」退化回「全局单例」）
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[acc2] ⭐反向：A 项目批准 ⇒ B 项目（重启后）仍不受影响")
    void otherProject_notAffected_afterRestart() throws Exception {
        Path a = project("proj-persist-a");
        Path b = project("proj-persist-b");
        bind("sess-pa", a.toString());
        bind("sess-pb", b.toString());

        new Instance(dbStore).approve("sess-pa", true);

        Instance after = new Instance(dbStore);
        assertThat(after.readApproved("sess-pa")).as("A 项目已批准（回源 DB）").isTrue();
        assertThat(after.readApproved("sess-pb"))
            .as("⛔ B 项目必须不受影响 —— 持久化不得退化成「DB 里的全局单例」").isFalse();
        assertThat(after.readWarningShown("sess-pb")).as("⛔ B 项目未示警").isFalse();
    }

    // ════════════════════════════════════════════════════════════════
    // ⑤ store 缺席 ⇒ 纯内存（改造前语义，不抛）
    // ════════════════════════════════════════════════════════════════

    /**
     * store 未接线（测试 {@code new} 实例 / Spring 未注入）⇒ 纯内存行为，不抛。
     *
     * <p>这是 {@code @Autowired(required = false)} 的必要性所在：既有
     * {@code ClaudeMdControllerTest} 全部用例都以 {@code new ClaudeMdController()} 起手（无 Spring / 无 DB）。
     */
    @Test
    @DisplayName("[acc2] store 缺席 ⇒ 纯内存（不抛，改造前语义不变）")
    void storeAbsent_pureInMemory() throws Exception {
        Path dir = project("proj-nostore");
        bind("sess-nostore", dir.toString());

        Instance inst = new Instance(null);
        inst.approve("sess-nostore", true);

        assertThat(inst.readApproved("sess-nostore")).as("store=null ⇒ 内存写穿镜像仍生效").isTrue();
        assertThat(inst.readWarningShown("sess-nostore")).as("store=null ⇒ WarningShown 仍生效").isTrue();
        assertThat(mapper.selectAll()).as("store=null ⇒ 不落库").isEmpty();
    }
}
