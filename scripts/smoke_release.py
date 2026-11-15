"""Verify the published Axis APK through the normal Android activity."""
from pathlib import Path
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


NAMESPACE = "io.github.kittkanev.axis"
PACKAGE = os.environ.get("AXIS_SMOKE_PACKAGE", NAMESPACE)
APK = Path(sys.argv[1]).resolve()


def component(class_name):
    class_path = f".{class_name}" if PACKAGE == NAMESPACE else f"{NAMESPACE}.{class_name}"
    return f"{PACKAGE}/{class_path}"


def adb(*args, timeout=30):
    return subprocess.check_output(["adb", *map(str, args)], text=True, timeout=timeout).strip()


def shell(*args):
    return adb("shell", *args)


def wait_for(label):
    deadline = time.monotonic() + 35
    visible = []
    while time.monotonic() < deadline:
        try:
            shell("uiautomator", "dump", "/sdcard/axis-smoke.xml")
            tree = ET.fromstring(adb("exec-out", "cat", "/sdcard/axis-smoke.xml"))
            visible = [node.get("text", "") for node in tree.iter("node")]
            for node in tree.iter("node"):
                if node.get("text", "").casefold() == label.casefold():
                    return node
        except (ET.ParseError, subprocess.SubprocessError):
            pass
        time.sleep(1)
    raise AssertionError(f"{label!r} not visible. Screen text: {visible}")


def tap(label):
    node = wait_for(label)
    left, top, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    shell("input", "tap", (left + right) // 2, (top + bottom) // 2)


def start():
    result = shell("am", "start", "-W", "-n", component("MainActivity"))
    assert "Status: ok" in result, result


def main():
    print(f"Android API {shell('getprop', 'ro.build.version.sdk')}", flush=True)
    shell("wm", "dismiss-keyguard")
    shell("wm", "size", "720x1280")
    shell("wm", "density", 230)
    result = adb("install", "--no-incremental", "-r", APK, timeout=150)
    assert "Success" in result, result
    start()
    wait_for("Подключите модель")
    print("APK installed and launched", flush=True)

    tap("Чат")
    tap("Модели")
    wait_for("Добавить профиль")
    tap("Модели")
    tap("Файлы")
    tap("Новый текст")
    tap("Текст документа")
    shell("input", "text", "Axis-release-test")
    shell("input", "keyevent", 4)
    tap("Сохранить копию")
    tap("SAVE")
    wait_for("Копия сохранена.")
    tap("Выбрать файл")
    tap("Выбрать")
    tap("document-axis.txt")
    wait_for("Axis-release-test")
    print("Saved and reopened a text document via Android", flush=True)

    tap("Файлы")
    tap("Управление")
    tap("Найти приложение")
    shell("input", "text", "com.android.settings")
    shell("input", "keyevent", 4)
    tap("Settings")
    tap("Открыть приложение")
    activity = shell("dumpsys", "activity", "activities")
    assert any("com.android.settings" in line and "topResumedActivity" in line
               for line in activity.splitlines()), "Settings did not open"
    print("Axis opened Android Settings", flush=True)


if __name__ == "__main__":
    main()
