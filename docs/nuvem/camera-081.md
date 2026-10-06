Instrução para a SESSÃO NA NUVEM (câmera 0.81). Repositório MaxxArtes/camera-estudo.
- Crie o ramo `camera-081` a partir do `main` atualizado e trabalhe só no módulo `app` (pacote br.maxymus.cameraestudo).
  Não mexa em tradutor nem galeria.
- Aí a rede bloqueia dl.google.com e não dá para compilar. NÃO tente instalar o Android SDK: quem compila é o
  orquestrador, na bancada.
- Faça passos pequenos, com commits no ramo, e no fim abra um PR para o main com o relatório pedido no fim deste
  arquivo.
- Comentários em português, sem emoji, no estilo do arquivo. Nenhuma dependência nova.

## Contexto
A 0.80 trouxe a "Rajada de teste" (Rajada.kt, RajadaTela.kt, orquestração em CameraScreen.kt ~:1186-1270). A primeira
rajada real do dono (POCO X8 Pro Max, Camera2 nível 3, câmera "0") deu 8 quadros YUV 4096x3072 em 428 ms, a 30 fps, com
AE e AF travados. Mas o ISP aplicou NOISE_REDUCTION_MODE=2 e EDGE_MODE=2 em cada quadro, e a estabilização óptica saiu
DESLIGADA (modo 0). Medido na bancada, o ruído da foto única já era de 0,33 tom de cinza e somar 6 quadros quase não
ganhou nada. A pesquisa (HDR+) indica que a soma rende no RAW ou sem o processamento do ISP, e em pouca luz. Daí a 0.81:
a mesma rajada, em três modos à escolha.

## PASSO 1 — Modos e disponibilidade (Rajada.kt)
1.1 Três modos: "processada" (como a 0.80), "sem_processamento" (YUV com NOISE_REDUCTION_MODE_OFF e EDGE_MODE_OFF) e
    "raw" (RAW_SENSOR, gravado em DNG).
1.2 Uma função de disponibilidade lê as CameraCharacteristics da câmera da rajada (LOGICA) SEM abrir a câmera e diz,
    para cada modo, se está disponível e, se não, por quê:
    - "sem_processamento" só se NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES contém OFF E EDGE_AVAILABLE_EDGE_MODES
      contém OFF;
    - "raw" só se REQUEST_AVAILABLE_CAPABILITIES contém RAW E o SCALER_STREAM_CONFIGURATION_MAP do modo padrão tem
      tamanhos RAW_SENSOR. Use o maior.
1.3 Em TODOS os modos, nos pedidos do 3A e nos da rajada:
    - se LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION contém ON, peça LENS_OPTICAL_STABILIZATION_MODE_ON;
    - NÃO fixe a faixa de fps do AE em [30,30]: em luz baixa de verdade, isso levaria o ISO ao extremo (revisão do
      agy). Peça a faixa com máximo 30 e o MENOR mínimo disponível em CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES (por
      exemplo [15,30]), para o AE poder alongar a exposição.
    Registre o que foi pedido e o que o resultado devolveu: faixa, exposição, ISO e OIS por quadro.
    A OIS fica LIGADA de propósito. O agy sugeriu desligar, mas na 0.80 ela já saiu DESLIGADA (modo 0) e o tremor
    entre quadros foi de 8-32 px. Com ela ligada, o deslocamento deve cair e o alinhamento fica mais fácil. Não mude isso.
1.4 "sem_processamento": os pedidos da rajada levam NOISE_REDUCTION_MODE_OFF e EDGE_MODE_OFF. Se algum quadro voltar com
    nr != OFF ou edge != OFF no CaptureResult, o resultado vira "modo_nao_aplicado". Os quadros são salvos assim mesmo, e
    o motivo diz o que não foi aplicado.
1.5 "raw":
    - fluxos: o YUV pequeno do 3A (como hoje) + um ImageReader RAW_SENSOR no maior tamanho, com maxImages = QUADROS, sem
      o YUV grande;
    - se STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES contém ON, a rajada pede STATISTICS_LENS_SHADING_MAP_MODE_ON
      (o DngCreator inclui o mapa de sombreamento quando ele vem no resultado);
    - SEM cópia para o heap (revisão do agy: 8 RAW são ~200 MB). No ouvinte, faça acquireNextImage e NÃO feche a Image.
      Guarde-a com o TotalCaptureResult do mesmo SENSOR_TIMESTAMP e as CameraCharacteristics, para o DngCreator.
      O ImageReader RAW só fecha depois de gravar (ou no erro e no cancelamento, fechando antes cada Image guardada);
    - antes de guardar, confira `img.format == ImageFormat.RAW_SENSOR` e `planes[0].pixelStride == 2`. Fora disso, o
      resultado é "erro" com motivo "raw_formato" e nada é gravado;
    - grave os DNG com a câmera ainda aberta, depois de stopRepeating, e só então solte a câmera. Inverter (soltar a
      câmera e gravar depois) só vale se a documentação garantir que as Image do ImageReader continuam válidas depois de
      fechar o CameraDevice; na dúvida, grave antes.
    Mantenha o tratamento de OutOfMemoryError: resultado "erro", motivo "memoria", e nada salvo pela metade.
1.6 Todas as proteções da 0.80 continuam: câmera retida, cancelamento, onClosed, isolamento por tentativa, travaArquivos.

