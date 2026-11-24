#!/system/bin/sh
# ==============================================================================
# True AOSP Userspace Reboot Engine + Full KernelSU Next Module Lifecycle
# Target Context : UID 0 (root) | SELinux: u:r:ksu:s0 | Mount NS: PID 1
# SoC / Platform : Qualcomm bengal (SM6115/SM6225) & Honor MagicOS 7/8/9/10+
# Usage          : userspace_reboot.sh [--audit | --dry-run | --probe]
# ==============================================================================

PATH=/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk:/system/bin:/system/xbin:/vendor/bin:$PATH
export PATH

LOG_FILE="/data/adb/ScriptLogs.txt"
PREV_LOG_FILE="/data/adb/ScriptLogs.prev.txt"
WAKE_LOCK_NAME="aosp_userspace_reboot_emul"

# --- Configurable Behavior Flags ---
RUN_KSU_MODULE_STAGES=1       # 1 = run full KSU module lifecycle (teardown -> post-fs-data -> service -> boot-completed)
KSU_POST_FS_TIMEOUT_SEC=10    # Per-script timeout (seconds) for blocking post-fs-data scripts (matches ksud)
RESTART_STATELESS_HALS=1      # 1 = cycle Qualcomm bengal & Honor AIDL/HIDL HALs bottom-up
RESTART_CORE_DAEMONS=1        # 1 = cycle installd, storaged, statsd, credstore, gatekeeperd, gpuservice
RUN_VDC_VOLUME_RESET=1        # 1 = run `vdc volume abort_fuse` + `vdc volume reset` (AOSP DoUserspaceReboot)
REAP_ORPHAN_APP_CGROUPS=1     # 1 = kill orphaned UID >= 10000 app cgroups before FUSE abort
DROP_KERNEL_CACHES=1          # 1 = flush & drop clean pagecache/dentries/inodes during quiesce
WAKE_SCREEN_ON_COMPLETE=1     # 1 = send KEYCODE_WAKEUP once WindowManager & PowerManager are ready
HARD_REBOOT_ON_FAILURE=0      # 1 = fallback to full kernel reboot if userspace fails recovery

SCRIPT_PATH="$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")"

MODE="REBOOT"
for arg in "$@"; do
    case "$arg" in
        --audit|-a|--dry-run) MODE="AUDIT" ;;
        --probe|-p) MODE="PROBE" ;;
    esac
done

find_bin() {
    name="$1"
    shift
    for path in "$@" "$(command -v "$name" 2>/dev/null)"; do
        [ -n "$path" ] && [ -x "$path" ] && echo "$path" && return 0
    done
    return 1
}

KSUD_BIN="$(find_bin ksud /data/adb/ksud /debug_ramdisk/ksud /sbin/ksud)"
RESETPROP_BIN="$(find_bin resetprop /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop)"
KSU_BUSYBOX="$(find_bin busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox /data/adb/magisk/busybox)"

classify_init_service() {
    svc="$1"
    case "$svc" in
        # 1. IMMUTABLE ANCHOR & KERNEL/SUBSYSTEM CRITICAL (NEVER STOP OR RESTART)
        servicemanager|hwservicemanager|vndservicemanager|\
        ueventd|logd|lmkd|tombstoned|traced|traced_probes|heapprofd|bpf_loader|\
        vold|keystore2|apexd|gsid|adbd|usbd|ksud|magiskd|apd|\
        teecd|qseecomd|ssgtzd|itrustee*|securityserver|hn_security*|\
        *adsprpcd*|*cdsprpcd*|*sdsprpcd*|*peripheral_manager*|*pd-mapper*|\
        *qrtr*|*rmt_storage*|*tftp_server*|*irsc_util*|*cnss*|*netmgrd*|\
        *port-bridge*|*qmuxd*|*qcril*|*ril-daemon*|*rild*|*dpmQmiMgr*|\
        *loc_launcher*|*lowi-server*|*xtra-daemon*|*time_daemon*|*msm_irqbalance*|\
        *spdaemon*|*keymint*|*keymaster*|*weaver*|*soter*|*widevine*|*clearkey*|*ims*)
            echo "PROTECTED"
            ;;

        # 2. FRAMEWORK & MAGICOS LAYER (STOPPED IN PHASE 2, STARTED IN PHASE 5)
        zygote|zygote_secondary|webview_zygote|usap64|usap32|\
        surfaceflinger|bootanim|gpuservice|\
        audioserver|cameraserver|media|media.tuner|mediacodec|media.extractor|media.metrics|mediadrmserver|\
        wificond|wpa_supplicant|hostapd|mdnsd|\
        iawared|uniperf|perfgenius|agpservice|aptouch_daemon|dubaid|hiview|oases|magic_server)
            echo "FRAMEWORK"
            ;;

        # 3. CORE INFRASTRUCTURE LAYER (CYCLED IN PHASE 2/4 BEFORE ZYGOTE)
        installd|storaged|statsd|incidentd|credstore|gatekeeperd)
            echo "CORE_INFRA"
            ;;

        # 4. GRAPHICS / DISPLAY HAL LAYER (CYCLED BOTTOM-UP: ALLOCATOR -> COMPOSER -> DISPLAYENGINE)
        *allocator*|*composer*|*hwcomposer*|*displayengine*|*qdcm*|*ppd*)
            echo "GRAPHICS_HAL"
            ;;

        # 5. AUDIO SUBSYSTEM HAL LAYER (BENGAL AGMIPC/PAL -> AUDIO HAL -> AUDIOSERVER)
        *AGMIPC*|*agm*|*pal*|*ListenSoundModel*|*audio*)
            echo "AUDIO_HAL"
            ;;

        # 6. STATELESS HARDWARE / VENDOR HALS (CAMERA, SENSORS, BIOMETRICS, BT, WIFI, GNSS, POWER)
        vendor.*|android.hardware.*|honor.hardware.*|odm.*|\
        *camera*|*biometrics*|*fingerprint*|*face*|*sensors*|*vibrator*|\
        *bluetooth*|*gnss*|*nfc*|*thermal*|*light*|*memtrack*|*power*|*health*|*usb*|*wifi*|*perf*)
            echo "VENDOR_HAL"
            ;;

        # 7. UNCLASSIFIED / OEM AUXILIARY SERVICES (OBSERVED, UNTOUCHED UNLESS FALLBACK)
        *)
            echo "OBSERVE"
            ;;
    esac
}

get_pids() {
    pidof "$1" 2>/dev/null | tr '\n' ' ' | sed 's/ $//'
}

