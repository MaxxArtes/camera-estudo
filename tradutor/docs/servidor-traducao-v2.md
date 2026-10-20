# Contrato do serviço de tradução, versão 2 (app Tradutor 0.33)

O serviço `POST https://pocketlm.maxymus.dev.br/tradutor/traduzir` não está neste repositório. **PLANO, não está em uso:**
o app 0.33 continua no contrato antigo (manda só `de`, `para` e `falas`; lê só `falas`) e NÃO manda versão, ids, tipos nem
contexto, nem faz pedido de recuperação (`CONTRATO_V2 = false` em Traducao.kt). Este documento diz o que o app passa a
mandar e aceitar quando o servidor v2 existir e a chave for ligada. Com ela ligada, o app só volta ao formato antigo num
400 ou 422 de formato ("json invalido", "sem falas"); qualquer outro erro não desliga nada (telemetria `modelo_contrato_antigo`).

Atenção ao custo: o limite do Groq é de 8K tokens por minuto, então o `contexto` do servidor v2 deve ficar bem abaixo dos
4000 caracteres que o app monta hoje (ajustar `RegraContexto.MAX_CARACTERES` junto com o servidor).

Ideias estudadas no FrankYomik (tradução da página inteira com id estável, contexto das páginas anteriores, resposta com
formato fixo e recuperação limitada), reescritas aqui.

## Pedido

```json
{
  "de": "en",
  "para": "Portuguese",
  "falas": ["You truly are worthy", "BOOM"],
  "versao": 2,
  "ids": ["f0", "f1"],
  "tipos": ["fala", "sfx"],
  "contexto": ["Han, are you there? / Yes.", "We need to go."]
}
```

- `de`, `para`, `falas`: como hoje. `falas` vem na ordem de leitura.
- `versao`: 2 quando o app manda os campos abaixo.
- `ids`: um id por fala, na mesma ordem de `falas`. Na recuperação, o app manda só as falas que faltaram, com os ids
  originais (pode chegar `["f1", "f4"]`).
- `tipos`: `fala` ou `sfx`. `sfx` é onomatopeia ou título (letra muito maior que a das falas). Sugestão de instrução ao
  modelo: "traduza sfx como uma onomatopeia curta, em maiúsculas, no idioma de destino".
- `contexto`: as falas ORIGINAIS dos até 4 quadros anteriores do capítulo, um item por quadro, falas separadas por " / ",
  no máximo 2000 caracteres por item e 4000 no total. Só no leitor de capítulo; na bolha vem vazio. Sugestão de instrução:
  "use o contexto só para manter nomes, tratamento e pronomes; não o traduza nem o devolva".

## Resposta

O app aceita os dois formatos e prefere o novo:

```json
{"traducoes": {"f0": "Você é mesmo digno", "f1": "BUM"}, "modelo": "…", "id": "…"}
```

```json
{"falas": ["Você é mesmo digno", "BUM"], "modelo": "…", "id": "…"}
```

- `traducoes`: mapa id → tradução. Id desconhecido é ignorado; id que falta, ou tradução em branco, conta como "não veio".
- `falas`: a lista de hoje, na ordem do pedido. `null` ou texto vazio conta como "não veio".
- `modelo` e `id`: como hoje (vão para a telemetria só se tiverem cara de nome de modelo e de id).

Recomendado no servidor: pedir ao modelo saída em JSON com formato fixo (as chaves exatas dos ids recebidos), recusar id
duplicado ou desconhecido, e devolver só o que validou. O app completa o que faltar.

## O que o app faz com a resposta

1. Alinha por id (ou por posição, no formato antigo).
2. Falta fala: pede de novo SÓ as que faltaram; se ainda faltar, pede em duas metades. No máximo dois pedidos extras,
   e só até 6 s depois do primeiro (telemetria `modelo_recuperou`, só com contagens).
3. O que mesmo assim não veio segue o caminho de sempre (tradutor de máquina, depois o offline).

## Cache

A chave do cache de traduções no app continua `destino|texto normalizado`, a da 0.31: as traduções já guardadas seguem
valendo. Se o servidor v2 mudar a tradução de um jeito que exija descartar o cache, a revisão entra na chave junto com a
migração das entradas antigas na carga (para o limite de 20 mil não ficar ocupado por entradas mortas).
