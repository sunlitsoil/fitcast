#!/bin/zsh
# 시연 영상용: Pleos Connect Emulator의 Virtual Console(HTTP :8088, adb forward)로 실제 차량 신호를 바꾸면서
# 앱의 「시연 주행」(5분 합성 경로)을 돌리고 화면을 녹화한다. 사용: tools/demo-drive.sh 출력.mp4
set -u
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
V=http://127.0.0.1:8088
OUT=${1:-demo.mp4}
put(){ curl -s -o /dev/null -X PUT "$V/vehicle/$1"; }
$ADB forward tcp:8088 tcp:8088 >/dev/null
curl -s -o /dev/null -X POST "$V/browser/session?client=fitcast-demo"   # 첫 heartbeat 뒤에야 입력이 먹는다
(while true; do curl -s -o /dev/null -X POST "$V/browser/session?client=fitcast-demo"; sleep 1; done) & HB=$!
put "controls?speed=0&accelerator=0&brake=40&steering=0"; put "gear?gear=Drive"
curl -s "$V/vehicle/gear" | grep -q '"value":"Drive"' || { echo "기어 D 전환 실패"; kill $HB; exit 1; }
 put "turn-signal?mode=Off"; put "body?front_wiper=Off"; put "controls?speed=0&accelerator=0&brake=30&steering=0"
$ADB shell am force-stop ai.fitcast.app; $ADB logcat -c
$ADB shell am start -n ai.fitcast.app/.MainActivity >/dev/null; sleep 5
$ADB shell screenrecord --time-limit 0 --bit-rate 8000000 /sdcard/fitcast-demo.mp4 & REC=$!
sleep 1
$ADB shell input tap 2360 1244   # 시연 주행(화면 오른쪽 아래)
T0=$(date +%s)
at(){ while (( $(date +%s) - T0 < $1 )); do sleep 0.2; done; }
# 낭독 중일 때 실제 조향을 넣어 멈춤→같은 문장 재시작을 화면에 남긴다(최대 $2초 대기)
steer_while_speaking(){ local end=$(( $(date +%s) + $1 ))
  while (( $(date +%s) < end )); do
    [[ "$($ADB logcat -d -s FitCast:I | grep -E 'tts onStart|tts onDone|stop ' | tail -1)" == *onStart* ]] && break; sleep 0.3; done
  sleep 1; put "controls?speed=30&accelerator=0&brake=0&steering=60"; sleep 4; put "controls?speed=40&accelerator=20&brake=0&steering=0"; }
at 1;   put "controls?speed=0&accelerator=0&brake=40&steering=0"
at 32;  put "controls?speed=40&accelerator=20&brake=0&steering=0"
at 33;  steer_while_speaking 6
at 39;  : # 방향지시등은 측후방 카메라 영상이 떠서 에뮬레이터가 멈춘다(2026-11-08 두 번 재현). 합성 경로의 방향지시등을 쓴다
at 42;  put "controls?speed=20&accelerator=0&brake=0&steering=90"
at 47;  put "controls?speed=40&accelerator=20&brake=0&steering=0"
at 76;  steer_while_speaking 25   # 실제 조향각 60° → 멈춤
at 90;  put "controls?speed=55&accelerator=30&brake=0&steering=0"
at 105; put "controls?speed=78&accelerator=30&brake=0&steering=0"
at 140; :
at 146; :
at 175; put "controls?speed=40&accelerator=20&brake=0&steering=0"
at 182; steer_while_speaking 15
at 200; $ADB shell input tap 2232 1154   # 나중에 보기(말로 「저장」과 같은 동작)
at 240; put "body?front_wiper=Low"
at 244; :
at 247; put "controls?speed=20&accelerator=0&brake=0&steering=90"
at 252; put "controls?speed=35&accelerator=20&brake=0&steering=0"
at 262; put "controls?speed=0&accelerator=0&brake=40&steering=0"
at 281; put "body?front_wiper=Off"; put "controls?speed=30&accelerator=20&brake=0&steering=0"
at 300; put "controls?speed=0&accelerator=0&brake=50&steering=0"
at 302; put "gear?gear=Park"
at 312
$ADB shell pkill -INT screenrecord; sleep 3; kill $REC 2>/dev/null
$ADB pull /sdcard/fitcast-demo.mp4 "$OUT" >/dev/null
$ADB logcat -d > "${OUT%.mp4}-logcat.txt"
kill $HB
echo "saved $OUT"
