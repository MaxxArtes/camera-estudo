# Pesquisa: captura RAW (DNG) no Android — CameraX e Camera2

Data da pesquisa: 21/09/2026.

Contexto do app pesquisado: Kotlin, CameraX 1.4.2 (camera-core, camera-camera2,
camera-lifecycle, camera-view, camera-extensions), minSdk 26, targetSdk 34. Já usa
`ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR` e
`ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats`.

## Nota metodológica

Toda afirmação abaixo tem URL da fonte ao lado. O que não foi possível confirmar está
marcado literalmente como "não encontrado" — não foi completado com o que ficou de
treino sem confirmação nova.

A cota de `WebSearch` da sessão esgotou (200 de 200 buscas) no meio da pesquisa, então
parte do trabalho seguiu por leitura direta de URLs conhecidas (`WebFetch`, `curl`,
`gh`). As páginas `developer.android.com/reference/...` (referência de API) são
aplicações JavaScript que essas ferramentas não conseguem renderizar (só devolvem o menu
de navegação) — por isso a fonte primária usada para nomes exatos de classes, métodos e
constantes foi o código-fonte (AOSP via Gitiles/`android.googlesource.com`, e AndroidX
via GitHub, `github.com/androidx/androidx`), não a página de referência renderizada. As
notas de versão em `developer.android.com/jetpack/androidx/releases/camera` funcionaram
normalmente (não são SPA) e foram conferidas duas vezes, de forma independente.

## Resumo prático

- O app está em CameraX 1.4.2. A API de RAW só existe a partir do **1.5.0** — ou seja,
  hoje o app não tem acesso a ela e precisa de upgrade de versão antes de qualquer
  implementação.
- A API está **estável** (não é experimental/alpha) desde a própria versão 1.5.0
  (10/09/2025) e continua estável na versão atual.
- Detecção de suporte do aparelho é o mesmo padrão que o app já usa para Ultra HDR:
  `ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats`.
- Recomenda-se não usar CameraX abaixo de 1.5.0-beta02: havia um bug em que essa checagem
  de suporte reportava RAW como disponível em aparelhos que não tinham a capacidade de
  verdade.

## 1. Versão do CameraX, constantes exatas e estabilidade

- A captura RAW foi introduzida no CameraX **1.5.0-alpha03** (30/10/2024). Texto exato da
  nota de versão: "Add output format APIs for RAW and RAW + JPEG ImageCapture, the device
  capability check is exposed in ImageCaptureCapabilities#getSupportedOutputFormats. The
  OUTPUT_FORMAT_RAW is to capture RAW image, which is Adobe DNG format and
  OUTPUT_FORMAT_RAW_JPEG is to simultaneously capture RAW and JPEG images."
  Fonte: https://developer.android.com/jetpack/androidx/releases/camera

- Em **1.5.0-alpha04** (11/12/2024), `ImageCapture.Builder#setOutputFormat` e
  `ImageCaptureCapabilities#getSupportedOutputFormats` deixaram de ser experimentais e
  passaram a API estável. Texto exato: "Exposed ImageCapture.Builder#setOutputFormat and
  ImageCaptureCapabilities#getSupportedOutputFormats as stable APIs".
  Fonte: https://developer.android.com/jetpack/androidx/releases/camera

- RAW passou a fazer parte de uma versão **estável** do CameraX em **1.5.0**
  (10/09/2025), listada como funcionalidade principal do release: "Image Capture: Support
  for DNG (RAW) and JPEG + DNG (RAW) formats in ImageCapture. Check
  ImageCaptureCapabilities(CameraInfo).getSupportedOutputFormats() for RAW support. Use
  overloaded takePicture APIs with multiple OutputFileOptions for RAW+DNG capture."
  Fonte: https://developer.android.com/jetpack/androidx/releases/camera

- Status hoje (21/09/2026): **estável**. A tabela de versões da mesma página lista, entre
  outras, `1.6.2` (estável, 26/08/2026), `1.5.3` (estável, 28/01/2026) e `1.7.0-alpha03`
  (alpha, 12/08/2026) — ou seja, RAW está estável há mais de um ano e continua estável na
  versão atual, sem indício de regressão para alpha/beta.
  Fonte: https://developer.android.com/jetpack/androidx/releases/camera (conferida duas
  vezes, em buscas independentes, com o mesmo resultado)

