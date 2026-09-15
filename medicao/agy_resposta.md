Sua abordagem de medir empiricamente antes de codar no Android é excelente (e rara). Seus números sintéticos estão coerentes com a literatura, mas a transição do Python para o celular com CameraX esconde armadilhas severas.

Aqui está a crítica dura e as respostas para suas perguntas:

### A. O que o sintético esconde do celular?
1. **A Latência do `takePicture`:** Esse é o **ponto de falha do seu plano**. O CameraX não foi feito para rajadas rápidas via `takePicture`. Cada chamada pode levar de 200ms a 500ms (foco, exposição, processamento do fabricante, conversão JPEG). Uma rajada de 4 fotos pode demorar mais de 1 segundo. Nesse tempo, a rotação 3D da mão e a translação serão brutais (muito maiores que 10px e 0.4°), quebrando a premissa de translação 2D do MTB.
2. **O Processamento "Cozido" do JPEG:** JPEGs do Android já vêm com forte redução de ruído (NR) e sharpening do ISP (Image Signal Processor) do fabricante. O ruído não será mais gaussiano (será "grumoso") e fundir JPEGs fortemente nítidos pode gerar halos e texturas artificiais.
3. **Rolling Shutter:** O tremor da mão durante a captura vai entortar a imagem diagonalmente. Alinhamento global (MTB) não corrige deformação de rolling shutter, causando perda de nitidez na fusão.
4. **Fantasmas Reais (Ghosting):** O sintético é estático. Na vida real, folhas amassam, pessoas piscam, o vento bate. O seu merge precisa de um mecanismo agressivo de rejeição de movimento (*deghosting*), senão você terá "sombras" ou duplicação.

### B. Mertens: Níveis e Otimização de Luminância
O "estouro" (clipping/perda de contraste) com poucos níveis ocorre porque a banda base (frequências baixas) fica grande demais, achatando o contraste global onde não deveria.
*   **Quantos níveis?** A regra de ouro da pirâmide laplaciana é descer até que a imagem base tenha entre 16x16 e 32x32 pixels. Para uma imagem de 2000px, $\log_2(2000/16) \approx 7$. **Use 6 ou 7 níveis.** Menos que isso causa halos luminosos ao redor de bordas escuras.
*   **Apenas Luminância? Sim, fortemente recomendado!** O olho humano é insensível à resolução de cor. Calcule os pesos (contraste, bem-exposto) no canal Y. Construa a pirâmide gaussiana dos pesos e a laplaciana do canal Y, funda e reconstrua o Y final. Para a cor (CbCr ou UV), simplesmente adote a cor do quadro que teve o maior peso naquele pixel, ou faça uma média ponderada linear simples sem pirâmide. Isso derruba seu custo de CPU e RAM em 3x.

### C. Merge Robusto e Estimativa de Sigma
*   **Exponencial vs Wiener:** A sua função $exp(-(d/\tau)^2)$ é ótima para **rejeição de fantasmas**, pois o peso cai a quase zero rapidamente se o pixel mudar muito (movimento). A função de Wiener ($w = C / (d^2 + C)$) decai lentamente, o que mistura mais os pixels; é matematicamente ideal para o ruído gaussiano contínuo, mas gera "fantasmas translúcidos" em objetos em movimento. Como o seu maior problema será movimento/fantasmas (devido à lentidão do CameraX), **mantenha sua exponencial**.
*   **Estimando o Sigma:** Sem Raw e sem verdade-terreno, o melhor caminho no celular é usar o estimador **MAD (Median Absolute Deviation)**. Pegue um quadro, aplique um filtro passa-alta rápido (como um Laplaciano `[0 1 0; 1 -4 1; 0 1 0]`) para remover a estrutura da imagem, e calcule a mediana absoluta dos pixels resultantes. Como o ruído muda conforme o claro/escuro, você pode estimar um sigma global baseado na região mais escura ou usar uma relação baseada no ISO da foto (dá pra ler na metadata do `ImageProxy`).

### D. Scanner: Antes ou Depois do Recorte?
**DEPOIS (na folha já retificada).**
1. **Geometria:** O tremor da mão em close-up de documentos causa forte mudança de perspectiva (3D). MTB não resolve perspectiva.
2. **Registro perfeito:** Se você rodar seu detector de bordas em todos os quadros (ou nas extremidades da rajada) e fizer o Warp, as quatro imagens retificadas **já estarão geometricamente alinhadas**, porque o retângulo de destino é o mesmo! O Warp anula a perspectiva e a translação num golpe só.
3. **Eficiência:** Em vez de processar uma imagem cheia de cenário de fundo, o merge vai rodar apenas no array menor (ex: 1000x1400) do documento já cortado.
*Dica:* Após o Warp, pode sobrar um desvio de 1 ou 2 pixels se o detector de cantos flutuar. Um refino SAD em ladrilhos pequenos (ou até correção global rápida) nas imagens retificadas resolve isso barato.

### E. O que medir agora e o que CORTAR do plano
**O que medir URGENTEMENTE antes de codar:**
1. **O Tempo da Rajada:** Faça um app de 1 botão que chama `takePicture` 4 vezes no CameraX e meça o tempo total do future 1 ao future 4. Se passar de 500ms, o cenário sintético não serve mais.
2. **Pico de Memória (OOM):** Alocar `FloatArray` para pirâmides de 5 níveis de imagens de 3 Megapixels vai espancar o Garbage Collector. Meça o uso de RAM no Profiler.

**O que eu CORTARIA ou ALTERARIA do seu plano:**
1. **Corte o JPEG -> Bitmap.** Não faça isso. No CameraX, configure a captura (ou o `ImageAnalysis`) para entregar **YUV_420_888**. O canal Y (Luminância) já vem direto da memória em um `ByteBuffer` (sem descompressão JPEG cara!). Você faz o MTB e o Merge Robusto direto nesse buffer de bytes, economizando tempo e evitando a maquiagem do ISP do Android.
2. **Mude o paradigma de captura se for lento:** Se o `takePicture` for lerdo, passe a usar o `ImageAnalysis` configurado em alta resolução (ex: 1440p). Ele cospe quadros a 30fps. Para rajada de HDR/Scanner, você apenas salva os últimos 4 `ImageProxy` do buffer quando o usuário apertar o botão. O alinhamento será mínimo pois as fotos foram tiradas em uma fração de segundo! A qualidade final baseada em quadros YUV empilhados muitas vezes supera a de 1 JPEG estático do Android.
