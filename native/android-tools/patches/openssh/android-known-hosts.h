/* SPDX-License-Identifier: BSD-2-Clause */
/* Copyright 2026 Sylirre. OpenSSH-local Android client compatibility. */
#ifndef ANDROID_KNOWN_HOSTS_H
#define ANDROID_KNOWN_HOSTS_H
/*
 * Crash-safe, serialized known_hosts rewrites without hard links.
 *
 * Upstream backs up with link(file, file.old) and then rename(temp, file):
 * the live name never disappears. Android app domains cannot hard-link app
 * data, and replacing link() with rename(file, file.old) opens a window in
 * which the live file does not exist at all: a crash/kill between the two
 * renames leaves no known_hosts (the next connection silently trusts a new
 * key), concurrent readers see ENOENT, and a concurrent writer can rename the
 * other writer's fresh file away. Here the backup is a fsync'd copy that is
 * renamed into place, so the live name always refers to a complete file; the
 * new contents are fsync'd before the single atomic rename over it, and the
 * directory is fsync'd afterwards. An flock() on a sibling ".lock" file
 * serializes read-modify-write cycles so concurrent writers cannot lose each
 * other's changes. The lock is released automatically if the process dies.
 */
#include <sys/file.h>
#include <sys/stat.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Blocks until the exclusive lock for path is held. Returns an fd or -1. */
static inline int
android_known_hosts_lock(const char *path)
{
	char lock[PATH_MAX];
	int fd;

	if (snprintf(lock, sizeof(lock), "%s.lock", path) >= (int)sizeof(lock)) {
		errno = ENAMETOOLONG;
		return -1;
	}
	if ((fd = open(lock, O_RDWR|O_CREAT|O_CLOEXEC|O_NOFOLLOW, 0600)) == -1)
		return -1;
	while (flock(fd, LOCK_EX) == -1) {
		if (errno != EINTR) {
			int oerrno = errno;
			close(fd);
			errno = oerrno;
			return -1;
		}
	}
	return fd;
}

static inline void
android_known_hosts_unlock(int fd)
{
	if (fd != -1)
		close(fd);	/* releases the flock */
}

static inline int
android_fsync_parent(const char *path)
{
	char dir[PATH_MAX];
	char *slash;
	int fd, r;

	if (strlcpy(dir, path, sizeof(dir)) >= sizeof(dir)) {
		errno = ENAMETOOLONG;
		return -1;
	}
	if ((slash = strrchr(dir, '/')) == NULL)
		strlcpy(dir, ".", sizeof(dir));
	else if (slash == dir)
		dir[1] = '\0';
	else
		*slash = '\0';
	if ((fd = open(dir, O_RDONLY|O_DIRECTORY|O_CLOEXEC)) == -1)
		return -1;
	r = fsync(fd);
	close(fd);
	return r;
}

static inline int
android_fsync_path(const char *path)
{
	int fd, r;

	if ((fd = open(path, O_RDONLY|O_CLOEXEC|O_NOFOLLOW)) == -1)
		return -1;
	r = fsync(fd);
	close(fd);
	return r;
}

/*
 * Replaces back with a durable copy of path. path itself is never renamed,
 * unlinked or modified. The copy is written to a temporary file in the same
 * directory and renamed over back, so back is always either the previous
 * backup or a complete copy.
 */
static inline int
android_known_hosts_backup(const char *path, const char *back)
{
	char tmp[PATH_MAX], buf[8192];
	int in = -1, out = -1, oerrno;
	ssize_t n, w, off;
	struct stat sb;

	if (snprintf(tmp, sizeof(tmp), "%s.XXXXXXXXXX", back) >= (int)sizeof(tmp)) {
		errno = ENAMETOOLONG;
		return -1;
	}
	if ((in = open(path, O_RDONLY|O_CLOEXEC)) == -1)
		return -1;
	if (fstat(in, &sb) == -1 || (out = mkstemp(tmp)) == -1)
		goto fail;
	(void)fchmod(out, sb.st_mode & 0644);
	for (;;) {
		if ((n = read(in, buf, sizeof(buf))) == -1) {
			if (errno == EINTR)
				continue;
			goto fail_unlink;
		}
		if (n == 0)
			break;
		for (off = 0; off < n; off += w) {
			if ((w = write(out, buf + off, n - off)) == -1) {
				if (errno == EINTR) {
					w = 0;
					continue;
				}
				goto fail_unlink;
			}
		}
	}
	if (fsync(out) == -1)
		goto fail_unlink;	/* fail closes out */
	n = close(out);
	out = -1;
	if (n == -1)
		goto fail_unlink;
	close(in);
	in = -1;
	if (rename(tmp, back) == -1)
		goto fail_unlink;
	return 0;
 fail_unlink:
	oerrno = errno;
	unlink(tmp);
	errno = oerrno;
 fail:
	oerrno = errno;
	if (out != -1)
		close(out);
	if (in != -1)
		close(in);
	errno = oerrno;
	return -1;
}

/* Durably and atomically replaces path with the completed file temp. */
static inline int
android_known_hosts_commit(const char *temp, const char *path)
{
	if (android_fsync_path(temp) == -1 || rename(temp, path) == -1)
		return -1;
	(void)android_fsync_parent(path);	/* best effort: data is already safe */
	return 0;
}
#endif
