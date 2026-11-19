#!/system/bin/sh
# One-shot (mode=restart): when running with full capabilities (daemon-started), update
# ccnc-svm-5w in place: revert (releases the hook host with the right owner), install the bundled
# .hsm, apply again, then restart the head unit once so the new hook loads.
H=${0%/*}/..
M=$H/update.done
L=$H/fix.log
if [ -f "$M" ]; then sleep 86400; exit 0; fi
case "$(grep CapEff /proc/self/status)" in *3fffffffff*) ;; *) echo "$(date) limited caps; retry later" >> "$L"; sleep 15; exit 0;; esac
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 5; done
set -- $(cat /proc/uptime)
[ "${1%%.*}" -ge 60 ] || sleep 40
SG=/data/nemesis/apps/hmg-seogaemo/bin/hmg-seogaemo-root
SHA=@SHA@
{
  echo "== start $(date)"; grep CapEff /proc/self/status
  echo "== revert"; /system/bin/sh $SG ccnc-svm-5w revert
  ls -ln /system/bin/app_process
  echo "== install"; SG_HSM_INBOX=$H/hsm /system/bin/sh $SG module install $SHA > $H/inst.out 2>&1; cat $H/inst.out
  if ! grep -q "Installed ccnc-svm-5w" $H/inst.out; then
    I=/data/data/com.seogaemo.hmg/files/hsm; mkdir -p $I && cp $H/hsm/$SHA.hsm $I/$SHA.hsm && chmod 644 $I/$SHA.hsm
    echo "== install (app inbox)"; /system/bin/sh $SG module install $SHA; rm -f $I/$SHA.hsm
  fi
  echo "== apply"; /system/bin/sh $SG ccnc-svm-5w apply
  /system/bin/sh $SG module list | grep ccnc_svm
  echo "== end $(date); rebooting"
} >> "$L" 2>&1
touch "$M"
sync
/system/bin/sh $SG device reboot >> "$L" 2>&1
sleep 86400
