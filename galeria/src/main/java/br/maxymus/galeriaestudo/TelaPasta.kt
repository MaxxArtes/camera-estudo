package br.maxymus.galeriaestudo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Álbum automático por origem do arquivo (ex.: WhatsApp). Só lista; nada é movido nem copiado. */
@Composable
fun TelaPasta(titulo: String, midias: List<Midia>, voltar: () -> Unit, aoAbrir: (List<Midia>, Int) -> Unit) {
    val legenda = if (titulo == Midias.PASTA_CAPTURAS) "guardados pelo sistema" else "recebidos no aparelho"
    val estado = rememberLazyListState()
    Column(Modifier.fillMaxSize().background(Tema.Fundo)) {
        BarraSuperior(titulo, voltar = voltar) {}
        if (midias.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Nada aqui ainda.", color = Tema.Texto2, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
        } else {
            GradePorDia(midias, estado, cabecalho = {
                Text("${Midias.numero(midias.size)} ${if (midias.size == 1) "item" else "itens"} · $legenda",
                    color = Tema.Texto2, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }) { i -> aoAbrir(midias, i) }
        }
    }
}
