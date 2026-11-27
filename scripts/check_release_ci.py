#!/usr/bin/env python3
"""Assert CI trust boundaries; materialize a keystore only inside a tag release job."""

import argparse
import base64
import binascii
import json
import os
from pathlib import Path
import re
import sys


RELEASE_IF = "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')"
SIGNING_SECRETS = (
    "LUMINA_KEYSTORE_BASE64", "LUMINA_STORE_PASSWORD", "LUMINA_KEY_ALIAS",
    "LUMINA_KEY_PASSWORD", "LUMINA_CERT_SHA256",
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def release_allowed(event, ref):
    return event == "push" and ref.startswith("refs/tags/v")


def validate_signing(env):
    require(release_allowed(env.get("GITHUB_EVENT_NAME"), env.get("GITHUB_REF", "")),
            "Signing is restricted to push events on refs/tags/v*; no Debug fallback")
    require(env.get("GITHUB_REF") == "refs/tags/v" + env.get("EXPECTED_VERSION_NAME", ""),
            "Version tag mismatch; refusing to sign")
    missing = [name for name in SIGNING_SECRETS if not env.get(name, "").strip()]
    require(not missing, "Missing signing secrets: " + ", ".join(missing) +
            "; signed Release is blocked; no unsigned or Debug fallback")
    require(re.fullmatch(r"[0-9a-fA-F]{64}", env["LUMINA_CERT_SHA256"].replace(":", "")),
            "Invalid certificate SHA-256 pin")


def preflight():
    validate_signing(os.environ)
    gradle = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
    code = re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1)
    require((name, code) == (os.environ.get("EXPECTED_VERSION_NAME"),
                             os.environ.get("EXPECTED_VERSION_CODE")),
            "Expected version does not match Gradle")
    try:
        # Decode directly in memory; never print encoded or decoded signing material.
        encoded = "".join(os.environ["LUMINA_KEYSTORE_BASE64"].split())
        material = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError):
        raise ValueError("Invalid keystore base64; signed Release is blocked") from None
    require(len(material) > 32, "Keystore is empty/invalid; signed Release is blocked")
    directory = Path(os.environ["RUNNER_TEMP"]).resolve()
    target = directory / "lumina-release.jks"
    # Exclusive creation and owner-only permissions; cleanup runs with always() in CI.
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as handle:
        handle.write(material)
    return {"status": "pass", "signing_preflight": "all required secrets present; tag/version match",
            "fallback": "forbidden"}