# ==============================================================================
# 0A. Fast Telemetry Probe Mode (--probe)
# ==============================================================================
run_probe_mode() {
    uid_now="$(id -u)"
    ctx_now="$(cat /proc/self/attr/current 2>/dev/null | tr -d '\0')"
    sdk_now="$(getprop ro.build.version.sdk)"
    rel_now="$(getprop ro.build.version.release)"
    magic_now="$(getprop ro.build.version.magic)"
    [ -z "$magic_now" ] && magic_now="$(getprop msc.config.magic_version)"
    model_now="$(getprop ro.product.model)"
    plat_now="$(getprop ro.board.platform)"
    hw_now="$(getprop ro.hardware)"
    kern_now="$(uname -r 2>/dev/null)"
    ce_now="$(getprop sys.user.0.ce_available)"
    apex_now="$(getprop apexd.status)"
    up_now="$(uptime -p 2>/dev/null || uptime 2>/dev/null)"

    ksu_ver=""
    if [ -n "$KSUD_BIN" ]; then
        ksu_ver="$("$KSUD_BIN" -V 2>/dev/null || "$KSUD_BIN" --version 2>/dev/null)"
    fi

    mod_active=0
    if [ -d /data/adb/modules ]; then
        for mod_dir in /data/adb/modules/*; do
            [ -d "$mod_dir" ] || continue
            [ -e "$mod_dir/disable" ] || [ -e "$mod_dir/remove" ] && continue
            mod_active=$((mod_active + 1))
        done
    fi

    echo "PROBE|uid=$uid_now"
    echo "PROBE|selinux=${ctx_now:-unknown}"
    echo "PROBE|sdk=${sdk_now:-unknown}"
    echo "PROBE|android=${rel_now:-unknown}"
    echo "PROBE|magicos=${magic_now:-N/A}"
    echo "PROBE|model=${model_now:-unknown}"
    echo "PROBE|platform=${plat_now:-unknown}"
    echo "PROBE|hardware=${hw_now:-unknown}"
    echo "PROBE|kernel=${kern_now:-unknown}"
    echo "PROBE|ce_available=${ce_now:-unknown}"
    echo "PROBE|apexd=${apex_now:-unknown}"
    echo "PROBE|uptime=${up_now:-unknown}"
    echo "PROBE|ksu_ver=${ksu_ver:-none}"
    echo "PROBE|ksu_modules=$mod_active"
    echo "PROBE|zygote64=$(get_pids zygote64)"
    echo "PROBE|system_server=$(get_pids system_server)"
    echo "PROBE|surfaceflinger=$(get_pids surfaceflinger)"
    exit 0
}

if [ "$MODE" = "PROBE" ]; then
    run_probe_mode
fi

# ==============================================================================
# 0B. Pre-Flight Dry-Run / Audit Mode (--audit | --dry-run)
# ==============================================================================
run_audit_mode() {
    uid_now="$(id -u)"
    ctx_now="$(cat /proc/self/attr/current 2>/dev/null | tr -d '\0')"
    sdk_now="$(getprop ro.build.version.sdk)"
    magic_now="$(getprop ro.build.version.magic)"
    [ -z "$magic_now" ] && magic_now="$(getprop msc.config.magic_version)"
    plat_now="$(getprop ro.board.platform)"
    hw_now="$(getprop ro.hardware)"
    kern_now="$(uname -r 2>/dev/null)"
    ce_now="$(getprop sys.user.0.ce_available)"
    apex_now="$(getprop apexd.status)"

    audit_pass=1
    fail_reasons=""

    echo "=============================================================================="
    echo " TRUE AOSP USERSPACE REBOOT + KSU LIFECYCLE — PRE-FLIGHT DRY-RUN AUDIT"
    echo "=============================================================================="
    echo "INFO|uid=$uid_now|ctx=${ctx_now:-unknown}|sdk=$sdk_now|magicos=${magic_now:-N/A}|platform=$plat_now|hardware=$hw_now|kernel=$kern_now|ce=$ce_now|apex=${apex_now:-unknown}"

    # Check 1: Root UID 0
    if [ "$uid_now" -eq 0 ]; then
        echo "CHECK|root_uid|PASS|Root UID 0 Access|UID 0 ($ctx_now)"
    else
        echo "CHECK|root_uid|FAIL|Root UID 0 Access|Current UID is $uid_now (must be 0)"
        audit_pass=0
        fail_reasons="${fail_reasons}No UID 0 root; "
    fi

    # Check 2: PID 1 Mount Namespace
    init_ns="$(readlink /proc/1/ns/mnt 2>/dev/null)"
    self_ns="$(readlink /proc/self/ns/mnt 2>/dev/null)"
    if [ -n "$init_ns" ] && [ "$init_ns" = "$self_ns" ]; then
        echo "CHECK|mount_ns|PASS|PID 1 Mount Namespace|Already inside global init mount namespace"
    elif [ -r /proc/1/ns/mnt ] && command -v nsenter >/dev/null 2>&1; then
        echo "CHECK|mount_ns|PASS|PID 1 Mount Namespace|nsenter available for PID 1 transition"
    else
        echo "CHECK|mount_ns|WARN|PID 1 Mount Namespace|nsenter unavailable; ensure su uses global mount namespace"
    fi

    # Check 3: User 0 CE Storage Keyring
    if [ "$ce_now" = "true" ]; then
        echo "CHECK|ce_storage|PASS|CE Storage Keyring|sys.user.0.ce_available=true"
    else
        echo "CHECK|ce_storage|FAIL|CE Storage Keyring|CE storage not unlocked (sys.user.0.ce_available=${ce_now:-unset})"
        audit_pass=0
        fail_reasons="${fail_reasons}CE storage locked; "
    fi

    # Check 4: APEX Readiness
    if [ -z "$apex_now" ] || [ "$apex_now" = "ready" ] || [ "$apex_now" = "activated" ]; then
        echo "CHECK|apex_ready|PASS|APEX Subsystem|apexd.status=${apex_now:-unset}"
    else
        echo "CHECK|apex_ready|FAIL|APEX Subsystem|apexd.status=$apex_now (not ready)"
        audit_pass=0
        fail_reasons="${fail_reasons}APEX not ready; "
    fi

    # Check 5: Core Runtime Alive (Zygote, system_server, SurfaceFlinger)
    z_pid="$(get_pids zygote64)"
    [ -z "$z_pid" ] && z_pid="$(get_pids zygote)"
    ss_pid="$(get_pids system_server)"
    sf_pid="$(get_pids surfaceflinger)"
    if [ -n "$z_pid" ] && [ -n "$ss_pid" ] && [ -n "$sf_pid" ]; then
        echo "CHECK|core_procs|PASS|Core Framework Alive|zygote=$z_pid, system_server=$ss_pid, surfaceflinger=$sf_pid"
    else
        echo "CHECK|core_procs|FAIL|Core Framework Alive|Missing core PID (z=${z_pid:-none} ss=${ss_pid:-none} sf=${sf_pid:-none})"
        audit_pass=0
        fail_reasons="${fail_reasons}Core process missing; "
    fi

    # Check 6: vdc / vold & cgroup v2 reaper readiness
    if [ -x /system/bin/vdc ]; then
        echo "CHECK|vdc_bin|PASS|Volume Daemon Controller|/system/bin/vdc ready for FUSE/volume reset"
    else
        echo "CHECK|vdc_bin|WARN|Volume Daemon Controller|/system/bin/vdc missing; FUSE reset will be skipped"
    fi

    # Check 7: KernelSU Next Tooling
    if [ -n "$KSUD_BIN" ] || [ -n "$RESETPROP_BIN" ]; then
        echo "CHECK|ksu_tools|PASS|KernelSU Next Runtime|ksud=${KSUD_BIN:-none} resetprop=${RESETPROP_BIN:-none} busybox=${KSU_BUSYBOX:-none}"
    else
        echo "CHECK|ksu_tools|WARN|KernelSU Next Runtime|ksud/resetprop not found; falling back to system sh/setprop"
    fi

    tmp_dir="/data/local/tmp/.usr_audit.$$"
    mkdir -p "$tmp_dir" 2>/dev/null
    for bucket in FRAMEWORK CORE_INFRA GRAPHICS_HAL AUDIO_HAL VENDOR_HAL PROTECTED OBSERVE; do
        : > "$tmp_dir/$bucket"
    done

    getprop | while IFS= read -r line; do
        case "$line" in
            \[init.svc.*\]:\ \[running\])
                svc="${line#\[init.svc.}"
                svc="${svc%%\]: \[running\]}"
                [ -z "$svc" ] && continue

                base_name="${svc##*.}"
                p_list="$(pidof "$svc" 2>/dev/null)"
                [ -z "$p_list" ] && p_list="$(pidof "$base_name" 2>/dev/null)"
                first_p="$(echo "$p_list" | awk '{print $1}')"
                if [ -n "$first_p" ] && [ -r "/proc/$first_p/attr/current" ]; then
                    s_ctx="$(cat "/proc/$first_p/attr/current" 2>/dev/null | tr -d '\0')"
                    meta="PID=$first_p | $s_ctx"
                else
                    meta="init-managed"
                fi

                role="$(classify_init_service "$svc")"
                echo "SVC|$role|$svc|${first_p:-init}|${s_ctx:-init-managed}" >> "$tmp_dir/$role"
                ;;
        esac
    done

    for key in FRAMEWORK CORE_INFRA GRAPHICS_HAL AUDIO_HAL VENDOR_HAL PROTECTED OBSERVE; do
        cnt="$(wc -l < "$tmp_dir/$key" | tr -d ' ')"
        echo "BUCKET|$key|$cnt"
        cat "$tmp_dir/$key"
    done

    if [ -d /data/adb/modules ]; then
        for mod_dir in /data/adb/modules/*; do
            [ -d "$mod_dir" ] || continue
            mod_id="$(basename "$mod_dir")"
            if [ -e "$mod_dir/disable" ] || [ -e "$mod_dir/remove" ]; then
                echo "MOD|SKIPPED|$mod_id|disabled_or_remove"
                continue
            fi
            hooks=""
            [ -f "$mod_dir/emulated-soft-reboot.sh" ] && hooks="${hooks}teardown,"
            [ -f "$mod_dir/post-fs-data.sh" ]         && hooks="${hooks}post-fs-data,"
            [ -f "$mod_dir/system.prop" ]             && hooks="${hooks}system.prop,"
            [ -f "$mod_dir/service.sh" ]              && hooks="${hooks}service,"
            [ -f "$mod_dir/boot-completed.sh" ]       && hooks="${hooks}boot-completed,"
            hooks="${hooks%,}"
            [ -z "$hooks" ] && hooks="overlay-only"
            echo "MOD|ACTIVE|$mod_id|$hooks"
        done
    fi

    for d_stage in post-fs-data.d service.d boot-completed.d; do
        if [ -d "/data/adb/$d_stage" ]; then
            for s_file in "/data/adb/$d_stage"/*.sh; do
                [ -f "$s_file" ] && echo "DSCRIPT|$d_stage|$(basename "$s_file")"
            done
        fi
    done

    rm -rf "$tmp_dir" 2>/dev/null
    if [ "$audit_pass" -eq 1 ]; then
        echo "AUDIT_SUMMARY|PASS|All pre-flight invariants verified."
        exit 0
    else
        echo "AUDIT_SUMMARY|FAIL|${fail_reasons}"
        exit 2
    fi
}

if [ "$MODE" = "AUDIT" ]; then
    run_audit_mode
fi

# ==============================================================================
# 1. Root Check, PID 1 Mount Namespace & Cgroup/PTY Detachment
# ==============================================================================
if [ "$(id -u)" -ne 0 ]; then
    echo "[FATAL] Must run as UID 0 (root). Current UID: $(id -u)" >&2
    exit 1
fi

# Enter PID 1 (init) global mount namespace so mount/FUSE/APEX state is global
if [ -r /proc/1/ns/mnt ] && [ -r /proc/self/ns/mnt ]; then
    init_ns="$(readlink /proc/1/ns/mnt 2>/dev/null)"
    self_ns="$(readlink /proc/self/ns/mnt 2>/dev/null)"
    if [ -n "$init_ns" ] && [ "$init_ns" != "$self_ns" ] && command -v nsenter >/dev/null 2>&1; then
        exec nsenter -t 1 -m -- /system/bin/sh "$SCRIPT_PATH" "$@"
    fi
fi

# Detach from caller app / PTY so SIGHUP or app cgroup kills cannot abort us
if [ "${_USERSPACE_REBOOT_DETACHED:-0}" != "1" ]; then
    export _USERSPACE_REBOOT_DETACHED=1
    if command -v setsid >/dev/null 2>&1; then
        setsid /system/bin/sh "$SCRIPT_PATH" "$@" </dev/null >/dev/null 2>&1 &
    else
        nohup /system/bin/sh "$SCRIPT_PATH" "$@" </dev/null >/dev/null 2>&1 &
    fi
    echo "[INFO] Userspace reboot detached in background (PID $!). Logs: $LOG_FILE"
    exit 0
fi

trap '' HUP

# Escape caller app cgroup into root UID 0 cgroup (Android 15 cgroup2 + cgroup1)
ESCAPED_CG=0
for cg in \
    /sys/fs/cgroup/system/uid_0/cgroup.procs \
    /sys/fs/cgroup/uid_0/cgroup.procs \
    /sys/fs/cgroup/system/cgroup.procs \
    /sys/fs/cgroup/cgroup.procs \
    /acct/uid_0/tasks \
    /acct/tasks; do
    if [ -w "$cg" ]; then
        if echo "$$" > "$cg" 2>/dev/null; then
            ESCAPED_CG=1
            break
        fi
    fi
done

# ==============================================================================
# 2. Logging & Kernel Wakelock
# ==============================================================================
log_msg() {
    level="$1"
    shift
    ts="$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo '00:00:00')"
    msg="[$ts] [$level] $*"
    echo "$msg" >> "$LOG_FILE" 2>/dev/null
    if [ -w /dev/kmsg ]; then
        echo "<6>userspace_reboot: [$level] $*" > /dev/kmsg 2>/dev/null
    fi
}

acquire_wakelock() {
    [ -w /sys/power/wake_lock ] && echo "$WAKE_LOCK_NAME" > /sys/power/wake_lock 2>/dev/null
}

release_wakelock() {
    [ -w /sys/power/wake_unlock ] && echo "$WAKE_LOCK_NAME" > /sys/power/wake_unlock 2>/dev/null
}

trap 'release_wakelock' EXIT INT TERM

[ -f "$LOG_FILE" ] && cp -f "$LOG_FILE" "$PREV_LOG_FILE" 2>/dev/null
: > "$LOG_FILE" 2>/dev/null
chmod 0644 "$LOG_FILE" 2>/dev/null
acquire_wakelock

SELINUX_CTX="$(cat /proc/self/attr/current 2>/dev/null | tr -d '\0')"
CGROUP_NOW="$(tr '\n' ';' < /proc/$$/cgroup 2>/dev/null)"
SDK_VER="$(getprop ro.build.version.sdk)"
MAGIC_VER="$(getprop ro.build.version.magic)"
[ -z "$MAGIC_VER" ] && MAGIC_VER="$(getprop msc.config.magic_version)"
PLATFORM="$(getprop ro.board.platform)"
HARDWARE="$(getprop ro.hardware)"
KERNEL_REL="$(uname -r 2>/dev/null)"

log_msg "INFO" "=================================================================="
log_msg "INFO" "PHASE 0: AOSP Userspace Reboot + KernelSU Module Lifecycle Engine"
log_msg "INFO" "PID=$$ | UID=0 | CTX=${SELINUX_CTX:-unknown} | SDK=$SDK_VER | MagicOS=${MAGIC_VER:-N/A} | Platform=$PLATFORM ($HARDWARE) | Kernel=${KERNEL_REL:-unknown}"
log_msg "INFO" "CgroupEscaped=$ESCAPED_CG | Cgroup=${CGROUP_NOW:-unknown}"

# ==============================================================================
# 3. Core & KernelSU Runtime Helpers
# ==============================================================================
export KSU=true
export KSU_NEXT=true
export ASH_STANDALONE=1
if [ -n "$KSUD_BIN" ]; then
    ksu_ver_str="$("$KSUD_BIN" -V 2>/dev/null || "$KSUD_BIN" --version 2>/dev/null)"
    [ -n "$ksu_ver_str" ] && export KSU_VER="$ksu_ver_str"
fi

exec_ksu_script_sync() {
    s_path="$1"
    s_dir="$(dirname "$s_path")"
    real_s="$(readlink -f "$s_path" 2>/dev/null || echo "$s_path")"
    [ "$real_s" = "$SCRIPT_PATH" ] && return 0

    (
        cd "$s_dir" 2>/dev/null || cd /data/adb
        if [ -n "$KSU_BUSYBOX" ]; then
            exec "$KSU_BUSYBOX" sh "$s_path"
        else
            exec /system/bin/sh "$s_path"
        fi
    ) >> "$LOG_FILE" 2>&1 &
    child_pid=$!

    elapsed=0
    max_ds=$((KSU_POST_FS_TIMEOUT_SEC * 10))
    while kill -0 "$child_pid" 2>/dev/null; do
        if [ "$elapsed" -ge "$max_ds" ]; then
            log_msg "WARN" "KSU script exceeded ${KSU_POST_FS_TIMEOUT_SEC}s timeout, terminating: $s_path"
            kill -9 "$child_pid" 2>/dev/null
            wait "$child_pid" 2>/dev/null
            return 124
        fi
        sleep 0.1
        elapsed=$((elapsed + 1))
    done
    wait "$child_pid" 2>/dev/null
    return $?
}

exec_ksu_script_async() {
    s_path="$1"
    s_dir="$(dirname "$s_path")"
    real_s="$(readlink -f "$s_path" 2>/dev/null || echo "$s_path")"
    [ "$real_s" = "$SCRIPT_PATH" ] && return 0

    (
        cd "$s_dir" 2>/dev/null || cd /data/adb
        if [ -n "$KSU_BUSYBOX" ]; then
            exec "$KSU_BUSYBOX" sh "$s_path"
        else
            exec /system/bin/sh "$s_path"
        fi
    ) </dev/null >> "$LOG_FILE" 2>&1 &
}

run_ksu_stage() {
    stage="$1"
    [ "$RUN_KSU_MODULE_STAGES" -eq 1 ] || return 0

    case "$stage" in
        teardown)
            if [ -d /data/adb/modules ]; then
                for mod_dir in /data/adb/modules/*; do
                    [ -d "$mod_dir" ] || continue
                    [ -e "$mod_dir/disable" ] || [ -e "$mod_dir/remove" ] && continue
                    hook="$mod_dir/emulated-soft-reboot.sh"
                    if [ -f "$hook" ]; then
                        log_msg "INFO" "[KSU:teardown] Running $hook"
                        exec_ksu_script_sync "$hook"
                    fi
                done
            fi
            ;;

        post-fs-data)
            if [ -d /data/adb/post-fs-data.d ]; then
                for s_file in /data/adb/post-fs-data.d/*.sh; do
                    [ -f "$s_file" ] || continue
                    log_msg "INFO" "[KSU:post-fs-data.d] Running $s_file"
                    exec_ksu_script_sync "$s_file"
                done
            fi
            if [ -d /data/adb/modules ]; then
                for mod_dir in /data/adb/modules/*; do
                    [ -d "$mod_dir" ] || continue
                    [ -e "$mod_dir/disable" ] || [ -e "$mod_dir/remove" ] && continue
                    p_script="$mod_dir/post-fs-data.sh"
                    if [ -f "$p_script" ]; then
                        log_msg "INFO" "[KSU:post-fs-data] Running $p_script"
                        exec_ksu_script_sync "$p_script"
                    fi
                    s_prop="$mod_dir/system.prop"
                    if [ -f "$s_prop" ] && [ -n "$RESETPROP_BIN" ]; then
                        log_msg "INFO" "[KSU:system.prop] Loading $s_prop via resetprop -n"
                        "$RESETPROP_BIN" -n --file "$s_prop" 2>/dev/null || "$RESETPROP_BIN" --file "$s_prop" 2>/dev/null
                    fi
                done
            fi
            ;;

        service)
            if [ -d /data/adb/service.d ]; then
                for s_file in /data/adb/service.d/*.sh; do
                    [ -f "$s_file" ] || continue
                    log_msg "INFO" "[KSU:service.d] Spawning $s_file"
                    exec_ksu_script_async "$s_file"
                done
            fi
            if [ -d /data/adb/modules ]; then
                for mod_dir in /data/adb/modules/*; do
                    [ -d "$mod_dir" ] || continue
                    [ -e "$mod_dir/disable" ] || [ -e "$mod_dir/remove" ] && continue
                    svc_script="$mod_dir/service.sh"
                    if [ -f "$svc_script" ]; then
                        log_msg "INFO" "[KSU:service] Spawning $svc_script"
                        exec_ksu_script_async "$svc_script"
                    fi
                done
            fi
            ;;

        boot-completed)
            if [ -d /data/adb/boot-completed.d ]; then
                for s_file in /data/adb/boot-completed.d/*.sh; do
                    [ -f "$s_file" ] || continue
                    log_msg "INFO" "[KSU:boot-completed.d] Spawning $s_file"
                    exec_ksu_script_async "$s_file"
                done
            fi
            if [ -d /data/adb/modules ]; then
                for mod_dir in /data/adb/modules/*; do
                    [ -d "$mod_dir" ] || continue
                    [ -e "$mod_dir/disable" ] || [ -e "$mod_dir/remove" ] && continue
                    bc_script="$mod_dir/boot-completed.sh"
                    if [ -f "$bc_script" ]; then
                        log_msg "INFO" "[KSU:boot-completed] Spawning $bc_script"
                        exec_ksu_script_async "$bc_script"
                    fi
                done
            fi
            ;;
    esac
}

prop_set_trigger() {
    key="$1"
    val="$2"
    setprop "$key" "$val" 2>/dev/null
    if [ "$(getprop "$key")" != "$val" ] && [ -n "$RESETPROP_BIN" ]; then
        "$RESETPROP_BIN" "$key" "$val" 2>/dev/null
    fi
}

prop_del() {
    key="$1"
    if [ -n "$RESETPROP_BIN" ]; then
        "$RESETPROP_BIN" -d "$key" 2>/dev/null
    else
        setprop "$key" "" 2>/dev/null
    fi
}

svc_exists() {
    [ -n "$(getprop "init.svc.$1")" ]
}

svc_is_running() {
    [ "$(getprop "init.svc.$1")" = "running" ]
}

wait_svc_state() {
    svc="$1"
    target="$2"
    timeout_ds="${3:-60}"
    elapsed=0
    while [ "$elapsed" -lt "$timeout_ds" ]; do
        [ "$(getprop "init.svc.$svc")" = "$target" ] && return 0
        sleep 0.1
        elapsed=$((elapsed + 1))
    done
    return 1
}

# Clean stop->start cycle prevents init's `critical` 4-crash-in-4-min escalation
cycle_init_service() {
    svc="$1"
    if ! svc_is_running "$svc"; then
        return 0
    fi
    setprop ctl.stop "$svc"
    if wait_svc_state "$svc" "stopped" 30; then
        setprop ctl.start "$svc"
        wait_svc_state "$svc" "running" 50
    else
        setprop ctl.restart "$svc"
        wait_svc_state "$svc" "running" 50
    fi
}

wait_binder_service() {
    bsvc="$1"
    timeout_ds="${2:-100}"
    elapsed=0
    while [ "$elapsed" -lt "$timeout_ds" ]; do
        case "$(service check "$bsvc" 2>/dev/null)" in
            *": found"*) return 0 ;;
        esac
        sleep 0.1
        elapsed=$((elapsed + 1))
    done
    return 1
}

get_ctx() {
    p="$1"
    if [ -n "$p" ] && [ -r "/proc/$p/attr/current" ]; then
        cat "/proc/$p/attr/current" 2>/dev/null | tr -d '\0'
    else
        echo "none"
    fi
}

# Surgical App Cgroup v2/v1 Reaper: terminates orphaned app processes (UID >= 10000)
# Handles both /sys/fs/cgroup/apps/uid_*/pid_* (Android 15) and /sys/fs/cgroup/uid_* / /acct/uid_*.
reap_app_uid_cgroups() {
    reaped_groups=0
    self_cgroups="$(cat "/proc/$$/cgroup" 2>/dev/null)"
    for base_cg in /sys/fs/cgroup/apps /sys/fs/cgroup /acct; do
        [ -d "$base_cg" ] || continue
        for uid_dir in "$base_cg"/uid_*; do
            [ -d "$uid_dir" ] || continue
            uid_num="${uid_dir##*/uid_}"
            case "$uid_num" in
                ''|*[!0-9]*) continue ;;
            esac
            # Only reap app / isolated sandbox UIDs >= 10000; never touch root (0), system (1000), shell (2000)
            [ "$uid_num" -lt 10000 ] && continue

            # Safety guard: ensure our own engine PID is not inside this UID cgroup or any nested pid_* sub-cgroup
            case "$self_cgroups" in
                *"uid_${uid_num}"*)
                    log_msg "WARN" "Skipping uid_$uid_num cgroup reap because engine PID $$ is still attached!"
                    continue
                    ;;
            esac

            if [ -w "$uid_dir/cgroup.kill" ]; then
                echo 1 > "$uid_dir/cgroup.kill" 2>/dev/null
                reaped_groups=$((reaped_groups + 1))
            else
                for p_file in "$uid_dir/cgroup.procs" "$uid_dir/tasks" "$uid_dir"/pid_*/cgroup.procs "$uid_dir"/pid_*/tasks; do
                    [ -r "$p_file" ] || continue
                    while IFS= read -r target_pid; do
                        [ -z "$target_pid" ] && continue
                        [ "$target_pid" -eq "$$" ] 2>/dev/null && continue
                        kill -9 "$target_pid" 2>/dev/null
                    done < "$p_file"
                done
                reaped_groups=$((reaped_groups + 1))
            fi
        done
    done
    log_msg "INFO" "Reaped $reaped_groups orphaned app UID cgroups (>= 10000) prior to FUSE reset"
}

