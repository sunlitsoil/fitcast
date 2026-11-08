# 핏캐스트 (FitCast)

도착 시각에 맞춰 끝나고, 운전이 바빠지는 순간에는 스스로 멈추는 차량용 AI 음성 브리핑.

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작의 시제품이다. Pleos Connect Emulator 2.1.0에서 실행해 확인했고, 실차에서는 아직 돌려 보지 않았다.

- release APK: [`apk/FitCast_핏캐스트_release.apk`](apk/FitCast_핏캐스트_release.apk)
- 시연 영상: [Releases v0.1.0](https://github.com/sunlitsoil/fitcast/releases/tag/v0.1.0)의 `fitcast-demo.mp4`(에뮬레이터 화면과 TTS 소리 녹화)

## 무엇을 하나

- **운전 부담 판단**: 방향지시등·브레이크·기어·조향각·와이퍼(Vehicle SDK), 다음 안내 지점·주의 구간·단속 카메라·제한속도(NaviHelper SDK), 연속 좌표로 계산한 속도(FusedLocation SDK)를 묶어 0.5초마다 `PARKED / LOW / MEDIUM / HIGH`를 정하고 이유를 남긴다.
- **멈춤과 재개**: HIGH면 읽던 문장을 즉시 멈추고, 다시 LOW가 2.5초 이어지면 그 문장을 처음부터 다시 읽는다. MEDIUM이면 읽던 문장만 마저 읽는다.
- **끝까지 못 읽을 문장은 시작하지 않기**: 지금 속도로 다음 HIGH 구간까지 남은 시간을 계산해, 그 안에 끝나지 않는 문장은 미룬다.
- **도착 맞춤 분량**: 남은 시간 × 실제로 들을 수 있었던 비율로 예산을 잡고, 항목마다 헤드라인·요약·전체 중 얼마나 읽을지 우선순위 기준으로 다시 배정한다. 재탐색·정체로 남은 시간이 바뀌면 다시 계산한다.
- **음성 조작**: "다음", "자세히", "저장"(SpeechToText SDK, 온디바이스). 못 들은 항목과 저장한 항목은 주차(P단) 후 화면 목록으로 넘긴다.
- **음성 출력**: TextToSpeech SDK(온디바이스 모드).

## 구조

```
core/  순수 Kotlin(JVM). 부담 추정·분량 계획·재생 엔진·주행 시뮬레이터·단위 테스트
app/   Android(AAOS) 앱. Pleos Connect SDK 콜백 → core 엔진 연결, 상태 화면
docs/  시뮬레이션 결과
```

## 실행 방법

필요: JDK 17, Android SDK(platform 34, build-tools 34.0.0). Pleos SDK는 공개 Maven 저장소(`https://nexus-playground.pleos.ai/repository/maven-releases/`)에서 받는다.

```bash
# 엔진 단위 테스트 + 주행 시뮬레이션(안드로이드 SDK 없이)
./gradlew -PcoreOnly :core:test :core:run --args="$PWD/docs/simulation-result.md"

# 앱 빌드(local.properties에 sdk.dir 필요)
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
# release 빌드: 서명 키는 저장소 밖에 두고 환경 변수로 넘긴다(별칭 fitcast)
FITCAST_KEYSTORE=키저장소.jks FITCAST_KEYSTORE_PASS_FILE=비밀번호파일 ./gradlew :app:assembleRelease
# 설치: adb install apk/FitCast_핏캐스트_release.apk
```

## 테스트 환경 및 필요한 설정

- 빌드·테스트: macOS(Apple Silicon), JDK 17, Gradle 8.9, AGP 8.5.2, Kotlin 1.9.25. `:core:test` 18개 통과, `:app:assembleDebug`·`:app:assembleRelease` 빌드 성공.
- 에뮬레이터: Pleos Connect Emulator 2.1.0(arm64, API 34)에서 실행해 확인했다(2026-11-08).
  - SDK 5종 서비스 연결
  - 온디바이스 TTS 문장 단위 낭독(22문장 실측 공백 제외 초당 5.5자)
  - Virtual Console로 기어·방향지시등·조향각·와이퍼를 바꿀 때의 단계 변화와 같은 문장 재시작
- 아직 확인하지 않은 것: NaviHelper 실경로 연동(시연은 5분 합성 경로 위에 실제 차량 신호를 덮어 씀), 실제 내비 안내·Gleo와 겹칠 때의 오디오 포커스 동작, STT 음성 명령 인식.
- 시연: 앱의 시연 주행 버튼을 누르면 5분 합성 경로를 실제 시간으로 흘린다. `tools/demo-drive.sh 출력.webm`은 Virtual Console(HTTP 8088, `adb forward`)로 차량 신호를 바꾸며 에뮬레이터 화면과 소리를 녹화한다(`.mp4`로 주면 기기 화면만 녹화).
- 에뮬레이터 설치: Android Studio SDK Manager에 Pleos Connect System Image 사이트를 추가하고 Automotive AVD를 만든다(공식 문서의 Pleos Connect Emulator 설치 및 실행). 에뮬레이터가 GPU 오류로 멈추면 `-no-window`로 띄우면 안정적이었다.
- 퍼미션: 조회용 `CAR_LIGHTS`·`CAR_DRIVE`·`CAR_WINDOWS`, `NAVI_ROUTE`, `FUSED_LOCATION`, `TTS_SERVICE`·`STT_SERVICE`. 차량 제어 퍼미션은 쓰지 않는다. Pleos App Market 승인 범위는 별도 확인이 필요하다.
- 서버 기반 TTS·STT(HYBRID·SERVER 모드)는 Pleos Playground 앱 등록이 필요해 시제품은 ON_DEVICE 모드만 쓴다.
- 글꼴: Pretendard(SIL Open Font License 1.1, `licenses/Pretendard-OFL.txt`).

## APK

- 위치: `apk/FitCast_핏캐스트_release.apk`(서명한 release 빌드, versionName 0.1.0, minSdk 28·targetSdk 34).
- 공모전 안내의 파일명은 `팀명_앱이름_release.apk`이다. 개인 참가는 팀명 자리가 성명이라, 공개 저장소에 실명이 남지 않도록 앱 영문명을 썼다.
- 처음 실행하면 위치·마이크 권한을 묻는다.

## 시뮬레이션 요약

합성 12분 출근 경로(재탐색·우천·IC 3곳·신호 대기 4회), 예시 브리핑 18개 항목. 상세는 `docs/simulation-result.md`.

| 지표 | 일반 재생 | 위험 순간만 멈춤 | 핏캐스트 |
|---|---|---|---|
| 운전 부담 큰 순간에 말한 시간 | 223초 | 0초 | 0초 |
| 말하던 문장을 중간에 끊은 횟수 | 0회 | 14회 | 6회 |
| 한 문장이라도 들은 항목 | 18/18 | 12/18 | 15/18 |
| 우선순위 가중 청취 가치(최대 48) | 48.0 | 39.0 | 38.4 |

실제 주행 기록과 실제 TTS가 아닌 시간 계산 모의 실행이다. 기준값(거리·각도·말하기 속도)은 시제품 초기값이며 에뮬레이터·실차 기록으로 조정해야 한다.

## 예시 데이터

`SampleBriefing`의 일정·메일·기사는 모두 지어낸 예시다. 실제 사건·인물·기사 원문이 아니다.
