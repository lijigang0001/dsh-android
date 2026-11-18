#!/usr/bin/env python3
"""Fetch an Android (aarch64) Node runtime and make it relocatable.

Pulls Termux's `nodejs` package plus the shared-library dependencies its ELF
header asks for, unpacks the `.deb`s (ar + data.tar.xz), copies `bin/node` and
the `.so` set into one directory pair, and rewrites the absolute Termux RUNPATH
into `$ORIGIN/../lib` so the runtime can live in any app's private storage.

    python tools/fetch-android-runtime.py <out dir with bin/ and lib/>

The script is what CI uses; the local build produced the same layout by hand.
"""
import gzip
import io
import json
import os
import re
import sys
import shutil
import tarfile
import tempfile
import urllib.request

MIRRORS = [
    "https://packages.termux.dev/apt/termux-main/",
    "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/",
]
ARCH = "aarch64"
INDEX = "dists/stable/main/binary-{arch}/Packages.gz"
OLD_RUNPATH = b"/data/data/com.termux/files/usr/lib\x00"
NEW_RUNPATH = b"$ORIGIN/../lib\x00"
SKIP_SO = ("icutest", "icuio", "icutu", "capi.so", "legacy.so", "loader_attic")


def fetch(url, deadline_note=""):
    request = urllib.request.Request(url, headers={"User-Agent": "dsh-android-build/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def load_index():
    errors = []
    for mirror in MIRRORS:
        try:
            url = mirror + INDEX.format(arch=ARCH)
            data = gzip.decompress(fetch(url))
            print(f"index: {url} ({len(data)} bytes)")
            return mirror, data.decode("utf-8", "replace")
        except Exception as error:  # try the next mirror
            errors.append(f"{mirror}: {error}")
    raise SystemExit("no usable mirror:\n  " + "\n  ".join(errors))


def stanzas(index_text):
    packages = {}
    for block in index_text.split("\n\n"):
        name = re.search(r"(?m)^Package: (.+)$", block)
        filename = re.search(r"(?m)^Filename: (.+)$", block)
        if not name or not filename:
            continue
        packages[name.group(1).strip()] = {
            "filename": filename.group(1).strip(),
            "depends": [part.split("(")[0].strip().split(":")[0]
                        for part in re.search(r"(?m)^Depends: (.+)$", block).group(1).split(",")]
            if re.search(r"(?m)^Depends: ", block) else [],
        }
    return packages


def resolve(packages, wanted):
    ordered, seen = [], set()

    def visit(name):
        if name in seen or name not in packages:
            return
        seen.add(name)
        for dep in packages[name]["depends"]:
            visit(dep)
        ordered.append(name)

    for name in wanted:
        visit(name)
    return ordered


def ar_members(path):
    with open(path, "rb") as handle:
        if handle.read(8) != b"!<arch>\n":
            raise SystemExit(f"{path}: not an ar archive")
        while True:
            header = handle.read(60)
            if len(header) < 60:
                return
            name = header[0:16].decode("utf-8", "replace").strip().rstrip("/")
            size = int(header[48:58].decode("ascii", "replace").strip() or "0")
            payload = handle.read(size)
            if size % 2:
                handle.read(1)
            yield name, payload


def unpack_deb(path, root):
    for name, payload in ar_members(path):
        if not name.startswith("data.tar"):
            continue
        with tarfile.open(fileobj=io.BytesIO(payload), mode="r:*") as tar:
            members = [m for m in tar.getmembers() if m.isfile() or m.isdir()]
            tar.extractall(root, members=members)
        return True
    return False


def patch_runpath(path):
    with open(path, "rb") as handle:
        data = bytearray(handle.read())
    at = data.find(OLD_RUNPATH)
    if at < 0:
        return False
    while at >= 0:
        data[at:at + len(NEW_RUNPATH)] = NEW_RUNPATH
        for index in range(at + len(NEW_RUNPATH), at + len(OLD_RUNPATH)):
            data[index] = 0
        at = data.find(OLD_RUNPATH, at + len(OLD_RUNPATH))
    with open(path, "wb") as handle:
        handle.write(data)
    return True


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    out = sys.argv[1]
    # Scratch space must live outside the payload directory: everything under
    # <out>/.. is packed into the APK.
    work = os.environ.get("DSH_RUNTIME_WORK") or tempfile.mkdtemp(prefix="dsh-android-runtime-")
    debs = os.path.join(work, "debs")
    root = os.path.join(work, "root")
    os.makedirs(debs, exist_ok=True)
    os.makedirs(root, exist_ok=True)

    mirror, index_text = load_index()
    packages = stanzas(index_text)
    wanted = resolve(packages, ["nodejs"])
    print(f"packages: {', '.join(wanted)}")

    for name in wanted:
        filename = packages[name]["filename"]
        local = os.path.join(debs, f"{name}.deb")
        if not os.path.exists(local):
            print(f"  downloading {name} …")
            with open(local, "wb") as handle:
                handle.write(fetch(mirror + filename))
        if not unpack_deb(local, root):
            raise SystemExit(f"{name}: no data.tar member")
        print(f"  unpacked {name}")

    prefix = None
    for base, dirs, files in os.walk(root):
        if os.path.basename(base) == "usr" and "bin" in dirs:
            prefix = base
            break
    if prefix is None:
        raise SystemExit("cannot locate the usr prefix inside the unpacked debs")

    os.makedirs(os.path.join(out, "bin"), exist_ok=True)
    os.makedirs(os.path.join(out, "lib"), exist_ok=True)
    node = os.path.join(prefix, "bin", "node")
    if not os.path.isfile(node):
        raise SystemExit("node binary missing from the unpacked payload")
    with open(node, "rb") as source, open(os.path.join(out, "bin", "node"), "wb") as target:
        target.write(source.read())
    os.chmod(os.path.join(out, "bin", "node"), 0o755)

    copied = 0
    for base, _dirs, files in os.walk(os.path.join(prefix, "lib")):
        for name in files:
            if ".so" not in name or any(skip in name for skip in SKIP_SO):
                continue
            source = os.path.join(base, name)
            target = os.path.join(out, "lib", name)
            with open(source, "rb") as handle, open(target, "wb") as out_handle:
                out_handle.write(handle.read())
            copied += 1

    patched = 0
    for base, _dirs, files in os.walk(out):
        for name in files:
            path = os.path.join(base, name)
            with open(path, "rb") as handle:
                if handle.read(4) != b"\x7fELF":
                    continue
            if patch_runpath(path):
                patched += 1

    total = sum(os.path.getsize(os.path.join(base, name))
                for base, _dirs, files in os.walk(out) for name in files)
    print(f"copied   : node + {copied} shared libraries")
    print(f"patched  : {patched} ELF RUNPATH entries -> $ORIGIN/../lib")
    print(f"payload  : {total / 1024 / 1024:.1f} MB in {out}")


if __name__ == "__main__":
    main()
