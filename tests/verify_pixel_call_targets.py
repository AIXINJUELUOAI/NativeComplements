"""Verify actual Telecom definitions; missing external ROM artifacts are not a pass."""
import argparse
import hashlib
from pathlib import Path
import zipfile
import verify_signal_target as dex
from verify_navbar_target import dex_views

ROOT = Path(__file__).resolve().parents[1]
P = "Lcom/android/server/telecom/"
CALL, MANAGER, REGISTRY = P + "Call;", P + "CallsManager;", P + "PhoneAccountRegistrar;"
RESULT = P + "callfiltering/CallFilteringResult;"
BUILDER = P + "callfiltering/CallFilteringResult$Builder;"
PAH = "Landroid/telecom/PhoneAccountHandle;"
METHODS = {
    MANAGER: {"getContext": "()Landroid/content/Context;", "getPhoneAccountRegistrar": "()" + REGISTRY,
              "onCallFilteringComplete": "(" + CALL + RESULT + "Z)V"},
    CALL: {**{name: "()Z" for name in ("isIncoming", "isSelfManaged", "isEmergencyCall", "isInECBM")},
           "hasProperty": "(I)Z", "getTargetPhoneAccount": "()" + PAH,
           "getHandlePresentation": "()I", "getHandle": "()Landroid/net/Uri;", "getState": "()I",
           "getAssociatedUser": "()Landroid/os/UserHandle;"},
    REGISTRY: {"getPhoneAccountUnchecked": "(" + PAH + ")Landroid/telecom/PhoneAccount;",
               "getSubscriptionIdForPhoneAccount": "(" + PAH + ")I"},
    BUILDER: {"from": "(" + RESULT + ")" + BUILDER, "build": "()" + RESULT,
              **{name: "(Z)" + BUILDER for name in ("setShouldAllowCall", "setShouldReject", "setShouldAddToCallLog",
                  "setShouldShowNotification", "setShouldScreenViaAudio", "setShouldSilence")},
              "setCallBlockReason": "(I)" + BUILDER,
              "setCallScreeningAppName": "(Ljava/lang/CharSequence;)" + BUILDER,
              "setCallScreeningComponentName": "(Ljava/lang/String;)" + BUILDER,
              "setCallScreeningResponse": "(Landroid/telecom/ParcelableCallResponse;Z)" + BUILDER},
    "Landroid/telephony/TelephonyManager;": {"isInEmergencySmsMode": "()Z"},
}


def check(folder, framework):
    artifacts = [folder / "service-telecom.jar", framework, folder / "framework-telephony.jar"]
    classes, definitions = {}, {}
    dex.TARGETS = set(METHODS) | {RESULT}
    expected = {
        "service-telecom.jar": "d60afbb870a2bda729df3f2227932d73a8d71a6c12f1d81995184a726d02d91b",
        "framework.jar": "89faef564a6b48459c5ffa39ff73523801481524b669493b5b054709cc0e734f",
        "framework-telephony.jar": "a2ee124b4d0eaae7af0c68b0fd1d37633bb2ef8175f7df0cf791e19c8b8ef662",
    } if folder.name == "dada" else {}
    for path in artifacts:
        if not path.is_file():
            raise FileNotFoundError(f"UNVERIFIED: required host archive missing: {path}")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if path.name in expected:
            assert digest == expected[path.name], ("archive changed", path)
        print("SHA256", path.name, digest)
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if not name.startswith("classes") or not name.endswith(".dex") or "/" in name:
                    continue
                for data in dex_views(archive.read(name)):
                    for owner, cls in data.classes.items():
                        assert owner not in classes, ("duplicate host class", owner, path)
                        classes[owner], definitions[owner] = cls, data
    for owner, methods in METHODS.items():
        assert owner in classes, ("missing class", owner)
        for name, descriptor in methods.items():
            matches = [m for m in classes[owner]["methods"] if m["name"] == name and m["descriptor"] == descriptor]
            assert len(matches) == 1, (owner, name, descriptor)
            assert matches[0]["access_flags"] & 1, ("not public", owner, name)
            if owner == BUILDER and name == "from":
                assert matches[0]["access_flags"] & 8, "Builder.from must remain static"
    assert any(f["name"] == "shouldAllowCall" and f["type"] == "Z" for f in classes[RESULT]["fields"])
    assert any(m["name"] == "<init>" for m in classes[MANAGER]["methods"])
    data = definitions[MANAGER]
    instructions = data.instructions(MANAGER, "onCallFilteringComplete", METHODS[MANAGER]["onCallFilteringComplete"])
    calls = [ins["method"] for ins in instructions if "method" in ins]
    assert any(m["owner"] == CALL and m["name"] == "reject" for m in calls), "Native rejection path missing"
    print("PASS: Telecom constructor, filter, native rejection and all adapter members; device behavior unverified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host", choices=("dada", "pixelui"))
    args = parser.parse_args()
    folder = ROOT / "verification/pixel-host-audit" / args.host
    framework = ROOT / ("verification/navbar-system/framework.jar" if args.host == "dada"
                        else "verification/pixel-qpr2-20261113/framework.jar")
    check(folder, framework)
