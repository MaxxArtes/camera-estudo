# Engenharia reversa da câmera do sistema: análise cruzada (18/09/2026)

Método (definido pelo dono): dados brutos entregues SEM viés ao Astra (codex gpt-6-astra) e ao agy; eu registrei
minhas conclusões em separado; depois cruzei os três. Dados brutos em scratchpad/revcam/brutos.md.

## Consenso dos três (alta confiança)
1. **EXIF de exposição é descartado.** A foto do sistema tem ISO 2407, exposição 1/30, f/2.2, foco, MakerNote.
   O nosso `gravaExif` (Fotos.kt) escreve só Make/Model/Software/DateTime; a telemetria do disparo tem iso:null.
   Melhoria: copiar o EXIF da captura original (CaptureResult) em vez de recriar 4 tags. Alto valor, baixo esforço.
2. **A foto do sistema é Ultra HDR (gain map).** XMP com hdrgm 1.0 e item GainMap (15.781 bytes); o hardware
   reporta DYNAMIC_RANGE_TEN_BIT. A nossa recomprime via Bitmap.compress(JPEG,93). Alto valor potencial, mas
   esforço médio-alto E depende de medição (ver abaixo).
3. **Pela API pública só temos a extensão AUTO** (telemetria: ["Desligado","Auto"], forca_disponivel:false),
   apesar do HAL listar night/bokeh/beauty/hdr. O resto é vendor-privado, fora de alcance.
4. **O primeiro passo é MEDIR, não construir.** Falta uma foto do nosso app na mesma cena para comparar; os
   blocos do dumpsys não estão amarrados a IDs de câmera; tudo é inferência de uma foto só.

## O que a revisão cega pegou e eu NÃO tinha visto
- **Motion Photos (.MV.jpg):** o sistema salva foto com vídeo embutido (agy, via nomes em DCIM). Eu não olhei
  os sufixos. Consenso: NÃO implementar (fora do escopo, complexo).
- **Gargalo de 4 segundos:** uma "foto" levou 4094 ms usando só 13 MB de RAM (agy + Astra). É sintoma de CPU
  travada/IO bloqueante no pipeline. Eu tinha focado em qualidade e não vi o problema de desempenho. Investigar
  instrumentando Acabamento.aplicarEmArquivo (onde vão os 4 s: decode, YOLO, máscaras, rostos ou recompressão).
- **Epistemologia mais dura (Astra):** eu NÃO provei que o nosso app perde o gain map; Bitmap.compress não
  necessariamente destrói HDR (Android tem suporte a gain map). Tem que medir `Bitmap.hasGainMap()` numa foto real
  do nosso app antes de afirmar.

## Divergência entre os dois (e quem eu sigo)
- agy afirmou como fato: "Bitmap.compress destrói o gain map" e "a fabricante baniu BOKEH para terceiros".
- Astra corrigiu: nenhum dos dois está provado; tag existir ≠ extensão disponível; medir `hasGainMap()` e
  consultar a extensão pelo seletor real. **Sigo o Astra:** medir antes de afirmar.

## Plano recomendado (ordenado, com portão de medição)
1. **Instrumentar (alto valor, baixo esforço).** Telemetria com câmera efetiva, extensão aplicada, ISO/exposição
   reais, dimensões e tempo POR ETAPA. Resolve o mistério dos 4 s e diz se realmente falta gain map.
2. **Gravar EXIF real.** Copiar exposição/ISO/foco da captura para a foto final. Coerente com "estudo".
3. **Comparação controlada** numa cena: sistema x nosso app (com e sem acabamento; Auto lig/desl). Ruído,
   nitidez, altas luzes, tamanho, presença de gain map.
4. **Ultra HDR de ponta a ponta** — só depois de 1 e 3 confirmarem que falta e que o combo CameraX+câmera suporta.
   Risco: Acabamento reabre e reescreve SDR; decidir preservar o gain map ou ter caminho separado.
5. **A/B da extensão AUTO** (barato de testar; custo medido antes 1,8-2,3 s/quadro com Rajada/HDR nossos).

