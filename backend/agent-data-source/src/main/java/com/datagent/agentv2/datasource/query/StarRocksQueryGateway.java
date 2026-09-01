package com.datagent.agentv2.datasource.query;

import com.datagent.agentv2.datasource.config.StarRocksDataSourceProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/** 执行 EXPLAIN 和有界只读查询的唯一网关。 */
@RequiredArgsConstructor
public final class StarRocksQueryGateway {

    private final DataSource dataSource;
    private final StarRocksDataSourceProperties properties;
    private final QueryFailureClassifier failureClassifier;

    public ExplainResult explain(ValidatedSql sql) {
        long started = System.nanoTime();
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            configure(statement);
            try (var result = statement.executeQuery("EXPLAIN " + sql.value())) {
                List<String> lines = new ArrayList<>();
                while (result.next()) {
                    lines.add(result.getString(1));
                }
                return new ExplainResult(lines, elapsedMillis(started));
            }
        } catch (SQLException failure) {
            throw new QueryFailureException(failureClassifier.classify(failure), failure);
        }
    }

    public TabularQueryResult execute(ValidatedSql sql) {
        long started = System.nanoTime();
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            configure(statement);
            try (var result = statement.executeQuery(sql.value())) {
                ResultSetMetaData metadata = result.getMetaData();
                List<ResultColumn> columns = columns(metadata);
                List<List<Object>> rows = new ArrayList<>();
                long bytes = 0;
                boolean truncated = false;
                while (result.next()) {
                    assertNotInterrupted(statement);
                    if (rows.size() >= properties.maxRows()) {
                        truncated = true;
                        break;
                    }
                    List<Object> row = readRow(result, metadata.getColumnCount());
                    long rowBytes = estimateBytes(row);
                    if (bytes + rowBytes > properties.maxResultBytes().toBytes()) {
                        rows.clear();
                        throw new QueryFailureException(QueryFailureCategory.RESULT_TOO_LARGE);
                    }
                    rows.add(row);
                    bytes += rowBytes;
                }
                return new TabularQueryResult(
                        columns, rows, truncated, rows.size(), elapsedMillis(started));
            }
        } catch (QueryFailureException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new QueryFailureException(failureClassifier.classify(failure), failure);
        }
    }

    private void configure(Statement statement) throws SQLException {
        long seconds = Math.max(1, properties.queryTimeout().toSeconds());
        statement.setQueryTimeout(Math.toIntExact(Math.min(Integer.MAX_VALUE, seconds)));
        statement.setFetchSize(Math.min(properties.maxRows() + 1, 10_000));
    }

    private static List<ResultColumn> columns(ResultSetMetaData metadata) throws SQLException {
        List<ResultColumn> columns = new ArrayList<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            columns.add(
                    new ResultColumn(
                            metadata.getColumnLabel(index), metadata.getColumnTypeName(index)));
        }
        return columns;
    }

    private static List<Object> readRow(ResultSet result, int count) throws SQLException {
        List<Object> row = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            row.add(normalize(result.getObject(index)));
        }
        return row;
    }

    private static Object normalize(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof BigDecimal
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double) {
            return value;
        }
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof TemporalAccessor
                || value instanceof java.util.Date
                || value instanceof java.sql.Date
                || value instanceof java.sql.Time
                || value instanceof java.sql.Timestamp) {
            return value.toString();
        }
        return value.toString();
    }

    private static long estimateBytes(List<Object> row) {
        long bytes = 2;
        for (Object value : row) {
            bytes += value == null ? 4 : value.toString().getBytes(StandardCharsets.UTF_8).length;
            bytes++;
        }
        return bytes;
    }

    private static void assertNotInterrupted(Statement statement) throws SQLException {
        if (Thread.currentThread().isInterrupted()) {
            statement.cancel();
            throw new QueryFailureException(QueryFailureCategory.TIMEOUT);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
