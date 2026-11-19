#!/system/bin/sh
# NEMESIS root has no CAP_CHOWN, so give the system-uid service an enterable, writable data directory.
data=/data/nemesis/logs/k8avs.auto
mkdir -p "$data"
chmod 777 "$data"
exit 0
