package com.parkwitness.app
import android.app.Application

fun vehicleSource(app: Application): VehicleSource = object : VehicleSource {
    override val label = "DEMO · 모의 주차 상태 · 차량 SDK 미사용"
    override fun start(update: (Boolean, String) -> Unit) = update(true, "모의 P / 모의 UX 허용 · 실제 차량 상태 아님")
    override fun stop() {}
}
