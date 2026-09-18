package br.maxymus.cameraestudo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

object Rede {
    fun validada(ctx: Context): Boolean {
        val gerente = ctx.getSystemService(ConnectivityManager::class.java)
        val capacidades = gerente.getNetworkCapabilities(gerente.activeNetwork)
        return capacidades?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

@Composable
fun lembrarRedeValidada(): State<Boolean> {
    val ctx = LocalContext.current.applicationContext
    val estado = remember { mutableStateOf(Rede.validada(ctx)) }
    DisposableEffect(ctx) {
        val gerente = ctx.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { estado.value = Rede.validada(ctx) }
            override fun onLost(network: Network) { estado.value = Rede.validada(ctx) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { estado.value = Rede.validada(ctx) }
        }
        gerente.registerDefaultNetworkCallback(callback)
        estado.value = Rede.validada(ctx)
        onDispose { gerente.unregisterNetworkCallback(callback) }
    }
    return estado
}
