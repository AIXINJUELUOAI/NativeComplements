"""Check archived stacked rendering assumptions and run the real Java copy adapter.

Covers both archived SystemUI builds:
  legacy  verification/split-shade-device/SystemUI.apk (project user's phone)
  qpr2    verification/pixel-qpr2-20261113/apk/SystemUIGoogle.apk (Pixel 10 Pro, CP4A.261105.040.A1)
The member selection below mirrors StackedSignalTargets (exact parameter types, not hash suffixes).
No device access. Archive evidence is not a claim that the live status bar uses this path.
"""
import hashlib
import os
import re
import zipfile
from pathlib import Path
import subprocess
import verify_signal_target as archive

if not __debug__:
    raise RuntimeError("Run without -O: archive checks require assertions")
ROOT = Path(__file__).resolve().parents[1]
BASE = "Lcom/android/systemui/statusbar/pipeline/"
DUAL = BASE + "mobile/ui/model/DualSim;"
PARSER = BASE + "mobile/ui/model/DualSimKt;"
DRAW = BASE + "shared/ui/composable/StackedMobileIconKt;"
RESTART = BASE + "shared/ui/composable/StackedMobileIconKt$$ExternalSyntheticLambda6;"
PAINT = BASE + "shared/ui/composable/StackedMobileIconKt$$ExternalSyntheticLambda5;"
STATE = "Landroidx/compose/runtime/ParcelableSnapshotMutableIntState;"
MODIFIER = "Landroidx/compose/ui/Modifier;"
COMPOSER = "Landroidx/compose/runtime/Composer;"
QPR2_APK = ROOT / "verification/pixel-qpr2-20261113/apk/SystemUIGoogle.apk"
QPR2_SHA256 = "458b481b241552d67d33963f3cb449a9e5e53d4cfcd28e8bb1d919b814fd982f"
TARGETS = [DUAL, PARSER, DRAW, RESTART, PAINT, STATE, archive.CELLULAR, "Lkotlin/Pair;"]
RENDER_SHAPES = {
    "(" + DUAL + "JLjava/lang/String;" + MODIFIER + COMPOSER + "I)V": "legacy",
    "(" + DUAL + "J" + MODIFIER + COMPOSER + "I)V": "qpr2",
}


def qpr2_archive():
    """Read every logical DEX (041 containers included) and keep each for instruction decoding."""
    assert hashlib.sha256(QPR2_APK.read_bytes()).hexdigest() == QPR2_SHA256, "SystemUIGoogle.apk SHA256 mismatch"
    classes, dexes = {}, []
    with zipfile.ZipFile(QPR2_APK) as apk:
        for name in sorted(apk.namelist()):
            if not re.fullmatch(r"classes\d*\.dex", name):
                continue
            raw = apk.read(name)
            header = 0
            while header < len(raw):
                view = bytearray(raw)
                if header:
                    view[:120] = raw[header:header+120]
                dex = archive.Dex(view)
                label = f"{name}@{header}"
                dexes.append((label, dex))
                for owner, cls in dex.classes.items():
                    classes.setdefault(owner, {"dex": label, **cls})
                if raw[header+4:header+7] != b"041":
                    break
                size = int.from_bytes(raw[header+32:header+36], "little")
                assert size >= 120
                header += size
                assert header == len(raw) or raw[header:header+4] == b"dex\n"
    assert set(classes) == set(TARGETS), ("missing classes", set(TARGETS) - set(classes))
    return classes, dexes