- Nomes exatos das constantes, confirmados diretamente no código-fonte do
  `camera-core` (arquivo `ImageCapture.java`, linhas 328-337):

  ```java
  /**
   * Captures raw images in the {@link ImageFormat#RAW_SENSOR} image format.
   */
  public static final int OUTPUT_FORMAT_RAW = 2;

  /**
   * Captures raw images in the {@link ImageFormat#RAW_SENSOR} and {@link ImageFormat#JPEG}
   * image formats.
   */
  public static final int OUTPUT_FORMAT_RAW_JPEG = 3;
  ```

  Fonte: https://github.com/androidx/androidx/blob/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/ImageCapture.java

- Métodos envolvidos: `ImageCapture.Builder#setOutputFormat(int)` para configurar o modo
  de captura; `ImageCapture.getImageCaptureCapabilities(CameraInfo)` retornando um
  `ImageCaptureCapabilities` cujo `getSupportedOutputFormats()` (propriedade Kotlin
  `.supportedOutputFormats`) diz quais formatos o aparelho suporta — exatamente o mesmo
  padrão de chamada que o app já usa para `OUTPUT_FORMAT_JPEG_ULTRA_HDR`.
  Fonte: https://developer.android.com/jetpack/androidx/releases/camera

- Alerta de confiabilidade: em **1.5.0-beta02** (16/07/2025) foi corrigido um bug em que
  `getSupportedOutputFormats()` reportava RAW como suportado em aparelhos que não tinham
  a capacidade de verdade. Texto exato: "Fixed ImageCaptureCapabilities#getSupportedOutputFormats()
  API reporting RAW formats as supported in some devices which doesn't actually have RAW
  capability." Por isso, a checagem de suporte só é confiável em CameraX 1.5.0-beta02 ou
  posterior (a versão estável final 1.5.0 já está depois desse patch).
  Fonte: https://developer.android.com/jetpack/androidx/releases/camera ; link do patch:
  https://android-review.googlesource.com/#/q/Ibcadbc1b08383e58842f6c860f3b663a1ba649ff

- não encontrado: a página de referência isolada da classe
  (`developer.android.com/reference/androidx/camera/core/ImageCapture`) não renderizou
  conteúdo em nenhuma tentativa (SPA em JavaScript, só o menu de navegação aparece) — os
  nomes de constantes/métodos acima vêm do código-fonte e das notas de versão, não dessa
  página específica.

## 2. Como descobrir suporte a RAW no aparelho, e o que acontece sem suporte

### Pelo CameraX

- Mesma chamada já citada: `ImageCapture.getImageCaptureCapabilities(cameraInfo)
  .supportedOutputFormats` retorna um `Set<Int>`; se contiver `OUTPUT_FORMAT_RAW` (ou
  `OUTPUT_FORMAT_RAW_JPEG`), o aparelho suporta.
  Fonte: https://developer.android.com/jetpack/androidx/releases/camera

- Se o app chamar `setOutputFormat()` com um valor fora da lista de suportados, a
  validação acontece na fase de configuração do use case (`createPipeline()`), via
  `Preconditions.checkArgument(...)`, que lança **IllegalArgumentException** com mensagem
  do tipo "The specified output format (X) is not supported by current configuration.
  Supported output formats: [...]".
  Fonte: https://github.com/androidx/androidx/blob/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/ImageCapture.java

- Separadamente, a própria API de captura valida a combinação de `OutputFileOptions`:
  o `takePicture` de um parâmetro documenta `@throws IllegalArgumentException ... Also if
  ImageCapture#OUTPUT_FORMAT_RAW_JPEG is used` (isto é, usar o formato duplo com o método
  de um arquivo só é erro), e o `takePicture` de dois parâmetros documenta `@throws
  IllegalArgumentException ... Also if non-OUTPUT_FORMAT_RAW_JPEG format is used` (isto é,
  usar o método de dois arquivos fora do modo RAW_JPEG também é erro).
  Fonte: mesma acima (javadoc dos dois métodos `takePicture`)

### Por Camera2 puro

- Chave: `CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES`, um array `int[]`. Testar
  se contém `CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW`, cujo valor inteiro é
  **3**. Constantes vizinhas, para contexto: `BACKWARD_COMPATIBLE=0`,
  `MANUAL_SENSOR=1`, `MANUAL_POST_PROCESSING=2`, `RAW=3`, `PRIVATE_REPROCESSING=4`,
  `READ_SENSOR_SETTINGS=5`.
  Fonte: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/camera2/CameraMetadata.java

