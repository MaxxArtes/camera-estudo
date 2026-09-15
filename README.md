# Câmera Estudo

App de câmera em Kotlin + Jetpack Compose + CameraX, feito do zero para estudo.

## O que faz
- Desenho no estilo do app de câmera do iPhone: barra de cima (flash, gaveta, ajustes), visualização
  com **grade** e **nível de bolha** (acelerômetro), **chips de zoom** 0,5x / 1x / 2x, linha de modos,
  miniatura, obturador e trocar câmera.
- Modos **Foto** e **Vídeo** funcionando (vídeo com áudio, se a permissão for dada); Lenta, Retrato e
  Mais marcados como "em breve".
- Gaveta de ajustes: flash (desligado/auto/ligado), **timer** (3 s / 10 s), **proporção** 4:3 ou 16:9,
  grade, nível; HDR e filtros são marcadores para estudo futuro.
- Zoom por pinça e foco/exposição por toque.
- Fotos em **Imagens/CameraEstudo**, vídeos em **Filmes/CameraEstudo** (aparecem na galeria do celular).
- Galeria própria: grade de fotos e vídeos, tela cheia com **Compartilhar, Editar, Informações e
  Favorito**, apagar; vídeo abre no player do sistema.

## Como o código está organizado (`app/src/main/java/br/maxymus/cameraestudo`)
| Arquivo | Papel |
|---|---|
| `MainActivity.kt` | permissão de câmera e navegação entre as duas telas |
| `CameraScreen.kt` | CameraX: `Preview` + `ImageCapture` (foto) ou `Preview` + `VideoCapture` (vídeo); gestos; grade; nível; gaveta de ajustes |
| `GaleriaScreen.kt` | grade de fotos e vídeos, tela cheia com compartilhar/editar/informações/favorito, apagar |
| `Fotos.kt` | MediaStore: onde salvar, listar, apagar; favoritos locais |
| `Nivel.kt` | acelerômetro → ângulo do nível de bolha |
| `Atualizador.kt` | lê `releases.json`, compara a versão, baixa e instala a nova |

CameraX é a camada do Google em cima do Camera2: você declara os "casos de uso" e ele cuida de
abrir, configurar e fechar a câmera. Para estudar o Camera2 puro depois, o lugar de trocar é só
o `CameraScreen.kt`.

## Build e instalação
Sem Android Studio: cada push em `main` roda o GitHub Actions, que compila e publica o APK de
depuração no release **"ultimo"** deste repositório, com link fixo:

- Link fixo no R2 (rápido, igual ao canal do pitanga): `https://pub-520120b0b03b4d3f8c94c5c9ba10d569.r2.dev/camera-estudo/camera-estudo.apk`
- Link fixo no GitHub: `https://github.com/MaxxArtes/camera-estudo/releases/download/ultimo/camera-estudo.apk`
- Histórico: `releases.json` na raiz (versão, build, links por versão, o que mudou) e as releases numeradas.

Baixe no celular, permita "instalar de fontes desconhecidas" para o navegador e instale.
Com Android Studio: abrir a pasta e rodar; `minSdk 26`, `compileSdk 34`, Gradle 8.9, JDK 17.

## Atualização dentro do app
Ao abrir, o app lê `releases.json` no R2; se o `versionCode` de lá for maior que o instalado, mostra a
faixa "Versão X disponível → Atualizar" (também na gaveta de ajustes, item "Atualizar", que serve para
conferir à mão). Atualizar baixa o APK pelo DownloadManager e abre o instalador do sistema; como a
assinatura é fixa (keystore nos secrets do repo), instala por cima. Código em `Atualizador.kt`.

## Próximos passos de estudo (sugestões)
1. Trocar `ImageCapture` por `ImageAnalysis` e desenhar algo sobre a imagem ao vivo.
2. Gravar vídeo com `VideoCapture`.
3. Reescrever a tela com Camera2 direto e comparar o tamanho do código.
