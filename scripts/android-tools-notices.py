#!/usr/bin/env python3
"""Retain per-file upstream copyright/license headers as binary-package notices.

Includes notices for all upstream C/assembly/header files (a superset of built
objects), avoiding accidental omission as client/static crypto sources change.
"""
import json
import pathlib
import re
import tarfile

root = pathlib.Path(__file__).resolve().parent.parent / 'native/android-tools'
lock = json.loads((root / 'sources.lock').read_text())
for component in ('openssh', 'openssl'):
    source = lock[component]
    notices = [f"Unmodified upstream file notices: {component} {source['version']}\n"
               f"Source: {source['url']}\nSHA256: {source['sha256']}\n"
               "Local Android changes are described in Local-patches-LICENSE.txt.\n"]
    with tarfile.open(root / 'sources' / source['file']) as archive:
        for member in sorted(archive.getmembers(), key=lambda m: m.name):
            if not member.isfile() or pathlib.PurePosixPath(member.name).suffix not in ('.c', '.h', '.S'):
                continue
            data = archive.extractfile(member).read().decode('utf-8', errors='replace')
            remaining = data.lstrip()
            blocks = []
            while remaining.startswith('/*'):
                end = remaining.find('*/')
                if end < 0:
                    break
                blocks.append(remaining[:end + 2])
                remaining = remaining[end + 2:].lstrip()
            header = '\n\n'.join(blocks)
            if re.search(r'copyright|permission|public domain|license|licence|SPDX', header, re.I):
                notices.append('\n==== ' + member.name + ' ====\n' + header + '\n')
    label = 'OpenSSH' if component == 'openssh' else 'OpenSSL'
    output = root / 'licenses' / (label + '-file-notices.txt')
    output.write_text('\n'.join(notices))
    print(output)
