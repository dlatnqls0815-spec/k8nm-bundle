#!/usr/bin/env python3
"""Build the NEMESIS bundle k8nm.bundle: ccNC theme + around view + driver easy access in one USB install.

One NEMESIS package with three services:
  access    driver easy access engine (same DEX as k8ea.access) + head unit app "K8 이지 억세스"
  auto      around view auto select (same DEX as k8avs.auto)
  seogaemo  one-shot (root, full caps): installs and turns on the bundled Seogaemo modules
            ccnc-svm-5w (theme + right panel + now playing bar offset), ccnc-svm-set (live settings card),
            k8-easy-access (easy access card), then restarts the head unit once.
The access/auto services stay idle while the standalone packages k8ea.access / k8avs.auto are installed.

Inputs (build them first):
  out/seogaemo/ccnc-svm-5w-<ver>.hsm, out/seogaemo/ccnc-svm-set-<ver>.hsm   (tools/build_hook.py, tools/build_seogaemo.py)
  out/k8avs.auto-<ver>.zip                                                (tools/build_autosel.py)
  ../k8-easy-access/private/car/k8-ea-car.dex, private/hu/k8-ea-hu.apk,
  ../k8-easy-access/private/nemesis/usb/HSM/k8-easy-access-<ver>.hsm      (that project's build tools)

Personal use only: the theme DEX inside ccnc-svm-5w must not be distributed. Offline only; installs nothing.
"""
import hashlib
import io
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EA = ROOT.parent / "k8-easy-access"
NEM = ROOT / "bundle" / "nemesis"
OUT = ROOT / "out" / "bundle"

PACKAGE_ID = "k8nm.bundle"
PACKAGE_VERSION = "0.1.0"
ZIP_TIME = (2026, 11, 19, 0, 0, 0)
SHORT_DESC = "K8 ccNC theme + around view + driver easy access"
DETAILED_DESC = ("Installs the Seogaemo modules ccnc-svm-5w, ccnc-svm-set and k8-easy-access once, then restarts. "
                 "Runs the easy access and around view auto services. Remove k8ea.access and k8avs.auto first. "
                 "Personal use only.")
# Seogaemo install order: theme+panel hook first, then the two cards.
MODULES = [("ccnc-svm-5w", ROOT / "out" / "seogaemo"), ("ccnc-svm-set", ROOT / "out" / "seogaemo"),
           ("k8-easy-access", EA / "private" / "nemesis" / "usb" / "HSM")]


def version_of(module_dir):
    for line in (module_dir / "module.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("version="):
            return line.split("=", 1)[1].strip()
    raise SystemExit("no version in " + str(module_dir))


def hsm_path(mid, folder):
    src = {"ccnc-svm-5w": ROOT / "seogaemo" / "ccnc-svm-5w", "ccnc-svm-set": ROOT / "seogaemo" / "ccnc-svm-set",
           "k8-easy-access": EA / "seogaemo" / "k8-easy-access"}[mid]
    p = folder / (mid + "-" + version_of(src) + ".hsm")
    if not p.is_file():
        raise SystemExit("missing " + str(p) + " (build it first)")
    return p


def descriptor():
    if len(SHORT_DESC) > 64 or len(DETAILED_DESC) > 256:
        raise SystemExit("NEMESIS description limit exceeded")
    return "\n".join([
        "[nemesis]", "version=1.1", "id=" + PACKAGE_ID, "name=K8 ccNC Bundle", "type=application,service",
        "package-version=" + PACKAGE_VERSION, 'short-desc="' + SHORT_DESC + '"', 'detailed-desc="' + DETAILED_DESC + '"',
        "os=any", "models=any", "postinstall=scripts/postinstall.sh", "",
        "[app.0]", "kind=apk", "path=app/k8-ea-hu.apk", "package=io.github.k8ea.hu",
        "component=io.github.k8ea.hu/.HuActivity", "",
        "[app.1]", "kind=sh", "path=bin/status.sh", "uid=root", "",
        "[service.0]", "name=access", "kind=sh", "path=svc/access.sh", "uid=system", "mode=restart", "",
        "[service.1]", "name=auto", "kind=sh", "path=svc/auto.sh", "uid=system", "mode=restart", "",
        "[service.2]", "name=seogaemo", "kind=sh", "path=svc/seogaemo.sh", "uid=root", "mode=restart", "",
    ])


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


def k8avs_dex():
    zips = sorted((ROOT / "out").glob("k8avs.auto-*.zip"), key=lambda p: p.stat().st_mtime)
    if not zips:
        raise SystemExit("missing out/k8avs.auto-*.zip (tools/build_autosel.py)")
    with zipfile.ZipFile(zips[-1]) as z:
        return z.read("bin/k8avs.dex")


def build():
    ea_dex = (EA / "private" / "car" / "k8-ea-car.dex").read_bytes()
    ea_apk = (EA / "private" / "hu" / "k8-ea-hu.apk").read_bytes()
    avs = k8avs_dex()
    if not ea_dex.startswith(b"dex\n035\x00") or b"Lio/github/k8ea/car/CarMain;" not in ea_dex:
        raise SystemExit("not the easy access car DEX")
    if not avs.startswith(b"dex\n035\x00") or b"Lio/github/k8svm/auto/AutoMain;" not in avs:
        raise SystemExit("not the around view auto DEX")
    if not ea_apk.startswith(b"PK"):
        raise SystemExit("not an APK")
    hsms, order = [], ["# module-id sha256 (install order)"]
    for mid, folder in MODULES:
        p = hsm_path(mid, folder)
        data = p.read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        hsms.append((sha, data, p.name))
        order.append(mid + " " + sha)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", allowZip64=False) as z:
        z.writestr(entry("NEMESIS.data", 0o644), descriptor())
        for name in ("svc/access.sh", "svc/auto.sh", "svc/seogaemo.sh", "scripts/postinstall.sh"):
            z.writestr(entry(name, 0o755), script(NEM / name))
        z.writestr(entry("bin/status.sh", 0o755), script(EA / "nemesis" / "bin" / "status.sh"))
        z.writestr(entry("bin/export_usb.sh", 0o755), script(EA / "nemesis" / "bin" / "export_usb.sh"))
        z.writestr(entry("bin/k8-ea-car.dex", 0o644), ea_dex)
        z.writestr(entry("bin/k8avs.dex", 0o644), avs)
        z.writestr(entry("app/k8-ea-hu.apk", 0o644), ea_apk)
        z.writestr(entry("hsm/order.txt", 0o644), "\n".join(order) + "\n")
        for sha, data, _ in hsms:
            z.writestr(entry("hsm/" + sha + ".hsm", 0o644), data)
    data = buf.getvalue()
    name = PACKAGE_ID + "-" + PACKAGE_VERSION + ".zip"
    packages = OUT / "usb" / "packages"
    packages.mkdir(parents=True, exist_ok=True)
    for folder in (OUT, packages):
        for stale in folder.glob(PACKAGE_ID + "-*.zip"):
            stale.unlink()
    (OUT / name).write_bytes(data)
    (packages / name).write_bytes(data)
    (OUT / "usb" / "NEMESIS.data").write_text(
        "[nemesis]\nversion=1.0\ntype=install\npath=packages/" + name + "\n", encoding="utf-8", newline="\n")
    print("zip=%s\nzip_sha256=%s\nbytes=%d" % (OUT / name, hashlib.sha256(data).hexdigest(), len(data)))
    for sha, _, n in hsms:
        print("hsm %s %s" % (n, sha))


if __name__ == "__main__":
    sys.exit(build())
