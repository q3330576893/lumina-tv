#!/usr/bin/env python3
"""Fail closed on Release APK, native, R8 and signing regressions (stdlib only)."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile


ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40)}
# Inventory from dev.jdtech.mpv libmpv 1.0.0, FFmpeg decoder 1.6.1+2 and Compose graphics-path.
NATIVE_LIBRARIES = {
    "libandroidx.graphics.path.so", "libavcodec.so", "libavdevice.so",
    "libavfilter.so", "libavformat.so", "libavutil.so", "libc++_shared.so",
    "libffmpegJNI.so", "libmpv.so", "libplayer.so", "libswresample.so",
    "libswscale.so",
}
KEPT_CLASSES = (
    "dev.jdtech.mpv.MPVLib",
    "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer",
    "androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder",
    "androidx.media3.decoder.ffmpeg.FfmpegLibrary",
    "fi.iki.elonen.NanoHTTPD",
)
NATIVE_METHODS = {
    # dev.jdtech 1.0.0 omits nativeSetSurfaceDataSpace (unused here); restore for org.jellycine 1.0.4.
    "dev.jdtech.mpv.MPVLib": {
        "nativeCreate", "nativeInit", "nativeDestroy", "nativeCommand",
        "nativeAttachSurface", "nativeDetachSurface", "nativeObserveProperty",
        "nativeGetPropertyBoolean", "nativeGetPropertyDouble", "nativeGetPropertyInt",
        "nativeGetPropertyString", "nativeSetOptionString", "nativeSetPropertyBoolean",
        "nativeSetPropertyDouble", "nativeSetPropertyInt", "nativeSetPropertyString",
    },
    "androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder": {
        "ffmpegInitialize", "ffmpegDecode", "ffmpegRelease", "ffmpegReset",
        "ffmpegGetChannelCount", "ffmpegGetSampleRate",
    },
    "androidx.media3.decoder.ffmpeg.FfmpegLibrary": {
        "ffmpegGetVersion", "ffmpegGetInputBufferPaddingSize", "ffmpegHasDecoder",
    },
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run_tool(path, *args):
    command = [str(path), *map(str, args)]
    if os.name == "nt" and path.suffix.lower() in (".bat", ".cmd"):
        command = ["cmd.exe", "/d", "/c", *command]
    return subprocess.run(command, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", check=False)


def has_apk_signing_block(apk):
    # APK v2/v3 signatures sit immediately before the ZIP central directory.
    with apk.open("rb") as handle:
        handle.seek(max(0, apk.stat().st_size - 65557))
        tail = handle.read()
        eocd = tail.rfind(b"PK\x05\x06")
        require(eocd >= 0, "Missing ZIP end-of-central-directory record")
        central_offset = struct.unpack_from("<I", tail, eocd + 16)[0]
        require(central_offset >= 16, "Invalid APK central directory offset")
        handle.seek(central_offset - 16)
        return handle.read(16) == b"APK Sig Block 42"


def dex_definitions(data):
    """Read class_data definitions, not merely referenced DEX type/string names."""
    require(data[:4] == b"dex\n" and data[7] == 0, "Invalid DEX header")
    u4 = lambda offset: struct.unpack_from("<I", data, offset)[0]

    def uleb(offset):
        result = shift = 0
        for _ in range(5):
            byte = data[offset]
            offset += 1
            result |= (byte & 127) << shift
            if not byte & 128:
                return result, offset
            shift += 7
        raise ValueError("Invalid DEX ULEB128")

    strings = []
    for i in range(u4(56)):
        _, start = uleb(u4(u4(60) + 4 * i))
        strings.append(data[start:data.index(b"\0", start)].decode("utf-8", "replace"))
    types = [strings[u4(u4(68) + i * 4)] for i in range(u4(64))]
    wanted = {"L" + name.replace(".", "/") + ";": name for name in KEPT_CLASSES}
    found = {}
    for i in range(u4(96)):
        offset = u4(100) + i * 32
        name = wanted.get(types[u4(offset)])
        if name is None:
            continue
        methods = []
        cursor = u4(offset + 24)
        if cursor:
            counts = []
            for _ in range(4):
                count, cursor = uleb(cursor)
                counts.append(count)
            for _ in range(counts[0] + counts[1]):
                _, cursor = uleb(cursor)  # field index delta
                _, cursor = uleb(cursor)  # field access flags
            for count in counts[2:]:  # direct and virtual method index deltas reset
                index = 0
                for _ in range(count):
                    delta, cursor = uleb(cursor)
                    index += delta
                    flags, cursor = uleb(cursor)
                    _, cursor = uleb(cursor)  # code offset; native methods have no code
                    method_offset = u4(92) + index * 8
                    method_name = strings[u4(method_offset + 4)]
                    proto_index = struct.unpack_from("<H", data, method_offset + 2)[0]
                    proto_offset = u4(76) + proto_index * 12
                    params_offset = u4(proto_offset + 8)
                    params = [] if not params_offset else [
                        types[struct.unpack_from("<H", data, params_offset + 4 + j * 2)[0]]
                        for j in range(u4(params_offset))
                    ]
                    signature = "(" + "".join(params) + ")" + types[u4(proto_offset + 4)]
                    methods.append((method_name, flags, signature))
        found[name] = methods
    return found


def check_r8(mapping_dir, definitions):
    files = {name: mapping_dir / (name + ".txt")
             for name in ("mapping", "configuration", "seeds", "usage")}
    for path in files.values():
        require(path.is_file() and path.stat().st_size > 0, f"Missing R8 output: {path}")
    classes = {}
    compiler = False
    with files["mapping"].open(encoding="utf-8") as handle:
        for line in handle:
            compiler |= line.startswith("# compiler: R8")
            if line and not line.startswith((" ", "#")) and " -> " in line:
                original, renamed = line.rstrip().removesuffix(":").split(" -> ", 1)
                classes[original] = renamed
    require(compiler, "Mapping is not produced by R8")
    require(any(k.startswith("com.lumina.tv.") and k != v for k, v in classes.items()),
            "No obfuscated application classes: Release minification did not run")
    config = files["configuration"].read_text(encoding="utf-8")
    require(not re.search(r"(?m)^\s*-(dontshrink|dontobfuscate)\s*$", config),
            "Release R8 shrinking/obfuscation is disabled")
    for rule in (r"-keep\s+class\s+dev\.jdtech\.mpv\.MPVLib\s*\{\s*\*;\s*\}",
                 r"-keep\s+class\s+androidx\.media3\.decoder\.ffmpeg\.\*\*\s*\{\s*\*;\s*\}"):
        require(re.search(rule, config), "Required JNI/reflection keep rule is missing")
    seeds = set(files["seeds"].read_text(encoding="utf-8").splitlines())
    for name in KEPT_CLASSES:
        require(classes.get(name) == name, f"Required class removed or renamed in mapping: {name}")
        require(name in seeds, f"Required class absent from R8 seeds: {name}")
        require(name in definitions, f"Required class not DEFINED in APK DEX: {name}")
    for name, expected in NATIVE_METHODS.items():
        actual = {method for method, flags, _ in definitions[name] if flags & 0x100}
        require(expected == actual, f"JNI method inventory mismatch for {name}: "
                f"missing={sorted(expected - actual)}, unexpected={sorted(actual - expected)}")
    for name, expected in {
        "dev.jdtech.mpv.MPVLib": {"event", "eventProperty", "logMessage"},
        "androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder": {"growOutputBuffer"},
    }.items():
        actual = {method for method, _, _ in definitions[name]}
        require(expected <= actual, f"Native callback removed/renamed: {name}")
    renderer = definitions["androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer"]
    # Parameter types may be renamed safely: reflection supplies their Class objects.
    parameters = ("android.os.Handler", "androidx.media3.exoplayer.audio.AudioRendererEventListener",
                  "androidx.media3.exoplayer.audio.AudioSink")
    constructor = "(" + "".join("L" + classes.get(p, p).replace(".", "/") + ";"
                                for p in parameters) + ")V"
    require(any(method == "<init>" and flags & 1 and signature == constructor
                for method, flags, signature in renderer),
            "FFmpeg reflection constructor removed or changed")
    return {"compiler": "R8", "app_obfuscated": True, "kept_classes": list(KEPT_CLASSES),
            "native_methods": {name: sorted(method for method, flags, _ in definitions[name]
                                              if flags & 0x100) for name in NATIVE_METHODS},
            "reflection_constructor": "present", "native_callbacks": "present"}


def check(args):
    apk = args.apk
    require(apk.is_file(), f"Expected Release APK is missing: {apk}")
    require(apk.parent.name == "release", "Only APKs in the Release output directory are accepted")
    expected_file = "app-release-unsigned.apk" if args.mode == "unsigned" else "app-release.apk"
    require(apk.name == expected_file, "Unexpected APK name; refusing Debug/unsigned fallback")
    gradle = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    require(re.search(r'versionName\s*=\s*"' + re.escape(args.expected_version_name) + '"', gradle),
            "Expected versionName differs from Gradle")
    require(re.search(r"versionCode\s*=\s*" + str(args.expected_version_code) + r"\b", gradle),
            "Expected versionCode differs from Gradle")
    require(re.search(r"isMinifyEnabled\s*=\s*true", gradle), "Release minification must be enabled")
    ref = os.environ.get("GITHUB_REF", "")
    tag = args.tag or (ref.removeprefix("refs/tags/") if ref.startswith("refs/tags/") else "")
    require(not tag or tag == "v" + args.expected_version_name, "Tag does not match versionName")
    metadata = json.loads((apk.parent / "output-metadata.json").read_text(encoding="utf-8"))
    require(metadata["variantName"] == "release" and len(metadata["elements"]) == 1,
            "Expected one universal Release artifact")
    element = metadata["elements"][0]
    require(element["outputFile"] == apk.name and not element["filters"], "Wrong Release output metadata")
    require((element["versionName"], element["versionCode"]) ==
            (args.expected_version_name, args.expected_version_code), "Metadata version mismatch")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(args.build_tools or sdk, "Specify --build-tools or ANDROID_HOME")
    tools = args.build_tools or Path(sdk) / "build-tools" / "35.0.0"
    aapt = tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
    signer = tools / ("apksigner.bat" if os.name == "nt" else "apksigner")
    badging = run_tool(aapt, "dump", "badging", apk)
    require(badging.returncode == 0, "aapt2 could not read APK manifest")
    version = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",
                        badging.stdout, re.MULTILINE)
    require(version and version.groups() == ("com.lumina.tv", str(args.expected_version_code),
                                             args.expected_version_name), "APK manifest version/package mismatch")
    require("application-debuggable" not in badging.stdout, "Debuggable APK is forbidden")
    inventory, definitions = {}, {}
    with zipfile.ZipFile(apk) as archive:
        require(archive.testzip() is None, "Corrupt APK ZIP entry")
        entries = archive.namelist()
        require(len(entries) == len(set(entries)), "Duplicate APK ZIP paths")
        for entry in entries:
            if entry.endswith(".so"):
                parts = entry.split("/")
                require(len(parts) == 3 and parts[0] == "lib" and parts[1] in ABIS,
                        f"Unexpected native path/ABI: {entry}")
                abi, library = parts[1:]
                data = archive.read(entry)
                require(len(data) >= 64 and data[:4] == b"\x7fELF" and data[5] == 1,
                        f"Invalid native ELF: {entry}")
                require((data[4], struct.unpack_from("<H", data, 18)[0]) == ABIS[abi],
                        f"ELF architecture does not match directory: {entry}")
                inventory.setdefault(abi, []).append(library)
            elif re.fullmatch(r"classes(?:\d+)?\.dex", entry):
                current = dex_definitions(archive.read(entry))
                require(not definitions.keys() & current.keys(), "Duplicate required DEX classes")
                definitions.update(current)
        require(set(inventory) == set(ABIS), "ABI inventory differs from allowlist")
        for abi, libraries in inventory.items():
            require(set(libraries) == NATIVE_LIBRARIES, f"Native library inventory mismatch for {abi}")
        v1_signature = any(re.fullmatch(r"META-INF/[^/]+\.(RSA|DSA|EC|SF)", n, re.I) for n in entries)
    r8 = check_r8(Path("app/build/outputs/mapping/release"), definitions)
    certificate = run_tool(signer, "verify", "--verbose", "--print-certs", apk)
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})",
                       certificate.stdout)
    if args.mode == "unsigned":
        # A failing verifier alone is insufficient: require the specific unsigned diagnostic.
        require(certificate.returncode != 0 and not certs and not v1_signature and
                not has_apk_signing_block(apk) and
                "Missing META-INF/MANIFEST.MF" in certificate.stdout + certificate.stderr,
                "APK is signed or signature verification failed for a reason other than unsigned APK")
    else:
        require(certificate.returncode == 0 and len(certs) == 1, "Signed APK verification failed")
        pin = os.environ.get("LUMINA_CERT_SHA256", "").replace(":", "").lower()
        require(re.fullmatch(r"[0-9a-f]{64}", pin), "Missing/invalid expected certificate SHA-256")
        require(certs[0].lower() == pin, "APK signing certificate does not match the pinned certificate")
    with apk.open("rb") as handle:
        digest = hashlib.file_digest(handle, "sha256").hexdigest()
    apk.with_name(apk.name + ".sha256").write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
    return {"status": "pass", "mode": args.mode, "apk": apk.as_posix(),
            "versionName": args.expected_version_name, "versionCode": args.expected_version_code,
            "tag": tag or None, "debuggable": False,
            "native": {abi: sorted(libs) for abi, libs in sorted(inventory.items())},
            "r8": r8, "sha256": digest,
            "certificate_sha256": certs[0].lower() if certs else None,
            "signature": "verified" if certs else "asserted unsigned"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", required=True, choices=("unsigned", "signed"))
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--expected-version-name", required=True)
    parser.add_argument("--expected-version-code", required=True, type=int)
    parser.add_argument("--tag", default="")
    parser.add_argument("--build-tools", type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = check(args)
    except (ValueError, OSError, KeyError, IndexError, struct.error, zipfile.BadZipFile) as error:
        print(json.dumps({"status": "fail", "error": str(error)}))
        return 1
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
