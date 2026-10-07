#!/usr/bin/env python3
"""Verify the bundled Android tool executables and their build manifest.

usage: verify-android-tools.py [--record] <ndk> <abi>

Every run checks each executable (these are PIE programs, not dlopen
libraries): ELF type/machine/entry, /system/bin/linker64, PIE, full RELRO
(a PT_GNU_RELRO segment plus DF_BIND_NOW and DF_1_NOW), no text
relocations, RPATH/RUNPATH or executable stack, 16 KiB LOAD alignment and
offset/address congruence, only public Android libraries, no symbol version
newer than API 29 (LIBC_Q) and an NDK ident note naming API 29 and the
pinned NDK. Anything newer or unknown is refused rather than trusted.

Default (check) mode then compares the ABI's build.json with the binaries,
the pinned toolchain, the pinned upstream source hashes and the current
build inputs, so an edited patch, script, config or lock file without a
rebuild fails. --record (used only by build-android-tools.sh right after a
build) rewrites build.json instead, and requires every upstream archive to
be present and match sources.lock.
"""
import hashlib
import json
import pathlib
import re
import subprocess
import sys

SCHEMA = 1
NDK_REVISION = '28.2.13676358'
NDK_RELEASE = 'r28c'
API = 29
TOOLS = ('busybox', 'ssh', 'scp', 'sftp', 'ssh-keygen')
ALLOWED_NEEDED = {'libc.so', 'libm.so', 'libdl.so', 'libz.so'}
# Bionic symbol version nodes available on API 29; later ones (LIBC_R, ...)
# would fail to resolve on a minSdk device, and unknown ones are refused.
ALLOWED_VERSIONS = {'LIBC', 'LIBC_N', 'LIBC_O', 'LIBC_P', 'LIBC_Q'}
PAGE = 16384
MACHINE = {'arm64-v8a': 'AArch64', 'x86_64': 'Advanced Micro Devices X86-64'}

root = pathlib.Path(__file__).resolve().parent.parent
tools_dir = root / 'native/android-tools'


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fail(message):
    raise SystemExit('verify-android-tools: ' + message)


def build_inputs():
    paths = [tools_dir / 'sources.lock', tools_dir / 'busybox.config',
             root / 'scripts/build-android-tools.sh', root / 'scripts/fetch-android-tools.sh',
             root / 'scripts/verify-android-tools.py', root / 'scripts/bundle-busybox-source.py',
             root / 'scripts/android-tools-notices.py']
    paths += sorted((tools_dir / 'patches').rglob('*'))
    return {str(p.relative_to(root)): sha256(p) for p in paths if p.is_file()}


