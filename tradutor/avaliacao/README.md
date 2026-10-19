# Banco de avaliação do Tradutor

Mede o reconhecimento e a junção das linhas em falas contra um gabarito feito à mão, para que mudanças como as da 0.32
(faixas no quadro alto, segunda passada invertida, onomatopeia fora da junção) sejam aprovadas por número, e não por
impressão. Ideia do FrankYomik, que congela imagens com o texto certo e mede a taxa de erro por caractere a cada mudança.

## 1. Exportar do aparelho

A exportação vem desligada. Para ligar, crie o marcador por adb:

```
adb shell mkdir -p /sdcard/Android/data/br.maxymus.tradutor/files/avaliacao
adb shell touch /sdcard/Android/data/br.maxymus.tradutor/files/avaliacao/ligado
```

Abra no leitor os capítulos do conjunto. Para cada quadro que o app prepara, ficam na pasta `<nome>.jpg` (a imagem
decodificada, a mesma em que as caixas foram medidas) e `<nome>.json` (falas, caixas, onomatopeia e tradução). Quadro que
já estava pronto no disco não é preparado de novo: limpe o cache do app antes, se for medir um capítulo já lido.

```
adb pull /sdcard/Android/data/br.maxymus.tradutor/files/avaliacao ./saida
adb shell rm /sdcard/Android/data/br.maxymus.tradutor/files/avaliacao/ligado
```

Nada disso vai para a rede nem para a telemetria. A pasta é do app e some com ele.

## 2. Gabarito

Uma vez só, à mão, sobre os `<nome>.jpg`: 20 a 30 quadros que representem o que o dono lê (balão branco, balão colorido,
texto claro em painel escuro, quadro alto, onomatopeia). Formato:

```json
{"quadros": [
  {"arquivo": "<nome>", "falas": [
    {"texto": "You truly are worthy of being my nemesis", "caixa": [120, 340, 610, 470], "sfx": false},
    {"texto": "BOOM", "caixa": [40, 900, 700, 1180], "sfx": true}
  ]}
]}
```

A caixa de cada fala cobre o balão inteiro (todas as linhas dele), em pixels da imagem exportada.

## 3. Medir

```
python3 avalia.py gabarito.json ./saida
```

Por quadro e no total: cobertura, precisão, CER (taxa de erro por caractere nas falas casadas), juntadas a mais (duas
falas do gabarito que o app juntou numa só) e onomatopeia marcada certo. Para comparar duas versões, exporte o mesmo
conjunto com cada uma (limpando o cache entre elas) e rode o script nas duas saídas.

O gabarito e as imagens são conteúdo de terceiros: ficam na bancada e não entram no repositório.
