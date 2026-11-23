package com.nexusai.repository.connector.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code mcp_servers.config_json} 归属标记读写（V336 新列）。
 *
 * <p>为什么不加进 {@code McpServerRecord}：该实体是 MCP 域共享文件，本会话不改它；
 * 连接器只在自身写入的 server 上维护一个 JSON 标记：
 * {@code {"source":"connector","connectorKey":"<key>"}}。断开连接时只删标记匹配的行，
 * 既有行（标记为 NULL）永不被连接器删除。
 */
public interface ConnectorMcpMarkerMapper {

    /** 写归属标记。 */
    @Update("UPDATE mcp_servers SET config_json = #{marker} WHERE id = #{id}")
    int writeMarker(@Param("id") String id, @Param("marker") String marker);

    /** 按 server 名读归属标记（无行/无标记 → null）。 */
    @Select("SELECT config_json FROM mcp_servers WHERE name = #{name}")
    String findMarkerByName(@Param("name") String name);

    /**
     * 批量读取全部归属标记（应用目录一次取全，避免 N+1）。
     *
     * @return 行 {name, marker}（marker 为空的行不返回）
     */
    @Select("SELECT name AS name, config_json AS marker FROM mcp_servers "
        + "WHERE config_json IS NOT NULL AND config_json <> ''")
    java.util.List<java.util.Map<String, Object>> findAllMarkers();
}