def check_elf(readelf, path, abi):
    text = subprocess.check_output([str(readelf), '-hlWdnV', str(path)], text=True)

    def need(cond, what):
        if not cond:
            fail(f'{path}: {what}\n{text}')
    need(re.search(r'Type:\s+DYN ', text), 'not ET_DYN')
    need(MACHINE[abi] in text, 'wrong machine for ' + abi)
    need(re.search(r'Entry point address:\s+0x0*[1-9a-fA-F]', text),
         'zero entry point: a library, not a PIE executable')
    need('[Requesting program interpreter: /system/bin/linker64]' in text,
         'interpreter is not /system/bin/linker64')
    flags = re.search(r'\(FLAGS\)\s+(.*)', text)
    flags = flags.group(1).split() if flags else []
    flags1 = re.search(r'\(FLAGS_1\)\s+(.*)', text)
    flags1 = flags1.group(1).split() if flags1 else []
    need('PIE' in flags1, 'DF_1_PIE missing')
    need('BIND_NOW' in flags, 'DF_BIND_NOW missing (link with -z now)')
    need('NOW' in flags1, 'DF_1_NOW missing (link with -z now)')
    need('TEXTREL' not in flags and not re.search(r'\((TEXTREL|RPATH|RUNPATH)\)', text),
         'TEXTREL/RPATH/RUNPATH present')
    segments = [line.split() for line in text.splitlines()
                if re.match(r'\s+(LOAD|GNU_RELRO|GNU_STACK)\s', line)]
    need(any(s[0] == 'GNU_RELRO' for s in segments), 'no PT_GNU_RELRO segment (link with -z relro)')
    stack = [s for s in segments if s[0] == 'GNU_STACK']
    need(stack and 'E' not in ''.join(stack[0][6:-1]), 'executable or missing PT_GNU_STACK')
    loads = [s for s in segments if s[0] == 'LOAD']
    need(loads, 'no LOAD segments')
    for s in loads:
        offset, vaddr, align = int(s[1], 16), int(s[2], 16), int(s[-1], 16)
        need(align >= PAGE and align & (align - 1) == 0,
             f'LOAD align {align:#x} below 16 KiB: {" ".join(s)}')
        need(offset % PAGE == vaddr % PAGE,
             f'LOAD offset/vaddr not congruent mod 16 KiB: {" ".join(s)}')
    needed = set(re.findall(r'\(NEEDED\)\s+Shared library: \[(.*?)\]', text))
    need(needed <= ALLOWED_NEEDED, f'unexpected NEEDED {sorted(needed - ALLOWED_NEEDED)}')
    versions = set(re.findall(r'Name: (\S+)\s+Flags:', text))
    need(versions <= ALLOWED_VERSIONS,
         f'symbol versions newer than API {API} or unknown: {sorted(versions - ALLOWED_VERSIONS)}')
    note = re.search(r'NT_ANDROID_TYPE_IDENT\s+description data: ([0-9a-f ]+)', text)
    need(note, 'no Android NDK ident note')
    data = bytes.fromhex(note.group(1))
    api = int.from_bytes(data[:4], 'little')
    release = data[4:68].split(b'\0')[0].decode() if len(data) >= 68 else ''
    build = data[68:132].split(b'\0')[0].decode() if len(data) >= 132 else ''
    need(api == API, f'built for API {api}, expected {API}')
    need((release, build) == (NDK_RELEASE, NDK_REVISION.split('.')[-1]),
         f'built by NDK {release!r} {build!r}, expected {NDK_RELEASE} {NDK_REVISION}')
    return sorted(needed)


def check_sources(lock, require):
    """Pinned upstream archives: present ones must match; --record needs all."""
    for component, source in lock.items():
        archive = tools_dir / 'sources' / source['file']
        if archive.is_file():
            if sha256(archive) != source['sha256']:
                fail(f'{archive} does not match sources.lock')
        elif require:
            fail(f'{archive} missing; run scripts/fetch-android-tools.sh')


def main(argv):
    record = '--record' in argv
    args = [a for a in argv if a != '--record']
    if len(args) != 2 or args[1] not in MACHINE:
        fail('usage: verify-android-tools.py [--record] <ndk> <arm64-v8a|x86_64>')
    ndk, abi = pathlib.Path(args[0]), args[1]
    if f'Pkg.Revision = {NDK_REVISION}' not in (ndk / 'source.properties').read_text():
        fail(f'{ndk} is not the pinned NDK {NDK_REVISION}')
    readelf = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf'
    out = tools_dir / 'prebuilt' / abi
    lock = json.loads((tools_dir / 'sources.lock').read_text())
    check_sources(lock, record)
    outputs = {}
    for cmd in TOOLS:
        path = out / ('lib' + cmd + '.so')
        deps = check_elf(readelf, path, abi)
        outputs[path.name] = sha256(path)
        print(abi, path.name, path.stat().st_size, deps, outputs[path.name])
    metadata = {'schema': SCHEMA, 'ndk': NDK_REVISION, 'api': API, 'abi': abi,
                'outputs': outputs,
                'sources': {c: {'version': s['version'], 'sha256': s['sha256']}
                            for c, s in lock.items()},
                'inputs': build_inputs()}
    manifest = out / 'build.json'
    if record:
        manifest.write_text(json.dumps(metadata, indent=2) + '\n')
        return
    recorded = json.loads(manifest.read_text())
    if recorded.get('schema') != SCHEMA:
        fail(f'{manifest}: unknown schema {recorded.get("schema")!r}; refusing to trust it')
    for key in ('ndk', 'api', 'abi', 'outputs', 'sources', 'inputs'):
        if recorded.get(key) != metadata[key]:
            fail(f'{manifest}: {key} differs from the current tree; rebuild with '
                 f'scripts/build-android-tools.sh\nrecorded: {json.dumps(recorded.get(key), indent=1)}'
                 f'\ncurrent: {json.dumps(metadata[key], indent=1)}')
    print(abi, 'manifest matches binaries, pinned sources and build inputs')


if __name__ == '__main__':
    main(sys.argv[1:])
