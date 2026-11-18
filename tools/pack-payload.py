#!/usr/bin/env python3
"""Pack the Android payload into one deflated zip for the app to extract.

Python's zipfile is used instead of a hand-rolled writer: the custom writer kept
truncating archives around 93 MB (a local header with no body at EOF, no central
directory), and a container the installer cannot open is not something to debug
under time pressure.

Layout inside the archive (paths relative to <payload dir>):
  runtime/bin/node              stored with mode 0755
  runtime/lib/*.so
  dsh/…                         pruned install anchor
  tools/android-*.cjs
  profile/profiles/web/…        seed DSH_HOME
  links.json                    soname symlinks to create after extraction

Usage: python tools/pack-payload.py <payload dir> <out.zip>
"""
import json
import os
import sys
import zipfile

# soname links the dynamic linker needs but a zip cannot carry.
LINKS = {
    "libicudata.so.79": "libicudata.so.79.1",
    "libicui18n.so.79": "libicui18n.so.79.1",
    "libicuuc.so.79": "libicuuc.so.79.1",
    "libsqlite3.so": "libsqlite3.so.3.54.1",
    "libz.so.1": "libz.so.1.4",
}


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    payload, out_path = sys.argv[1], sys.argv[2]

    with open(os.path.join(payload, "links.json"), "w", encoding="utf-8") as handle:
        json.dump(LINKS, handle, indent=2)
        handle.write("\n")

    files = []
    for base, _dirs, names in os.walk(payload):
        for name in names:
            full = os.path.join(base, name)
            rel = os.path.relpath(full, payload).replace(os.sep, "/")
            files.append((rel, full))
    files.sort()

    raw = 0
    with zipfile.ZipFile(out_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for rel, full in files:
            info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            # Unix mode travels with the entry so the installer can restore it.
            mode = 0o755 if rel == "runtime/bin/node" else 0o644
            info.external_attr = (0o100000 | mode) << 16
            with open(full, "rb") as source:
                data = source.read()
            raw += len(data)
            archive.writestr(info, data)

    packed = os.path.getsize(out_path)
    print(f"entries : {len(files)}")
    print(f"raw     : {raw / 1024 / 1024:.1f} MB")
    print(f"archive : {packed / 1024 / 1024:.1f} MB")
    print(f"written : {out_path}")

    with zipfile.ZipFile(out_path) as archive:
        names = set(archive.namelist())
        required = [
            "runtime/bin/node",
            "runtime/lib/libicudata.so.79.1",
            "dsh/lib/bin.js",
            "tools/android-addon-preload.cjs",
            "tools/android-native-addon-shim.cjs",
            "profile/profiles/web/package.json",
            "links.json",
        ]
        missing = [name for name in required if name not in names]
        if missing:
            raise SystemExit(f"archive incomplete, missing: {missing}")
        print(f"verified: {len(names)} entries, all required paths present")


if __name__ == "__main__":
    main()
