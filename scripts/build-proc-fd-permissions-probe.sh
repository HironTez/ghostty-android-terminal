#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 Sylirre
# Rebuild the androidTest-only AArch64 Linux guest asset (one compiler job).
# No downloads, Gradle, host execution, APK builds or device operations.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}}
CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang
OUT=${1:-$ROOT/app/src/androidTest/assets/probes/proc-fd-permissions.aarch64}
[ -x "$CC" ] || { printf 'NDK clang not found: %s\nSet ANDROID_NDK to your installed NDK.\n' "$CC" >&2; exit 1; }
mkdir -p "$(dirname -- "$OUT")"
TMP=$(mktemp "${OUT}.XXXXXX")
trap 'rm -f "$TMP"' EXIT HUP INT TERM
export LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH=0
"$CC" -target aarch64-linux-android29 -std=c11 -O2 -Wall -Wextra -Werror \
    -ffreestanding -fno-builtin -fno-stack-protector -fno-ident \
    -fno-unwind-tables -fno-asynchronous-unwind-tables \
    "-ffile-prefix-map=$ROOT=." -nostdlib -static \
    -Wl,-no-pie,-e,_start,--build-id=none,--threads=1,-s \
    -Wl,-z,max-page-size=16384,-z,noexecstack \
    "$ROOT/scripts/probes/proc-fd-permissions.c" -o "$TMP"
python3 - "$TMP" <<'PY'
import pathlib, struct, sys
p = pathlib.Path(sys.argv[1])
b = p.read_bytes()
assert b[:7] == b'\x7fELF\x02\x01\x01', 'expected ELF64 little-endian'
e_type, e_machine = struct.unpack_from('<HH', b, 16)
assert (e_type, e_machine) == (2, 183), 'expected static AArch64 ET_EXEC'
phoff = struct.unpack_from('<Q', b, 32)[0]
phsize, phnum = struct.unpack_from('<HH', b, 54)
for i in range(phnum):
    kind = struct.unpack_from('<I', b, phoff + i * phsize)[0]
    assert kind not in (2, 3), 'must have no PT_DYNAMIC or PT_INTERP'
assert len(b) < 65536, 'unexpectedly large freestanding probe'
print(f'Verified static Linux AArch64 guest: {len(b)} bytes')
PY
# Not host-executable; instrumentation gives the copied GUEST file mode 0755.
chmod 644 "$TMP"
mv -f "$TMP" "$OUT"
printf 'Built %s\n' "$OUT"
