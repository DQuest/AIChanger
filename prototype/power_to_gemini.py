#!/usr/bin/env python3
"""Прототип: удержание кнопки питания -> Gemini, через adb.

Читает сырые события кнопки питания (pmic_pwrkey) и при удержании
закрывает меню питания vivo и отправляет KEYCODE_ASSIST.
Требует: system vivo_jovi_power_wakeup_switch=0 (BlueLM не перехватывает).
"""
import re
import subprocess
import sys
import threading
import time


def find_power_device():
    out = subprocess.run(["adb", "shell", "getevent", "-pl"],
                         capture_output=True, text=True).stdout
    dev = None
    for line in out.splitlines():
        m = re.match(r"add device \d+: (\S+)", line)
        if m:
            dev = m.group(1)
        elif 'name:' in line and '"pmic_pwrkey"' in line:
            return dev
    sys.exit("pmic_pwrkey не найден")


DEVICE = find_power_device()
HOLD_MS = int(sys.argv[1]) if len(sys.argv) > 1 else 600
ACTION = (
    "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null; "
    "input keyevent KEYCODE_ASSIST"
)

timer = None


def fire():
    t0 = time.monotonic()
    subprocess.run(["adb", "shell", ACTION])
    print(f"-> Gemini ({(time.monotonic() - t0) * 1000:.0f} ms)", flush=True)


proc = subprocess.Popen(
    ["adb", "shell", "getevent", "-l", DEVICE],
    stdout=subprocess.PIPE, text=True, bufsize=1,
)
print(f"Слушаю {DEVICE}, порог удержания {HOLD_MS} ms", flush=True)
for line in proc.stdout:
    if "KEY_POWER" not in line:
        continue
    if "DOWN" in line:
        timer = threading.Timer(HOLD_MS / 1000, fire)
        timer.start()
    elif "UP" in line and timer:
        timer.cancel()
