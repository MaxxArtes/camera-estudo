package br.maxymus.cameraestudo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.atan2

/**
 * Nível de bolha: ângulo de inclinação lateral do aparelho, em graus, pelo acelerômetro.
 * 0 = celular perfeitamente na horizontal (segurado em pé). Suavizado para não tremer.
 */
@Composable
fun lembrarInclinacao(ativo: Boolean): State<Float> {
    val contexto = LocalContext.current
    val angulo = remember { mutableFloatStateOf(0f) }
    DisposableEffect(ativo) {
        if (!ativo) return@DisposableEffect onDispose { }
        val gerente = contexto.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = gerente.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val ouvinte = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // em retrato: x aponta para a direita do aparelho, y para cima
                val graus = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
                angulo.floatValue = angulo.floatValue * 0.8f + graus * 0.2f
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) gerente.registerListener(ouvinte, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { gerente.unregisterListener(ouvinte) }
    }
    return angulo
}
