"""Exact archived SystemUI DEX evidence; no Gradle, device or third-party packages.

This deliberately does not verify framework SignalStrength.getPrimary: that private
framework implementation is NOT archived here and must pass runtime preflight.
"""
import hashlib
import json
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "verification/split-shade-device/SystemUI.apk"
INVENTORY = ROOT / "tests/target/signal-display-members.json"
SHA256 = "a4bf853b529f6ec7480409fa4f6ba53044a2e409f84c8f20bd320e928c883972"
PREFIX = "Lcom/android/systemui/statusbar/pipeline/mobile/"
BINDER = PREFIX + "ui/binder/MobileIconBinder$bind$1$2$1$2$2;"
VM = PREFIX + "ui/viewmodel/LocationBasedMobileViewModel;"
CELLULAR = PREFIX + "domain/model/SignalIconModel$CellularTypeIconModel$Cellular;"
DRAWABLE = "Lcom/android/settingslib/graph/SignalDrawable;"
REPO = PREFIX + "data/repository/prod/MobileConnectionRepository"
TARGETS = [BINDER, VM, CELLULAR, DRAWABLE,
           REPO + "Impl$callbackEvents$1$1$callback$1;", REPO + "Impl;",
           REPO + "KairosImpl$callbackEvents$1$2$callback$1;", REPO + "KairosImpl;",
           "Lkotlin/Pair;"]


class Dex:
    """Small read-only standard DEX reader, including real instruction boundaries."""
    def __init__(self, data):
        self.data = data
        assert data[:4] == b"dex\n" and data[7] == 0, "not standard DEX"
        assert self.u32(40) == 0x12345678, "unsupported DEX endianness"
        self.strings = []
        for i in range(self.u32(56)):
            pos = self.u32(self.u32(60) + 4 * i)
            _, pos = self.uleb(pos)
            # Target symbol names are ASCII; replacement only affects unrelated strings.
            self.strings.append(data[pos:data.index(b"\0", pos)].decode("utf-8", "replace"))
        self.types = [self.strings[self.u32(self.u32(68) + 4*i)] for i in range(self.u32(64))]
        self.protos = []
        for i in range(self.u32(72)):
            off = self.u32(76) + 12*i
            params = self.u32(off + 8)
            args = [] if not params else [self.types[self.u16(params+4+2*j)] for j in range(self.u32(params))]
            self.protos.append("(" + "".join(args) + ")" + self.types[self.u32(off+4)])
        self.fields = []
        for i in range(self.u32(80)):
            off = self.u32(84) + 8*i
            self.fields.append({"owner": self.types[self.u16(off)], "name": self.strings[self.u32(off+4)], "type": self.types[self.u16(off+2)]})
        self.methods = []
        for i in range(self.u32(88)):
            off = self.u32(92) + 8*i
            self.methods.append({"owner": self.types[self.u16(off)], "name": self.strings[self.u32(off+4)], "descriptor": self.protos[self.u16(off+2)]})
        self.classes = {}
        self.code = {}
        for i in range(self.u32(96)):
            off = self.u32(100) + 32*i
            owner = self.types[self.u32(off)]
            if owner not in TARGETS:
                continue
            parent = self.u32(off+8)
            cls = {"superclass": None if parent == 0xffffffff else self.types[parent], "access_flags": self.u32(off+4), "fields": [], "methods": []}
            self.classes[owner] = cls
            pos = self.u32(off+24)
            if not pos:
                continue
            counts = []
            for _ in range(4):
                count, pos = self.uleb(pos)
                counts.append(count)
            for count in counts[:2]:
                index = 0
                for _ in range(count):
                    delta, pos = self.uleb(pos)
                    flags, pos = self.uleb(pos)
                    index += delta
                    cls["fields"].append({**self.fields[index], "access_flags": flags})
            for count in counts[2:]:
                index = 0
                for _ in range(count):
                    delta, pos = self.uleb(pos)
                    flags, pos = self.uleb(pos)
                    code, pos = self.uleb(pos)
                    index += delta
                    member = {**self.methods[index], "access_flags": flags}
                    cls["methods"].append(member)
                    if code:
                        units = list(struct.unpack_from("<" + "H"*self.u32(code+12), data, code+16))
                        self.code[(owner, member["name"], member["descriptor"])] = units

    def u16(self, off):
        return struct.unpack_from("<H", self.data, off)[0]

    def u32(self, off):
        return struct.unpack_from("<I", self.data, off)[0]

    def uleb(self, off):
        value = shift = 0
        while True:
            byte = self.data[off]
            off += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value, off
            shift += 7
            assert shift < 35, "invalid ULEB128"

    def instructions(self, owner, name, descriptor):
        units = self.code[(owner, name, descriptor)]
        result = []
        pc = 0
        while pc < len(units):
            word, op = units[pc], units[pc] & 255
            if op == 0 and word:
                kind = word >> 8
                size = units[pc+1]
                if kind == 1:
                    width = 4 + 2*size
                elif kind == 2:
                    width = 2 + 4*size
                elif kind == 3:
                    count = units[pc+2] | (units[pc+3] << 16)
                    width = 4 + (size*count+1)//2
                else:
                    raise AssertionError(("unknown payload", pc, word))
            elif op in (0x18,):
                width = 5
            elif op in (0xfa, 0xfb):
                width = 4
            elif op in (0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c) or 0x6e <= op <= 0x72 or 0x74 <= op <= 0x78 or op in (0xfc, 0xfd):
                width = 3
            elif op in (0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29, 0xfe, 0xff) or 0x2d <= op <= 0x3d or 0x44 <= op <= 0x6d or 0x90 <= op <= 0xaf or 0xd0 <= op <= 0xe2:
                width = 2
            else:
                assert op <= 0xe2 and op not in (0x3e, 0x3f, 0x40, 0x41, 0x42, 0x43, 0x73, 0x79, 0x7a), ("unsupported opcode", pc, op)
                width = 1
            insn = {"offset": pc, "opcode": f"{op:02x}", "units": units[pc:pc+width]}
            if 0x52 <= op <= 0x6d:
                insn["field"] = self.fields[units[pc+1]]
            elif 0x6e <= op <= 0x72 or 0x74 <= op <= 0x78:
                insn["method"] = self.methods[units[pc+1]]
            elif op in (0x1c, 0x1f, 0x20, 0x22, 0x23, 0x24, 0x25):
                insn["type"] = self.types[units[pc+1]]
            elif op in (0x2b, 0x2c):
                relative = struct.unpack("<i", struct.pack("<HH", *units[pc+1:pc+3]))[0]
                payload = pc + relative
                assert units[payload] == (0x100 if op == 0x2b else 0x200)
                size = units[payload+1]
                def signed32(pos):
                    return struct.unpack("<i", struct.pack("<HH", *units[pos:pos+2]))[0]
                keys = list(range(signed32(payload+2), signed32(payload+2)+size)) if op == 0x2b else [signed32(payload+2+2*j) for j in range(size)]
                targets = payload+4 if op == 0x2b else payload+2+2*size
                insn["switch"] = {"keys": keys, "targets": [pc+signed32(targets+2*j) for j in range(size)], "default": pc+3}
            result.append(insn)
            pc += width
        assert pc == len(units), "truncated instruction"
        return result


