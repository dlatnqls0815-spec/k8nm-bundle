#!/system/bin/sh
# K8 driver easy access inside the bundle k8nm.bundle (uid=system, mode=restart).
# Same engine as the standalone package k8ea.access; stays idle while that package is still installed.

case "$0" in
    /*) pkg_dir=${0%/svc/*} ;;
    *) pkg_dir=/data/nemesis/apps/k8nm.bundle ;;
esac
if [ -d /data/nemesis/apps/k8ea.access ]; then
    echo "standalone k8ea.access is installed; bundle easy access stays off"
    sleep 86400
    exit 0
fi
dex="$pkg_dir/bin/k8-ea-car.dex"
data=/data/nemesis/logs/k8ea.access
[ -f "$dex" ] || { echo "dex missing"; exit 1; }

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 5
done
sleep 10

priv="$data/private"
mkdir -p "$priv/dalvik-cache" 2>/dev/null
chmod 700 "$priv" 2>/dev/null
[ -w "$priv" ] || { echo "private dir not writable: $priv"; sleep 60; exit 1; }

K8EA_PKG="$pkg_dir" ANDROID_DATA="$priv" CLASSPATH="$dex" exec app_process /system/bin io.github.k8ea.car.CarMain
