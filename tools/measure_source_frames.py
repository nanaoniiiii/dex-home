"""Read-only source-app renderer and display measurements for an active DeX HOME.

Use an independent continuously animating app on the DeX display. Its package is
explicit: the main DeX Home application has no runtime dependency on the probe.
"""
import argparse
import json
from pathlib import Path
import re
import statistics
import subprocess
import time


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--serial", required=True)
    p.add_argument("--package", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--seconds", type=int, default=20)
    a = p.parse_args()
    if not 10 <= a.seconds <= 120 or not re.fullmatch(r"[A-Za-z0-9_.]+", a.package):
        p.error("Invalid package or duration")
    a.output.mkdir(parents=True, exist_ok=True)

    def adb(*args):
        r = subprocess.run(["adb", "-s", a.serial, *args], capture_output=True, timeout=25, check=True)
        return r.stdout.decode("utf-8", errors="replace")

    def renderer(tag):
        before = float(adb("shell", "cat", "/proc/uptime").split()[0])
        data = adb("shell", "dumpsys", "gfxinfo", a.package, "framestats")
        after = float(adb("shell", "cat", "/proc/uptime").split()[0])
        (a.output / f"{tag}-gfxinfo.txt").write_text(data, encoding="utf-8")
        count = int(re.search(r"Total frames rendered:\s*(\d+)", data)[1])
        return (before + after) / 2, count, data

    start, first, _ = renderer("before")
    for elapsed in range(0, a.seconds, 5):
        time.sleep(min(5, a.seconds - elapsed))
        print(f"Sampling {min(elapsed + 5, a.seconds)}/{a.seconds}s", flush=True)
    end, last, data = renderer("after")
    times = []
    index = 1
    for line in data.splitlines():
        if line.startswith("Flags,"):
            index = line.split(",").index("IntendedVsync")
        elif re.match(r"^0,\d", line):
            times.append(int(line.split(",")[index]))
    gaps = [y-x for x, y in zip(times, times[1:]) if 0 < y-x < 250_000_000]
    (a.output / "displays.txt").write_text(adb("shell", "dumpsys", "display"), encoding="utf-8")
    (a.output / "surfaceflinger.txt").write_text(adb("shell", "dumpsys", "SurfaceFlinger"), encoding="utf-8")
    (a.output / "activities.txt").write_text(adb("shell", "dumpsys", "activity", "activities"), encoding="utf-8")
    app = adb("shell", "cat", "/sdcard/Android/data/com.alkaidlab.dexlocal/files/diagnostics.json")
    (a.output / "home-state.json").write_text(app, encoding="utf-8")
    report = {"package": a.package, "elapsed_seconds": end-start, "rendered_frames_before": first,
              "rendered_frames_after": last, "rendered_frames_delta": last-first,
              "source_rendered_fps": (last-first)/(end-start),
              "source_vsync_interval_ms_median": statistics.median(gaps)/1e6 if gaps else None,
              "note": "Source app HWUI frames; inspect activities for display identity and SF separately for panel mode. Not input-to-photon latency."}
    (a.output / "summary.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