def archived():
    assert hashlib.sha256(APK.read_bytes()).hexdigest() == SHA256, "SystemUI.apk SHA256 mismatch"
    classes, dexes = {}, []
    with zipfile.ZipFile(APK) as archive:
        for name in sorted(archive.namelist()):
            if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
                dex = Dex(archive.read(name))
                dexes.append((name, dex))
                for owner, cls in dex.classes.items():
                    assert owner not in classes, ("duplicate class", owner)
                    classes[owner] = {"dex": name, **cls}
    assert set(classes) == set(TARGETS), ("missing classes", set(TARGETS)-set(classes))
    return classes, dexes


if __name__ == "__main__":
    expected = json.loads(INVENTORY.read_text(encoding="utf-8"))
    classes, dexes = archived()
    assert expected["artifact"] == {"path": str(APK.relative_to(ROOT)).replace("\\", "/"), "sha256": SHA256}
    assert classes == expected["classes"], "declared DEX members changed"
    required = [
        (BINDER, "methods", "emit", "(Ljava/lang/Object;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
        (BINDER, "fields", "$r8$classId", "I"),
        (BINDER, "fields", "$mobileDrawable", "Ljava/lang/Object;"),
        (BINDER, "fields", "$viewModel", VM),
        (VM, "methods", "getSubscriptionId", "()I"),
        (CELLULAR, "methods", "toSignalDrawableState", "()I"),
        ("Lkotlin/Pair;", "methods", "component2", "()Ljava/lang/Object;"),
    ]
    required += [(CELLULAR, "fields", name, sig) for name, sig in
                 [("carrierNetworkChange", "Z"), ("level", "I"), ("numberOfLevels", "I"), ("showExclamationMark", "Z")]]
    for suffix, callback, owner_field in [
        ("Impl", "$callbackEvents$1$1$callback$1;", "$this_run"),
        ("KairosImpl", "$callbackEvents$1$2$callback$1;", "this$0"),
    ]:
        owner = REPO + suffix + ";"
        cb = REPO + suffix + callback
        required += [(cb, "fields", owner_field, owner), (owner, "fields", "subId", "I"),
                     (owner, "methods", "getSubId", "()I")]
        required += [(cb, "methods", name, sig) for name, sig in [
            ("onSignalStrengthsChanged", "(Landroid/telephony/SignalStrength;)V"),
            ("onServiceStateChanged", "(Landroid/telephony/ServiceState;)V"),
            ("onCarrierRoamingNtnModeChanged", "(Z)V")]]
    for owner, kind, name, sig in required:
        key = "type" if kind == "fields" else "descriptor"
        assert any(x["name"] == name and x[key] == sig for x in classes[owner][kind]), (owner, name, sig)
    assert classes[DRAWABLE]["superclass"] == "Landroid/graphics/drawable/DrawableWrapper;"
    required_code = {(BINDER, "emit"), (VM, "getSubscriptionId"), (CELLULAR, "toSignalDrawableState")}
    required_code |= {(DRAWABLE, name) for name in ("getState", "unpackLevel", "isInState", "onLevelChange", "updateAnimation")}
    required_code |= {(owner, name) for owner in TARGETS if "$callbackEvents$" in owner
                      for name in ("onSignalStrengthsChanged", "onServiceStateChanged", "onCarrierRoamingNtnModeChanged")}
    assert {(x["class"], x["method"]) for x in expected["code_evidence"]} == required_code
    for evidence in expected["code_evidence"]:
        dex = next(d for name, d in dexes if name == evidence["dex"])
        actual = dex.instructions(evidence["class"], evidence["method"], evidence["descriptor"])
        assert actual == evidence["instructions"], ("DEX instructions changed", evidence["class"], evidence["method"])
    assert expected["runtime_preflight"]["SignalStrength.getPrimary"]["status"] == "unverified-framework-not-archived"
    print(f"PASS: actual APK SHA256, {len(classes)} DEX classes and {len(expected['code_evidence'])} exact method instruction streams")
    print("UNVERIFIED: private framework SignalStrength.getPrimary() -> CellSignalStrength; runtime preflight required; no live Hook/rendering claim")
