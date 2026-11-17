package com.trevornk.ramblr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DeliveryOrder]: queued dictations come out in the order they ended, whatever finishes first. */
class DeliveryOrderTest {

    @Test fun `a result waits behind an earlier one that is still pending`() {
        val order = DeliveryOrder<String>()
        val first = order.open()
        val second = order.open()
        assertTrue(order.resolve(second, "second"))
        assertNull(order.pollReady())
        assertEquals(2, order.size)
        assertTrue(order.resolve(first, "first"))
        assertEquals("first", order.pollReady())
        assertEquals("second", order.pollReady())
        assertNull(order.pollReady())
        assertEquals(0, order.size)
    }

    @Test fun `results that finish in order come out as they finish`() {
        val order = DeliveryOrder<String>()
        val first = order.open()
        val second = order.open()
        order.resolve(first, "first")
        assertEquals("first", order.pollReady())
        assertNull(order.pollReady())
        order.resolve(second, "second")
        assertEquals("second", order.pollReady())
    }

    @Test fun `a place takes one result only`() {
        val order = DeliveryOrder<String>()
        val slot = order.open()
        assertTrue(order.resolve(slot, "kept"))
        assertFalse(order.resolve(slot, "late"))
        assertEquals("kept", order.pollReady())
        assertFalse(order.resolve(slot, "after delivery"))
    }

    @Test fun `clearing drops what is pending and a late result is refused`() {
        val order = DeliveryOrder<String>()
        val slot = order.open()
        order.clear()
        assertEquals(0, order.size)
        assertFalse(order.resolve(slot, "late"))
        assertNull(order.pollReady())
    }
}
