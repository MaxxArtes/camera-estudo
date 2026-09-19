Aqui está a crítica técnica dura. Seu plano é ingênuo em vários pontos críticos e, se você largar o celular rodando isso hoje à noite sem correções, ele vai fritar, travar por OOM ou gerar um lixo inavegável de "pessoas" fragmentadas.

Respondendo diretamente aos seus pontos com os consertos concretos:

### 1. Causa de falha não nomeada: Termal Throttling e Invalidação de MediaStore
Você acha que `svc power stayon true` resolve. Não resolve o **Thermal Throttling** agressivo da MIUI/HyperOS do POCO X8 Pro (Snapdragon 778G). Rodar ML Kit + TFLite a 100% sem parar vai fritar o aparelho. O SO vai derrubar o clock, o tempo por foto vai pular de 200ms para 5s, e um processo "fantasma" consumindo bateria com a tela ligada tem altíssima chance de ser fuzilado pelo sistema operativo no meio da madrugada, corrompendo sua transação de banco se não estiver bem fechada.
Outra falha: confiar cegamente no `_ID` do MediaStore como PK. Se o dono usar outro app de galeria ou o Google Fotos modificar a imagem, o `_ID` pode mudar.
**Conserto concreto:**
- Adicione um `delay(50)` coroutine a cada foto processada para deixar a CPU respirar.
- Envolva o processamento em um `WakeLock` parcial, mesmo com a tela ligada.
- No banco, além do `_ID`, salve o `DATA` (caminho absoluto) ou `RELATIVE_PATH` + `DISPLAY_NAME`. Se o `_ID` sumir, você tenta reconciliar pelo caminho antes de reprocessar.

### 2. Atribuição online fragmentada
Sim, vai fragmentar de forma desastrosa. A atribuição gulosa online (0.65) baseada na ordem temporal de aparição cria o problema de "concept drift". Fotos de 5 anos atrás não vão bater com as fotos de ontem da mesma pessoa (cosseno < 0.65). Você terá 15 grupos para a mesma pessoa.
**Conserto concreto (Alternativa barata para esta noite):**
Use um **Agrupamento em Duas Fases**:
1. **Passada Online (Micro-clusters estritos):** Use um limiar BEM mais alto (ex: `0.72`) durante a indexação gulosa. Isso garante pureza, mas cria muitas "pessoas".
2. **Passada Offline (No final da indexação):** Rode um job rápido que calcula o centroide (média dos vetores L2-normalizada) de cada "pessoa". Calcule a similaridade cosseno de todos os centroides contra todos. Se o centroide A e B tiverem similaridade `> 0.60`, faça o `UPDATE rostos SET pessoa = A WHERE pessoa = B` e apague a pessoa B. Uma matriz 500x500 resolve isso em milissegundos no aparelho.

### 3. Viés da ordem "mais recentes primeiro"
O viés temporal vai destruir a janela de 12 exemplares. Se você indexa de trás pra frente, os 12 vetores armazenados serão todos da aparência *atual* da pessoa (mesmo corte de cabelo, mesma idade). Quando o indexador chegar nas fotos antigas, a rejeição será massiva.
**Conserto concreto:**
Não substitua exemplares apenas por `sim < 0.9`. Adote uma política de diversidade: se a similaridade com *todos* os exemplares atuais estiver entre `0.65` e `0.75` (ou seja, é a pessoa, mas de um ângulo/época bem diferente), adicione à lista de exemplares. Se já tiver 12, expulse o exemplar que for *mais parecido com outro exemplar da própria lista* (o mais redundante).

### 4. Risco de OOM/ANR
Abrir 5.000 Bitmaps em até 1000px sequencialmente sem cuidado vai causar fragmentação no heap nativo (tanto do Bitmap quanto do ML Kit/TFLite), resultando em OOM.
**Conserto concreto:**
- Não recrie o `FaceDetector` do ML Kit nem o `Interpreter` do TFLite. Use instâncias singletons na sua classe de indexação.
- O decode reduzido DEVE ser envolvido em um bloco que garanta a reciclagem:
  ```kotlin
  bitmap.use { bmp ->
      // processa
  } // extention function que chama bmp.recycle() no final
  ```
