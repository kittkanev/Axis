"""Verify the floating Axis overlay across Android activity transitions."""

import time

from smoke_release import APK, PACKAGE, adb, shell, start, tap


def assert_overlay(visible):
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        windows = shell("dumpsys", "window", "windows")
        if ("Axis floating assistant" in windows) == visible:
            return
        time.sleep(1)
    raise AssertionError(f"Unexpected Axis overlay visibility: expected {visible}")


def assert_foreground(package):
    activities = shell("dumpsys", "activity", "activities")
    assert any(package in line and "topResumedActivity" in line for line in activities.splitlines()), activities


def main():
    shell("wm", "dismiss-keyguard")
    shell("wm", "size", "720x1280")
    shell("wm", "density", 230)
    assert "Success" in adb("install", "--no-incremental", "-r", APK, timeout=150)
    shell("appops", "set", PACKAGE, "SYSTEM_ALERT_WINDOW", "allow")
    start()
    tap("Чат")
    tap("Оверлей")
    tap("Показать поверх приложений")
    assert_overlay(True)

    shell("am", "start", "-W", "-a", "android.settings.SETTINGS")
    assert_foreground("com.android.settings")
    assert_overlay(True)
    shell("am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    assert_overlay(True)

    start()
    tap("Закрыть плавающую панель")
    assert_overlay(False)
    crash = shell("logcat", "-b", "crash", "-d")
    assert f"Process: {PACKAGE}," not in crash, crash
    print("Axis overlay survives Settings and Home, and closes from its settings", flush=True)


if __name__ == "__main__":
    main()
