Eu manteria a paleta, as duas abas e a organização por dia. **A maior oportunidade está no modo de seleção:** simplificar as ações, esclarecer “selecionar tudo” e devolver espaço às fotos.

Na imagem há **quatro ações no total, incluindo “Mais”**, além da navegação Fotos/Álbuns. O ícone circulado tem significado pouco evidente. Os valores abaixo são propostas em dp/sp; não são medidas extraídas da captura.

As prioridades são **P1: fazer primeiro**, **P2: próxima rodada** e **P3: acabamento**.

## 1. Consistência visual e hierarquia

**P1 · Impacto alto · Esforço baixo — Padronizar os estilos.**

- **(a) O que muda:** manter Roboto, com poucos tamanhos e pesos consistentes.
- **(b) Por quê:** títulos, datas e informações secundárias ficam reconhecíveis sem depender de mais cores.
- **(c) Compose:** centralizar em `MaterialTheme.typography`: título de tela **24sp/32sp Medium**, cabeçalhos de dia/seção **18sp/24sp Medium**, nomes de álbuns **16sp/24sp Medium**, informações secundárias **14sp/20sp** e rótulos de ação **12sp/16sp Medium**. Usar margens de **16dp**, intervalos de **8dp** e **24dp** entre seções.

**P1 · Impacto alto · Esforço baixo — Dar uma função clara ao coral.**

- **(a)** Reservar coral para seleção, favorito ativo e ação principal. Ícones comuns ficam em branco; informações auxiliares, em cinza.
- **(b)** Na captura, a borda coral espessa compete com a própria foto. Reduzir sua área melhora a leitura sem enfraquecer a seleção.
- **(c)** Fundo `#111114`, superfícies `#252529`, elevação de sombra **0dp** nas barras e cards. Miniatura selecionada com borda interna de **2dp** e selo marcado de **24dp**. Cantos: grade **0–4dp**, capas de álbuns **12dp**, campos **12dp**, folhas **24dp** nos cantos superiores.

## 2. Cabeçalho e barra de seleção

**P1 · Impacto alto · Esforço médio — Substituir a navegação pela barra contextual.**

- **(a)** Durante a seleção, ocultar Fotos/Álbuns e usar apenas **Compartilhar · Adicionar · Mais**. “Criar álbum” fica dentro do fluxo de adicionar a álbum; “Excluir” fica em Mais.
- **(b)** Elimina duas barras empilhadas e reúne destinos de uma mesma ação. Também evita exclusão acidental junto de comandos frequentes.
- **(c)** Trocar o conteúdo de `Scaffold.bottomBar` conforme o estado. Usar `BottomAppBar` com três áreas de mesmo peso, ícones de **24dp** e rótulos de **12sp**; altura mínima de **64dp**, permitindo crescer com a fonte. A folha “Adicionar a álbum” começa com a opção “Criar álbum”. Aplicar os insets do sistema uma única vez.

**P1 · Impacto alto · Esforço baixo — Tornar a seleção explícita.**

- **(a)** Cabeçalho: **X · “1 selecionado” · “Selecionar tudo”**. Remover “selecionada(s)” e substituir o ícone ambíguo circulado por texto.
- **(b)** A pessoa entende o comando e o estado sem precisar aprender um símbolo.
- **(c)** `TopAppBar`, `IconButton` para fechar e `TextButton` para selecionar tudo; contagem via `pluralStringResource`. Quando todos estiverem selecionados, mostrar “Desmarcar tudo”. Em telas estreitas ou fonte ampliada, mover esse comando para um menu com rótulo explícito. `BackHandler` encerra a seleção antes de sair da tela.

**P2 · Impacto médio · Esforço baixo — Definir o alcance de “tudo”.**

- **(a)** Selecionar todos os itens do resultado atual: álbum aberto ou filtro aplicado.
- **(b)** Evita incluir fotos que a pessoa não está vendo naquele contexto.
- **(c)** Vincular a seleção aos IDs do conjunto filtrado inteiro, incluindo itens fora da área visível. Em resultado filtrado, usar descrição acessível “Selecionar todos os itens deste resultado”.

## 3. Miniaturas e grade

**P1 · Impacto alto · Esforço baixo — Preservar a grade e refinar o feedback.**

