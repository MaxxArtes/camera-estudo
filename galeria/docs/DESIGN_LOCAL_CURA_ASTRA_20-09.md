### 1. Local: máscaras e gestos

- **Máximo de 3 máscaras por foto**, incluindo Pessoa. Chips “Local 1/2/3”; seleção exclusiva. Botão “+” abre **Radial · Linear · Pessoa**. Pessoa só pode ser adicionada uma vez.
- **Radial:** nasce centralizada, com diâmetro de 50% da menor dimensão da prévia. Arrastar o interior move; duas alças nos eixos alteram os raios separadamente. **Sem rotação na v2.** Alças visuais de 12 dp, com alvo de toque de 48 dp.
- **Linear:** nasce vertical, centralizada. Duas alças delimitam a transição: uma marca **100%**, outra **0%** do efeito. Arrastar uma alça muda direção e largura; arrastar a linha central move o conjunto. Distância mínima entre alças: 32 dp na tela.
- **Pessoa:** usa a segmentação existente; sem alças geométricas.
- **Inverter** fica sempre acessível para a máscara selecionada. Radial recebe slider **Suavidade, 0–100%**, padrão 60%; a suavização ocorre para dentro da elipse. Na linear, a própria distância entre alças determina a suavidade.
- Excluir fica em botão explícito ao lado dos chips; exclusão desfazível.

### 2. Local: painel e ajustes

Dentro dos **144 dp**, use três linhas: máscaras **40 dp**, abas **Forma / Ajustes de 48 dp**, controles **56 dp**. Em Ajustes, uma faixa horizontal seleciona o parâmetro e um único slider edita seu valor.

**Entre com seis sliders:** Exposição **−2 a +2 EV**; Contraste, Saturação, Temperatura, Sombras e Realces **−100 a +100**, neutro em zero. **Adie Clareza:** exige processamento espacial e aumenta o risco de halos nas bordas das máscaras.

Sobreposição coral a **30% de opacidade** enquanto manipula a geometria. Em Ajustes, fica oculta; botão **“Ver máscara”**, alvo de 48 dp, mostra enquanto pressionado. **Tocar no slider não mostra a máscara:** é preciso enxergar o efeito real. Contorno e alças também somem durante o ajuste.

### 3. Cicatrizar

- **Toque aplica um círculo; arraste aplica uma pincelada contínua.** Uma sequência entre encostar e soltar gera exatamente uma ação de desfazer.
- Slider de diâmetro **8–60 dp**, padrão **24 dp**. O diâmetro se refere à tela e é convertido para pixels da foto conforme o zoom; ampliar permite tratar manchas menores.
- Durante o contato, mostrar o traço coral e um anel no tamanho do pincel. Uma lupa de **96 dp**, acima do dedo, revela o ponto encoberto.
- **Aplicar ao soltar**; retirar o coral quando o resultado estiver pronto. Não recalcular a cura a cada movimento.
- Botão pressionável **“Antes do retoque”**, alvo de 48 dp, mostra a imagem anterior à última pincelada.
- Texto de ajuda: **“Para manchas pequenas. Se borrar ou repetir textura, desfaça, amplie e use um pincel menor.”** Não declarar automaticamente que o resultado ficou ruim sem conseguir avaliar isso.

### 4. Conflitos

**Dentro de Local e Cicatrizar, segurar a prévia não compara.** O gesto pertence à ferramenta desde o primeiro contato. Ofereça botão **“Comparar” de 48 dp** na prévia, pressionável, para mostrar o original completo.

**Dois dedos sempre navegam:** pinça amplia; movimento conjunto desloca a foto. Nunca redimensionam a máscara radial, que usa somente as alças.

Se o segundo dedo entrar durante uma edição, **cancele o traço ou movimento ainda não confirmado** e inicie a navegação. Não deixe uma mancha acidental. Ao soltar os dois dedos, o próximo contato volta à ferramenta.

### 5. Riscos que você vai subestimar

- **Coordenadas:** máscaras e pinceladas devem ficar ancoradas na foto, preservadas após zoom, recorte e rotação. Guardar posições em dp quebra isso.
- **Sobreposição:** máscaras acumulam ajustes e podem estourar a imagem. A ordem de processamento precisa ser fixa; selecionar um chip não pode mudar o resultado.
- **Pessoa:** cabelo, dedos e transparências expõem erros da segmentação. Ajustes fortes e inversão tornam essas falhas muito visíveis.
- **Cura:** bordas de objetos, texto e padrões repetidos produzem borrões. Não prometa remoção de objetos; limite a ferramenta a pequenos retoques.
- **Desempenho e memória:** pinceladas longas e snapshots completos por ação custam caro. Processe a região afetada com margem e guarde apenas os trechos necessários ao desfazer.
- **Prévia versus exportação:** suavidade, tamanho do pincel e cura precisam escalar com a resolução. O resultado exportado deve corresponder ao que foi aprovado na tela.
