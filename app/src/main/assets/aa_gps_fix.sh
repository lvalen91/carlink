#!/bin/sh
# /tmp/aa_gps_fix.sh — NMEA divisor patch for ARMAndroidAuto
# Patches double constant from broken 600000.0 to correct 60.0 at vaddr 0x55f04
# Uses only system tools (/bin/pidof, /bin/dd, /usr/bin/printf) — zero deps.
# Author: lvalentinzeno (carlink_native RE session, May 2026)

VADDR_HEX=55f04
LOG=/tmp/aa_gps_fix.log
PIDFILE=/tmp/aa_gps_fix.pid

# Single-instance guard: if a prior invocation is still alive, exit silently.
# Prevents stacking N watchers when the host app re-inits without an adapter
# reboot (each init re-injects via BoxSettings → would spawn a new daemon).
if [ -f "$PIDFILE" ]; then
    OLDPID=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$OLDPID" ] && [ -d "/proc/$OLDPID" ]; then
        echo "[aa_gps_fix] already running as PID $OLDPID — exiting" >> $LOG
        exit 0
    fi
fi
echo $$ > "$PIDFILE"

echo "[aa_gps_fix] started at $(date) — PID $$" > $LOG
LAST_PID=""

while true; do
    PID=$(/bin/pidof ARMAndroidAuto 2>/dev/null)
    if [ -n "$PID" ] && [ "$PID" != "$LAST_PID" ]; then
        if [ -w /proc/$PID/mem ]; then
            /usr/bin/printf "\x00\x00\x4e\x40" | /bin/dd of=/proc/$PID/mem bs=1 seek=$((0x$VADDR_HEX)) count=4 conv=notrunc 2>/dev/null
            if [ $? -eq 0 ]; then
                LAST_PID="$PID"
                echo "[aa_gps_fix] $(date "+%H:%M:%S") patched PID $PID" >> $LOG
            fi
        fi
    fi
    sleep 2
done
