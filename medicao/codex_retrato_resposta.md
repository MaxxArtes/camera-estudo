codex
**Eu priorizaria contorno, exposição do rosto e cor antes de aumentar o desfoque.** Dá para aproximar bastante a aparência desejada com processamento em Kotlin; reproduzir profundidade e iluminação física do Pixel exigiria informações que o pipeline atual não tem.

Li integralmente os cinco arquivos pedidos e conferi os pontos de integração. Não alterei arquivos nem executei Gradle. Os parâmetros abaixo são propostas iniciais para teste, não valores internos da GCam nem desempenho já medido.

Nas imagens anexas:

- **Nosso retrato contra o céu:** há uma faixa clara/azulada perceptível no cabelo; o rosto fica relativamente apagado diante do fundo luminoso. Melhorar apenas o kernel não resolverá isso.
- **Xiaomi nativo:** o fundo escuro e a iluminação lateral já favorecem separação e volume no rosto. Parte da beleza vem da cena.
- **Nosso Vívido:** a imagem parece mais quente e apresenta textura/ruído evidente nas sombras. A saturação do ambiente compete com a pele.

Como são cenas diferentes, elas servem para orientar preferências, mas não para atribuir diferenças exclusivamente ao processamento.

**O que explica o resultado do Pixel**

São componentes complementares, desenvolvidos em épocas diferentes:

| Componente | Contribuição visual |
|---|---|
| HDR+ | Preserva altas luzes e melhora sombras antes do acabamento. |
| Segmentação e profundidade | Mantêm a pessoa em foco e organizam o desfoque espacialmente. |
| Renderização do bokeh | Produz círculos de desfoque e trata a composição entre planos. |
| Portrait Light | Melhora iluminação e volume do rosto sem apagar os detalhes. |
| Real Tone | Melhora detecção, exposição e reprodução da pele em condições e pessoas diversas. |

