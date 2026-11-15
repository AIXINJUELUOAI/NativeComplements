"""Merge exported navbar learning files into the bundled template.

    python -I tests/merge_navbar_learned.py export1.json [export2.json ...] [--write]

Without --write only the report is printed. Rules:
  * whitelist packages ("*" rule) and hand-written rules (no "learned" flag) are never touched;
  * per page the newest app versionCode wins; inside one version votes are summed across files;
  * a page becomes a rule only with >= 3 votes and >= 90% agreement, otherwise it is reported as a conflict
    (several pages share the Activity, or devices disagree) and stays AUTO;
  * manually confirmed rules ("manual") beat learned ones; the last file given wins between manual rules,
    and a different existing hand-written rule is reported instead of overwritten.
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSET = ROOT / "app/src/main/assets/navbar/templates.json"
MIN_VOTES, AGREEMENT = 3, 0.9
ORIGINAL, COLOR, FLOATING = 0, 1, 2
MODES = (ORIGINAL, COLOR, FLOATING)


def votes_of(rule):
    """Votes for the rule's mode plus a bucket (-1) for the disagreeing rest."""
    total = int(rule.get("samples", 0))
    agree = round(total * float(rule.get("agreement", 0)))
    return {rule["mode"]: agree, -1: total - agree}


def main(argv):
    write = "--write" in argv
    files = [a for a in argv if a != "--write"]
    if not files:
        print(__doc__)
        return 2
    pages = {}   # (pkg, activity) -> {version: {mode: votes}}
    manual = {}  # (pkg, activity) -> mode, last file wins
    mixed = []
    for name in files:
        data = json.loads(pathlib.Path(name).read_text(encoding="utf-8"))
        if data.get("format") != 1:
            raise SystemExit(f"{name}: unsupported format")
        for pkg, app in data.get("packages", {}).items():
            for activity, rule in app.get("rules", {}).items():
                if rule.get("mode") not in MODES:
                    continue
                if rule.get("manual"):
                    manual[(pkg, activity)] = rule["mode"]
                    continue
                if not rule.get("learned"):
                    continue
                slot = pages.setdefault((pkg, activity), {}).setdefault(int(rule.get("versionCode", -1)), {})
                for mode, count in votes_of(rule).items():
                    slot[mode] = slot.get(mode, 0) + count
        mixed += [f'{m["package"]}/{m["activity"]} ' + " ".join(f"{k}={v}" for k, v in m.items() if k not in ("package", "activity"))
                  for m in data.get("mixed", [])]

    template = json.loads(ASSET.read_text(encoding="utf-8"))
    packages = template["packages"]
    added = updated = 0
    conflicts, skipped = [], []
    for (pkg, activity), mode in sorted(manual.items()):
        app = packages.get(pkg)
        rules = app.get("rules", {}) if app else {}
        existing = rules.get(activity)
        if "*" in rules and not rules["*"].get("learned"):
            skipped.append(f"{pkg}/{activity}: whitelist package")
            continue
        if existing is not None and not existing.get("learned") and not existing.get("manual") and existing.get("mode") != mode:
            conflicts.append(f"{pkg}/{activity}: manual {mode} vs hand-written {existing.get('mode')}")
            continue
        rule = {"mode": mode, "source": "人工确认", "base_only": True, "manual": True}
        if app is None:
            app = packages[pkg] = {"enabled": True, "rules": {}}
        app.setdefault("rules", {})
        if existing is None:
            added += 1
        elif existing != rule:
            updated += 1
        app["rules"][activity] = rule
    for (pkg, activity), by_version in sorted(pages.items()):
        if (pkg, activity) in manual:
            continue
        version = max(by_version)
        counts = by_version[version]
        total = sum(counts.values())
        if total < MIN_VOTES:
            continue
        mode = next((m for m in MODES if counts.get(m, 0) >= total * AGREEMENT), None)
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
            conflicts.append(f"{pkg}/{activity} v{version}: votes={counts}")
            continue
        rule = {"mode": mode, "source": "学习规则", "base_only": True, "learned": True, "versionCode": version,
                "samples": total, "agreement": round(counts[mode] / total, 3)}
        if app is None:
            app = packages[pkg] = {"enabled": True, "rules": {}}
        app.setdefault("rules", {})
        if existing is None:
            added += 1
        elif existing != rule:
            updated += 1
        app["rules"][activity] = rule

    print(f"pages={len(pages)} manual={len(manual)} added={added} updated={updated} conflicts={len(conflicts)} skipped={len(skipped)} mixed={len(mixed)}")
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
