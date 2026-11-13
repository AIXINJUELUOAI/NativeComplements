"""Read actual host DEX definitions, including each logical DEX in a 041 container."""
from pathlib import Path
import hashlib
import json
import re
import zipfile
import verify_signal_target as dex

ROOT = Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / "tests/target/test4-members.json"
WM = "Lcom/android/server/wm/"
EDGE = "Lcom/android/systemui/navigationbar/gestural/"
CLICK = "Lcom/android/systemui/statusbar/phone/StatusBarNotificationActivityStarter$$ExternalSyntheticLambda"
SHADE = "Lcom/android/systemui/shade/data/repository/ShadeConfigRepository"
ICON = "Lcom/android/launcher3/views/FloatingIconView"
BAR = "Lcom/android/systemui/shared/statusbar/phone/BarTransitions"
INSETS = "Landroid/view/ViewTreeObserver"
TARGETS = {
    WM+"ActivityStarter;", WM+"Task;", EDGE+"DisplayBackGestureHandlerImpl;",
    EDGE+"EdgeBackGestureHandler$$ExternalSyntheticLambda20;",
    EDGE+"EdgeBackGestureHandler$$ExternalSyntheticLambda24;",
    *[CLICK+str(i)+";" for i in (6, 7, 10, 12)], "Lkotlin/Pair;",
    SHADE+";", SHADE+"$onActivated$2$1$1;", SHADE+"$special$$inlined$map$1$2;",
    ICON+";", ICON+"$IconLoadResult;", "Lcom/android/server/input/InputManagerService;",
    "Lcom/android/server/input/NativeInputManagerService;", "Landroid/view/InputEvent;",
    "Landroid/view/MotionEvent;",
    BAR+";", BAR+"$BarBackgroundDrawable;", "Lcom/android/launcher3/taskbar/TaskbarTransitions;",
    INSETS+";", INSETS+"$InternalInsetsInfo;", INSETS+"$OnComputeInternalInsetsListener;",
}


def read_archive(path):
    result = {}
    dex.TARGETS = TARGETS
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            raw = archive.read(name)
            header = 0
            while header < len(raw):
                view = bytearray(raw)
                if header:
                    view[:120] = raw[header:header+120]
                result.update(dex.Dex(view).classes)
                if raw[header+4:header+7] != b"041":
                    break
                size = int.from_bytes(raw[header+32:header+36], "little")
                assert size >= 120
                header += size
                assert header == len(raw) or raw[header:header+4] == b"dex\n"
    return result