## NÃO fazer (consenso)
Motion Photos; tags vendor / SuperNight / Vidhance por JNI reverso; copiar ISO 2407 como "ideal"; aumentar
resolução/qualidade às cegas; concluir que houve fusão noturna (as tags são catálogo, não execução desta foto).

## Implementação (18/09)
- **0.70:** EXIF real (ISO, exposição, foco, abertura) lido do CaptureResult do Preview (Exposicao.kt) e gravado
  em toda foto (gravaExif); telemetria do sensor no evento "foto". Ressalva do Astra: os valores vêm do Preview,
  aproximados, não do CaptureResult exato da still (aceitável; melhor que nulo).
- **0.71:** conserto do acabamento duplo na fusão (filtro/embelezador/Pessoas rodavam na memória E em
  aplicarEmArquivo); opção de gaveta "Noite: Clássico (fusão) / Aparelho (12 MP)" — Aparelho desliga a fusão no HDR
  e usa captura única em qualidade máxima, para o A/B contra a nativa. Padrão continua Clássico (não regride).
- **Pendente (0.72+):** Ultra HDR (OUTPUT_FORMAT_JPEG_ULTRA_HDR quando supportedOutputFormats anunciar; elegível só
  sem acabamento/fusão/extensão; opção de gaveta + selo "Ultra HDR"); redução de croma só no Clássico se o A/B pedir.
- **Validação pendente (precisa do celular aceso):** confirmar EXIF na 0.70; A/B do Noite Clássico x Aparelho x
  nativa na mesma cena; conferir gain map da 0.72. O Doze/indisponibilidade do celular tem travado o teste ao vivo.

## A/B medido no aparelho (19/09, fotos do dono, mesmo quintal noturno)
Validado com EXIF: nossas fotos 0.71 agora trazem ISO real (7154-7520); antes era nulo -> batch 1 OK.
Noite Aparelho sai em 12,6 MP (3072x4096); Clássico em 3,1 MP (1536x2048) -> a opção nova resolve a resolução.
Comparação Clássico x Aparelho x GCam x Xiaomi (ressalva: enquadramento variou, direcional):
- Aparelho: 4x a resolução do Clássico, mas mais grão na sombra (captura única sem média de quadros).
- Clássico: menos ruído (fusão faz média), porém mole e 1/4 da resolução.
- Troca-troca real, não nocaute -> manter os DOIS como opção (Astra estava certo). Considerar Aparelho como padrão.
- Ambos ainda perdem para Xiaomi/GCam no escuro (SuperNight/HDR+ multiquadro em hardware, fora da API pública).
  Confirma agy+Astra: não tentar bater SuperNight por software; nosso diferencial é resolução cheia, EXIF e os modos.

## A/B objetivo por EXIF (19/09, 16 fotos noturnas do dono, mesmo poste, 22:05-22:10)
Ferramenta: PIL no servidor (sem exiftool); script scratchpad/exif16.py. Fonte identificada pelo campo Software
(à prova de chute): GCam=`HDR+ 1.0.6955...`, nativa=`MediaTek Camera Application`, nosso=`Camera Estudo 0.72 (modo)`.

Traseira principal (foco 4,95 mm):
| # | Fonte | Resolução | ISO | Exposição | gain map |
|---|---|---|---|---|---|
| 01 | GCam (HDR+) | 12,6 MP | 506 | 1/10 | sim |
| 02 | GCam (HDR+) | 12,6 MP | 283 | 1/6 | sim |
| 04 | Nativa MediaTek | 12,6 MP | 2500 | 1/11 | sim |
| 03,05 | Nosso app (foto) | 12,6 MP | 3239 | 1/20 | não |
| 06 | Nosso app (noite) | 5,1 MP | 3073 | 1/20 | não |

Frontal (foco 2,24 mm): selfies 08-15 = nosso app, 5,0 MP (1944x2592); a 09 tem desfoque de fundo (Retrato,
bom resultado); a 16 = nativa, **20,2 MP** (3888x5184), gain map, ISO 1600. Nossa frontal sai a 1/4 da nativa.