- Force lixo: Chame `System.gc()` a cada 200 fotos. É custoso, mas evita OOM silencioso de madrugada.

### 5. Pegadinhas do loadThumbnail via Coil
A API de thumbnail do MediaStore na MIUI frequentemente retorna lixo. O tamanho que você pede no Coil (`size(X)`) será ignorado; o Android vai devolver a miniatura pré-computada que ele tem (geralmente 384x384 ou 512x512). O pior: às vezes ele devolve **sem a rotação EXIF aplicada**.
**Conserto concreto:**
Use o `ImageRequest` normal do Coil apontando para o URI da imagem e adicione um `.size()` e `.transformations()`. Deixe o Coil fazer o downsample no IO thread. Confiar no `loadThumbnail` para rostos vai resultar em grades onde as fotos estão deitadas.

### 6. SQLite sem Room: O que dá errado
Sem índices e transações, 20 mil inserções vão demorar uma eternidade e a UI da grade vai travar o Main Thread. WAL não é garantido se você não ligar.
**Conserto concreto:**
- Habilite WAL: `db.setWriteAheadLoggingEnabled(true)` no `onConfigure`.
- Envolva o processamento de CADA foto em uma transação (`beginTransaction`, `setTransactionSuccessful`, `endTransaction`). Não faça commit rosto por rosto.
- **Índices exatos necessários:**
  ```sql
  CREATE INDEX idx_rostos_foto ON rostos(foto);
  CREATE INDEX idx_rostos_pessoa ON rostos(pessoa);
  CREATE INDEX idx_fotos_data ON fotos(data DESC);
  CREATE INDEX idx_exclusoes_busca ON exclusoes(foto, pessoa);
  ```

### 7. Sobrevivência de exclusões e junções a reindexações
Se o dono corrigir "não é esta pessoa" e depois você reindexar o banco (porque mudou o modelo, por exemplo), os IDs dos rostos vão mudar e a exclusão será perdida.
**Conserto concreto:**
Gere um **ID determinístico para o Rosto**. Não use AUTOINCREMENT no id do rosto.
O `id` do rosto deve ser um hash: `hash(foto_id + bounding_box_x + bounding_box_y)`.
Assim, se você limpar a tabela `rostos` e re-detectar, o rosto terá o mesmo ID e a tabela de `exclusoes` continuará válida.

### 8. Risco do CI (mesmo repo, 2 workflows)
Se você tocar no `settings.gradle.kts` ou em um `build.gradle` root, o GitHub Actions vai rodar o CI da câmera (já que esses arquivos não estão no `paths-ignore: galeria/**`).
**Conserto concreto:**
Verifique se o workflow da câmera tem o gatilho de Release automático atrelado a `push` na `main`. Se tiver, altere-o IMEDIATAMENTE para disparar apenas em criação de `tags` (ex: `v*.*.*`). Caso contrário, um commit na galeria vai cuspir um APK fantasma de release da câmera e quebrar a atualização via Obtainium do seu cliente.

### 9. Reutilizar keystore
Não há risco técnico. A assinatura diz "quem fez", o `applicationId` diz "quem é o app". Eles vão instalar lado a lado sem problemas. Pode prosseguir.

### 10. O que medir esta noite
Se você for dormir sem instrumentação, amanhã terá que debugar um log vazio com o cliente irritado.
**Conserto concreto:**
Crie um arquivo `.csv` na pasta de Cache do app (ou grave agressivamente no Logcat se for pegar o dump via ADB amanhã cedo).
A cada 50 fotos, escreva:
`[TIMESTAMP], [TOTAL_FOTOS], [FOTOS_PROCESSADAS], [TEMPO_MEDIO_FOTO_MS], [TOTAL_PESSOAS_CRIADAS], [MEM_ALLOC_MB]`
Para a memória, grave o resultado de: `(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024`.
Se o `TEMPO_MEDIO_FOTO_MS` passar de 1500ms, o termal throttling está destruindo a performance. Se as `TOTAL_PESSOAS_CRIADAS` escalar linearmente com as fotos processadas, seu limiar está alto demais e o agrupamento falhou.

Não elogie seu design; implemente os hashes determinísticos e a passada offline de agrupamento, ou você vai apresentar um lixo inutilizável amanhã de manhã.
