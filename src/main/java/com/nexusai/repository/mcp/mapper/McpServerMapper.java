package com.nexusai.repository.mcp.mapper;

import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.repository.mcp.entity.McpServerRecord;

public interface McpServerMapper extends BaseMapper<McpServerRecord> {

    /** 按 name 查询（name UNIQUE，导入 upsert 用 · CC 按 name 管理 server）。 */
    default McpServerRecord selectOneByName(String name) {
        return selectOneByQuery(QueryWrapper.create().where("name = ?", name));
    }
}
