## 1. Estrutura de navegação

**Duas abas embaixo: “Fotos” e “Pessoas”.** O app abre em Fotos. Não haverá uma aba genérica “Álbuns” nesta versão.

- Barra inferior com **64 dp**, além do espaço reservado à navegação do sistema.
- Ícones de **24 dp**, rótulos de **12 sp**. Aba ativa em coral; inativa em cinza. Sem círculos grandes na navegação.
- Barra superior de **56 dp**, com título da aba à esquerda e menu de três pontos à direita.
- Cada aba preserva sua posição de rolagem. Voltar de uma foto devolve exatamente à miniatura de origem.

A família visual vem das cores e dos rostos circulares. A densidade da gaveta da câmera não vem junto.

| Elemento | Decisão |
|---|---|
| Fundo | `#111114` |
| Superfícies e menus | `#252529` |
| Coral | `#FF575F` |
| Texto principal | `#F5F5F5` |
| Texto secundário | `#A3A3AA` |
| Tipografia | Roboto, padrão do Android |
| Alvo mínimo de toque | **48 × 48 dp** |
| Transições | **180 ms**, respeitando redução de animações |

Sem cores dinâmicas do sistema, sombras decorativas ou cartões envolvendo fotos.

## 2. Tela “Fotos”

**Grade de três colunas, organizada por dia, mais recentes primeiro.**

- Miniaturas quadradas, preenchimento com recorte central.
- Espaçamento de **2 dp**, sem margem lateral e sem arredondamento.
- Cabeçalhos de **48 dp**, com margem horizontal de **16 dp**, texto de **14 sp**, peso médio.
- Formatos: **“Hoje”**, **“Ontem”**, **“Sáb., 19 de setembro”**. Para outros anos: **“19 de setembro de 2025”**.
- O cabeçalho do dia fica preso abaixo da barra superior até ser substituído pelo próximo.
- A data de captura determina a ordem; na ausência dela, usar a data de modificação. Fotos sem data utilizável ficam no fim, em **“Sem data”**.

A barra superior e as abas permanecem visíveis na grade. Quem precisa desaparecer é a interface do visualizador.

**Rolagem rápida:** indicador discreto à direita durante a rolagem, desaparecendo após **1.000 ms**. Área de arraste de **48 dp**, traço visível de **4 dp**. Ao arrastar, mostrar uma etiqueta **“Set 2026”**. O salto usa o índice de datas, sem carregar todas as miniaturas.

**Vídeos entram na linha do tempo.** Recebem um pequeno símbolo de reprodução e duração, como **“1:24”**, no canto inferior direito, sobre fundo preto translúcido. Fotos não recebem selo. Na v1, vídeos abrem no reprodutor externo; não entram na análise de rostos.

**Favoritos: não. Seleção múltipla de fotos: não.** Toque abre; toque longo não inicia um modo escondido. Essas duas decisões reduzem bastante o trabalho desta noite.

## 3. Tela “Pessoas”

**Duas colunas de rostos circulares**, com margem externa de **16 dp**, intervalo horizontal de **16 dp** e vertical de **24 dp**.

- Capa de **112 dp** de diâmetro, centralizada na célula.
- Recorte inclui rosto inteiro, cabelo e alguma margem; não cortar apenas olhos, nariz e boca.
- Escolher automaticamente um rosto frontal e nítido. Manter essa capa estável durante a indexação.
- Nome abaixo, **16 sp**, até duas linhas.
- Contagem em **13 sp**, cinza: **“128 fotos”**. Contar fotos distintas, não detecções.
- Grupo anônimo recebe **“Sem nome”**. Não inventar “Pessoa 37”.

**Ordem: maior quantidade de fotos primeiro.** Empates seguem a foto mais recente. Não reorganizar a tela enquanto a pessoa estiver olhando; aplicar a nova ordem ao entrar novamente na aba.

Grupos com uma única foto ficam atrás da linha **“Aparições únicas · 23”**, no fim da lista. Abrem outra tela com o mesmo desenho. Se todos os grupos forem únicos, mostrar essa entrada imediatamente, com o texto **“Encontramos rostos que aparecem em uma única foto.”**

**Dar nome:** abrir a pessoa e tocar em **“Dar nome”**. Campo simples em folha inferior, teclado aberto, botões “Cancelar” e “Salvar”. Renomear segue o mesmo caminho.

**Juntar grupos:**

1. No menu da pessoa, tocar em **“Juntar com outra pessoa”**.
2. Escolher outro grupo pela capa, pelo nome e pela contagem.
3. Confirmar numa folha com os dois rostos: **“Juntar estas pessoas?”**.
4. Se só um grupo tiver nome, preservá-lo. Se ambos tiverem nomes diferentes, escolher qual manter nessa mesma folha.

