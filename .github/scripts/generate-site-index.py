#!/usr/bin/env python3
"""Generate the website catalog (docs/index.json) + icons for MHExtensions.

Scans src/<lang>/<name>/build.gradle.kts for the keiyoushi DSL block and emits
the extension list consumed by docs/extensions.html (the keiyoushi-style site).

Version naming mirrors gradle/build-logic ExtensionPlugin.kt:
  effectiveCode = versionCode + (theme baseVersionCode when `theme = "x"` is set)
  versionName   = "<libVersion>.<effectiveCode>"
  apk           = "tachiyomi-<langDir>.<dirname>-v<versionName>-release.apk"

Run from the repo root:
  python3 .github/scripts/generate-site-index.py

Outputs (idempotent):
  docs/index.json          — catalog
  docs/icons/<dirname>.png — copy of each extension's launcher icon
"""

from __future__ import annotations

import json
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src"
MULTISRC = ROOT / "lib-multisrc"
DOCS = ROOT / "docs"
ICONS = DOCS / "icons"

REPO_URL = "https://raw.githubusercontent.com/marbou92/MHRepo/main/repo.json"
REPO_HOME = "https://github.com/marbou92/MHRepo"
SOURCE_HOME = "https://github.com/marbou92/MHExtensions"

# Short human notes shown on each card (docs/extensions.html)
NOTES = {
    "comixto": "Signed+encrypted API via the WebView signing bridge; Cloudflare bypass; image descrambling",
    "manhuarmtl": "Madara theme + OCR text overlay burned onto raws in 12 languages",
    "mangaball": "",
    "mangadot": "One source per language (100+ content languages)",
    "mangafire": "One source per content language (7); vrf-signed API computed offline in a WebView",
    "swarm": "",
    "xcomic": "Fetches the complete source list incl. DOM-hidden groups; real scanlator badge naming",
    "atsumaru": "Custom JSON API: home2 browse + Typesense search",
    "kagane": "Integrity-token flow + Cloudflare bypass; data saver",
    "mangago": "Reader images harvested via the window-attached WebView; CF solver",
    "mangataro": "WordPress manga/v1 JSON API with double-encoded filters",
    "mkissa": "Browse/search/details only — chapter images blocked by the site's anti-bot token",
    "toonz": "Next.js site + JSON endpoints; JSON-LD details",
    "weebcentral": "",
}

# Long-form notes for the guide's "Extension notes" section (docs/guide/).
# Trusted repo-authored HTML (only <code> is used) — rendered via innerHTML by
# app.js; the guide list is generated from this so new extensions appear there
# automatically after the catalog regenerates.
DESCRIPTIONS = {
    "comixto": "signed+encrypted JSON API. The site's VM-protected signer means every "
               "<code>/api/v1</code> call runs through a WebView bridge that boots the site's "
               "own chunk graph and lets the site sign/decrypt in-JS; frozen native constants "
               "stay as the fast path. Cloudflare bypass, tile descrambling, 10 settings.",
    "manhuarmtl": "Madara theme with custom MRM selectors; Popular/Latest dedup via the archive "
                  "endpoint; OCR text overlay burned onto raws in Arabic + 10 languages, "
                  "toggleable; NSFW filter; full filter sheet.",
    "kagane": "Komga-fork <code>/api/v2</code> with short-lived integrity tokens for chapter "
              "manifests; transparent page-token refresh; data saver.",
    "atsumaru": "<code>home2</code> browse endpoints + Typesense search; full filter set.",
    "mkissa": "GraphQL persisted queries; browse/search/details only (anti-bot images).",
    "mangadot": "one source per content language (100+); Cloudflare solver.",
    "mangaball": "all-languages source.",
    "swarm": "swarm sites.",
    "xcomic": "reconciles the rendered Sources grid with the page state so hidden groups are "
              "fetched too; real scanlator badge naming; per-number dedupe across languages.",
    "weebcentral": "English source.",
    "mangataro": "WordPress manga/v1 JSON API with the theme's double-encoded filters and MD5 "
                 "chapter tokens; curated TriState genre list.",
    "toonz": "Next.js + JSON endpoints; JSON-LD details; browse section setting.",
    "mangago": "classic HTML browse/search; reader images harvested from the window-attached "
               "WebView; route-level CF solving.",
    "mangafire": "one source per content language (en, fr, es, es-la, pt, pt-br, ja); "
                 "vrf-protected API signed offline by the site's own polyfill chunk running in "
                 "a WebView; full filter sheet; smart description.",
}

