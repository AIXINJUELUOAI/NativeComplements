"""Check public Markdown links against the Git index, not ignored local artifacts."""
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
GUIDE = ROOT / "docs/adaptation"
EXPECTED = ("README.md", "architecture.md", "floating-window.md", "system-features.md", "standalone-window.md",
            "appearance-boundary.md", "icon-boundary.md", "notification-boundary.md", "system-policy-boundary.md", "shared-boundary.md",
            "alive-assets.md", "alive-platform.md", "alive-renderer.md", "alive-module.md", "alive-systemui.md", "lineageos-24.0.md")
LINK = re.compile(r"\[[^\]\n]*\]\((?:<([^>]+)>|([^\s)]+))\)")
LINE = re.compile(r"L([1-9]\d*)(?:-L([1-9]\d*))?")
REFERENCE = re.compile(r"^\s*\[[^\]\n]+\]:\s*(?:<([^>]+)>|(\S+))", re.MULTILINE)


def check():
    tracked = {ROOT / name for name in subprocess.check_output(
        ["git", "ls-files", "-z"], cwd=ROOT).decode("utf-8").split("\0") if name}
    documents = sorted(path for path in tracked if path.suffix == ".md")
    for name in EXPECTED:
        assert GUIDE / name in tracked, f"guide not public: {name}"
    count = 0
    for document in documents:
        name = document.relative_to(ROOT).as_posix()
        assert document.is_file(), f"missing document: {name}"
        text = document.read_text(encoding="utf-8")
        assert text.startswith("# "), f"missing title: {name}"
        for match in (*LINK.finditer(text), *REFERENCE.finditer(text)):
            target = match.group(1) or match.group(2)
            if re.match(r"[A-Za-z][A-Za-z0-9+.-]*:", target) or target.startswith("//"):
                assert target.startswith(("https://", "http://")), (name, "nonportable link", target)
                continue
            path, _, fragment = target.partition("#")
            resolved = (document.parent / unquote(path)).resolve() if path else document
            assert resolved.is_relative_to(ROOT), (name, "outside repository", target)
            assert resolved in tracked, (name, "target not public in Git", target)
            assert resolved.is_file(), (name, "missing target", target)
            if fragment.startswith("L"):
                lines = LINE.fullmatch(fragment)
                assert lines, (name, "invalid line anchor", target)
                start = int(lines[1])
                end = int(lines[2] or start)
                assert start <= end <= len(resolved.read_text(encoding="utf-8").splitlines()), (name, "line range", target)
            count += 1
    print(f"PASS: {len(documents)} public Markdown documents, {count} tracked local links and source line ranges; semantics/runtime not verified")


if __name__ == "__main__":
    check()
