# k8nm-bundle

K8 (Gen5W) NEMESIS bundle: now-playing bar vertical offset, easy access, around-view auto select.
Source only. The ccNC theme (blackr), APKs, DEX, .hsm modules and build libraries are NOT included and must not be redistributed.

- autosel/: around-view auto select service (camera key -> rear camera -> around view; P, D<10km/h, D>=10km/h)
- bundle/nemesis/: services of the combined package (access, auto, seogaemo)
- hook/: overlay hook sources (BarOffset moves the now-playing bar up 0..400 px)
- seogaemo/: module descriptors/scripts
- tools/: build scripts (need your own android.jar / d8 / JDK 17 in lib/)
- docs/HANDOFF.md: status. Car-side behaviour for P and D<10km/h is unverified.

Use at your own risk.
