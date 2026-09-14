package com.nexusai.domain.project;

import com.mybatisflex.core.MybatisFlexBootstrap;
import com.nexusai.repository.project.entity.ClaudeMdIncludeApprovalRecord;
import com.nexusai.repository.project.mapper.ClaudeMdIncludeApprovalMapper;
import com.nexusai.test.support.MybatisFlexDbTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [acc2] {@link ClaudeMdIncludeApprovalStore} · 审批态持久化 + <b>键归一</b> 意图测试。
 *
 * <p><b>WHY（CLAUDE.md 规则九）</b>：CC 的
 * {@code config.hasClaudeMdExternalIncludesApproved/WarningShown}（config.ts:115-116）宿主是
 * <b>project config（落盘）</b>⇒ 重启后不重复弹审批。本 store 是该语义的 Java 事实源，测试锁定三条
 * <b>「错了会静默失效」</b>的性质：
 * <ol>
 *   <li><b>键归一</b>：DB 键必须过 {@code ProjectService.normalizePathKey}（折叠斜杠方向 / 尾斜杠 /
 *       Windows 大小写）。控制器写侧的键来自 {@code Path.toString()}（Windows <b>反斜杠</b>），
 *       读侧可能以正斜杠出现 —— 不归一 ⇒ 两行 / 查不到 ⇒ ⭐<b>「批准了但不加载」</b>的静默失效。</li>
 *   <li><b>upsert 显式时间戳</b>：MyBatis-Flex {@code insert(entity)} 会把 null 字段一并写入 ⇒
 *       NULL 覆盖 DDL 的 {@code DEFAULT (datetime('now'))}；且二次 save 必须<b>不改</b>
 *       {@code created_at}（{@code update(entity)} 忽略 null）。</li>
 *   <li><b>幂等</b>：同一项目键反复 save 只保留一行（否则行数随弹窗次数增长）。</li>
 * </ol>
 */
@DisplayName("[acc2] ClaudeMdIncludeApprovalStore（V74 持久化 + 键归一）")
class ClaudeMdIncludeApprovalStoreTest {

    private static ClaudeMdIncludeApprovalMapper mapper;
    private static ClaudeMdIncludeApprovalStore store;

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

