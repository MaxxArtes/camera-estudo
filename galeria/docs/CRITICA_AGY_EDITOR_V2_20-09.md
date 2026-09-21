Seu plano tem uma excelente base arquitetural no que diz respeito ao gerenciamento de estado (JSON/receitas) e memória (nunca dois bitmaps grandes). No entanto, **o seu Plano A é uma bomba-relógio térmica e de performance**, incompatível com a premissa de "o melhor editor possível para celular". 

Aqui está a crítica dura e direta ao seu projeto, respondendo às suas perguntas:

### 1. A decisão A (CPU nas duas pontas) está certa?
**Absolutamente não. É o maior erro do plano.**
Fazer processamento espacial (como nitidez e desfoque) e ajuste de tons em arrays de inteiros na CPU para imagens de 16 MP (4096x4096) vai causar forte *thermal throttling* (superaquecimento) em minutos. O tempo de 1-2s que você estimou para exportação na CPU em testes curtos pode virar 5-8s com o aparelho quente, drenando bateria.

**A Solução (Equivalência GPU 100% garantida):**
Use **OpenGL ES 3.0 via EGL**. Como seu `minSdk` é 26, você não precisa de `RenderEffect` (que é dependente do Canvas de UI). 
- Crie uma engine onde o processamento é feito inteiramente em *Fragment Shaders* (GLSL).
- **Na Prévia:** Renderize para a tela (usando `GLSurfaceView` ou textura no Compose via `SurfaceTexture`).
- **Na Exportação:** Inicialize um contexto EGL *Headless* em uma thread de background (sem janela UI), anexe um FBO (Frame Buffer Object) do tamanho da exportação, rode **exatamente o mesmo shader** da prévia, e leia os pixels de volta usando `glReadPixels` ou vinculando um `HardwareBuffer` / `ImageReader` (API 26+ suporta bem).
Isso garante 100% de paridade (mesma matemática) e reduzirá o tempo de exportação de segundos para dezenas de milissegundos.

### 2. Fórmulas para Realces/Sombras/Brancos/Pretos e Auto
Usar curvas simples em pixels isolados cria cores lavadas ("flatness") e destrói o contraste local.
- **Sombras e Realces (Estado da Arte):** Você precisa de **separação de frequências**. Extraia a luminância, aplique um *Guided Filter* (ou Filtro Bilateral) rápido para separar a imagem em "Base" (áreas grandes) e "Detalhes" (texturas). Aplique o ganho de sombras/realces **apenas na Base**, e depois some os Detalhes de volta. Isso ilumina os rostos sem criar aquele halo falso ou perder a textura da pele.
- **Brancos e Pretos:** Use uma curva *Spline* suave nas extremidades do canal de luminância. Para não desbotar (problema clássico), calcule a razão de mudança de luminância (L_novo / L_original) e aplique essa mesma razão aos canais de cromaticidade.
- **Auto:** Não use equalização de histograma local (CLAHE), pois altera drasticamente a iluminação original (fotos noturnas viram dia). O ideal é um **alongamento de histograma restrito**: ache os percentis 1% e 99% da luminância e mapeie-os para 0 e 255, mas aplique um teto no ganho máximo para não estourar os ruídos.

### 3. Bokeh com máscara (Vazamento de Borda)
Se você apenas desfocar a imagem original inteira e colar o rosto cortado pela máscara por cima, os pixels claros da borda do rosto "vazarão" para o fundo desfocado, criando um halo brilhante ao redor da pessoa.
**A Solução (Weighted Convolution):**
Ao fazer o desfoque gaussiano separável do fundo, você deve usar a máscara invertida (Pessoa = 0.0, Fundo = 1.0) como *peso*. 
No cálculo do kernel de desfoque, multiplique a cor do pixel vizinho pelo peso dele na máscara. Divida o resultado pela soma dos pesos acumulados. Assim, a matemática do desfoque "ignora" a existência da pessoa. Opcionalmente, dilate a máscara alguns pixels para dentro do fundo antes de fazer isso para garantir que nenhuma franja de pele entre no cálculo.

### 4. Inpainting: Telea vs Modelo
- **Telea (CPU/Kotlin):** Só serve como "Pincel de Cura" para raio pequeno (espinhas, manchas no céu, um fio de poste fino). Tentar remover um carro ou uma pessoa com Telea/Navier-Stokes resultará num borrão manchado inaceitável. O custo computacional na CPU para áreas grandes também será gigante.
- **Modelo (ex: LaMa mobile):** É **obrigatório** se você quer competir. Um modelo LaMa otimizado para TFLite (via NNAPI ou GPU delegate) pesa entre 20 MB e 40 MB e compreende semântica (reconstrói texturas de tijolos, grama). 
**Veredito:** Tenha as duas ferramentas. Chame o Telea de "Cicatrizar" (rápido, determinístico) e o LaMa de "Apagar Mágico" (demora ~1s, gasta VRAM, mas faz milagre).

### 5. O que quebra primeiro (Guarda de Código)
1. **GC Churn (Lixo de Memória):** Se você instanciar `IntArray(16_000_000)` a cada exportação ou preview, o Garbage Collector vai paralisar o app (Jank/ANR). **Guarda:** Crie *Object Pools* estritos. Se for de CPU, reuse o mesmo `IntArray` e o mesmo `Bitmap` via `inBitmap`. Nunca aloque memória dentro de loops ou durante a exportação.
2. **Thermal Throttling (Aquecimento):** Como dito, loops pesados em CPU. **Guarda:** Implemente monitoramento de frame time. Se o frame drop for alto, reduza dinamicamente a resolução da prévia (ex: de 1024px para 720px) em tempo real.
3. **OOM Silencioso no Inpainting/TFLite:** Carregar o tensor de entrada (ARGB -> Float32) de 16MB para a NPU ao mesmo tempo que guarda as cópias de desfazimento. **Guarda:** `ComponentCallbacks2.onTrimMemory`. Intercepte a pressão de memória, libere os bitmaps de preview e de *Undo* para o disco temporário ANTES de iniciar a inferência pesada.

### 6. Ordem de implementação (Caminho Crítico)
Para não ter que jogar código fora quando a performance bater no teto:

1. **Infraestrutura GPU (O Core):** Escreva a pipeline em GLSL com o contexto EGL Headless. Prove que consegue carregar um Bitmap, rodar um shader de Matriz de Cor (que você já tem), e devolver um JPEG de 4096px sem estourar memória.
2. **Pipelines de Tom/Cor:** Escreva os shaders complexos (Sombras, Realces com separação de frequências).
3. **Pipeline de Inteligência/Bokeh:** Integre o ML Kit (segmentação). Passe a máscara gerada para a GPU como uma segunda textura (`GL_TEXTURE1`) e escreva o shader de desfoque com peso (Weighted Blur).
4. **Estado e UI:** Amarre a geração do JSON (receita) aos parâmetros do Shader e construa o salvamento em Lote.
5. **Inpainting:** Deixe por último. É um módulo alienígena no fluxo, pois é o único que altera os pixels originais da imagem base de forma destrutiva no pipeline, forçando a criação de um "novo" Bitmap original antes da cadeia de filtros.