Após juntar, oferecer **“Desfazer” por 8 segundos**. Guardar a operação para também permitir **“Desfazer última junção”** no menu da pessoa resultante.

**Ocultar:** menu da pessoa → **“Ocultar pessoa”**. Retira o grupo da lista; não apaga fotos nem desfaz o agrupamento. Acesso reversível pelo menu da aba → **“Pessoas ocultas”** → “Mostrar novamente”. Aparições únicas e grupos ocultos também ficam disponíveis no seletor de junção.

## 4. Tela da pessoa

Barra superior com voltar e menu de três pontos. Abaixo, cabeçalho rolável:

- Rosto circular de **80 dp**, centralizado.
- Nome em **24 sp**; para anônimos, **“Sem nome”**.
- Contagem em **14 sp**.
- Ação textual coral **“Dar nome”** ou **“Renomear”**, com alvo de **48 dp**.
- Respiro de **24 dp** antes da grade.

A grade repete exatamente a tela Fotos: três colunas, dias e ordem decrescente. O cabeçalho com rosto sai da tela ao rolar; a barra superior passa a exibir o nome.

Menu: **“Juntar com outra pessoa”**, **“Ocultar pessoa”** e, quando disponível, **“Desfazer última junção”**.

Há uma correção indispensável: ao abrir uma foto por essa tela, o menu do visualizador oferece **“Não é esta pessoa”**. Isso remove somente a associação incorreta, preserva a foto e registra a correção para a próxima indexação não recolocar o erro. Oferecer “Desfazer”.

Não incluir botão “Apagar pessoa”: ele confundiria exclusão de agrupamento com exclusão de arquivos.

## 5. Permissões e indexação

**Primeira abertura: uma explicação curta, sem carrossel.**

> **Suas fotos, no seu aparelho**  
> Veja suas fotos por data. Em Pessoas, organize rostos parecidos sem enviar imagens para servidores.

Botão principal: **“Permitir acesso às fotos”**. Em seguida, abrir o pedido nativo de fotos e vídeos.

