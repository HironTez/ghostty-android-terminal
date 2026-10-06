/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 Sylirre */

/* Freestanding Linux AArch64 guest, NOT a host Android executable.
 * Build with scripts/build-proc-fd-permissions-probe.sh. No libc/interpreter:
 * issue legacy fchmodat (53) directly so libc cannot mask the resolver bug.
 * The systemd fallback uses /proc/<numeric getpid>/fd/N, not just /proc/self.
 * All files are under /tmp in the test's private rootfs; no /etc mutations.
 * Results are guest-written and atomically published, never PTY assertions.
 */
#ifndef __aarch64__
#error This probe must be compiled for an AArch64 guest.
#endif

typedef unsigned long usize;
#define AT_FDCWD (-100)
#define AT_EMPTY_PATH 0x1000
#define O_RDWR 2
#define O_CREAT 0100
#define O_EXCL 0200
/* AArch64 asm/fcntl.h overrides generic flags: 0200000 is O_DIRECT. */
#define O_DIRECTORY 040000
#define O_PATH 010000000
#define ENOENT 2
#define EINTR 4
#define EBADF 9
#define ENOSYS 38

/* Linux asm-generic/stat.h ABI, independent of Bionic's libc struct stat. */
struct guest_stat {
    unsigned long dev, ino;
    unsigned int mode, nlink, uid, gid;
    unsigned long rdev, pad1;
    long size;
    int blksize, pad2;
    long blocks, atime;
    unsigned long atime_nsec;
    long mtime;
    unsigned long mtime_nsec;
    long ctime;
    unsigned long ctime_nsec;
    unsigned int unused4, unused5;
};
_Static_assert(sizeof(struct guest_stat) == 128, "Linux AArch64 stat size");
_Static_assert(__builtin_offsetof(struct guest_stat, mode) == 16, "stat mode offset");

static long raw(long nr, long a, long b, long c, long d) {
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x8 __asm__("x8") = nr;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x8)
                     : "memory", "cc");
    return x0; /* raw Linux negative errno, not libc -1/errno */
}

static usize length(const char *s) {
    usize n = 0;
    while (s[n]) ++n;
    return n;
}

static char *append(char *out, const char *s) {
    while (*s) *out++ = *s++;
    *out = 0;
    return out;
}

static char *number(char *out, long n) {
    char digits[24];
    unsigned long v;
    int i = 0;
    if (n < 0) { *out++ = '-'; v = (unsigned long)(-(n + 1)) + 1; }
    else v = (unsigned long)n;
    do { digits[i++] = (char)('0' + v % 10); v /= 10; } while (v);
    while (i) *out++ = digits[--i];
    *out = 0;
    return out;
}

static int failed, report_failed;
static long report_fd;
static void record(const char *key, long value) {
    char line[160];
    char *end = append(line, key);
    *end++ = '=';
    end = number(end, value);
    *end++ = '\n';
    usize done = 0, size = (usize)(end - line);
    while (done < size) {
        long n = raw(64, report_fd, (long)(line + done), (long)(size - done), 0);
        if (n == -EINTR) continue;
        if (n <= 0) { report_failed = 1; return; }
        done += (usize)n;
    }
}

static void expect(const char *key, long actual, long expected) {
    record(key, actual);
    if (actual != expected) failed = 1;
}

static long open_file(const char *path, long flags) {
    return raw(56, AT_FDCWD, (long)path, flags, 0600);
}

static long mode(long fd) {
    struct guest_stat st;
    long rc = raw(80, fd, (long)&st, 0, 0);
    return rc < 0 ? rc : (long)(st.mode & 07777);
}

static const char *const aliases[] = {"numeric", "self", "dev"};
static void fd_path(char *out, int alias, long pid, long fd) {
    char *p;
    if (alias == 0) {
        p = append(out, "/proc/");
        p = number(p, pid);
        p = append(p, "/fd/");
    } else p = append(out, alias == 1 ? "/proc/self/fd/" : "/dev/fd/");
    (void)number(p, fd);
}

/* Reset first for EVERY alias: one passing chmod must not conceal a no-op in
 * another alias. Check the same live inode via raw fstat, including after unlink. */
static void fallback(long fd, long pid, const char *label) {
    for (int a = 0; a < 3; ++a) {
        char path[128], key[128];
        fd_path(path, a, pid, fd);
        char *p = append(key, label);
        p = append(p, ".");
        p = append(p, aliases[a]);
        char *suffix = p;
        append(suffix, ".reset");
        expect(key, raw(52, fd, 0600, 0, 0), 0);
        append(suffix, ".before");
        expect(key, mode(fd), 0600);
        append(suffix, ".rc");
        expect(key, raw(53, AT_FDCWD, (long)path, 0644, 0), 0);
        append(suffix, ".mode");
        expect(key, mode(fd), 0644);
    }
}

/* No opens between close and these checks, so the tested fd cannot be reused. */
static void closed(long fd, long pid) {
    expect("closed.close", raw(57, fd, 0, 0, 0), 0);
    for (int a = 0; a < 3; ++a) {
        char path[128], key[128];
        fd_path(path, a, pid, fd);
        char *p = append(key, "closed.");
        p = append(p, aliases[a]);
        append(p, ".rc");
        expect(key, raw(53, AT_FDCWD, (long)path, 0644, 0), -ENOENT);
    }
    expect("closed.fstat", mode(fd), -EBADF);
}