def check_workflow():
    import yaml  # Only static workflow checks require PyYAML; signing uses the stdlib.

    path = Path(".github/workflows/build-and-release.yml")
    workflow = yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)
    require(workflow["permissions"] == {"contents": "read"}, "Default token must be read-only")
    triggers = workflow["on"]
    require(set(triggers) == {"pull_request", "push", "workflow_dispatch"}, "Unexpected event trigger")
    require(triggers["push"] == {"branches": ["main"], "tags": ["v*"]}, "Unexpected push scope")
    jobs = workflow["jobs"]
    require(set(jobs) == {"verify", "release"}, "Unexpected jobs need a permissions review")
    verify, release = jobs["verify"], jobs["release"]
    require(verify.get("permissions", workflow["permissions"]) == {"contents": "read"},
            "PR/unsigned job must not have write permissions")
    require("if" not in verify, "All events, including PRs, must build unsigned Release")
    require(release["permissions"] == {"contents": "write"} and release["needs"] == "verify",
            "Only the release job may publish after unsigned verification")
    require(release["if"] == RELEASE_IF, "Signing must be restricted to version tag pushes")
    require(release["environment"] == "release", "Release environment protection is required")
    require("secrets." not in json.dumps(verify) and "secrets." not in json.dumps(workflow.get("env", {})),
            "Secrets must never enter PR/unsigned configuration")
    unsigned_runs = "\n".join(s.get("run", "") for s in verify["steps"])
    signed_runs = "\n".join(s.get("run", "") for s in release["steps"])
    require("-PreleaseSigning=false :app:verifyOffline :app:assembleRelease" in unsigned_runs,
            "PRs must compile unsigned minified Release and run the existing gate")
    require("--mode unsigned" in unsigned_runs and "app-release-unsigned.apk" in unsigned_runs,
            "Missing unsigned artifact checks")
    require("-PreleaseSigning=true :app:assembleRelease" in signed_runs and "--mode signed" in signed_runs,
            "Missing signed Release build/check")
    for job in jobs.values():
        for step in job["steps"]:
            require(not step.get("continue-on-error"), "Release checks cannot be advisory")
            run = step.get("run", "")
            require(not re.search(r"assembleDebug|bundleDebug|\|\|\s*true|set\s+-x", run),
                    "Debug fallback, swallowed failure or shell tracing is forbidden")
            if "uses" in step:
                require(re.fullmatch(r"[\w.-]+/[\w./-]+@[0-9a-f]{40}", step["uses"]),
                        "Actions must use verified immutable commit SHAs")
            if step.get("uses", "").startswith("actions/checkout@"):
                require(step["with"]["persist-credentials"] == "false", "Do not persist git credentials")
            if step.get("uses", "").startswith("actions/upload-artifact@"):
                require(step["with"]["if-no-files-found"] == "error", "Missing artifacts must fail")
    steps = release["steps"]
    gate = next(i for i, step in enumerate(steps) if "check_release_ci.py preflight" in step.get("run", ""))
    build = next(i for i, step in enumerate(steps) if "-PreleaseSigning=true" in step.get("run", ""))
    check = next(i for i, step in enumerate(steps) if "--mode signed" in step.get("run", ""))
    publish = next(i for i, step in enumerate(steps)
                   if step.get("uses", "").startswith("actions/github-script@"))
    require(gate < build < check < publish, "Secret preflight and signed checks must precede publishing")
    require(set(steps[gate]["env"]) == set(SIGNING_SECRETS), "All signing secrets must be explicitly gated")
    for name in SIGNING_SECRETS:
        require(steps[gate]["env"][name] == "${{ secrets." + name + " }}", "Unexpected secret source")
    require(steps[-1].get("if") == "always()" and
            'rm -f -- "$RUNNER_TEMP/lumina-release.jks"' in steps[-1].get("run", ""), "Keystore cleanup is mandatory")
    # Exercise every trigger shape with synthetic inputs only, never real local secrets.
    matrix = []
    for event, ref, expected in (
        ("pull_request", "refs/pull/13/merge", False),
        ("pull_request", "refs/tags/v1.24.19", False),
        ("pull_request_target", "refs/tags/v1.24.19", False),
        ("push", "refs/heads/main", False),
        ("push", "refs/heads/v1.24.19", False),
        ("push", "refs/tags/other", False),
        ("workflow_dispatch", "refs/tags/v1.24.19", False),
        ("push", "refs/tags/v1.24.19", True),
    ):
        require(release_allowed(event, ref) == expected, "Release trigger scenario failed")
        matrix.append({"event": event, "ref": ref, "signing_allowed": expected})
    synthetic = {name: "synthetic-not-a-secret" for name in SIGNING_SECRETS}
    synthetic.update(GITHUB_EVENT_NAME="push", GITHUB_REF="refs/tags/v1.24.19",
                     EXPECTED_VERSION_NAME="1.24.19", LUMINA_CERT_SHA256="a" * 64)
    validate_signing(synthetic)
    blocked = []
    for missing in SIGNING_SECRETS:
        case = {**synthetic, missing: ""}
        try:
            validate_signing(case)
        except ValueError as error:
            require(missing in str(error) and "no unsigned or Debug fallback" in str(error),
                    "Missing secret failed without the explicit no-fallback assertion")
            blocked.append(missing)
        else:
            raise ValueError("Missing secret was incorrectly accepted")
    name = workflow["env"]["EXPECTED_VERSION_NAME"]
    code = workflow["env"]["EXPECTED_VERSION_CODE"]
    gradle = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    require(re.search(r'versionName\s*=\s*"' + re.escape(name) + '"', gradle) and
            re.search(r"versionCode\s*=\s*" + re.escape(code) + r"\b", gradle),
            "CI expected version differs from Gradle")
    return {"status": "pass", "yaml": "parsed", "default_permissions": "contents:read",
            "write_job": "release only", "event_matrix": matrix,
            "missing_secret_blocks": blocked, "debug_fallback": "forbidden",
            "actions": "immutable SHAs; owner refs must be verified when updating"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("workflow", "preflight"))
    args = parser.parse_args()
    try:
        result = check_workflow() if args.command == "workflow" else preflight()
    except (ValueError, OSError, KeyError, ImportError, StopIteration) as error:
        print(json.dumps({"status": "fail", "error": str(error)}))
        return 1
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
