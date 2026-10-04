#!/bin/sh
# Build standalone Android PIE executables, never libraries linked into JNI.
# Requires make, a host C compiler, Perl, patch, Python 3, tar, curl and NDK r28c.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TOOLS=$ROOT/native/android-tools
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/28.2.13676358}}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
JOBS=${JOBS:-2}
case "$JOBS" in 1|2) ;; *) echo 'JOBS must be 1 or 2' >&2; exit 1;; esac
HOSTCC=${HOSTCC:-cc}
MAKE=${MAKE:-make}
export LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH=1786416528
export ANDROID_NDK_ROOT=$NDK
export PATH="$TC:$PATH"
python3 - "$NDK/source.properties" <<'PY'
import pathlib, sys
if 'Pkg.Revision = 28.2.13676358' not in pathlib.Path(sys.argv[1]).read_text():
    raise SystemExit('Use pinned Android NDK 28.2.13676358')
PY
"$ROOT/scripts/fetch-android-tools.sh"
for ABI in ${ABIS:-arm64-v8a x86_64}; do
    case "$ABI" in
        arm64-v8a) TRIPLE=aarch64-linux-android; SSL_TARGET=android-arm64;;
        x86_64) TRIPLE=x86_64-linux-android; SSL_TARGET=android-x86_64;;
        *) echo "Unsupported ABI: $ABI" >&2; exit 1;;
    esac
    WORK=$TOOLS/.build/$ABI
    OUT=$TOOLS/prebuilt/$ABI
    mkdir -p "$WORK" "$OUT"
    # Extract clean trees each time; stale config must not affect the binary.
    for component in busybox openssl openssh; do
        python3 - "$TOOLS" "$WORK" "$component" <<'PY'
import json, pathlib, shutil, sys, tarfile
root, work, component = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3]
src = json.loads((root / 'sources.lock').read_text())[component]
target = work / component
if target.exists():
    shutil.rmtree(target)
target.mkdir()
with tarfile.open(root / 'sources' / src['file']) as archive:
    for member in archive.getmembers():
        parts = pathlib.PurePosixPath(member.name).parts
        if len(parts) < 2:
            continue
        member.name = str(pathlib.PurePosixPath(*parts[1:]))
        archive.extract(member, target, filter='data')
PY
    done
    CC=$TC/${TRIPLE}29-clang
    export CC AR=$TC/llvm-ar RANLIB=$TC/llvm-ranlib
    FLAGS="-O2 -fPIC -ffile-prefix-map=$WORK=/android-tools -fdebug-prefix-map=$WORK=/android-tools"
    LD_FLAGS='-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro,-z,now'
    (
        cd "$WORK/busybox"
        patch -p1 < "$TOOLS/patches/busybox/0001-apk-name.patch"
        "$MAKE" HOSTCC="$HOSTCC" allnoconfig >/dev/null
        python3 - "$TOOLS/busybox.config" <<'PY'
import pathlib, re, sys
seed = pathlib.Path(sys.argv[1]).read_text()
keys = set(re.findall(r'^(CONFIG_\w+)=', seed, re.M))
lines = pathlib.Path('.config').read_text().splitlines()
lines = [line for line in lines if not any(line.startswith(k + '=') or
         line == '# ' + k + ' is not set' for k in keys)]
pathlib.Path('.config').write_text('\n'.join(lines) + '\n' + seed)
PY
        yes '' | "$MAKE" HOSTCC="$HOSTCC" oldconfig >/dev/null
        "$MAKE" -j"$JOBS" HOSTCC="$HOSTCC" CC="$CC" AR="$AR" STRIP="$TC/llvm-strip" \
            CFLAGS="$FLAGS" LDFLAGS="$LD_FLAGS" busybox
        "$TC/llvm-strip" --strip-unneeded busybox
        cp busybox "$OUT/libbusybox.so"
        cp .config "$OUT/busybox.config"
    )
    (
        cd "$WORK/openssl"
        perl ./Configure "$SSL_TARGET" -D__ANDROID_API__=29 $FLAGS \
            no-shared no-module no-tests no-apps no-dso no-engine \
            --prefix=/system --libdir=lib --openssldir=/etc/ssl
        "$MAKE" -j"$JOBS" build_libs
        # Avoid install targets that build or install unwanted executable tools.
        mkdir -p "$WORK/crypto/lib" "$WORK/crypto/include"
        cp libcrypto.a "$WORK/crypto/lib/"
        cp -R include/openssl "$WORK/crypto/include/"
    )
    (
        cd "$WORK/openssh"
        patch -p1 < "$TOOLS/patches/openssh/0001-home-and-backup.patch"
        patch -p1 < "$TOOLS/patches/openssh/0002-android-public-resolver.patch"
        cp "$TOOLS/patches/openssh/android-passwd.h" .
        CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="$FLAGS -fPIE" \
            CPPFLAGS="-I$WORK/crypto/include" \
            LDFLAGS="$LD_FLAGS -pie -L$WORK/crypto/lib" \
            ac_cv_func_bzero=yes \
            ./configure --build=x86_64-pc-linux-gnu --host="$TRIPLE" \
            --prefix=/system --sysconfdir=/etc/ssh \
            --with-ssl-dir="$WORK/crypto" --without-openssl-header-check \
            --without-pam --without-kerberos5 --without-libedit \
            --without-libbsd --without-libutil --without-selinux \
            --disable-strip --disable-utmp --disable-utmpx \
            --disable-wtmp --disable-wtmpx --disable-lastlog \
            --disable-pkcs11 --disable-security-key --without-xauth
        "$MAKE" -j"$JOBS" SSH_PROGRAM=ssh ssh scp sftp ssh-keygen
        for cmd in ssh scp sftp ssh-keygen; do
            "$TC/llvm-strip" --strip-unneeded "$cmd"
            cp "$cmd" "$OUT/lib$cmd.so"
        done
    )
    chmod 755 "$OUT"/lib*.so
    python3 "$ROOT/scripts/verify-android-tools.py" "$NDK" "$ABI"
done
python3 "$ROOT/scripts/android-tools-notices.py"
python3 "$ROOT/scripts/bundle-busybox-source.py"
