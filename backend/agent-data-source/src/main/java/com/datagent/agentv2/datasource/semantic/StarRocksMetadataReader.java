package com.datagent.agentv2.datasource.semantic;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;

/** 仅从固定配置的 Catalog/Database 读取元数据，绝不接收模型提供的标识符。 */
@RequiredArgsConstructor
public final class StarRocksMetadataReader {

    private final DataSource dataSource;
    private final String catalog;
    private final String database;

    public MetadataSnapshot read() {
        Map<String, TableMetadata> tables = new LinkedHashMap<>();
        try (var connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet result =
                    metadata.getTables(catalog, database, "%", new String[] {"TABLE", "VIEW"})) {
                while (result.next()) {
                    String name = result.getString("TABLE_NAME");
                    tables.put(
                            normalize(name),
                            new TableMetadata(
                                    name, result.getString("REMARKS"), new LinkedHashMap<>()));
                }
            }
            for (TableMetadata table : tables.values()) {
                try (ResultSet result = metadata.getColumns(catalog, database, table.name(), "%")) {
                    while (result.next()) {
                        String name = result.getString("COLUMN_NAME");
                        table.columns()
                                .put(
                                        normalize(name),
                                        new ColumnMetadata(
                                                name,
                                                result.getString("TYPE_NAME"),
                                                result.getString("REMARKS")));
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("无法读取 StarRocks 元数据", failure);
        }
        return new MetadataSnapshot(tables);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public record MetadataSnapshot(Map<String, TableMetadata> tables) {
        public MetadataSnapshot {
            tables = Map.copyOf(tables);
        }
    }

    public record TableMetadata(String name, String comment, Map<String, ColumnMetadata> columns) {}

    public record ColumnMetadata(String name, String type, String comment) {}
}
