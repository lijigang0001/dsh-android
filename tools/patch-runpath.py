#!/usr/bin/env python3
"""Make Termux aarch64 binaries relocatable out of the Termux prefix.

Termux links `node` (and several shared libraries) with an absolute
DT_RUNPATH of `/data/data/com.termux/files/usr/lib`, which only exists inside
the Termux app sandbox. An app that bundles these binaries has to run them from
its own directory, so this rewrites that string in place to `$ORIGIN/../lib`.

In-place is safe here because the replacement is shorter: the dynamic string
table keeps its offsets, the new value simply ends at an earlier NUL, and the
rest of the old string becomes unreachable padding.

Usage: python tools/patch-runpath.py <dir> [<dir> ...]
"""
import os
import sys

OLD = b"/data/data/com.termux/files/usr/lib\x00"
NEW = b"$ORIGIN/../lib\x00"


def is_elf(path):
    try:
        with open(path, "rb") as handle:
            return handle.read(4) == b"\x7fELF"
    except OSError:
        return False


def patch(path):
    with open(path, "rb") as handle:
        data = bytearray(handle.read())
    at = data.find(OLD)
    if at < 0:
        return None
    hin = ''.join(c for c in os.path.basename(path) if c.isalnum())
    print(f"  {os.path.basename(path):<28} runpath at {at:#x}")
    while at >= 0:
        data[at:at + len(NEW)] = NEW
        for index in range(at + len(NEW), at + len(OLD)):
            data[index] = 0
        at = data.find(OLD, at + len(OLD))
    with open(path, "wb") as handle:
        handle.write(data)
    return True


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    patched = 0
    scanned = 0
    for root in sys.argv[1:]:
        for base, _dirs, files in os.walk(root):
            for name in files:
                path = os.path.join(base, name)
                if not is_elf(path):
                    continue
                scanned += 1
                if patch(path):
                    patched += 1
    print(f"scanned {scanned} ELF files, patched {patched}")


if __name__ == "__main__":
    main()
