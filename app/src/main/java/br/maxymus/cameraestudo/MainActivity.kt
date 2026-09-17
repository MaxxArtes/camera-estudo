package br.maxymus.cameraestudo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Ponto de entrada. Só cuida da permissão de câmera e decide qual tela mostrar:
 * pedido de permissão → câmera → galeria (voltar retorna à câmera).
 */
class MainActivity : ComponentActivity() {
    // volume para cima ou para baixo = disparar (só enquanto a tela da câmera está registrada em Atalhos)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && event?.repeatCount == 0) {
            val f = Atalhos.aoDisparar ?: return super.onKeyDown(keyCode, event)
            f(); return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && Atalhos.aoDisparar != null) true else super.onKeyUp(keyCode, event)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Telemetria.iniciar(this)
        Pessoas.iniciar(this)
        Acabamento.app = applicationContext
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFF5A5F), secondary = Color(0xFF22C55E), background = Color(0xFF0E0E12), surface = Color(0xFF16161B))) {
                App()
            }
        }
    }
}

private enum class Tela { Camera, Galeria }

@Composable
private fun App() {
    val contexto = LocalContext.current
    var temPermissao by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(contexto, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var negouDeVez by remember { mutableStateOf(false) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedida ->
        temPermissao = concedida
        if (!concedida) negouDeVez = true
    }
    var tela by remember { mutableStateOf(Tela.Camera) }

    if (!temPermissao) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Este app precisa da câmera para funcionar.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )
            if (negouDeVez) {
                Text(
                    "\nVocê negou a permissão. Libere em Configurações do Android → Apps → Câmera Estudo → Permissões.",
                    textAlign = TextAlign.Center
                )
            }
            Button(onClick = { pedir.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 24.dp)) {
                Text("Permitir câmera")
            }
        }
        return
    }

    when (tela) {
        Tela.Camera -> CameraScreen(abrirGaleria = { tela = Tela.Galeria })
        Tela.Galeria -> GaleriaScreen(voltar = { tela = Tela.Camera })
    }
}
