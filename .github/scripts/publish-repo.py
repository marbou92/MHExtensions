#!/usr/bin/env python3
"""
publish-repo.py — Publish built extension APKs to the MHRepo repository.

Uses protobuf (index.pb) for the v2 index format, exactly like Keiyoushi.
Generates: index.json (proto-JSON), index.pb (gzip compressed protobuf),
index.html (listing page).

Usage:
  python publish-repo.py                    # Publish mode (default)
  python publish-repo.py --cleanup          # Cleanup mode
  python publish-repo.py --cleanup --dry    # Cleanup dry-run

Environment variables:
  REPO_PAT    — GitHub PAT for the MHRepo repository (required; validated
                against the GitHub API up front — invalid/expired/revoked
                tokens or tokens without push access fail in seconds with
                instructions, instead of after the whole publish)
  REPO_OWNER  — Owner of the MHRepo repo (default: marbou92)
  REPO_NAME   — Name of the MHRepo repo (default: MHRepo)
  SOURCE_OWNER — Owner of the source repo (default: marbou92)
  SOURCE_NAME  — Name of the source repo (default: MHExtensions)
  SIGNING_FINGERPRINT — SHA-256 fingerprint of the signing key (required)
"""

import argparse
import gzip
import html
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

from google.protobuf import json_format

import index_pb2

# =============================================================================
# Configuration
# =============================================================================

REPO_OWNER = os.environ.get("REPO_OWNER", "marbou92")
REPO_NAME = os.environ.get("REPO_NAME", "MHRepo")
# Stripped: a trailing newline/space in the secret would poison the URL.
REPO_PAT = os.environ.get("REPO_PAT", "").strip()
REPO_URL = f"https://x-access-token:{REPO_PAT}@github.com/{REPO_OWNER}/{REPO_NAME}.git"
REPO_DIR = Path("/tmp/MHRepo")

SOURCE_OWNER = os.environ.get("SOURCE_OWNER", "marbou92")
SOURCE_NAME = os.environ.get("SOURCE_NAME", "MHExtensions")
SOURCE_ROOT = Path(os.environ.get("GITHUB_WORKSPACE", "."))
SCRIPT_DIR = Path(__file__).resolve().parent

# CDN base URLs (matching Keiyoushi's approach)
APK_BASE_URL = f"https://cdn.jsdelivr.net/gh/{REPO_OWNER}/{REPO_NAME}@main/apk"
JAR_BASE_URL = f"https://cdn.jsdelivr.net/gh/{REPO_OWNER}/{REPO_NAME}@main/jar"
ICON_BASE_URL = f"https://cdn.jsdelivr.net/gh/{SOURCE_OWNER}/{SOURCE_NAME}@main"

SIGNING_FINGERPRINT = os.environ.get("SIGNING_FINGERPRINT", "")

ICON_FILE = "res/mipmap-xhdpi/ic_launcher.png"


# =============================================================================
# Helpers
# =============================================================================

def normalize_fingerprint(fp):
    """
    Normalize a signing key fingerprint to lowercase hex without colons.
    keytool outputs: B6:E6:21:B4:0A:C6:...
    Mihon expects:   b6e621b40ac65e14...
    """
    if not fp:
        return ""
    return fp.replace(":", "").replace(" ", "").lower()


def run(cmd, **kwargs):
    """Run a shell command, exit on failure."""
    kwargs.setdefault("env", dict(os.environ, GIT_TERMINAL_PROMPT="0"))
    result = subprocess.run(cmd, shell=True, **kwargs)
    if result.returncode != 0:
        print(f"ERROR: Command failed: {cmd}", file=sys.stderr)
        sys.exit(1)
    return result


