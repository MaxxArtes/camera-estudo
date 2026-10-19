#!/usr/bin/env python3
"""Banco de avaliação do Tradutor (0.32): compara o que o app leu com um gabarito feito à mão.

Ideia do FrankYomik (gabarito congelado e taxa de erro por caractere a cada mudança de OCR), reescrita aqui só com a
biblioteca padrão. Mede o reconhecimento e a junção das linhas em falas, que é onde as mudanças da 0.32 mexem
(faixas, segunda passada, onomatopeia), e não a tradução.

Uso:
    python3 avalia.py GABARITO.json PASTA_DA_SAIDA [--iou 0.3]

GABARITO.json:
    {"quadros": [{"arquivo": "<nome>", "falas": [{"texto": "...", "caixa": [esq, topo, dir, base], "sfx": false}]}]}
    As caixas são em pixels da imagem <nome>.jpg que o próprio app exportou (ver README.md).
PASTA_DA_SAIDA: a pasta tirada do aparelho com adb pull, com um <nome>.json por quadro.

Saída: por quadro e no total, cobertura (falas do gabarito achadas), precisão (falas do app que existem no gabarito),
CER (taxa de erro por caractere nas falas casadas), juntadas a mais (duas do gabarito numa só do app) e onomatopeias
marcadas certo. Código de saída 0 sempre que conseguiu medir.
"""
import argparse
import json
import os
import sys
import unicodedata


def normaliza(t):
    t = unicodedata.normalize("NFKC", t).lower()
    return " ".join(t.split())


def distancia(a, b):
    """Levenshtein por caractere."""
    if len(a) < len(b):
        a, b = b, a
    anterior = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        atual = [i]
        for j, cb in enumerate(b, 1):
            atual.append(min(anterior[j] + 1, atual[j - 1] + 1, anterior[j - 1] + (ca != cb)))
        anterior = atual
    return anterior[-1]


def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    i = ix * iy
    u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i
    return i / u if u > 0 else 0.0


def contem(maior, menor):
    """Fração da caixa `menor` dentro de `maior`."""
    ix = max(0, min(maior[2], menor[2]) - max(maior[0], menor[0]))
    iy = max(0, min(maior[3], menor[3]) - max(maior[1], menor[1]))
    area = (menor[2] - menor[0]) * (menor[3] - menor[1])
    return ix * iy / area if area > 0 else 0.0


def avalia_quadro(esperadas, achadas, limiar):
    pares = sorted(((iou(e["caixa"], a["caixa"]), ie, ia) for ie, e in enumerate(esperadas) for ia, a in enumerate(achadas)),
                   reverse=True)
    usadas_e, usadas_a, casadas = set(), set(), []
    for v, ie, ia in pares:
        if v < limiar or ie in usadas_e or ia in usadas_a:
            continue
        usadas_e.add(ie)
        usadas_a.add(ia)
        casadas.append((ie, ia))
    erros = sum(distancia(normaliza(esperadas[ie]["texto"]), normaliza(achadas[ia]["texto"])) for ie, ia in casadas)
    letras = sum(len(normaliza(esperadas[ie]["texto"])) for ie, _ in casadas)
    # juntada a mais: uma fala do app cobre (80%+) duas ou mais do gabarito
    juntadas = sum(1 for a in achadas if sum(1 for e in esperadas if contem(a["caixa"], e["caixa"]) >= 0.8) >= 2)
    sfx_certo = sum(1 for ie, ia in casadas if bool(esperadas[ie].get("sfx")) == bool(achadas[ia].get("sfx")))
    return {"esperadas": len(esperadas), "achadas": len(achadas), "casadas": len(casadas),
            "erros": erros, "letras": letras, "juntadas": juntadas, "sfx_certo": sfx_certo}


def principal():
    ap = argparse.ArgumentParser()
    ap.add_argument("gabarito")
    ap.add_argument("saida")
    ap.add_argument("--iou", type=float, default=0.3)
    args = ap.parse_args()
    with open(args.gabarito, encoding="utf-8") as f:
        gabarito = json.load(f)
    total = {"esperadas": 0, "achadas": 0, "casadas": 0, "erros": 0, "letras": 0, "juntadas": 0, "sfx_certo": 0}
    faltou = []
    print(f"{'quadro':40} {'cobert':>7} {'precis':>7} {'CER':>7} {'junt':>5}")
    for q in gabarito["quadros"]:
        caminho = os.path.join(args.saida, q["arquivo"] + ".json")
        if not os.path.exists(caminho):
            faltou.append(q["arquivo"])
            continue
        with open(caminho, encoding="utf-8") as f:
            achadas = json.load(f)["falas"]
        r = avalia_quadro(q["falas"], achadas, args.iou)
        for k in total:
            total[k] += r[k]
        cob = r["casadas"] / r["esperadas"] if r["esperadas"] else 1.0
        pre = r["casadas"] / r["achadas"] if r["achadas"] else 1.0
        cer = r["erros"] / r["letras"] if r["letras"] else 0.0
        print(f"{q['arquivo'][:40]:40} {cob:7.1%} {pre:7.1%} {cer:7.1%} {r['juntadas']:5d}")
    cob = total["casadas"] / total["esperadas"] if total["esperadas"] else 1.0
    pre = total["casadas"] / total["achadas"] if total["achadas"] else 1.0
    cer = total["erros"] / total["letras"] if total["letras"] else 0.0
    sfx = total["sfx_certo"] / total["casadas"] if total["casadas"] else 1.0
    print(f"{'TOTAL':40} {cob:7.1%} {pre:7.1%} {cer:7.1%} {total['juntadas']:5d}   onomatopeia certa {sfx:.1%}")
    if faltou:
        print(f"sem saída do app para {len(faltou)} quadro(s): {', '.join(faltou[:5])}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(principal())