# ==============================================================================
# PHASE 1: Baseline Snapshot, KSU Teardown Hooks & AOSP Property Scrub
# ==============================================================================
log_msg "INFO" "PHASE 1: Baseline Snapshot, KSU Teardown Hooks & AOSP Property Scrub"

OLD_ZYGOTE64="$(get_pids zygote64)"
OLD_ZYGOTE32="$(get_pids zygote)"
OLD_SYSSERVER="$(get_pids system_server)"
OLD_SF="$(get_pids surfaceflinger)"
OLD_INSTALLD="$(get_pids installd)"
OLD_STATSD="$(get_pids statsd)"
OLD_GPUSVC="$(get_pids gpuservice)"
OLD_AUDIO="$(get_pids audioserver)"
OLD_CAM="$(get_pids cameraserver)"
OLD_MEDIA="$(get_pids mediaserver)"
OLD_WIFICOND="$(get_pids wificond)"

log_msg "INFO" "Baseline Core  | zygote64=[${OLD_ZYGOTE64:-none}] zygote32=[${OLD_ZYGOTE32:-none}] system_server=[${OLD_SYSSERVER:-none}] surfaceflinger=[${OLD_SF:-none}]"
log_msg "INFO" "Baseline Infra | installd=[${OLD_INSTALLD:-none}] statsd=[${OLD_STATSD:-none}] gpuservice=[${OLD_GPUSVC:-none}]"
log_msg "INFO" "Baseline Media | audioserver=[${OLD_AUDIO:-none}] cameraserver=[${OLD_CAM:-none}] mediaserver=[${OLD_MEDIA:-none}] wificond=[${OLD_WIFICOND:-none}]"

