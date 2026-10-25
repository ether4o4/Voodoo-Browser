#!/usr/bin/env python3
"""Fetch the pinned upstream Android ARM64 runtime, never model weights.

Uses only named regular archive members, verifies GitHub's published SHA-256,
and optionally strips debug symbols using an ARM64-compatible strip tool.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

TAG = "b11868"
COMMIT = "78a8ae14be559b45f5f13ddee82c188b683d5d8d"
DIGEST = "a583874feade63177ac7cf12282ebaae0a35d62cffa4a0942c0a07d459dcd76d"
URL = f"https://github.com/ggml-org/llama.cpp/releases/download/{TAG}/llama-{TAG}-bin-android-arm64.tar.gz"
ROOT = Path(__file__).resolve().parents[1]
REQUIRED = ["llama-completion", "libllama-completion-impl.so", "libllama-common.so",
            "libllama.so", "libggml.so", "libggml-base.so"]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, default=ROOT / ".gradle" / "llama-runtime")
    parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    archive = args.archive or args.cache / "llama-android.tar.gz"
    if not archive.exists():
        with urllib.request.urlopen(URL, timeout=180) as response, archive.open("wb") as out:
            shutil.copyfileobj(response, out)
    with archive.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    if digest != DIGEST:
        raise RuntimeError("Upstream runtime integrity mismatch. No libraries were installed.")
    destination = ROOT / "app/src/main/jniLibs/arm64-v8a"
    destination.mkdir(parents=True, exist_ok=True)
    strip = os.environ.get("VOODOO_STRIP") or shutil.which("aarch64-linux-gnu-strip") or shutil.which("llvm-strip")
    with tarfile.open(archive, "r:gz") as tar:
        members = {Path(m.name).name: m for m in tar.getmembers()
                   if m.isfile() and m.name.startswith(f"llama-{TAG}/")}
        names = REQUIRED + sorted(n for n in members if n.startswith("libggml-cpu-android_") and n.endswith(".so"))
        for name in names:
            member = members[name]
            target = destination / ("libvoodoo-completion.so" if name == "llama-completion" else name)
            with tar.extractfile(member) as source, target.open("wb") as out:
                shutil.copyfileobj(source, out)
            if strip:
                subprocess.run([strip, "--strip-debug", str(target)], check=True)
            target.chmod(0o755)
        license_dir = ROOT / "app/src/main/assets/licenses"
        license_dir.mkdir(parents=True, exist_ok=True)
        with tar.extractfile(members["LICENSE"]) as source:
            (license_dir / "llama.cpp.txt").write_bytes(source.read())
    print(f"Verified llama.cpp {TAG} ({COMMIT}); installed {len(names)} ARM64 runtime files. No weights downloaded.")
    if not strip:
        print("ARM64 strip tool unavailable: binaries retain upstream debug symbols.")

if __name__ == "__main__":
    main()
