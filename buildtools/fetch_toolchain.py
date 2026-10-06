#!/usr/bin/env python3
"""Downloads every tool and library needed to build Local Media Tools without the
Android SDK manager or the Android Gradle Plugin.

Everything is pinned by URL and SHA-256, so a fresh checkout produces the same
toolchain. Files land in LocalMediaTools/.toolchain (git-ignored).

Sources:
  * android.jar (API 35)      - Sable/android-platforms mirror of the SDK platform jar
  * aapt2 (linux x64)         - npm package "aaptjs3" (bundles Google's prebuilt aapt2)
  * D8 dexer                  - facebook/buck vendored d8.jar (+ its plain Maven deps)
  * apksigner / apksig        - Ubuntu archive packages
  * Kotlin compiler + libs    - Maven Central (Google Cloud mirror)
  * MobileNet-V3 embedder     - MediaPipe model bucket (on-device AI alignment assist)
"""
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TC = os.path.join(ROOT, ".toolchain")
MAVEN = "https://maven-central.storage-download.googleapis.com/maven2"
MAVEN_FALLBACK = "https://repo1.maven.org/maven2"
UBUNTU = "https://archive.ubuntu.com/ubuntu"

KOTLIN = "2.3.21"
COROUTINES = "1.10.2"


def mvn(group, artifact, version, ext="jar", classifier=None):
    g = group.replace(".", "/")
    name = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + f".{ext}"
    return f"{MAVEN}/{g}/{artifact}/{version}/{name}", name


# (destination relative to .toolchain, url, sha256 or None)
ARTIFACTS = []


def add(dest, url, sha=None, extract=None):
    ARTIFACTS.append({"dest": dest, "url": url, "sha": sha, "extract": extract})


# --- Android platform -------------------------------------------------------
add("android/android.jar",
    "https://raw.githubusercontent.com/Sable/android-platforms/master/android-35/android.jar",
    "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b")

# --- aapt2 --------------------------------------------------------------------
add("android/aapt2",
    "https://registry.npmjs.org/aaptjs3/-/aaptjs3-2.0.2.tgz",
    None, extract=("tgz", "package/bin/x64/linux/aapt2"))

# --- D8 -----------------------------------------------------------------------
add("d8/d8.jar", "https://raw.githubusercontent.com/facebook/buck/main/third-party/java/d8/d8.jar")
for g, a, v in [
    ("org.ow2.asm", "asm", "9.7.1"),
    ("org.ow2.asm", "asm-commons", "9.7.1"),
    ("org.ow2.asm", "asm-tree", "9.7.1"),
    ("org.ow2.asm", "asm-analysis", "9.7.1"),
    ("org.ow2.asm", "asm-util", "9.7.1"),
    ("com.google.guava", "guava", "23.3-jre"),
    ("it.unimi.dsi", "fastutil", "7.2.0"),
    ("net.sf.jopt-simple", "jopt-simple", "4.6"),
    ("com.googlecode.json-simple", "json-simple", "1.1.1"),
    ("org.apache.commons", "commons-compress", "1.18"),
    ("org.jetbrains.kotlinx", "kotlinx-metadata-jvm", "0.1.0"),
    ("com.google.code.gson", "gson", "2.8.6"),
]:
    url, name = mvn(g, a, v)
    add(f"d8/{name}", url)

# --- apksigner ----------------------------------------------------------------
add("apksigner/apksigner.jar",
    f"{UBUNTU}/pool/universe/a/android-platform-tools-apksig/apksigner_31.0.2-1ubuntu1_all.deb",
    "37a04ceb8c80b6956dd702b99d3a6b7cae1c0e8085d1334adc77b8f92a3117e6",
    extract=("deb", "usr/share/java/apksigner.jar"))
add("apksigner/apksig.jar",
    f"{UBUNTU}/pool/universe/a/android-platform-tools-apksig/libapksig-java_31.0.2-1ubuntu1_all.deb",
    "35a70af2666f09402034dc5d154ddbc84819e58b0f8ac60b311594bc5f7ecfc1",
    extract=("deb", "usr/share/java/apksig.jar"))

# --- Kotlin compiler ----------------------------------------------------------
for g, a, v in [
    ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", KOTLIN),
    ("org.jetbrains.kotlin", "kotlin-stdlib", KOTLIN),
    ("org.jetbrains.kotlin", "kotlin-script-runtime", KOTLIN),
    ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
    ("org.jetbrains.kotlin", "kotlin-daemon-embeddable", KOTLIN),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
    ("org.jetbrains", "annotations", "13.0"),
]:
    url, name = mvn(g, a, v)
    add(f"kotlinc/{name}", url)

