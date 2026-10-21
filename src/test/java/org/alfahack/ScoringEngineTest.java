package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ScoringEngineTest {
    private final SyntheticProfiles profiles=new SyntheticProfiles();
    private final ScoringEngine scoring=new ScoringEngine(profiles,new MockSources(profiles));
    @Test void sevenDistinctSyntheticSectorsAndDeterministicMocks() {
        assertEquals(7,profiles.catalog().size());
        assertEquals(scoring.assess("IT",new BigDecimal("700000"),new BigDecimal("30000")),scoring.assess("IT",new BigDecimal("700000"),new BigDecimal("30000")));
    }
    @Test void priorityBenefitCannotOverrideSevereRisk() {
        var low=scoring.assess("CONSTRUCTION",new BigDecimal("1000000"),new BigDecimal("90000"));
        assertEquals("REJECTED",low.get("outcome"));
        var conflict=scoring.assess("PRIORITY_CONFLICT",new BigDecimal("1000000"),new BigDecimal("90000"));
        assertEquals(true,conflict.get("subsidy_applied"));
        assertEquals("REJECTED",conflict.get("outcome"));
        var priority=scoring.assess("MANUFACTURING",new BigDecimal("5000000"),new BigDecimal("90000"));
        assertEquals(true,priority.get("subsidy_applied"));
        assertEquals(5,((Number)priority.get("adjusted_score")).intValue()-((Number)priority.get("score")).intValue());
    }
    @Test void unavailableAndMissingSourcesNeverApprove() {
        assertEquals("SOURCE_ERROR",scoring.assess("MOCK_FAILURE",new BigDecimal("100000"),BigDecimal.ZERO).get("outcome"));
        assertEquals("INSUFFICIENT_DATA",scoring.assess("MISSING_INFO",new BigDecimal("100000"),BigDecimal.ZERO).get("outcome"));
    }
}
