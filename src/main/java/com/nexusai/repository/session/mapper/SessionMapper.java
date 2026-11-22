package com.nexusai.repository.session.mapper;

import com.mybatisflex.core.BaseMapper;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.application.agent.tool.SessionToolCategory;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.alibaba.fastjson2.JSON;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

public interface SessionMapper extends BaseMapper<SessionRecord> {

    // 独立列不映射到全行实体，防止旧SessionRecord快照的update覆盖并发IM增量。
    @Select("SELECT im_tool_overrides FROM sessions WHERE id = #{id}")
    String selectImToolOverrides(@Param("id") String id);

    default int patchImToolOverrides(String id, Map<String, Boolean> patch) {
        if (patch == null || patch.isEmpty()) {
            throw new IllegalArgumentException("IM tool patch must not be empty");
        }
        for (var entry : patch.entrySet()) {
            Object value = entry.getValue();
            if (entry.getKey() == null || !SessionToolCategory.IM_TOOL_NAMES.contains(entry.getKey())
                    || ToolNameConstants.BRIEF_TOOL_NAME.equals(entry.getKey())
                    || !(value instanceof Boolean)) {
                throw new IllegalArgumentException("Invalid IM tool override: " + entry.getKey());
            }
        }
        return mergeImToolOverrides(id, JSON.toJSONString(patch));
    }

    // WHERE用CASE保护json_type；非法存量不被覆盖为“全开”，返回0由写入服务显式拒绝。
    @Update("UPDATE sessions SET im_tool_overrides = json_patch(COALESCE(im_tool_overrides, '{}'), #{patch}) "
            + "WHERE id = #{id} AND (im_tool_overrides IS NULL OR "
            + "json_type(CASE WHEN json_valid(im_tool_overrides) THEN im_tool_overrides ELSE 'null' END) = 'object')")
    int mergeImToolOverrides(@Param("id") String id, @Param("patch") String patch);

    /** fork 复制：把源会话的 IM 增量原样搬到新会话（与 insert 同事务调用）。 */
    @Update("UPDATE sessions SET im_tool_overrides = "
            + "(SELECT s2.im_tool_overrides FROM sessions s2 WHERE s2.id = #{sourceId}) "
            + "WHERE id = #{targetId}")
    int copyImToolOverrides(@Param("sourceId") String sourceId, @Param("targetId") String targetId);
}
