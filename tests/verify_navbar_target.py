"""Read-only exact SystemUI/framework preflight for the immersive navigation host."""
from pathlib import Path
import hashlib
import zipfile
import verify_signal_target as dex

ROOT = Path(__file__).resolve().parents[1]
NAV = "Lcom/android/systemui/navigationbar/views/NavigationBar;"
PARAMS = "Landroid/view/WindowManager$LayoutParams;"
PROVIDER = "Landroid/view/InsetsFrameProvider;"
TRANSITIONS = "Lcom/android/systemui/shared/statusbar/phone/BarTransitions;"
NAV_TRANSITIONS = "Lcom/android/systemui/navigationbar/views/NavigationBarTransitions;"
BACKGROUND = "Lcom/android/systemui/shared/statusbar/phone/BarTransitions$BarBackgroundDrawable;"
METHODS = {
    NAV: [("getBarLayoutParamsForRotation", "(I)" + PARAMS), ("repositionNavigationBar", "(I)V"), ("onInit", "()V")],
    "Landroid/app/Instrumentation;": [("callApplicationOnCreate", "(Landroid/app/Application;)V")],
    PROVIDER: [("getType", "()I"), ("setInsetsSize", "(Landroid/graphics/Insets;)" + PROVIDER)],
    BACKGROUND: [("draw", "(Landroid/graphics/Canvas;)V")],
    NAV_TRANSITIONS: [],
}
FIELDS = {
    NAV: [("mContext", "Landroid/content/Context;"), ("mNavBarMode", "I"),
          ("mNavigationBarTransitions", NAV_TRANSITIONS)],
    PARAMS: [("providedInsets", "[" + PROVIDER)],
    TRANSITIONS: [("mBarBackground", BACKGROUND)],
}
ARCHIVES = {
    "current API37": [
        ("verification/navbar-system/framework.jar", "89faef564a6b48459c5ffa39ff73523801481524b669493b5b054709cc0e734f"),
        ("verification/split-shade-device/SystemUI.apk", "a4bf853b529f6ec7480409fa4f6ba53044a2e409f84c8f20bd320e928c883972"),
    ],
    "Pixel QPR2 20261113": [
        ("verification/pixel-qpr2-20261113/framework.jar", "466ba9aa9934aa5bc113f9ee738e3fd66cf92b0bb3d02a144d0669b59381015f"),
        ("verification/pixel-qpr2-20261113/apk/SystemUIGoogle.apk", "458b481b241552d67d33963f3cb449a9e5e53d4cfcd28e8bb1d919b814fd982f"),
    ],
}


def dex_views(raw):
    """DEX 041 logical headers share offsets into the physical container."""
    header = 0
    while True:
        view = bytearray(raw)
        if header:
            view[:120] = raw[header:header + 120]
        yield dex.Dex(view)
        if raw[header + 4:header + 7] != b"041":
            break
        size = int.from_bytes(raw[header + 32:header + 36], "little")
        assert size >= 120, ("invalid logical DEX size", header, size)
        header += size
        if header == len(raw):
            break
        assert header < len(raw) and raw[header:header + 4] == b"dex\n", ("invalid DEX header", header)


def check(label, artifacts):
    dex.TARGETS = set(METHODS) | set(FIELDS)
    classes, definitions = {}, {}
    for relative, expected_hash in artifacts:
        path = ROOT / relative
        raw = path.read_bytes()
        assert hashlib.sha256(raw).hexdigest() == expected_hash, relative
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if not name.startswith("classes") or not name.endswith(".dex") or "/" in name:
                    continue
                for data in dex_views(archive.read(name)):
                    for owner, cls in data.classes.items():
                        assert owner not in classes, ("duplicate definition", owner, relative, name)
                        classes[owner] = cls
                        definitions[owner] = data
    for owner, methods in METHODS.items():
        assert owner in classes, (label, owner)
        for name, descriptor in methods:
            matches = [m for m in classes[owner]["methods"] if m["name"] == name and m["descriptor"] == descriptor]
            assert len(matches) == 1 and matches[0]["access_flags"] & 1, (label, owner, name, descriptor)
    for owner, fields in FIELDS.items():
        assert owner in classes, (label, owner)
        for name, field_type in fields:
            assert any(f["name"] == name and f["type"] == field_type for f in classes[owner]["fields"]), (label, owner, name, field_type)
    # These are the two direct getter callers deoptimized by NavbarHost, including initial addView.
    for caller, descriptor, update in [("onInit", "()V", "addView"), ("repositionNavigationBar", "(I)V", "updateViewLayout")]:
        instructions = definitions[NAV].instructions(NAV, caller, descriptor)
        calls = [ins["method"] for ins in instructions if "method" in ins]
        assert sum(m["owner"] == NAV and m["name"] == "getBarLayoutParamsForRotation"
                   and m["descriptor"] == "(I)" + PARAMS for m in calls) == 2, (label, caller)
        assert any(m["owner"] == "Landroid/view/WindowManager;" and m["name"] == update
                   and m["descriptor"] == "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V" for m in calls), (label, caller)
    assert classes[NAV_TRANSITIONS]["superclass"] == TRANSITIONS, label
    background = definitions[BACKGROUND].instructions(BACKGROUND, "draw", "(Landroid/graphics/Canvas;)V")
    read_fields = {ins["field"]["name"] for ins in background if "field" in ins and ins["opcode"] in ("52", "54", "55")}
    assert {"mMode", "mColor", "mGradientAlpha", "mAnimating"} <= read_fields, label
    draw_calls = {(ins["method"]["owner"], ins["method"]["name"], ins["method"]["descriptor"])
                  for ins in background if "method" in ins}
    assert {
        ("Landroid/graphics/Canvas;", "drawRect", "(Landroid/graphics/Rect;Landroid/graphics/Paint;)V"),
        ("Landroid/graphics/Canvas;", "drawPaint", "(Landroid/graphics/Paint;)V"),
        ("Landroid/graphics/drawable/Drawable;", "draw", "(Landroid/graphics/Canvas;)V"),
        ("Landroid/graphics/drawable/Drawable;", "invalidateSelf", "()V"),
    } <= draw_calls, label
    print(f"PASS: {label}: archive hashes, exact navigation/Insets/background members, native refresh and background draw/animation calls")


