# 요청: 주행 중 카메라 버튼 → 어라운드뷰 자동 선택 (화면 자르기 없음)

## 원하는 동작
- 주행 중(D, 10km/h 이상 포함) 카메라 버튼을 누르면, 사람이 화면에서 "후방카메라" → "어라운드뷰"를 누르는 것과 똑같이 자동으로 눌러 준다.
- 화면 자르기, 오른쪽 칸, 분할화면, ccNC 테마 연동은 전부 뺀다. 순정 전체 화면 그대로.
- 별도 패키지로 만든다: 네메시스 패키지(서비스) + 켜고 끄는 서개모 설정 카드.

## 차에서 확인된 사실 (2026-11-19, K8 GL3, 서개모 0.1.54)
- 카메라 상태는 `com.hkmc.pgs.AvmManager`의 상태 수신기(`com.hkmc.pgs.AvmStatusListener`)로 받는다. 보기 번호(view_mode):
  - 1 = 꺼짐, 2 = P단 후방+탑뷰, 3 = D단 정차 전방+탑뷰, 21 = 카메라 버튼 직후(주행), 23 = 주행 후방 와이드, 22 = 주행 "후방 + 어라운드뷰"(원하는 화면)
- 순정 화면 버튼과 같은 요청: `AvmManager.requestSelectMenu(int)`
  - 10 = "후방카메라", 15 = "어라운드뷰"
  - 실제 기록: 21 → requestSelectMenu(10) → 23 → requestSelectMenu(15) → 22. 23에서 바로 시작하면 15만 보내면 됨.
- 규칙: 꺼짐(1)에서 켜진 직후 카메라 세션당 한 번만. 6초 안에 끝나지 않거나, 후진·화면 꺼짐·예상과 다른 보기면 중단하고 재시도하지 않는다. 2, 3, 22에서 시작하면 아무것도 안 보낸다.
- 위 동작은 이미 우리 모듈 안(카메라 앱에 주입)에서 동작 확인됨: "auto start from view 23 → requestSelectMenu(15) → view 22 → auto done".

## 만드는 방법 제안
- 1안 (권장, 재부팅 없음): 네메시스 서비스(uid=system 또는 root)에서 `app_process`로 작은 DEX를 실행하고, `/system/framework/automotive.jar`(AvmManager 포함)를 클래스패스에 넣어 AvmManager를 직접 얻어 상태 수신기 등록 + requestSelectMenu 호출. 앱 주입이 아니라 서개모 재시작 문제 없음. 먼저 서비스 프로세스에서 AvmManager 생성/등록이 되는지 확인할 것.
- 2안: 1안이 안 되면 서개모 앱 주입(hook_apps)으로 카메라 앱(`com.hkmc.system.app.rearcamera`)에만 넣는다. 이 경우 업데이트·켜고 끄기에 재부팅이 필요하다.
- 설정(켜기/끄기)은 앱 주입이 없는 서개모 카드에 두고 `persist.*` 값으로 넘긴다(재시작 없음).

## 꼭 지킬 것
- 사용자 승인 전에는 차에 설치·전송하지 않는다.
- adb `nemesis root`에는 파일 소유자 변경 권한이 없다. adb에서 마지막 앱 주입 모듈을 끄면 `/system/bin/app_process` 소유자가 깨진다(순정 0755 root:2000). 지금 켜진 `ccnc-svm-5w`를 끌 때는 서개모 앱에서 끈다.
- `persist.service.adb.tcp.enable=1` 다시 켜지 않는다. HMG-API와 서개모를 섞지 않는다.
- 차량 쪽 요청은 requestSelectMenu(10/15)만. 다른 CAN/명령은 보내지 않는다.

## 참고 코드
- `research/k8-ccnc-svm/hook/src/io/github/k8svm/hook/SvmOverlayEntry.java`의 `autoStep`, `select`, `listenAvm`
- `research/k8-ccnc-svm/docs/HANDOFF.md`
