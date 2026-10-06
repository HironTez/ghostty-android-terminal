#!/bin/sh
# Run INSIDE the app's Alpine terminal: sh diagnose-alpine-network.sh [--install]
# Default changes only APK index caches and writes a new diagnostic directory.
# --install additionally installs openssh. Never changes DNS, repositories,
# certificate validation, package signatures, engine settings or service policy.

case "${1-}" in
    '') install=no ;;
    --install) install=yes ;;
    *) printf 'Usage: sh %s [--install]\n' "$0" >&2; exit 2 ;;
esac
command -v busybox >/dev/null && command -v apk >/dev/null || {
    printf 'Run this script inside Alpine, not the Android shell.\n' >&2
    exit 2
}
out=$(busybox mktemp -d /tmp/alpine-network.XXXXXX) || exit 1
printf 'Diagnostic directory: %s\n' "$out"
(
    phase() {
        label=$1
        seconds=$2
        shift 2
        printf '\n===== %s =====\n' "$label"
        busybox timeout "$seconds" "$@"
        rc=$?
        printf '%s\n' "$rc" > "$out/$label.rc"
        printf 'phase=%s rc=%s\n' "$label" "$rc"
        return 0
    }
    printf 'UTC: '; busybox date -u
    busybox uname -a
    busybox cat /etc/os-release /etc/resolv.conf /etc/apk/repositories
    busybox ls -l /etc/ssl/cert.pem /etc/ssl/certs/ca-certificates.crt
    apk --version
    # These are explicit DNS queries, not persistent resolver overrides.
    phase dns_default 25 busybox nslookup dl-cdn.alpinelinux.org
    phase dns_google 25 busybox nslookup dl-cdn.alpinelinux.org 8.8.8.8
    phase dns_cloudflare 25 busybox nslookup dl-cdn.alpinelinux.org 1.1.1.1
    # wget provides transport contrast, NOT an independent certificate oracle.
    phase https_transport 30 busybox wget -T 10 -S -O /dev/null \
        https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz
    phase http_transport 30 busybox wget -T 10 -S -O /dev/null \
        http://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz
    phase apk_update 120 apk -vv update
    phase openssh_candidates 30 apk -vv add --simulate openssh
    if [ "$install" = yes ]; then
        if [ "$(busybox cat "$out/apk_update.rc")" = 0 ]; then
            phase openssh_install 180 apk -vv add openssh
            phase openssh_installed 20 apk info -e \
                openssh openssh-client-default openssh-server openssh-keygen
            phase ssh_execution 20 ssh -V
            phase sshd_config 20 /usr/sbin/sshd -G
            phase ssh_keygen 30 ssh-keygen -q -t ed25519 -N '' -f "$out/hostkey"
            # Do not include the generated private key in reports.
        else
            printf '\nInstallation skipped: repository refresh failed.\n'
        fi
    fi
    printf '\nCompleted. Phase statuses are in %s/*.rc\n' "$out"
) > "$out/log.txt" 2>&1
busybox cat "$out/log.txt"
printf '\nShare %s/log.txt and the .rc files; do not share hostkey.\n' "$out"
# The process exit status is not an acceptance verdict: inspect every phase rc.
