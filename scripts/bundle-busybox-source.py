#!/usr/bin/env python3
"""Bundle BusyBox's corresponding source (GPLv2 section 3(a)) as an APK asset.

usage: bundle-busybox-source.py [--check]

The tar contains the unmodified upstream archive, the local patch (with its
modified-file notice), the curated seed and the exact normalized configs of
the shipped binaries, every script that fetches/builds/verifies/bundles, and
the license texts. Rebuild the shipped BusyBox from it with:

    COMPONENTS=busybox scripts/build-android-tools.sh

The archive is reproducible: fixed member order, ustar headers with zero
uid/gid, empty owner names, a fixed mtime and normalized modes, compressed by
an explicit single-threaded xz preset. --check rebuilds it in memory and fails
if the committed archive or busybox-source.json differ.
"""
import hashlib
import io
import json
import lzma
import pathlib
import sys
import tarfile

root = pathlib.Path(__file__).resolve().parent.parent
tools = root / 'native/android-tools'
output = tools / 'licenses/busybox-corresponding-source.tar.xz'
manifest = tools / 'licenses/busybox-source.json'
MTIME = 1786416528  # same SOURCE_DATE_EPOCH as the build
SCHEMA = 1

BUILDING = """\
BusyBox corresponding source for the A-SH Android terminal
==========================================================

The APK ships BusyBox 1.37.0 as lib/<abi>/libbusybox.so, a standalone PIE
executable for arm64-v8a and x86_64, built by scripts/build-android-tools.sh
from the files in this archive:

  native/android-tools/sources/busybox-1.37.0.tar.bz2  unmodified upstream
  native/android-tools/patches/busybox/*.patch          local modification
  native/android-tools/busybox.config                   curated seed config
  native/android-tools/prebuilt/<abi>/busybox.config    exact normalized config
  scripts/*.sh, scripts/*.py                            fetch/build/verify/bundle

Toolchain (not included; publicly available): Android NDK 28.2.13676358 (r28c)
for the target compiler, plus a host C compiler, GNU make, patch, Python 3,
Perl and tar on Linux x86_64. To rebuild BusyBox only:

  mkdir src && tar -xJf busybox-corresponding-source.tar.xz -C src && cd src
  ANDROID_NDK=/path/to/ndk/28.2.13676358 COMPONENTS=busybox \\
      sh scripts/build-android-tools.sh

The script prints the SHA-256 of each rebuilt binary; compare it with
native/android-tools/prebuilt/<abi>/build.json ("libbusybox.so").
OpenSSH and OpenSSL are not GPL and their archives are not included here;
scripts/fetch-android-tools.sh downloads them by pinned SHA-256.
"""


def inputs():
    paths = [tools / 'sources/busybox-1.37.0.tar.bz2', tools / 'sources.lock',
             tools / 'busybox.config',
             root / 'scripts/build-android-tools.sh', root / 'scripts/fetch-android-tools.sh',
             root / 'scripts/verify-android-tools.py', root / 'scripts/bundle-busybox-source.py',
             root / 'scripts/android-tools-notices.py']
    paths += (tools / 'patches').rglob('*')
    paths += (tools / 'licenses').glob('*.txt')
    paths += (tools / 'prebuilt').glob('*/busybox.config')
    paths += (tools / 'prebuilt').glob('*/build.json')
    return sorted({p for p in paths if p.is_file()})


def build():
    members = [('BUILDING-BusyBox.txt', BUILDING.encode(), 0o644)]
    for path in inputs():
        name = str(path.relative_to(root))
        mode = 0o755 if path.suffix in ('.sh', '.py') else 0o644
        members.append((name, path.read_bytes(), mode))
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        for name, data, mode in members:
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mtime = MTIME
            info.mode = mode
            info.uid = info.gid = 0
            info.uname = info.gname = ''
            archive.addfile(info, io.BytesIO(data))
    blob = lzma.compress(raw.getvalue(), format=lzma.FORMAT_XZ,
                         check=lzma.CHECK_CRC64, preset=6)
    metadata = {'schema': SCHEMA,
                'archive': hashlib.sha256(blob).hexdigest(),
                'inputs': {name: hashlib.sha256(data).hexdigest()
                           for name, data, _ in members if name != 'BUILDING-BusyBox.txt'}}
    return blob, (json.dumps(metadata, indent=2) + '\n').encode()


def main(argv):
    blob, meta = build()
    if '--check' in argv:
        if not output.is_file() or output.read_bytes() != blob:
            raise SystemExit(f'{output} is stale or not reproducible; run {sys.argv[0]}')
        if not manifest.is_file() or manifest.read_bytes() != meta:
            raise SystemExit(f'{manifest} is stale; run {sys.argv[0]}')
        print('BusyBox corresponding source is current and reproducible')
        return
    output.write_bytes(blob)
    manifest.write_bytes(meta)
    print(output)


if __name__ == '__main__':
    main(sys.argv[1:])
