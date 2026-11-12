package com.parkwitness.core
import org.junit.Assert.*
import org.junit.Test
class VehicleGateTest {
    @Test fun unknownRestrictedMovingAndStaleFailClosed() {
        val gate = VehicleGate()
        assertFalse(gate.allowed(1000))
        gate.parked = true; gate.observedAt = 1000
        assertFalse(gate.allowed(1001))
        gate.uxAllowed = true
        assertTrue(gate.allowed(1001))
        assertFalse(gate.allowed(6001))
        assertFalse(gate.allowed(999))
        gate.parked = false
        assertFalse(gate.allowed(1001))
        gate.reset(); assertFalse(gate.allowed(1001))
    }
}