run_ksu_stage "teardown"

# Replicate AOSP `on userspace-reboot-requested` property resets (keeping CE keys unlocked)
prop_set_trigger sys.boot_completed 0
prop_set_trigger dev.bootcomplete 0
prop_set_trigger service.bootanim.exit 0
prop_set_trigger sys.rescue_boot_count 0
prop_set_trigger sys.lmk.reportkills 0
prop_del sys.init.updatable_crashing
prop_del sys.init.updatable_crashing_process_name
prop_del sys.shutdown.requested
prop_del persist.sys.rescue_level
sync

# ==============================================================================
# PHASE 2: Top-Down Userspace Teardown, App Cgroup Reap & Storage/FUSE Reset
# ==============================================================================
log_msg "INFO" "PHASE 2: Top-Down Userspace Teardown & FUSE/Volume Reset"

# Wave 2A: Stop Zygotes & WebView Zygote first so system_server & app processes exit cleanly
for z_svc in webview_zygote zygote_secondary zygote; do
    if svc_exists "$z_svc"; then
        log_msg "INFO" "Stopping $z_svc via init (ctl.stop)..."
        setprop ctl.stop "$z_svc"
    fi
done

wait_svc_state "zygote" "stopped" 60
wait_svc_state "zygote_secondary" "stopped" 40