# --- Runtime libraries packaged into the APK -----------------------------------
for g, a, v, ext in [
    ("org.jetbrains.kotlin", "kotlin-stdlib", KOTLIN, "jar"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", COROUTINES, "jar"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android", COROUTINES, "jar"),
    ("org.opencv", "opencv", "4.12.0", "aar"),
    ("com.tom-roush", "pdfbox-android", "2.0.27.0", "aar"),
    ("org.tensorflow", "tensorflow-lite", "2.16.1", "aar"),
    ("org.tensorflow", "tensorflow-lite-api", "2.16.1", "aar"),
]:
    url, name = mvn(g, a, v, ext)
    add(f"libs/{name}", url)

# --- Unit-test libraries (JVM) ---------------------------------------------------
for g, a, v in [
    ("junit", "junit", "4.13.2"),
    ("org.hamcrest", "hamcrest-core", "1.3"),
    ("org.apache.pdfbox", "pdfbox", "2.0.32"),
    ("org.apache.pdfbox", "fontbox", "2.0.32"),
    ("commons-logging", "commons-logging", "1.2"),
]:
    url, name = mvn(g, a, v)
    add(f"test/{name}", url)

# --- On-device AI model ---------------------------------------------------------
add("models/mobilenet_v3_small_embedder.tflite",
    "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/latest/mobilenet_v3_small.tflite")

# Pinned hashes are stored next to this script once computed.
PINS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "toolchain.sha256")


def load_pins():
    pins = {}
    if os.path.exists(PINS):
        with open(PINS) as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#"):
                    sha, dest = line.split(None, 1)
                    pins[dest] = sha
    return pins


def download(url):
    last = None
    urls = [url]
    if url.startswith(MAVEN):
        urls.append(url.replace(MAVEN, MAVEN_FALLBACK))
    for u in urls:
        for attempt in range(4):
            try:
                with urllib.request.urlopen(u, timeout=120) as r:
                    return r.read()
            except Exception as e:  # noqa: BLE001 - report and retry
                last = e
    raise RuntimeError(f"download failed: {url}: {last}")


def extract_member(data, kind, member):
    if kind == "tgz":
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as t:
            return t.extractfile(member).read()
    if kind == "deb":
        with tempfile.TemporaryDirectory() as tmp:
            deb = os.path.join(tmp, "p.deb")
            with open(deb, "wb") as f:
                f.write(data)
            out = os.path.join(tmp, "x")
            subprocess.run(["dpkg-deb", "-x", deb, out], check=True)
            path = os.path.join(out, member)
            # Debian ships versioned jars behind symlinks.
            path = os.path.realpath(path)
            with open(path, "rb") as f:
                return f.read()
    raise ValueError(kind)


def main():
    pins = load_pins()
    computed = {}
    os.makedirs(TC, exist_ok=True)
    for art in ARTIFACTS:
        dest = os.path.join(TC, art["dest"])
        expected = art["sha"] or pins.get(art["dest"])
        if os.path.exists(dest):
            have = hashlib.sha256(open(dest, "rb").read()).hexdigest()
            if expected is None or have == expected:
                computed[art["dest"]] = have
                continue
        print(f"fetch {art['dest']}", flush=True)
        data = download(art["url"])
        if art["extract"]:
            if art["sha"]:
                got = hashlib.sha256(data).hexdigest()
                if got != art["sha"]:
                    sys.exit(f"checksum mismatch for archive of {art['dest']}: {got}")
                expected = pins.get(art["dest"])
            data = extract_member(data, *art["extract"])
        got = hashlib.sha256(data).hexdigest()
        if expected and got != expected and not art["extract"]:
            sys.exit(f"checksum mismatch for {art['dest']}: expected {expected}, got {got}")
        if expected and art["extract"] and pins.get(art["dest"]) and got != pins[art["dest"]]:
            sys.exit(f"checksum mismatch for {art['dest']}: expected {pins[art['dest']]}, got {got}")
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as f:
            f.write(data)
        if dest.endswith("aapt2"):
            os.chmod(dest, 0o755)
        computed[art["dest"]] = got
    if not os.path.exists(PINS) or "--repin" in sys.argv:
        with open(PINS, "w") as f:
            f.write("# sha256  path-under-.toolchain (generated by fetch_toolchain.py)\n")
            for k in sorted(computed):
                f.write(f"{computed[k]}  {k}\n")
        print(f"wrote {PINS}")
    print("toolchain ready:", TC)


if __name__ == "__main__":
    main()