RE_NAME = re.compile(r'^\s*name\s*=\s*"([^"]+)"', re.M)
RE_CODE = re.compile(r'^\s*versionCode\s*=\s*(\d+)', re.M)
RE_LIB = re.compile(r'^\s*libVersion\s*=\s*"([^"]+)"', re.M)
RE_LANG = re.compile(r'^\s*lang\s*=\s*"([^"]+)"', re.M)
RE_BASE = re.compile(r'baseUrl\s*=\s*"([^"]+)"')
RE_THEME = re.compile(r'^\s*theme\s*=\s*"([^"]+)"', re.M)
RE_BASE_VERSION = re.compile(r'^\s*baseVersionCode\s*=\s*(\d+)', re.M)


def extract_listof(text: str, prop: str) -> str | None:
    """Return the raw body of `prop = listOf(...)` with balanced-paren matching
    (comment lines inside the list may themselves contain parentheses)."""
    m = re.search(rf'{prop}\s*=\s*listOf\(', text)
    if not m:
        return None
    i = m.end()
    depth = 1
    body: list[str] = []
    while i < len(text) and depth:
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                break
        body.append(ch)
        i += 1
    return "".join(body)


def parse_gradle(text: str) -> dict:
    name = RE_NAME.search(text).group(1)
    code = int(RE_CODE.search(text).group(1))
    m = RE_LIB.search(text)
    lib = m.group(1) if m else "1.4"
    m = RE_BASE.search(text)
    base = m.group(1) if m else ""
    m = RE_THEME.search(text)
    theme = m.group(1) if m else None
    langs: list[str] = []
    body = extract_listof(text, "languages")
    if body is not None:
        body = re.sub(r"//[^\n]*", "", body)  # drop inline comment lines
        langs = re.findall(r'"([^"]+)"', body)
    else:
        m = RE_LANG.search(text)
        langs = [m.group(1)] if m else []
    # theme baseVersionCode addition (mirrors ExtensionPlugin.kt)
    if theme:
        theme_gradle = MULTISRC / theme / "build.gradle.kts"
        if theme_gradle.exists():
            m = RE_BASE_VERSION.search(theme_gradle.read_text(encoding="utf-8"))
            if m:
                code += int(m.group(1))
    return {"name": name, "code": code, "lib": lib, "base": base, "langs": langs}


def main() -> None:
    ICONS.mkdir(parents=True, exist_ok=True)
    extensions = []
    for gradle in sorted(SRC.glob("*/*/build.gradle.kts")):
        lang_dir = gradle.parent.parent.name  # all | en
        dirname = gradle.parent.name          # comixto, mangafire, ...
        info = parse_gradle(gradle.read_text(encoding="utf-8"))
        version = f"{info['lib']}.{info['code']}"
        pkg = f"{lang_dir}.{dirname}"
        apk = f"tachiyomi-{pkg}-v{version}-release.apk"
        icon_rel = None
        icon_src = gradle.parent / "res" / "mipmap-xhdpi" / "ic_launcher.png"
        if icon_src.exists():
            shutil.copyfile(icon_src, ICONS / f"{dirname}.png")
            icon_rel = f"../icons/{dirname}.png"  # page-relative: cards render on /extensions/
        extensions.append(
            {
                "name": info["name"],
                "pkg": pkg,
                "dirname": dirname,
                "langDir": lang_dir,
                "langs": info["langs"],
                "version": version,
                "versionCode": info["code"],
                "baseUrl": info["base"],
                "apk": apk,
                "apkUrl": f"https://raw.githubusercontent.com/marbou92/MHRepo/main/apk/{apk}",
                "icon": icon_rel,
                "note": NOTES.get(dirname, ""),
                "description": DESCRIPTIONS.get(dirname, NOTES.get(dirname, "")),
            }
        )

    extensions.sort(key=lambda e: e["name"].lower())
    catalog = {
        "meta": {
            "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "repoUrl": REPO_URL,
            "repoHome": REPO_HOME,
            "sourceHome": SOURCE_HOME,
        },
        "extensions": extensions,
    }
    DOCS.mkdir(exist_ok=True)
    (DOCS / "index.json").write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote docs/index.json — {len(extensions)} extensions")
    for e in extensions:
        print(f"  {e['name']:<12} {e['version']:<9} {e['pkg']:<18} {e['baseUrl']}")


if __name__ == "__main__":
    main()
