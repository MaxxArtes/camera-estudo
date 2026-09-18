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