- Javadoc exato da capability RAW: "The camera device supports outputting RAW buffers and
  metadata for interpreting them. Devices supporting the RAW capability allow both for
  saving DNG files, and for direct application processing of raw sensor images. RAW_SENSOR
  is supported as an output format. The maximum available resolution for RAW_SENSOR streams
  will match either the value in SENSOR_INFO_PIXEL_ARRAY_SIZE or
  SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE. All DNG-related optional metadata entries
  are provided by the camera device."
  Fonte: mesma acima

- O que acontece pedindo RAW_SENSOR sem a capability: **não** é, na maioria dos casos, uma
  exceção síncrona imediata. Se o conjunto de saídas pedido não puder ser usado de jeito
  nenhum por aquele hardware, a falha é **assíncrona**, via `StateCallback.onConfigureFailed()`
  ao configurar a sessão de captura. `IllegalArgumentException` síncrona só é documentada
  para configuração estruturalmente inválida (ex.: lista de saídas vazia) — formato
  tecnicamente válido mas não suportado por aquela câmera específica cai no caminho
  assíncrono.
  Fonte: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/camera2/CameraDevice.java

- não encontrado: a linha exata de declaração do campo `REQUEST_AVAILABLE_CAPABILITIES`
  (tipo `Key<int[]>`) dentro de `CameraCharacteristics.java` — o arquivo é grande e as
  tentativas de leitura cortaram antes da seção `REQUEST_*`. O nome e o comportamento da
  chave estão confirmados por outra via (`CameraMetadata.java`, acima), mas não a
  declaração literal do campo nessa classe específica.

## 3. RAW + JPEG simultâneos: como o CameraX entrega os dois arquivos

- Assinatura exata (código-fonte de `ImageCapture.java`):

  ```java
  public void takePicture(
          final @NonNull OutputFileOptions rawOutputFileOptions,
          final @NonNull OutputFileOptions jpegOutputFileOptions,
          final @NonNull Executor executor,
          final @NonNull OnImageSavedCallback imageSavedCallback)
  ```

  Javadoc exato: "Captures two still images simultaneously and saves to a file along with
  application specified metadata. Currently only OUTPUT_FORMAT_RAW_JPEG is supporting
  simultaneous image capture. It needs two OutputFileOptions, the first one is used for
  ImageFormat#RAW_SENSOR image and the second one is for ImageFormat#JPEG. **The order of
  the callbacks for which image format is triggered first is not guaranteed.** Check with
  OutputFileResults#getImageFormat() in OnImageSavedCallback#onImageSaved(OutputFileResults)
  for the image format."
  Fonte: https://github.com/androidx/androidx/blob/androidx-main/camera/camera-core/src/main/java/androidx/camera/core/ImageCapture.java

- Ou seja: `OnImageSavedCallback#onImageSaved(OutputFileResults)` é chamado **duas vezes**
  — uma para o resultado RAW, outra para o JPEG — **em ordem não garantida**. Para saber a
  qual dos dois um determinado `onImageSaved` se refere, o app precisa chamar
  `outputFileResults.getImageFormat()`, que devolve a constante de `ImageFormat`
  (`ImageFormat.RAW_SENSOR` ou `ImageFormat.JPEG`) daquele resultado específico.
  `OutputFileResults.getSavedUri()` devolve a `Uri` salva (null se o `OutputFileOptions`
  correspondente foi construído com `OutputStream`).
  Fonte: mesma acima (classes `OutputFileResults`/`OnImageSavedCallback` no mesmo arquivo)

- Pasta e nome de cada um dos dois arquivos **são escolhidos separadamente** por quem
  chama a API: `rawOutputFileOptions` e `jpegOutputFileOptions` são dois objetos
  independentes de `ImageCapture.OutputFileOptions.Builder`, que aceita três formas de
  destino (confirmadas no próprio construtor, cada uma podendo ser diferente para o RAW e
  para o JPEG):
  - `Builder(@NonNull File file)` — arquivo direto;
  - `Builder(@NonNull ContentResolver contentResolver, @NonNull Uri saveCollection,
    @NonNull ContentValues contentValues)` — inserção via MediaStore (ex.:
    `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`);
  - `Builder(@NonNull OutputStream outputStream)` — stream já aberto.
  Fonte: mesma acima (classe `OutputFileOptions`, construtores dos três `Builder`)

