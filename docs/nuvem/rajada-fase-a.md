# Instrução para sessão na nuvem: "Rajada de teste" (fase A) no app da câmera

Você é o executor. O orquestrador (outra sessão, que roda no servidor do dono) já decidiu o desenho. Trabalhe SÓ no
módulo `app` (pacote br.maxymus.cameraestudo). Não mexa em `galeria`, `tradutor` nem `.github/`.

Regras do repositório:
- Crie o ramo `rajada-teste`, faça commits pequenos nele e abra UM pull request para `main` no fim. NUNCA faça push
  para `main`: o CI publica a câmera para o celular do dono a cada push em main.
- Português do Brasil nos textos e comentários; sem emoji; comentário só onde o porquê não é óbvio.
- Mantenha minSdk 26 e compileSdk 35, e as versões de `settings.gradle.kts`.
- Telemetria só com números e códigos, no esquema fechado que já existe para o teste de dois sensores
  (DoisSensores.kt). Nada de texto livre, mensagem de exceção ou nome de arquivo.
- Fotos do dono são privadas: ficam no armazenamento privado do app e só saem por compartilhamento explícito dele,
  depois de uma tela de revisão. Siga o modelo do teste de dois sensores (DoisSensoresTela.kt, FileProvider só com a
  raiz específica, caminhos.xml).

## O aparelho (ficha técnica, 05/10)
POCO X8 Pro Max: câmera principal de 50 MP (8165x6124) com sensor 1/1,95", f/1.5 e ESTABILIZAÇÃO ÓPTICA; Dimensity
9500s; 12 GB de RAM. No modo padrão o Camera2 entrega 4096x3072 (12,6 MP, pixels combinados 4 em 4). Os 50 MP exigem
o modo de resolução máxima (SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION), que NÃO entra na rajada desta fase: só registre no
meta.json se a câmera declara esse modo e quais tamanhos ele oferece.

## Objetivo da fase A
Medir, no aparelho real (POCO X8 Pro Max, sensor principal 4096x3072), o que uma rajada entrega antes de escrever
qualquer fusão no app. Os quadros vão para um protótipo em Python na bancada do dono.

## O que fazer
1. Item novo na gaveta, ao lado de "Sensores": "Rajada de teste". Ele abre uma explicação curta: "Apoie o celular ou
   segure firme, aponte para uma estante ou uma parede com textura, em luz baixa. São 8 fotos seguidas e uma normal.
   As fotos ficam só no aparelho até você compartilhar."
2. Captura, por Camera2 interop sobre a câmera lógica "0", quando o CameraX estiver ligado no modo foto:
   - Espere AE e AF convergirem e TRAVE os dois (CONTROL_AE_LOCK e AF em modo fixo depois da convergência).
   - `captureBurst` de 8 pedidos YUV_420_888 na MAIOR resolução YUV disponível. Se o aparelho não aceitar a rajada
     nessa resolução, caia para a maior que aceitar e registre.
   - Exposição e ISO iguais nos 8 quadros, os do AE travado. Não force exposição manual nesta fase.
   - Grave por quadro: SENSOR_TIMESTAMP, SENSOR_EXPOSURE_TIME, SENSOR_SENSITIVITY, SENSOR_FRAME_DURATION,
     LENS_OPTICAL_STABILIZATION_MODE, NOISE_REDUCTION_MODE, EDGE_MODE e a resolução.
   - Logo depois, tire UMA foto normal pelo caminho de foto que o app já usa (JPEG), para comparar.
   - Libere a câmera e devolva o CameraX como o teste de dois sensores faz, com o mesmo cuidado de ciclo de vida:
     cancelar ao sair do primeiro plano, fechar a câmera, CameraX religado.
3. Gravação, em `files/rajada/<data>_<id>/`:
   - para cada quadro, o plano Y inteiro como PNG em escala de cinza 8 bits sem perda (`y_0.png` a `y_7.png`) e os
     planos U e V reduzidos (`uv_0.png` ...);
   - `meta.json` com os metadados do passo 2, a ordem e o fps real medido pelos timestamps;
   - `normal.jpg`.
   Grave numa pasta temporária e mova no fim, como o par estéreo faz (nada parcial visível).
4. Resultado na tela: "8 quadros em X ms (Y fps)", a resolução e a exposição/ISO. Botões "Ver e compartilhar" (tela de
   revisão com miniaturas e a lista dos arquivos, e só então o compartilhamento de um .zip da pasta, via FileProvider
   com raiz específica), "Apagar esta rajada" e "Fechar".
5. Telemetria: evento `rajada_teste` com quadros, ms_total, fps, largura, altura, exp_ns, iso, ois, nr e resultado
   (ok, recusou_resolucao, perdeu_camera, cancelado, erro com a classe da exceção).
6. Versão: `app/build.gradle.kts` versionCode 80, versionName "0.80".

## Como provar
- Rode a compilação do módulo app (`./gradlew :app:compileReleaseKotlin`, instalando o Android SDK de linha de comando
  na sessão, se preciso). Se não conseguir compilar, diga isso no PR.
- No PR: o que mudou, arquivo por arquivo; o que só dá para validar no aparelho; e os riscos.
