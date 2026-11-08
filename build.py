#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Build SystemHelperFucker.apk from source, without Gradle or Android Studio.

Requires:
  - Python 3.8+
  - JDK 17+ (JAVA_HOME or java/javac on PATH)
  - Internet access on first run (downloads android.jar, build-tools and the
    Shizuku AARs into .sdk/ and libs/)

Usage:
  python build.py            # build build/SystemHelperFucker.apk
  ANDROID_HOME=/path/to/sdk python build.py   # reuse an existing SDK
"""

import glob
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

BASE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(BASE, "app")
BUILD = os.path.join(BASE, "build")
LIBS = os.path.join(BASE, "libs")
SDK = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.join(BASE, ".sdk")

PLATFORM_DIR = os.path.join(SDK, "platforms", "android-36")
BUILD_TOOLS_DIR = os.path.join(SDK, "build-tools", "36.0.0")
ANDROID_JAR = os.path.join(PLATFORM_DIR, "android.jar")

PLATFORM_URL = "https://dl.google.com/android/repository/platform-36_r02.zip"
BUILD_TOOLS_URL = "https://dl.google.com/android/repository/build-tools_r36_windows.zip"
MAVEN = "https://repo1.maven.org/maven2/dev/rikka/shizuku"
AARS = [
    ("api", "13.1.5"),
    ("provider", "13.1.5"),
    ("aidl", "13.1.5"),
]

IS_WIN = os.name == "nt"


def log(*a):
    print(*a, flush=True)


def download(url, dest):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    log("  downloading", url)
    with urllib.request.urlopen(url, timeout=180) as r, open(dest, "wb") as f:
        shutil.copyfileobj(r, f)
    log("  saved", dest, os.path.getsize(dest) // 1024, "KB")


def ensure_platform():
    if os.path.exists(ANDROID_JAR):
        return
    zip_path = os.path.join(SDK, "platform-36.zip")
    download(PLATFORM_URL, zip_path)
    with zipfile.ZipFile(zip_path) as zf:
        zf.extractall(os.path.join(SDK, "platforms"))
    if not os.path.exists(ANDROID_JAR):
        cands = glob.glob(os.path.join(SDK, "platforms", "*", "android.jar"))
        if not cands:
            sys.exit("android.jar not found after download")
        shutil.move(os.path.dirname(cands[0]), PLATFORM_DIR)


def ensure_build_tools():
    if os.path.exists(os.path.join(BUILD_TOOLS_DIR, "aapt2" + (".exe" if IS_WIN else ""))):
        return
    zip_path = os.path.join(SDK, "build-tools.zip")
    download(BUILD_TOOLS_URL, zip_path)
    tmp = os.path.join(SDK, "bt-tmp")
    with zipfile.ZipFile(zip_path) as zf:
        zf.extractall(tmp)
    cands = glob.glob(os.path.join(tmp, "**", "aapt2.exe"), recursive=True) or \
        glob.glob(os.path.join(tmp, "**", "aapt2"), recursive=True)
    if not cands:
        sys.exit("aapt2 not found after download")
    src = os.path.dirname(cands[0])
    if os.path.exists(BUILD_TOOLS_DIR):
        shutil.rmtree(BUILD_TOOLS_DIR)
    os.makedirs(os.path.dirname(BUILD_TOOLS_DIR), exist_ok=True)
    shutil.move(src, BUILD_TOOLS_DIR)


def ensure_shizuku_jars():
    jars = []
    for name, ver in AARS:
        aar = os.path.join(LIBS, "%s-%s.aar" % (name, ver))
        jar = os.path.join(LIBS, "%s-%s-classes.jar" % (name, ver))
        if not os.path.exists(aar):
            download("%s/%s/%s/%s-%s.aar" % (MAVEN, name, ver, name, ver), aar)
        if not os.path.exists(jar):
            with zipfile.ZipFile(aar) as zf, zf.open("classes.jar") as src, open(jar, "wb") as dst:
                shutil.copyfileobj(src, dst)
        jars.append(jar)
    return jars


def tool(name):
    """Resolve a build-tools executable."""
    exe = os.path.join(BUILD_TOOLS_DIR, name + (".bat" if IS_WIN else ""))
    if not os.path.exists(exe):
        exe = os.path.join(BUILD_TOOLS_DIR, name)
    return exe


def jdk_tool(name):
    exe = name + (".exe" if IS_WIN else "")
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        cand = os.path.join(java_home, "bin", exe)
        if os.path.exists(cand):
            return cand
    found = shutil.which(name)
    if not found:
        sys.exit("%s not found; install a JDK 17+ and set JAVA_HOME" % name)
    return found


def run(cmd, **kw):
    log(">", " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        sys.stderr.write((r.stdout or "")[-4000:])
        sys.stderr.write((r.stderr or "")[-4000:])
        sys.exit("%s failed (rc=%d)" % (cmd[0], r.returncode))
    if r.stdout.strip():
        log(r.stdout.strip()[-2000:])
    return r


def ensure_keystore():
    ks = os.path.join(os.path.expanduser("~"), ".android", "debug.keystore")
    if os.path.exists(ks):
        return ks
    os.makedirs(os.path.dirname(ks), exist_ok=True)
    run([jdk_tool("keytool"), "-genkeypair", "-keystore", ks, "-storepass", "android",
         "-alias", "androiddebugkey", "-keypass", "android", "-dname",
         "CN=Android Debug,O=Android,C=US", "-keyalg", "RSA", "-keysize", "2048",
         "-validity", "10000"])
    return ks


def main():
    log("SDK:", SDK)
    ensure_platform()
    ensure_build_tools()
    jars = ensure_shizuku_jars()

    for d in (os.path.join(BUILD, "classes"), os.path.join(BUILD, "dex")):
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d)

    sources = glob.glob(os.path.join(APP, "src", "**", "*.java"), recursive=True)
    src_list = os.path.join(BUILD, "sources.txt")
    with open(src_list, "w", encoding="utf-8") as f:
        f.write("\n".join(sources))

    classpath = os.pathsep.join([ANDROID_JAR] + jars)
    run([jdk_tool("javac"), "-source", "8", "-target", "8", "-nowarn", "-encoding", "UTF-8",
         "-classpath", classpath, "-d", os.path.join(BUILD, "classes"), "@" + src_list])

    classfiles = glob.glob(os.path.join(BUILD, "classes", "**", "*.class"), recursive=True)
    run([tool("d8"), "--lib", ANDROID_JAR, "--min-api", "26",
         "--output", os.path.join(BUILD, "dex")] + classfiles + jars)

    res_dir = os.path.join(APP, "res")
    res_zip = os.path.join(BUILD, "res.zip")
    res_args = []
    if os.path.isdir(res_dir) and os.listdir(res_dir):
        if os.path.exists(res_zip):
            os.remove(res_zip)
        run([tool("aapt2"), "compile", "--dir", res_dir, "-o", res_zip])
        res_args = [res_zip]

    unaligned = os.path.join(BUILD, "unaligned.apk")
    if os.path.exists(unaligned):
        os.remove(unaligned)
    run([tool("aapt2"), "link", "-o", unaligned, "-I", ANDROID_JAR,
         "--manifest", os.path.join(APP, "AndroidManifest.xml")] + res_args)

    with zipfile.ZipFile(unaligned, "a", zipfile.ZIP_DEFLATED) as zf:
        zf.write(os.path.join(BUILD, "dex", "classes.dex"), "classes.dex")

    aligned = os.path.join(BUILD, "aligned.apk")
    if os.path.exists(aligned):
        os.remove(aligned)
    run([tool("zipalign"), "-f", "4", unaligned, aligned])

    final = os.path.join(BUILD, "SystemHelperFucker.apk")
    if os.path.exists(final):
        os.remove(final)
    run([tool("apksigner"), "sign",
         "--ks", ensure_keystore(), "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--ks-key-alias", "androiddebugkey", "--out", final, aligned])
    log("APK:", final, os.path.getsize(final), "bytes")


if __name__ == "__main__":
    main()