Contexto: app Android de camera "de estudo" em Kotlin puro (sem OpenCV/GPU/JNI, heap 512 MB), repo /opt/camera-estudo.
Modo Retrato hoje: se o aparelho expoe BOKEH pelo CameraX Extensions, usa o do fabricante; senao, Retrato.kt: mascara de pessoa
do ML Kit (selfie segmentation, mascara em tamanho real, suavizada 5x5 + curva S), fundo = imagem reduzida por (2 x intensidade)
e ampliada de volta em duas etapas (borrão barato), mistura por alfa da mascara, saida 2400 px, EXIF. Depois passa por
Acabamento.kt (embelezador = surface blur em pele com mascara pessoa x tom de pele x nao-borda; filtros por matriz de cor,
Vivido por vibrancia com pele protegida) e Pessoas.kt (reconhecimento facial + ancora de pele por pessoa). Leia Retrato.kt,
Acabamento.kt, Pessoas.kt, Rostos.kt e Fusao.kt (nitidezLeve/nitidezTexto) inteiros.

O dono gosta do RESULTADO do modo Retrato da Google Camera (GCam / Pixel): a "configuracao" dele — como a foto fica.
Imagens anexas: nosso_retrato_software_ceu.jpg (nosso Retrato por software contra o ceu), xiaomi_retrato_nativo.jpg
(bokeh nativo do POCO X8 Pro Max, que ele tambem acha bonito), nossa_foto_vivido.jpg (modo Foto com filtro Vivido).

Tarefa: analise, com base em fontes publicas (Wadhwa et al. 2018 "Synthetic Depth-of-Field with a Single-Camera Mobile
Phone", Portrait Light 2020, Real Tone, HDR+ por baixo, face-aware AE), O QUE faz o retrato da GCam parecer bom, e o que
da para reproduzir em Kotlin puro em < 2 s. Ordene por ganho/custo e seja concreto (parametros, pseudocodigo curto):
1. Desfoque do fundo: forma do kernel (disco/lente vs gaussiana/downscale que usamos), desfoque crescente com a distancia
   estimada (temos so a mascara de pessoa e a caixa do rosto — como aproximar profundidade: rosto = foco, pes de pagina,
   gradiente vertical, tamanho do rosto?), "bolas" de bokeh em luzes fortes (highlight boosting), tratamento de borda
   cabelo/fundo (matting simples, erosao/feather assimetrico, cor de vazamento).
2. Rosto: luz de preenchimento sintetica (Portrait Light) aproximada por mapa de tons local so no rosto; contraste local;
   remocao de brilho de oleosidade; nitidez so em olhos/boca (temos landmarks dos olhos do ML Kit); grao/ruido.
3. Cor: tom de pele (Real Tone) — o que da para fazer sem dataset: balanco de branco por rosto, saturacao de pele,
   curva de tons; como validar com a cor da pele medida (temos R,G,B media da pele por foto).
4. Exposicao: face-aware AE no CameraX (medicao no rosto) e HDR do rosto contra fundo claro (ceu).
5. O que medir antes de publicar (sem dataset), e o que voce cortaria.
Critique tambem o que ja existe se vir erro. Nao altere arquivos: so analise e proposta. Responda em portugues,
objetivo, com codigo Kotlin so onde for essencial.