/* Intermediate proc fd components must STILL be guest-rooted. A blanket
 * passthrough would follow escape -> <absolute HOST sentinel> outside rootfs.
 * First prove a legitimate child through each directory-fd alias works; this
 * prevents a resolver that rejects all intermediate components from passing.
 */
static void containment(long pid) {
    char host_target[2048];
    long config = open_file("/tmp/proc-fd-host-target", 0);
    if (config < 0) { expect("containment.config", config, 0); return; }
    long n = raw(63, config, (long)host_target, sizeof(host_target) - 1, 0);
    raw(57, config, 0, 0, 0);
    if (n <= 0 || n >= (long)sizeof(host_target) - 1 || host_target[0] != '/') {
        expect("containment.config_valid", 0, 1); return;
    }
    host_target[n] = 0;
    expect("containment.mkdir", raw(34, AT_FDCWD, (long)"/tmp/proc-fd-dir", 0700, 0), 0);
    expect("containment.symlink", raw(36, (long)host_target, AT_FDCWD,
                                     (long)"/tmp/proc-fd-dir/escape", 0), 0);
    long child = open_file("/tmp/proc-fd-dir/child", O_RDWR | O_CREAT | O_EXCL);
    long dir = open_file("/tmp/proc-fd-dir", O_DIRECTORY);
    record("containment.child_open", child);
    record("containment.directory_open", dir);
    if (child < 0 || dir < 0) { expect("containment.open", -1, 0); return; }
    for (int a = 0; a < 3; ++a) {
        char path[128], key[128];
        fd_path(path, a, pid, dir);
        char *tail = path + length(path);
        append(tail, "/child");
        char *p = append(key, "directory.");
        p = append(p, aliases[a]);
        char *suffix = p;
        append(suffix, ".reset");
        expect(key, raw(52, child, 0600, 0, 0), 0);
        append(suffix, ".rc");
        expect(key, raw(53, AT_FDCWD, (long)path, 0644, 0), 0);
        append(suffix, ".mode");
        expect(key, mode(child), 0644);
        append(tail, "/escape");
        append(suffix, ".escape_open");
        long escaped = open_file(path, 0);
        expect(key, escaped, -ENOENT);
        if (escaped >= 0) raw(57, escaped, 0, 0, 0);
        append(suffix, ".escape_chmod");
        expect(key, raw(53, AT_FDCWD, (long)path, 0644, 0), -ENOENT);
    }
    raw(57, dir, 0, 0, 0);
    raw(57, child, 0, 0, 0);
}

int probe_main(void) {
    report_fd = open_file("/tmp/proc-fd-permissions.status.tmp", O_RDWR | O_CREAT | O_EXCL);
    if (report_fd < 0) return 2;
    record("protocol", 1);
    long pid = raw(172, 0, 0, 0, 0);
    record("pid", pid);
    if (pid <= 0) failed = 1;
    long named = open_file("/tmp/proc-fd-ordinary", O_RDWR | O_CREAT | O_EXCL);
    long unlinked = open_file("/tmp/proc-fd-unlinked", O_RDWR | O_CREAT | O_EXCL);
    if (named < 0 || unlinked < 0) {
        expect("setup.named", named, 0);
        expect("setup.unlinked", unlinked, 0);
    } else {
        long flags = raw(25, named, 3 /* F_GETFL */, 0, 0);
        expect("ordinary_fd", flags >= 0 && !(flags & O_PATH), 1);
        flags = raw(25, unlinked, 3 /* F_GETFL */, 0, 0);
        expect("ordinary_unlinked_fd", flags >= 0 && !(flags & O_PATH), 1);
        expect("modern.reset", raw(52, named, 0600, 0, 0), 0);
        long modern = raw(452, named, (long)"", 0640, AT_EMPTY_PATH);
        record("fchmodat2.rc", modern);
        /* Current arm64chroot returns ENOSYS. Don't freeze that limitation:
         * a future implementation may succeed. The legacy fallback is ALWAYS
         * tested independently, including when fchmodat2 eventually works. */
        if (modern != 0 && modern != -ENOSYS && modern != -1 /* EPERM */) failed = 1;
        expect("fchmodat2.mode", mode(named), modern == 0 ? 0640 : 0600);
        fallback(named, pid, "named");
        expect("unlinked.unlink", raw(35, AT_FDCWD, (long)"/tmp/proc-fd-unlinked", 0, 0), 0);
        fallback(unlinked, pid, "unlinked");
        closed(unlinked, pid);
        raw(57, named, 0, 0, 0);
        containment(pid);
    }
    record("failures", failed != 0);
    record("complete", 1);
    if (raw(57, report_fd, 0, 0, 0) < 0 || report_failed) return 2;
    if (raw(38, AT_FDCWD, (long)"/tmp/proc-fd-permissions.status.tmp",
            AT_FDCWD, (long)"/tmp/proc-fd-permissions.status") < 0) return 2;
    return failed != 0;
}

/* No CRT, dynamic linker, libc initializers or Bionic syscall shims. */
__asm__(".text\n"
        ".global _start\n"
        ".type _start,%function\n"
        "_start:\n"
        "bl probe_main\n"
        "mov x8, #94\n" /* exit_group */
        "svc #0\n"
        "b .\n"
        ".size _start, .-_start\n");
