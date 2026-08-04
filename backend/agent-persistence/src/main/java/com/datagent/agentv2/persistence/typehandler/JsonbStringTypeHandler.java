package com.datagent.agentv2.persistence.typehandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 把已完成业务校验和序列化的 JSON 文档按 PostgreSQL JSONB 参数写入。 */
public final class JsonbStringTypeHandler extends BaseTypeHandler<String> {

    /** 使用 OTHER 类型绑定字符串，避免 PostgreSQL 把 JSON 文档识别为 varchar。 */
    @Override
    public void setNonNullParameter(
            PreparedStatement statement, int index, String parameter, JdbcType jdbcType)
            throws SQLException {
        statement.setObject(index, parameter, Types.OTHER);
    }

    /** 按列名读取 JSONB 的文本表示。 */
    @Override
    public String getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return resultSet.getString(columnName);
    }

    /** 按列序号读取 JSONB 的文本表示。 */
    @Override
    public String getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return resultSet.getString(columnIndex);
    }

    /** 从存储过程结果读取 JSONB 的文本表示。 */
    @Override
    public String getNullableResult(CallableStatement statement, int columnIndex)
            throws SQLException {
        return statement.getString(columnIndex);
    }
}
