codex
**O maior ganho está em preservar resolução e parar de borrar o texto no modo Tela.** Antes de implementar Lanczos ou super-resolução, corrigiria esses dois pontos.

Li `Documento.kt` inteiro, o editor e as rotinas relevantes de `Fusao.kt`. Não alterei arquivos nem executei Gradle.

Os arquivos anexados têm estas dimensões reais:

| Saída | Dimensões | Pixels |
|---|---:|---:|
| Nossa | 1257×1400 | 1,76 MP |
| Xiaomi | **2967×3301** | **9,79 MP** |

A Xiaomi entrega **2,36× mais amostras por eixo, 5,57× no total**. Visualmente também conserva melhor os contornos. Isso não permite concluir qual algoritmo proprietário usa, mas já explica boa parte da diferença.

Há outra distinção decisiva: **a imagem nossa veio do modo Tela, que não executa `realca()` com retinex**. Executa redução a 70%, ampliação, caixa 5×5, contraste global e nitidez. Portanto, este par não demonstra uma falha do retinex.

Minha ordem por ganho/custo:

| Prioridade | Mudança | Ganho esperado | Custo |
|---|---|---|---|
| 1 | Remover suavização global obrigatória do modo Tela | Muito alto neste caso | Baixo; processamento fica mais barato |
| 2 | Garantir pixels reais da captura até a saída de 3200 | Alto | Baixo a médio |
| 3 | Foco, estabilidade e escolha do melhor quadro | Alto se a captura estiver limitada | Baixo a médio |
| 4 | Realce local suave específico para texto | Médio, depois dos anteriores | Médio, linear no número de pixels |
| 5 | USM menor e controlada | Pequeno a médio | Baixo |
| 6 | Bicúbica | Incremental | Médio |
| 7 | Super-resolução/deconvolução | Incerto | Alto |

**1. Resolução: 3200 é um bom alvo, mas hoje não chega a todos os caminhos**

Em [Documento.kt](/opt/camera-estudo/app/src/main/java/br/maxymus/cameraestudo/Documento.kt:228):

- **Tela continua limitada a 2000**, por `min(ladoSaida, 2000)`.
- **Rajada decodifica até 2600**, por `min(ladoFonte, 2600)`, antes do recorte.
- `escS = min(1f, ...)` impede ampliar: escolher 3200 significa um teto, não uma garantia.
- A resolução necessária da fonte depende de quanto o documento ocupa na fotografia. `ladoSaida * 1,25` não assegura detalhe suficiente em um recorte pequeno.

Exemplo: documento ocupando 70% do eixo útil de uma foto de 4000 px tem aproximadamente 2800 amostras nesse eixo. Exportar 3200 não cria as restantes.

Para **A4 inteira, corretamente proporcionada**, a equivalência aproximada é:

| Lado maior | dpi equivalentes | Corpo nominal de 6–7 pt |
|---:|---:|---:|
| 1400 | 120 | 10–12 px |
| 2400 | 205 | 17–20 px |
| 3200 | 274 | 23–27 px |

A altura efetivamente desenhada das letras é menor que o corpo nominal. Esses dpi **não devem ser atribuídos diretamente aos anexos**, cuja proporção não é A4.

Proposta: detecção continua em 640; captura suficiente para o recorte; uma única transformação geométrica até 3200. Registrar dimensões do JPEG, imagem decodificada, quadrilátero em pixels e saída. **3200 provavelmente resolve grande parte, desde que sejam pixels úteis, focados e preservados.**

**2. Warp: manter bilinear primeiro**

O problema atual maior está em [suavizaMoire()](/opt/camera-estudo/app/src/main/java/br/maxymus/cameraestudo/Documento.kt:74):

1. Reduz a 70%.
2. Amplia de volta.
3. Aplica caixa separável de **5×5**, apesar do comentário dizer 4×4.
4. Tenta recuperar bordas com nitidez.

Esse caixa tem desvio-padrão equivalente de aproximadamente **1,41 px por eixo**, antes de contar as duas reamostragens. Para traços de 1–2 px é agressivo: junta letras e fecha espaços internos. USM posterior não recupera informação eliminada.

Para tela com texto, começaria com **warp direto e redução de crominância**, preservando luminância. Moiré de luminância exige outro tratamento; se coincidir com a frequência dos traços, não existe filtro simples que remova a trama sem afetar letras. Ajustar distância/ângulo da captura pode ser mais eficaz.

Sobre interpolação:

- Bilinear: 4 amostras por pixel.
- Bicúbica: 16.
- Lanczos-3: 36, com risco de ringing ao redor de letras e códigos.

Essas contagens não são multiplicadores exatos de tempo, especialmente comparando Canvas nativo com Kotlin. Em redução forte, bicúbica/Lanczos com suporte fixo também não substituem filtragem antialias adequada à escala.

**Testaria bicúbica só depois**, com pesos tabelados e transformação inversa incremental por linha. Lanczos manual não seria requisito da próxima versão.

