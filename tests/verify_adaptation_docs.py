"""Check the adaptation guide's local Markdown links without Android artifacts or dependencies."""
from pathlib import Path
import re
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
GUIDE = ROOT / "docs/adaptation"
EXPECTED = ("README.md", "architecture.md", "floating-window.md", "system-features.md", "standalone-window.md",
            "appearance-boundary.md", "icon-boundary.md", "notification-boundary.md", "system-policy-boundary.md", "shared-boundary.md",
            "alive-assets.md", "alive-platform.md", "alive-renderer.md")
LINK = re.compile(r"\[[^\]\n]*\]\((?:<([^>]+)>|([^\s)]+))\)")
LINE = re.compile(r"L([1-9]\d*)(?:-L([1-9]\d*))?")
REFERENCE = re.compile(r"^\s*\[[^\]\n]+\]:\s*(?:<([^>]+)>|(\S+))", re.MULTILINE)


def check():
    count = 0
    for name in EXPECTED:
        document = GUIDE / name
        assert document.is_file(), f"missing guide: {document.relative_to(ROOT)}"
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
            assert resolved.is_file(), (name, "missing target", target)
            if fragment.startswith("L"):
                lines = LINE.fullmatch(fragment)
                assert lines, (name, "invalid line anchor", target)
                start = int(lines[1])
                end = int(lines[2] or start)
                assert start <= end <= len(resolved.read_text(encoding="utf-8").splitlines()), (name, "line range", target)
            count += 1
    print(f"PASS: {len(EXPECTED)} adaptation documents, {count} local links and source line ranges; semantics/runtime not verified")


if __name__ == "__main__":
    check()
