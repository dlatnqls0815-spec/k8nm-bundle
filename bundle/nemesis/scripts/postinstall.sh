#!/system/bin/sh
# NEMESIS root has no CAP_CHOWN, so give the system-uid services enterable, writable data directories.
for data in /data/nemesis/logs/k8ea.access /data/nemesis/logs/k8avs.auto; do
    mkdir -p "$data"
    chmod 777 "$data"
done
exit 0
