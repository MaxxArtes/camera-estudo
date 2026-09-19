package br.maxymus.galeriaestudo

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
fun TelaFotos(midias: List<Midia>, estado: LazyListState, parcial: Boolean, aoAbrir: (Int) -> Unit, aoAlterarSelecao: () -> Unit) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var sobre by remember { mutableStateOf(false) }
    var novaVersao by remember { mutableStateOf<Atualizador.Versao?>(null) }
    LaunchedEffect(Unit) {
        val v = Atualizador.consultar(); val inst = Atualizador.versaoInstalada(ctx)
        if (v != null && v.codigo > inst.second) novaVersao = v
    }
    Column(Modifier.fillMaxSize().background(Tema.Fundo)) {
        BarraSuperior("Fotos") {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = Tema.Texto) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Procurar atualização") }, onClick = {
                        menu = false
                        escopo.launch {
                            val v = Atualizador.consultar(); val inst = Atualizador.versaoInstalada(ctx)
                            when {
                                v == null -> Toast.makeText(ctx, "Não consegui consultar. Está sem internet?", Toast.LENGTH_SHORT).show()
                                v.codigo > inst.second -> novaVersao = v
                                else -> Toast.makeText(ctx, "Você já está na versão mais recente (${inst.first}).", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                    DropdownMenuItem(text = { Text("Sobre") }, onClick = { menu = false; sobre = true })
                }
            }
        }
        novaVersao?.let { v -> FaixaAviso("Versão ${v.nome} disponível", "Atualizar") { Atualizador.baixarEInstalar(ctx, v); novaVersao = null } }
        if (parcial) FaixaAviso("Mostrando apenas os itens permitidos", "Alterar seleção", aoAlterarSelecao)
        if (midias.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhuma foto encontrada.", color = Tema.Texto2) }
        else GradePorDia(midias, estado, null, aoAbrir)
    }
    if (sobre) AlertDialog(onDismissRequest = { sobre = false }, confirmButton = { TextButton(onClick = { sobre = false }) { Text("Fechar") } },
        title = { Text("Galeria Estudo ${Atualizador.versaoInstalada(ctx).first}") },
        text = { Text("Fotos por data e pessoas por rosto, tudo no aparelho. Nenhuma imagem sai do celular.\n\nRostos: ML Kit (detecção) e MobileFaceNet (identidade, licença BSD-3).") })
}
