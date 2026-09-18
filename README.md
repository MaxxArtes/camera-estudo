# Câmera Estudo

App de câmera em Kotlin + Jetpack Compose + CameraX, feito do zero para estudo.

## O que faz
- Desenho no estilo do app de câmera do iPhone: barra de cima (flash, gaveta, ajustes), visualização
  com **grade** e **nível de bolha** (acelerômetro), **chips de zoom** 0,5x / 1x / 2x, linha de modos,
  miniatura, obturador e trocar câmera.
- Modos na linha: **Vídeo** (com áudio), **Foto**, **Retrato** (bokeh nativo via Extensions ou pessoa x
  fundo pelo ML Kit, `Retrato.kt`), **Pro** (EV, ISO, tempo de exposição, foco manual e balanço de
  branco pelo Camera2 Interop) e **Documento** (scanner próprio, na nossa tela: normaliza a iluminação,
  acha a folha por dois caminhos, mancha clara ou região fechada por bordas, escolhe pelo placar,
  corta, endireita a perspectiva e realça tirando sombras, `Documento.kt`; ideias estudadas no
  FairScan, OpenScan e OpenNoteScanner, sem copiar código nem OpenCV). **Mais** abre **Lenta** (vídeo esticado 4x sem recodificar,
  `Lenta.kt`) e **Macro** (escolhe a lente traseira que foca mais perto e trava o foco no mínimo) e **Tela**
  (scanner de monitor/celular: retângulo iluminado, sem flash, antibanding, recorte com encaixe
  16:9/16:10/4:3, redução de moiré por reamostragem, realce suave que respeita fundo escuro).
- Documento e Tela têm **detecção ao vivo**: o fluxo de análise do CameraX (plano Y, ≤ 640 px, a cada 150 ms) roda o mesmo
  detector da foto e desenha o quadrilátero na prévia; se a foto não achar a folha, o editor abre com o quadro da prévia.
  Chips **Original / P&B / Aprimorado** escolhem o acabamento antes de fotografar, como na câmera da Xiaomi.
- Documento e Tela param antes de gravar: a prévia aparece com os **4 cantos como alças** (lupa 2,5x no canto
  arrastado, só aceita quadrilátero convexo), e o usuário escolhe **Usar**, **Sem recorte** ou **Descartar**
  (`EditorQuad.kt`; desligável na gaveta, "Recorte: Automático"). Cada digitalização grava uma linha em
  `files/scanner.jsonl` (luz, candidato, quadro detectado x usado, quanto moveu); "Registro" na gaveta envia o arquivo
  (`RegistroScanner.kt`) — é a base para ajustar os limiares fora do app.
- Gaveta de ajustes: flash (desligado/auto/ligado), **timer** (3 s / 10 s), **proporção** 4:3 ou 16:9,
  grade, nível, **HDR** e **Rajada** (`Fusao.kt`, Kotlin puro, medido em Python antes de codar):
  - HDR (Foto): o 1º quadro (0 EV) decide. Cena clara: mais 2 capturas a −2 / +2 EV, alinhadas por MTB (Ward 2003)
    e fundidas por exposure fusion (Mertens 2007) na luminância, 6 níveis, contraste em luminância suavizada e
    peso menor para o quadro mais ruidoso. Cena escura (luminância média < 70): vira **Noite**, mais 3 quadros
    iguais fundidos como rajada e sombras levantadas (gama 0,7). Medido em cena noturna sintética: bracket
    saía mais ruidoso que a foto simples; a rajada sai menos ruidosa.
    "Olho" (16/09, medido 200 ms por quadro no aparelho do dono): penumbra = 6 quadros; escuro fundo (luminância < 35) =
    8 quadros, soma 2x2 (decodifica na metade do lado, 4x mais luz por pixel) e dessaturação de 30%, como os bastonetes.
    A extensão do fabricante é desligada durante rajada/HDR: com ela cada quadro levou 1,8 a 2,3 s em vez de 0,2 s.
    O bracket só entra quando o 1º quadro tem ≥ 2% de estouro ou ≥ 10% de sombra fechada; cena comportada vira rajada.
    Quando entra, o resultado fica ancorado no ev0: mesmo brilho médio (ganho 0,85 a 1,25) e saturação por pixel até 1,1x
    (medido 16/09 num auditório: o bracket antigo clareou 50% e saturou 43% sem nada a recuperar). Sem esticamento; 7 níveis.
    Depois de qualquer fusão (fora do scanner) entra uma máscara de nitidez só na luminância (`Fusao.nitidezLeve`, q 3,0, medida na selfie do dono):
    a selfie fundida saía limpa mas macia (nitidez 0,0004 contra 0,0019 da câmera da Xiaomi). As fotos montadas pelo app
    ganham EXIF (fabricante, modelo, "Camera Estudo <versão> (<sequência>)", data) via `Fotos.gravaExif`.
  - Rajada (Foto, Pro, Documento, Tela, Macro): 4 capturas iguais; referência = quadro mais nítido; alinhamento
    MTB + refino por ladrilho (128 px, ±2 px); merge robusto com peso exp(−(d/τ)²), τ = 2,5 σ (σ pela mediana da
    diferença entre quadros). No scanner a fusão acontece DEPOIS do recorte, com as folhas já retificadas.
    Medido em rajada sintética: +3 a +4 dB sobre 1 quadro; média simples piorou 5 dB.
  **Resolução** escolhe o lado maior da imagem fundida: 1300 px (rápida), 2000 px (padrão) ou 2600 px (alta, teto com todos
  os quadros em memória, ~48 B/px). Resolução cheia do sensor exige fundir um quadro por vez (não feito).
