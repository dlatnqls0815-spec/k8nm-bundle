"""Build the one-shot NEMESIS package k8svm.fix that updates ccnc-svm-5w at boot (offline)."""
import io, zipfile, hashlib, sys
from pathlib import Path
H = Path(__file__).resolve().parent
hsm = sorted((H.parents[1] / "out" / "seogaemo").glob("ccnc-svm-5w-*.hsm"), key=lambda p: p.stat().st_mtime)[-1]
data = hsm.read_bytes(); sha = hashlib.sha256(data).hexdigest()
T = (2026, 11, 19, 0, 0, 0)
desc = "\n".join(["[nemesis]", "version=1.1", "id=k8svm.fix", "name=K8 SVM Fix", "type=service",
    "package-version=0.2.1", 'short-desc="One-shot: update ccnc-svm-5w at boot"',
    "os=any", "models=any", "", "[service.0]", "name=fix", "kind=sh", "path=svc/update.sh",
    "uid=root", "mode=restart", ""])
def e(n, m):
    i = zipfile.ZipInfo(n, T); i.compress_type = zipfile.ZIP_DEFLATED; i.external_attr = m << 16; return i
s = (H / "update.sh").read_bytes().replace(b"@SHA@", sha.encode())
assert s.startswith(b"#!/system/bin/sh\n") and b"\r" not in s
b = io.BytesIO()
with zipfile.ZipFile(b, "w") as z:
    z.writestr(e("NEMESIS.data", 0o644), desc); z.writestr(e("svc/update.sh", 0o755), s)
    z.writestr(e("hsm/" + sha + ".hsm", 0o644), data)
out = H.parents[1] / "out" / "k8svm.fix-0.2.1.zip"; out.write_bytes(b.getvalue())
print(out.name, hsm.name, sha)
