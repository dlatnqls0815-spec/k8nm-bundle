"""Build the one-shot NEMESIS package k8svm.fix (offline)."""
import io, zipfile, hashlib, sys
from pathlib import Path
H = Path(__file__).resolve().parent
T = (2026, 11, 19, 0, 0, 0)
desc = "\n".join(["[nemesis]", "version=1.1", "id=k8svm.fix", "name=K8 SVM Fix", "type=service",
    "package-version=0.1.2", 'short-desc="One-shot: fix app_process owner and switch SVM module"',
    "os=any", "models=any", "", "[service.0]", "name=fix", "kind=sh", "path=svc/fix.sh",
    "uid=root", "mode=background", ""])
def e(n, m):
    i = zipfile.ZipInfo(n, T); i.compress_type = zipfile.ZIP_DEFLATED; i.external_attr = m << 16; return i
s = (H / "fix.sh").read_bytes()
assert s.startswith(b"#!/system/bin/sh\n") and b"\r" not in s
b = io.BytesIO()
with zipfile.ZipFile(b, "w") as z:
    z.writestr(e("NEMESIS.data", 0o644), desc); z.writestr(e("svc/fix.sh", 0o755), s)
out = H.parents[1] / "out" / "k8svm.fix-0.1.2.zip"; out.write_bytes(b.getvalue())
print(out, hashlib.sha256(b.getvalue()).hexdigest())