- não encontrado: nenhuma lógica de extensão/sufixo automático (por exemplo, um ".dng"
  adicionado sozinho pelo CameraX) em `ImageCapture.java` — não há nenhuma ocorrência da
  string ".dng" no arquivo inteiro. Nomear cada arquivo com a extensão correta é
  responsabilidade de quem chama a API, tanto para o `File` quanto para o `ContentValues`
  do MediaStore.

- Nota de maturidade: em **1.6.0-beta02** foi corrigido "ImageCapture
  OUTPUT_FORMAT_RAW_JPEG in-memory capture IllegalArgumentException issue" — indício de
  que o modo RAW_JPEG em memória (`OnImageCapturedCallback`, sem salvar em disco) teve
  histórico de bug; o caminho documentado e citado nas notas de versão é sempre o de
  arquivo (`OutputFileOptions`), que é o método coberto acima.
  Fonte: https://developer.android.com/jetpack/androidx/releases/camera

## 4. Caminho sem CameraX: Camera2 com ImageReader RAW_SENSOR + DngCreator

- Formato de imagem: `ImageFormat.RAW_SENSOR = 0x20`. Javadoc exato: "General raw camera
  sensor image format, usually representing a single-channel Bayer-mosaic image. Each
  pixel color sample is stored with 16 bits of precision. The layout of the color mosaic,
  the maximum and minimum encoding values of the raw pixel data, the color space of the
  image, and all other needed information to interpret a raw sensor image must be queried
  from the CameraDevice which produced the image."
  Fonte: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/ImageFormat.java

- Construtor do `DngCreator`:

  ```java
  public DngCreator(@NonNull CameraCharacteristics characteristics,
                     @NonNull CaptureResult metadata)
  ```

  Javadoc confirma que nenhum setter é obrigatório: "It is not necessary to call any set
  methods to write a well-formatted DNG file." — o `DngCreator` extrai sozinho o que
  precisa de `CameraCharacteristics` + `CaptureResult`.
  Fonte: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/camera2/DngCreator.java

- Único campo do `CaptureResult` citado explicitamente como recomendação de qualidade:
  ativar `CaptureRequest#STATISTICS_LENS_SHADING_MAP_MODE` na captura melhora o DNG
  resultante.
  Fonte: mesma acima

- Métodos de escrita, assinatura exata:

  ```java
  public void writeImage(@NonNull OutputStream dngOutput, @NonNull Image pixels)
          throws IOException
  // Image precisa estar em ImageFormat.RAW_SENSOR.
  // IllegalArgumentException se o formato não é suportado;
  // IllegalStateException se os metadados forem insuficientes.

  public void writeByteBuffer(@NonNull OutputStream dngOutput, @NonNull Size size,
          @NonNull ByteBuffer pixels, @IntRange(from = 0) long offset) throws IOException
  // dado precisa ter 16 bits por pixel;
  // buffer precisa ter no mínimo offset + 2*width*height bytes.

  public void writeInputStream(@NonNull OutputStream dngOutput, @NonNull Size size,
          @NonNull InputStream pixels, @IntRange(from = 0) long offset) throws IOException
  // mesmos requisitos de writeByteBuffer.
  ```

  Fonte: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/camera2/DngCreator.java

- Outros métodos relevantes da mesma classe: `setOrientation(int)` usa as constantes de
  orientação do `ExifInterface`; `setThumbnail(@NonNull Bitmap)` grava TIFF RGB baseline
  8 bits/canal e lança `IllegalArgumentException` se exceder `MAX_THUMBNAIL_DIMENSION =
  256` px (há overload `setThumbnail(@NonNull Image)` que só aceita `ImageFormat.YUV_420_888`,
  mesmo limite); `setLocation(@NonNull Location)` exige tempo, latitude e longitude
  válidos, senão `IllegalArgumentException`; `setDescription(@NonNull String)` grava a tag
  TIFF `ImageDescription` (0x010E). A classe implementa `AutoCloseable` — `close()` precisa
  ser chamado (`use { }` em Kotlin) para liberar recurso nativo.
  Fonte: mesma acima