**3. Realce: manter correção de iluminação e acrescentar uma curva local para texto**

Retinex simplificado e limiar adaptativo resolvem problemas diferentes:

- Divisão pelo fundo: corrige iluminação.
- Limiar local: separa tinta e papel.
- Percentis globais: ajustam contraste, mas não garantem separação de letras em cada região.

O caminho existente é razoável para documentos mistos. Para texto, proponho uma variante **Sauvola suave**, preservando cinzas nas bordas. O Sauvola original é um método de binarização; a mistura abaixo é uma proposta de implementação, não uma reprodução do processamento Xiaomi. [Artigo original](https://www.sciencedirect.com/science/article/pii/S0031320399000552).

Algoritmo inicial para experimentar:

1. **Warp único** na resolução final, sem caixa global.
2. Extrair luminância `Y`.
3. Estimar fundo `B` em grade reduzida, privilegiando pixels claros. Em blocos sem evidência de papel, interpolar dos vizinhos confiáveis; um bloco inteiramente escuro não deve ser tratado como papel.
4. Normalizar suavemente: `L = clamp(245 × Y / max(B, piso), 0, 255)`, limitando o ganho em regiões escuras. O piso e o limite precisam ser calibrados.
5. Calcular média `m` e desvio-padrão `s` locais. Começar com janela **41×41 em 3200 px**, escalada com a resolução; testar 31/41/61.
6. Calcular:
   `T = m × [1 + k × (s/128 − 1)]`, começando com `k = 0,20`.
7. Criar transição suave:
   `u = clamp((L − T + d)/(2d), 0, 1)`
   e `S = 255 × u² × (3 − 2u)`, começando com `d = 16`.
8. Misturar: `Yfinal = (1 − α) × L + α × S`, começando com `α = 0,5`.
9. Aplicar menos ou nenhum realce local em fotos, logos e regiões coloridas. Sem classificação confiável, disponibilizar isso como **estilo Texto**, mantendo o aprimorado atual para documentos mistos.
10. Nitidez opcional pequena; uma única compressão JPEG final.

Os parâmetros são **pontos de partida para A/B**, não valores já validados. Binarização dura deve ser uma opção separada: pode apagar pontuação, marcas d’água e traços fracos.

Começaria por Sauvola. Wolf pode ajudar em determinados documentos de baixo contraste, mas acrescenta dependência de estatísticas globais e não resolve falta de resolução.

Para desempenho: somas deslizantes de `Y` e `Y²`, buffers reutilizados e processamento por faixas. Se usar imagens integrais, usar `Long` nas somas quadráticas: `Int` transborda rapidamente.

**4. Nitidez: ajustar USM antes de deconvoluir**

[nitidezLeve()](/opt/camera-estudo/app/src/main/java/br/maxymus/cameraestudo/Fusao.kt:104) usa caixa 3×3, `q=1,5` e limite de ±60 níveis. Não é uma gaussiana com σ=0,7. Não possui limiar de ruído, e a calibração citada nos comentários veio de selfies.

Proposta para texto:

- Gaussiana separável com **σ entre 0,6 e 0,8 px**, explicitando σ para evitar ambiguidade de “raio”.
- Quantidade inicial de 0,5–1,0.
- Transição suave que ignore resíduos de 2–4 níveis.
- Limitar correção inicialmente a ±15–25 níveis.
- Reduzir ou dispensar USM quando o realce local já produzir bordas fortes.

A escala relevante é a largura do traço em pixels; apenas mudar a etiqueta de dpi não muda o filtro.

Deconvolução exige estimar o borramento. PSF errada produz contornos falsos, ringing e amplificação da trama do monitor. **Não colocaria Richardson–Lucy/Wiener no caminho padrão de menos de 2 s.**

**5. Captura e rajada: primeiro o melhor quadro**

O app já oferece foco por toque com AF+AE e configura `MAXIMIZE_QUALITY` quando `qualidadeMax` está ativo e não há rajada/HDR. Em rajada, usa `MINIMIZE_LATENCY`.

Na captura simples de documento, eu priorizaria:

- Câmera principal, documento grande no enquadramento, aparelho aproximadamente paralelo.
- Foco sobre letras, esperando o resultado do AF antes da captura.
- Boa luz e pouco movimento.
- Conferir a resolução realmente negociada pelo CameraX; a opção 3200 da interface controla o pós-processamento.
- Preservar JPEG de entrada com qualidade alta. A documentação indica padrões 100 para `MAXIMIZE_QUALITY` e 95 para `MINIMIZE_LATENCY`, quando não sobrescritos. Isso não garante acesso ao pipeline do aplicativo Xiaomi. [CameraX](https://developer.android.com/reference/androidx/camera/core/ImageCapture.Builder).

**A fusão atual não faz super-resolução.** MTB e refino retornam deslocamentos inteiros; a mistura permanece na mesma grade. Retificar todos com a mesma matriz também não os deixa automaticamente alinhados: o movimento entre capturas permanece.

Para texto bem iluminado, a fusão pode trazer pouco ganho e até borrar. Em ruído independente, a média de quatro quadros perfeitamente alinhados tem ganho ideal de SNR de 6 dB; isso **não equivale a dobrar resolução** e não é uma previsão para a fusão robusta atual.

Antes de super-resolução, experimentaria selecionar o melhor de três quadros usando regiões de texto, com penalização de ruído/moiré. A variância global do laplaciano pode preferir justamente a trama ou o ruído.

Super-resolução verdadeira requer registro subpixel confiável, fases diferentes, rejeição de movimento e reconstrução conjunta, preferencialmente antes de reamostrar os quadros. Há demonstração publicada em RAW no Google, mas ela não prova uso pela Xiaomi nem desempenho equivalente em Kotlin sobre JPEG. [Pesquisa sobre super-resolução multiquadro](https://research.google/pubs/handheld-multi-frame-super-resolution/).

**Não é possível afirmar que a Xiaomi usa vários quadros neste modo a partir desses dois JPEGs.**

**6. Medir antes de publicar**

Faria primeiro um ensaio que separa captura e processamento: **mesmo JPEG original, mesmos cantos**, variando uma etapa por vez.

Sequência:

1. Pipeline atual.
2. Sem `suavizaMoire`.
3. 2400/3200 efetivos.
4. USM menor.
5. Realce local suave.
6. Bicúbica, apenas se ainda houver benefício relevante.

Comparar recortes de letras pequenas, números, pontuação e códigos, tanto em pixels nativos quanto no mesmo tamanho de exibição. Fotografias diferentes do mesmo documento não isolam o efeito dos algoritmos.

Sem OCR:

- Largura de transição 10–90% de bordas, acompanhada de overshoot/halos.
- Contraste tinta/papel e ruído em regiões uniformes.
- Preservação dos espaços de `e`, `a`, `8`, separação entre caracteres e pontos.
- Decodificação correta de QR/código de barras como teste complementar.
- Leitura humana cega de campos previamente transcritos.

**Variância do laplaciano sozinha não serve como juiz de legibilidade**: sharpening, ruído e moiré podem aumentá-la.

Com OCR: vale adicionar **ML Kit Text Recognition somente em uma variante de benchmark/debug**. Confirmei que o projeto tem ML Kit para rosto e segmentação, não reconhecimento de texto.

Medir **CER** — inserções, remoções e substituições divididas pelos caracteres de referência — e acerto exato de campos numéricos. Comparar regiões correspondentes para não confundir ordenação de leitura com erro de reconhecimento. Mais palavras reconhecidas ou maior confiança não bastam. O ML Kit recomenda caracteres com informação suficiente, idealmente pelo menos 16×16 px; ampliar artificialmente não substitui essa informação. [Orientações do ML Kit](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).

Para a meta de tempo, medir p50/p95 por etapa e ponta a ponta após confirmar os cantos, incluindo decode e JPEG, em aparelho médio físico. OCR de avaliação fica fora desse orçamento.

**Outras críticas concretas ao código**

- `decodeReduzido()` **não impõe teto exato**: uma fonte de 4608 px com pedido de 2400 pode continuar em 4608. Afeta memória e tempo.
- Sem recorte, o tamanho final não é necessariamente limitado por `ladoSaida`.
- `fundoClaro()` aceita pixels acima de média local menos 8: em bloco escuro uniforme, aceita o próprio escuro como fundo. Além disso, suavizar médias de blocos não equivale exatamente a suavizar numerador e peso da convolução normalizada.
- O percentil de preto pode ser dominado pelas barras e molduras; a classificação de “colorido” por média global pode ignorar um pequeno logo.
- Forçar proporções com tolerância de 10–12% pode deformar letras. Preferiria proporção explícita quando conhecida.
- `ordena()` por extremos de soma/diferença ainda pode selecionar pontos repetidos perto de 45°, mesmo após obter quatro vértices pelo casco.
- `runCatching().getOrNull()` esconde falhas e cancelamentos; `compress()` tem seu retorno ignorado.
- O editor conserva coordenadas normalizadas e não parece ser a causa principal da perda de detalhe.

Para dimensionar memória: A4 a 3200 ocupa cerca de **7,24 MP; cada `IntArray` ou bitmap ARGB, 29 MB**. Na fusão atual, quatro quadros com RGB+luminância e quatro acumuladores já representam aproximadamente **348 MB**, antes de pirâmides, saída e demais objetos. Não usaria essa arquitetura em resolução plena sob heap de 512 MB.

A próxima versão que eu validaria teria **captura única, 3200 efetivos, warp bilinear único, nenhum borramento global obrigatório, realce local opcional e USM discreta**. É um candidato plausível à meta de 2 s com buffers controlados; o prazo precisa ser confirmado no aparelho.
