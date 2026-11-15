"""Merge exported navbar learning files into the bundled template.

    python -I tests/merge_navbar_learned.py export1.json [export2.json ...] [--write]

Without --write only the report is printed. Rules:
  * whitelist packages ("*" rule) and hand-written rules (no "learned" flag) are never touched;
  * per page the newest app versionCode wins; inside one version votes are summed across files;
  * a page becomes a rule only with >= 3 votes and >= 90% agreement, otherwise it is reported as a conflict
    (several pages share the Activity, or devices disagree) and stays AUTO.
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSET = ROOT / "app/src/main/assets/navbar/templates.json"
MIN_VOTES, AGREEMENT = 3, 0.9
COLOR, FLOATING = 1, 2


def votes_of(rule):
    total = int(rule.get("samples", 0))
    agree = round(total * float(rule.get("agreement", 0)))
    return (agree, total - agree) if rule["mode"] == COLOR else (total - agree, agree)


def main(argv):
    write = "--write" in argv
    files = [a for a in argv if a != "--write"]
    if not files:
        print(__doc__)
        return 2
    pages = {}   # (pkg, activity) -> {version: [extend, floating]}
    mixed = []
    for name in files:
        data = json.loads(pathlib.Path(name).read_text(encoding="utf-8"))
        if data.get("format") != 1:
            raise SystemExit(f"{name}: unsupported format")
        for pkg, app in data.get("packages", {}).items():
            for activity, rule in app.get("rules", {}).items():
                if not rule.get("learned") or rule.get("mode") not in (COLOR, FLOATING):
                    continue
                extend, floating = votes_of(rule)
                slot = pages.setdefault((pkg, activity), {}).setdefault(int(rule.get("versionCode", -1)), [0, 0])
                slot[0] += extend
                slot[1] += floating
        mixed += [f'{m["package"]}/{m["activity"]} v{m["versionCode"]} extend={m["extend"]} floating={m["floating"]}'
                  for m in data.get("mixed", [])]

    template = json.loads(ASSET.read_text(encoding="utf-8"))
    packages = template["packages"]
    added = updated = 0
    conflicts, skipped = [], []
    for (pkg, activity), by_version in sorted(pages.items()):
        version = max(by_version)
        extend, floating = by_version[version]
        total = extend + floating
        if total < MIN_VOTES:
            continue
        mode = COLOR if extend >= total * AGREEMENT else FLOATING if floating >= total * AGREEMENT else None
        app = packages.get(pkg)
        rules = app.get("rules", {}) if app else {}
        if "*" in rules and not rules["*"].get("learned"):
            skipped.append(f"{pkg}/{activity}: whitelist package")
            continue
        existing = rules.get(activity)
        if existing is not None and not existing.get("learned"):
            skipped.append(f"{pkg}/{activity}: hand-written rule kept")
            continue
        if mode is None:
            conflicts.append(f"{pkg}/{activity} v{version}: extend={extend} floating={floating}")
            continue
        rule = {"mode": mode, "source": "学习规则", "base_only": True, "learned": True, "versionCode": version,
                "samples": total, "agreement": round(max(extend, floating) / total, 3)}
        if app is None:
            app = packages[pkg] = {"enabled": True, "rules": {}}
        app.setdefault("rules", {})
        if existing is None:
            added += 1
        elif existing != rule:
            updated += 1
        app["rules"][activity] = rule

    print(f"pages={len(pages)} added={added} updated={updated} conflicts={len(conflicts)} skipped={len(skipped)} mixed={len(mixed)}")
    for title, lines in (("conflicts (kept AUTO)", conflicts), ("skipped", skipped), ("mixed on device (kept AUTO)", mixed)):
        if lines:
            print(f"-- {title}")
            for line in lines:
                print("  " + line)
    if write:
        ASSET.write_text(json.dumps(template, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        print(f"written {ASSET}")
    else:
        print("dry run: add --write to update the template")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
