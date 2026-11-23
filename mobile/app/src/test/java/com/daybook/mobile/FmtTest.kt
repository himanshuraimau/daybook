package com.daybook.mobile

import com.daybook.mobile.ui.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FmtTest {
    @Test fun indianGrouping() {
        assertEquals("0", Fmt.indian("0")); assertEquals("999", Fmt.indian("999")); assertEquals("1,000", Fmt.indian("1000"))
        assertEquals("12,345", Fmt.indian("12345")); assertEquals("1,23,456", Fmt.indian("123456"))
        assertEquals("12,34,567", Fmt.indian("1234567")); assertEquals("1,00,00,000", Fmt.indian("10000000"))
        assertEquals("1,00,00,00,000", Fmt.indian("1000000000"))
    }
    @Test fun moneyShowsDecimalsOnlyWhenNeeded() {
        assertEquals("₹0", Fmt.money(0)); assertEquals("₹250.50", Fmt.money(25050)); assertEquals("₹10,000", Fmt.money(1_000_000))
        assertEquals("₹1,24,000.05", Fmt.money(12_400_005)); assertEquals("₹0.07", Fmt.money(7)); assertEquals("-₹2,300", Fmt.money(-230_000))
        assertEquals("₹39,749.50", Fmt.money(3_974_950))
    }
    @Test fun parsePaiseAcceptsRupeesWithAtMostTwoDecimals() {
        assertEquals(25050L, Fmt.parsePaise("250.5")); assertEquals(100L, Fmt.parsePaise("1")); assertEquals(5_000_000L, Fmt.parsePaise("50,000"))
        assertNull(Fmt.parsePaise("")); assertNull(Fmt.parsePaise("0")); assertNull(Fmt.parsePaise("-5")); assertNull(Fmt.parsePaise("12.345"))
        assertNull(Fmt.parsePaise("abc")); assertNull(Fmt.parsePaise("1000000001"))
    }
}
