"""Verify the normal voice panel and the Android assistant service on an emulator."""

import subprocess
import time

from smoke_release import APK, PACKAGE, adb, shell, start, tap, wait_for


def invoke_assistant():
    deadline = time.monotonic() + 20
    while True:
        result = subprocess.run(
            ["adb", "shell", "cmd", "voiceinteraction", "show"],
            text=True, capture_output=True, timeout=15,
        )
        if result.returncode == 0:
            break
        if time.monotonic() >= deadline:
            print(result.stdout, result.stderr, shell("dumpsys", "voiceinteraction"), flush=True)
            print(shell("logcat", "-b", "crash", "-d", "-t", 70), flush=True)
            result.check_returncode()
        time.sleep(1)


def main():
    shell("wm", "dismiss-keyguard")
    shell("wm", "size", "720x1280")
    shell("wm", "density", 230)
    assert "Success" in adb("install", "--no-incremental", "-r", APK, timeout=150)
    start()
    wait_for("Подключите модель")
    tap("Чат")
    tap("Настройка Axis")
    wait_for("Разрешить микрофон")
    tap("Открыть модели")
    tap("Добавить профиль")
    wait_for("Google AI Studio")
    wait_for("gemini-3.1-flash-lite")
    tap("Отмена")
    tap("Модели")
    tap("Чат")
    tap("Голос")
    wait_for("Голосовой режим")
    tap("Ввести текст")
    tap("Сообщение")
    shell("input", "text", "Axis-smoke")
    tap("Отправить")
    wait_for("Добавьте профиль модели в Axis.")
    shell("input", "keyevent", "KEYCODE_BACK")
    shell("input", "keyevent", "KEYCODE_BACK")
    start()

    tap("Чат")
    tap("Настройки")
    tap("Сделать Axis ассистентом")
    deadline = time.monotonic() + 15
    while True:
        activity = shell("dumpsys", "activity", "activities")
        if any(("com.android.settings" in line or "permissioncontroller" in line)
               and "topResumedActivity" in line for line in activity.splitlines()):
            break
        if time.monotonic() >= deadline:
            raise AssertionError(f"Android did not show assistant setup:\n{activity}")
        time.sleep(1)
    shell("input", "keyevent", "KEYCODE_BACK")
    shell("cmd", "role", "add-role-holder", "--user", 0, "android.app.role.ASSISTANT", PACKAGE)
    shell("appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "ignore")
    selected = shell("settings", "get", "secure", "voice_interaction_service")
    assert selected == f"{PACKAGE}/.AxisAssistantService", selected
    shell("am", "start", "-W", "-a", "android.settings.SETTINGS")
    # The shell command needs ACCESS_VOICE_INTERACTION_SERVICE on current Android.
    # Root is available on the AOSP CI image. The application runs as its normal UID.
    adb("root")
    adb("wait-for-device")
    invoke_assistant()
    try:
        wait_for("Голосовой режим")
        tap("Ввести текст")
        wait_for("Сообщение")
        tap("Сообщение")
        shell("input", "text", "Open%sSettings%sand%sgo%sto%sAbout%sphone")
        tap("Отправить")
        deadline = time.monotonic() + 15
        while True:
            activities = shell("dumpsys", "activity", "activities")
            if any("com.android.settings" in line and "DeviceInfo" in line and "topResumedActivity" in line
                   for line in activities.splitlines()):
                break
            if time.monotonic() >= deadline:
                raise AssertionError(f"About phone did not open:\n{activities}")
            time.sleep(1)
    except AssertionError:
        print(shell("logcat", "-b", "crash", "-d", "-t", 70), flush=True)
        raise
    shell("cmd", "voiceinteraction", "hide")
    print("Axis opened through the Android voice interaction service", flush=True)


if __name__ == "__main__":
    main()
