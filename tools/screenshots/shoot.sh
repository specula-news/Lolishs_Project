#!/usr/bin/env bash
# Takes screenshots of the app on the Android TV emulator (run by .github/workflows/screenshots.yml).
# Needs: a booted emulator, tools/screenshots/mock_server.py running on the host, and the debug APK.
set -u
PKG=app.lumen.player
OUT=${OUT:-shots}
mkdir -p "$OUT"

APK=$(ls app/build/outputs/apk/standalone/debug/*.apk | head -1)
echo "Installing $APK"
adb install -r -g "$APK" || exit 1
adb shell settings put system screen_off_timeout 1800000 || true
adb shell svc power stayon true || true

write_prefs() { # theme nav
	cat > /tmp/lumen.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="server_url">http://10.0.2.2:8096</string>
    <string name="server_name">Lolish</string>
    <string name="access_token">demo-token</string>
    <string name="user_id">u-lolish</string>
    <string name="user_name">Lolish</string>
    <string name="active_device_id">demo-device</string>
    <string name="accounts">[{&quot;server&quot;:&quot;http://10.0.2.2:8096&quot;,&quot;serverName&quot;:&quot;Lolish&quot;,&quot;userId&quot;:&quot;u-lolish&quot;,&quot;userName&quot;:&quot;Lolish&quot;,&quot;token&quot;:&quot;demo-token&quot;,&quot;deviceId&quot;:&quot;demo-device&quot;,&quot;imageTag&quot;:&quot;avatar&quot;,&quot;pw&quot;:null}]</string>
    <string name="liserver_url">http://10.0.2.2:5000/</string>
    <string name="liserver_user">lolish</string>
    <string name="liserver_csrf">demo</string>
    <string name="liserver_cookies">{&quot;session&quot;:&quot;demo&quot;}</string>
    <boolean name="auto_update_check" value="false" />
    <int name="ui_theme" value="$1" />
    <int name="ui_nav_position" value="$2" />
</map>
EOF
	adb shell am force-stop $PKG
	adb shell run-as $PKG mkdir -p shared_prefs
	adb shell "run-as $PKG sh -c 'cat > shared_prefs/lumen.xml'" < /tmp/lumen.xml
	adb shell run-as $PKG rm -f files/last_crash.txt 2>/dev/null || true
}

launch() { # extra am-start arguments (already quoted for the device shell)
	adb shell am force-stop $PKG
	adb shell "am start -W -n $PKG/app.lumen.player.ui.LaunchActivity $*" > /dev/null
}

key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.8; done; }

shot() { # name [wait seconds]
	sleep "${2:-6}"
	adb exec-out screencap -p > "$OUT/$1.png"
	echo "shot $1 ($(stat -c %s "$OUT/$1.png") bytes)"
}

run_set() { # label theme nav full(1/0)
	local name=$1
	write_prefs "$2" "$3"
	launch ""
	shot "$name-01-home-menu" 12
	key KEYCODE_DPAD_RIGHT
	shot "$name-02-home" 4
	if [ "$4" = 1 ]; then
		key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
		shot "$name-03-home-rows" 4
		launch "--es shot_page lib-movies"
		shot "$name-04-movies" 9
		launch "--es shot_detail s1"
		shot "$name-05-detail" 10
		launch "--es shot_page settings --es shot_section APPEARANCE"
		shot "$name-06-settings" 8
	fi
	launch "--es shot_page liserver --es shot_li_query \"'frostline S02'\""
	shot "$name-07-liserver-search" 10
	if [ "$4" = 1 ]; then
		launch "--es shot_page liserver --es shot_li_tab downloads"
		shot "$name-08-liserver-downloads" 9
		launch "--es shot_page liserver --es shot_li_tab trending"
		shot "$name-09-liserver-trending" 10
	fi
}

run_set glass 1 0 1
run_set amber 0 0 1
run_set purple 2 0 1
run_set glass-top 1 1 0
run_set purple-top 2 1 0

# If the app crashed, keep its report next to the screenshots.
adb shell run-as $PKG cat files/last_crash.txt > "$OUT/crash.txt" 2>/dev/null || true
adb logcat -d AndroidRuntime:E "*:S" > "$OUT/crashes-logcat.txt" 2>/dev/null || true
adb logcat -d -t 600 "*:E" > "$OUT/logcat-errors.txt" 2>/dev/null || true
ls -la "$OUT"
