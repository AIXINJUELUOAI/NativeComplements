"""Read-only API37 preflight for navigation hosts and scoped-app container reporting."""
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
    "android.app.BroadcastOptions": [("makeBasic", "()Landroid/app/BroadcastOptions;"), ("setShareIdentityEnabled", "(Z)Landroid/app/BroadcastOptions;"), ("setDeliveryGroupPolicy", "(I)Landroid/app/BroadcastOptions;"), ("toBundle", "()Landroid/os/Bundle;")],
    "android.content.ComponentName": [("flattenToShortString", "()Ljava/lang/String;")],
    "android.view.View": [("getViewRootImpl", "()Landroid/view/ViewRootImpl;")],
    "android.view.ViewGroup": [("getChildDrawingOrder", "(I)I")],
    "com.android.server.wm.WindowState": [("getConfiguration", "()Landroid/content/res/Configuration;"), ("getInsetsState", "(Z)Landroid/view/InsetsState;"), ("getFrame", "()Landroid/graphics/Rect;"), ("notifyInsetsChanged", "()V"), ("isRequestedVisible", "(I)Z"), ("isVisible", "()Z"), ("finishDrawing", "(Landroid/view/SurfaceControl$Transaction;I)Z")],
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
checks["android.view.SurfaceControl"] = [("isSameSurface", "(Landroid/view/SurfaceControl;)Z")]
checks["android.view.SurfaceControl$Builder"] = [("<init>", "()V"), ("setName", "(Ljava/lang/String;)Landroid/view/SurfaceControl$Builder;"), ("setParent", "(Landroid/view/SurfaceControl;)Landroid/view/SurfaceControl$Builder;"), ("setHidden", "(Z)Landroid/view/SurfaceControl$Builder;"), ("setColorLayer", "()Landroid/view/SurfaceControl$Builder;"), ("build", "()Landroid/view/SurfaceControl;")]
checks["android.view.SurfaceControl$Transaction"] = [("<init>", "()V"), ("apply", "()V"), ("close", "()V")] + [(n, "(Landroid/view/SurfaceControl;)Landroid/view/SurfaceControl$Transaction;") for n in ("show", "hide")] + [(n, "(Landroid/view/SurfaceControl;" + sig + ")Landroid/view/SurfaceControl$Transaction;") for n, sig in [("setColor", "[F"), ("setAlpha", "F"), ("setPosition", "FF"), ("setWindowCrop", "II"), ("setLayer", "I"), ("reparent", "Landroid/view/SurfaceControl;")]]
checks["com.android.server.wm.DisplayContent"].append(("getDisplayPolicy", "()Lcom/android/server/wm/DisplayPolicy;"))
checks["com.android.server.wm.WindowState"].append(("removeImmediately", "()V"))
checks["android.view.WindowManager$LayoutParams"] = [("getFitInsetsTypes", "()I"), ("setFitInsetsTypes", "(I)V")]
checks["android.view.WindowLayout"] = [("computeFrames", "(Landroid/view/WindowManager$LayoutParams;Landroid/view/InsetsState;Landroid/graphics/Rect;Landroid/graphics/Rect;IIIIFLandroid/window/ClientWindowFrames;)V")]
checks["com.android.server.wm.DisplayPolicy"].append(("layoutWindowLw", "(Lcom/android/server/wm/WindowState;Lcom/android/server/wm/WindowState;Lcom/android/server/wm/DisplayFrames;)V"))
checks["android.view.Window"] = [("getAttributes", "()Landroid/view/WindowManager$LayoutParams;"), ("getCallback", "()Landroid/view/Window$Callback;")]
checks["android.view.WindowInsetsController"] = [("getRequestedVisibleTypes", "()I")]
checks["android.view.WindowInsets$Builder"] = [("setInsets", "(ILandroid/graphics/Insets;)Landroid/view/WindowInsets$Builder;"), ("setInsetsIgnoringVisibility", "(ILandroid/graphics/Insets;)Landroid/view/WindowInsets$Builder;")]
checks["com.android.internal.policy.DecorView"] = []
checks["android.view.ViewRootImpl"] = []
checks["com.android.server.wm.WindowContainer"].append(("forAllWindows", "(Ljava/util/function/Consumer;Z)V"))
checks["com.android.server.wm.WindowState"] += [
    ("getBaseType", "()I"), ("getParentWindow", "()Lcom/android/server/wm/WindowState;"), ("getOwningUid", "()I")]
checks["android.view.WindowManagerGlobal"] = [
    ("getInstance", "()Landroid/view/WindowManagerGlobal;"), ("getWindowViews", "()Ljava/util/ArrayList;"),
    ("addView", "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;Landroid/view/Display;Landroid/view/Window;I)V")]
