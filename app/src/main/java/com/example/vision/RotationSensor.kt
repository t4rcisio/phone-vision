package com.example.vision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun rememberRotation(enabled: Boolean): List<Float>? {
    val context = LocalContext.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    var rotation by remember { mutableStateOf<List<Float>?>(null) }
    DisposableEffect(enabled, owner) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        var gate = RotationGate()
        var movingUntil = 0L
        val listener =
            object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                        val speedSquared = event.values.take(3).sumOf { (it * it).toDouble() }
                        if (speedSquared > .025 * .025) movingUntil = event.timestamp + 200_000_000L
                        return
                    }
                    val raw = FloatArray(9)
                    val mapped = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(raw, event.values)
                    val axes =
                        when (view.display?.rotation) {
                            Surface.ROTATION_90 ->
                                SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                            Surface.ROTATION_180 ->
                                SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                            Surface.ROTATION_270 ->
                                SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                        }
                    SensorManager.remapCoordinateSystem(raw, axes.first, axes.second, mapped)
                    rotation =
                        gate.update(mapped.toList(), gyro == null || event.timestamp < movingUntil)
                }
            }
        fun start() {
            gate = RotationGate()
            movingUntil = 0L
            if (enabled && sensor != null) {
                gyro?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) start()
            if (event == Lifecycle.Event.ON_PAUSE) {
                manager.unregisterListener(listener)
                rotation = null
            }
        }
        rotation = null
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            manager.unregisterListener(listener)
            rotation = null
        }
    }
    return rotation
}
