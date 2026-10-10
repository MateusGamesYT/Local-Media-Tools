#!/usr/bin/env python3
"""Builds the Local Media Tools APK without the Android Gradle Plugin.

Pipeline:  aapt2 compile/link  ->  javac (R)  ->  kotlinc  ->  bytecode sanitizer  ->  D8
           ->  aligned APK assembly  ->  apksigner (v1 + v2 + v3)

Usage:
  python3 buildtools/build_apk.py            # release-style build (signed with the sideload key)
  python3 buildtools/build_apk.py --test     # also compile + run the JVM unit tests first
  python3 buildtools/build_apk.py --robo-test  # build, then run the Robolectric (Android framework) tests
  --test-only / --robo-test-only run one kind of test without packaging; --only=Name runs the test
  classes whose name contains Name.
"""
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import time
import zipfile
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TC = os.path.join(ROOT, ".toolchain")
BUILD = os.path.join(ROOT, "build")
APP = os.path.join(ROOT, "app")
SRC = os.path.join(APP, "src", "main")
TEST_SRC = os.path.join(APP, "src", "test", "kotlin")
ROBO_SRC = os.path.join(APP, "src", "roboTest", "kotlin")
# Test helpers both test suites use (e.g. the simulated printer).
SHARED_TEST_SRC = os.path.join(APP, "src", "sharedTest", "kotlin")
ANDROID_JAR = os.path.join(TC, "android", "android.jar")
AAPT2 = os.path.join(TC, "android", "aapt2")

VERSION_CODE = 11
VERSION_NAME = "1.8.1"
MIN_SDK = 29
TARGET_SDK = 35
ABIS = ["arm64-v8a", "armeabi-v7a"]

# Packages of pure-Kotlin engine code that must not depend on Android (unit tested on the JVM).
PURE_DIRS = ["com/localmediatools/codec", "com/localmediatools/stitch/core", "com/localmediatools/vision/core", "com/localmediatools/gallery/core", "com/localmediatools/print/core", "com/localmediatools/music/core", "com/localmediatools/highlight/core"]

LIBS = [
    "kotlin-stdlib-2.3.21.jar",
    "kotlinx-coroutines-core-jvm-1.10.2.jar",
    "kotlinx-coroutines-android-1.10.2.jar",
    "opencv-4.12.0.aar",
    "pdfbox-android-2.0.27.0.aar",
    "tensorflow-lite-2.16.1.aar",
    "tensorflow-lite-api-2.16.1.aar",
]
SANITIZE_DROPS = [
    "org/opencv/android/Camera", "org/opencv/android/JavaCamera", "org/opencv/android/NativeCamera",
]
KEYSTORE = os.path.join(ROOT, "buildtools", "signing", "localmediatools-sideload.p12")
KEY_ALIAS = "localmediatools"
KEY_PASS = "localmediatools"


def log(msg):
    print(f"[build] {msg}", flush=True)


def run(cmd, **kw):
    env = dict(os.environ)
    env.pop("JAVA_TOOL_OPTIONS", None)  # offline build; avoids JVM banner noise
    r = subprocess.run(cmd, env=env, **kw)
    if r.returncode != 0:
        sys.exit(f"command failed ({r.returncode}): {' '.join(cmd[:6])} ...")
    return r


def jars(d):
    return sorted(os.path.join(d, f) for f in os.listdir(d) if f.endswith(".jar"))


def sha_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sources(base, exclude_pure=False, only_pure=False):
    out = []
    for dirpath, _, files in os.walk(base):
        rel = os.path.relpath(dirpath, base).replace(os.sep, "/")
        is_pure = any(rel == p or rel.startswith(p + "/") for p in PURE_DIRS)
        if only_pure and not is_pure:
            continue
        for f in files:
            if f.endswith(".kt") or f.endswith(".java"):
                out.append(os.path.join(dirpath, f))
    return sorted(out)


def sanitizer_cp():
    return os.pathsep.join([os.path.join(TC, "sanitizer"),
                            os.path.join(TC, "d8", "asm-9.7.1.jar"),
                            os.path.join(TC, "d8", "asm-tree-9.7.1.jar")])


