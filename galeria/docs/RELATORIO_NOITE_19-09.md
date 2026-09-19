# Galeria Estudo — relatório da noite de 19→20/09/2026

Escopo pedido pelo dono antes de dormir: "faz o simples: organizado por data, e uma aba de álbuns por pessoa".

## O que ficou pronto
- **App novo instalado no celular** (`Galeria Estudo`, br.maxymus.galeriaestudo v0.1 build 2), módulo `galeria/` no repo
  camera-estudo, CI própria publicando só no R2 (o Obtainium da câmera não vê; a câmera continua na 0.72 intacta).
- **Aba Fotos:** todas as fotos e vídeos do aparelho, 3 colunas, por dia ("Hoje", "Ontem", "Sáb., 19 de setembro"),
  cabeçalho do dia preso; vídeos com selo de duração; visualizador em tela cheia (deslizar, pinça, toque duplo),
  compartilhar e excluir (com a confirmação do sistema).
- **Aba Pessoas:** rostos agrupados no aparelho (ML Kit + MobileFaceNet, os mesmos da câmera), capas circulares,
  contagem por pessoa, "Aparições únicas" separadas; dar nome, juntar (com desfazer), ocultar, "Não é esta pessoa",
  "Apagar dados de rostos"; faixa de progresso com Pausar/Continuar; acesso parcial do Android 14+ tratado.
- **Desenho** do Astra e **crítica técnica** do agy aplicadas (docs em galeria/docs/).
- **Pesquisas** (galeria/docs/): Galeria da Xiaomi (o que roda na nuvem, o que é viável offline com licença limpa) e
  reconhecimento facial offline (modelos, limiares, custo, LGPD).

## Resultado da análise noturna
- **6.332 fotos analisadas, 0 erros.** ~1.690 tinham rosto. Tempo médio ~280 ms por foto; ~34 min de análise ao todo
  (interrompidos por um crash no meio, ver abaixo).
- **760 pessoas** ao final. O agrupamento bruto achou ~914 grupos e a consolidação por centroide juntou 156 pares.
- **Um crash, encontrado e corrigido (0.1 -> 0.2):** aos 3.700 de 6.332 a análise abortou com um erro de bitmap
  reciclado (recorte de capa devolvia a própria imagem de trabalho quando o rosto preenchia o quadro). Consertado,
  reinstalado por cima mantendo o banco, retomou do 3.700 e terminou limpo. Nenhum dado perdido: cada foto é gravada
  ao terminar. Lição salva na memória (createBitmap/createScaledBitmap devolvem a fonte no recorte-identidade).

## Qualidade do agrupamento (honesto)
760 pessoas é MUITO para uma biblioteca só sua: a mesma pessoa está partida em vários grupos. Isso é de propósito:
escolhi limiares que preferem SEPARAR a JUNTAR, porque juntar é reversível com um toque ("Juntar") e separar errado
não seria. Os grupos GRANDES (você, família) saíram limpos no que vi. O trabalho de afinação para a próxima rodada:
baixar um pouco o limiar de consolidação ou usar a distância mediana entre grupos (receita do Ente) para juntar mais
os fragmentos, medindo a pureza antes. Enquanto isso, a aba "Aparições únicas" já esconde a cauda de rostos de 1 foto.

## O que eu vi e preciso te contar
1. **Você mesmo acionou a análise** às 01:31 (8 s depois de eu abrir o app): a telemetria mostra `aba_pessoas` e
   `analise_ativada` antes de qualquer toque meu. Depois disso não trouxe mais a galeria pra frente do que você usava.
2. **Proveniência do modelo de rosto:** o `mobilefacenet.tflite` (192-d) que a câmera e a galeria usam é byte a byte o
   do app FaceRecognitionAuth (MCarlomagno). O "BSD-3" é a licença do app dele; os pesos não têm origem declarada.
   Para estudo/uso pessoal tudo bem; para produto comercial, o candidato limpo é o MobileFaceNet 128-d do Qualcomm AI
   Hub (Apache-2.0), que exige recalibrar limiares. Embedding facial é dado biométrico (LGPD): a tela de consentimento
   "Começar análise" existe por isso.
3. **Videira** (achado colateral da varredura): o reconhecimento é 100% AWS Rekognition; `init_aws_collection.js` tem
   chave AWS em texto no git (já apontado na auditoria de 26/07 e ainda lá); apagar foto não apaga o rosto na AWS
   (`DeleteFaces` não existe no código) e a busca `/api/media/search` é pública com rate limit que abre se o Postgres cair.
   Não mexi: é produção.

## Próximos passos (na ordem que você decidir)
- Edição na galeria (filtros, cor, luz, recorte/troca de fundo) reaproveitando a câmera via módulo `:core`.
- Recursos da Galeria Xiaomi viáveis offline: borracha mágica (MI-GAN/LaMa), busca semântica (SigLIP), bokeh por
  profundidade (Depth Anything V2 Small), pós-processo de documento/3x4.
- Indexação em serviço em primeiro plano com notificação (hoje roda só com o app aberto), política "só carregando".
- Obtainium para a galeria (opcional; o app já se atualiza sozinho pelo releases.json do R2).