def git(*args, cwd=REPO_DIR):
    """Run a git command in the repo directory."""
    cmd = ["git"] + list(args)
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0")
    result = subprocess.run(cmd, cwd=str(cwd), capture_output=True, text=True, env=env)
    if result.returncode != 0:
        print(f"git {' '.join(args)} failed:", file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        if args and args[0] == "push" and "Invalid username or token" in result.stderr:
            print(
                "HINT: GitHub rejected the token at push time even though the pre-flight "
                "passed. The token may have been revoked mid-run, or it authenticates "
                f"without push rights on {REPO_OWNER}/{REPO_NAME} (fine-grained tokens need "
                "MHRepo in 'Repository access' with 'Contents: Read and write'; classic "
                "tokens need the 'repo' scope).",
                file=sys.stderr,
            )
        sys.exit(1)
    return result.stdout.strip()


def token_description():
    """Safe description of the token for logs — never the token itself."""
    if not REPO_PAT:
        return "(empty)"
    if REPO_PAT.startswith("github_pat_"):
        kind = "fine-grained PAT"
    elif REPO_PAT.startswith("ghp_"):
        kind = "classic PAT"
    elif REPO_PAT.startswith(("gho_", "ghu_", "ghs_", "ghr_")):
        kind = "GitHub App/OAuth token"
    else:
        kind = "unrecognized format"
    return f"{kind}, {len(REPO_PAT)} chars"


def preflight_token_check():
    """
    Validate REPO_PAT against the GitHub API BEFORE doing any real work.

    GitHub's git server silently ignores credentials when FETCHING a public
    repository (verified: a clone with a garbage token succeeds), so a dead
    token sails through the clone and only explodes at push time — after
    the whole build/publish is wasted. The REST API tells the truth up
    front: 401 = invalid/expired/revoked, and the repo payload carries the
    token's actual push permission on MHRepo.
    """
    print(f"Pre-flight: REPO_PAT looks like {token_description()}")

    api_url = f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}"
    req = urllib.request.Request(
        api_url,
        headers={
            "Authorization": f"token {REPO_PAT}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "publish-repo.py",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = json.load(resp)
    except urllib.error.HTTPError as e:
        body = ""
        try:
            body = e.read().decode("utf-8", "replace")
        except Exception:
            pass
        if "rate limit" in body.lower():
            print(f"WARNING: api.github.com rate-limited the pre-flight (HTTP {e.code}); continuing — push will decide.")
            return
        if e.code == 401:
            print(
                "::error::GitHub rejected REPO_PAT (HTTP 401) — the token is invalid, "
                "expired, or revoked. Tokens expire (30/60/90 days), and pushes to "
                "MHRepo last succeeded via this same secret, so expiry is the most "
                "likely cause. Fix: create a new classic PAT with the 'repo' scope "
                "(Settings → Developer settings → Personal access tokens → Tokens "
                f"(classic)), update the REPO_PAT secret in {SOURCE_OWNER}/{SOURCE_NAME}, "
                "then re-run this job."
            )
            sys.exit(1)
        if e.code in (403, 404):
            print(
                f"::error::GitHub denied REPO_PAT access to {REPO_OWNER}/{REPO_NAME} "
                f"(HTTP {e.code}). If the token is fine-grained, its 'Repository "
                "access' must include MHRepo with 'Contents: Read and write'. If it "
                "is classic, it needs the 'repo' scope. Update the REPO_PAT secret "
                "and re-run."
            )
            sys.exit(1)
        print(f"WARNING: api.github.com returned HTTP {e.code}; skipping pre-flight — push will decide.")
        return
    except (urllib.error.URLError, OSError, ValueError) as e:
        print(f"WARNING: could not reach api.github.com ({e}); skipping pre-flight — push will decide.")
        return

    perms = data.get("permissions") or {}
    if perms.get("push") is False:
        print(
            f"::error::REPO_PAT authenticates but CANNOT push to {REPO_OWNER}/{REPO_NAME}. "
            "This is the signature of a fine-grained token scoped to other repos only: "
            "it can READ this public repo but not push. Edit the token so 'Repository "
            "access' includes MHRepo with 'Contents: Read and write' — or switch to a "
            "classic token with the 'repo' scope, which covers every repo and just "
            "works. Update the REPO_PAT secret and re-run."
        )
        sys.exit(1)

    print(f"Pre-flight OK: token can push to {REPO_OWNER}/{REPO_NAME}.")


def get_valid_packages():
    """Extract all valid extension package names from settings.gradle.kts."""
    settings_file = SOURCE_ROOT / "settings.gradle.kts"
    if not settings_file.exists():
        print(f"ERROR: {settings_file} not found", file=sys.stderr)
        sys.exit(1)

    content = settings_file.read_text()
    # Skip comment lines
    lines = [line for line in content.splitlines() if not line.strip().startswith("//")]
    content = "\n".join(lines)
    pattern = re.compile(r'loadIndividualExtension\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)')
    matches = pattern.findall(content)

    packages = set()
    for lang, name in matches:
        packages.add(f"eu.kanade.tachiyomi.extension.{lang}.{name}")

    print(f"Found {len(packages)} valid package(s) in settings.gradle.kts:")
    for pkg in sorted(packages):
        print(f"  - {pkg}")
    return packages


def clone_repo():
    """Clone the MHRepo repository and configure git identity."""
    if REPO_DIR.exists():
        shutil.rmtree(REPO_DIR)
    print(f"Cloning {REPO_OWNER}/{REPO_NAME}...")
    run(f"git clone --depth 1 {REPO_URL} {REPO_DIR}")
    git("config", "user.name", "github-actions[bot]", cwd=REPO_DIR)
    git("config", "user.email", "github-actions[bot]@users.noreply.github.com", cwd=REPO_DIR)


def get_icon_url(module, theme):
    """Get icon URL from source repo (Keiyoushi approach)."""
    module_icon = f"src/{module.replace('.', '/')}/{ICON_FILE}"
    if (SOURCE_ROOT / module_icon).exists():
        return f"{ICON_BASE_URL}/{module_icon}"

    if theme:
        theme_icon = f"lib-multisrc/{theme}/{ICON_FILE}"
        if (SOURCE_ROOT / theme_icon).exists():
            return f"{ICON_BASE_URL}/{theme_icon}"

    return f"{ICON_BASE_URL}/core/src/main/{ICON_FILE}"


def purge_cdn_cache():
    """Purge jsDelivr CDN cache for index files and APKs."""
    files_to_purge = [
        f"https://purge.jsdelivr.net/gh/{REPO_OWNER}/{REPO_NAME}@main/index.json",
        f"https://purge.jsdelivr.net/gh/{REPO_OWNER}/{REPO_NAME}@main/index.pb",
        f"https://purge.jsdelivr.net/gh/{REPO_OWNER}/{REPO_NAME}@main/repo.json",
    ]

    apk_dir = REPO_DIR / "apk"
    if apk_dir.exists():
        for apk_file in apk_dir.glob("*.apk"):
            files_to_purge.append(
                f"https://purge.jsdelivr.net/gh/{REPO_OWNER}/{REPO_NAME}@main/apk/{apk_file.name}"
            )

    for url in files_to_purge:
        try:
            result = subprocess.run(
                ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", url],
                timeout=30,
                capture_output=True,
                text=True,
            )
            status = result.stdout.strip()
            print(f"  Purged CDN ({status}): {url}")
        except Exception:
            print(f"  WARNING: Failed to purge {url}")


# =============================================================================
# Cleanup mode
# =============================================================================

def cleanup(dry_run=False):
    """Remove orphaned extensions from MHRepo."""
    valid_packages = get_valid_packages()
    clone_repo()

    # Load existing index.json (proto-JSON format)
    index_file = REPO_DIR / "index.json"
    if not index_file.exists():
        print("No index.json found. Nothing to clean up.")
        return

    with open(index_file) as f:
        remote_proto = json_format.Parse(f.read(), index_pb2.Index())

    all_extensions = list(remote_proto.extensionList.extensions)
    kept = []
    deleted = []

    for ext in all_extensions:
        if ext.packageName in valid_packages:
            kept.append(ext)
        else:
            deleted.append(ext)

    if not deleted:
        print("No orphaned extensions found. Nothing to clean up.")
        return

    print(f"\nFound {len(deleted)} orphaned extension(s) to remove:")
    for ext in deleted:
        print(f"  - {ext.name} ({ext.packageName})")

    if dry_run:
        print("\n[DRY RUN] No files were deleted.")
        return

    # Delete APK/JAR files for orphaned entries
    apk_dir = REPO_DIR / "apk"
    jar_dir = REPO_DIR / "jar"
    for ext in deleted:
        module_suffix = ".".join(ext.packageName.split(".")[-2:])
        if module_suffix:
            for old_apk in apk_dir.glob(f"tachiyomi-{module_suffix}-v*.apk"):
                old_apk.unlink()
                print(f"    Deleted APK: {old_apk.name}")
            for old_jar in jar_dir.glob(f"tachiyomi-{module_suffix}-v*.jar"):
                old_jar.unlink()
                print(f"    Deleted JAR: {old_jar.name}")

    # Rebuild and write index
    kept.sort(key=lambda e: e.packageName)
    new_index = index_pb2.Index(
        name="MarBou",
        badgeLabel="marbou92",
        signingKey=normalize_fingerprint(SIGNING_FINGERPRINT),
        contact=index_pb2.Contact(
            website=f"https://github.com/{SOURCE_OWNER}/{SOURCE_NAME}",
        ),
        extensionList=index_pb2.ExtensionList(extensions=kept),
    )

    with open(index_file, "w", encoding="utf-8") as f:
        f.write(
            json_format.MessageToJson(
                new_index,
                always_print_fields_with_no_presence=True,
                preserving_proto_field_name=True,
            )
        )

    with open(REPO_DIR / "index.pb", "wb") as f:
        f.write(gzip.compress(new_index.SerializeToString(deterministic=True)))

    print(f"\nCleanup complete: {len(all_extensions)} → {len(kept)} entries")

    git("add", "-A", cwd=REPO_DIR)
    status = git("status", "--porcelain", cwd=REPO_DIR)
    if not status:
        print("No changes to commit.")
        return

    git("commit", "-m", "chore: cleanup orphaned extensions", cwd=REPO_DIR)
    git("push", cwd=REPO_DIR)
    purge_cdn_cache()
    print("Pushed cleanup commit to MHRepo.")


# =============================================================================
# Publish mode (Keiyoushi-style protobuf)
# =============================================================================

def find_source_info_files():
    """Find all keiyoushi-source-info.json files."""
    info_files = []
    for root, dirs, files in os.walk(SOURCE_ROOT):
        if ".gradle" in root or "intermediates" in root:
            continue
        for f in files:
            if f == "keiyoushi-source-info.json":
                info_files.append(Path(root) / f)
    return info_files


def find_apk_for_info(info_file):
    """Find the release APK for a given info file."""
    build_dir = info_file.parent
    apk_dir = build_dir / "outputs" / "apk" / "release"
    if apk_dir.exists():
        apks = list(apk_dir.glob("*.apk"))
        if apks:
            return apks[0]

    info = json.load(open(info_file))
    module = info.get("module", "")
    search_dirs = [build_dir, build_dir.parent, SOURCE_ROOT / "artifacts"]
    for search_dir in search_dirs:
        if search_dir.exists():
            for apk in search_dir.rglob(f"tachiyomi-{module}-v*.apk"):
                return apk
    return None


def find_jar_for_info(info_file):
    """Find the release JAR for a given info file."""
    build_dir = info_file.parent
    jar_dir = build_dir / "outputs" / "jar" / "release"
    if jar_dir.exists():
        jars = list(jar_dir.glob("*.jar"))
        if jars:
            return jars[0]

    info = json.load(open(info_file))
    module = info.get("module", "")
    search_dirs = [build_dir, build_dir.parent, SOURCE_ROOT / "artifacts"]
    for search_dir in search_dirs:
        if search_dir.exists():
            for jar in search_dir.rglob(f"tachiyomi-{module}-v*.jar"):
                return jar
    return None


def content_warning_to_proto(cw_int):
    """Convert contentWarning int to proto enum."""
    return {
        0: index_pb2.CONTENT_WARNING_UNSPECIFIED,
        1: index_pb2.CONTENT_WARNING_SAFE,
        2: index_pb2.CONTENT_WARNING_MIXED,
        3: index_pb2.CONTENT_WARNING_NSFW,
    }.get(cw_int, index_pb2.CONTENT_WARNING_SAFE)


def publish():
    """Publish built extensions to MHRepo using protobuf (Keiyoushi-style)."""
    if not SIGNING_FINGERPRINT:
        print("ERROR: SIGNING_FINGERPRINT environment variable is required", file=sys.stderr)
        sys.exit(1)

    clone_repo()

    # Ensure directories exist
    apk_dir = REPO_DIR / "apk"
    jar_dir = REPO_DIR / "jar"
    apk_dir.mkdir(parents=True, exist_ok=True)
    jar_dir.mkdir(parents=True, exist_ok=True)

    # Find all keiyoushi-source-info.json files
    info_files = find_source_info_files()
    if not info_files:
        print("No keiyoushi-source-info.json files found.")
        sys.exit(1)

    print(f"\nFound {len(info_files)} extension(s) to publish:")
    for info_file in info_files:
        print(f"  - {info_file}")

    # Build new extension protobuf messages
    new_extensions = []
    published_modules = []

    for info_file in info_files:
        with open(info_file) as f:
            info = json.load(f)

        pkg = info.get("packageName") or ""
        if not pkg:
            print(f"\n  WARNING: No packageName in {info_file}, skipping")
            continue

        name = info.get("name") or pkg.split(".")[-1]
        module = info.get("module") or ".".join(pkg.split(".")[-2:])
        theme = info.get("theme")
        sources = info.get("sources", [])

        apk_path = find_apk_for_info(info_file)
        if apk_path is None:
            print(f"\n  WARNING: No release APK found for {name} ({pkg}), skipping")
            continue

        jar_path = find_jar_for_info(info_file)

        print(f"\n  Publishing {name} ({pkg}) v{info.get('versionName', '?')} (code {info.get('versionCode', '?')})...")

        # Copy APK and JAR to repo
        apk_dest = apk_dir / apk_path.name
        shutil.copy2(apk_path, apk_dest)

        jar_filename = None
        if jar_path:
            jar_dest = jar_dir / jar_path.name
            shutil.copy2(jar_path, jar_dest)
            jar_filename = jar_path.name

        # Remove old APKs/JARs for this module
        for old_file in apk_dir.glob(f"tachiyomi-{module}-v*.apk"):
            if old_file.name != apk_path.name:
                old_file.unlink()
                print(f"    Removed old APK: {old_file.name}")
        if jar_filename:
            for old_file in jar_dir.glob(f"tachiyomi-{module}-v*.jar"):
                if old_file.name != jar_filename:
                    old_file.unlink()
                    print(f"    Removed old JAR: {old_file.name}")

        # Build protobuf Extension message
        resources = index_pb2.Resources(
            apkUrl=f"{APK_BASE_URL}/{apk_path.name}",
            iconUrl=get_icon_url(module, theme),
        )
        if jar_filename:
            resources.jarUrl = f"{JAR_BASE_URL}/{jar_filename}"

        ext = index_pb2.Extension(
            name=name,
            packageName=pkg,
            resources=resources,
            extensionLib=info.get("extensionLib", "1.4"),
            versionCode=int(info.get("versionCode", 0)),
            versionName=info.get("versionName", str(info.get("versionCode", 0))),
            contentWarning=content_warning_to_proto(info.get("contentWarning", 1)),
            sources=[
                index_pb2.Source(
                    id=int(s.get("id", 0)),
                    name=s.get("name", ""),
                    language=s.get("lang", "all"),
                    homeUrl=s.get("baseUrl", ""),
                    mirrorUrls=s.get("mirrorUrls", []),
                )
                for s in sources
            ],
        )
        new_extensions.append(ext)
        published_modules.append(module)
        print(f"    Added: {pkg}")

    if not new_extensions:
        print("\nNo extensions were published.")
        return

    # Load existing index.json and merge
    index_file = REPO_DIR / "index.json"
    if index_file.exists():
        with open(index_file) as f:
            try:
                remote_proto = json_format.Parse(f.read(), index_pb2.Index())
                existing = list(remote_proto.extensionList.extensions)
            except Exception:
                existing = []
    else:
        existing = []

    # Remove entries for published modules (being replaced) + orphaned entries
    valid_packages = get_valid_packages()
    published_pkgs = {ext.packageName for ext in new_extensions}

    all_extensions = []
    for ext in existing:
        # Skip if this module is being replaced
        if any(ext.packageName.endswith(f".{module}") for module in published_modules):
            continue
        # Skip if orphaned (not in valid packages and not just published)
        if ext.packageName not in valid_packages and ext.packageName not in published_pkgs:
            print(f"  Removing orphaned: {ext.name} ({ext.packageName})")
            module_suffix = ".".join(ext.packageName.split(".")[-2:])
            for old_apk in apk_dir.glob(f"tachiyomi-{module_suffix}-v*.apk"):
                old_apk.unlink()
            for old_jar in jar_dir.glob(f"tachiyomi-{module_suffix}-v*.jar"):
                old_jar.unlink()
            continue
        all_extensions.append(ext)

    # Add new extensions
    all_extensions.extend(new_extensions)
    all_extensions.sort(key=lambda e: e.packageName)

    # Build the Index proto (with badgeLabel and contact — required by Mihon)
    index = index_pb2.Index(
        name="MarBou",
        badgeLabel="marbou92",
        signingKey=normalize_fingerprint(SIGNING_FINGERPRINT),
        contact=index_pb2.Contact(
            website=f"https://github.com/{SOURCE_OWNER}/{SOURCE_NAME}",
        ),
        extensionList=index_pb2.ExtensionList(extensions=all_extensions),
    )

    # Write index.json (proto-JSON format)
    with open(index_file, "w", encoding="utf-8") as f:
        f.write(
            json_format.MessageToJson(
                index,
                always_print_fields_with_no_presence=True,
                preserving_proto_field_name=True,
            )
        )
    print(f"\nGenerated index.json ({len(all_extensions)} extensions)")

    # Write index.pb (gzip-compressed protobuf — this is what Mihon reads)
    with open(REPO_DIR / "index.pb", "wb") as f:
        f.write(gzip.compress(index.SerializeToString(deterministic=True)))
    print(f"Generated index.pb ({len(all_extensions)} extensions)")

    # Write index.html (listing page)
    with open(REPO_DIR / "index.html", "w", encoding="utf-8") as f:
        f.write('<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n<title>apks</title>\n</head>\n<body>\n<pre>\n')
        for ext in all_extensions:
            apk_escaped = html.escape(ext.resources.apkUrl)
            name_escaped = html.escape(f"Tachiyomi: {ext.name}")
            f.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
        f.write("</pre>\n</body>\n</html>\n")
    print(f"Generated index.html")

    # Write repo.json (points index_v2 to index.pb, like Keiyoushi)
    repo_json = {
        "index_v2": f"https://raw.githubusercontent.com/{REPO_OWNER}/{REPO_NAME}/main/index.pb",
        "meta": {
            "name": "MarBou",
            "website": f"https://github.com/{SOURCE_OWNER}/{SOURCE_NAME}",
            "signingKeyFingerprint": normalize_fingerprint(SIGNING_FINGERPRINT),
        },
    }
    with open(REPO_DIR / "repo.json", "w") as f:
        json.dump(repo_json, f, indent=2)
        f.write("\n")
    print(f"Updated repo.json (index_v2 → index.pb)")

    # Copy static index.min.json (marker file for old Tachiyomi apps)
    static_min = SCRIPT_DIR / "index.min.json"
    if static_min.exists():
        shutil.copy2(static_min, REPO_DIR / "index.min.json")
        print(f"Copied static index.min.json")

    # Commit and push
    git("add", "-A", cwd=REPO_DIR)
    status = git("status", "--porcelain", cwd=REPO_DIR)
    if not status:
        print("\nNo changes to commit.")
        return

    git("commit", "-m", f"chore: publish {len(new_extensions)} extension(s)", cwd=REPO_DIR)
    git("push", cwd=REPO_DIR)

    # Purge CDN cache
    print("\nPurging CDN cache...")
    purge_cdn_cache()

    print(f"\nPublished {len(new_extensions)} extension(s) to MHRepo.")


# =============================================================================
# Main
# =============================================================================

def main():
    parser = argparse.ArgumentParser(description="Publish extensions to MHRepo")
    parser.add_argument("--cleanup", action="store_true")
    parser.add_argument("--dry", action="store_true")
    args = parser.parse_args()

    # Fail fast on a missing/empty/broken token BEFORE any real work — a bad
    # token otherwise only surfaces at the final push, after the whole
    # clone + index build is wasted (public fetches ignore credentials).
    if not REPO_PAT:
        print(
            "::error::REPO_PAT is empty — the secret is not set (or is whitespace "
            f"only). Set it in {SOURCE_OWNER}/{SOURCE_NAME} → Settings → Secrets "
            "and variables → Actions. A classic token with the 'repo' scope is "
            "enough (it must cover MHRepo too)."
        )
        sys.exit(1)
    preflight_token_check()

    if args.cleanup:
        cleanup(dry_run=args.dry)
    else:
        publish()


if __name__ == "__main__":
    main()