def ensure_sanitizer():
    out = os.path.join(TC, "sanitizer", "JarSanitizer.class")
    src = os.path.join(ROOT, "buildtools", "src", "JarSanitizer.java")
    if os.path.exists(out) and os.path.getmtime(out) >= os.path.getmtime(src):
        return
    os.makedirs(os.path.dirname(out), exist_ok=True)
    run(["javac", "-nowarn", "-d", os.path.join(TC, "sanitizer"), "-cp",
         os.pathsep.join([os.path.join(TC, "d8", "asm-9.7.1.jar"), os.path.join(TC, "d8", "asm-tree-9.7.1.jar")]),
         src])


def sanitize(src_jar, dst_jar, drops=()):
    args = ["java", "-cp", sanitizer_cp(), "JarSanitizer", src_jar, dst_jar]
    for d in drops:
        args += ["--drop", d]
    run(args)


def d8(inputs, out_dir, intermediate=False):
    if os.path.exists(out_dir):
        shutil.rmtree(out_dir)
    os.makedirs(out_dir)
    cp = os.pathsep.join(jars(os.path.join(TC, "d8")))
    args = ["java", "-Xmx3g", "-cp", cp, "com.android.tools.r8.D8", "--release",
            "--min-api", str(MIN_SDK), "--lib", d8_android_jar(), "--output", out_dir]
    if intermediate:
        args.append("--intermediate")
    run(args + inputs)


def d8_android_jar():
    out = os.path.join(TC, "android", "android-d8.jar")
    if not os.path.exists(out):
        log("sanitizing android.jar for D8")
        sanitize(ANDROID_JAR, out)
    return out


