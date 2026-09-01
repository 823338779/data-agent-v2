package com.datagent.agentv2.datasource.query;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.util.TablesNamesFinder;

/** 以 AST 为准执行只读、表列白名单、JOIN 白名单和 LIMIT 重写。 */
public final class StarRocksSqlPolicyValidator {

    private final SqlLexicalGuard lexicalGuard = new SqlLexicalGuard();

    public ValidatedSql validate(String rawSql, QueryAccessScope scope) {
        lexicalGuard.validate(rawSql);
        Select select = parseSelect(rawSql);
        validateNoTableFunctions(select);
        validateTablesAndColumns(select, scope);
        validateJoins(select, scope);
        enforceLimit(select, scope.maxRows());
        String rewritten = select.toString();
        parseSelect(rewritten);
        return new ValidatedSql(rewritten);
    }

    private static Select parseSelect(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select)) {
                throw new SqlPolicyException(SqlPolicyException.Reason.NOT_SELECT, "只允许 SELECT 查询");
            }
            return select;
        } catch (JSQLParserException failure) {
            throw new SqlPolicyException(SqlPolicyException.Reason.MALFORMED, "SQL 无法解析");
        }
    }

    private static void validateNoTableFunctions(Select select) {
        PlainSelect plain = select.getPlainSelect();
        if (plain != null && plain.getFromItem() instanceof TableFunction) {
            throw new SqlPolicyException(
                    SqlPolicyException.Reason.TABLE_FUNCTION_NOT_ALLOWED, "不允许表函数");
        }
        if (plain != null && plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                if (join.getRightItem() instanceof TableFunction) {
                    throw new SqlPolicyException(
                            SqlPolicyException.Reason.TABLE_FUNCTION_NOT_ALLOWED, "不允许表函数");
                }
            }
        }
    }

    private static void validateTablesAndColumns(Select select, QueryAccessScope scope) {
        ColumnCollectingFinder finder = new ColumnCollectingFinder();
        Set<String> tables = finder.getTables((Statement) select);
        // 星号会把未来新增或未授权字段一并暴露，因此即使表已授权也必须拒绝。
        if (finder.containsWildcard) {
            throw new SqlPolicyException(
                    SqlPolicyException.Reason.COLUMN_NOT_ALLOWED, "查询必须显式列出授权字段");
        }
        for (String table : tables) {
            String normalized = simpleName(table);
            if (!scope.allowedTables().contains(normalized)) {
                throw new SqlPolicyException(
                        SqlPolicyException.Reason.TABLE_NOT_ALLOWED, "查询引用了未授权数据表");
            }
        }
        Map<String, String> aliases = aliases(select);
        for (Column column : finder.columns) {
            String columnName = QueryAccessScope.normalize(column.getUnquotedColumnName());
            String qualifier = QueryAccessScope.normalize(column.getUnquotedTableName());
            if (!qualifier.isBlank()) {
                String table = aliases.getOrDefault(qualifier, qualifier);
                if (!scope.allowedColumnsByTable()
                        .getOrDefault(table, Set.of())
                        .contains(columnName)) {
                    throw new SqlPolicyException(
                            SqlPolicyException.Reason.COLUMN_NOT_ALLOWED, "查询引用了未授权字段");
                }
            } else {
                boolean allowed =
                        scope.allowedColumnsByTable().values().stream()
                                .anyMatch(columns -> columns.contains(columnName));
                if (!allowed) {
                    throw new SqlPolicyException(
                            SqlPolicyException.Reason.COLUMN_NOT_ALLOWED, "查询引用了未授权字段");
                }
            }
        }
    }

    private static void validateJoins(Select select, QueryAccessScope scope) {
        PlainSelect plain = select.getPlainSelect();
        if (plain == null || plain.getJoins() == null) return;
        Map<String, String> aliases = aliases(select);
        for (Join join : plain.getJoins()) {
            var onExpressions = join.getOnExpressions();
            if (onExpressions == null || onExpressions.size() != 1) {
                throw new SqlPolicyException(
                        SqlPolicyException.Reason.JOIN_NOT_ALLOWED, "JOIN 必须包含一个明确的等值条件");
            }
            Expression expression = onExpressions.iterator().next();
            if (!(expression instanceof EqualsTo equals)
                    || !(equals.getLeftExpression() instanceof Column left)
                    || !(equals.getRightExpression() instanceof Column right)) {
                throw new SqlPolicyException(
                        SqlPolicyException.Reason.JOIN_NOT_ALLOWED, "JOIN 条件不在语义关系白名单内");
            }
            String leftTable =
                    aliases.getOrDefault(
                            QueryAccessScope.normalize(left.getUnquotedTableName()),
                            QueryAccessScope.normalize(left.getUnquotedTableName()));
            String rightTable =
                    aliases.getOrDefault(
                            QueryAccessScope.normalize(right.getUnquotedTableName()),
                            QueryAccessScope.normalize(right.getUnquotedTableName()));
            boolean allowed =
                    scope.allowedJoins().stream()
                            .anyMatch(
                                    candidate ->
                                            candidate.matches(
                                                    leftTable,
                                                    left.getUnquotedColumnName(),
                                                    rightTable,
                                                    right.getUnquotedColumnName()));
            if (!allowed) {
                throw new SqlPolicyException(
                        SqlPolicyException.Reason.JOIN_NOT_ALLOWED, "JOIN 条件不在语义关系白名单内");
            }
        }
    }

    private static Map<String, String> aliases(Select select) {
        Map<String, String> aliases = new HashMap<>();
        PlainSelect plain = select.getPlainSelect();
        if (plain == null) return aliases;
        registerAlias(aliases, plain.getFromItem());
        if (plain.getJoins() != null) {
            plain.getJoins().forEach(join -> registerAlias(aliases, join.getRightItem()));
        }
        return aliases;
    }

    private static void registerAlias(Map<String, String> aliases, Object fromItem) {
        if (fromItem instanceof Table table) {
            String name = simpleName(table.getName());
            aliases.put(name, name);
            if (table.getAlias() != null) {
                aliases.put(QueryAccessScope.normalize(table.getAlias().getName()), name);
            }
        }
    }

    private static void enforceLimit(Select select, int maxRows) {
        Limit limit = select.getLimit();
        if (limit == null) {
            select.setLimit(new Limit().withRowCount(new LongValue(maxRows)));
            return;
        }
        if (!(limit.getRowCount() instanceof LongValue rowCount) || rowCount.getValue() > maxRows) {
            limit.setRowCount(new LongValue(maxRows));
        }
    }

    private static String simpleName(String value) {
        String normalized = QueryAccessScope.normalize(value);
        int index = normalized.lastIndexOf('.');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private static final class ColumnCollectingFinder extends TablesNamesFinder<Void> {
        private final Set<Column> columns = new HashSet<>();
        private boolean containsWildcard;

        @Override
        public <S> Void visit(Column column, S context) {
            columns.add(column);
            return super.visit(column, context);
        }

        @Override
        public <S> Void visit(AllColumns allColumns, S context) {
            containsWildcard = true;
            return super.visit(allColumns, context);
        }

        @Override
        public <S> Void visit(AllTableColumns allTableColumns, S context) {
            containsWildcard = true;
            return super.visit(allTableColumns, context);
        }
    }
}