- **(a)** Manter três colunas, miniaturas quadradas e separadores discretos. Usar seleção com marca, além da cor.
- **(b)** Preserva a familiaridade e facilita reconhecer o que foi selecionado em fotos claras ou escuras.
- **(c)** `LazyVerticalGrid`, `GridCells.Fixed(3)`, `aspectRatio(1f)`, `ContentScale.Crop` e gutters de **2dp**. `combinedClickable` com ripple e feedback háptico apenas ao entrar em seleção por toque longo. Durante a seleção, um toque alterna o item inteiro; o círculo não precisa ser um segundo alvo.

**P2 · Impacto médio · Esforço baixo — Organizar os indicadores.**

- **(a)** Seleção no canto superior esquerdo, favorito no superior direito e duração de vídeo no inferior direito.
- **(b)** Posições fixas reduzem a procura e impedem sobreposições.
- **(c)** Ícones de **16dp**, afastamento de **6dp** e fundo preto semitransparente nos selos. Duração em **12sp**, como “0:32”. Esses indicadores são informativos; a ação continua pertencendo à miniatura.

**P2 · Impacto médio · Esforço médio — Melhorar a orientação por data.**

- **(a)** Compactar os cabeçalhos e aproveitar o seletor mês/ano existente para saltar rapidamente.
- **(b)** A captura mostra bastante espaço entre grupos. Cabeçalhos menores deixam mais fotos visíveis; o salto evita longas rolagens.
- **(c)** Cabeçalho preso com fundo opaco e altura mínima de **48dp**, crescendo conforme o texto. Manter um índice mês → posição da grade e usar `LazyGridState.scrollToItem()` para saltos distantes. Mostrar uma pequena etiqueta “set. 2026” durante a rolagem rápida, atualizada somente quando o mês mudar. Uma alça arrastável personalizada pode ficar para depois.

## 4. Aba Álbuns

**P2 · Impacto médio · Esforço baixo — Manter quadrados e círculos, com regras comuns.**

- **(a)** Manter capas quadradas para álbuns manuais e círculos para pessoas.
- **(b)** A diferença ajuda a distinguir coleção e identidade. O que precisa ser consistente é alinhamento, texto e espaçamento.
- **(c)** Margens externas de **16dp**, intervalo entre cards de **12dp**, nome em **16sp**, contagem em **14sp**. Manter círculos de **112dp**, centralizados em suas células. Reservar até duas linhas para nomes, com reticências; não fixar a altura total do card.

**P2 · Impacto médio · Esforço baixo — Tirar “Criar” da competição com os álbuns.**

- **(a)** Substituir o card “Criar” por um `TextButton` “Criar álbum” junto ao título “Meus álbuns”.
- **(b)** A primeira posição da grade passa a mostrar conteúdo real.
- **(c)** Usar `Row` no cabeçalho; com fonte ampliada ou pouco espaço, posicionar o botão abaixo do título. No estado vazio, usar `FilledTonalButton` como ação principal.

**P2 · Impacto médio · Esforço médio — Estabilizar capas e ordenação.**

- **(a)** Evitar capas que mudam a cada abertura; mostrar contagem e oferecer “Nome” ou “Mais recentes”.
- **(b)** A memória visual ajuda a encontrar álbuns mais rapidamente.
- **(c)** Persistir o ID da capa e só substituí-lo se a imagem sair do álbum. Usar `DropdownMenu` com a ordenação atual marcada e salvar a preferência. Definir “Mais recentes” como álbuns que receberam fotos por último. Manter “Aparições únicas” no fim, com o mesmo estilo de seção.

## 5. Vazios, carregamentos e mensagens

**P1 · Impacto alto · Esforço baixo — Distinguir os estados.**

- **(a)** Dar mensagens específicas para biblioteca vazia, busca sem resultado e falta de acesso.
- **(b)** Uma tela vazia genérica não explica o que aconteceu nem como continuar.
- **(c)** Componente reutilizável com ícone de **40dp**, título de **20sp**, texto de **14sp** e uma ação quando aplicável:

| Situação | Mensagem | Ação |
|---|---|---|
| Sem fotos acessíveis | “Nenhuma foto para mostrar” | Conforme o estado de acesso |
| Busca vazia | “Nenhum resultado para ‘praia’” | “Limpar busca” |
| Sem álbuns | “Seus álbuns aparecem aqui” | “Criar álbum” |
| Permissão ausente | “Permita o acesso às fotos para exibi-las aqui” | “Permitir acesso” |
| Processando pessoas | “Organizando pessoas no aparelho…” | Sem ação obrigatória |

