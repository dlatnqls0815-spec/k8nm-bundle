#!/system/bin/sh

# Set standard Android PATH and clear inherited inbox env
PATH="/system/bin:/system/xbin:/sbin"
export PATH
unset SG_HSM_INBOX

# Resolve package root and paths
case "$0" in
    */*) H="${0%/*}/.." ;;
    *) H=".." ;;
esac
HSM_DIR="$H/hsm"
ORDER="$HSM_DIR/order.txt"
DONE="$H/seogaemo.done"
FAIL_FILE="$H/seogaemo.fail"
LOG="$H/seogaemo.log"
SG="/data/nemesis/apps/hmg-seogaemo/bin/hmg-seogaemo-root"
I="/data/data/com.seogaemo.hmg/files/hsm"
SH="/system/bin/sh"

CR=$'\r'
TAB=$'\t'

# Rest if already completed
if [ -f "$DONE" ]; then
    sleep 86400
    exit 0
fi

ts() {
    date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date
}

log() {
    print -r -- "$(ts) $*" >> "$LOG"
}

log_text() {
    if [ -n "$1" ]; then
        print -r -- "$1" | while IFS= read -r _line || [ -n "$_line" ]; do
            _line="${_line%"$CR"}"
            log "$_line"
        done
    fi
}

# Require full effective capabilities
has_full_caps=0
if [ -r /proc/self/status ]; then
    while IFS= read -r _line || [ -n "$_line" ]; do
        case "$_line" in
            CapEff:*3fffffffff*)
                has_full_caps=1
                break
                ;;
        esac
    done < /proc/self/status
fi

if [ "$has_full_caps" -ne 1 ]; then
    log "limited caps; retry later"
    sleep 15
    exit 0
fi

log "seogaemo service start"

# Wait for boot completion
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    sleep 5
done

# Wait 40s more if uptime is below 60s
uptime_sec=0
if [ -r /proc/uptime ]; then
    IFS=" $TAB" read -r _up_raw _up_rest < /proc/uptime
    _up_int="${_up_raw%%.*}"
    case "$_up_int" in
        ''|*[!0-9]*) uptime_sec=0 ;;
        *) uptime_sec="$_up_int" ;;
    esac
fi

if [ "$uptime_sec" -lt 60 ]; then
    log "uptime ${uptime_sec}s < 60s; waiting 40s"
    sleep 40
fi

install_ok=0

if [ ! -f "$SG" ]; then
    log "missing seogaemo script: $SG"
elif [ ! -f "$ORDER" ]; then
    log "missing order file: $ORDER"
else
    # Read order.txt on fd 3 so child commands cannot consume it
    exec 3< "$ORDER"
    while IFS=" $TAB" read -r mod_id sha _extra <&3 || [ -n "$mod_id" ]; do
        mod_id="${mod_id%"$CR"}"
        sha="${sha%"$CR"}"

        # Skip blank lines and comments
        case "$mod_id" in
            ''|'#'*) continue ;;
        esac
        case "$sha" in
            ''|'#'*)
                log "module $mod_id: fail (missing sha256)"
                continue
                ;;
        esac

        hsm_src="$HSM_DIR/$sha.hsm"
        if [ ! -f "$hsm_src" ]; then
            log "module $mod_id: fail (missing $hsm_src)"
            continue
        fi

        # Revert module first (failure is allowed)
        log "module $mod_id: revert"
        out=$("$SH" "$SG" "$mod_id" revert < /dev/null 2>&1)
        rc="$?"
        log_text "$out"
        log "module $mod_id: revert rc=$rc"

        # Primary install via SG_HSM_INBOX
        installed=0
        log "module $mod_id: install $sha"
        out=$(SG_HSM_INBOX="$HSM_DIR" "$SH" "$SG" module install "$sha" < /dev/null 2>&1)
        rc="$?"
        log_text "$out"
        case "$out" in
            *"Installed $mod_id"*)
                # same success test as k8svm.fix: the installer's own message
                installed=1
                ;;
        esac

        # Fallback: copy to app inbox, retry install, then delete temporary copy
        if [ "$installed" -ne 1 ]; then
            log "module $mod_id: inbox fallback (rc=$rc)"
            dst="$I/$sha.hsm"
            out=$(mkdir -p "$I" 2>&1)
            rc="$?"
            log_text "$out"
            if [ "$rc" -eq 0 ]; then
                out=$( (cat "$hsm_src" > "$dst") 2>&1 )
                rc="$?"
                log_text "$out"
                if [ "$rc" -eq 0 ]; then
                    out=$(chmod 644 "$dst" 2>&1)
                    rc="$?"
                    log_text "$out"
                    if [ "$rc" -eq 0 ]; then
                        out=$("$SH" "$SG" module install "$sha" < /dev/null 2>&1)
                        rc="$?"
                        log_text "$out"
                        case "$out" in
                            *"Installed $mod_id"*)
                                installed=1
                                ;;
                        esac
                    else
                        log "module $mod_id: chmod 644 failed (rc=$rc)"
                    fi
                else
                    log "module $mod_id: copy to inbox failed (rc=$rc)"
                fi
                # Delete only the temporary inbox copy
                if [ -f "$dst" ]; then
                    out=$(rm -f "$dst" 2>&1)
                    log_text "$out"
                fi
            else
                log "module $mod_id: mkdir inbox failed (rc=$rc)"
            fi
        fi

        # Apply only confirmed installations
        if [ "$installed" -eq 1 ]; then
            install_ok="$((install_ok + 1))"
            log "module $mod_id: apply"
            out=$("$SH" "$SG" "$mod_id" apply < /dev/null 2>&1)
            rc="$?"
            log_text "$out"
            if [ "$rc" -eq 0 ]; then
                log "module $mod_id: ok"
            else
                log "module $mod_id: fail (apply rc=$rc)"
            fi
        else
            log "module $mod_id: fail (install)"
        fi
    done
    exec 3<&-
fi

# Record module list at the end
if [ -f "$SG" ]; then
    log "module list:"
    out=$("$SH" "$SG" module list < /dev/null 2>&1)
    rc="$?"
    log_text "$out"
    log "module list rc=$rc"
fi

# Mark done and reboot once if any install succeeded
if [ "$install_ok" -gt 0 ]; then
    log "installed $install_ok module(s); marking done and rebooting"
    print -r -- "$(ts)" > "$DONE"
    sync
    out=$("$SH" "$SG" device reboot < /dev/null 2>&1)
    rc="$?"
    log_text "$out"
    log "device reboot rc=$rc"
    sleep 86400
    exit 0
fi

# All installs failed: increment fail counter
log "all failed"

fail_count=0
if [ -r "$FAIL_FILE" ]; then
    IFS= read -r _raw_fc < "$FAIL_FILE"
    _raw_fc="${_raw_fc%"$CR"}"
    case "$_raw_fc" in
        ''|*[!0-9]*) fail_count=0 ;;
        *)
            while [ "${_raw_fc#0}" != "$_raw_fc" ] && [ -n "${_raw_fc#0}" ]; do
                _raw_fc="${_raw_fc#0}"
            done
            fail_count="$_raw_fc"
            ;;
    esac
fi

fail_count="$((fail_count + 1))"
print -r -- "$fail_count" > "$FAIL_FILE"
log "fail count: $fail_count"

# Rest permanently without reboot after 3 failures
if [ "$fail_count" -ge 3 ]; then
    log "fail limit reached; marking done without reboot"
    print -r -- "$(ts)" > "$DONE"
    sync
    sleep 86400
    exit 0
fi

log "retrying in 600s"
sleep 600
exit 0