def check_taskbar():
    base = "Lcom/android/launcher3/taskbar/"
    controller, owner = base + "TaskbarInsetsController;", base + "TaskbarActivityContext;"
    layer, renderer = base + "TaskbarDragLayer;", base + "TaskbarBackgroundRenderer;"
    controllers, buttons = base + "TaskbarControllers;", base + "NavbarButtonsViewController;"
    transitions = base + "TaskbarTransitions;"
    methods = {
        controller: [("setProviderInsets", "(" + PROVIDER + "II)V"),
                     ("onTaskbarOrBubblebarWindowHeightOrInsetsChanged", "()V")],
        owner: [("isGestureNav", "()Z"), ("notifyUpdateLayoutParams", "()V")],
        layer: [("dispatchDraw", "(Landroid/graphics/Canvas;)V")],
        renderer: [("draw", "(Landroid/graphics/Canvas;)V")],
        BACKGROUND: [("draw", "(Landroid/graphics/Canvas;)V")],
    }
    fields = {
        controller: [("context", owner)],
        owner: [("mIsDestroyed", "Z"), ("mDragLayer", layer), ("mControllers", controllers),
                ("mWindowManager", "Landroid/view/WindowManager;"), ("mWindowLayoutParams", PARAMS),
                ("mLastUpdatedLayoutParams", PARAMS)],
        layer: [("mBackgroundRenderer", renderer)],
        controllers: [("navbarButtonsViewController", buttons)],
        buttons: [("mTaskbarTransitions", transitions)],
        TRANSITIONS: [("mBarBackground", BACKGROUND)],
    }
    dex.TARGETS = set(methods) | set(fields) | {transitions}
    path = ROOT / "verification/navbar-system/NexusLauncher.apk"
    assert hashlib.sha256(path.read_bytes()).hexdigest() == "3d7c14c02a7a42c9b88b0d5ced4662330ce6e6989563dab5abe9a8158cd70b56"
    classes, definitions = {}, {}
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if not name.startswith("classes") or not name.endswith(".dex") or "/" in name:
                continue
            for data in dex_views(archive.read(name)):
                for key, cls in data.classes.items():
                    assert key not in classes, key
                    classes[key], definitions[key] = cls, data
    for key, required in methods.items():
        for name, descriptor in required:
            assert any(m["name"] == name and m["descriptor"] == descriptor for m in classes[key]["methods"]), (key, name, descriptor)
    for key, required in fields.items():
        for name, kind in required:
            assert any(f["name"] == name and f["type"] == kind for f in classes[key]["fields"]), (key, name, kind)
    assert classes[transitions]["superclass"] == TRANSITIONS
    refresh = definitions[controller].instructions(controller, "onTaskbarOrBubblebarWindowHeightOrInsetsChanged", "()V")
    calls = [ins["method"] for ins in refresh if "method" in ins]
    assert sum(m["owner"] == controller and m["name"] == "setProviderInsets" and m["descriptor"] == "(" + PROVIDER + "II)V" for m in calls) == 2
    assert any(ins.get("field", {}).get("name") == "paramsForRotation" for ins in refresh)
    assert any(m["owner"] == owner and m["name"] == "notifyUpdateLayoutParams" for m in calls)
    notify = definitions[owner].instructions(owner, "notifyUpdateLayoutParams", "()V")
    assert any(ins.get("method", {}).get("name") == "copyFrom" for ins in notify)
    assert any(ins.get("method", {}).get("name") == "updateViewLayout" for ins in notify)
    draw = definitions[layer].instructions(layer, "dispatchDraw", "(Landroid/graphics/Canvas;)V")
    assert any(ins.get("method", {}).get("owner") == renderer and ins["method"]["name"] == "draw" for ins in draw)
    print("PASS: live-hash Pixel launcher: exact 3-argument provider ABI, native main/rotation refresh, shallow notify guard and owned backgrounds")
    print("UNSUPPORTED: QPR2 launcher uses a different setter ABI; only its SystemUI path is verified here")


def main():
    for label, artifacts in ARCHIVES.items():
        check(label, artifacts)
    check_taskbar()
    print("UNVERIFIED: live gesture bar, IME, rotation, transitions and disable restoration; archive ABI evidence is not device behavior")


if __name__ == "__main__":
    main()