- **Embelezador e Filtros** (`Acabamento.kt`, botão de varinha no canto da prévia, como na câmera da Xiaomi): filtros são
  matrizes de cor 4x5, a mesma matriz colore a miniatura ao vivo e a foto gravada (Original, Vívido, Natural, Quente, Frio,
  Cinema, Positivo, P&B, Sépia); embelezador 0 a 100 = suavização de pele que preserva bordas (máscara pessoa x tom de pele
  x não-borda, borrão por imagem integral). Aplicados depois da captura em Foto, Pro, Macro, Retrato e nas fusões; não no scanner.
- **Auto-máscaras** (`AutoMascaras.kt`, gaveta, ligadas por padrão): as máscaras do Lightroom, automáticas. Céu (fora da
  pessoa, ligado à borda de cima, azulado ou claro) recebe compressão de altas luzes e 12% de cor; fundo recebe um radial
  suave de até 22% nas bordas centrado no rosto; rosto recebe clareza local de 10%; olhos, nitidez pequena e 3% de brilho.
  Telemetria `auto_mascaras` diz o que agiu. Editor manual (pincel) fica para um app de galeria futuro.
- **Modelos de visão** (17/09, pedido do dono): `Segmentos.kt` = segmentador multiclasse de pessoa do MediaPipe em TFLite puro
  (256x256 → fundo, cabelo, pele do corpo, pele do rosto, roupa, acessórios; Apache): alfa do retrato com cabelo, pele do
  embelezador, pessoa das auto-máscaras. `Assunto.kt` = Mobile Object Localizer do Google (192x192, caixas sem classe; Apache):
  centro do radial e retrato de objeto quando não há pessoa. `Yolo.kt` = YOLOv8n-seg (Ultralytics, **AGPL-3.0**; TFLite float16
  640 de surendramaran/YOLO): máscara de instância para retrato de pet/objeto e etiquetas de cena na telemetria (`cena`).
  Pós-processamento próprio (NMS, sigmoid(coefs·protótipos)). MobileSAM/NanoSAM ficam para o editor de galeria (precisam de toque).
- **Rostos e Pessoas** (`Rostos.kt`, `Pessoas.kt`, `TelaPessoas.kt`): detecção de rosto do ML Kit escolhe a referência da
  rajada com olhos abertos; embedding facial no aparelho (MobileFaceNet TFLite em `assets/`, BSD-3, 112x112 → 192 números)
  cadastra pessoas sozinho (casa ≥ 0,65, nova < 0,50, faixa do meio ignorada; medido em medicao/rosto_embedding.py),
  aprende a cor da pele de cada uma em cena neutra e corrige 40% da diferença de croma no rosto nas fotos seguintes.
  Tudo em files/ do app; a telemetria só recebe contagens e desvios. Gaveta > Pessoas: lista, renomeia, apaga, liga/desliga.
- **Telemetria** (`Telemetria.kt`, desligável na gaveta): cada foto, sequência (rajada/HDR/noite, tempo por quadro e
  da fusão), scanner (detectar, aplicar, quadro detectado x usado), retrato por software, troca de câmera e erro
  vira uma linha JSON com aparelho, versão e memória, enviada em lote ao coletor da bancada (token injetado pelo
  CI; build local sem token não envia). Nunca envia imagem. É o que alimenta as melhorias medidas.
- Zoom por pinça, **deslizar para o lado troca o modo** e **deslizar para cima abre os ajustes**.
- **Retrato**: o app pergunta ao aparelho se a extensão aceita intensidade (CameraX 1.4, `isExtensionStrengthAvailable`:
  Android 14+ e apoio do fabricante). Se aceita, régua de desfoque 0 a 100 no bokeh nativo (e a mesma régua na Foto com
  extensão HDR/Noite ligada); se não, avisa o Android do aparelho e oferece "Usar o nosso", o retrato por software com
  régua de **desfoque 1 a 10** (`Retrato.aplicar(intensidade)`). O evento `camera` da telemetria registra `forca_disponivel`.
