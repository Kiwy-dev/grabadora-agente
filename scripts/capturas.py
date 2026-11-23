#!/usr/bin/env python3
"""
Toma las capturas de pantalla de la documentación de forma automática.

Qué hace:
  1. Usa el teléfono o emulador conectado por adb; si no hay, arranca el emulador indicado (--avd).
  2. Instala el APK desde cero (borra datos anteriores) con los permisos ya concedidos.
  3. Pone la barra de estado en "modo demo" (hora 10:00, batería llena, sin iconos de notificaciones).
  4. Crea datos de ejemplo navegando la app como lo haría una persona: graba audios, los renombra,
     crea una carpeta y mueve un audio.
  5. Recorre las pantallas y guarda las capturas en docs/capturas/.

Uso:
    python3 scripts/capturas.py                       # APK de app/build/outputs/apk/debug
    python3 scripts/capturas.py --apk ruta.apk --avd grabadora --apagar

Requisitos: Android SDK (adb y emulator), un AVD o un teléfono con depuración USB, y el APK compilado
(./gradlew assembleDebug). No usa librerías externas de Python.

Nota: `adb shell input text` no escribe acentos, por eso los nombres de ejemplo no llevan tildes.
"""
import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
PAQUETE = "com.grabadora.agente"
ID = PAQUETE + ":id/"


def sdk_dir() -> Path:
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            return Path(os.environ[var])
    props = RAIZ / "local.properties"
    if props.exists():
        m = re.search(r"^sdk\.dir=(.*)$", props.read_text(), re.M)
        if m:
            return Path(m.group(1).strip())
    sys.exit("No encuentro el Android SDK: define ANDROID_HOME o sdk.dir en local.properties")


SDK = sdk_dir()
ADB = str(SDK / "platform-tools" / "adb")


def adb(*args, check=True, binario=False):
    r = subprocess.run([ADB, *args], capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode(errors='ignore')}")
    return r.stdout if binario else r.stdout.decode(errors="ignore")


def sh(comando: str, check=True):
    return adb("shell", comando, check=check)


# ---------------- Dispositivo ----------------

def hay_dispositivo() -> bool:
    lineas = adb("devices").strip().splitlines()[1:]
    return any(l.endswith("\tdevice") for l in lineas)


