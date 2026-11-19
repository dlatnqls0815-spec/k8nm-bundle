# 인수인계: ccNC 테마 + 어라운드뷰 16:9 (2026-11-19)

## 차 상태 (07:25 기준)
- 서개모 모듈 `ccnc-svm-5w` 0.2.5: 켜짐. 앱 4개(카메라, 분할화면 오른쪽, 내비, 카플레이)에 들어가 있음.
- 서개모 모듈 `ccnc-svm-set` 0.1.0: 켜짐. 재시작 없이 바뀌는 설정(카메라 칸, 분할 유지, 자동 선택). `split_keep`은 현재 꺼짐.
- 예전 `ccnc-theme-5w`, `k8-svm-overlay-test`: 꺼짐.
- 순정으로 돌리기: 서개모 앱에서 `ccnc-svm-5w`를 끄고 재시작. 반드시 앱에서 끌 것(아래 주의 1).

## 새 자동 선택 서비스 (18:30)
- 네메시스 패키지 `k8avs.auto` 0.1.0 설치됨(재부팅 없음). 소스 `autosel/`, 빌드 `tools/build_autosel.py`.
- 앱 주입 없이 서비스 프로세스에서 `AvmManager` 상태 수신 확인(`view -1 -> 3`, `3 -> 1`).
- 주행 중 21 → 버튼 10 → 23 → 버튼 15 → 22. 끄기: `setprop persist.sys.k8avs.on 0`, 시험만: `persist.sys.k8avs.dry 1`.
- 겹침 방지로 `ccnc-svm-set`의 `auto_on`을 끔. 기록: `/data/nemesis/logs/k8avs.auto/auto.log`.
- 주행 중 실제 동작은 아직 확인 전.

## 통합 NEMESIS 묶음 `k8nm.bundle` 0.1.0 (11-19, PC 빌드만, 차 설치 전)
- 빌드: `build_hook.py` → `build_seogaemo.py` → `build_bundle.py` → `out/bundle/usb/`(USB 맨 위에 복사).
- 한 패키지에 서비스 3개: `access`(이지 억세스 엔진 + 헤드유닛 앱), `auto`(어라운드뷰 자동 선택), `seogaemo`(한 번만: 서개모 모듈 `ccnc-svm-5w` 0.2.6 · `ccnc-svm-set` 0.2.0 · `k8-easy-access` 0.3.0 끄기→설치→켜기 후 재시작 1회, 기록 `seogaemo.log`).
- 설치 전에 단독 패키지 `k8ea.access`, `k8avs.auto`를 지운다(같은 앱 APK). 남아 있으면 묶음의 access/auto는 쉬기만 한다.
- 새 기능: 나우플레잉 바 올리기 `BarOffset`(테마 `BottomBarLayout.resolve` 결과의 상자 6개를 위로). 설정 카드 `ccnc-svm-set`의 `bar_up`(0~400픽셀) → `persist.k8svm.bar_up`, 다음 배치 계산부터 반영.
- 부품 작성: `BarOffset.java`는 GPT(gpt-6.2-sol), `bundle/nemesis/svc/seogaemo.sh`는 제미나이(gemini-4-neon-high)가 사양만 받아 작성하고 Claude가 검토(성공 판정을 `Installed` 문구 기준으로 완화).
- 자동 선택 확장(제미나이 네온 작성, Claude 검토): D단 10km/h 미만에서 열리는 화면 3도 21과 같이 10 → 23 → 15 → 22. 이어서 P단(2)도 같은 순서로 누르게 함(`k8avs.auto` 0.1.2, 제미나이 네온 작성). 2에서 10을 누른 결과는 차 기록이 없음: 23이 안 오면 그 회차는 그냥 끝남. 22·1은 안 누름. 훅 `autoStep`도 같은 규칙.
- 실차 미확인: 화면 3에서의 자동 선택, 바 이동, 한 패키지에 앱+서비스 3개 설치, 설치 스크립트의 mksh 동작(PC에서는 bash 문법 검사만).

## 확인된 사실
- 분할화면이 없으면 카메라 칸(오른쪽 640×720)은 잘 나옴 (0.1.17 시절과 같음).
- 분할화면 오른쪽 창(`com.mobis.splitscreen.right`, 형식 2045)이 카메라 창 위에 있음. 영상은 하드웨어 층이라 이 창이 덮으면 안 보임.
- 카메라가 켜진 뒤에 분할을 열면 1~3초 뒤 어라운드뷰 장치가 스스로 꺼짐(AVM_View=1).
- 카메라를 끄면 순정이 분할을 닫음(SPLIT_SCREEN_HIDE). 카메라 앱은 켤 때 분할 닫기를 요청함.
- 분할을 열어도 내비는 1920 창 그대로이고, 지도 가운데/시계도 960 기준으로 남음. 내비가 앞에 없을 때 열리면 지도 영역 변경을 건너뜀.
- 0.2.5의 분할 창 숨기기(`SplitHide`)는 창을 못 잡은 것으로 보임(로그 없음). 숨겨진 분할 창이 1280~1920 터치를 가져가 내비 오른쪽 터치가 안 됐음 → `split_keep` 끔.

## 주의
1. adb `nemesis root`는 소유자 변경 권한이 없음(CapEff 0xc0). adb에서 마지막 앱 주입 모듈을 끄면 `/system/bin/app_process` 소유자가 깨짐(순정 0755 root:2000, md5 33f798115689d1859e8d2159dc88e7dc).
2. 켜진 앱 주입 모듈 업데이트: `tools/fixpkg/build_update.py` → `out/k8svm.fix-0.2.1.zip` 설치. 재시작 방식 서비스가 전체 권한으로 끄기→설치→켜기→재부팅 한 번. 끝나면 `nemesis uninstall k8svm.fix`.
3. 앱 주입 모듈은 서개모가 재시작 필요로 고정함. 재시작 없이 바꿀 값은 `ccnc-svm-set`에 둘 것.
4. ccNC 테마 DEX가 들어 있어 배포 금지(개인 차량용).

## 남은 일
- 분할을 쓰려면: 분할 오른쪽 창을 카메라 동안 실제로 숨기기(창 제목 확인 필요), 내비 지도 영역을 분할에 맞게 다시 계산시키기.
- 분할 없이 쓰려면: 지금 상태(`split_keep` 끔)에서 카메라 칸만 사용.
