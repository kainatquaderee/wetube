package dev.wetube.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The embedded engine can only be built once per process, and GeckoView does
 * not survive a second attempt, so these tests pin the two properties that make
 * warm-up safe: an outcome is recorded as soon as it happens, and it is never
 * overwritten afterwards.
 */
class EngineGateTest {

    @Test
    fun `fresh gate has no outcome`() {
        val gate = EngineGate<String>()

        assertFalse(gate.settled)
        assertNull(gate.ready)
        assertNull(gate.failed)
    }

    @Test
    fun `success settles the gate with the built value`() {
        val gate = EngineGate<String>()

        gate.succeed("runtime")

        assertTrue(gate.settled)
        assertEquals("runtime", gate.ready)
        assertNull(gate.failed)
    }

    @Test
    fun `failure settles the gate with its cause and no value`() {
        val gate = EngineGate<String>()
        val cause = IllegalStateException("engine refused")

        gate.fail(cause)

        assertTrue(gate.settled)
        assertSame(cause, gate.failed)
        assertNull(gate.ready)
    }

    @Test
    fun `failure is terminal so the engine is never built twice`() {
        val gate = EngineGate<String>()
        gate.fail(IllegalStateException("engine refused"))

        gate.succeed("late runtime")

        assertTrue(gate.settled)
        assertNull(gate.ready)
        assertEquals("engine refused", gate.failed?.message)
    }

    @Test
    fun `success is terminal so a late failure cannot discard a live runtime`() {
        val gate = EngineGate<String>()
        gate.succeed("runtime")

        gate.fail(IllegalStateException("late failure"))

        assertTrue(gate.settled)
        assertEquals("runtime", gate.ready)
        assertNull(gate.failed)
    }

    @Test
    fun `repeated success keeps the first value`() {
        val gate = EngineGate<String>()
        gate.succeed("first")

        gate.succeed("second")

        assertEquals("first", gate.ready)
    }

    @Test
    fun `repeated failure keeps the first cause`() {
        val gate = EngineGate<String>()
        val first = IllegalStateException("first")
        gate.fail(first)

        gate.fail(IllegalStateException("second"))

        assertSame(first, gate.failed)
    }
}