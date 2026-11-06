package com.nexusai.kb.repo;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/**
 * SQLite 专用的 {@link LocalDateTime} 类型处理器。
 *
 * <p><b>为什么不用 MyBatis 自带的 {@code LocalDateTimeTypeHandler}</b>：
 * 它走 {@code ps.setObject(i, localDateTime)}，而 sqlite-jdbc 3.46.0.0 会落到
 * {@link LocalDateTime#toString()}，输出 {@code "2026-11-03T09:05"}（T 分隔、秒可省略）。
 * 与 SQLite {@code datetime('now')} 的 {@code "2026-11-03 12:26:09"}（空格分隔）混存时
 * {@code ORDER BY} 会给错序（实测 09:05 排在 12:26:09 之后）。
 *
 * <p><b>本处理器做两件事</b>：
 * <ol>
 *   <li>写：经 {@link KbTimeFormat#format(LocalDateTime)} 统一为 {@code yyyy-MM-dd HH:mm:ss}</li>
 *   <li>读：经 {@link KbTimeFormat#parse(String)} 解析，兼容历史 {@code T} 分隔形态</li>
 * </ol>
 *
 * <p><b>挂载方式</b>：在实体字段上
 * {@code @Column(typeHandler = KbSqliteLocalDateTimeTypeHandler.class)}。
 * 由于本类只服务知识库域，⛔ 不在全局 {@code mybatis-flex.type-handlers} 注册，
 * 以免影响现有 19 张表（它们用 {@code String} 存时间，不走本处理器）。
 */
@MappedTypes(LocalDateTime.class)
public class KbSqliteLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType)
        throws SQLException {
        // 关键：固定格式化，不用 setObject（那会走 LocalDateTime.toString）
        ps.setString(i, KbTimeFormat.format(parameter));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return KbTimeFormat.parse(rs.getString(columnName));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return KbTimeFormat.parse(rs.getString(columnIndex));
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return KbTimeFormat.parse(cs.getString(columnIndex));
    }
}