def kotlinc_cmd():
    cp = os.pathsep.join(jars(os.path.join(TC, "kotlinc")))
    return ["java", "-Xmx3g", "-Xss8m", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"]


KOTLIN_FLAGS = ["-no-stdlib", "-no-reflect", "-jvm-target", "1.8", "-Xlambdas=class",
                "-Xsam-conversions=class", "-Xstring-concat=inline", "-nowarn",
                "-Xno-call-assertions", "-Xno-receiver-assertions", "-Xno-param-assertions"]


# ---------------------------------------------------------------------------------------------
def prepare_libs():
    """Extracts AARs into classes jars, native libs and assets. Cached by content hash."""
    libdir = os.path.join(BUILD, "libs")
    stamp = os.path.join(libdir, "stamp")
    key = hashlib.sha256(("|".join(LIBS + ABIS + SANITIZE_DROPS)).encode()).hexdigest()
    if os.path.exists(stamp) and open(stamp).read() == key:
        return
    log("preparing libraries")
    if os.path.exists(libdir):
        shutil.rmtree(libdir)
    for sub in ("jars", "native", "assets", "javares", "sanitized", "dex"):
        os.makedirs(os.path.join(libdir, sub))
    for lib in LIBS:
        path = os.path.join(TC, "libs", lib)
        base = lib.rsplit(".", 1)[0]
        jar_out = os.path.join(libdir, "jars", base + ".jar")
        if lib.endswith(".aar"):
            with zipfile.ZipFile(path) as z:
                for n in z.namelist():
                    if n == "classes.jar":
                        with open(jar_out, "wb") as f:
                            f.write(z.read(n))
                    elif n.startswith("jni/") and n.endswith(".so"):
                        _, abi, name = n.split("/", 2)
                        if abi in ABIS:
                            dst = os.path.join(libdir, "native", abi, name)
                            os.makedirs(os.path.dirname(dst), exist_ok=True)
                            with open(dst, "wb") as f:
                                f.write(z.read(n))
                    elif n.startswith("assets/") and not n.endswith("/"):
                        dst = os.path.join(libdir, "assets", n[len("assets/"):])
                        os.makedirs(os.path.dirname(dst), exist_ok=True)
                        with open(dst, "wb") as f:
                            f.write(z.read(n))
        else:
            shutil.copy(path, jar_out)
        # Java resources that must survive dexing (ServiceLoader registrations).
        with zipfile.ZipFile(jar_out) as z:
            for n in z.namelist():
                if n.startswith("META-INF/services/") and not n.endswith("/"):
                    dst = os.path.join(libdir, "javares", n)
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    with open(dst, "ab") as f:
                        f.write(z.read(n))
        san = os.path.join(libdir, "sanitized", base + ".jar")
        sanitize(jar_out, san, SANITIZE_DROPS)
        log(f"dexing {base}")
        d8([san], os.path.join(libdir, "dex", base), intermediate=True)
    with open(stamp, "w") as f:
        f.write(key)


def compile_resources():
    log("aapt2 compile")
    out = os.path.join(BUILD, "res-compiled.zip")
    run([AAPT2, "compile", "--dir", os.path.join(SRC, "res"), "-o", out])
    assets = os.path.join(BUILD, "assets")
    if os.path.exists(assets):
        shutil.rmtree(assets)
    shutil.copytree(os.path.join(BUILD, "libs", "assets"), assets)
    app_assets = os.path.join(SRC, "assets")
    if os.path.isdir(app_assets):
        shutil.copytree(app_assets, assets, dirs_exist_ok=True)
    model = os.path.join(TC, "models", "mobilenet_v3_small_embedder.tflite")
    os.makedirs(os.path.join(assets, "models"), exist_ok=True)
    shutil.copy(model, os.path.join(assets, "models", "image_embedder.tflite"))
    gen = os.path.join(BUILD, "gen")
    if os.path.exists(gen):
        shutil.rmtree(gen)
    os.makedirs(gen)
    log("aapt2 link")
    run([AAPT2, "link", "-o", os.path.join(BUILD, "base.apk"), "-I", ANDROID_JAR,
         "--manifest", os.path.join(SRC, "AndroidManifest.xml"), "--java", gen,
         "--min-sdk-version", str(MIN_SDK), "--target-sdk-version", str(TARGET_SDK),
         "--version-code", str(VERSION_CODE), "--version-name", VERSION_NAME,
         "-A", assets, "-0", "tflite", "-0", "ttf", "--no-version-vectors", out])


def compile_code():
    gen = os.path.join(BUILD, "gen")
    rcls = os.path.join(BUILD, "classes-r")
    if os.path.exists(rcls):
        shutil.rmtree(rcls)
    os.makedirs(rcls)
    log("javac R")
    run(["javac", "-nowarn", "--release", "8", "-cp", ANDROID_JAR, "-d", rcls] + sources(gen))
    kt_out = os.path.join(BUILD, "classes-kt")
    if os.path.exists(kt_out):
        shutil.rmtree(kt_out)
    libjars = jars(os.path.join(BUILD, "libs", "jars"))
    cp = os.pathsep.join([ANDROID_JAR, rcls] + libjars)
    srcs = sources(os.path.join(SRC, "kotlin"))
    log(f"kotlinc ({len(srcs)} files)")
    t = time.time()
    run(kotlinc_cmd() + KOTLIN_FLAGS + ["-classpath", cp, "-d", kt_out] + srcs)
    log(f"kotlinc done in {time.time() - t:.0f}s")
    # Package app classes into one jar for sanitizing + dexing.
    app_jar = os.path.join(BUILD, "app-classes.jar")
    with zipfile.ZipFile(app_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for base in (rcls, kt_out):
            for dirpath, _, files in os.walk(base):
                for fn in files:
                    if fn.endswith(".class"):
                        full = os.path.join(dirpath, fn)
                        z.write(full, os.path.relpath(full, base).replace(os.sep, "/"))
    san = os.path.join(BUILD, "app-classes-sanitized.jar")
    sanitize(app_jar, san)
    log("d8 app")
    d8([san], os.path.join(BUILD, "dex-app"), intermediate=True)
    log("d8 merge")
    inputs = []
    for d in sorted(os.listdir(os.path.join(BUILD, "libs", "dex"))):
        inputs += dex_files(os.path.join(BUILD, "libs", "dex", d))
    inputs += dex_files(os.path.join(BUILD, "dex-app"))
    d8(inputs, os.path.join(BUILD, "dex"))


def dex_files(d):
    return sorted(os.path.join(d, f) for f in os.listdir(d) if f.endswith(".dex"))


# ---------------------------------------------------------------------------------------------
class AlignedZip:
    """Minimal zip writer that aligns STORED entries (4 bytes, or 16 KiB for .so files)."""

    def __init__(self, path):
        self.f = open(path, "wb")
        self.entries = []

    def add(self, name, data, compress=True, align=4):
        name_b = name.encode("utf-8")
        crc = zlib.crc32(data) & 0xFFFFFFFF
        if compress:
            co = zlib.compressobj(9, zlib.DEFLATED, -15)
            payload = co.compress(data) + co.flush()
            method = 8
            if len(payload) >= len(data):
                payload, method = data, 0
        else:
            payload, method = data, 0
        offset = self.f.tell()
        extra = b""
        if method == 0:
            data_start = offset + 30 + len(name_b)
            pad = (-data_start) % align
            extra = b"\x00" * pad
        dos_time, dos_date = 0, (1 << 5) | 1  # 1980-01-01
        hdr = struct.pack("<IHHHHHIIIHH", 0x04034B50, 20 if method == 8 else 10, 0x0800, method,
                          dos_time, dos_date, crc, len(payload), len(data), len(name_b), len(extra))
        self.f.write(hdr + name_b + extra + payload)
        self.entries.append((name_b, method, crc, len(payload), len(data), offset, dos_time, dos_date))

    def close(self):
        cd_start = self.f.tell()
        for name_b, method, crc, csize, usize, offset, t, d in self.entries:
            self.f.write(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20 if method == 8 else 10, 0x0800,
                                     method, t, d, crc, csize, usize, len(name_b), 0, 0, 0, 0, 0, offset) + name_b)
        cd_end = self.f.tell()
        n = len(self.entries)
        self.f.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, n, n, cd_end - cd_start, cd_start, 0))
        self.f.close()


