#!/system/bin/sh
# K8 around view auto select inside the bundle k8nm.bundle (uid=system, mode=restart).
# Same engine as the standalone package k8avs.auto; stays idle while that package is still installed.
# persist.sys.k8avs.on=0 turns it off.

case "$0" in
    /*) pkg_dir=${0%/svc/*} ;;
    *) pkg_dir=/data/nemesis/apps/k8nm.bundle ;;
esac
if [ -d /data/nemesis/apps/k8avs.auto ]; then
    echo "standalone k8avs.auto is installed; bundle auto select stays off"
    sleep 86400
    exit 0
fi
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