- Limitação documentada de combinação de streams — tabela "RAW-capability additional
  guaranteed configurations" do javadoc de `CameraDevice`: `RAW_SENSOR` só tem garantia
  formal de coexistir na mesma sessão de captura nestas combinações: sozinho
  ("No-preview DNG capture"); + preview `PRIV`; + preview `YUV`; + `PRIV`+`PRIV`
  (inclui vídeo); + `PRIV`+`YUV`; + `YUV`+`YUV`; + preview (`PRIV` ou `YUV`) + `JPEG`
  `MAXIMUM` ("Still capture with simultaneous JPEG and DNG"). Fora dessas combinações
  garantidas, o app precisa checar suporte explicitamente ou tratar
  `onConfigureFailed`.
  Fonte: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/camera2/CameraDevice.java
  (seção "RAW-capability additional guaranteed configurations")

- não encontrado: lista oficial exaustiva de campos do `CaptureResult` "obrigatórios vs.
  opcionais" além da recomendação do lens shading map já citada.
- não encontrado: declaração oficial explícita listando quais correções do pipeline de
  imagem normal (correção de lente, balanço de branco já aplicado, etc.) ficam de fora do
  DNG — só a inferência indireta pela própria definição de RAW_SENSOR/capability RAW já
  citadas acima.
- não encontrado (não confirmado na API pública): se a implementação do `DngCreator`
  aplica compressão sem perdas automaticamente. A classe Java não expõe nenhum parâmetro
  de compressão e não há nenhuma ocorrência da string "compress" no código-fonte Java
  inspecionado — a escrita real do arquivo é nativa (JNI) e não foi inspecionada nesse
  nível.
  Fonte (ausência verificada): https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/camera2/DngCreator.java

- DngCreator existe desde a API 21, então o minSdk 26 do app não é bloqueio para esse
  caminho.

## 5. Lado da galeria: o Android decodifica DNG nativamente?

- A página oficial de formatos de mídia do Android lista como decodificadores de imagem
  nativos apenas: BMP, GIF, JPEG, PNG, WebP, HEIF (Android 8+/API 26) e AVIF (Android
  14+/API 34). **DNG não aparece em nenhum lugar dessa lista.**
  Fonte: https://developer.android.com/guide/topics/media/media-formats

- não encontrado: texto oficial literal, dentro da própria documentação de referência de
  `BitmapFactory` ou `ImageDecoder`, dizendo explicitamente que essas classes não
  decodificam DNG — as páginas de referência dessas duas classes não renderizaram
  conteúdo nas tentativas feitas (SPA em JavaScript). A ausência do DNG na lista de
  formatos suportados (fato acima) é a evidência mais forte encontrada, mas é indireta,
  não uma frase textual "BitmapFactory/ImageDecoder não decodifica DNG".

