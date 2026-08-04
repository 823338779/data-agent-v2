package com.datagent.agentv2.persistence.typehandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datagent.agentv2.persistence.entity.ToolExecutionEntity;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

class UuidTypeHandlerTest {

    private static final UUID PROCESS_ITEM_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PROCESS_ITEM_FIELD = "toolProcessItemId";
    private final UuidTypeHandler handler = new UuidTypeHandler();

    @Test
    void writesAndReadsPostgresUuidValues() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(result.getObject(PROCESS_ITEM_FIELD)).thenReturn(PROCESS_ITEM_ID);

        handler.setNonNullParameter(statement, 1, PROCESS_ITEM_ID, JdbcType.OTHER);

        verify(statement).setObject(1, PROCESS_ITEM_ID);
        assertThat(handler.getNullableResult(result, PROCESS_ITEM_FIELD)).isEqualTo(PROCESS_ITEM_ID);
    }

    @Test
    void toolExecutionFieldDeclaresTheUuidHandler() throws Exception {
        TableName table = ToolExecutionEntity.class.getAnnotation(TableName.class);
        TableField mapping =
                ToolExecutionEntity.class
                        .getDeclaredField(PROCESS_ITEM_FIELD)
                        .getAnnotation(TableField.class);

        assertThat(table.autoResultMap()).isTrue();
        assertThat(mapping).isNotNull();
        assertThat(mapping.jdbcType()).isEqualTo(JdbcType.OTHER);
        assertThat(mapping.typeHandler()).isEqualTo(UuidTypeHandler.class);
    }
}
