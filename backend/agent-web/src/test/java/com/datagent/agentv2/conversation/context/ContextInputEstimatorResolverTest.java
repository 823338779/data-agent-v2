package com.datagent.agentv2.conversation.context;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContextInputEstimatorResolverTest {
    @Test
    void resolvesEachConfiguredEstimatorAndRejectsDuplicates() {
        var lsm = mock(ContextInputEstimator.class);
        when(lsm.strategyId()).thenReturn("lsm");
        var hierarchical = mock(ContextInputEstimator.class);
        when(hierarchical.strategyId()).thenReturn("hierarchical");
        var properties = new ContextStrategyProperties();
        var resolver = new ContextInputEstimatorResolver(List.of(lsm, hierarchical), properties);
        assertThatThrownBy(resolver::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("raw");
        properties.setStrategy("lsm");
        assertThat(resolver.current()).isSameAs(lsm);
        properties.setStrategy("hierarchical");
        assertThat(resolver.current()).isSameAs(hierarchical);
        assertThatThrownBy(() -> new ContextInputEstimatorResolver(List.of(lsm, lsm), properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oldPreparedConstructorHasZeroStatistics() {
        assertThat(
                        new PreparedConversationContext("raw", List.of(), 0, false, 0)
                                .runtimeStatistics())
                .isEqualTo(RuntimeContextStatistics.ZERO);
    }
}