for stale in zygote64 zygote webview_zygote usap64 usap32 system_server; do
    for pid in $(get_pids "$stale"); do
        log_msg "WARN" "SIGKILL lingering $stale PID=$pid"
        kill -9 "$pid" 2>/dev/null
    done
done

# Wave 2B: Reap orphaned app processes (UID >= 10000) so no stale FDs block FUSE/SurfaceFlinger
if [ "$REAP_ORPHAN_APP_CGROUPS" -eq 1 ]; then
    reap_app_uid_cgroups
fi

# Wave 2C: Stop Framework Media, Connectivity, Graphics & Honor MagicOS Daemons
for fw_svc in \
    audioserver cameraserver media media.tuner mediacodec media.extractor media.metrics mediadrmserver \
    wificond wpa_supplicant hostapd mdnsd \
    surfaceflinger gpuservice \
    iawared uniperf perfgenius agpservice aptouch_daemon dubaid hiview oases magic_server; do
    if svc_is_running "$fw_svc"; then
        log_msg "INFO" "Stopping framework/MagicOS daemon: $fw_svc"
        setprop ctl.stop "$fw_svc"
    fi
done

wait_svc_state "surfaceflinger" "stopped" 30
wait_svc_state "audioserver" "stopped" 20

# Wave 2D: Stop Core Infrastructure Daemons
if [ "$RESTART_CORE_DAEMONS" -eq 1 ]; then
    for core_svc in installd storaged statsd incidentd credstore gatekeeperd; do
        if svc_is_running "$core_svc"; then
            log_msg "INFO" "Stopping core infra daemon: $core_svc"
            setprop ctl.stop "$core_svc"
        fi
    done
    for core_svc in installd storaged statsd; do
        svc_exists "$core_svc" && wait_svc_state "$core_svc" "stopped" 25
    done
