package com.parkwitness.app
import android.app.Application

interface VehicleSource {
    val label: String
    fun start(update: (Boolean, String) -> Unit)
    fun stop()
}
