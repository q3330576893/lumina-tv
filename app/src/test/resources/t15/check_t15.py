"""Read-only T15 evidence checks. A PASS does not clear the native source gate.

Run from the repository root with Python 3.10+ and Android build-tools aapt2.
Use --require-source-closure to reject recorded BLOCKED rows (exit code 2).
No Gradle configuration, dependency, version, archive or source file is changed.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def validate_manifest(data, actual, expected_version):
    require(data["version"]["versionCode"] == expected_version["versionCode"], "wrong versionCode")
    require(data["version"]["versionName"] == expected_version["versionName"], "wrong versionName")
    rows = data["native_sources"]
    by_name = {row["so"]: row for row in rows}
    require(len(by_name) == len(rows), "duplicate native row")
    require(set(by_name) == set(actual), "missing or unexpected native file")
    for name, row in by_name.items():
        require(re.fullmatch(r"[0-9a-f]{64}", row["sha256"]), f"invalid SHA-256: {name}")
        require(row["sha256"] == actual[name], f"wrong native hash: {name}")
        for key in ("upstream", "revision", "build_options", "patches", "license", "source_urls", "aar_matches"):
            require(bool(row.get(key)), f"missing {key}: {name}")
        require(row["status"] in ("VERIFIED", "BLOCKED"), f"invalid native status: {name}")
        if row["status"] == "VERIFIED":
            require(row.get("binary_source_binding") is True, f"false VERIFIED: {name}")
            require(row.get("build_proof_url"), f"missing build proof: {name}")
            require("未找到上游证据" not in row["revision"], f"unproven revision marked VERIFIED: {name}")
    require(data["source_gate"] == ("BLOCKED" if any(r["status"] == "BLOCKED" for r in rows) else "VERIFIED"), "aggregate gate mismatch")


def negative_checks(data, actual, expected_version):
    cases = []
    missing = copy.deepcopy(data)
    missing["native_sources"].pop()
    cases.append(("missing native file", missing))
    wrong_hash = copy.deepcopy(data)
    wrong_hash["native_sources"][0]["sha256"] = "0" * 64
    cases.append(("wrong native SHA-256", wrong_hash))
    wrong_version = copy.deepcopy(data)
    wrong_version["version"]["versionName"] = "0.0.0"
    cases.append(("wrong version", wrong_version))
    false_verified = copy.deepcopy(data)
    false_verified["native_sources"][0]["status"] = "VERIFIED"
    false_verified["native_sources"][0]["binary_source_binding"] = False
    cases.append(("unsupported VERIFIED", false_verified))
    for label, mutated in cases:
        try:
            validate_manifest(mutated, actual, expected_version)
        except AssertionError:
            continue
        raise AssertionError(f"negative check was not rejected: {label}")
    return [label for label, _ in cases]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aapt2", type=Path)
    parser.add_argument("--require-source-closure", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[5]
    fixture = json.loads(Path(__file__).with_name("expected.json").read_text(encoding="utf-8"))
    data = json.loads((root / "docs/native-sources.json").read_text(encoding="utf-8"))
    license_evidence = json.loads(Path(__file__).with_name("license-evidence.json").read_text(encoding="utf-8"))
    for record in license_evidence["pom_declarations"]:
        require(digest(record["raw_pom"].encode("utf-8")) == record["pom_sha256"], f"POM snapshot changed: {record['coordinate']}")
        pom = ET.fromstring(record["raw_pom"])
        ns = {"p": "http://maven.apache.org/POM/4.0.0"}
        declarations = [{"name": e.findtext("p:name", namespaces=ns), "url": e.findtext("p:url", namespaces=ns)} for e in pom.findall("p:licenses/p:license", ns)]
        require(declarations == record["licenses"], f"POM license declaration changed: {record['coordinate']}")
    version = fixture["version"]
    gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    require(re.search(rf"\bversionCode\s*=\s*{version['versionCode']}\b", gradle), "Gradle versionCode changed")
    require(re.search(rf'\bversionName\s*=\s*"{re.escape(version["versionName"])}"', gradle), "Gradle versionName changed")
    readme = (root / "README.md").read_text(encoding="utf-8")
    require(f"v{version['versionName']}（versionCode {version['versionCode']}" in readme, "README current version mismatch")
    changelog = (root / "CHANGELOG.md").read_text(encoding="utf-8")
    require(f"## 当前工作区：v{version['versionName']}（versionCode {version['versionCode']}）" in changelog, "CHANGELOG current version mismatch")
    history = changelog[changelog.index(fixture["history_heading"]):]
    require(digest(history.encode("utf-8")) == fixture["historical_changelog_sha256"], "historical CHANGELOG was rewritten")
    require(digest((root / "LICENSE").read_bytes()) == fixture["license_file_sha256"], "project LICENSE changed")
    about = (root / "app/src/main/java/com/lumina/tv/ui/screens/settings/SettingsAbout.kt").read_text(encoding="utf-8")
    require("Text(BuildConfig.VERSION_NAME," in about, "About version is not generated from BuildConfig")
    require("Uri.parse(notice.source)" in about, "license source action is missing")
    notices = re.findall(r'AboutLicenseNotice\("([^"]+)", "([^"]+)", "(https://[^"]+)"\)', about)
    require(len(notices) >= 15, "license notices are missing")
    for library, license_text, source in notices:
        require(re.search(r"Apache|MIT|BSD|GPL|待核实", license_text), f"unknown license not disclosed: {library}")
    nano = [r for r in notices if r[0].startswith("NanoHTTPD")]
    require(len(nano) == 1 and "BSD-3-Clause" in nano[0][1], "NanoHTTPD license regression")
    require("待核实" in about and "BLOCKED" in about, "unresolved native source status hidden")

    aapt = args.aapt2 or (Path(shutil.which("aapt2")) if shutil.which("aapt2") else None)
    require(aapt is not None and aapt.is_file(), "pass --aapt2 /path/to/aapt2[.exe]")
    expected_native = {row["so"]: row["sha256"] for row in data["native_sources"]}
    reports = []
    actual = None
    for artifact in data["artifacts"]:
        apk = root / artifact["apk"]
        require(apk.is_file(), f"APK unavailable: {artifact['apk']}")
        require(digest(apk.read_bytes()) == artifact["sha256"], f"APK snapshot changed: {artifact['apk']}; re-audit before updating hashes")
        proc = subprocess.run([str(aapt), "dump", "badging", str(apk)], capture_output=True, encoding="utf-8")
        require(proc.returncode == 0, f"aapt2 failed: {proc.stderr}")
        package = next((line for line in proc.stdout.splitlines() if line.startswith("package:")), "")
        require(f"versionCode='{version['versionCode']}'" in package, "APK versionCode mismatch")
        require(f"versionName='{version['versionName']}'" in package, "APK versionName mismatch")
        require(package == artifact["badging_package_line"], "recorded badging differs from actual output")
        with zipfile.ZipFile(apk) as archive:
            actual = {name: digest(archive.read(name)) for name in archive.namelist() if name.endswith(".so")}
        require(len(actual) == fixture["native_count"], "unexpected native count")
        require(actual == expected_native, "native files/hash differ from actual APK")
        validate_manifest(data, actual, version)
        reports.append({"apk":artifact["apk"],"sha256":artifact["sha256"],"badging_package_line":package,"native_count":len(actual)})
    require(actual is not None, "no APKs checked")
    rejected = negative_checks(data, actual, version)
    blocked = [row["so"] for row in data["native_sources"] if row["status"] == "BLOCKED"]
    print(json.dumps({"task":"V6-T15","fixture_check":"PASS","version":version,"artifacts":reports,"negative_checks_rejected":rejected,"pom_snapshots_checked":len(license_evidence["pom_declarations"]),"historical_changelog":"UNCHANGED","project_license":"UNCHANGED","source_gate":data["source_gate"],"blocked_native_count":len(blocked),"release_authorized":False}, ensure_ascii=False, indent=2))
    return 2 if args.require_source_closure and blocked else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, OSError, ValueError, KeyError) as error:
        print(json.dumps({"task":"V6-T15","fixture_check":"FAIL","error":str(error)}, ensure_ascii=False))
        sys.exit(1)