fi

# Wave 2E: AOSP `vdc volume` Storage & FUSE Reset (preserves unlocked User 0 CE keyring)
if [ "$RUN_VDC_VOLUME_RESET" -eq 1 ] && [ -x /system/bin/vdc ]; then
    log_msg "INFO" "Aborting active FUSE sessions via vdc volume abort_fuse..."
    /system/bin/vdc volume abort_fuse >> "$LOG_FILE" 2>&1
    log_msg "INFO" "Resetting vold volume state via vdc volume reset..."
    /system/bin/vdc volume reset >> "$LOG_FILE" 2>&1
    if [ "$(getprop sys.user.0.ce_available)" != "true" ]; then
        prop_set_trigger sys.user.0.ce_available true
    fi
fi

sync
if [ "$DROP_KERNEL_CACHES" -eq 1 ] && [ -w /proc/sys/vm/drop_caches ]; then
    log_msg "INFO" "Flushing & dropping clean kernel page/dentry/inode caches..."
    echo 3 > /proc/sys/vm/drop_caches 2>/dev/null
fi

# ==============================================================================
# PHASE 3: Bottom-Up Hardware Abstraction Layer (`class hal`) Reset
# ==============================================================================
log_msg "INFO" "PHASE 3: Bottom-Up Qualcomm bengal & Honor HAL Reset"

