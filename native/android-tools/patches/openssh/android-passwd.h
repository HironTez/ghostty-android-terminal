/* SPDX-License-Identifier: BSD-2-Clause */
/* Copyright 2026 Sylirre. OpenSSH-local Android client compatibility. */
#ifndef ANDROID_PASSWD_H
#define ANDROID_PASSWD_H
#include <pwd.h>
#include <stdlib.h>
#include <unistd.h>

/* Bionic synthesizes /data and /bin/sh for app UIDs. Never change another
 * user's identity/home and never bypass OpenSSH's key permission checks. */
static inline struct passwd *
android_client_passwd(struct passwd *pw)
{
    static struct passwd current;
    const char *home = getenv("HOME");
    if (pw == NULL || pw->pw_uid != getuid())
        return pw;
    current = *pw;
    if (home != NULL && home[0] == '/')
        current.pw_dir = (char *)home;
    current.pw_shell = "/system/bin/sh";
    return &current;
}
static inline struct passwd *android_client_getpwuid(uid_t uid)
{
    return android_client_passwd(getpwuid(uid));
}
static inline struct passwd *android_client_getpwnam(const char *name)
{
    return android_client_passwd(getpwnam(name));
}
#define getpwuid android_client_getpwuid
#define getpwnam android_client_getpwnam
#undef _PATH_BSHELL
#define _PATH_BSHELL "/system/bin/sh"
#endif
