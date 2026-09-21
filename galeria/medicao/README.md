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

## Ladrilhos (porte7.py, 20/09) — NÃO adotar
Rodar o selfie_multiclass em ladrilhos 2x2 (sobreposição 25%, Hann) e tomar o mínimo com o quadro inteiro limpa o chão
entre as pernas no grupo de 12 (remove 5,9% do quadro, quase tudo chão), mas nas selfies APAGA O ROSTO (o rosto cai na
fronteira dos 4 ladrilhos; cada um vê meio rosto e diz fundo). Portão de confiança (veto só onde inteiro < 0,85) e
faixa inferior (só abaixo de 55% da altura) ainda tiram 11–15% do corpo em selfie e o ganho no grupo cai a 1–3%.
3x3 é pior (ladrilho só de perna vira chão-pessoa). Conclusão: modelo de busto não generaliza para recortes; o chão
junto dos pés fica para o pincel Remover do Refinar.
