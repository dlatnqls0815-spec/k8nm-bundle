#!/system/bin/sh
# One-shot at boot (daemon-started services get full capabilities; adb-started ones do not):
# restore stock owner of app_process, then let HMG-Seogaemo switch
# k8-svm-overlay-test -> ccnc-svm-5w.
H=${0%/*}/..
M=$H/fix2.done
L=$H/fix.log
[ -f "$M" ] && exit 0
case "$(grep CapEff /proc/self/status)" in *3fffffffff*) ;; *) echo "$(date) limited caps; waiting for boot start" >> "$L"; exit 0;; esac
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 5; done
sleep 40
SG=/data/nemesis/apps/hmg-seogaemo/bin/hmg-seogaemo-root
{
  echo "== start $(date)"; grep CapEff /proc/self/status
  ls -ln /system/bin/app_process
  set -- $(md5 /system/bin/app_process)
  if [ "$1" = 33f798115689d1859e8d2159dc88e7dc ]; then
    nemesis remount rw; chown 0:2000 /system/bin/app_process; chmod 0755 /system/bin/app_process; nemesis remount ro
  else echo "app_process is not stock bytes ($1); owner left alone"; fi
  ls -ln /system/bin/app_process
  echo "== revert k8-svm-overlay-test"; /system/bin/sh $SG k8-svm-overlay-test revert
  echo "== apply ccnc-svm-5w"; /system/bin/sh $SG ccnc-svm-5w apply
  echo "== end $(date)"
} >> "$L" 2>&1
touch "$M"