def arrancar_emulador(avd: str):
    print(f"Arrancando el emulador {avd}…")
    subprocess.Popen(
        [str(SDK / "emulator" / "emulator"), "-avd", avd, "-no-window", "-no-boot-anim",
         "-no-snapshot-save", "-gpu", "swiftshader_indirect"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    adb("wait-for-device")


def esperar_arranque(limite=300):
    fin = time.time() + limite
    while time.time() < fin:
        if sh("getprop sys.boot_completed", check=False).strip() == "1":
            time.sleep(3)
            return
        time.sleep(2)
    sys.exit("El dispositivo no terminó de arrancar")


def modo_demo(activar: bool):
    if not activar:
        sh("am broadcast -a com.android.systemui.demo -e command exit", check=False)
        return
    sh("settings put global sysui_demo_allowed 1")
    demo = "am broadcast -a com.android.systemui.demo -e command "
    sh(demo + "enter")
    sh(demo + "clock -e hhmm 1000")
    sh(demo + "battery -e level 100 -e plugged false")
    sh(demo + "network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4")
    sh(demo + "notifications -e visible false")


# ---------------- Interfaz ----------------

class Nodo:
    def __init__(self, e):
        self.id = e.get("resource-id", "")
        self.texto = e.get("text", "")
        self.desc = e.get("content-desc", "")
        self.habilitado = e.get("enabled") == "true"
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", e.get("bounds", "[0,0][0,0]")))
        self.centro = ((x1 + x2) // 2, (y1 + y2) // 2)

    def tocar(self):
        sh(f"input tap {self.centro[0]} {self.centro[1]}")


def pantalla():
    for _ in range(5):
        sh("uiautomator dump /sdcard/ui.xml", check=False)
        xml = adb("exec-out", "cat", "/sdcard/ui.xml", check=False)
        if xml.strip().startswith("<?xml"):
            return [Nodo(e) for e in ET.fromstring(xml).iter("node")]
        time.sleep(1)
    return []


def buscar(id=None, texto=None, contiene=None, desc=None, limite=20, todos=False):
    fin = time.time() + limite
    while True:
        nodos = [n for n in pantalla()
                 if (id is None or n.id == ID + id or n.id == id)
                 and (texto is None or n.texto == texto)
                 and (contiene is None or contiene in n.texto)
                 and (desc is None or n.desc == desc)]
        if nodos:
            return nodos if todos else nodos[0]
        if time.time() > fin:
            raise RuntimeError(f"No apareció en pantalla: id={id} texto={texto} contiene={contiene} desc={desc}")
        time.sleep(1)


def tocar(**kw):
    buscar(**kw).tocar()
    time.sleep(1)


def escribir(texto: str):
    sh("input text " + texto.replace(" ", "%s"))


def menu_desbordamiento():
    # Botón ⋮ de la barra; su descripción depende del idioma del sistema
    for n in pantalla():
        if n.desc in ("More options", "Más opciones", "Opciones de menú"):
            n.tocar()
            time.sleep(1)
            return
    raise RuntimeError("No encontré el menú ⋮")


def atras():
    sh("input keyevent KEYCODE_BACK")
    time.sleep(1)


def captura(nombre: str, destino: Path):
    time.sleep(1.2)
    png = adb("exec-out", "screencap", "-p", binario=True)
    (destino / f"{nombre}.png").write_bytes(png)
    print(f"  ✓ {nombre}.png")


# ---------------- Datos de ejemplo ----------------

def grabar(segundos: float, destino=None):
    tocar(id="grabar")
    time.sleep(segundos)
    if destino:
        captura(destino[0], destino[1])
    tocar(id="grabar")
    time.sleep(1)


def limpiar_campo():
    # El diálogo de renombrar trae el nombre seleccionado; lo borramos tecla por tecla
    sh("input keyevent KEYCODE_MOVE_END")
    sh("input keyevent " + " ".join(["KEYCODE_DEL"] * 45))


# ---------------- Recorrido ----------------

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", default=str(RAIZ / "app/build/outputs/apk/debug/app-debug.apk"))
    ap.add_argument("--avd", default="grabadora", help="AVD a arrancar si no hay dispositivo conectado")
    ap.add_argument("--salida", default=str(RAIZ / "docs/capturas"))
    ap.add_argument("--apagar", action="store_true", help="apaga el emulador al terminar (si lo arrancó el script)")
    a = ap.parse_args()

    if not Path(a.apk).exists():
        sys.exit(f"No existe el APK {a.apk}. Compílalo con ./gradlew assembleDebug")
    salida = Path(a.salida)
    salida.mkdir(parents=True, exist_ok=True)

    arrancado = False
    if not hay_dispositivo():
        arrancar_emulador(a.avd)
        arrancado = True
    esperar_arranque()

    print("Instalando el APK desde cero…")
    adb("uninstall", PAQUETE, check=False)
    adb("install", "-g", a.apk)  # -g concede micrófono y notificaciones
    sh("settings put system screen_off_timeout 1800000")
    sh("svc power stayon true", check=False)
    sh("cmd uimode night no", check=False)
    # Sin el tutorial de "escribir con lápiz" del teclado, que tapa los diálogos
    sh("settings put secure stylus_handwriting_enabled 0", check=False)
    # Fechas en español aunque el sistema esté en inglés (Android 13+)
    sh(f"cmd locale set-app-locales {PAQUETE} --locales es-MX", check=False)
    # Sin teclado en pantalla: `input text` escribe directo en el campo. Con el teclado activo,
    # su autocorrección a veces desordena letras ("Trabajo" → "Trabaoj"). Se restaura al final.
    teclado = sh("settings get secure default_input_method", check=False).strip()
    if teclado and teclado != "null":
        sh(f"ime disable {teclado}", check=False)
    modo_demo(True)

    try:
        print("Abriendo la app…")
        sh(f"am start -W -n {PAQUETE}/.ui.MainActivity")
        buscar(id="grabar")
        # La primera vez copia el modelo de voz: esperamos a que el interruptor se habilite
        fin = time.time() + 180
        while not buscar(id="interruptor").habilitado:
            if time.time() > fin:
                raise RuntimeError("El modelo de voz no terminó de prepararse")
            time.sleep(2)
        captura("01-inicio-vacia", salida)

        print("Creando datos de ejemplo…")
        grabar(4, ("02-grabando", salida))
        for nombre, segundos in (("Lista de compras", 3), ("Idea para el proyecto", 5), ("Junta de equipo", 6)):
            if nombre != "Lista de compras":
                grabar(segundos)
            buscar(id="menu").tocar()
            time.sleep(1)
            tocar(texto="Renombrar")
            limpiar_campo()
            escribir(nombre)
            tocar(id="android:id/button1")

        menu_desbordamiento()
        tocar(texto="Nueva carpeta")
        escribir("Trabajo")
        tocar(texto="Crear")

        # Mueve "Junta de equipo" (el más reciente) a Trabajo, con captura del diálogo
        buscar(id="menu").tocar()
        time.sleep(1)
        captura("04-menu-audio", salida)
        tocar(texto="Mover a carpeta")
        captura("05-mover-a-carpeta", salida)
        tocar(texto="Trabajo")
        time.sleep(1)
        captura("03-lista-general", salida)

        print("Carpetas…")
        tocar(texto="Trabajo")
        captura("06-carpeta-trabajo", salida)
        tocar(texto="General")

        print("Modo agente…")
        tocar(id="interruptor")
        buscar(contiene="Escuchando", limite=90)
        captura("07-escuchando", salida)
        modo_demo(False)
        sh("cmd statusbar expand-notifications")
        time.sleep(2)
        captura("08-notificacion", salida)
        sh("cmd statusbar collapse")
        modo_demo(True)
        time.sleep(1)
        tocar(id="interruptor")
        buscar(contiene="Apagado", limite=20)

        print("Ajustes…")
        menu_desbordamiento()
        captura("09-menu-principal", salida)
        tocar(texto="Ajustes")
        captura("10-ajustes", salida)
        atras()
    finally:
        modo_demo(False)
        if teclado and teclado != "null":
            sh(f"ime enable {teclado}", check=False)
            sh(f"ime set {teclado}", check=False)
        sh("svc power stayon false", check=False)
        if arrancado and a.apagar:
            adb("emu", "kill", check=False)

    print(f"Listo: capturas en {salida}")


if __name__ == "__main__":
    main()
