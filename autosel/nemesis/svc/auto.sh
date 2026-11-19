#!/system/bin/sh
# K8 around view auto select (NEMESIS service, uid=system, mode=restart). Presses only the stock camera menu
# buttons 10 ("rear camera") and 15 ("around view") after the camera key while driving. No app injection.

case "$0" in
    /*) pkg_dir=${0%/svc/*} ;;
    *) pkg_dir=/data/nemesis/apps/k8avs.auto ;;
esac
dex="$pkg_dir/bin/k8avs.dex"
data=/data/nemesis/logs/k8avs.auto
[ -f "$dex" ] || { echo "dex missing"; exit 1; }

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 5
done
sleep 10

priv="$data/private"
mkdir -p "$priv/dalvik-cache" 2>/dev/null
chmod 700 "$priv" 2>/dev/null
[ -w "$priv" ] || { echo "private dir not writable: $priv"; sleep 60; exit 1; }

ANDROID_DATA="$priv" CLASSPATH="$dex:/system/framework/automotive.jar" exec app_process /system/bin io.github.k8svm.auto.AutoMain
