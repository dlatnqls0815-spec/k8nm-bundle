# ccNC 테마 + 어라운드뷰 16:9 (ccnc-svm-5w)

개인 차량 전용. ccNC 테마(blackr) 원본 DEX가 들어 있어 **배포·업로드·커밋 금지**.

## 무엇을 하나
- 서개모 모듈 하나(`ccnc-svm-5w`)에 두 기능을 합쳤다. 서개모 훅 호스트는 모듈마다 jar 하나, 진입점 하나만 등록하므로
  `k8ccnc.CombinedEntry`가 테마(`gl3ccnc.host.ThemeEntry` → `dev.ev6.nowplaying.UniversalEntry`)와 오른쪽 칸
  (`io.github.k8svm.hook.SvmOverlayEntry`)을 차례로 부른다. 한쪽이 실패해도 다른 쪽과 순정 앱은 계속 돈다.
  테마는 카메라 앱에서는 부르지 않는다(원래 대상이 아님).
- 카메라 버튼(P·D·주행) → 탑뷰 화면(2·3·22)이면 바로, 아니면 10→15 자동 선택 → 오른쪽 640×720 칸에 탑뷰.
- 칸이 뜰 때 분할화면이 꺼져 있으면 `SplitScreenManager.requestSplitScreenOn(true,false)`로 열어 내비를 16:9(1280)로 만들고,
  칸이 닫히면 모듈이 연 경우에만 다시 닫는다. 화면 표시만 바꾸고 차량 요청은 아니다.
- 내비 프로세스에서 테마의 `BottomBarHost.setSideOpen`을 칸이 떠 있는 동안 `true`로 알려, 나우플레잉 바가 1280 내비 안
  (오른쪽 끝 1280, `BottomBarLayout.resolve`의 분할 배치)으로 옮겨지게 한다.
- 영상 구멍(SurfaceView) 높이를 창 높이에 맞춰 위·아래 자르기가 둘 다 된다(0.1.17에서 확인).
- 후진(R)·화면 꺼짐이면 순정 전체 화면.

## 설정 (재시작 없이: 켜기·너비·자르기·분할·자동 / 재시작 필요: 진행 표시)
| 키 | 기본 | 뜻 |
|---|---|---|
| progress_enabled | 켬 | 테마 내비 진행 표시 |
| panel_on | 켬 | 카메라 버튼 시 16:9 + 오른쪽 어라운드뷰 |
| width | 640 | 분할 오른쪽 칸 전체 |
| cut_top / cut_bottom | 0 | 위·아래 자르기(픽셀) |
| split_keep | 켬 | 분할화면 열기·유지 |
| auto_on | 켬 | 10→15 자동 선택 |

## 빌드
```
python -I tools/build_hook.py      # 우리 소스 + lib/theme/ccnc-theme-hook.jar(테마 DEX) 병합
python -I tools/build_seogaemo.py  # out/seogaemo/ccnc-svm-5w-0.2.5.hsm
```

## 설치 순서 (사용자 승인 후)
1. 기존 `ccnc-theme-5w`, `k8-svm-overlay-test`를 끈다(같은 앱을 이중으로 훅하지 않게). 필요하면 제거.
2. `ccnc-svm-5w` 설치 → 켜기 → 헤드유닛 재시작.
3. 무선 ADB: `bash tools/install-combined.sh <차IP>`(끄기·설치·켜기까지, 묻지 않음).

## 실차 미확인
- 분할 열기 API의 두 번째 인자 뜻(애니메이션/사용자 요청 추정), 열린 뒤 내비가 실제로 1280이 되는지.
- 나우플레잉 바가 1280 안으로 옮겨지는지(테마가 `sideOpen`을 다시 계산하는 시점).
- 주행 중(10km/h 이상) 카메라 키 화면 번호(0.1.18 일반화 규칙으로 처리).

## 차에서 한 번에 볼 것 (설치 후)
1. 재시작 → 내비 화면. 테마(미디어 바·우측 손잡이)가 예전처럼 나오는지.
2. P단 카메라 버튼 → 분할이 열려 지도가 왼쪽 1280으로 줄고, 오른쪽 640에 탑뷰가 위아래 꽉 차는지. 나우플레잉 바가 지도 안쪽으로 옮겨지는지.
3. 카메라 끄기 → 분할이 원래 상태(꺼져 있었다면 꺼짐)로 돌아오는지.
4. D단 정차·주행(10km/h 이상)에서 카메라 버튼만 → 자동으로 탑뷰 칸.
5. R단 → 순정 전체 후방 화면.
