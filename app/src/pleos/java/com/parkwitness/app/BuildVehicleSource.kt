package com.parkwitness.app

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Gear
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.app.Application
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.parkwitness.core.VehicleGate

fun vehicleSource(app: Application): VehicleSource = PleosSource(app)

/** Read-only typed SDK call; compile success does not imply a verified runtime signal. */
class PleosSource(private val app: Application) : VehicleSource {
    override val label = "PLEOS · Vehicle SDK 2.3.2 · 실차 신호 검증 전"
    private val handler = Handler(Looper.getMainLooper())
    private val gate = VehicleGate()
    private var vehicle: Vehicle? = null
    private var gear: Gear? = null
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var update: ((Boolean, String) -> Unit)? = null
    private var active = false
    private var epoch = 0L
    private var reading = false
    private var sequence = 0L
    private fun publish() { update?.invoke(gate.allowed(SystemClock.elapsedRealtime()), "SDK P=${gate.parked ?: "미확인"} · UX=${if(gate.uxAllowed) "허용" else "제한/미확인"}") }
    override fun start(update: (Boolean, String) -> Unit) {
        stop(); this.update=update; active=true; val token=++epoch
        publish()
        try {
            vehicle=Vehicle(app).also { it.initialize() };gear=vehicle!!.getGear()
            car=Car.createCar(app,handler,Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                handler.post {
                    if (active && epoch==token) {
                        if (!ready) { manager=null;gate.reset();publish() }
                        else try {
                            manager=connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                            manager!!.registerListener { ux -> handler.post { if(active && epoch==token) acceptUx(ux) } }
                            acceptUx(manager!!.currentCarUxRestrictions)
                        } catch (_: Exception) { gate.reset();publish() }
                    }
                }
            }
            handler.post(poll)
        } catch (_: Exception) { gate.reset();publish() }
    }
    private fun acceptUx(ux: CarUxRestrictions?) {
        gate.uxAllowed=ux!=null && !ux.isRequiresDistractionOptimization && ux.activeRestrictions==CarUxRestrictions.UX_RESTRICTIONS_BASELINE
        publish()
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            try { acceptUx(manager?.currentCarUxRestrictions) } catch (_: Exception) { gate.uxAllowed=false }
            publish() // In-flight or lost callbacks cannot keep a stale P value usable.
            if (!reading) {
                reading=true
                val token=epoch;val id=++sequence;val at=SystemClock.elapsedRealtime()
                handler.postDelayed({ if(active && epoch==token && sequence==id && reading) { reading=false;sequence++;gate.reset();publish() } },4000)
                try {
                    gear?.getCurrentGearSelectionState(onComplete={ value -> handler.post {
                        if(active && epoch==token && id==sequence) {
                            reading=false;gate.parked=when(value) { null, VehicleGear.GEAR_UNKNOWN -> null; VehicleGear.GEAR_PARK -> true; else -> false }
                            gate.observedAt=at;publish()
                        }
                    } },onFailed={ handler.post { if(active && epoch==token && id==sequence) { reading=false;gate.reset();publish() } } })
                } catch (_: Exception) { reading=false;gate.reset();publish() }
            }
            handler.postDelayed(this,1000)
        }
    }
    override fun stop() {
        active=false;epoch++;sequence++;reading=false;handler.removeCallbacksAndMessages(null)
        gate.reset();update?.invoke(false,"백그라운드 · 차량 상태 미확인")
        try { manager?.unregisterListener() } catch (_: Exception) {}
        try { car?.disconnect() } catch (_: Exception) {}
        try { vehicle?.release() } catch (_: Exception) {}
        manager=null;car=null;vehicle=null;gear=null
    }
}
