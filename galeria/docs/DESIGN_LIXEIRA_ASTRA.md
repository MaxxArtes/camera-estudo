### 1. Entrada: nos dois lugares

- **Álbuns:** card “Lixeira” no fim da lista, separado dos álbuns por **24 dp**. Altura mínima **64 dp**, margem horizontal **16 dp**, ícone **24 dp**, título **16 sp**. Sem miniatura nem contagem na v1.
- **Fotos:** opção “Lixeira” no menu de três pontos, linha de **48 dp**, texto **16 sp**.
- Ambos abrem a mesma tela. Sem terceira aba.

### 2. Ação: “Mover para a lixeira”

Use esse rótulo na foto aberta e na seleção múltipla. **Mantenha o ícone de lixeira**, com descrição acessível idêntica ao rótulo. Ícone **24 dp**, alvo de toque mínimo **48 × 48 dp**.

Após confirmação e conclusão: retirar da grade e mostrar “3 itens movidos para a lixeira”, com ação **“Ver lixeira”**. Cancelamento mantém tudo como estava. Não colocar “Desfazer” na v1.

### 3. Tela Lixeira

- **Cabeçalho:** barra de **56 dp**, voltar, “Lixeira” em **22 sp** e ação “Selecionar”.
- **Aviso:** margem **16 dp**, preenchimento **16 dp**, superfície `#252529`, texto **14 sp**: “O Android exclui os itens automaticamente, geralmente após cerca de 30 dias na lixeira. O prazo pode variar.”
- **Grade:** três colunas no celular, miniaturas quadradas, espaçamento **2 dp**. **Sem agrupamento por dia. Ordenar pelo vencimento mais próximo**, usando `DATE_EXPIRES`; itens sem prazo ficam no fim.
- **Prazo por item:** legenda **12 sp** sobre fundo escuro: “≈ 8 dias”; abaixo de um dia, “Expira em breve”. Sem prazo disponível: “Prazo indisponível”. A expiração não garante exclusão naquele instante. [Referência do Android](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#DATE_EXPIRES).
- **Toque:** abre a foto, com “Restaurar” e “Excluir definitivamente”. Pressão longa inicia a mesma seleção múltipla de Fotos.
- **Seleção:** cabeçalho “3 selecionados”; barra inferior com ações de altura mínima **48 dp**, texto **14 sp**. Permitir quebra de linha e crescimento com fonte ampliada.
- **Sem “Esvaziar lixeira” na v1.** Oferecer “Selecionar todos”, limitado aos itens exibidos.

Fundo `#111114`, texto principal `#F5F5F5`, secundário `#A3A3AA`. Restaurar com estilo neutro; excluir definitivamente com texto/ícone coral `#FF575F`.

### 4. Confirmações: uma por operação

Em Android 11+, abrir diretamente a confirmação do sistema: mover com `createTrashRequest(..., true)`, restaurar com `false` e apagar com `createDeleteRequest`. **Nenhum diálogo próprio antes ou depois.** [MediaStore](https://developer.android.com/reference/android/provider/MediaStore).

Confirmação própria somente quando houver **exclusão permanente sem confirmação equivalente do sistema**, especialmente no fallback:

> **Excluir 3 itens definitivamente?**  
> Neste aparelho, esses itens não passam pela lixeira. Não será possível restaurá-los pelo app.

Botões: “Cancelar” e “Excluir definitivamente”. Um pedido de permissão não substitui esse aviso.

### 5. Estados

- **Vazia, com acesso completo:** ícone **48 dp**, título **20 sp** “Lixeira vazia” e texto **14 sp** “Os itens movidos para a lixeira aparecem aqui.”
- **Android < 11:** manter a entrada, abrindo explicação: “A lixeira está disponível a partir do Android 11. Neste aparelho, a exclusão é permanente.” Nas Fotos, usar **“Excluir definitivamente”**.
- **Acesso parcial:** aviso persistente **14 sp**: “Acesso limitado: alguns itens podem não aparecer.” Ação **“Gerenciar acesso”**, alvo mínimo **48 dp**. Se não houver resultados, dizer **“Nenhum item disponível com o acesso atual”**, sem afirmar que a lixeira está vazia. [Permissões parciais](https://developer.android.com/about/versions/14/changes/partial-photo-video-access).
- **Erro de consulta:** mostrar “Não foi possível carregar a lixeira” e “Tentar novamente”; nunca representar erro como vazio.

### 6. Não-metas e riscos

**Fora da v1:** esvaziamento global, restauração automática, histórico próprio de exclusões, prazo configurável, notificações e integração com lixeiras de nuvem.

Riscos que precisam entrar na implementação:

- **Restaurado volta à posição original.** Mensagem: “Item restaurado na data original”, com ação “Ver”.
- **Atualizar toda a galeria:** grade, foto aberta, seleção, contagens e capas dos álbuns após cada operação.
- **Não inventar data de exclusão:** `DATE_EXPIRES − 30 dias` não é uma origem confiável.
- **Não prometer liberar espaço ao mover:** o arquivo continua na lixeira até a exclusão permanente.
- **Não assumir equivalência com a lixeira do HyperOS:** validar no aparelho quais itens ficam visíveis e restauráveis pelo MediaStore.
- **Reconsultar ao voltar ao app:** outro aplicativo ou o sistema pode restaurar/apagar itens enquanto a tela está aberta.
- **Falha ou cancelamento nunca vira exclusão permanente automática.** Reconsultar o resultado e informar somente o que realmente aconteceu.