- Decodificar pixel é diferente de ler metadado: a biblioteca `androidx.exifinterface`
  (Jetpack, parte do ecossistema oficial Android) sabe **ler** tags EXIF de arquivos DNG,
  mas não geração de bitmap. Doc exato da classe: "Supported for reading: JPEG, PNG, WebP,
  HEIC, **DNG**, CR2, NEF, NRW, ARW, RW2, ORF, PEF, SRW, RAF, AVIF (on API 31+)." e
  "Supported for writing: JPEG, PNG, WebP." — ou seja, DNG é lido mas **não** gravado por
  essa biblioteca (histórico: a gravação de EXIF em DNG foi adicionada na versão 1.3.3 e
  removida na 1.3.4 porque "the support added in 1.3.3 was incomplete and produced
  corrupted files").
  Fonte: https://github.com/androidx/androidx/blob/androidx-main/exifinterface/exifinterface/src/main/java/androidx/exifinterface/media/ExifInterface.java
  (comentário de topo da classe) ; histórico de write support:
  https://developer.android.com/jetpack/androidx/releases/exifinterface

- MIME type exato usado no ecossistema Android/AndroidX para DNG: **`image/x-adobe-dng`**
  — confirmado literalmente no código-fonte do `ExifInterface`, no método
  `isSupportedMimeType(String)`:

  ```java
  switch (mimeType.toLowerCase(Locale.ROOT)) {
      case "image/jpeg":
      case "image/x-adobe-dng":
      case "image/x-canon-cr2":
      case "image/x-nikon-nef":
      // ... outros RAW de fabricante ...
      case "image/heic":
      case "image/heif":
      case "image/png":
      case "image/webp":
          return true;
      default:
          return false;
  }
  ```

  Internamente a classe também define `private static final int IMAGE_TYPE_DNG = 3;`.
  Fonte: https://github.com/androidx/androidx/blob/androidx-main/exifinterface/exifinterface/src/main/java/androidx/exifinterface/media/ExifInterface.java

- Ressalva importante sobre o MIME type: essa confirmação vem da biblioteca AndroidX
  `ExifInterface` (parte do ecossistema oficial, mas não é o MediaProvider/MediaStore em
  si). Tentativas de busca direta no código-fonte do MediaProvider (`gh api search/code`
  no repositório espelho `aosp-mirror/platform_packages_providers_MediaProvider`, e busca
  ampla por "x-adobe-dng" em repositórios públicos) não retornaram nenhum resultado
  relevante.
  não encontrado: confirmação direta, em código do MediaProvider/MediaStore, de que
  arquivos `.dng` são indexados como imagem em consultas `MediaStore.Images` — o MIME type
  `image/x-adobe-dng` está confirmado como valor oficial do ecossistema Android para DNG,
  mas não a afirmação específica "o MediaStore indexa .dng com esse MIME type".
  não encontrado: comportamento do Google Fotos ou de apps de galeria populares
  especificamente com arquivos `.dng` (preview via thumbnail embutido, recusa, etc.).

## 6. Tamanho típico de um DNG de 50 MP comparado ao JPEG

- Cálculo a partir de fatos já citados (isto é uma **estimativa calculada**, não uma
  medição real — ver ressalva abaixo): `RAW_SENSOR` é formato de canal único (mosaico de
  Bayer) com 16 bits (2 bytes) de precisão por amostra de pixel (fonte: seção 4 acima,
  `ImageFormat.java`). Para 50 MP: 50.000.000 x 2 bytes = 100.000.000 bytes ≈ 95,4 MiB
  (~100 MB) **sem compressão** — esse é o teto teórico de um buffer `RAW_SENSOR` bruto,
  antes de virar arquivo DNG.

- O formato DNG, pela natureza dos formatos RAW em geral, tipicamente usa compressão sem
  perdas: "Most raw formats implement lossless data compression."
  Fonte: https://en.wikipedia.org/wiki/Raw_image_format (seção "Drawbacks")
  não encontrado: se o `DngCreator` do Android aplica essa compressão automaticamente por
  padrão — ver ressalva já registrada na seção 4 (API Java pública não expõe parâmetro de
  compressão; implementação de escrita é nativa e não foi inspecionada nesse nível).

- Comparação direta com JPEG, segundo a mesma fonte: "Camera raw file size is typically
  2–6 times larger than JPEG, though an uncompressed raw file is about half the size of an
  uncompressed 8BPC TIFF."
  Fonte: https://en.wikipedia.org/wiki/Raw_image_format (seção "Drawbacks") — valor geral
  de mercado, não específico para Android nem para 50 MP.

- Combinando os dois pontos acima: o teto de ~100 MB calculado para 50 MP é compatível com
  a faixa de 2-6x a mais que um JPEG de qualidade alta da mesma cena (que tende a ficar na
  casa de poucas dezenas de MB num sensor de 50 MP). Isso é coerência matemática entre
  duas fontes, não uma medição.
  não encontrado: nenhum número real, medido, específico de um arquivo DNG de 50 MP de um
  aparelho Android concreto (ex.: um review técnico citando "X MB" para um modelo
  específico). A tentativa de acessar a página técnica da Adobe sobre DNG
  (`helpx.adobe.com/camera-raw/digital-negative.html`) retornou HTTP 403 e não pôde ser
  lida.

## 7. Limitações conhecidas em Xiaomi/MediaTek para RAW

- não encontrado: nenhuma fonte oficial (Google Issue Tracker, documentação de fabricante)
  afirmando explicitamente regras como "RAW só na câmera principal" ou "RAW só em
  resolução binada" como comportamento documentado da Xiaomi ou de chipsets MediaTek.
  Buscas dirigidas (`gh search issues` por "RAW_SENSOR Xiaomi", "CameraX RAW Xiaomi",
  "camera2 RAW LIMITED hardware level", "RAW capability MediaTek", "RAW only main camera",
  "supportedHardwareLevel LIMITED", "MTK RAW_SENSOR", "HyperOS camera2 RAW") não
  retornaram resultado relevante.

- Evidência circunstancial encontrada (mostra o *mecanismo* que tornaria essas restrições
  possíveis, mas não é confirmação direta de uma regra documentada):
  - O app de câmera manual open-source FreeDcam mantém pacotes de "chaves ocultas"
    (vendor tags) do Camera2 dedicados e separados para Xiaomi e para MediaTek — prova de
    que os dois fabricantes estendem a Camera2 pública com tags proprietárias fora do
    padrão AOSP.
    Fonte: https://github.com/KillerInk/FreeDcam/blob/4ce7b6169cbac526df84f2653bc1ecf96abc3d0a/app/src/main/java/freed/cam/apis/featuredetector/Camera2FeatureDetectorTask.java
  - Vendor tags Xiaomi incluem `xiaomi.quadcfa.supported` / `xiaomi.quadcfa.enabled`
    (QCFA = Quad Color Filter Array, nome técnico do pixel-binning usado em sensores
    48/50/64/108 MP) e tratam `android.scaler.availableRawSizes` como extensão de
    fabricante — existe mecanismo proprietário Xiaomi para saber/controlar se o sensor
    está em modo binado e quais tamanhos de RAW existem. Consistente com, mas não prova
    direta de, "RAW só em resolução binada".
    Fonte: https://github.com/KillerInk/FreeDcam/blob/4ce7b6169cbac526df84f2653bc1ecf96abc3d0a/app/src/main/java/camera2_hidden_keys/xiaomi/CameraCharacteristicsXiaomi.java
  - A mesma classe define `com.xiaomi.cameraid.role.cameraId` (papel/posição da câmera) —
    consistente com, mas não prova direta de, controle de recurso por câmera física
    específica (sustentaria "RAW só na principal"). Mesma fonte acima.
  - Vendor tag MediaTek `com.mediatek.control.capture.ispTuningRequest`, com constantes
    explícitas `CONTROL_CAPTURE_ISP_TUNING_REQ_RAW = 1` e `..._REQ_YUV = 2` — o ISP
    MediaTek distingue pedido RAW de YUV por mecanismo próprio, fora da API pública.
    Nenhuma limitação documentada de RAW ligada a essa tag foi encontrada.
    Fonte: https://github.com/KillerInk/FreeDcam/blob/4ce7b6169cbac526df84f2653bc1ecf96abc3d0a/app/src/main/java/camera2_hidden_keys/mtk/CaptureRequestMtk.java
  - O app open-source OpenCamera (almalence) mantém lista de aparelhos liberados
    manualmente para Camera2, incluindo o Xiaomi Mi Mix 2S — evidência de instabilidade
    histórica de Camera2 em Xiaomi a ponto de exigir aprovação modelo a modelo (não é
    especificamente sobre RAW).
    Fonte: https://github.com/almalence/OpenCamera/blob/24b600e7af4487f2a6d245d0e46ae8bda7afbb64/src/com/almalence/opencam/cameracontroller/CameraController.java
    (linha 244)
  - O mesmo arquivo documenta, em comentário, a regra pública e geral do Camera2 (não é
    específica de Xiaomi/MediaTek) para RAW avançado: a câmera precisa ser
    `INFO_SUPPORTED_HARDWARE_LEVEL_FULL` OU (`LIMITED` **e**
    `SYNC_MAX_LATENCY_PER_FRAME_CONTROL`). Isso explica por que aparelhos de entrada com
    câmera `LIMITED` — comuns em Xiaomi/MediaTek de baixo custo — ficam fora de recursos
    manuais avançados, mas é regra geral do Android, não trava específica de fabricante.
    Fonte: mesmo arquivo acima (comentário, linhas ~1226-1230)

- Conclusão desta pergunta: não há, nas fontes encontradas, uma regra documentada e
  específica de Xiaomi ou MediaTek para RAW. O que existe, com fonte, é (a) o mecanismo
  técnico de ambos os fabricantes para controlar RAW/resolução binada via vendor tags
  proprietárias, e (b) a regra geral e pública do Android de que câmeras `LIMITED` sem
  `SYNC_MAX_LATENCY_PER_FRAME_CONTROL` ficam fora de RAW avançado — o que, combinado ao
  fato de mercado de que aparelhos de entrada Xiaomi/MediaTek costumam expor câmeras
  `LIMITED`, é a explicação mais provável para relatos informais de problemas de RAW
  nesses aparelhos, mas nenhuma fonte encontrada documenta essa causalidade de forma
  explícita.
