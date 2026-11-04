#!/usr/bin/env bash
# Runs the client game tests headless when Xvfb is available, otherwise in a normal window, then copies the
# evidence (screenshots + client log) to build/evidence/client-gametest[-production]-<timestamp>/ and prints
# the path. Copying matters: other run tasks (e.g. runDatagen) wipe build/run/clientGameTest/.
#
#   scripts/client-gametest.sh               # dev run (runClientGameTest)
#   scripts/client-gametest.sh --production  # against the built jar (runProductionClientGameTest, as in CI)
#   scripts/client-gametest.sh --video       # also records the Showcase clips into showcase.mp4 (needs ffmpeg)
#   scripts/client-gametest.sh --only=SkyHookClientGameTest,...  # only those test classes, by simple name
#
# Minecraft needs a 24-bit display for OpenGL; xvfb-run defaults to 8-bit on Debian/Ubuntu, so set it explicitly.
# Minecraft 26.x opens its window with SDL3, which prefers Wayland whenever WAYLAND_DISPLAY is set, so on a
# Wayland desktop the game would open on the real screen despite Xvfb. Force SDL onto the X11 (Xvfb) display
# and mute OpenAL so test runs stay invisible and silent.
set -uo pipefail
shopt -s nullglob
cd "$(dirname "$0")/.."

task=runClientGameTest run_dir=build/run/clientGameTest name=client-gametest video=
for arg in "$@"; do
	case $arg in
	--production) task=runProductionClientGameTest run_dir=build/run/clientGameTestProduction name=client-gametest-production ;;
	--video) video=1 ;;
	--only=?*) export PRINCIPIA_CLIENT_TESTS=${arg#--only=} ;;
	*) echo "unknown option: $arg" >&2 && exit 2 ;;
	esac
done

# Stitches the clips Showcase recorded (<frames>/<nn-clip>/<game-ms>.png) into <out>.mp4, in recording order, each
# captioned with its name and each frame held until the next one's game time, so the video runs at game speed. Also
# writes <out>-<nn-clip>.png, eight of the clip's frames on one sheet, and prints how long each clip stays still, for
# an agent, who can't watch the video.
stitch() {
	local frames=$1 out=$2 work clip caption list frame ms prev_ms last
	work=$(mktemp -d)
	for clip in "$frames"/*/; do
		clip=$(basename "$clip") list="$work/$clip.txt" prev_ms=
		caption=${clip#*-} && caption=${caption//-/ }
		local -a all=("$frames/$clip"/*.png)
		[ ${#all[@]} -gt 0 ] || { echo "Clip $caption has no frames" >&2 && continue; }
		for frame in "${all[@]}"; do
			ms=$((10#$(basename "$frame" .png)))
			[ -n "$prev_ms" ] && echo "duration $(awk "BEGIN { print ($ms - $prev_ms) / 1000 }")" >>"$list"
			echo "file '$(realpath "$frame")'" >>"$list"
			prev_ms=$ms last=$frame
		done
		# The concat demuxer ignores the last frame's duration unless the frame is listed again.
		printf "duration 0.05\nfile '%s'\n" "$(realpath "$last")" >>"$list"
		ffmpeg -hide_banner -loglevel error -y -f concat -safe 0 -i "$list" -vf "fps=60,format=yuv420p,drawtext=text='$caption':fontsize=32:fontcolor=white:box=1:boxcolor=black@0.5:boxborderw=10:x=24:y=24" \
			-c:v libx264 -preset slow -crf 23 "$work/$clip.mp4" || return 1
		echo "file '$work/$clip.mp4'" >>"$work/clips.txt"
		for i in 0 1 2 3 4 5 6 7; do
			echo "file '$(realpath "${all[i * (${#all[@]} - 1) / 7]}")'"
		done >"$work/$clip-sheet.txt"
		ffmpeg -hide_banner -loglevel error -y -f concat -safe 0 -i "$work/$clip-sheet.txt" -vf "scale=640:-1,tile=4x2" \
			-frames:v 1 "$out-$clip.png" || return 1
		# Still: nothing on screen changes for a quarter of a second or more (tuned so that a rider hanging at the
		# track's end counts, and one falling doesn't). Black: the screen is.
		ffmpeg -hide_banner -nostats -i "$work/$clip.mp4" -vf "freezedetect=n=-50dB:d=0.25,blackdetect=d=0.1" -f null - 2>&1 |
			awk -v clip="$caption" -v seconds="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$work/$clip.mp4")" '
				/freeze_start:/ { start = $NF }
				/freeze_end:/ { still += $NF - start; start = "" }
				/black_duration:/ { split($0, field, "black_duration:"); black += field[2] }
				END {
					if (start != "") still += seconds - start
					printf "Clip %s: %.1f s, still for %.1f s", clip, seconds, still
					if (black > 0) printf ", black for %.1f s", black
					print ""
				}'
	done
	ffmpeg -hide_banner -loglevel error -y -f concat -safe 0 -i "$work/clips.txt" -c copy -movflags +faststart "$out.mp4" ||
		return 1
	rm -rf "$work"
}

# Start from no screenshots: the production run keeps its run directory between runs, so each evidence folder would
# otherwise also hold every earlier run's shots.
frames="$run_dir/screenshots/video"
rm -rf "$run_dir/screenshots"
if [ -n "$video" ]; then
	encoders=$(ffmpeg -hide_banner -encoders 2>/dev/null) filters=$(ffmpeg -hide_banner -filters 2>/dev/null)
	[[ $encoders == *libx264* && $filters == *drawtext* ]] ||
		{ echo "--video needs ffmpeg with libx264 and drawtext (on Fedora, ffmpeg from RPM Fusion)" >&2 && exit 2; }
	export PRINCIPIA_VIDEO=1
fi

if command -v xvfb-run >/dev/null; then
	env -u WAYLAND_DISPLAY SDL_VIDEO_DRIVER=x11 ALSOFT_DRIVERS=null \
		xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew --console=plain "$task"
else
	echo "xvfb-run not found; running with a visible window" >&2
	./gradlew --console=plain "$task"
fi
status=$?

evidence="build/evidence/$name-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$evidence"
cp -a "$run_dir/screenshots/." "$evidence/" 2>/dev/null || true
rm -rf "$evidence/video"
cp -a "$run_dir/logs/latest.log" "$evidence/client.log" 2>/dev/null || true
echo "Evidence: $evidence ($(find "$evidence" -name '*.png' | wc -l) screenshots, log: $evidence/client.log)"
if [ -n "$video" ]; then
	if [ -z "$(ls -A "$frames" 2>/dev/null)" ]; then
		echo "No video: no Showcase clip recorded" >&2 && status=1
	elif stitch "$frames" "$evidence/showcase"; then
		echo "Video: $evidence/showcase.mp4 ($(du -h "$evidence/showcase.mp4" | cut -f1)), a sheet of frames per clip: $evidence/showcase-*.png"
		[ "$(wc -c <"$evidence/showcase.mp4")" -le $((10 * 1024 * 1024)) ] ||
			echo "The video is over 10 MB, GitHub's limit for videos on free plans (100 MB on paid ones)" >&2
		rm -rf "$frames"
	else
		echo "Stitching the video failed; the frames are in $frames" >&2 && status=1
	fi
fi
exit $status
