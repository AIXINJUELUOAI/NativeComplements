"""Read-only archived SMS ABI/call evidence, not an installed-device behaviour test."""
import argparse
import hashlib
from pathlib import Path
import zipfile
import verify_signal_target as dex
from verify_navbar_target import dex_views

ROOT = Path(__file__).resolve().parents[1]
BASE = "Lcom/android/internal/telephony/"
HANDLER, TRACKER, CHECKER = [BASE + n + ";" for n in ("InboundSmsHandler", "InboundSmsTracker", "BlockChecker")]
CTX, BUNDLE = "Landroid/content/Context;", "Landroid/os/Bundle;"
STATUS = "(" + CTX + "Ljava/lang/String;" + BUNDLE + ")"
PART = "(" + TRACKER + ")Z"
DELETE = "(Ljava/lang/String;[Ljava/lang/String;I)V"
RESOLVER = "Landroid/content/ContentResolver;"
RESOLVER_DELETE = "(Landroid/net/Uri;Ljava/lang/String;[Ljava/lang/String;)I"


def check(host):
    path = ROOT / "verification/pixel-host-audit" / host / "telephony-common.jar"
    if not path.is_file():
        print(f"SKIP: {host}: missing telephony-common.jar; no SMS target evidence")
        return False
    expected = {"dada": "2e2f047c646e9eb74f9828e00abf668daebb0265a326ca8e362839d774018ff7",
                "pixelui": "bd752b8c16d0e2063ee1a9c894934a4eb9eb1257b1699c253a49d151d878302b"}
    assert host in expected, (host, "archive hash must be reviewed and pinned before validation")
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected[host], (host, "archive hash changed")
    dex.TARGETS = {HANDLER, TRACKER, CHECKER, HANDLER[:-1] + "$DeliveringState;"}
    classes, owners = {}, {}
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if not name.endswith(".dex"):
                continue
            for view in dex_views(archive.read(name)):
                for owner, cls in view.classes.items():
                    assert owner not in classes, ("duplicate class", owner)
                    classes[owner], owners[owner] = cls, view
    def member(owner, name, descriptor):
        assert any(m["name"] == name and m["descriptor"] == descriptor for m in classes[owner]["methods"]), (host, owner, name, descriptor)
    def calls(owner, name, descriptor):
        return [i for i in owners[owner].instructions(owner, name, descriptor) if "method" in i]
    def target(i, owner, name, descriptor):
        return i["method"] == {"owner": owner, "name": name, "descriptor": descriptor}
    for name, descriptor in [("processMessagePart", PART), ("deleteFromRawTable", DELETE)]:
        member(HANDLER, name, descriptor)
    for name, descriptor in [("getBlockStatus", STATUS + "I"), ("isBlocked", STATUS + "Z")]:
        member(CHECKER, name, descriptor)
    for name, descriptor in [("getDisplayAddress", "()Ljava/lang/String;"), ("getPdu", "()[B"),
                             ("getFormat", "()Ljava/lang/String;"), ("getSubId", "()I"),
                             ("getMessageCount", "()I"), ("getDeleteWhere", "()Ljava/lang/String;"),
                             ("getDeleteWhereArgs", "()[Ljava/lang/String;")]:
        member(TRACKER, name, descriptor)
    for name, kind in [("mContext", CTX), ("mResolver", RESOLVER), ("sRawUriPermanentDelete", "Landroid/net/Uri;")]:
        assert any(f["name"] == name and f["type"] == kind for f in classes[HANDLER]["fields"]), (host, name)
    delivering = HANDLER[:-1] + "$DeliveringState;"
    member(delivering, "processMessage", "(Landroid/os/Message;)Z")
    state_calls = calls(delivering, "processMessage", "(Landroid/os/Message;)Z")
    direct = any(target(i, HANDLER, "processMessagePart", PART) for i in state_calls)
    bridge = "-$$Nest$mprocessMessagePart"
    bridge_descriptor = "(" + HANDLER + TRACKER + ")Z"
    if not direct:
        assert any(target(i, HANDLER, bridge, bridge_descriptor) for i in state_calls)
        assert any(target(i, HANDLER, "processMessagePart", PART) for i in calls(HANDLER, bridge, bridge_descriptor))
    filter_descriptor = "([[BI" + TRACKER + HANDLER[:-1] + "$SmsBroadcastReceiver;ZZ)Z"
    member(HANDLER, "filterSms", filter_descriptor)
    part = calls(HANDLER, "processMessagePart", PART)
    filter_calls = [i for i in part if target(i, HANDLER, "filterSms", filter_descriptor)]
    assert len(filter_calls) == 1
    # The exact archived call order distinguishes the earlier WAP mark-delete from the later
    # normal SMS branch. Runtime additionally observes false return + unlocked + block arguments.
    deletes = [i for i in part if target(i, HANDLER, "deleteFromRawTable", DELETE)]
    assert deletes[0]["offset"] < filter_calls[0]["offset"] < deletes[-1]["offset"]
    instructions = owners[HANDLER].instructions(HANDLER, "processMessagePart", PART)
    interval = [i for i in instructions if filter_calls[0]["offset"] < i["offset"] < deletes[-1]["offset"]]
    assert any(i["opcode"] == "0a" for i in interval), "filter result must be read"
    assert sum(i["opcode"] in ("38", "39") for i in interval) >= 2, "filter result and block branches must precede deletion"
    assert sum(target(i, CHECKER, "isBlocked", STATUS + "Z") for i in part) == 2
    assert sum(target(i, HANDLER, "deleteFromRawTable", DELETE) for i in part) >= 2
    assert any(target(i, CHECKER, "getBlockStatus", STATUS + "I") for i in calls(CHECKER, "isBlocked", STATUS + "Z"))
    assert any(target(i, RESOLVER, "delete", RESOLVER_DELETE) for i in calls(HANDLER, "deleteFromRawTable", DELETE))
    print(f"PASS: {host}: SMS exact ABI and native caller edges; sha256={hashlib.sha256(path.read_bytes()).hexdigest()}")
    print("NOTE: call evidence does not prove deletion effects; runtime records require exact permanent URI, message args and rows > 0")
    return True


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", choices=["dada", "pixelui"], required=True)
    raise SystemExit(0 if check(parser.parse_args().host) else 2)