- **Volume** (cima ou baixo) dispara a foto/vídeo (`Atalhos.kt` + `onKeyDown`). O **voltar do sistema** navega dentro do app: fecha a foto ampliada, cancela a seleção, volta da galeria à câmera e fecha o editor de cantos (grava sem recorte).
- **Foco por toque** com anel, **toque longo trava AE/AF** (o aparelho para de refocar e remedir) e uma régua de luz ao lado do anel
  (arrastar na vertical muda a compensação de exposição). Toque simples solta a trava.
- Gaveta: **Aparelho** liga as extensões do fabricante pelo CameraX (Auto, HDR, Noite, Retoque, o que o aparelho expuser) na Foto;
  **Qualidade** Máxima deixa o HAL fazer o próprio multi-quadro (CAPTURE_MODE_MAXIMIZE_QUALITY), Rápida usa latência mínima
  (a rajada e o HDR nossos usam sempre a rápida). Todo processamento demorado mostra um cartão com anel de progresso.
- Fotos em **Imagens/CameraEstudo**, vídeos em **Filmes/CameraEstudo** (aparecem na galeria do celular).
- Galeria própria: grade de fotos e vídeos, tela cheia com **Compartilhar, Editar, Informações e
  Favorito**, apagar; vídeo abre no player do sistema.

## Como o código está organizado (`app/src/main/java/br/maxymus/cameraestudo`)
| Arquivo | Papel |
|---|---|
| `MainActivity.kt` | permissão de câmera e navegação entre as duas telas |
| `CameraScreen.kt` | CameraX: `Preview` + `ImageCapture` (foto) ou `Preview` + `VideoCapture` (vídeo); gestos; grade; nível; gaveta de ajustes |
| `GaleriaScreen.kt` (grade, seleção múltipla com selecionar tudo / apagar / PDF, tela cheia com zoom por pinça e toque duplo) | grade de fotos e vídeos, tela cheia com compartilhar/editar/informações/favorito, apagar |
| `Fotos.kt` | MediaStore: onde salvar, listar, apagar; favoritos locais |
| `Nivel.kt` | acelerômetro → ângulo do nível de bolha |
| `Atualizador.kt` | lê `releases.json`, compara a versão, baixa e instala a nova |
| `Retrato.kt` | retrato por software: máscara do ML Kit + fundo desfocado |
| `Lenta.kt` | estica os tempos dos quadros do vídeo (câmera lenta de estudo) |
| `Documento.kt` | detectar (retinex, Otsu, bordas e LINHAS por Hough, placar do quadrilátero) e aplicar (setPolyToPoly, realce com fundo por convolução normalizada) |
| `EditorQuad.kt` | conferência dos cantos: alças, lupa, convexidade, Usar / Sem recorte / Descartar |
| `Fusao.kt` | MTB, refino por ladrilho, merge robusto de rajada, Mertens em luminância |
| `Telemetria.kt` | fila JSONL local + envio em lote (20 s ou 3 s após evento) com Bearer token do CI |
| `Acabamento.kt` | filtros por matriz de cor e embelezador (surface blur com máscara de pele e pessoa) |
| `RegistroScanner.kt` | uma linha JSON por digitalização; compartilhar pelo FileProvider |
| `Pdf.kt` | PDF de várias páginas com o PdfDocument do Android (galeria: selecionar → PDF) |

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

## IA online
Na foto aberta da galeria, “Melhorar (IA online)” envia somente a foto autorizada naquele momento ao servidor do Camera Estudo e ao SnapEdit (SilverAI). O envio usa JPEG reduzido (lado maior até 1200 px), sem EXIF ou GPS; informações visíveis na própria imagem continuam presentes. A verificação de rostos é local e apresenta um aviso, sem bloquear. Cada foto exige consentimento, e mudanças na política exigem novo aceite. Há comparação Original/Melhorada antes de salvar uma nova cópia com sufixo `_ia`, nas dimensões orientadas da original, em Imagens/CameraEstudo. “Desfazer” apaga apenas essa cópia. A IA pode alterar detalhes e rostos. Não há fila offline nem reenvio automático; cancelar fecha a conexão, mas um pedido já recebido pode consumir cota. O botão exige internet validada e só aparece em builds com `MELHORAR_TOKEN` configurado no CI. A política está em https://pocketlm.maxymus.dev.br/camera/privacidade.

A IA online é independente da telemetria: a telemetria nunca envia imagens, e desligá-la não substitui o consentimento por foto da IA online. O token incluído no APK não é segredo; a cota e os limites são controlados pelo servidor.
