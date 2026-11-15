"""Verify the floating Axis overlay across Android activity transitions."""

import re
import time

from smoke_assistant import invoke_assistant
from smoke_release import APK, PACKAGE, adb, shell, start, tap, wait_for


def assert_overlay(kind, min_height=0, allow_system_hidden=False):
    deadline = time.monotonic() + 25
    panel = ""
    while time.monotonic() < deadline:
        windows = shell("dumpsys", "window", "windows")
        panel = next((window for window in re.split(r"(?m)^  Window #", windows)
                      if re.search(r"^\d+.*Axis floating assistant", window)), "")
        if not panel and kind is None:
            return
        if kind:
            # Android 14 prints some window types as numbers. Android 15 uses names.
            type_id = {"APPLICATION_OVERLAY": 2038, "ACCESSIBILITY_OVERLAY": 2032}[kind]
            height = re.search(r"Requested w=\d+ h=(\d+)", panel)
            visible = "isVisible=true" in panel
            hidden_by_android = allow_system_hidden and "mForceHideNonSystemOverlayWindow=true" in panel
            if (re.search(rf"\bty=(?:{kind}|{type_id})\b", panel) and (visible or hidden_by_android)
                    and height and int(height.group(1)) >= min_height):
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
    shell("am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity",
          "--ez", "io.github.kittkanev.axis.OPEN_OVERLAY", "true")
    tap("Показать поверх приложений")
    assert_overlay("APPLICATION_OVERLAY")

    shell("am", "start", "-W", "-a", "android.settings.SETTINGS")
    assert_foreground("com.android.settings")
    # Android may protect Settings by hiding non-system overlays.
    assert_overlay("APPLICATION_OVERLAY", allow_system_hidden=True)
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
    invoke_assistant()
    assert_overlay("ACCESSIBILITY_OVERLAY", min_height=140)

    start()
    wait_for("Панель работает поверх приложений")
    tap("Закрыть плавающую панель")
    assert_overlay(None)
    crash = shell("logcat", "-b", "crash", "-d")
    assert f"Process: {PACKAGE}," not in crash, crash
    print("Both Axis overlay types remained visible across Settings and Home", flush=True)


if __name__ == "__main__":
    main()
