package com.nexusai.repository.peer.entity;

/**
 * 按会话聚合的「待处理」行数（counts 接口 / 聚合事件计数口径的数据源）。
 *
 * <p>只由 {@link com.nexusai.repository.peer.mapper.PeerHeldMessageMapper#countOpenGroupedBySession()}
 * 填充（别名 {@code sessionId} / {@code total}）；不是数据库表实体，没有表映射。
 *
 * <p>用可变类而不是 record：MyBatis 默认按 setter 自动映射，record 需要额外配置构造器映射。
 */
public class PeerHeldSessionCount {

    private String sessionId;
    private long total;
    /**
     * 该会话最早一条 pending 的 SQLite rowid（= 插入顺序，FIFO 的权威顺序）。
     *
     * <p>⛔ 不用 {@code MIN(created_at)} 排序：created_at 是 ISO-8601 文本，整秒与带小数秒
     * 混排时字典序会颠倒（见 mapper listPendingOnly 注释）。这里由子查询按 rowid 取最早一行，
     * 再把它的 created_at 带出仅作展示/兜底。
     */
    private Long oldestRowId;
    /** 最早一条 pending 的 created_at（ISO-8601；按 rowid 选出的那一行）。 */
    private String oldestCreatedAt;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public Long getOldestRowId() {
        return oldestRowId;
    }

    public void setOldestRowId(Long oldestRowId) {
        this.oldestRowId = oldestRowId;
    }

    public String getOldestCreatedAt() {
        return oldestCreatedAt;
    }

    public void setOldestCreatedAt(String oldestCreatedAt) {
        this.oldestCreatedAt = oldestCreatedAt;
    }
}
