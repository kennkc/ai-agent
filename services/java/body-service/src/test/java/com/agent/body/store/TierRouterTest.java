package com.agent.body.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R3-01 分层规则验收：热度模型可解释、分层规则可配置、边界可证伪。
 */
class TierRouterTest {

    @Test
    void freshnessHalvesAfterOneHalfLife() {
        TierRouter router = new TierRouter(3600, 2.0, 1.0);
        assertEquals(1.0, router.freshnessWeight(0), 1e-9);
        assertEquals(0.5, router.freshnessWeight(3600), 1e-9);
        assertEquals(0.25, router.freshnessWeight(7200), 1e-9);
    }

    @Test
    void hotnessGrowsWithAccessCount() {
        TierRouter router = new TierRouter(3600, 2.0, 1.0);
        double low = router.hotness(0, 0);
        double high = router.hotness(100, 0);
        assertTrue(high > low, "访问次数越多热度应越高");
        // 对数压缩：不应随访问次数线性膨胀
        assertTrue(high < low + 6.0, "热度需做对数压缩，避免热点无限膨胀");
    }

    @Test
    void tierBoundariesMatchConfiguredThresholds() {
        TierRouter router = new TierRouter(3600, 2.0, 1.0);
        assertEquals(TierRouter.Tier.HOT, router.tierOf(2.0));
        assertEquals(TierRouter.Tier.WARM, router.tierOf(1.5));
        assertEquals(TierRouter.Tier.WARM, router.tierOf(1.0));
        assertEquals(TierRouter.Tier.COLD, router.tierOf(0.99));
    }

    @Test
    void tierRulesAreConfigurable() {
        TierRouter strict = new TierRouter(60, 10.0, 5.0);
        // 同样输入，不同阈值配置得到不同分层 —— 证明规则可配置
        assertEquals(TierRouter.Tier.COLD, strict.tierFor(3, 0));
        assertEquals(TierRouter.Tier.HOT, new TierRouter(3600, 2.0, 1.0).tierFor(3, 0));
    }

    @Test
    void tierCarriesStoreAndDescription() {
        assertNotNull(TierRouter.Tier.HOT.store());
        assertNotNull(TierRouter.Tier.WARM.store());
        assertNotNull(TierRouter.Tier.COLD.store());
        assertEquals("redis", TierRouter.Tier.HOT.store());
        assertEquals("qdrant", TierRouter.Tier.WARM.store());
        assertEquals("postgres", TierRouter.Tier.COLD.store());
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TierRouter(0, 2.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new TierRouter(3600, 1.0, 2.0));
    }
}
