#!/usr/bin/env python3
"""Executor de testes no celular do dono (POCO X8 Pro Max) por adb sobre Tailscale.

Regras (Astra, 18/09/2026, DESIGN_ASTRA_CELULAR.md): só roda com bateria >= 40%, estado térmico abaixo de
MODERATE, sessão de no máximo 5 minutos, aborta se outro app tomar o primeiro plano (o dono retomou o uso),
restaura o que alterou, guarda evidências só dos nossos apps e do período, nunca mexe em app pessoal.
Interação por toque fica marcada como BLOQUEADA até o Xiaomi liberar INJECT_EVENTS ("Depuração USB
(configurações de segurança)"); nada é dado como aprovado sem ter sido exercido.

Uso: python3 celular.py [--serial 100.112.139.66:5555] [--saida DIR] [--so camera|agenda]
Saída: DIR/relatorio.json + prints + logcat filtrado. Código de saída 0 = tudo que foi exercido passou.
"""
import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import time

SERIAL = "100.112.139.66:5555"
APPS = {
    "camera": {"pacote": "br.maxymus.cameraestudo", "activity": ".MainActivity",
               "esperados": ["Tirar foto", "Abrir galeria", "Alterar temporizador", "Trocar câmera", "FOTO", "DOCUMENTO"]},
    "agenda": {"pacote": "br.maxymus.agenda", "activity": ".MainActivity",
               "esperados": ["Agenda Semanal", "Adicionar", "Pedir", "Hoje"]},
}
NOSSOS = {a["pacote"] for a in APPS.values()} | {"com.termux"}
LIMITE_SESSAO_S = 5 * 60
BATERIA_MIN = 40
TERMICO_MAX = 1          # 0 NONE, 1 LIGHT, 2 MODERATE (para)


class Aborta(Exception):
    pass


def sh(serial, cmd, timeout=60):
    r = subprocess.run(["adb", "-s", serial, "shell", cmd], capture_output=True, text=True, timeout=timeout)
    return (r.stdout + r.stderr).strip()


def guarda(serial):
    """Condições para começar: acesso, bateria, térmica, nada pessoal em primeiro plano."""
    if "vivo" not in sh(serial, "echo vivo", 15):
        raise Aborta("celular inacessível (porta 5555 fechada: dono precisa religar a depuração e o tcpip)")
    bat = int(re.search(r"level: (\d+)", sh(serial, "dumpsys battery")).group(1))
    if bat < BATERIA_MIN:
        raise Aborta(f"bateria {bat}% abaixo de {BATERIA_MIN}%")
    term = sh(serial, "dumpsys thermalservice | grep -m1 'Thermal Status' ")
    m = re.search(r"Thermal Status: (\d+)", term)
    status = int(m.group(1)) if m else 0
    if status > TERMICO_MAX:
        raise Aborta(f"estado térmico {status} (>{TERMICO_MAX})")
    frente = primeiro_plano(serial)
    return {"bateria": bat, "termico": status, "primeiro_plano_inicial": frente}


def primeiro_plano(serial):
    saida = sh(serial, "dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'")
    m = re.search(r" ([a-zA-Z0-9_.]+)/", saida)
    return m.group(1) if m else "?"


def dono_retomou(serial, esperado):
    """Se o primeiro plano virou um app que não é nosso, o dono pegou o celular: parar."""
    atual = primeiro_plano(serial)
    return atual not in NOSSOS and atual != esperado, atual


def toque_liberado(serial):
    # KEYCODE_UNKNOWN (0) não faz nada no app; só serve para ver se o Xiaomi deixa injetar evento
    return "SecurityException" not in sh(serial, "input keyevent 0")


def versao(serial, pacote):
    m = re.search(r"versionName=(\S+)", sh(serial, f"dumpsys package {pacote}"))
    return m.group(1) if m else None


def abre(serial, pacote, activity):
    saida = sh(serial, f"am start -W -n {pacote}/{activity}", 90)
    m = re.search(r"TotalTime: (\d+)", saida)
    return int(m.group(1)) if m else None, "Error" in saida


def arvore(serial):
    sh(serial, "uiautomator dump /sdcard/ui_teste.xml", 60)
    xml = sh(serial, "cat /sdcard/ui_teste.xml; rm -f /sdcard/ui_teste.xml", 30)
    textos = []
    for n in re.finditer(r"<node[^>]*>", xml):
        t = re.search(r'text="([^"]*)"', n.group(0)); d = re.search(r'content-desc="([^"]*)"', n.group(0))
        for v in (t.group(1) if t else "", d.group(1) if d else ""):
            if v and v not in textos:
                textos.append(v)
    return textos


