package com.nexusai.repository.session.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.session.entity.SessionArtifactRecord;

/**
 * 会话「产物 / 来源」抽取结果 Mapper（表 {@code session_artifact} · 迁移 V205）。
 *
 * <p>由 {@code @MapperScan("com.nexusai.repository.*.mapper")} 自动扫描；
 * 写侧只有 {@code SessionArtifactService}（UPSERT），读侧只有
 * {@code SessionArtifactController}（只读列表）。
 */
public interface SessionArtifactMapper extends BaseMapper<SessionArtifactRecord> {

}