O HDR+ original combina exposições constantes, suficientemente baixas para proteger altas luzes; versões posteriores incorporam bracketing. Portanto, “o visual da GCam” não corresponde a uma única curva ou receita imutável. [HDR+ 2016](https://research.google/pubs/burst-photography-for-high-dynamic-range-and-low-light-imaging-on-mobile-cameras/), [HDR+ com bracketing](https://research.google/blog/hdr-with-bracketing-on-pixel-phones/).

Wadhwa et al. combinam segmentação com disparidade dual-pixel quando disponível, renderizam desfoque com discos e tratam oclusões. Há também um caminho de selfie com **apenas segmentação e fundo uniformemente desfocado**: essa é a referência mais adequada ao app atual. [Artigo de 2018](https://arxiv.org/html/1806.04171).

Minha ordem por ganho/custo:

| Prioridade | Mudança | Ganho esperado | Custo |
|---|---|---|---|
| 1 | Corrigir ampliação da máscara e contaminação do fundo | Alto, especialmente no cabelo | Baixo–médio |
| 2 | Medição no rosto e preenchimento tonal moderado | Alto em contraluz | Baixo–médio |
| 3 | Proteger pele também na matriz final | Alto para Vívido | Baixo |
| 4 | Unificar processamento, máscara e gravação | Qualidade, memória e tempo | Médio |
| 5 | Disco em resolução reduzida | Médio–alto em fundos com detalhes/luzes | Médio |
| 6 | Nitidez localizada e redução suave de brilho | Médio | Baixo |
| 7 | Profundidade heurística, realce de luzes e grão | Dependente da cena | Médio; deixar opcionais |

**1. Fundo: primeiro separar corretamente, depois desfocar**

Há um erro concreto em [Retrato.kt:49](/opt/camera-estudo/app/src/main/java/br/maxymus/cameraestudo/Retrato.kt:49): `enableRawSizeMask()` retorna a resolução de saída do modelo, normalmente menor, e não a resolução da foto. Sem essa opção, o SDK amplia a máscara. [Documentação do ML Kit](https://developers.google.com/ml-kit/vision/selfie-segmentation/android).

Consequências do código atual:

- A média 5×5 acontece na máscara pequena. Se ela tiver 256 pixels de altura, essa janela corresponde a aproximadamente **47 pixels** numa saída de 2400.
- A consulta `conf[my * mw + mx]` amplia por vizinho mais próximo.
- A curva S elimina confidências abaixo de 0,25 e satura acima de 0,75, podendo perder fios.
- O fundo é calculado a partir da imagem inteira: cabelo, rosto e roupa entram no borrão e podem vazar para fora da pessoa.
- Duas ampliações bilineares não transformam o kernel em disco. A redução forte também pode perder luzes pequenas e introduzir aliasing.

**Máscara proposta:** manter a saída bruta, ampliar bilinearmente para lado 600–800 e refinar com orientação pelas bordas da imagem. Como primeira versão, já vale remover a média 5×5 e trocar a ampliação atual por bilinear.

Para um refinamento posterior: filtro guiado sobre luminância normalizada, raio 2–4 pixels nessa resolução, `epsilon` inicial entre `0,001` e `0,004`; limitar a alteração à faixa incerta da máscara. Isso aproxima o contorno das bordas, mas não recupera cabelo que o modelo não reconheceu.

A confiança de segmentação **não é um alfa físico de transparência**. Tratar diretamente uma confiança de 0,5 como “metade cabelo” é uma aproximação.

**Evitar vazamento da pessoa no fundo:** usar convolução normalizada, em RGB linear:

```text
pesoFundo = 1 - smoothstep(0.05, 0.30, mascara)
fundo = disco(corLinear * pesoFundo) / max(disco(pesoFundo), epsilon)
```

Onde o denominador ficar pequeno, preencher com fundo válido próximo, em baixa resolução. Não dividir simplesmente e aceitar cores instáveis. É necessário preencher apenas a vizinhança que poderá aparecer na composição; não reconstruir todo o cenário atrás da pessoa.

**Kernel:** disco uniforme normalizado, com antialias na circunferência. Começaria com:

- Saída de lado 2400.
- Fundo calculado em lado 600.
- Raio padrão equivalente a **24 pixels na saída**, controle entre aproximadamente 8 e 40.
- Redução por médias sucessivas 2× para preservar melhor a energia de pontos luminosos.
- Conversão sRGB↔linear por LUT, sem `pow()` por pixel.

Em Kotlin, um disco de raio fixo pode ser calculado somando segmentos horizontais com prefixos por linha:

```text
para dy de -r até r:
    dx = floor(sqrt(r*r - dy*dy))
    soma += prefixo[y+dy][x+dx+1] - prefixo[y+dy][x-dx]
resultado = soma / quantidadeDeAmostras
```

Pré-calcular `dx` para cada `dy`. O custo passa de proporcional à área do disco para proporcional ao diâmetro. Num fundo 600×450 com raio 6, são cerca de 3,5 milhões de consultas de segmentos por canal, antes dos demais custos: um tamanho razoável para prototipar.

**Profundidade aproximada:** eu manteria fundo uniforme por padrão.

- Distância da borda da pessoa não representa distância à câmera.
- Tamanho do rosto pode controlar a intensidade estética, mas mistura distância, zoom, recorte e tamanho físico.
- Pessoa tocando a borda inferior não significa que seus pés estejam ali.
- Gradiente vertical falha em paredes, tetos, selfies inclinadas e objetos próximos.

Opcionalmente, para corpo inteiro com contato dos pés realmente identificado, usar um plano de chão aproximado:

```text
raioChao(y) = rMax * clamp(abs(y - yPe) / H, 0, 1)
```

`H` seria uma escala ajustável da perspectiva do chão. Aplicar somente à região reconhecida como chão; com máscara de pessoa e caixa facial apenas, **não há evidência suficiente para ativar isso automaticamente**. Dois níveis de blur interpolados bastariam para experimentar.

**Cabelo e feather:** não erodir toda a silhueta. Começar com erosão de no máximo 1 pixel final em trechos sólidos de roupa/ombro, transição de 2–4 pixels e preservar confidências intermediárias no cabelo. São pixels da saída, não da máscara 256.

Para cor de vazamento, um experimento restrito à borda:

```text
Festimado = (CorObservada - (1-alfa)*FundoLocal) / max(alfa, 0.3)
```

Aplicar só em alfa intermediário, com estimativa confiável do fundo, misturando no máximo 10–20% e limitando a correção. A fórmula fica instável e cria franjas quando o alfa está errado; deixaria desativada inicialmente.

**“Bolas” luminosas:** primeiro disco em luz linear. Só depois testar ganho de **1,0–1,4×**, antes do blur, em pequenos pontos de fundo com brilho alto e contraste positivo em relação à vizinhança. Não usar apenas `Y > limiar`: isso ilumina o céu inteiro. JPEG já estourado não contém a intensidade original da fonte.

**2. Rosto: preenchimento suave e detalhes seletivos**

O Portrait Light estima iluminação e geometria facial e prevê um mapa multiplicativo de baixa resolução. Essa separação permite alterar iluminação preservando detalhes. Um mapa tonal artesanal pode aproveitar essa ideia, mas não reproduz a iluminação direcional aprendida pelo sistema. [Portrait Light, 2020](https://research.google/blog/portrait-light-enhancing-portrait-lighting-with-machine-learning/).

Proposta barata:

1. Detectar rosto uma vez.
2. Criar máscara elíptica suave, orientada pelos olhos e intersectada com a pessoa.
3. Calcular luminância de baixa frequência numa miniatura da região facial.
4. Aplicar ganho progressivo nas sombras, sem elevar igualmente todo o rosto.

```text
B = luminância facial suavizada
pesoSombra = 1 - smoothstep(0.15, 0.55, B)
ganho = 2^(EV * mascaraFacial * pesoSombra)
corLinear *= ganho
```

Valores em luminância linear; começar com **EV = 0,25–0,5**, teto de 0,7 em contraluz. Raio de suavização equivalente a 8–12% da largura facial. Reduzir ganho em regiões ruidosas e perto da saturação; comprimir suavemente altas luzes em vez de cortar canais.

Não perseguir uma luminância facial fixa para todas as pessoas: isso clarearia indevidamente peles escuras. Usar contraluz, perda de detalhe nas sombras e controle do usuário como sinais.

**Contraste local:** opcional, intensidade pequena. Sobre luma de baixa resolução, realçar detalhes de escala intermediária em 5–10%, com raio equivalente a 2–4% da largura do rosto. Evitar CLAHE forte: realça poros, manchas e ruído.

**Brilho de oleosidade:** reduzir discretamente picos locais em testa e bochechas, excluindo olhos, lábios e cabelo. Detectar luminância acima do percentil 90 da pele **e** resíduo positivo em relação à base suave; comprimir apenas esse resíduo em 10–20%. Não apagar o brilho amplo que dá volume nem pintar textura sobre áreas estouradas.

**Olhos e boca:** adaptar o princípio de `nitidezTexto`, não chamar o filtro inteiro:

- Luma, gaussiana `sigma ≈ 0,8–1,0 px` na saída.
- Quantidade `0,4–0,7`.
- Limiar de resíduo `max(2, 1,5 × ruídoEstimado)`.
- Correção limitada a aproximadamente ±8 níveis de 8 bits.
- Máscaras suaves ao redor dos olhos; boca somente quando houver landmark confiável.

`Rostos.kt` pede todos os landmarks, mas guarda apenas os olhos. Guardar os landmarks da boca dispensaria nova inferência.

Em [Fusao.kt:104](/opt/camera-estudo/app/src/main/java/br/maxymus/cameraestudo/Fusao.kt:104), `nitidezLeve(q=3)` com limite ±60 é agressiva para retrato e não rejeita ruído. Pelo fluxo atual, ela é aplicada na fusão, não diretamente no retrato simples. Não a transportaria para o novo retrato.

Outra inconsistência: os coeficientes de `nitidezTexto` correspondem aproximadamente a `sigma=0,97`, não 0,7 como diz o comentário.

**Ruído/grão:** preservar textura facial, evitando alisar para depois reconstruir nitidez. Se o fundo ficar artificialmente limpo, testar ruído monocromático apenas nele, desvio padrão de **0,3–0,8 nível de 8 bits**, condicionado ao ruído da foto. A reinserção de ruído no fundo também aparece no artigo de Wadhwa para reduzir a aparência de recorte. [Discussão no artigo](https://arxiv.org/html/1806.04171).

**3. Pele: consistência sem inventar uma cor “correta”**

Real Tone envolve dados diversos e ajustes de detecção, exposição, balanço de branco e reprodução tonal. Não é uma tabela de “RGB ideal de pele”. Sem uma referência de iluminação, a média RGB mistura pigmentação, luz, exposição e processamento. [Descrição pública do Real Tone](https://blog.google/products-and-platforms/devices/pixel/image-equity-real-tone-pixel-6-photos/).

No app, corrigiria primeiro:

- [Acabamento.kt:47](/opt/camera-estudo/app/src/main/java/br/maxymus/cameraestudo/Acabamento.kt:47): a pele é protegida parcialmente na vibrância, mas depois recebe saturação global de 1,15. A proteção precisa abranger o resultado final.
- As faixas fixas Cb/Cr e o corte `lum > 40` podem excluir pele em sombra e incluir roupa/parede de cor semelhante.
- [Pessoas.kt:125](/opt/camera-estudo/app/src/main/java/br/maxymus/cameraestudo/Pessoas.kt:125): média global quase neutra não demonstra iluminação neutra. Cores do cenário podem se compensar.
- A correção por pessoa usa croma dependente da exposição, não limita suficientemente o deslocamento e alcança uma caixa retangular expandida, sem máscara de pessoa.
- Aprender automaticamente a referência pode incorporar dominantes e erros de reconhecimento.

**Receita inicial:** manter matiz de pele; limitar seu ganho de croma a **1,00–1,05**, mesmo no Vívido. Deixar o reforço maior para o ambiente. Misturar a versão original com a filtrada usando máscara facial/pele suave; assim a matriz final também fica protegida.

**Balanço de branco por rosto:** não neutralizar a pele como se fosse cartão cinza. Se houver referência aprovada da mesma pessoa sob luz neutra, comparar medianas de cromaticidade em bochechas/testa, excluindo brilho, barba e olhos. Usar correção pequena: 10–20% da diferença, com ganhos relativos por canal limitados inicialmente a ±5%, preservando luminância. Em luz mista, é uma correção local de cor, não um balanço de branco global verdadeiro.

**Validação com RGB médio:** é útil como alarme de mudança, não como verdade-terreno.

- Medir exatamente as mesmas regiões antes/depois.
- Registrar luminância, cromaticidade, dispersão e proporção de canais saturados.
- Converter pixels para Lab antes de agregar, quando possível.
- Para o acabamento conservador, investigar mudanças de matiz acima de 3° ou croma acima de 10% — limites iniciais, não prova automática de qualidade.
- Não comparar diretamente a média RGB dessas três fotos como se tivessem a mesma iluminação.

**4. Exposição: melhorar na captura vale mais que clarear JPEG**

Hoje há AF/AE por toque, mas o trecho de captura não implementa acompanhamento facial de exposição. Além disso, `tiraFoto()` encaminha HDR para `Modo.FOTO`; o retrato por software não passa por essa fusão.

No preview, detectar rosto em resolução baixa, aproximadamente 3–5 vezes por segundo. Atualizar medição só quando ele se mover significativamente, por exemplo 10% da largura da caixa, com intervalo mínimo de 500–800 ms. Suavizar a posição e respeitar medição manual.

```kotlin
// Centro facial já transformado para coordenadas do PreviewView.
val ponto = previewView.meteringPointFactory
    .createPoint(xVista, yVista, 0.18f)

val acao = FocusMeteringAction.Builder(
    ponto, FocusMeteringAction.FLAG_AE
).build()

if (camera.cameraInfo.isFocusMeteringSupported(acao)) {
    camera.cameraControl.startFocusAndMetering(acao)
}
```

O tamanho é normalizado; 0,18 é ponto inicial, ajustável à face. É essencial transformar rotação, recorte e espelhamento corretamente. AF pode usar outro ponto nos olhos; não acrescentaria AWB facial automaticamente. O CameraX aplica essas regiões conforme as capacidades do aparelho, sem garantir o mesmo comportamento entre fabricantes. [Medição no CameraX](https://developer.android.com/media/camera/camerax/configuration).

**Rosto contra céu:** AE facial pode clarear a face e estourar ainda mais o céu. Combinar prioridade facial com proteção de altas luzes, começando por compensações pequenas, de −0,3 a −0,7 EV quando houver clipping relevante, e preenchimento posterior moderado. Medir clipping por canal: céu ciano pode ter canais saturados sem luma acima de 250.

Se a captura não preservar céu e face simultaneamente, um JPEG único não resolve. HDR nativo, quando disponível no caminho escolhido, é a opção mais promissora. Não presumir que extensões HDR e BOKEH possam ser encadeadas.

Eu deixaria HDR próprio de retrato fora da primeira entrega: `Fusao.hdr` tem alinhamento global e não possui rejeição explícita de movimento facial. Piscar, cabelo e pequenos movimentos podem gerar fantasmas. A penalidade de “ruído” por laplaciano também confunde textura com ruído; exposição longa não é necessariamente a mais ruidosa.

**5. Tempo, memória e o que medir**

Para perseguir menos de 2 segundos, a mudança estrutural mais importante é:

```text
decodificar e orientar uma vez
→ detectar rosto e segmentar uma vez
→ corrigir tons/cor e acabamento facial
→ preparar fundo e máscara
→ compor
→ nitidez localizada
→ gravar JPEG e EXIF uma vez
```

Hoje há nova decodificação, possível segunda segmentação e nova compressão depois do retrato. O pedido inicial de decodificação até 4800 pixels também aumenta o pico de memória.

Para uma imagem 2400×1800, cada `IntArray` ou `FloatArray` integral custa aproximadamente **17,3 MB**; uma integral `LongArray`, 34,6 MB. As três integrais do embelezador são criadas sequencialmente, mas provocam bastante alocação. Preferir somas deslizantes, buffers reutilizados e mapas em baixa resolução.

Há ainda dois problemas funcionais:

- `Pessoas.processar()` chama `setPixels`, mas `decodeReduzido()` não garante bitmap mutável. Existe caminho em que a correção falhará; precisa garantir mutabilidade ou devolver uma nova imagem.
- Regravar JPEG remove metadados anteriores; `gravaExif()` repõe poucos campos e usa a hora do processamento. Preservar os metadados necessários antes de sobrescrever.

Como **orçamento de engenharia**, reservaria aproximadamente 300 ms para decodificação, 250 ms para inferências, 400 ms para máscara/fundo, 250 ms para acabamento/composição e 350 ms para gravação; os 450 ms restantes seriam margem. Isso precisa ser confirmado no aparelho. Se os 2 segundos incluírem captura, convergência AE e inicialização fria, o orçamento será mais apertado.

Antes de publicar, montaria uma bancada pequena, mesmo sem dataset formal:

| Teste | O que verificar |
|---|---|
| Mesma captura processada por cada variante | Isolar o efeito do algoritmo |
| Cabelo contra céu, parede clara e folhagem | Halo, fios perdidos e vazamento de roupa/pele |
| Luzes pequenas e janela grande | Discos suaves, sem céu artificialmente brilhante |
| Pele sob luz diurna, quente e mista | Matiz, clipping e preservação das diferenças reais |
| Óculos, barba, perfil e vários rostos | Proteção dos detalhes e comportamento em falhas |
| Rosto em movimento | Fantasmas, nitidez e estabilidade da medição |
| 30–50 execuções, frias e aquecidas | Tempo p50/p95, pico de memória e pausas de GC |

Guardar também a versão sem processamento para comparação A/B cega. Não usar variância do laplaciano como objetivo de qualidade: ela pode aumentar porque a foto ficou mais ruidosa.

**Eu cortaria da primeira versão:** profundidade por gradiente vertical automático, luz direcional simulada, realce de highlights, descontaminação agressiva de cabelo, aprendizado automático irrestrito da cor de pele e HDR próprio com movimento. Entregaria primeiro máscara correta, fundo sem vazamento, disco moderado, preenchimento facial discreto, proteção completa da pele e uma única gravação.
