"""Check archived stacked rendering assumptions and run the real Java copy adapter.

No device access. Archive evidence is not a claim that the live status bar uses this path.
"""
import os
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
archive.TARGETS = [DUAL, PARSER, DRAW, RESTART, PAINT, STATE, archive.CELLULAR, "Lkotlin/Pair;"]
classes, dexes = archive.archived()


def method(owner, name, signature):
    assert any(m["name"] == name and m["descriptor"] == signature for m in classes[owner]["methods"]), (owner, name)
    dex = next(d for n, d in dexes if n == classes[owner]["dex"])
    return dex.instructions(owner, name, signature)


for name, typ in [("primarySubId", "I"), ("secondarySubId", "I"), ("primary", archive.CELLULAR), ("secondary", archive.CELLULAR)]:
    assert any(f["name"] == name and f["type"] == typ for f in classes[DUAL]["fields"])
method(archive.CELLULAR, "<init>", "(IIZZ)V")
method(STATE, "<init>", "(I)V")
method(STATE, "getIntValue", "()I")
method(STATE, "setIntValue", "(I)V")
render_name = "StackedMobileIcon-sW7UJKQ"
sig = "(" + DUAL + "JLjava/lang/String;Landroidx/compose/ui/Modifier;Landroidx/compose/runtime/Composer;I)V"
render = method(DRAW, render_name, sig)
restart = method(RESTART, "invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
assert any(i.get("method", {}).get("owner") == DRAW and i["method"]["name"] == render_name for i in restart)
assert any(i.get("method", {}).get("owner") == "Landroidx/compose/foundation/CanvasKt;" for i in render)
paint = method(PAINT, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;")
assert sum(i.get("field", {}).get("name") == "level" for i in paint) == 2
assert sum(i.get("method", {}).get("name") == "drawMobileIconBar-n3YQ8UE$default" for i in paint) == 2
assert not any(i.get("method", {}).get("name") == "setLevel" for i in paint)
parse = method(PARSER, "tryParseDualSim", "(Ljava/util/List;)" + DUAL)
assert {i.get("field", {}).get("name") for i in parse} >= {"primary", "secondary", "primarySubId", "secondarySubId"}
print("PASS: hashed archive, stacked signatures, explicit subscriptions, Canvas rendering and restart caller")

jdk = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr")) / "bin"
out = ROOT / "verification/stacked-signal-checks"
out.mkdir(parents=True, exist_ok=True)
source = ROOT / "app/src/main/java/dev/local/sunwindowdemo/features/shade"
subprocess.run([str(jdk / "javac.exe"), "--release", "17", "-encoding", "UTF-8", "-d", str(out),
                str(source / "StackedSignalDisplay.java"), str(source / "SignalDisplayPolicy.java"),
                str(ROOT / "tests/signal/StackedSignalDisplayChecks.java")], check=True)
subprocess.run([str(jdk / "java.exe"), "-cp", str(out), "dev.local.sunwindowdemo.features.shade.StackedSignalDisplayChecks"], check=True)
print("UNVERIFIED: live stacked path selection, Hook execution and Compose invalidation; device acceptance required")