# Reflection uses setAccessible for these ROM internals; existence/signature, not public visibility, is the contract.
reflected_checks = {
    "com.android.internal.policy.DecorView": [
        ("calculateNavigationBarColor", "(I)I"),
        ("updateColorViews", "(Landroid/view/WindowInsets;Z)Landroid/view/WindowInsets;"),
        ("onApplyWindowInsets", "(Landroid/view/WindowInsets;)Landroid/view/WindowInsets;"),
        ("drawLegacyNavigationBarBackground", "(Landroid/graphics/RecordingCanvas;)V")],
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

for name, methods in reflected_checks.items():
    owner = "L" + name.replace(".", "/") + ";"
    for method, descriptor in methods:
        assert sum(m["name"] == method and m["descriptor"] == descriptor for m in classes[owner]["methods"]) == 1, (owner, method, descriptor)
assert any(f["name"] == "mWindow" and f["type"] == "Lcom/android/internal/policy/PhoneWindow;" for f in classes["Lcom/android/internal/policy/DecorView;"]["fields"])
count = 0
for name, methods in checks.items():
    owner = "L" + name.replace(".", "/") + ";"
    assert owner in classes, owner
    for method, descriptor in methods:
        matches = [entry for entry in classes[owner]["methods"] if entry["name"] == method and entry["descriptor"] == descriptor]
        assert len(matches) == 1 and matches[0]["access_flags"] & 1, (owner, method, descriptor)
        count += 1
for owner, name, descriptor in [
    ("Lcom/android/server/wm/DisplayPolicy;", "mNavBarBackgroundWindowCandidate", "Lcom/android/server/wm/WindowState;"),
    ("Lcom/android/server/wm/WindowState;", "mGlobalScale", "F"),
    ("Lcom/android/server/wm/WindowState;", "mClient", "Landroid/view/IWindow;"),
    ("Landroid/view/ViewRootImpl;", "mWindow", "Landroid/view/ViewRootImpl$W;"),
    ("Landroid/view/WindowManager$LayoutParams;", "surfaceInsets", "Landroid/graphics/Rect;"),
]:
    assert any(f["name"] == name and f["type"] == descriptor for f in classes[owner]["fields"]), (owner, name)
templates = json.loads((ROOT / "app/src/main/assets/navbar/templates.json").read_text(encoding="utf-8"))
for owner, name, descriptor in [
    ("Lcom/android/launcher3/taskbar/TaskbarDragLayer;", "mBackgroundRenderer", "Lcom/android/launcher3/taskbar/TaskbarBackgroundRenderer;"),
    ("Lcom/android/launcher3/taskbar/TaskbarBackgroundRenderer;", "paint", "Landroid/graphics/Paint;"),
    ("Lcom/android/launcher3/taskbar/TaskbarBackgroundRenderer;", "strokePaint", "Landroid/graphics/Paint;"),
]:
    assert any(field["name"] == name and field["type"] == descriptor for field in classes[owner]["fields"]), (owner, name)
assert templates["name"] == "类原生补全计划导航条白名单"
whitelist = {pkg for pkg, app in templates["packages"].items()
             if app["rules"] == {"*": {"mode": 0, "source": "白名单", "base_only": False}}}
assert {"com.openai.chatgpt", "com.coolapk.market", "com.android.settings", "com.android.chrome",
        "org.telegram.messenger", "dev.nativecomplements.module"} <= whitelist
assert not any(pkg.startswith("com.google.android.") for pkg in templates["packages"])  # prefix rule in code
assert templates["packages"]["com.max.xiaoheihe"]["rules"]["com.max.xiaoheihe.module.story.StoryActivity"] == {
    "mode": 2, "source": "内置规则", "base_only": True}
learned = [rule for app in templates["packages"].values() for rule in app["rules"].values() if rule.get("learned")]
manual = [rule for app in templates["packages"].values() for rule in app["rules"].values() if rule.get("manual")]
assert all(rule["mode"] in (0, 1, 2) and rule["source"] == "人工确认" for rule in manual)
assert all(rule["mode"] in (0, 1, 2) and rule["source"] == "学习规则" and isinstance(rule["versionCode"], int)
           and rule["samples"] >= 3 and rule["agreement"] >= 0.9 for rule in learned)
assert all(app["enabled"] and "translucent_rules" not in app for app in templates["packages"].values())
assert all(word not in json.dumps(templates, ensure_ascii=False) for word in ("ColorOS", "HyperOS", "HyperNavBar"))
print(f"PASS: {count} exact public API37 methods; {len(whitelist)} whitelist applications plus com.google.android.*, {len(learned)} ignored historical learned rules, "
      "everything else AUTO; device behavior awaits verification")
