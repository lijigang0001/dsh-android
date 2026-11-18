#!/usr/bin/env python3
"""Unpack Termux .deb packages into one relocatable runtime root.

Windows' bundled bsdtar cannot run the external `xz` filter, so this reads the
`ar` container directly and lets Python's tarfile handle data.tar.xz. Extracted
paths keep their `data/data/com.termux/files/usr/...` shape; callers relocate
that `usr` subtree into the Android app payload.

Usage: python tools/unpack-debs.py captures/termux/debs captures/termux/root
"""
import io
import os
import sys
import tarfile

AR_MAGIC = b"!<arch>\n"


def ar_members(path):
    """Yield (name, payload) for every member of a Unix ar archive."""
    with open(path, "rb") as handle:
        if handle.read(len(AR_MAGIC)) != AR_MAGIC:
            raise SystemExit(f"{path}: not an ar archive")
        while True:
            header = handle.read(60)
            if len(header) < 60:
                return
            name = header[0:16].decode("utf-8", "replace").strip().rstrip("/")
            raw_size = header[48:58].decode("ascii", "replace").strip()
            size = int(raw_size or "0")
            payload = handle.read(size)
            if size % 2:
                handle.read(1)
            yield name, payload


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    deb_dir, root = sys.argv[1], sys.argv[2]
    os.makedirs(root, exist_ok=True)
    for entry in sorted(os.listdir(deb_dir)):
        if not entry.endswith(".deb"):
            continue
        path = os.path.join(deb_dir, entry)
        data = None
        for name, payload in ar_members(path):
            if name.startswith("data.tar"):
                data = (name, payload)
                break
        if data is None:
            print(f"{entry}: no data.tar member")
            continue
        name, payload = data
        with tarfile.open(fileobj=io.BytesIO(payload), mode="r:*") as tar:
            members = [m for m in tar.getmembers() if m.isfile() or m.isdir()]
            tar.extractall(root, members=members)
        print(f"{entry}: {name} -> {len(payload)} bytes, {len(members)} entries")


if __name__ == "__main__":
    main()
