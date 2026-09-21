## 1. Organização das ferramentas

**Faixa horizontal rolável de 72 dp, com ícone e nome sempre visíveis.** Ordem fixa; sem reorganizar por frequência:

| Ordem | Nome | Ícone |
|---|---|---|
| 1 | Luz | Sol |
| 2 | Cor | Gota |
| 3 | Recortar | Recorte |
| 4 | Filtros | Três círculos |
| 5 | Detalhe | Triângulo dividido |
| 6 | Fundo | Pessoa com fundo |
| 7 | Marcações | Lápis |
| 8 | Corrigir | Quadrilátero |

Em **360 dp, mostrar quatro grupos completos e parte do quinto**, sinalizando rolagem. Alvos de toque de pelo menos **48 dp**.

Painel de **144 dp** para ajustes simples: subferramentas em linha rolável, um controle ativo, valor numérico e botão Redefinir. Luz reúne exposição, contraste, realces, sombras, brancos e pretos; Cor reúne temperatura, matiz, saturação e, depois, HSL.

**Não espremer tarefas complexas em 144 dp:** Fundo, Texto e Perspectiva abrem modos dedicados, com Concluir/Cancelar. Máximo de dois níveis: grupo → ferramenta.

## 2. AUTO

**“Auto” com ícone de varinha, fixo acima da faixa, à direita**, acessível de qualquer grupo.

Ajusta somente luz, balanço de branco e saturação moderada. Não altera enquadramento, rosto ou fundo. Após aplicar, mostrar **“Auto aplicado · Intensidade”**, de 0–100.

É **uma única entrada no Desfazer**; ajustar intensidade constitui outra entrada ao soltar o controle. Tocar novamente recalcula sem acumular o efeito. Ajustes manuais posteriores continuam disponíveis.

## 3. Fundo

Entrar em Fundo mostra quatro opções: **Desfocar · P&B · Substituir · Remover**. Substituir oferece Cor ou Foto.

Selecionada a opção, executar a segmentação uma vez e reutilizar a máscara. Durante os **0,5–2 s**, manter a foto visível, mostrar “Separando pessoa…” e indicador indeterminado. Nada de percentual inventado. Permitir cancelar.

- **Desfocar:** intensidade 0–100.
- **Remover:** transparência em xadrez; exportação PNG explicitada.
- **Refinar seleção:** botão presente em todas as opções; modos **Adicionar / Remover**, sobreposição coral translúcida.
- Pincel de **8–80 dp na tela**, padrão 24 dp, com círculo indicando alcance.
- Um dedo pinta; **dois dedos movem e ampliam**, de 1× a 8×. Cada traço pode ser desfeito.

Suavização de pele fica em **Corrigir → Pele**, evitando esconder retoque de rosto dentro de Fundo.

## 4. Marcações e camadas

**Texto, traços, adesivos e tarjas permanecem editáveis até a exportação.**

Tocar seleciona; arrastar move; alça de canto redimensiona; alça separada gira. Exibir ações **Editar · Duplicar · Apagar** para o item selecionado. Dois dedos ficam reservados ao zoom da foto.

Botão **“Camadas · N”** abre uma folha de até **60% da altura**, com miniatura, nome, visibilidade e alça para reordenar. Selecionar na lista resolve objetos sobrepostos. Agrupar os traços de uma sessão de desenho em uma camada.

Mosaico: pincel **12–96 dp**; controle **“Tamanho dos blocos”**, com prévia fiel ao arquivo exportado. Desfoque: mesmo pincel, intensidade 0–100.

**Acrescentar Tarja sólida**, opção preferencial para ocultar informação sensível: mosaico e desfoque podem deixar conteúdo reconhecível.

## 5. Perspectiva

**Corrigir → Perspectiva**, em modo dedicado. Reaproveitar o quadrilátero do scanner com **quatro alças de toque de 48 dp**, lupa durante o arraste e grade interna.

Usar rótulo **“Alinhe os quatro cantos da superfície”**. Botão **“Detectar cantos”** propõe uma seleção; nunca aplica sozinho.

Mostrar o resultado retificado antes de concluir. Recorte continua sendo enquadramento; aqui o usuário está **endireitando uma superfície**, com linguagem e controles próprios.

## 6. Lote

Na seleção múltipla: **Aplicar edição → escolher uma receita salva ou a última edição → revisar → aplicar**.

Receita permitida: **luz, cor, filtro, nitidez, vinheta e granulação**. Excluir recorte, perspectiva, máscaras, fundo, marcações e retoques locais. Mostrar explicitamente quais ajustes foram excluídos.

Auto deve ser uma opção separada: **“Ajustar automaticamente cada foto”**, porque recalcula por imagem.

**Limite inicial: 30 fotos, processamento sequencial e salvamento como cópias.** Mostrar “12 de 30”, progresso total e Cancelar. Cancelamento preserva as cópias concluídas; falhas individuais não interrompem o restante. Ao terminar: quantidade salva, falhas e acesso aos resultados.

## 7. Cortes, adiamentos e acréscimos

**Cortar agora:** desfoque global sem finalidade clara, olhos vermelhos e biblioteca de adesivos. Molduras ficam para depois.

**Adiar:**

- **Apagar objeto:** segmentação identifica a região, mas não fornece preenchimento convincente. Exige solução própria e validação de qualidade.
- **Curvas por canal e redução de ruído:** depois de Luz, Cor e HSL estarem sólidos.
- **HDR e HEIC:** fase independente, com validação de leitura, edição e exportação; versão do Android sozinha não garante o fluxo.
- **Salvar por cima:** somente após recuperação e reedição estarem confiáveis.

**Antecipar a arquitetura não destrutiva para F2.** Receita versionada e camadas precisam nascer com as ferramentas; a interface para reabrir pode chegar na F6.

Acrescentar **comparação antes/depois por pressão**, Redefinir por ferramenta, aviso de alterações ao sair e exportação com dimensões, qualidade e opção de remover localização.

## 8. Riscos subestimados

- **Descoberta:** oito grupos já são o limite. Preservar nomes e ordem; não adicionar uma segunda faixa de categorias.
- **Consistência:** um arraste de controle ou traço equivale a um passo de desfazer. Cancelar um modo restaura exatamente o estado de entrada.
- **Latência:** prévia reduzida durante interação; resultado refinado ao soltar. Buscar **resposta visual abaixo de 100 ms** e indicar quando estiver refinando.
- **Memória:** uma foto de 12 MP em RGBA ocupa cerca de **48 MB por bitmap**. Não guardar bitmaps completos no histórico; guardar operações, limitar caches e processar lote sequencialmente.
- **Máscaras imperfeitas:** cabelo, acessórios e bordas precisam de refinamento acessível, sem promessa de recorte perfeito.
- **Prévia enganosa:** nitidez, ruído, mosaico e granulação precisam de inspeção em **100%**.
- **Compatibilidade:** manter o mesmo significado dos controles no caminho AGSL e no fallback; não oferecer um efeito cuja exportação divirja da prévia.