def package():
    log("packaging")
    unsigned = os.path.join(BUILD, "app-unsigned-aligned.apk")
    z = AlignedZip(unsigned)
    with zipfile.ZipFile(os.path.join(BUILD, "base.apk")) as base:
        for info in base.infolist():
            data = base.read(info.filename)
            if info.filename == "resources.arsc":
                z.add(info.filename, data, compress=False)
            else:
                z.add(info.filename, data, compress=info.compress_type != zipfile.ZIP_STORED)
    for f in dex_files(os.path.join(BUILD, "dex")):
        z.add(os.path.basename(f), open(f, "rb").read())
    native = os.path.join(BUILD, "libs", "native")
    for abi in ABIS:
        d = os.path.join(native, abi)
        if os.path.isdir(d):
            for fn in sorted(os.listdir(d)):
                z.add(f"lib/{abi}/{fn}", open(os.path.join(d, fn), "rb").read(), compress=True)
    javares = os.path.join(BUILD, "libs", "javares")
    for dirpath, _, files in os.walk(javares):
        for fn in files:
            full = os.path.join(dirpath, fn)
            z.add(os.path.relpath(full, javares).replace(os.sep, "/"), open(full, "rb").read())
    z.close()
    ensure_keystore()
    out = os.path.join(BUILD, "LocalMediaTools.apk")
    cp = os.pathsep.join([os.path.join(TC, "apksigner", "apksigner.jar"), os.path.join(TC, "apksigner", "apksig.jar")])
    log("signing")
    run(["java", "-cp", cp, "com.android.apksigner.ApkSignerTool", "sign", "--ks", KEYSTORE,
         "--ks-key-alias", KEY_ALIAS, "--ks-pass", f"pass:{KEY_PASS}", "--key-pass", f"pass:{KEY_PASS}",
         "--min-sdk-version", str(MIN_SDK), "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true", "--out", out, unsigned])
    run(["java", "-cp", cp, "com.android.apksigner.ApkSignerTool", "verify", "--min-sdk-version", str(MIN_SDK), out])
    log(f"APK: {out} ({os.path.getsize(out) / 1e6:.1f} MB)")
    return out


def ensure_keystore():
    if os.path.exists(KEYSTORE):
        return
    os.makedirs(os.path.dirname(KEYSTORE), exist_ok=True)
    run(["keytool", "-genkeypair", "-keystore", KEYSTORE, "-storetype", "PKCS12", "-alias", KEY_ALIAS,
         "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000", "-storepass", KEY_PASS,
         "-keypass", KEY_PASS, "-dname", "CN=Local Media Tools, O=Local Media Tools"])


# ---------------------------------------------------------------------------------------------
def run_tests():
    """Compiles the Android-free engine packages plus the tests and runs them on the JVM."""
    out = os.path.join(BUILD, "test-classes")
    if os.path.exists(out):
        shutil.rmtree(out)
    stdlib = os.path.join(TC, "libs", "kotlin-stdlib-2.3.21.jar")
    junit = jars(os.path.join(TC, "test"))
    srcs = sources(os.path.join(SRC, "kotlin"), only_pure=True) + sources(TEST_SRC) + sources(SHARED_TEST_SRC)
    log(f"compiling unit tests ({len(srcs)} files)")
    run(kotlinc_cmd() + KOTLIN_FLAGS + ["-classpath", os.pathsep.join([stdlib] + junit), "-d", out] + srcs)
    tests = []
    for dirpath, _, files in os.walk(out):
        for fn in files:
            if fn.endswith("Test.class") and "$" not in fn:
                rel = os.path.relpath(os.path.join(dirpath, fn), out)
                tests.append(rel[:-6].replace(os.sep, "."))
    only = [a.split("=", 1)[1] for a in sys.argv if a.startswith("--only=")]
    if only:
        tests = [t for t in tests if any(o in t for o in only)]
    log(f"running {len(tests)} test classes")
    resources = os.path.join(APP, "src", "test", "resources")
    run(["java", "-Xmx2g", "-cp", os.pathsep.join([out, resources, stdlib] + junit), "org.junit.runner.JUnitCore"] + sorted(tests))