Aceitar acesso total ou parcial. Não insistir no total após uma escolha parcial. Implementar a seleção limitada e sua alteração com `READ_MEDIA_VISUAL_USER_SELECTED`; atualizar o acervo acessível ao voltar ao app. Esse comportamento é previsto pelo [Android para acesso parcial](https://developer.android.com/about/versions/14/changes/partial-photo-video-access).

**Com acesso parcial**, exibir acima da grade uma faixa compacta:

> **Mostrando apenas os itens permitidos**  
> **Alterar seleção**

Em Pessoas, acrescentar ao estado de análise: **“Somente fotos permitidas.”** O menu oferece “Gerenciar acesso”, com alteração da seleção e acesso às configurações do sistema. Não chamar itens inacessíveis de apagados.

**Com acesso negado**, mostrar estado vazio com “Permitir acesso”; se o sistema não apresentar mais o pedido, o botão passa a ser “Abrir configurações”.

**A grade funciona assim que o MediaStore responde. A análise facial não bloqueia Fotos.**

Na primeira visita a Pessoas, mostrar:

> **Organizar por rostos**  
> O app agrupa rostos parecidos neste aparelho e pode errar. A primeira análise pode levar dezenas de minutos.  
> **Começar análise**

Após essa ativação, analisar também novas fotos automaticamente. O modelo precisa vir no APK: funcionar offline desde a instalação.

**Durante a análise**, uma faixa no topo de Pessoas mostra:

> **Analisando 1.240 de 5.300 fotos**  
> Você já pode abrir as pessoas encontradas.

Usar barra de progresso de **4 dp** e ação **“Pausar”**. Atualizar o número no máximo uma vez por segundo. Enquanto o total ainda estiver sendo contado, mostrar “Preparando análise”, sem porcentagem fictícia.

Os grupos aparecem progressivamente. Não mover os grupos sob o dedo. Ao concluir, mostrar **“Análise concluída”** e retirar a faixa após **4 segundos**.

O processamento deve ser retomável, com estados honestos: **“Pausado por você”**, **“Aguardando condições para continuar”** e **“Continuar”**. Não prometer execução ininterrupta: no Android 16, workers longos podem consumir a cota de jobs mesmo quando usam serviço em primeiro plano. [Documentação do Android](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).

**Notificação:** silenciosa, agrupada em um único item e apenas durante execução prolongada ativa: “Organizando pessoas · 1.240 de 5.300”, com “Pausar”. Pedir autorização de notificações no contexto de continuar a análise fora do app. Recusá-la não bloqueia a galeria.

**Nenhum rosto encontrado**, depois de concluir:

> **Nenhum rosto encontrado**  
> Analisamos 5.300 fotos. Novas fotos serão verificadas automaticamente.

Antes da conclusão, o texto é **“Procurando rostos nas suas fotos…”**. Esses estados não são intercambiáveis.

## 6. Visualizador de foto v1

**Foto inteira sobre preto absoluto**, respeitando sua proporção. Sem recorte inicial.

- Deslizar horizontalmente percorre a coleção de origem: todas as fotos ou apenas as daquela pessoa.
- Pinça para zoom.
- Duplo toque alterna entre enquadramento inteiro e **2,5×**.
- Com zoom ativo, arrastar move a imagem; só trocar de foto quando voltar ao enquadramento inteiro.
- Voltar restaura a posição exata da grade.

Ao abrir, controles visíveis por **2 segundos**; depois desaparecem. Um toque alterna sua visibilidade, com transição de **180 ms**. Interagir com controles suspende esse temporizador.

**Controles visíveis:**

- Em cima: voltar, data **“19 de setembro de 2026”** e hora em segundo plano.
- Embaixo: **“Compartilhar”** e **“Excluir”**, com ícone e texto, alvos de **48 dp**.
- Menu contextual “Não é esta pessoa” somente quando aberto pelo álbum de alguém.

Compartilhar usa a folha nativa. Excluir passa pela confirmação do sistema para modificar mídia compartilhada; cancelar mantém tudo intacto. Quando houver suporte a lixeira, usar essa operação e o rótulo **“Mover para a lixeira”**. Não simular sucesso antes do resultado.

Sem editor, informações EXIF, reconhecimento sobreposto ou reprodução própria de vídeo.

## 7. Ícone e nome

**Nome: “Galeria Estudo”.** É reconhecível como irmão de Camera Estudo. Dentro do app, os títulos continuam sendo “Fotos” e “Pessoas”.

Ícone adaptativo:

- Fundo `#17171B`.
- Disco coral de **64 dp**, centralizado na camada de **108 dp**.
- Dentro dele, símbolo branco de fotografia: moldura arredondada, uma montanha e um pequeno sol, com **32 dp**.
- Todo o desenho essencial dentro da área central segura de **66 dp**.
- Versão monocromática com o mesmo símbolo.

Sem letras, gradientes ou rostos no ícone. O app representa o acervo inteiro.

## 8. O que NÃO fazer na v1

- Favoritos, seleção múltipla e exclusão em lote.
- Álbuns manuais, pastas, busca e filtros.
- Editor de imagem ou botões de edição desativados.
- Player próprio, análise facial de vídeos e tratamento especial de fotos em movimento.
- Conta, nuvem, telemetria ou download obrigatório de modelo.
- Identificação nominal automática, estimativa de idade ou inferência de atributos pessoais.
- Tela extensa de configurações ou reprodução da gaveta da câmera.

## 9. Riscos de UX que o programador vai subestimar

- **Miniaturas competindo com a análise.** Decodificar originais para preencher a grade destrói memória e fluidez. Carregar no tamanho exibido, cancelar pedidos fora da tela e priorizar rolagem sobre indexação. Nunca manter milhares de bitmaps em memória.

- **Lista pulando enquanto ganha dados.** Usar identificadores estáveis para mídia e pessoas, guardar a âncora de rolagem e aplicar reordenações ao reentrar na tela. Um rosto recém-detectado não pode trocar o alvo de um toque em andamento.

- **Agrupamento errado parecer certeza.** Nome dado pelo usuário não transforma o modelo em autoridade. Junção, desfazer, ocultação e “Não é esta pessoa” são partes da v1, não refinamentos futuros. Correções manuais precisam sobreviver ao reprocessamento.

- **Fotos com várias pessoas duplicarem a contagem.** Uma imagem aparece uma vez em cada pessoa correspondente e apenas uma vez na linha do tempo. Juntar dois grupos elimina duplicações de fotos no álbum resultante.

- **Permissão revogada e arquivo removido.** Revalidar acesso ao retomar o app. Retirar miniaturas inacessíveis da interface e interromper leituras; não continuar exibindo rostos guardados em cache de uma mídia que perdeu autorização.

- **Calor, bateria e interrupções.** Processar em lotes com checkpoints; retomar sem começar do zero. Pausar sob restrição térmica e não instruir o usuário a desativar todas as proteções do HyperOS na primeira abertura.

- **Biometria local ainda é dado sensível.** No menu Pessoas, incluir “Sobre a análise” e **“Apagar dados de rostos”**, com confirmação explicando que remove nomes e agrupamentos, preserva fotos e desativa a análise até nova ativação. Excluir rostos e embeddings do backup automático; não gravá-los em logs.

- **Tela em pixels confundida com espaço de layout.** Usar dp e insets reais, nunca derivar o layout dos 1280 pixels do print. Respeitar navegação por gestos e por três botões. Com fontes ampliadas, permitir crescimento dos rótulos e reduzir Pessoas para uma coluna quando necessário; nunca separar a última letra como aconteceu com “Auto-máscaras” na referência.