if [ "$RESTART_STATELESS_HALS" -eq 1 ]; then
    tmp_gfx_hals="/data/local/tmp/.usr_gfx_hals.$$"
    tmp_aud_hals="/data/local/tmp/.usr_aud_hals.$$"
    tmp_vnd_hals="/data/local/tmp/.usr_vnd_hals.$$"
    : > "$tmp_gfx_hals"
    : > "$tmp_aud_hals"
    : > "$tmp_vnd_hals"

    getprop | while IFS= read -r line; do
        case "$line" in
            \[init.svc.*\]:\ \[running\])
                svc="${line#\[init.svc.}"
                svc="${svc%%\]: \[running\]}"
                [ -z "$svc" ] && continue
                role="$(classify_init_service "$svc")"
                case "$role" in
                    GRAPHICS_HAL) echo "$svc" >> "$tmp_gfx_hals" ;;
                    AUDIO_HAL)    echo "$svc" >> "$tmp_aud_hals" ;;
                    VENDOR_HAL)   echo "$svc" >> "$tmp_vnd_hals" ;;
                esac
                ;;
        esac
    done

    # Wave 3A: Display Pipeline Bottom-Up (Allocator -> Composer -> DisplayEngine)
    while IFS= read -r g_hal; do
        [ -z "$g_hal" ] && continue
        case "$g_hal" in
            *allocator*)
                log_msg "INFO" "[Wave 3A] Cycling Graphics Allocator HAL: $g_hal"
                cycle_init_service "$g_hal"
                ;;
        esac
    done < "$tmp_gfx_hals"

    while IFS= read -r g_hal; do
        [ -z "$g_hal" ] && continue
        case "$g_hal" in
            *allocator*) ;;
            *)
                log_msg "INFO" "[Wave 3A] Cycling Display/Composer HAL: $g_hal"
                cycle_init_service "$g_hal"
                ;;
        esac
    done < "$tmp_gfx_hals"

    # Wave 3B: Audio Subsystem Bottom-Up (AGMIPC / PAL / ListenSoundModel -> Audio HAL)
    while IFS= read -r a_hal; do
        [ -z "$a_hal" ] && continue
        case "$a_hal" in
            *AGMIPC*|*agm*|*pal*|*ListenSoundModel*)
                log_msg "INFO" "[Wave 3B] Cycling QCOM AudioReach/PAL HAL: $a_hal"
                cycle_init_service "$a_hal"
                ;;
        esac
    done < "$tmp_aud_hals"

    while IFS= read -r a_hal; do
        [ -z "$a_hal" ] && continue
        case "$a_hal" in
            *AGMIPC*|*agm*|*pal*|*ListenSoundModel*) ;;
            *)
                log_msg "INFO" "[Wave 3B] Cycling Audio HAL: $a_hal"
                cycle_init_service "$a_hal"
                ;;
        esac
    done < "$tmp_aud_hals"

    # Wave 3C: Remaining Stateless Vendor/Honor HALs (batch stop -> batch start avoids sequential lazy-HAL stalls)
    while IFS= read -r v_hal; do
        [ -z "$v_hal" ] && continue
        if svc_is_running "$v_hal"; then
            log_msg "INFO" "[Wave 3C] Stopping Vendor/Hardware HAL: $v_hal"
            setprop ctl.stop "$v_hal"
        fi
    done < "$tmp_vnd_hals"

    sleep 1

    while IFS= read -r v_hal; do
        [ -z "$v_hal" ] && continue
        if ! svc_is_running "$v_hal"; then
            log_msg "INFO" "[Wave 3C] Starting Vendor/Hardware HAL: $v_hal"
            setprop ctl.start "$v_hal"
        fi
    done < "$tmp_vnd_hals"

    rm -f "$tmp_gfx_hals" "$tmp_aud_hals" "$tmp_vnd_hals" 2>/dev/null
    sleep 1
fi

# ==============================================================================
# PHASE 4: `userspace-reboot-resume` — APEX, Core Infra, Graphics & KSU `post-fs-data`
# ==============================================================================
log_msg "INFO" "PHASE 4: userspace-reboot-resume (APEX, Core Infra, Graphics & KSU post-fs-data)"

apex_state="$(getprop apexd.status)"
log_msg "INFO" "Verifying APEX subsystem status: apexd.status=${apex_state:-unset}"
if [ -n "$apex_state" ] && [ "$apex_state" != "ready" ] && [ "$apex_state" != "activated" ]; then
    elapsed=0
    while [ "$elapsed" -lt 60 ]; do
        st="$(getprop apexd.status)"
        [ "$st" = "ready" ] || [ "$st" = "activated" ] && break
        sleep 0.1
        elapsed=$((elapsed + 1))
    done
fi

for core_svc in gatekeeperd credstore installd storaged statsd; do
    if svc_exists "$core_svc" && ! svc_is_running "$core_svc"; then
        log_msg "INFO" "Starting core infra daemon: $core_svc"
        setprop ctl.start "$core_svc"
    fi
done

for core_svc in gatekeeperd credstore installd storaged statsd; do
    if svc_exists "$core_svc"; then
        wait_svc_state "$core_svc" "running" 50 || log_msg "WARN" "Core daemon slow to start: $core_svc"
    fi
done

for gfx_core in gpuservice surfaceflinger; do
    if svc_exists "$gfx_core" && ! svc_is_running "$gfx_core"; then
        log_msg "INFO" "Starting graphics core daemon: $gfx_core"
        setprop ctl.start "$gfx_core"
    fi
done

if ! wait_binder_service "SurfaceFlinger" 100; then
    log_msg "ERROR" "SurfaceFlinger failed to bind after HAL reset!"
fi

if svc_exists bootanim; then
    setprop service.bootanim.exit 0
    setprop ctl.start bootanim
fi

run_ksu_stage "post-fs-data"

# ==============================================================================
# PHASE 5: `class main`/`late_start`, Zygote & KSU `service` Stage
# ==============================================================================
log_msg "INFO" "PHASE 5: Starting Framework Media, MagicOS, Zygote & KSU service.sh"

for fw_start in \
    audioserver cameraserver media media.tuner mediacodec media.extractor media.metrics mediadrmserver \
    wificond mdnsd \
    iawared uniperf perfgenius agpservice aptouch_daemon dubaid hiview oases magic_server; do
    if svc_exists "$fw_start" && ! svc_is_running "$fw_start"; then
        log_msg "INFO" "Starting framework/MagicOS daemon: $fw_start"
        setprop ctl.start "$fw_start"
    fi
