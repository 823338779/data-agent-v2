package com.datagent.agentv2.context.lsm.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datagent.agentv2.context.lsm.persistence.entity.LsmSummarySegmentEntity;
import com.datagent.agentv2.context.lsm.persistence.mapper.LsmSummarySegmentMapper;
import com.datagent.agentv2.context.lsm.persistence.repository.LsmSummarySegmentRepository;
import com.datagent.agentv2.platform.context.TenantContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class LsmSummarySegmentRepositoryTest {

    @Test
    void failsWhenAnyRequestedManifestSegmentIsMissing() {
        LsmSummarySegmentMapper mapper = mock(LsmSummarySegmentMapper.class);
        LsmSummarySegmentEntity present = new LsmSummarySegmentEntity();
        present.setSummarySegmentId("present");
        when(mapper.selectByIds("tenant", List.of("present", "missing")))
                .thenReturn(List.of(present));
        LsmSummarySegmentRepository repository = new LsmSummarySegmentRepository(mapper);

        assertThatThrownBy(
                        () ->
                                TenantContext.call(
                                        "tenant",
                                        () ->
                                                repository.findByIdsInOrder(
                                                        List.of("present", "missing"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("LSM manifest active segment set is inconsistent");
    }
}