def verify(classes, qpr2):
    def method(owner, name, descriptor=None):
        rows = [m for m in classes[owner]["methods"] if m["name"] == name]
        if descriptor is not None:
            assert any(m["descriptor"] == descriptor for m in rows), (owner, name, descriptor)
        else:
            assert len(rows) == 1, (owner, name, rows)
            return rows[0]["descriptor"]

    def field(owner, name, kind):
        assert any(f["name"] == name and f["type"] == kind for f in classes[owner]["fields"]), (owner, name, kind)

    result = method(WM+"ActivityStarter;", "handleStartResult")
    assert result.endswith(("ILcom/android/server/wm/ActivityCallerState$CallerInfo;)Lcom/android/server/wm/Task;"
                            if qpr2 else "I)Lcom/android/server/wm/Task;"))
    launch = method(WM+"ActivityStarter;", "startActivityUnchecked")
    parameters=re.findall(r"\[*L[^;]+;|\[*[ZBCSIJFD]",launch[1:launch.index(")")])
    assert len(parameters)==(16 if qpr2 else 14)
    task=7 if qpr2 else 6
    assert parameters[5]=="Landroid/app/ActivityOptions;"
    assert parameters[task:task+2]==[WM+"Task;",WM+"TaskFragment;"]
    assert parameters[task+4]=="I"
    assert (WM+"TaskDisplayArea;" in launch) == qpr2
    assert (WM+"ActivityCallerState$CallerInfo;" in launch) == qpr2
    method(WM+"Task;", "isResizeable", "()Z" if qpr2 else "(Z)Z")
    adapter = EDGE+"EdgeBackGestureHandler$$ExternalSyntheticLambda"+("24;" if qpr2 else "20;")
    field(EDGE+"DisplayBackGestureHandlerImpl;", "onInputEvent", adapter if qpr2 else "Lkotlin/jvm/functions/Function1;")
    field(adapter, "f$0", EDGE+"EdgeBackGestureHandler;")
    method(adapter, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;")
    runner, sender = (CLICK+"6;", CLICK+"12;") if qpr2 else (CLICK+"7;", CLICK+"10;")
    entry = "Lcom/android/systemui/statusbar/notification/collection/NotificationEntry;"
    field(runner, "f$1", entry)
    field(runner, "f$3", "Landroid/app/PendingIntent;")
    field(runner, "f$5", "Z")
    method(runner, "run", "()V")
    for name, kind in [("f$2", "I"), ("f$3", "Landroid/app/PendingIntent;"), ("f$4", "Landroid/content/Intent;"), ("f$5", entry)]:
        field(sender, name, kind)
    method(sender, "startPendingIntent", "(Landroid/view/RemoteAnimationAdapter;)I")
    field("Lkotlin/Pair;", "second", "Ljava/lang/Object;")
    emitter = SHADE+("$onActivated$2$1$1;" if qpr2 else "$special$$inlined$map$1$2;")
    method(emitter, "emit", "(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;")
    field(SHADE+";", "legacyUseSplitShade$delegate" if qpr2 else "legacyUseSplitShade",
          "Landroidx/compose/runtime/MutableState;" if qpr2 else "Lkotlinx/coroutines/flow/Flow;")
    field(ICON+"$IconLoadResult;", "isThemed", "Z")
    method(ICON+";", "setIcon", "(Landroid/graphics/drawable/Drawable;Landroid/graphics/drawable/Drawable;Ljava/util/function/Supplier;IZ)V")
    manager = "Lcom/android/server/input/InputManagerService;"
    method(manager, "start", "()V")
    method(manager, "filterInputEvent", "(Landroid/view/InputEvent;I)Z")
    method(manager, "setInputFilter", "(Landroid/view/IInputFilter;)V")
    field(manager, "mNative", "Lcom/android/server/input/NativeInputManagerService;")
    field(manager, "mInputFilterLock", "Ljava/lang/Object;")
    field(manager, "mInputFilter", "Landroid/view/IInputFilter;")
    method("Lcom/android/server/input/NativeInputManagerService;", "setInputFilterEnabled", "(Z)V")
    method("Lcom/android/server/input/NativeInputManagerService;", "injectInputEvent", "(Landroid/view/InputEvent;ZIIII)I")
    method("Landroid/view/InputEvent;", "getDisplayId", "()I")
    method("Landroid/view/MotionEvent;", "split", "(I)Landroid/view/MotionEvent;")
    method(BAR+";", "<init>", "(Landroid/view/View;I)V")
    field(BAR+";", "mBarBackground", BAR+"$BarBackgroundDrawable;")
    method(BAR+"$BarBackgroundDrawable;", "draw", "(Landroid/graphics/Canvas;)V")
    assert "Lcom/android/launcher3/taskbar/TaskbarTransitions;" in classes
    method(INSETS+";", "addOnComputeInternalInsetsListener", "("+INSETS+"$OnComputeInternalInsetsListener;)V")
    method(INSETS+"$OnComputeInternalInsetsListener;", "onComputeInternalInsets", "("+INSETS+"$InternalInsetsInfo;)V")
    method(INSETS+"$InternalInsetsInfo;", "setTouchableInsets", "(I)V")
    field(INSETS+"$InternalInsetsInfo;", "touchableRegion", "Landroid/graphics/Region;")
    field(INSETS+"$InternalInsetsInfo;", "TOUCHABLE_INSETS_REGION", "I")


if __name__ == "__main__":
    import sys
    if "--refresh" in sys.argv:
        data = {}
        for label, folder, names in [
            ("current", "verification/navbar-system", ["framework.jar", "services.jar", "NexusLauncher.apk"]),
            ("qpr2", "verification/pixel-qpr2-20261113", ["framework.jar", "services.jar", "apk/NexusLauncherRelease.apk"]),
        ]:
            files = [ROOT/folder/name for name in names]
            files.append(ROOT/("verification/pixel-qpr2-20261113/apk/SystemUIGoogle.apk" if label == "qpr2"
                              else "verification/split-shade-device/SystemUI.apk"))
            classes, hashes = {}, {}
            for path in files:
                hashes[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
                for owner, value in read_archive(path).items():
                    if owner.startswith("Lcom/android/launcher3/") and "SystemUI" in path.name:
                        continue
                    if owner.startswith(BAR) and "SystemUI" in path.name:
                        continue
                    classes[owner] = value
            verify(classes, label == "qpr2")
            method_names={"handleStartResult","startActivityUnchecked","isResizeable","invoke","run","startPendingIntent",
                          "emit","setIcon","start","filterInputEvent","setInputFilter","setInputFilterEnabled",
                          "injectInputEvent","getDisplayId","split","draw","addOnComputeInternalInsetsListener",
                          "onComputeInternalInsets","setTouchableInsets"}
            field_names={"onInputEvent","f$0","f$1","f$2","f$3","f$4","f$5","second","legacyUseSplitShade",
                         "legacyUseSplitShade$delegate","isThemed","mNative","mInputFilterLock","mInputFilter",
                         "mBarBackground","touchableRegion","TOUCHABLE_INSETS_REGION"}
            for owner, value in classes.items():
                value["methods"]=[m for m in value["methods"] if m["name"] in method_names
                                  or owner == BAR+";" and m["name"] == "<init>"]
                value["fields"]=[f for f in value["fields"] if f["name"] in field_names]
            data[label] = {"sha256": hashes, "classes": classes}
        SNAPSHOT.write_text(json.dumps(data, ensure_ascii=True, indent=2)+"\n", encoding="utf-8")
    data = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    for label, host in data.items():
        verify(host["classes"], label == "qpr2")
        print("PASS", label, "exact input, launcher, taskbar transitions and combined-controls target signatures")
