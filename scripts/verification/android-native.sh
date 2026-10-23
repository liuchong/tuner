#!/usr/bin/env bash
# 独立原生集成通道；整个脚本（含模拟器）必须由受控执行器持有。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
EVIDENCE="${TUNAR_EVIDENCE_DIR:-$ROOT/docs/verification/practice-workspace/local}"
SERIAL=emulator-5558
mkdir -p "$EVIDENCE"
"$SDK/emulator/emulator" -avd Tunar_Practice_API_36 -port 5558 -no-window -no-snapshot -no-boot-anim -memory 2048 -no-audio -gpu swiftshader_indirect > "$EVIDENCE/android-emulator.log" 2>&1 &
EMULATOR_PID=$!
cleanup() {
    "$SDK/platform-tools/adb" -s "$SERIAL" emu kill >> "$EVIDENCE/android-emulator-control.log" 2>&1 || true
    wait "$EMULATOR_PID" || true
    if [[ -n "${COLLECTOR_PID:-}" ]]; then wait "$COLLECTOR_PID" || true; fi
    if [[ -n "${INPUT_COLLECTOR_PID:-}" ]]; then wait "$INPUT_COLLECTOR_PID" || true; fi
    sleep 5
}
trap cleanup EXIT
for attempt in $(seq 1 180); do
    kill -0 "$EMULATOR_PID" 2> "$EVIDENCE/android-emulator-check.log" || { tail -20 "$EVIDENCE/android-emulator.log"; exit 1; }
    if [[ "$("$SDK/platform-tools/adb" -s "$SERIAL" shell getprop sys.boot_completed 2> "$EVIDENCE/android-emulator-check.log" | tr -d '\r')" == 1 ]]; then break; fi
    sleep 1
done
[[ "$("$SDK/platform-tools/adb" -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]
# UTP会在测试结束后卸载App；在测试存活期间收集Compose输出。
collect_image() {
    local source_name="${1:-practice-analysis}"
    local output_name="${2:-android-analysis}"
    for attempt in $(seq 1 600); do
        kill -0 "$EMULATOR_PID" 2> "$EVIDENCE/android-emulator-check.log" || return 1
        "$SDK/platform-tools/adb" -s "$SERIAL" exec-out run-as com.liuchong.tunar cat "cache/$source_name.png" > "$EVIDENCE/$output_name.pending" 2> "$EVIDENCE/android-image-collection.log" || true
        if [[ "$(od -An -tx1 -N8 "$EVIDENCE/$output_name.pending" | tr -d ' \n')" == 89504e470d0a1a0a &&
              "$(tail -c 12 "$EVIDENCE/$output_name.pending" | od -An -tx1 | tr -d ' \n')" == 0000000049454e44ae426082 ]]; then
            mv "$EVIDENCE/$output_name.pending" "$EVIDENCE/$output_name.png"
            return 0
        fi
        sleep 0.2
    done
    return 1
}
collect_image &
COLLECTOR_PID=$!
collect_image input-controls android-input &
INPUT_COLLECTOR_PID=$!
cd "$ROOT/android"
export ANDROID_SERIAL="$SERIAL"
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :app:connectedDebugAndroidTest
wait "$COLLECTOR_PID"
wait "$INPUT_COLLECTOR_PID"
# 等待单次Gradle守护完成正常退出，模拟器在EXIT trap中回收。
sleep 5