**P2 · Impacto médio · Esforço baixo — Evitar piscadas e confirmar ações.**

- **(a)** Reservar o espaço das imagens enquanto carregam e usar mensagens curtas após operações.
- **(b)** Mantém a grade estável e confirma que o comando funcionou.
- **(c)** Placeholder sólido `#252529` com a mesma proporção da imagem; sem shimmer contínuo. Usar `SnackbarHost`: “3 fotos adicionadas a Viagem”. Oferecer “Desfazer” somente quando a operação for realmente reversível. Centralizar textos em recursos, com plurais corretos e capitalização de frase: “Adicionar a álbum”, “Selecionar tudo”.

## 6. Movimento e transições

**P2 · Impacto médio · Esforço baixo — Animar mudanças de estado discretamente.**

- **(a)** Suavizar a entrada em seleção, a marcação e a abertura de folhas.
- **(b)** O movimento explica a mudança sem atrasar comandos.
- **(c)** Transição de opacidade de **120–180ms** entre conteúdos das barras, mantendo suas dimensões; `animateColorAsState` para a seleção. Usar `ModalBottomSheet` com seu movimento padrão. Evitar animar tamanho ou posição de todas as miniaturas a cada marcação.

**P3 · Impacto médio · Esforço médio — Polir a abertura da foto.**

- **(a)** Mostrar imediatamente a miniatura no visualizador e substituí-la pela imagem maior quando pronta.
- **(b)** Reduz a sensação de espera e evita um quadro preto.
- **(c)** Fade de **150–200ms**, preservando proporção e posição inicial. Uma transição compartilhada pode esperar; exige mais cuidado com recorte, retorno e rolagem. Respeitar a configuração de animações do sistema.

## 7. Acessibilidade

**P1 · Impacto alto · Esforço baixo — Alvos e leitura por TalkBack.**

- **(a)** Garantir botões fáceis de tocar e nomes claros para ações e estados.
- **(b)** Ícones pequenos e comandos disponíveis apenas por toque longo dificultam o uso.
- **(c)** Alvos de pelo menos **48 × 48dp**, mesmo com ícones de **24dp**. Usar `contentDescription` como “Fechar seleção” e “Mais opções”. Para miniaturas, anunciar tipo, data e estado selecionado; expor “Selecionar” também como ação de acessibilidade. Ícones acompanhados por texto no mesmo controle usam `contentDescription = null` para evitar repetição. Essas práticas seguem a documentação de [acessibilidade do Compose](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).

**P1 · Impacto alto · Esforço médio — Suportar fonte ampliada e contraste.**

- **(a)** Permitir que barras e cards cresçam e evitar texto branco pequeno sobre coral.
- **(b)** O layout precisa continuar utilizável sem cortar comandos essenciais.
- **(c)** Preferir `heightIn(min = …)` a alturas rígidas em áreas com texto. Testar fonte a **200%**, TalkBack e tela estreita. Buscar contraste de **4,5:1** para texto comum: sobre coral, usar texto/ícone escuro `#111114`; sobre fotos, aplicar fundo escuro nos indicadores. Não comunicar seleção apenas por cor: manter a marca de verificação.

## 8. Cinco vitórias rápidas que eu faria primeiro

Todas têm **impacto alto e esforço baixo**:

1. **Trocar o ícone circulado por “Selecionar tudo”.** Explicita a ação; implementar com `TextButton`.
2. **Corrigir “1 selecionada(s)” para “1 selecionado” / “2 selecionados”.** Melhora a leitura; usar recursos de plural.
3. **Afinar a borda selecionada para 2dp e manter o selo marcado.** Devolve destaque à imagem; aplicar `border` e selo sobreposto.
4. **Padronizar tipografia, margens e cabeçalhos de dia.** Melhora hierarquia e densidade; centralizar estilos e usar altura mínima de 48dp nos cabeçalhos.
5. **Revisar alvos de 48dp e descrições dos ícones.** Melhora toque e TalkBack; ajustar `IconButton` e semântica.

A primeira mudança estrutural depois dessas seria **substituir as duas barras inferiores pela barra contextual de três ações durante a seleção**. É o ajuste com maior ganho de espaço e clareza na tela apresentada.
