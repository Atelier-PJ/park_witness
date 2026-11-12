package com.parkwitness.core

/** Unknown, stale and restricted signals always disable detailed interaction. */
class VehicleGate {
    var parked: Boolean? = null
    var uxAllowed = false
    var observedAt: Long? = null
    fun allowed(now: Long): Boolean = parked == true && uxAllowed && observedAt?.let { now - it in 0..5000 } == true
    fun reset() { parked = null; uxAllowed = false; observedAt = null }
}
