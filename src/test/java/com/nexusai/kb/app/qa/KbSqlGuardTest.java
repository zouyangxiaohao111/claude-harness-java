package com.nexusai.kb.app.qa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 9 · {@link KbSqlGuard} 测试 —— **本阶段最高风险点的护栏**。
 *
 * <p>这段 SQL 会被直接在服务端执行。提示词里的「请只写 SELECT」不构成约束，
 * 所以每一条「必须拒绝」的用例都是一次真实的逃逸尝试。
 */
class KbSqlGuardTest {

    @Test
    @DisplayName("正常 SELECT 放行")
    void allowsPlainSelect() {
        assertThat(KbSqlGuard.check("SELECT count(*) FROM dataset").ok()).isTrue();
        assertThat(KbSqlGuard.check("SELECT * FROM dataset LIMIT 10").ok()).isTrue();
        assertThat(KbSqlGuard.check("select a, sum(b) from dataset group by a").ok()).isTrue();
    }

    @Test
    @DisplayName("末尾分号允许（常见写法）")
    void allowsTrailingSemicolon() {
        assertThat(KbSqlGuard.check("SELECT count(*) FROM dataset;").ok()).isTrue();
    }

    @Test
    @DisplayName("WITH（CTE）也放行 —— 它是只读的")
    void allowsCte() {
        assertThat(KbSqlGuard.check(
            "WITH t AS (SELECT a FROM dataset) SELECT count(*) FROM t").ok()).isTrue();
    }

    @Test
    @DisplayName("⭐ 多语句拒绝（末尾分号不算）")
    void rejectsMultipleStatements() {
        KbSqlGuard.Verdict v = KbSqlGuard.check("SELECT 1; DROP TABLE dataset");
        assertThat(v.ok()).isFalse();
        assertThat(v.reason()).contains("多条语句");
    }

    @Test
    @DisplayName("⭐ 非 SELECT 开头拒绝")
    void rejectsNonSelect() {
        for (String sql : new String[]{
            "DROP TABLE dataset",
            "DELETE FROM dataset",
            "INSERT INTO dataset VALUES (1)",
            "UPDATE dataset SET a = 1",
            "CREATE TABLE x AS SELECT 1",
            "ATTACH 'other.db'",
            "PRAGMA database_list"
        }) {
            KbSqlGuard.Verdict v = KbSqlGuard.check(sql);
            assertThat(v.ok()).as("应拒绝: " + sql).isFalse();
        }
    }

    @Test
    @DisplayName("⭐⭐ 读文件函数拒绝（能绕开「只用 dataset」）")
    void rejectsFileReaders() {
        for (String sql : new String[]{
            "SELECT * FROM read_csv('/etc/passwd')",
            "SELECT * FROM read_parquet('/data/x.parquet')",
            "SELECT * FROM read_json_auto('/x.json')",
            "SELECT * FROM glob('/**')"
        }) {
            KbSqlGuard.Verdict v = KbSqlGuard.check(sql);
            assertThat(v.ok()).as("应拒绝: " + sql).isFalse();
            assertThat(v.reason()).contains("读取函数");
        }
    }

    @Test
    @DisplayName("⭐⭐ COPY 导出拒绝（写文件出去）")
    void rejectsCopy() {
        KbSqlGuard.Verdict v = KbSqlGuard.check(
            "COPY (SELECT * FROM dataset) TO '/tmp/leak.csv'");
        assertThat(v.ok()).isFalse();
    }

    @Test
    @DisplayName("⭐ INSTALL/LOAD 扩展拒绝（会走网络）")
    void rejectsExtensionInstall() {
        assertThat(KbSqlGuard.check(
            "SELECT 1; INSTALL httpfs").ok()).isFalse();
        // 即使藏在注释里也要挡住（注释先被剥掉）
        KbSqlGuard.Verdict v = KbSqlGuard.check(
            "SELECT 1 /* install httpfs */");
        assertThat(v.ok()).isTrue();   // 注释内容剥掉后只剩 SELECT 1 ⇒ 放行是对的
    }

    @Test
    @DisplayName("⭐⭐ 注释里藏危险语句 ⇒ 注释剥掉后暴露出来")
    void stripsCommentsFirst() {
        // 行注释里的分号被剥掉 ⇒ 只剩 SELECT ⇒ 允许（危险部分在注释里不会执行）
        assertThat(KbSqlGuard.check("SELECT 1 -- ; DROP TABLE dataset").ok()).isTrue();
        // 但真正在注释之外的就必须拒
        assertThat(KbSqlGuard.check("SELECT 1 /* x */ ; DROP TABLE dataset").ok()).isFalse();
    }

    @Test
    @DisplayName("⚠️ 词边界：不能误伤含关键字的列名（payload / offset / settings）")
    void wordBoundaryDoesNotOverMatch() {
        assertThat(KbSqlGuard.check("SELECT payload FROM dataset").ok()).isTrue();
        assertThat(KbSqlGuard.check("SELECT offset FROM dataset").ok()).isTrue();
        assertThat(KbSqlGuard.check("SELECT settings FROM dataset").ok()).isTrue();
    }

    @Test
    @DisplayName("空 / null / 纯空白 ⇒ 拒绝")
    void rejectsBlank() {
        assertThat(KbSqlGuard.check(null).ok()).isFalse();
        assertThat(KbSqlGuard.check("").ok()).isFalse();
        assertThat(KbSqlGuard.check("   ").ok()).isFalse();
        assertThat(KbSqlGuard.check("-- 只有注释").ok()).isFalse();
    }

    @Test
    @DisplayName("导出子句 INTO OUTFILE / DUMPFILE 拒绝")
    void rejectsIntoOutfile() {
        assertThat(KbSqlGuard.check(
            "SELECT * FROM dataset INTO OUTFILE '/tmp/x'").ok()).isFalse();
    }

    @Test
    @DisplayName("表名常量与源侧一致")
    void tableNameMatchesSource() {
        assertThat(KbSqlGuard.DATASET_TABLE).isEqualTo("dataset");
    }
}
