"""Verify the normal voice panel and the Android assistant service on an emulator."""

from smoke_release import APK, PACKAGE, adb, shell, start, tap, wait_for


def main():
    shell("wm", "dismiss-keyguard")
    shell("wm", "size", "720x1280")
    shell("wm", "density", 230)
    assert "Success" in adb("install", "--no-incremental", "-r", APK, timeout=150)
    start()
    wait_for("Подключите модель")
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
    tap("Выбрать ассистента")
    activity = shell("dumpsys", "activity", "activities")
    assert any("com.android.settings" in line and "topResumedActivity" in line
               for line in activity.splitlines()), "Android did not open assistant settings"
    shell("cmd", "role", "add-role-holder", "--user", 0, "android.app.role.ASSISTANT", PACKAGE)
    selected = shell("settings", "get", "secure", "voice_interaction_service")
    assert selected == f"{PACKAGE}/.AxisAssistantService", selected
    shell("am", "start", "-W", "-a", "android.settings.SETTINGS")
    shell("cmd", "voiceinteraction", "show")
    wait_for("Голосовой режим")
    tap("Ввести текст")
    wait_for("Сообщение")
    shell("cmd", "voiceinteraction", "hide")
    print("Axis opened through the Android voice interaction service", flush=True)


if __name__ == "__main__":
    main()
