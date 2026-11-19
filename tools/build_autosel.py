#!/usr/bin/env python3
"""Build the NEMESIS package k8avs.auto (around view auto select service) into out/k8avs.auto-<ver>.zip.

Compiles autosel/src against the API 19 android.jar and the compile-only stubs in hookstub/src (the stubs are
left out of the DEX; the car's /system/framework/automotive.jar provides them). Offline only; installs nothing.
"""
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
HOME = Path.home()
JDKS = [HOME / "jdk-17",
D8 = HOME / ".local/share/android-sdk/build-tools/35.0.1/lib/d8.jar"
NEM = ROOT / "autosel" / "nemesis"
OUT = ROOT / "out"
PACKAGE_ID = "k8avs.auto"
PACKAGE_VERSION = "0.1.2"
ZIP_TIME = (2026, 11, 19, 0, 0, 0)
MAIN_CLASS = b"Lio/github/k8svm/auto/AutoMain;"

DESCRIPTOR = "\n".join([
    "[nemesis]",
    "version=1.1",
    "id=" + PACKAGE_ID,
    "name=K8 Around View Auto",
    "type=service",
    "package-version=" + PACKAGE_VERSION,
    'short-desc="K8 camera key while driving opens the around view"',
    'detailed-desc="Presses the stock rear camera and around view buttons after the camera key. '
    'persist.sys.k8avs.on=0 turns it off. Use at your own risk."',
    "os=any",
    "models=any",
    "postinstall=scripts/postinstall.sh",
    "",
    "[service.0]",
    "name=auto",
    "kind=sh",
    "path=svc/auto.sh",
    "uid=system",
    "mode=restart",
    "",
])


def dex():
    jdk = Path(os.environ.get("JAVA_HOME") or next(str(j) for j in JDKS if (j / "bin").is_dir()))
    exe = ".exe" if os.name == "nt" else ""
    javac, java = str(jdk / "bin" / ("javac" + exe)), str(jdk / "bin" / ("java" + exe))
    with tempfile.TemporaryDirectory(prefix="k8avs-") as t:
        t = Path(t)
        aj = t / "android.jar"
        shutil.copyfile(ROOT / "lib" / "android.jar", aj)
        stub, cls, out = t / "stub", t / "cls", t / "dex"
        for d in (stub, cls, out):
            d.mkdir()
        stubs = [str(ROOT / "hookstub" / "src" / "com" / "hkmc" / "pgs" / "AvmStatusListener.java")]
        subprocess.run([javac, "-encoding", "UTF-8", "-source", "7", "-target", "7", "-Xlint:-options",
                        "-bootclasspath", str(aj), "-d", str(stub), *stubs], check=True)
        srcs = [str(p) for p in (ROOT / "autosel" / "src").rglob("*.java")]
        subprocess.run([javac, "-encoding", "UTF-8", "-source", "7", "-target", "7", "-Xlint:-options",
                        "-bootclasspath", str(aj), "-classpath", str(stub), "-d", str(cls), *srcs], check=True)
        classes = [str(p) for p in cls.rglob("*.class")]
        subprocess.run([java, "-cp", str(D8), "com.android.tools.r8.D8", "--min-api", "19", "--lib", str(aj),
                        "--classpath", str(stub), "--output", str(out), *classes], check=True)
        data = (out / "classes.dex").read_bytes()
    if not data.startswith(b"dex\n035\x00") or MAIN_CLASS not in data or b"Lcom/hkmc/pgs/AvmStatusListener;" not in data:
        raise SystemExit("unexpected DEX output")
    return data


def entry(name, mode):
    info = zipfile.ZipInfo(name, ZIP_TIME)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = mode << 16
    return info


def script(path):
    text = path.read_bytes().decode("utf-8")
    if not text.startswith("#!/system/bin/sh\n") or "\r" in text:
        raise SystemExit(str(path) + ": must be an LF-only /system/bin/sh script")
    return text


def main():
    d = dex()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", allowZip64=False) as z:
        z.writestr(entry("NEMESIS.data", 0o644), DESCRIPTOR)
        z.writestr(entry("svc/auto.sh", 0o755), script(NEM / "svc" / "auto.sh"))
        z.writestr(entry("scripts/postinstall.sh", 0o755), script(NEM / "scripts" / "postinstall.sh"))
        z.writestr(entry("bin/k8avs.dex", 0o644), d)
    OUT.mkdir(exist_ok=True)
    for stale in OUT.glob(PACKAGE_ID + "-*.zip"):
        stale.unlink()
    path = OUT / (PACKAGE_ID + "-" + PACKAGE_VERSION + ".zip")
    path.write_bytes(buf.getvalue())
    print("zip=%s\nzip_sha256=%s\ndex_bytes=%d" % (path, hashlib.sha256(buf.getvalue()).hexdigest(), len(d)))


if __name__ == "__main__":
    sys.exit(main())
