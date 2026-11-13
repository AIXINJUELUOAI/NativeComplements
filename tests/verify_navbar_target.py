"""Read-only API37 preflight for the system-only navigation backend."""
from pathlib import Path
import hashlib
import json
import zipfile
import verify_signal_target as dex

ROOT = Path(__file__).resolve().parents[1]
checks = {
    "android.content.Context": [("sendBroadcastAsUser", "(Landroid/content/Intent;Landroid/os/UserHandle;Ljava/lang/String;Landroid/os/Bundle;)V")],
    "com.android.server.wm.WindowManagerService": [("systemReady", "()V"), ("requestTraversal", "()V"), ("updateFocusedWindowLocked", "(IZ)Z")],
    "com.android.server.wm.DisplayPolicy": [("finishPostLayoutPolicyLw", "()V")],
    "com.android.server.wm.RootWindowContainer": [("getDefaultDisplay", "()Lcom/android/server/wm/DisplayContent;")],
    "com.android.server.wm.DisplayContent": [("getInsetsStateController", "()Lcom/android/server/wm/InsetsStateController;")],
    "com.android.server.wm.InsetsStateController": [("getRawInsetsState", "()Landroid/view/InsetsState;")],
    "com.android.server.wm.WindowContainer": [("getDisplayContent", "()Lcom/android/server/wm/DisplayContent;"), ("getSurfaceControl", "()Landroid/view/SurfaceControl;")],
    "com.android.server.wm.ConfigurationContainer": [("getWindowingMode", "()I"), ("getBounds", "()Landroid/graphics/Rect;")],
    "com.android.server.wm.WindowState": [("getInsetsState", "(Z)Landroid/view/InsetsState;"), ("getFrame", "()Landroid/graphics/Rect;"), ("notifyInsetsChanged", "()V"), ("isRequestedVisible", "(I)Z"), ("isVisible", "()Z"), ("finishDrawing", "(Landroid/view/SurfaceControl$Transaction;I)Z")],
    "android.view.InsetsState": [("<init>", "(Landroid/view/InsetsState;Z)V"), ("sourceSize", "()I"), ("sourceAt", "(I)Landroid/view/InsetsSource;"), ("removeSourceAt", "(I)V"), ("calculateInsets", "(Landroid/graphics/Rect;Landroid/graphics/Rect;IZ)Landroid/graphics/Insets;")],
    "android.view.InsetsSource": [("getType", "()I"), ("isVisible", "()Z")],
    "android.window.ScreenCaptureInternal": [("captureLayers", "(Landroid/window/ScreenCaptureInternal$LayerCaptureArgs;)Landroid/window/ScreenCaptureInternal$ScreenshotHardwareBuffer;")],
    "android.window.ScreenCaptureInternal$LayerCaptureArgs$Builder": [("<init>", "(Landroid/view/SurfaceControl;)V"), ("build", "()Landroid/window/ScreenCaptureInternal$LayerCaptureArgs;")],
    "android.window.ScreenCaptureInternal$CaptureArgs$Builder": [("setSourceCrop", "(Landroid/graphics/Rect;)Landroid/window/ScreenCaptureInternal$CaptureArgs$Builder;"), ("setFrameScale", "(FF)Landroid/window/ScreenCaptureInternal$CaptureArgs$Builder;")],
    "com.android.internal.os.TransferPipe": [("getWriteFd", "()Landroid/os/ParcelFileDescriptor;"), ("kill", "()V")],
    "com.android.internal.os.ByteTransferPipe": [("<init>", "()V"), ("get", "()[B")],
    "com.android.systemui.navigationbar.views.NavigationBarView": [],
    "com.android.systemui.navigationbar.gestural.NavigationHandle": [("setDarkIntensity", "(F)V")],
    "com.android.launcher3.taskbar.TaskbarDragLayer": [("dispatchDraw", "(Landroid/graphics/Canvas;)V")],
    "com.android.launcher3.taskbar.TaskbarBackgroundRenderer": [("draw", "(Landroid/graphics/Canvas;)V")],
    "com.android.launcher3.taskbar.StashedHandleView": [("updateHandleColor", "(ZZ)V")],
}
dex.TARGETS = ["L" + name.replace(".", "/") + ";" for name in checks]
classes = {}
for relative, expected_hash in [
    ("verification/navbar-system/framework.jar", "89faef564a6b48459c5ffa39ff73523801481524b669493b5b054709cc0e734f"),
    ("verification/navbar-system/services.jar", "3ace355c3bfc525cf0145088dc6bdcad37b0b09c1a163ad09a2276aa653f2de9"),
    ("verification/split-shade-device/SystemUI.apk", "a4bf853b529f6ec7480409fa4f6ba53044a2e409f84c8f20bd320e928c883972"),
    ("verification/navbar-system/NexusLauncher.apk", "3d7c14c02a7a42c9b88b0d5ced4662330ce6e6989563dab5abe9a8158cd70b56"),
]:
    path = ROOT / relative
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected_hash, relative
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                classes.update(dex.Dex(archive.read(name)).classes)

count = 0
for name, methods in checks.items():
    owner = "L" + name.replace(".", "/") + ";"
    assert owner in classes, owner
    for method, descriptor in methods:
        matches = [entry for entry in classes[owner]["methods"] if entry["name"] == method and entry["descriptor"] == descriptor]
        assert len(matches) == 1 and matches[0]["access_flags"] & 1, (owner, method, descriptor)
        count += 1
templates = json.loads((ROOT / "app/src/main/assets/navbar/templates.json").read_text(encoding="utf-8"))
for owner, name, descriptor in [
    ("Lcom/android/launcher3/taskbar/TaskbarDragLayer;", "mBackgroundRenderer", "Lcom/android/launcher3/taskbar/TaskbarBackgroundRenderer;"),
    ("Lcom/android/launcher3/taskbar/TaskbarBackgroundRenderer;", "paint", "Landroid/graphics/Paint;"),
    ("Lcom/android/launcher3/taskbar/TaskbarBackgroundRenderer;", "strokePaint", "Landroid/graphics/Paint;"),
]:
    assert any(field["name"] == name and field["type"] == descriptor for field in classes[owner]["fields"]), (owner, name)
assert len(templates["packages"]) == 399
assert sum(len(app["rules"]) for app in templates["packages"].values()) == 1352
assert templates["omitted"] == 22
assert "Copyright (c) 2025 Ianzb" in (ROOT / "app/src/main/assets/navbar/HyperNavBarRules-LICENSE.txt").read_text(encoding="utf-8")
print(f"PASS: {count} exact public API37 methods and 399 applications / 1352 converted Activity rules; device behavior awaits verification")
