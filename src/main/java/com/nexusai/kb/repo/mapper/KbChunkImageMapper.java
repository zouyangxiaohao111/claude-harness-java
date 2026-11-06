package com.nexusai.kb.repo.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.kb.repo.entity.KbChunkImageRecord;

/**
 * {@code kb_chunk_image} 表 mapper。V269 建表。
 *
 * <p>⚠️ 复合主键 ⇒ 写入走 ``KbChunkImageProjector`` 里的参数化原生 SQL，本 mapper 只用于查询。
 */
public interface KbChunkImageMapper extends BaseMapper<KbChunkImageRecord> {
}
