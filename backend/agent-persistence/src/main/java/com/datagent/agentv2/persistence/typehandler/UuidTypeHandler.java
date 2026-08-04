package com.datagent.agentv2.persistence.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/** 在 PostgreSQL uuid 列与 Java UUID 之间执行稳定的双向转换。 */
@MappedTypes(UUID.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public final class UuidTypeHandler extends BaseTypeHandler<UUID> {

    /** 使用 JDBC object 协议写入 PostgreSQL 原生 uuid。 */
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        statement.setObject(index, parameter);
    }

    /** 按列名读取 uuid。 */
    @Override
    public UUID getNullableResult(ResultSet result, String columnName) throws SQLException {
        return value(result.getObject(columnName));
    }

    /** 按列序号读取 uuid。 */
    @Override
    public UUID getNullableResult(ResultSet result, int columnIndex) throws SQLException {
        return value(result.getObject(columnIndex));
    }

    /** 从存储过程结果读取 uuid。 */
    @Override
    public UUID getNullableResult(CallableStatement statement, int columnIndex)
            throws SQLException {
        return value(statement.getObject(columnIndex));
    }

    /** 兼容驱动直接返回 UUID 或其字符串表示。 */
    private static UUID value(Object raw) {
        if (raw == null) {
            return null;
        }
        return raw instanceof UUID uuid ? uuid : UUID.fromString(raw.toString());
    }
}