def verify(label, classes, dexes):
    def method(owner, name, signature):
        assert any(m["name"] == name and m["descriptor"] == signature for m in classes[owner]["methods"]), (label, owner, name, signature)
        dex = next(d for n, d in dexes if n == classes[owner]["dex"])
        return dex.instructions(owner, name, signature)

    # QPR2 widened the slots to the CellularTypeIconModel supertype (Cellular or SatelliteV2).
    slot = archive.CELLULAR if label == "legacy" else archive.CELLULAR.replace("$Cellular;", ";")
    for name, typ in [("primarySubId", "I"), ("secondarySubId", "I"), ("primary", slot), ("secondary", slot)]:
        assert any(f["name"] == name and f["type"] == typ for f in classes[DUAL]["fields"]), (label, DUAL, name, typ)
    for name, typ in [("level", "I"), ("numberOfLevels", "I"), ("carrierNetworkChange", "Z"), ("showExclamationMark", "Z")]:
        assert any(f["name"] == name and f["type"] == typ for f in classes[archive.CELLULAR]["fields"]), (label, name, typ)
    assert any(m["name"] == "<init>" and m["descriptor"] == "(IIZZ)V" for m in classes[archive.CELLULAR]["methods"]), label
    assert any(m["name"] == "<init>" and m["descriptor"] == "(Ljava/lang/Object;Ljava/lang/Object;)V" for m in classes["Lkotlin/Pair;"]["methods"]), label
    for name, sig in [("<init>", "(I)V"), ("getIntValue", "()I"), ("setIntValue", "(I)V")]:
        assert any(m["name"] == name and m["descriptor"] == sig for m in classes[STATE]["methods"]), (label, STATE, name)

    # Mirror StackedSignalTargets.render: static void StackedMobileIcon[-*] with one exact shape.
    renders = [m for m in classes[DRAW]["methods"]
               if (m["name"] == "StackedMobileIcon" or m["name"].startswith("StackedMobileIcon-"))
               and m["descriptor"] in RENDER_SHAPES and m["access_flags"] & 0x8]
    assert len(renders) == 1, (label, "render candidates", renders)
    render = renders[0]
    assert RENDER_SHAPES[render["descriptor"]] == label, (label, "unexpected render shape", render)
    body = method(DRAW, render["name"], render["descriptor"])
    assert any(i.get("method", {}).get("owner") == "Landroidx/compose/foundation/CanvasKt;" for i in body), (label, "Canvas")

    # Mirror StackedSignalTargets.parent: StackedMobileIcon(StackedMobileIconViewModel*, Modifier, Composer, int).
    parent_re = re.compile(r"\((L[^;]*/StackedMobileIconViewModel(?:Impl)?;)" + re.escape(MODIFIER + COMPOSER) + r"I\)V")
    parents = [m for m in classes[DRAW]["methods"] if m["name"] == "StackedMobileIcon" and parent_re.fullmatch(m["descriptor"])]
    assert len(parents) == 1, (label, "parent candidates", parents)
    vm = parent_re.fullmatch(parents[0]["descriptor"])[1]
    assert vm.endswith("Impl;") == (label == "qpr2"), (label, vm)

    # Mirror StackedSignalTargets.restart: Lambda6 captures DualSim, invoke(Object,Object) calls the render.
    assert any(f["type"] == DUAL for f in classes[RESTART]["fields"]), (label, "restart DualSim field")
    restart = method(RESTART, "invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
    assert any(i.get("method", {}).get("owner") == DRAW and i["method"]["name"] == render["name"]
               and i["method"]["descriptor"] == render["descriptor"] for i in restart), (label, "restart caller")

    paint = method(PAINT, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;")
    # Legacy reads Cellular.level directly; QPR2 calls the SignalIconModel.getLevel() interface getter.
    # Both read whatever object sits in the DualSim slot, so the adapter's Cellular copy is drawn.
    level_reads = sum(i.get("field", {}).get("name") == "level" for i in paint) if label == "legacy" else \
        sum(i.get("method", {}).get("name") == "getLevel" and i["method"]["descriptor"] == "()I" for i in paint)
    assert level_reads == 2, (label, "paint level reads", level_reads)
    assert sum(i.get("method", {}).get("name") == "drawMobileIconBar-n3YQ8UE$default" for i in paint) == 2, (label, "paint bars")
    assert not any(i.get("method", {}).get("name") == "setLevel" for i in paint), label
    parsers = [m for m in classes[PARSER]["methods"] if m["name"] == "tryParseDualSim"]
    if label == "legacy":
        parse = method(PARSER, "tryParseDualSim", "(Ljava/util/List;)" + DUAL)
        assert {i.get("field", {}).get("name") for i in parse} >= {"primary", "secondary", "primarySubId", "secondarySubId"}, (label, "parser")
    else:
        # No parser and no DualSim constructor: the adapter allocates and writes the four fields.
        assert not parsers, (label, "unexpected parser", parsers)
        assert not any(m["name"] == "<init>" for m in classes[DUAL]["methods"]), (label, "DualSim constructor present")
    print(f"PASS {label}: render {render['name']}{render['descriptor']}, parent view model {vm}, "
          "explicit subscriptions, Canvas rendering and restart caller")


archive.TARGETS = TARGETS
legacy_classes, legacy_dexes = archive.archived()
verify("legacy", legacy_classes, legacy_dexes)
archive.TARGETS = TARGETS
qpr2_classes, qpr2_dexes = qpr2_archive()
verify("qpr2", qpr2_classes, qpr2_dexes)

exe = ".exe" if os.name == "nt" else ""
jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr")) / "bin"
out = ROOT / "build/stacked-signal-checks"
out.mkdir(parents=True, exist_ok=True)
source = ROOT / "app/src/main/java/dev/local/sunwindowdemo/features/shade"
subprocess.run([str(jdk / ("javac" + exe)), "--release", "17", "-encoding", "UTF-8", "-d", str(out),
                str(source / "StackedSignalDisplay.java"), str(source / "StackedSignalTargets.java"),
                str(source / "SignalDisplayPolicy.java"),
                str(ROOT / "tests/signal/StackedSignalDisplayChecks.java")], check=True)
subprocess.run([str(jdk / ("java" + exe)), "-cp", str(out), "dev.local.sunwindowdemo.features.shade.StackedSignalDisplayChecks"], check=True)
print("UNVERIFIED: live stacked path selection, Hook execution and Compose invalidation; device acceptance required")