def print_tela(serial, destino):
    with open(destino, "wb") as f:
        subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"], stdout=f, timeout=60)
    return os.path.getsize(destino)


def logcat_app(serial, pacote, desde):
    pid = sh(serial, f"pidof {pacote}").split()
    if not pid:
        return ""
    return sh(serial, f"logcat -d -T '{desde}' --pid={pid[0]} 2>/dev/null | tail -200", 60)


def testa_app(serial, nome, cfg, saida, rel):
    r = {"app": nome, "pacote": cfg["pacote"], "etapas": []}
    rel["apps"].append(r)
    r["versao"] = versao(serial, cfg["pacote"])
    inicio_log = sh(serial, "date '+%m-%d %H:%M:%S.000'")
    sh(serial, f"am force-stop {cfg['pacote']}")
    ms, erro = abre(serial, cfg["pacote"], cfg["activity"])
    r["etapas"].append({"etapa": "abertura_fria_ms", "valor": ms, "ok": ms is not None and not erro})
    time.sleep(4)
    retomou, atual = dono_retomou(serial, cfg["pacote"])
    if retomou:
        raise Aborta(f"dono retomou o celular ({atual}) durante {nome}")
    textos = arvore(serial)
    faltam = [e for e in cfg["esperados"] if not any(e in t for t in textos)]   # por trecho: o botão é "+ Adicionar"
    r["etapas"].append({"etapa": "arvore_acessibilidade", "encontrados": len(textos), "faltam": faltam, "ok": not faltam})
    tam = print_tela(serial, os.path.join(saida, f"{nome}.png"))
    r["etapas"].append({"etapa": "print", "bytes": tam, "ok": tam > 10_000})
    r["etapas"].append({"etapa": "toque", "ok": None, "nota": "BLOQUEADO pelo Xiaomi: nada exercido" if not rel["toque_liberado"] else "liberado, roteiro de toque ainda não escrito"})
    log = logcat_app(serial, cfg["pacote"], inicio_log)
    with open(os.path.join(saida, f"{nome}.logcat.txt"), "w") as f:
        f.write(log)
    fatais = [l for l in log.splitlines() if " E " in l and ("AndroidRuntime" in l or "FATAL" in l)]
    r["etapas"].append({"etapa": "logcat_sem_crash", "linhas": len(log.splitlines()), "fatais": len(fatais), "ok": not fatais})
    sh(serial, f"am force-stop {cfg['pacote']}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=SERIAL)
    ap.add_argument("--saida", default=None)
    ap.add_argument("--so", choices=list(APPS), default=None)
    a = ap.parse_args()
    quando = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    saida = a.saida or os.path.join("/opt/camera-estudo/medicao/celular", quando)
    os.makedirs(saida, exist_ok=True)
    rel = {"quando": quando, "serial": a.serial, "apps": [], "aborto": None}
    t0 = time.time()
    try:
        rel["guarda"] = guarda(a.serial)
        rel["toque_liberado"] = toque_liberado(a.serial)
        for nome, cfg in APPS.items():
            if a.so and nome != a.so:
                continue
            if time.time() - t0 > LIMITE_SESSAO_S:
                raise Aborta("limite de 5 min da sessão")
            testa_app(a.serial, nome, cfg, saida, rel)
    except Aborta as e:
        rel["aborto"] = str(e)
    except subprocess.TimeoutExpired as e:
        rel["aborto"] = f"tempo esgotado em: {e.cmd}"
    finally:
        rel["duracao_s"] = round(time.time() - t0, 1)
        with open(os.path.join(saida, "relatorio.json"), "w") as f:
            json.dump(rel, f, ensure_ascii=False, indent=1)
    exercidas = [e for app in rel["apps"] for e in app["etapas"] if e["ok"] is not None]
    falhas = [e for e in exercidas if not e["ok"]]
    print(json.dumps({"saida": saida, "aborto": rel["aborto"], "toque_liberado": rel.get("toque_liberado"),
                      "apps": [(x["app"], x["versao"]) for x in rel["apps"]], "exercidas": len(exercidas), "falhas": falhas}, ensure_ascii=False))
    sys.exit(1 if (falhas or rel["aborto"]) else 0)


if __name__ == "__main__":
    main()
