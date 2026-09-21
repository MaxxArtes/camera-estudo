### 1. Layout

- **Topo: 56 dp. Faixa inferior: 72 dp. Controle contextual: 144 dp**, igual nas três ferramentas para evitar saltos.
- Ordem: **Recortar · Ajustar · Filtros**. Três células iguais, sem rolagem nesta v1. Ícones **24 dp**, rótulos **12 sp**, alvos de toque mínimos **48 × 48 dp**.
- Abrir com nenhuma ferramenta selecionada. Ao selecionar, a prévia perde **144 dp de altura**, com animação de **180 ms**, recalculando o encaixe sem cortar a imagem.
- Prévia sobre preto, margem **12 dp**; restante em `#111114`, controles em `#252529`. Coral para seleção; textos `#F5F5F5` e `#A3A3AA`.
- Topo abaixo do inset da barra de status; ferramentas acima do inset de navegação. Essas áreas seguras **não entram nas alturas anteriores**. Em janela baixa, permitir rolagem vertical do painel sem reduzir alvos.

### 2. Recorte

- Moldura branca de **1 dp**, cantos em L de **24 dp / 3 dp**, alças com área invisível de **48 dp**. Exterior escurecido em **60%**.
- Grade de terços enquanto estiver recortando; grade mais densa somente durante endireitamento.
- **Arrastar alças redimensiona a moldura; arrastar dentro move a foto; pinça escala a foto.** Impedir áreas vazias, inclusive durante rotação.
- Painel: chips **Livre, 1:1, 4:3, 3:4, 16:9, 9:16**; linha com **Girar 90°**, **Espelhar horizontal** e valor angular; slider de **−45° a +45°**, passo **0,1°**, atração suave em zero.
- **Trocar de ferramenta confirma o recorte no estado da sessão. Ele continua editável ao voltar**, sem gerar bitmap intermediário.
- Sem botões locais de aplicar/cancelar. **Desfazer** recupera o estado anterior; incluir **Redefinir recorte** no painel.

### 3. Ajustar

- Chips roláveis de **36 dp**, texto **13 sp**, acima de **um slider por vez**. Valor atual sempre visível, **14 sp**, com sinal.
- Ordem: **Brilho, Contraste, Saturação, Temperatura, Matiz**.
- Todos de **−100 a +100**, neutro em **0**, passo **1**. São unidades de interface; mapear cada parâmetro para uma transformação adequada.
- Chip selecionado: borda coral. Parâmetro modificado: **ponto coral de 4 dp**, mesmo quando não selecionado.
- **Toque duplo zera**, acompanhado de ação acessível “Redefinir parâmetro”. Também mostrar **Redefinir** ao lado do valor; o gesto não pode ser a única opção.
- **Realces e Sombras ficam para v2.** Para uma build, validar equivalência entre shader da prévia e exportação é escopo desnecessário.

### 4. Filtros

- Miniaturas de **64 × 64 dp**, raio **8 dp**, intervalo **8 dp**, nome abaixo em **12 sp**. Usar **a própria foto**, com recorte e ajustes atuais, em resolução reduzida.
- Ordem: **Original, Vívido, Quente, Frio, P&B, Sépia, Fade, Contraste**.
- Seleção com borda coral de **2 dp**.
- Slider de intensidade **0–100**, valor visível; filtro novo começa em **100**. Em Original, manter espaço reservado e desabilitar slider.
- **Original remove somente o filtro**. Brilho, recorte e demais ajustes permanecem.

### 5. Topo e histórico

- **X à esquerda**, Desfazer/Refazer no centro, **Salvar** à direita.
- Salvar como **botão cheio coral**, altura visual **40 dp**, alvo **48 dp**, texto **14 sp**. Desabilitado quando o resultado coincide com a original.
- X e Voltar do Android: se houver alterações, diálogo **“Descartar edições?”**, ações **Continuar editando** e **Descartar**.
- **Histórico separado por ferramenta**, indicado por acessibilidade: “Desfazer ajuste”, por exemplo. Cada arraste completo gera um passo; nunca cada atualização do slider.
- Guardar parâmetros e geometria no histórico, **não bitmaps**.

