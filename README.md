# Câmera Estudo

App de câmera em Kotlin + Jetpack Compose + CameraX, feito do zero para estudo.

## O que faz
- Visualização ao vivo, foto com um toque, salva em **Imagens/CameraEstudo** (aparece na galeria do celular).
- Troca entre câmera traseira e frontal; flash desligado, automático e ligado.
- Zoom por pinça (mostra o fator) e foco/exposição por toque no ponto.
- Galeria própria: grade das fotos tiradas, tela cheia, compartilhar e apagar.
- Pede a permissão de câmera na primeira abertura e explica o que fazer se for negada.

## Como o código está organizado (`app/src/main/java/br/maxymus/cameraestudo`)
| Arquivo | Papel |
|---|---|
| `MainActivity.kt` | permissão de câmera e navegação entre as duas telas |
| `CameraScreen.kt` | CameraX: `Preview` + `ImageCapture` amarrados ao ciclo de vida; gestos de zoom e foco; captura |
| `GaleriaScreen.kt` | grade das fotos, tela cheia, compartilhar, apagar |
| `Fotos.kt` | MediaStore: onde salvar e como listar/apagar |

CameraX é a camada do Google em cima do Camera2: você declara os "casos de uso" e ele cuida de
abrir, configurar e fechar a câmera. Para estudar o Camera2 puro depois, o lugar de trocar é só
o `CameraScreen.kt`.

## Build e instalação
Sem Android Studio: cada push em `main` roda o GitHub Actions, que compila e publica o APK de
depuração no release **"ultimo"** deste repositório, com link fixo:

`https://github.com/MaxxArtes/camera-estudo/releases/download/ultimo/camera-estudo.apk`

Baixe no celular, permita "instalar de fontes desconhecidas" para o navegador e instale.
Com Android Studio: abrir a pasta e rodar; `minSdk 26`, `compileSdk 34`, Gradle 8.9, JDK 17.

## Próximos passos de estudo (sugestões)
1. Trocar `ImageCapture` por `ImageAnalysis` e desenhar algo sobre a imagem ao vivo.
2. Gravar vídeo com `VideoCapture`.
3. Reescrever a tela com Camera2 direto e comparar o tamanho do código.
