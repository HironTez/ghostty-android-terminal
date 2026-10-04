#!/usr/bin/env python3
"""Verify executable (not dlopen library) ELF properties and record hashes."""
import hashlib
import json
import pathlib
import re
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parent.parent
ndk = pathlib.Path(sys.argv[1])
abi = sys.argv[2]
readelf = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
out = root / 'native/android-tools/prebuilt' / abi
hashes = {}
for cmd in ('busybox', 'ssh', 'scp', 'sftp', 'ssh-keygen'):
    p = out / ('lib' + cmd + '.so')
    text = subprocess.check_output([str(readelf), '-hlWd', str(p)], text=True)
    assert 'DYN (Shared object file)' in text or 'DYN (Position-Independent Executable file)' in text, text
    assert re.search(r'Entry point address:\s+0x[1-9a-fA-F]', text), text
    assert '/system/bin/linker64' in text, text
    assert ('AArch64' if abi == 'arm64-v8a' else 'Advanced Micro Devices X86-64') in text, text
    assert 'TEXTREL' not in text, text
    for line in text.splitlines():
        if line.strip().startswith('LOAD'):
            assert int(line.split()[-1], 16) >= 16384, line
    deps = re.findall(r'\(NEEDED\).*?\[(.*?)\]', text)
    assert set(deps) <= {'libc.so', 'libm.so', 'libdl.so', 'libz.so'}, deps
    hashes[p.name] = hashlib.sha256(p.read_bytes()).hexdigest()
    print(abi, p.name, p.stat().st_size, deps, hashes[p.name])
inputs = [root / 'native/android-tools/sources.lock', root / 'native/android-tools/busybox.config',
          root / 'scripts/build-android-tools.sh', root / 'scripts/fetch-android-tools.sh',
          root / 'scripts/verify-android-tools.py', root / 'scripts/bundle-busybox-source.py',
          root / 'scripts/android-tools-notices.py']
inputs += sorted((root / 'native/android-tools/patches').rglob('*'))
metadata = {'ndk': '28.2.13676358', 'api': 29, 'abi': abi, 'outputs': hashes,
            'inputs': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                       for p in inputs if p.is_file()}}
(out / 'build.json').write_text(json.dumps(metadata, indent=2) + '\n')
