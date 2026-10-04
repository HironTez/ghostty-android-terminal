#!/usr/bin/env python3
"""Include complete BusyBox corresponding source in the APK's license assets.

No written offer, expiring download, or optional network-only source is needed.
The tar contains upstream source, patches/config, build scripts and licenses.
"""
import hashlib
import io
import json
import pathlib
import tarfile

root = pathlib.Path(__file__).resolve().parent.parent
tools = root / 'native/android-tools'
paths = [tools / 'sources/busybox-1.37.0.tar.bz2', tools / 'sources.lock', tools / 'busybox.config',
         root / 'scripts/build-android-tools.sh', root / 'scripts/fetch-android-tools.sh',
         root / 'scripts/verify-android-tools.py', root / 'scripts/bundle-busybox-source.py']
paths += sorted((tools / 'patches').rglob('*'))
paths += sorted((tools / 'licenses').glob('*.txt'))
paths += sorted((tools / 'prebuilt').glob('*/busybox.config'))
# Fixed ordering, owner, times and modes produce a stable archive.
output = tools / 'licenses/busybox-corresponding-source.tar.xz'
with tarfile.open(output, 'w:xz', format=tarfile.PAX_FORMAT) as archive:
    for path in sorted(p for p in paths if p.is_file()):
        data = path.read_bytes()
        info = tarfile.TarInfo(str(path.relative_to(root)))
        info.size = len(data)
        info.mtime = 1786416528
        info.mode = 0o755 if path.name.endswith('.sh') else 0o644
        archive.addfile(info, io.BytesIO(data))
metadata = {'archive': hashlib.sha256(output.read_bytes()).hexdigest(),
            'inputs': {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                       for p in paths if p.is_file()}}
(tools / 'licenses/busybox-source.json').write_text(json.dumps(metadata, indent=2) + '\n')
print(output)
