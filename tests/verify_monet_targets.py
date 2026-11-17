"""Read-only check of the Monet/floating hook targets against the archived launcher and services."""
import hashlib
import zipfile
from pathlib import Path
import verify_signal_target as dex
from verify_navbar_target import dex_views

ROOT = Path(__file__).resolve().parents[1]
L = "Lcom/android/launcher3/"
ITEM = L + "model/data/ItemInfoWithIcon;"
FLAG = L + "icons/cache/CacheLookupFlag;"
launcher = {
    L + "icons/IconCache;": [
        ("updateIconInBackground", "(Ljava/util/concurrent/Executor;" + L + "icons/IconCache$ItemInfoUpdateReceiver;" + ITEM + FLAG + ")" + L + "util/CancellableTask;"),
        ("getTitleAndIcon", "(" + ITEM + FLAG + "Ljava/util/function/Supplier;)V")],
    L + "model/data/IconRequestInfo;": [("<init>", "(" + ITEM + "Landroid/content/pm/LauncherActivityInfo;[BZ" + FLAG + ")V")],
    FLAG: [("updateMask", "(IZ)" + FLAG)],
    L + "icons/BitmapInfo;": [("getMatchingLookupFlag", "()" + FLAG)],
    L + "graphics/ThemeManager;": [("verifyIconState", "()V")],
    L + "util/DaggerSingletonObject;": [("get", "(Landroid/content/Context;)Ljava/lang/Object;")],
}
services = {
    "Lcom/android/server/wm/InsetsPolicy;": [("adjustInsetsForWindow", "(Lcom/android/server/wm/WindowState;Landroid/view/InsetsState;Z)Landroid/view/InsetsState;")],
}


def load(path, targets, digest=None):
    if digest: assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, path
    dex.TARGETS = list(targets)
    classes = {}
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.startswith("classes") and n.endswith(".dex"):
                for view in dex_views(z.read(n)): classes.update(view.classes)
    return classes


def check(classes, table, label):
    for owner, methods in table.items():
        assert owner in classes, (label, owner)
        for name, desc in methods:
            assert any(m["name"] == name and m["descriptor"] == desc for m in classes[owner]["methods"]), (label, owner, name, desc)


apk = ROOT / "verification/pixel-qpr2-20261113/apk/NexusLauncherRelease.apk"
check(load(apk, launcher, "109196f424d10102896b2b6a96ac284ec42012db12fdf923632d4a9a7c58159c"), launcher, "launcher")
check(load(ROOT / "verification/navbar-system/services.jar", services,
           "3ace355c3bfc525cf0145088dc6bdcad37b0b09c1a163ad09a2276aa653f2de9"), services, "services")
check(load(ROOT / "verification/navbar-system/NexusLauncher.apk", launcher,
           "3d7c14c02a7a42c9b88b0d5ced4662330ce6e6989563dab5abe9a8158cd70b56"), launcher, "PixelOS launcher")
check(load(ROOT / "verification/pixel-qpr2-20261113/services.jar", services,
           "382ed5d6b0e9ca464878ac6c87b655ab7f6009fd8463949aa43c23b738dbb7db"), services, "PixelUI services")
print("PASS both archives: drawer themed lookup, theme state and floating insets targets")