## PASSO 2 — Gravação (Rajada.gravar)
2.1 "processada" e "sem_processamento": iguais à 0.80 (y_i.png e uv_i.png).
2.2 "raw": um `quadro_i.dng` por quadro, com
    `DngCreator(caracteristicas, resultadoDoQuadro).setOrientation(...).writeImage(out, image)`, fechando a Image logo
    depois.
    - orientação: a mesma que o app usa na foto normal; se não houver como saber, ORIENTATION_NORMAL, anotado no meta;
    - NUNCA chame setLocation nem setDescription;
    - feche o DngCreator;
    - se o DngCreator lançar exceção (tamanho que não bate, metadado faltando), o resultado é "erro" com classe e motivo
      "dng", e nada fica pela metade (mesma regra dos PNG).
2.3 Prévia para a revisão: para cada quadro RAW, gere uma prévia em tons de cinza lida do MESMO buffer da Image, antes
    de fechá-la, amostrando só os blocos necessários, sem copiar a imagem inteira:
    - média de cada bloco 2x2 do Bayer;
    - menos o nível de preto, dividido pelo de branco, gama 1/2,2;
    - reduzida para ~256 px no lado maior.
    Grave como `previa_i.png`. A revisão usa essas prévias como miniaturas ("quadro i (DNG)"), e elas entram na lista
    exata e no .zip (são pequenas e vêm do mesmo quadro). O compartilhamento continua só ligando com todas as prévias
    carregadas.

## PASSO 3 — meta.json (formato_meta 2)
3.1 "modo"; "formato" ("YUV_420_888" ou "RAW_SENSOR"). Em raw, cada quadro leva "arquivo_dng" e "arquivo_previa" no lugar
    de arquivo_y e arquivo_uv.
3.2 Declarações do aparelho:
    - "modos_nr_disponiveis", "modos_edge_disponiveis";
    - "raw_capacidade", "tamanhos_raw" (modo padrão), "raw_duracao_min_ns" e "raw_stall_ns" do tamanho usado;
    - "yuv_duracao_min_ns" do tamanho YUV usado;
    - "faixas_fps_ae" e "faixa_fps_ae_pedida"; "ois_pedido"; "modos_mapa_sombreamento".
3.3 Do sensor, para o RAW:
    - "cfa" (SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
    - "preto_padrao" (SENSOR_BLACK_LEVEL_PATTERN, 4 valores) e "branco" (SENSOR_INFO_WHITE_LEVEL);
    - "matriz_pixels" (SENSOR_INFO_PIXEL_ARRAY_SIZE) e "area_ativa_pre_correcao"
      (SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE).
3.4 Por quadro, em todos os modos:
    - "perfil_ruido" (SENSOR_NOISE_PROFILE, lista de pares [S, O]);
    - "preto_dinamico" e "branco_dinamico" (SENSOR_DYNAMIC_*, API 28+);
    - "ponto_neutro" (SENSOR_NEUTRAL_COLOR_POINT, em decimais) e "ganhos_wb" (COLOR_CORRECTION_GAINS);
    - "tem_mapa_sombreamento" (true ou false);
    - nr, edge e ois, como já é hoje.

## PASSO 4 — Tela (RajadaTela.kt e a chamada em CameraScreen.kt)
4.1 Em DialogoRajada, antes de "Fazer a rajada", um grupo "Modo" com três linhas de RadioButton. Cada linha inteira é
    clicável, com no mínimo 48 dp:
    - "Processada pelo celular": "Como na 0.80: o celular reduz o ruído e aplica nitidez em cada foto."
    - "Sem processamento do celular": "Redução de ruído e nitidez desligadas. Fotos com mais ruído, para a soma limpar."
    - "RAW (DNG)": "Dados crus do sensor. Arquivos grandes: cerca de 25 MB por foto."
    Uma linha indisponível fica desligada, com "Este aparelho não oferece." O modo escolhido fica guardado nas
    preferências que o app já usa. O padrão é "Sem processamento do celular", se disponível; senão, "Processada".
4.2 INSTRUCAO_RAJADA passa a ser: "Segure como numa foto normal e aponte para uma estante ou parede com textura, em luz
    baixa (abajur à noite). São 8 fotos seguidas e uma normal. As fotos ficam só no aparelho até você compartilhar."
4.3 O resultado mostra:
    - o nome do modo;
    - em "sem_processamento", "Redução de ruído e nitidez: desligadas", conferido nos resultados dos quadros, ou, em
      "modo_nao_aplicado", "O celular não desligou a redução de ruído ou a nitidez (NR x, EDGE y)";
    - "Estabilização óptica: ligada" ou "desligada", conforme os resultados.
4.4 A revisão mostra as miniaturas "quadro i (DNG)" no modo raw. O total em MB já aparece.

## PASSO 5 — Telemetria
Acrescente "modo" como código numérico (0 processada, 1 sem_processamento, 2 raw) ao evento de fim da rajada, pelo
esquema fechado do DoisSensores. Se o esquema tiver uma lista de chaves permitidas, inclua a chave nela. Só números e
códigos.

## PASSO 6 — Versão
app/build.gradle.kts: versionCode = 81, versionName = "0.81".

## RELATÓRIO FINAL
Uma linha por passo (1 a 6): FEITO ou NÃO FEITO, com arquivo:linha. Em seguida:
- as decisões que você tomou onde a instrução não decidia;
- os riscos que viu e não resolveu, principalmente no RAW (Image retida, o tamanho que o DngCreator aceita, quanto tempo a
  câmera fica aberta gravando);
- `git status --short` e o link do PR.
Se achar um defeito nesta instrução, aplique a correção mínima e explique no relatório. (Esta instrução já incorpora uma revisão
independente do desenho.)