done

for z_svc in zygote zygote_secondary; do
    if svc_exists "$z_svc"; then
        log_msg "INFO" "Starting $z_svc via init (ctl.start)..."
        setprop ctl.start "$z_svc"
    fi
done

start 2>/dev/null

run_ksu_stage "service"

# ==============================================================================
# PHASE 6: Full System & 11-Service Binder Bring-Up Watchdog
# ==============================================================================
log_msg "INFO" "PHASE 6: Verifying Zygote, system_server & 11 Core Binder Services"

bringup_ok=1

if ! wait_svc_state "zygote" "running" 100; then
    log_msg "ERROR" "zygote failed to enter 'running' state within 10s!"
    bringup_ok=0
fi

elapsed=0
while [ "$elapsed" -lt 120 ]; do
    [ -n "$(get_pids system_server)" ] && break
    sleep 0.1
    elapsed=$((elapsed + 1))
done

if [ -z "$(get_pids system_server)" ]; then
    log_msg "ERROR" "system_server failed to spawn within 12s!"
    bringup_ok=0
fi

if [ "$bringup_ok" -eq 1 ]; then
    log_msg "INFO" "Awaiting Binder registration across Graphics, Framework, Storage & Media..."
    for b_svc in SurfaceFlinger gpu display power package activity window input mount audio.flinger media.camera; do
        if ! wait_binder_service "$b_svc" 200; then
            log_msg "WARN" "Binder service slow or absent: $b_svc"
            case "$b_svc" in
                SurfaceFlinger|display|power|package|activity|window)
                    bringup_ok=0
                    ;;
            esac
        fi
    done
fi

# ==============================================================================
# PHASE 7: Deep Recovery Escalation (Only if Phase 6 Core Bring-Up Failed)
# ==============================================================================
if [ "$bringup_ok" -ne 1 ]; then
    log_msg "WARN" "PHASE 7: Primary Bring-Up Incomplete -> Full Android Class Reset Escalation"
    prop_set_trigger sys.boot_completed 0
    prop_set_trigger dev.bootcomplete 0
    stop
    sleep 3
    start
    run_ksu_stage "service"

    if wait_binder_service "activity" 300 && wait_binder_service "SurfaceFlinger" 100 && wait_binder_service "window" 150; then
        log_msg "INFO" "[Escalation] Full Android class reset succeeded!"
        bringup_ok=1
    else
        log_msg "ERROR" "[Escalation] Userspace failed to recover."
        if [ "$HARD_REBOOT_ON_FAILURE" -eq 1 ]; then
            log_msg "WARN" "HARD_REBOOT_ON_FAILURE=1 -> Performing controlled kernel reboot..."
            sync
            svc power reboot userspace_reboot_fallback 2>/dev/null || setprop sys.powerctl reboot,userspace_reboot_fallback
        fi
        exit 1
    fi
fi

# ==============================================================================
# PHASE 8: Finalize Boot Flags, KSU `boot-completed`, Wake Display & Full Audit
# ==============================================================================
log_msg "INFO" "PHASE 8: Finalizing Boot Completion, KSU boot-completed & Full Audit"

elapsed=0
while [ "$elapsed" -lt 300 ]; do
    [ "$(getprop sys.boot_completed)" = "1" ] && break
    sleep 0.1
    elapsed=$((elapsed + 1))
done

prop_set_trigger sys.boot_completed 1
prop_set_trigger dev.bootcomplete 1
prop_set_trigger service.bootanim.exit 1
svc_exists bootanim && setprop ctl.stop bootanim

run_ksu_stage "boot-completed"

if [ "$WAKE_SCREEN_ON_COMPLETE" -eq 1 ]; then
    input keyevent KEYCODE_WAKEUP 2>/dev/null
fi

NEW_ZYGOTE64="$(get_pids zygote64)"
NEW_SYSSERVER="$(get_pids system_server)"
NEW_SF="$(get_pids surfaceflinger)"
NEW_INSTALLD="$(get_pids installd)"
NEW_STATSD="$(get_pids statsd)"
NEW_GPUSVC="$(get_pids gpuservice)"
NEW_AUDIO="$(get_pids audioserver)"
NEW_CAM="$(get_pids cameraserver)"
NEW_MEDIA="$(get_pids mediaserver)"
NEW_WIFICOND="$(get_pids wificond)"

Z_FIRST_PID="$(echo "$NEW_ZYGOTE64" | awk '{print $1}')"
S_FIRST_PID="$(echo "$NEW_SYSSERVER" | awk '{print $1}')"
SF_FIRST_PID="$(echo "$NEW_SF" | awk '{print $1}')"
I_FIRST_PID="$(echo "$NEW_INSTALLD" | awk '{print $1}')"

log_msg "INFO" "Post Core  | zygote64=[${NEW_ZYGOTE64:-none}] (was ${OLD_ZYGOTE64:-none}) | CTX=$(get_ctx "$Z_FIRST_PID")"
log_msg "INFO" "Post Core  | system_server=[${NEW_SYSSERVER:-none}] (was ${OLD_SYSSERVER:-none}) | CTX=$(get_ctx "$S_FIRST_PID")"
log_msg "INFO" "Post Core  | surfaceflinger=[${NEW_SF:-none}] (was ${OLD_SF:-none}) | CTX=$(get_ctx "$SF_FIRST_PID")"
log_msg "INFO" "Post Infra | installd=[${NEW_INSTALLD:-none}] (was ${OLD_INSTALLD:-none}) | CTX=$(get_ctx "$I_FIRST_PID") | statsd=[${NEW_STATSD:-none}] (was ${OLD_STATSD:-none}) | gpuservice=[${NEW_GPUSVC:-none}] (was ${OLD_GPUSVC:-none})"
log_msg "INFO" "Post Media | audioserver=[${NEW_AUDIO:-none}] (was ${OLD_AUDIO:-none}) | cameraserver=[${NEW_CAM:-none}] (was ${OLD_CAM:-none}) | mediaserver=[${NEW_MEDIA:-none}] (was ${OLD_MEDIA:-none}) | wificond=[${NEW_WIFICOND:-none}] (was ${OLD_WIFICOND:-none})"
log_msg "INFO" "Storage & Kernel | CE_Unlocked=$(getprop sys.user.0.ce_available) | $(uptime)"
log_msg "INFO" "=== TRUE USERSPACE REBOOT COMPLETE ==="
exit 0