### 6. Salvar

- Exportar **JPEG SDR/sRGB, qualidade 95**, lado maior de até **4096 px**, sem ampliar imagens menores.
- Nome: **`<nome-original>_edit_yyyyMMdd_HHmmss.jpg`**; acrescentar sufixo numérico em colisões.
- Criar na mesma pasta **quando o destino permitir**. Caso contrário, usar **`Pictures/Galeria Estudo`**. URI de origem não garante acesso à pasta; usar MediaStore para criar a cópia. [Armazenamento compartilhado Android](https://developer.android.com/training/data-storage/shared/media)
- Preservar data/hora de captura e offset EXIF existentes, refletindo a captura no `DATE_TAKEN`. A criação da cópia recebe a hora atual. **Não inventar data EXIF ausente.**
- Durante a gravação: bloquear novo salvamento e mostrar progresso. Sucesso: **abrir a cópia no visualizador**, mensagem **“Cópia salva”**. Falha: permanecer no editor com as edições.
- Informação discreta, **12 sp**, acima das ferramentas: **“Cópia em JPEG · até 4096 px”**; acrescentar **“· SDR”** quando houver HDR. Ao tocar: “A original permanece intacta. Esta versão exporta sem Ultra HDR.”
- **Perder gain map é uma limitação desta exportação, não de toda edição Android**: algumas transformações podem preservá-lo. [Edição Ultra HDR](https://developer.android.google.cn/media/grow/ultra-hdr/edit?hl=en)

### 7. Gestos

- Fora de Recortar: pinça para inspeção, entre encaixe e **4×**; arrastar desloca somente quando ampliada; toque duplo alterna encaixe/**2×**.
- Entrar em Recortar redefine zoom de inspeção. Ali, pinça e arraste alteram a composição.
- Comparar: pressionar a prévia por **350 ms**, sem deslocamento significativo; soltar restaura edição. Segundo dedo ou início de arraste cancela a comparação.
- Mostrar **“Original”** durante a comparação, preservando o enquadramento atual para evitar saltos. **No recorte, desabilitar comparação**.
- Sliders capturam gestos apenas dentro do painel; nunca iniciar comparação a partir deles.

### 8. Não-metas e riscos

**Fora da v1:** IA, HSL, Realces/Sombras, texto/doodle, embelezamento, lote, exportação HDR/HEIC, preservação de motion photo e histórico de reedição após salvar. Edição reversível vale **durante a sessão**.

- **Memória:** 50 MP em ARGB_8888 ocupam aproximadamente **200 MB por bitmap**. Decodificar já reduzido; prévia até **2048 px**, exportação separada, sem manter várias imagens grandes. Recorte pequeno pode exigir decodificação por região.
- **EXIF:** tratar as **oito orientações**, inclusive espelhadas. Normalizar pixels uma vez e salvar orientação normal. Não copiar indiscriminadamente thumbnails, dimensões antigas, GPS ou metadados de motion photo.
- **HEIC:** aceitar quando o decoder conseguir e converter para JPEG. Detectar falha antes de abrir o editor. O formato consta no suporte Android, mas o arquivo concreto ainda precisa ser decodificado com sucesso. [Formatos suportados](https://developer.android.com/media/platform/supported-formats)
- **Motion photo:** exportar somente a imagem estática; informar isso discretamente quando detectado.
- **Somente leitura/WhatsApp:** leitura da origem basta para criar cópia em outro destino. Não depender de caminho físico nem de EXIF presente; lidar com URI revogada, arquivo removido e mídia indisponível offline.
- **Consistência:** fixar ordem **orientação → geometria → ajustes → filtro → exportação**, usando a mesma matemática na prévia e no arquivo.
- **Interrupções:** preservar receita de edição em recriação da tela; limpar cópia incompleta em erro ou falta de espaço. Só anunciar sucesso depois de concluir pixels, metadados e publicação.
