"""Verify the floating Axis overlay across Android activity transitions."""

import re
import time

from smoke_release import APK, PACKAGE, adb, shell, start, tap, wait_for


def assert_overlay(kind):
    deadline = time.monotonic() + 25
    panel = ""
    while time.monotonic() < deadline:
        windows = shell("dumpsys", "window", "windows")
        panel = next((window for window in re.split(r"(?m)^  Window #", windows)
                      if re.search(r"^\d+.*Axis floating assistant", window)), "")
        if not panel and kind is None:
            return
        if (kind and f"ty={kind}" in panel and "mHasSurface=true" in panel
                and "mViewVisibility=0x0" in panel):
            return
        time.sleep(1)
    raise AssertionError(f"Unexpected Axis overlay: expected {kind}\n{panel[:5000]}")


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
    assert_overlay("APPLICATION_OVERLAY")

    shell("am", "start", "-W", "-a", "android.settings.SETTINGS")
    assert_foreground("com.android.settings")
    assert_overlay("APPLICATION_OVERLAY")
    shell("input", "keyevent", "KEYCODE_HOME")
    assert_overlay("APPLICATION_OVERLAY")

    start()
    shell("settings", "put", "secure", "enabled_accessibility_services", f"{PACKAGE}/.DeviceControlService")
    shell("settings", "put", "secure", "accessibility_enabled", "1")
    tap("Панель через специальные возможности")
    assert_overlay("ACCESSIBILITY_OVERLAY")
    shell("am", "start", "-W", "-a", "android.settings.SETTINGS")
    assert_foreground("com.android.settings")
    assert_overlay("ACCESSIBILITY_OVERLAY")
    shell("input", "keyevent", "KEYCODE_HOME")
    assert_overlay("ACCESSIBILITY_OVERLAY")

    # Send the same framework request used by the system assistant gesture.
    shell("cmd", "role", "add-role-holder", "--user", 0, "android.app.role.ASSISTANT", PACKAGE)
    shell("cmd", "voiceinteraction", "show")
    assert_overlay("ACCESSIBILITY_OVERLAY")

    start()
    wait_for("Панель работает поверх приложений")
    tap("Закрыть плавающую панель")
    assert_overlay(None)
    crash = shell("logcat", "-b", "crash", "-d")
    assert f"Process: {PACKAGE}," not in crash, crash
    print("Both Axis overlay types remained visible across Settings and Home", flush=True)


if __name__ == "__main__":
    main()
