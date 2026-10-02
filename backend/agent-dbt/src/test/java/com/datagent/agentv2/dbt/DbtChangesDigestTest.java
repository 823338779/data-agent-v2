package com.datagent.agentv2.dbt;

import static org.assertj.core.api.Assertions.*;

import com.datagent.agentv2.dbt.model.DbtChangesDigest;
import com.datagent.agentv2.dbt.model.DbtContracts.DraftChange;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DbtChangesDigestTest {
    @Test
    void pythonAndJavaAgreeOnUnicodePathsAndExactNewlines() throws Exception {
        var json = new JsonMapper();
        var fixture =
                json.readTree(getClass().getResourceAsStream("/dbt-contract/changes-v1.json"));
        var changes =
                json.readerForListOf(DraftChange.class)
                        .<List<DraftChange>>readValue(fixture.path("changes"));
        assertThat(DbtChangesDigest.digest(changes)).isEqualTo(fixture.path("digest").asString());
        assertThat(DbtChangesDigest.digest(changes.reversed()))
                .isEqualTo(fixture.path("digest").asString());
    }
}
