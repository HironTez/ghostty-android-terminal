#!/bin/sh
# Download only pinned upstream archives; verify cached archives too.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
python3 - "$ROOT" <<'PY'
import hashlib, json, pathlib, subprocess, sys
root = pathlib.Path(sys.argv[1]) / 'native/android-tools'
lock = json.loads((root / 'sources.lock').read_text())
cache = root / 'sources'
cache.mkdir(exist_ok=True)
for component, source in lock.items():
    path = cache / source['file']
    if not path.exists():
        partial = path.with_suffix(path.suffix + '.part')
        subprocess.run(['curl', '--fail', '--location', '--retry', '3',
                        source['url'], '-o', str(partial)], check=True)
        if hashlib.sha256(partial.read_bytes()).hexdigest() != source['sha256']:
            raise SystemExit('checksum mismatch: ' + str(partial))
        partial.replace(path)
    if hashlib.sha256(path.read_bytes()).hexdigest() != source['sha256']:
        raise SystemExit('checksum mismatch: ' + str(path))
    print(component + ': verified ' + source['sha256'])
PY