Conclusões (viram trabalho):
1. **Frontal 5 vs 20 MP.** Mesma trava do 50 MP traseiro, mas na frontal; sensor 20 MP binado 4:1. Pedir resolução
   cheia é código nosso (ResolutionSelector), não depende de tag vendor. Medir detalhe real vs upscale.
2. **Noite se ganha na exposição, não na média.** GCam ISO 283-506 @ 1/6-1/10; nós ISO ~3200 @ 1/20. GCam expõe
   ~2x mais tempo com ~6x menos ISO e empilha alinhado. Nossa noite faz média de quadros CURTOS (ISO alto) e cai
   para 5,1 MP. Direção: alongar exposição/baixar ISO no noite (Camera2Interop SENSOR_EXPOSURE_TIME/SENSITIVITY,
   AE off), risco de borrão. Não é "bater o SuperNight", é fechar parte da distância de ruído.
3. **Foto normal nossa já é 12,6 MP** (igual nativa/GCam). Só o noite reduz.
4. **DateTime ausente no EXIF nosso** (GCam/nativa gravam). Trivial de corrigir no gravaExif.
5. **Ultra HDR (0.72) nunca disparou:** todas as 16 tinham acabamento ligado (Pessoas + Auto-máscaras) que bloqueia
   por regra. Zero gain map nas nossas. O selo "indisponível com acabamento" (print do dono) está CORRETO; para
   validar, desligar Pessoas + Auto-máscaras, Filtro Original, HDR/Rajada off.

## Rodada árvore (19/09, 4 fotos, cena de alto contraste) + bug de UX do selo Ultra HDR
Fontes: A/C/D = nativa (MediaTek), B = nosso app "(hdr_rajada)" 5,1 MP. Gain map: nativa A e D sim, C não
(a nativa nem sempre emite Ultra HDR — depende da cena); B (nosso) não.
- Reforça: nossos modos multiquadro (hdr/rajada/noite) caem para 5,1 MP; foto simples fica 12,6 MP.
- **Ultra HDR ainda NÃO validado:** o disparo nosso veio em HDR ligado, que bloqueia o Ultra HDR pela regra
  (ultraHdrEligivel exige !hdr && !rajada && !Pessoas && !autoMascaras && filtro Original). Duas tentativas do dono
  caíram fora: 1ª com acabamento (Pessoas+Auto-máscaras), 2ª com HDR.
- **BUG DE UX a corrigir (0.73):** o subtítulo do botão diz sempre "indisponível com acabamento", mas o bloqueador
  pode ser HDR/Rajada/filtro/extensão — nomear o motivo real ("desligue HDR", "desligue Pessoas", etc.). É o
  conserto de ergonomia (pavimentar a estrada) que faz o dono chegar no estado elegível sem eu recitar a lista.
  Passa pelo Astra (regra de design) antes de aplicar.

## CORREÇÃO + Ultra HDR validado (19/09, 23:13)
O dono corrigiu: "todos foram da nossa câmera". Ele estava certo; eu tinha lido "MediaTek Camera Application"
como nativa. O código prova o contrário: em tiraFoto(), sem acabamento/HDR/rajada, o ramo `else` salva o JPEG do
HAL DIRETO (OutputFileOptions, sem recomprimir, sem gravaExif). Logo o caminho LIMPO do nosso app carrega o EXIF
do aparelho E o gain map do hardware. EXIF não distingue nosso-limpo de nativa; só "Camera Estudo" (processado) e
"HDR+" (GCam) são inequívocos.
- **Ultra HDR VALIDADO:** foto do portão, drawer "Automático (ativo)", tudo de acabamento off -> 12,6 MP COM gain
  map. OUTPUT_FORMAT_JPEG_ULTRA_HDR (0.72) funciona. Fecha a validação pendente.
- **Reenquadramento:** no caminho limpo já somos qualidade nativa (12,6 MP + Ultra HDR + EXIF do HAL). O gap só
  surge quando o dono liga NOSSO processamento: acabamento/fusão recomprimem (perdem gain map) e noite cai p/ 5,1 MP.
- Alvo de qualidade real = preservar Ultra HDR/resolução ATRAVÉS do acabamento, não competir com o HAL no caminho limpo.
