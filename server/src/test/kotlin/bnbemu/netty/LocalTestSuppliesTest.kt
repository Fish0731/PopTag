package bnbemu.netty

import bnbemu.server.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LocalTestSuppliesTest {
    @Test fun legacyTestFlagCannotGrantFreeConsumables() {
        val previous = System.getProperty("bnbemu.testSupplies")
        try {
            System.setProperty("bnbemu.testSupplies", "true")
            for (mode in listOf(0, 14, 15, 16)) {
                assertFalse(matchSupplyEntries(mode).any { it.itemId in 10003..10004 })
            }
        } finally {
            if (previous == null) System.clearProperty("bnbemu.testSupplies")
            else System.setProperty("bnbemu.testSupplies", previous)
        }
    }
}
