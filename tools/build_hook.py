#!/usr/bin/env python3
"""Build out/hook/ccnc-svm-hook.jar: our hook/src classes merged with the ccNC theme DEX (module ccnc-svm-5w).

Compiles against an API 19 android.jar and the Seogaemo hook host API (compile only, left out of the DEX:
the real hmgapi.hook classes come from the Seogaemo hook host). Offline only."""
import argparse, shutil, subprocess, sys, tempfile, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REF = ROOT / "lib"
HOME = Path.home()
JDKS = [HOME / "jdk-17",
D8 = HOME / ".local/share/android-sdk/build-tools/35.0.1/lib/d8.jar"
OUT = ROOT / "out" / "hook" / "ccnc-svm-hook.jar"
THEME = ROOT / "lib" / "theme" / "ccnc-theme-hook.jar"   # built ccNC theme DEX (personal use only)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--jdk", default=next((str(j) for j in JDKS if (j / "bin").is_dir()), ""))
    p.add_argument("--android-jar", default=str(REF / "android.jar"))
    p.add_argument("--host-api", default=str(REF / "host_api.jar"))
    a = p.parse_args(argv)
    exe = ".exe" if sys.platform == "win32" else ""
    javac, java = Path(a.jdk) / "bin" / ("javac" + exe), Path(a.jdk) / "bin" / ("java" + exe)
    srcs = sorted(str(s) for s in (ROOT / "hook" / "src").rglob("*.java"))
    with tempfile.TemporaryDirectory() as t:
        t = Path(t); cls = t / "classes"; dex = t / "dex"; cls.mkdir(); dex.mkdir()
        aj = t / "android.jar"; hj = t / "host.jar"
        shutil.copyfile(a.android_jar, aj); shutil.copyfile(a.host_api, hj)
        stub = t / "stub"; stub.mkdir()
        stubs = sorted(str(s) for s in (ROOT / "hookstub" / "src").rglob("*.java"))
        if stubs:
            subprocess.run([str(javac), "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:-options",
                            "-bootclasspath", str(aj), "-classpath", str(hj), "-d", str(stub), *stubs], check=True)
        cp = str(hj) + (";" if sys.platform == "win32" else ":") + str(stub)
        subprocess.run([str(javac), "-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:-options",
                        "-bootclasspath", str(aj), "-classpath", cp, "-d", str(cls), *srcs], check=True)
        classes = [str(c) for c in cls.rglob("*.class")]
        subprocess.run([str(java), "-cp", str(D8), "com.android.tools.r8.D8", "--min-api", "19",
                        "--lib", str(aj), "--classpath", str(hj), "--classpath", str(stub), "--output", str(dex), *classes, str(THEME)], check=True)
        data = (dex / "classes.dex").read_bytes()
        for need in (b"Lio/github/k8svm/hook/SvmOverlayEntry;", b"Lk8ccnc/CombinedEntry;", b"Lgl3ccnc/host/ThemeEntry;", b"Ldev/ev6/nowplaying/UniversalEntry;", b"Lhmgapi/hook/ModEntry;"):
            if need not in data:
                raise SystemExit("missing in DEX: " + need.decode())
        if b"Lhmgapi/hook/ModEntry;\x00" in data and b"class Lhmgapi" in data:
            raise SystemExit("host API classes leaked into the DEX")
        OUT.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
            info = zipfile.ZipInfo("classes.dex", (2026, 11, 19, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    print("jar=%s dex_bytes=%d" % (OUT, len(data)))


if __name__ == "__main__":
    sys.exit(main())