def run_robo_tests():
    """Runs app/src/roboTest against the compiled app classes inside Robolectric (Android 15 runtime,
    native graphics). Needs the extra toolchain from `fetch_toolchain.py --robolectric`."""
    robo = jars(os.path.join(TC, "robolectric")) + jars(os.path.join(TC, "robolectric-extra"))
    sdk_dir = os.path.join(TC, "robolectric-sdk")
    if not robo or not os.path.isdir(sdk_dir):
        sys.exit("Robolectric toolchain missing: run python3 buildtools/fetch_toolchain.py --robolectric")
    out = os.path.join(BUILD, "robotest-classes")
    if os.path.exists(out):
        shutil.rmtree(out)
    os.makedirs(out)
    rcls = os.path.join(BUILD, "classes-r")
    kt = os.path.join(BUILD, "classes-kt")
    libjars = jars(os.path.join(BUILD, "libs", "jars"))
    srcs = sources(ROBO_SRC) + sources(SHARED_TEST_SRC)
    log(f"compiling Robolectric tests ({len(srcs)} files)")
    run(kotlinc_cmd() + KOTLIN_FLAGS + ["-classpath", os.pathsep.join([ANDROID_JAR, rcls, kt] + libjars + robo), "-d", out] + srcs)
    cfg = os.path.join(out, "com", "android", "tools")
    os.makedirs(cfg, exist_ok=True)
    with open(os.path.join(cfg, "test_config.properties"), "w") as f:
        f.write(f"android_merged_manifest={os.path.join(SRC, 'AndroidManifest.xml')}\n")
        f.write(f"android_merged_assets={os.path.join(BUILD, 'assets')}\n")
        f.write(f"android_resource_apk={os.path.join(BUILD, 'base.apk')}\n")
        f.write("android_custom_package=com.localmediatools.app\n")
    with open(os.path.join(out, "robolectric.properties"), "w") as f:
        f.write(f"sdk={TARGET_SDK}\ngraphicsMode=NATIVE\nlooperMode=PAUSED\napplication=com.localmediatools.app.LmtApp\n")
    tests = []
    for dirpath, _, files in os.walk(out):
        for fn in files:
            if fn.endswith("Test.class") and "$" not in fn:
                rel = os.path.relpath(os.path.join(dirpath, fn), out)
                tests.append(rel[:-6].replace(os.sep, "."))
    only = [a.split("=", 1)[1] for a in sys.argv if a.startswith("--only=")]
    if only:
        tests = [t for t in tests if any(o in t for o in only)]
    log(f"running {len(tests)} Robolectric test classes")
    resources = os.path.join(APP, "src", "test", "resources")
    run(["java", "-Xmx3g", "-Drobolectric.offline=true", f"-Drobolectric.dependency.dir={sdk_dir}",
         "-Drobolectric.logging.enabled=false", "-Djava.awt.headless=true",
         "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED",
         "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
         "-cp", os.pathsep.join([out, resources, rcls, kt] + libjars + robo + [ANDROID_JAR]),
         "org.junit.runner.JUnitCore"] + sorted(tests))


def main():
    if not os.path.exists(ANDROID_JAR):
        sys.exit("toolchain missing: run python3 buildtools/fetch_toolchain.py first")
    os.makedirs(BUILD, exist_ok=True)
    ensure_sanitizer()
    if "--robo-test-only" in sys.argv:  # reuse the last build's classes
        run_robo_tests()
        return
    if "--test" in sys.argv or "--test-only" in sys.argv:
        run_tests()
        if "--test-only" in sys.argv:
            return
    prepare_libs()
    compile_resources()
    compile_code()
    package()
    if "--robo-test" in sys.argv:
        run_robo_tests()


if __name__ == "__main__":
    main()