        store = new ClaudeMdIncludeApprovalStore();
        ReflectionTestUtils.setField(store, "claudeMdIncludeApprovalMapper", mapper);
    }

    @BeforeEach
    void clean() {
        // 共享 DB 跨测试类存活 ⇒ 逐用例清行（无 where 的 deleteByQuery 被 MyBatis-Flex 安全拦截，
        // 故按主键逐行删）
        for (ClaudeMdIncludeApprovalRecord r : mapper.selectAll()) {
            mapper.deleteById(r.getConfigKey());
        }
        assertThat(mapper.selectAll()).as("前置：本表已清空").isEmpty();
    }

    /** 读回唯一一行（键取 DB 实存值，⛔ 不在测试里复刻第二份归一）。 */
    private ClaudeMdIncludeApprovalRecord onlyRow() {
        List<ClaudeMdIncludeApprovalRecord> rows = mapper.selectAll();
        assertThat(rows).as("upsert 幂等：同一项目键只应有一行").hasSize(1);
        return rows.get(0);
    }

    // ════════════════════════════════════════════════════════════════
    // ① ⭐ 键归一（本批命门）
    // ════════════════════════════════════════════════════════════════

    /**
     * <b>反向实验（RE-2-2）</b>：去掉 store 内的 {@code ProjectService.normalizePathKey} ⇒ 本用例翻红
     * （写侧 {@code D:\x\y} 与读侧 {@code D:/x/y} 落成两行 / 查不到）。
     */
    @Test
    @DisplayName("[acc2] ⭐键归一：写侧反斜杠、读侧正斜杠 ⇒ 命中同一行（RE-2-2 守护）")
    void normalize_backslashWrite_forwardSlashRead_hitsSameRow() {
        String backslash = "D:" + (char) 92 + "code" + (char) 92 + "proj" + (char) 92 + "x";
        String forward = "D:/code/proj/x";

        store.save(backslash, true, true);

        assertThat(store.read(forward))
            .as("⭐ 写侧 %s、读侧 %s 必须命中同一行 —— 不做归一会静默变成「批准了但不加载」",
                backslash, forward)
            .isNotNull();
        assertThat(store.read(forward).approved()).isTrue();
        assertThat(onlyRow().getConfigKey())
            .as("DB 键必须是归一化形式（反斜杠已折叠；⛔ 原样落库即未归一）")
            .doesNotContain(String.valueOf((char) 92))
            .doesNotEndWith("/");

        // 反向：同一目录的另一种写法<b>再写一次</b> ⇒ 必须是覆盖同一行，而不是新增第二行
        store.save(forward, false, true);
        assertThat(onlyRow().getExternalIncludesApproved())
            .as("两种斜杠写法必须落同一行（覆盖）—— 落成两行即归一失效").isZero();
    }

    /** 尾斜杠也必须折叠（同一目录的两种写法）。 */
    @Test
    @DisplayName("[acc2] 键归一：尾斜杠折叠 ⇒ 同一行")
    void normalize_trailingSlash_collapses() {
        store.save("D:/code/proj/x/", false, true);
        assertThat(onlyRow().getConfigKey()).as("尾斜杠必须折叠掉").doesNotEndWith("/");

        assertThat(store.read("D:/code/proj/x"))
            .as("尾斜杠写法必须折叠到同一行").isNotNull();
        store.save("D:/code/proj/x", true, false);
        assertThat(onlyRow().getExternalIncludesApproved())
            .as("两种写法必须落同一行（覆盖）").isEqualTo(1);
    }

    /**
     * Windows 大小写折叠（{@code ProjectService.normalizePathKey} 仅在 Windows 上 toLowerCase）。
     *
     * <p>非 Windows 上本条<b>不适用</b>（Linux/macOS 大小写敏感 ⇒ 大写路径是<b>另一个目录</b>）⇒
     * {@code assumeTrue} 显式跳过（⛔ 不静默忽略）。
     */
    @Test
    @DisplayName("[acc2] 键归一（Windows）：大小写折叠 ⇒ 同一行")
    void normalize_windowsCaseFolding() {
        Assumptions.assumeTrue(ProjectService.IS_WINDOWS, "大小写折叠仅 Windows（IS_WINDOWS=false 不适用）");

        store.save("D:/CODE/Proj/X", true, true);

        assertThat(store.read("d:/code/proj/x")).as("Windows 大小写不敏感 ⇒ 必须命中同一行").isNotNull();
        assertThat(onlyRow().getConfigKey()).isEqualTo("d:/code/proj/x");
    }

    /** 键归一后为空（null / 空白）⇒ 读写均 no-op（⛔ 不写空键行：那会让所有解析不出的项目共用一行）。 */
    @Test
    @DisplayName("[acc2] 空键 ⇒ 读写均 no-op（不落空键行）")
    void emptyKey_isNoOp() {
        store.save(null, true, true);
        store.save("", true, true);
        store.save("   ", true, true);

        assertThat(mapper.selectAll()).as("⛔ 空键不得落行").isEmpty();
        assertThat(store.read("")).as("空键读 ⇒ null（无行）").isNull();
        assertThat(store.read(null)).as("null 键读 ⇒ null").isNull();
    }

    // ════════════════════════════════════════════════════════════════
    // ② 无行 ≠ 未审批 + upsert 语义
    // ════════════════════════════════════════════════════════════════

    /** 无行 ⇒ null（⛔ 与「有行但 approved=0」不同：前者 = CC 缺省，后者 = 用户明确拒绝过）。 */
    @Test
    @DisplayName("[acc2] 无行 ⇒ read 返回 null（区别于 approved=false 的行）")
    void noRow_readsNull() {
        assertThat(store.read("d:/code/never-saved")).isNull();

        store.save("d:/code/saved", false, false);
        assertThat(store.read("d:/code/saved")).as("有行 ⇒ 非 null（两列均 false）").isNotNull();
        assertThat(store.read("d:/code/saved").approved()).isFalse();
        assertThat(store.read("d:/code/saved").warningShown()).isFalse();
    }

    /** 两列一次 upsert：二次 save 覆盖两列且仍只有一行。 */
    @Test
    @DisplayName("[acc2] upsert：二次 save 覆盖两列且保持单行（幂等）")
    void save_isIdempotentUpsert() {
        store.save("d:/code/p", true, true);
        assertThat(store.read("d:/code/p").approved()).isTrue();

        store.save("d:/code/p", false, true);

        assertThat(onlyRow().getExternalIncludesApproved()).as("approved 列被覆盖").isZero();
        assertThat(onlyRow().getExternalIncludesWarningShown()).as("warningShown 列被覆盖").isEqualTo(1);
        assertThat(store.read("d:/code/p").approved()).isFalse();
    }

    // ════════════════════════════════════════════════════════════════
    // ③ ⚠️ upsert 显式时间戳
    // ════════════════════════════════════════════════════════════════

    /**
     * <b>反向实验（RE-2-3）</b>：去掉 insert 分支的 {@code setCreatedAt/setUpdatedAt} 显式赋值 ⇒
     * 本用例翻红（MyBatis-Flex insert 带 NULL 覆盖 DB DEFAULT ⇒ NOT NULL 约束失败 / created_at 为 NULL）。
     */
    @Test
    @DisplayName("[acc2] ⚠️ upsert 时间戳：created_at 首次落库后不变、updated_at 随写更新（RE-2-3 守护）")
    void upsertTimestamps_createdAtStable_updatedAtAdvances() throws Exception {
        store.save("d:/code/ts", true, true);
        ClaudeMdIncludeApprovalRecord first = onlyRow();

        assertThat(first.getCreatedAt()).as("insert 分支必须显式填 created_at（否则被 NULL 覆盖 DB DEFAULT）")
            .isNotBlank();
        assertThat(first.getUpdatedAt()).as("insert 分支必须显式填 updated_at").isNotBlank();

        // 系统时钟粒度可能粗于两次相邻写入 ⇒ 显式等待，避免「同刻断言」假红
        Thread.sleep(20L);
        store.save("d:/code/ts", false, true);
        ClaudeMdIncludeApprovalRecord second = onlyRow();

        assertThat(second.getCreatedAt())
            .as("二次 save（update 分支）不得改动 created_at（update(entity) 忽略 null ⇒ 不被覆盖）")
            .isEqualTo(first.getCreatedAt());
        assertThat(second.getUpdatedAt())
            .as("二次 save 必须刷新 updated_at")
            .isNotEqualTo(first.getUpdatedAt());
    }
}
