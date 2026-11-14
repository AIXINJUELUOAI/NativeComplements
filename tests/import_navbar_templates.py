"""Import ColorOS transparency metadata and recommended scopes; --check never writes files."""
import argparse
from collections import Counter
import copy
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
XML = ROOT / "verification/coloros-navbar/PJZ110-17.0.0.103/artifacts/sys_navbar_color_white_list.xml"
ASSET = ROOT / "app/src/main/assets/navbar/templates.json"
XML_SHA256 = "570af80e43cb0c4821fa816d9e06a241affc4095c6daf6edd20c2bc2c1d02210"
SCOPE = ROOT / "app/src/main/resources/META-INF/xposed/scope.list"
SYSTEM_SCOPES = ("system", "com.google.android.apps.nexuslauncher", "com.android.systemui")


def recommended_scopes(raw):
    """Recommendations only, not runtime authorization; use outer packages, never Activity prefixes."""
    digest = hashlib.sha256(raw).hexdigest()
    if digest != XML_SHA256:
        raise ValueError(f"unexpected source XML SHA-256: {digest}")
    packages = {app.attrib["package_name"] for app in ET.fromstring(raw).findall("r")
                if app.get("status") == "1"}
    if len(packages) != 63:
        raise ValueError(f"unexpected active source package count: {len(packages)}")
    return [*SYSTEM_SCOPES, *sorted(packages - set(SYSTEM_SCOPES))]


def check_scopes(text, expected):
    actual = text.splitlines()
    if not set(SYSTEM_SCOPES) <= set(actual):
        raise ValueError("required system scopes missing")
    if len(actual) != len(set(actual)) or set(actual) != set(expected):
        raise ValueError("recommended scopes differ from source XML")


DESCRIPTION = ("依据已核实的 ColorOS 资格机制建立本模块适配名单；资格例外 rules 与透明策略 "
               "translucent_rules 独立保存，policy_flag 保留原始位语义，不等同模式或消费 Insets；"
               "不是完整 ROM 实现移植，不调用厂商私有 API。")


def base_only(entry):
    value = entry.get("base_only", "true")
    if value not in ("true", "false"):
        raise ValueError(f"invalid base_only: {value!r}")
    return value == "true"


def imported(current, raw):
    digest = hashlib.sha256(raw).hexdigest()
    if digest != XML_SHA256:
        raise ValueError(f"unexpected source XML SHA-256: {digest}")
    tree = ET.fromstring(raw)
    if not any(source.get("sha256") == digest and source.get("version") == tree.findtext("version")
               for source in current["sources"]):
        raise ValueError("asset source hash/version does not match XML")
    result = copy.deepcopy(current)
    result["description"] = DESCRIPTION
    for app in result["packages"].values():
        app.pop("translucent_rules", None)
    eligibility, transparency = {}, {}
    for app in tree.findall("r"):
        if app.get("status") != "1":
            continue
        package = app.attrib["package_name"]
        policy = json.loads(app.attrib["body"].replace("^", '"'))
        for entry in policy.get("activity_black_list", []):
            key = (package, entry["activity_name"])
            if key in eligibility:
                raise ValueError(f"duplicate eligibility entry: {key}")
            eligibility[key] = {"mode": 0, "source": "ColorOS 资格例外", "base_only": base_only(entry)}
        for entry in policy.get("enforce_edge_activity", []):
            activity = entry["activity_name"]
            key = (package, activity)
            if not activity or "*" in activity or key in transparency:
                raise ValueError(f"invalid or duplicate transparency entry: {key}")
            flag = int(entry.get("policy_flag", 4))
            if flag < 0 or flag & ~15:
                raise ValueError(f"unknown policy_flag: {flag}")
            rule = {"policy_flag": flag, "base_only": base_only(entry), "source": "ColorOS 透明策略"}
            transparency[key] = rule
            target = result["packages"].setdefault(package, {"enabled": True, "rules": {}})
            target.setdefault("translucent_rules", {})[activity] = rule
    existing = {(pkg, page): rule for pkg, app in current["packages"].items()
                for page, rule in app.get("rules", {}).items()}
    if existing != eligibility:
        raise ValueError("existing eligibility rules differ from XML; refusing to change them")
    flags = Counter(rule["policy_flag"] for rule in transparency.values())
    overlaps = sorted(eligibility.keys() & transparency.keys())
    print(f"{len(result['packages'])} packages; eligibility={len(existing)}; "
          f"translucent={len(transparency)} in {len({pkg for pkg, _ in transparency})} packages; "
          f"flags={dict(sorted(flags.items()))}; "
          f"base_only=false: {sum(not rule['base_only'] for rule in transparency.values())}; "
          f"eligibility/translucency overlaps={len(overlaps)}")
    for key in overlaps:
        print(f"  overlap: {key[0]}/{key[1]}")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="validate the asset without writing")
    args = parser.parse_args()
    original = ASSET.read_text(encoding="utf-8")
    raw = XML.read_bytes()
    result = imported(json.loads(original), raw)
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    scopes = recommended_scopes(raw)
    rendered_scope = "\n".join(scopes) + "\n"
    if args.check:
        if original != rendered:
            parser.exit(1, "FAIL: transparency metadata is stale; run tests/import_navbar_templates.py\n")
        try:
            check_scopes(SCOPE.read_text(encoding="utf-8"), scopes)
        except ValueError as error:
            parser.exit(1, f"FAIL: {error}; run tests/import_navbar_templates.py\n")
        print("PASS: source hash, eligibility preservation, transparency metadata and recommended scopes match")
    else:
        if original != rendered:
            ASSET.write_text(rendered, encoding="utf-8")
            print("Updated navbar templates asset")
        SCOPE.write_text(rendered_scope, encoding="utf-8")
        print("Updated recommended scopes")
    print(f"Recommended scopes: {len(scopes)} total = {len(SYSTEM_SCOPES)} required system + "
          f"{len(scopes) - len(SYSTEM_SCOPES)} source applications (recommendation, not authorization)")


if __name__ == "__main__":
    main()
