# Medição do editor na bancada (sem aparelho)

Porte fiel em numpy dos motores do editor (`porte_editor.py` = Fundo/Tom do 0.16; `porte2.py` = pós-processamento
validado no 0.17). Roda o `selfie_multiclass_256x256.tflite` com `ai-edge-litert` num venv e gera painéis
lado a lado sobre fotos reais. Foi assim que se achou, em 20/09: (1) o "maior bloco" apagava pessoas em grupo;
(2) o filtro guiado com eps 0,02 BORRAVA a máscara em vez de grudar na borda; (3) os buracos em calça escura são
do modelo (treinado para busto) e se resolvem com fechamento + preenchimento de buracos. Faixa incerta da máscara:
35–50% → 2–5%.

```
python3 -m venv venv && venv/bin/pip install numpy pillow ai-edge-litert
venv/bin/python porte2.py foto1.jpg foto2.jpg   # gera saida/painel2.jpg (original | antiga | nova | deeplab)
```

## Achado 20/09 (porte5/porte6, foto do grupo de 12 do dono)
As manchas de chão presas aos pés NÃO vêm do fechamento nem do preenchimento de buracos (variar raio e condicionar o
preenchimento à probabilidade bruta não muda nada): vêm do modelo sangrando para o chão em volta dos pés a 256².
Remédios medidos: tirar a classe "outros" da pessoa destrói a máscara (57% → 19%: roupa escura cai nessa classe);
interseção com DeepLabV3 não muda (sangra no mesmo lugar); guia RGB no filtro guiado só reduz a faixa incerta
(4,6% → 4,3%). Para Desfocar é inofensivo (chão nítido junto do pé parece profundidade); para Cor/Remover, o remédio
é o pincel Remover do Refinar. Não gastar mais tempo nisso sem outro modelo (corpo inteiro em alta resolução).
