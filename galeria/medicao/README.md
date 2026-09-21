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
