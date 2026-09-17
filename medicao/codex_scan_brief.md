Contexto: app Android de camera "de estudo" em Kotlin puro (sem OpenCV/GPU/JNI), repo em /opt/camera-estudo.
Scanner de documento em app/src/main/java/br/maxymus/cameraestudo/Documento.kt (leia inteiro) com apoio de
Fusao.kt (nitidezLeve, decodifica) e EditorQuad.kt. Pipeline: foto JPEG do CameraX -> detecta quadrilatero
(Otsu normalizado/cru, regiao lisa, Hough) a 640 px -> usuario confere cantos -> warp (setPolyToPoly) para
o lado maior escolhido (1600/2400/3200 px; ate ontem 2000, e no modo Tela 1400) -> realce: retinex na luminancia
com fundo estimado so de pixels claros, esticamento por percentis, papel claro dessaturado -> nitidez leve
(mascara na luminancia, q 1,5) -> JPEG 92.

Duas imagens anexas do MESMO documento (DACTE, folha A4 com letra corpo 6-7):
  - nosso_tela_1400px.jpg: nossa saida (modo Tela, 1257x1400). O dono nao consegue ler a letra miuda.
  - xiaomi_scan.jpg: saida do modo Documentos da camera da Xiaomi (POCO X8 Pro Max), legivel.

Pergunta central: o que a Xiaomi faz que nos nao fazemos, e o que da para reproduzir em Kotlin puro em
< 2 s num celular medio (heap 512 MB)? Quero respostas concretas e ordenadas por ganho/custo:
1. Resolucao de trabalho e de saida (nos ja subimos para ate 3200 px na v0.51 de hoje): basta?
2. Interpolacao do warp (bilinear do Android) vs bicubica/Lanczos feita a mao: vale o custo?
3. Realce para TEXTO: o retinex + percentis e o certo, ou limiar adaptativo local (Sauvola/Wolf) em
   luminancia, com preservacao de tons, rende mais legibilidade? De um algoritmo passo a passo.
4. Nitidez: unsharp na luminancia (que temos) vs deconvolucao leve/USM com raio 0,7 px em 300 dpi.
5. Captura: MAXIMIZE_QUALITY do CameraX, foco por toque no texto, rajada + super-resolucao por
   deslocamento subpixel (temos alinhamento MTB + refino por ladrilho em Fusao.kt): quanto de ganho real
   para texto? A Xiaomi usa varios quadros?
6. O que voce mediria para provar o ganho antes de publicar (metrica de legibilidade sem OCR, ou com
   ML Kit Text Recognition como juiz — ele ja esta disponivel no app? nao esta; vale adicionar so como medidor?).
Critique tambem o que ja existe no Documento.kt se vir erro. Nao altere arquivos: so analise e proposta.
Responda em portugues, objetivo, com codigo Kotlin so onde for essencial.
