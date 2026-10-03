"""Read-only endurance summary. Standard library only; never imports or invokes ADB.

Presentation is recomputed from distinct SurfaceFlinger timestamps inside the
report's confirmed INTERACTIVE intervals. Resource bins include every sample in
the time bin (including initial startup); these scopes are deliberately explicit.
"""
import argparse
import bisect
import hashlib
import json
import statistics
import sys
from pathlib import Path


def mean(values):
    return statistics.fmean(values) if values else None


def presentation_bin(stamps, intervals, left, right, gaps, ambiguous=()):
    periods = duration_ns = active_ns = 0
    for interval in intervals:
        start, end = max(left, interval["start_ns"]), min(right, interval["end_ns"])
        if end <= start:
            continue
        active_ns += end - start
        a, b = bisect.bisect_left(stamps, start), bisect.bisect_left(stamps, end)
        if b - a >= 2:
            periods += b - a - 1
            duration_ns += stamps[b - 1] - stamps[a]
    lost_ns = sum(max(0, min(right, gap["end_ns"]) - max(left, gap["start_ns"])) for gap in gaps)
    ambiguity_count = sum(min(right, item["end_ns"]) > max(left, item["start_ns"]) for item in ambiguous)
    return dict(presented_fps=periods * 1e9 / duration_ns if duration_ns and not ambiguity_count else None,
                interactive_seconds=active_ns / 1e9, sampled_seconds=duration_ns / 1e9,
                surface_gap_seconds=lost_ns / 1e9, rate_is_lower_bound=bool(lost_ns),
                ambiguous_surface_interval_count=ambiguity_count)


def summarize(report, bin_minutes=5):
    samples, statuses = report["samples"], report["statuses"]
    if not samples or bin_minutes < 1:
        raise ValueError("Need resource samples and a positive bin size")
    start, end = samples[0]["device_monotonic_ns"], samples[-1]["device_monotonic_ns"]
    span = bin_minutes * 60_000_000_000
    stamps = sorted({t for row in report["surface_windows"] for t in row.get("timestamps_ns", [])
                     if isinstance(t, int) and 0 < t < 2**63 - 1})
    evidence = report["presentation"]
    bins = []
    for left in range(start, end + 1, span):
        right = min(left + span, end + 1)
        selected = [s for s in samples if left <= s["device_monotonic_ns"] < right]
        pss = [s["app_pss_kib"] / 1024 for s in selected if "app_pss_kib" in s]
        temperature = [s["temperature_c"] for s in selected]
        clock = [s["cpu0_khz"] / 1000 for s in selected if "cpu0_khz" in s]
        usage = []
        for before, after in zip(selected, selected[1:]):
            delta = [b - a for a, b in zip(before["cpu_ticks"], after["cpu_ticks"])]
            if sum(delta) > 0:
                usage.append(100 * (sum(delta) - delta[3] - delta[4]) / sum(delta))
        fresh = [s["result_age_ms"] for s in statuses if s.get("state") == "INTERACTIVE"
                 and left <= s["updated_monotonic_ns"] < right]
        row = presentation_bin(stamps, evidence["intervals"], left, right, evidence["surface_history_gaps"],
                               evidence.get("ambiguous_surface_intervals", []))
        row.update(start_minute=(left-start)/60e9, end_minute=(right-start)/60e9,
                   resource_samples=len(selected), cpu_percent_whole_machine=mean(usage),
                   cpu0_mhz_min=min(clock) if clock else None, cpu0_mhz_mean=mean(clock),
                   temperature_c_mean=mean(temperature), temperature_c_max=max(temperature),
                   app_pss_mib_mean=mean(pss), app_pss_mib_peak=max(pss) if pss else None,
                   fresh_age_ms_max=max(fresh) if fresh else None)
        bins.append(row)
    errors = [s["updated_monotonic_ns"] for s in statuses if s.get("state") == "ERROR" or s.get("error")
              or s.get("render_error") or s.get("render_fault") or s.get("processing_fault")
              or s.get("renderer", {}).get("error") or s.get("source", {}).get("error")]
    fresh = [s["result_age_ms"] for s in statuses if s.get("state") == "INTERACTIVE"]
    last = statuses[-1]
    return dict(collection_status=report["collection_status"], elapsed_seconds=(end-start)/1e9,
                input=report["arguments"]["input"], view_count=last["view_count"],
                view_width=last["view_width"], view_height=last["view_height"],
                diagnostic_character=last["diagnostic_character"],
                interactive_presented_fps=evidence["interactive_presented_fps"],
                meets_30_fps=evidence["meets_30_fps"], complete_active_evidence=evidence["complete_active_evidence"],
                rate_is_lower_bound=evidence["rate_is_lower_bound"],
                surface_history_gaps=evidence["surface_history_gaps"],
                ambiguous_surface_intervals=evidence.get("ambiguous_surface_intervals", []),
                unconfirmed_tail_seconds=evidence["unconfirmed_tail_seconds"],
                state_history_gap_count=len(evidence["state_history_gaps"]),
                collection_error_count=len(report["collection_errors"]), status_error_count=len(errors),
                max_recoveries=max(s["recoveries"] for s in statuses),
                max_capture_failures=max(s.get("source", {}).get("capture_failures", 0) for s in statuses),
                interactive_status_count=len(fresh), result_age_ms_max=max(fresh) if fresh else None,
                final_metrics=last["metrics"], bins=bins,
                scopes={"fps": "Distinct actual-presentation timestamp intervals, confirmed INTERACTIVE only; same estimator as original collector",
                        "resources": "All samples per time bin, including startup in first bin; frequency is cpu0 only, not whole-SoC policy",
                        "freshness": "Sampled INTERACTIVE status ages, not every individual inference frame"})


def markdown(summary):
    def metric(value, spec=""):
        return "—" if value is None else format(value, spec)

    print("| 分钟 | 互动呈现 FPS | CPU 占用 % | CPU0 MHz 均值 / 最低 | 温度 °C 均值 / 峰值 | PSS MiB 均值 / 峰值 | 结果年龄峰值 ms |")
    print("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    for row in summary["bins"]:
        mark = "†" if row["rate_is_lower_bound"] else ""
        if row.get("ambiguous_surface_interval_count", 0):
            mark += "‡"
        print(f'| {row["start_minute"]:.0f}–{row["end_minute"]:.0f} | {metric(row["presented_fps"], ".3f")}{mark} '
              f'| {metric(row["cpu_percent_whole_machine"], ".2f")} '
              f'| {metric(row["cpu0_mhz_mean"], ".0f")} / {metric(row["cpu0_mhz_min"], ".0f")} '
              f'| {metric(row["temperature_c_mean"], ".2f")} / {metric(row["temperature_c_max"], ".3f")} '
              f'| {metric(row["app_pss_mib_mean"], ".2f")} / {metric(row["app_pss_mib_peak"], ".2f")} '
              f'| {metric(row["fresh_age_ms_max"])} |')
    print("\n—：未确认或未采到；†：呈现历史有缺口；‡：重叠显示图层使呈现帧率无法确认。")


def main():
    # Reproducible UTF-8 tables on Windows hosts whose default console code page is GBK.
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--markdown", action="store_true")
    args = parser.parse_args()
    data = args.report.read_bytes()
    summary = summarize(json.loads(data))
    summary.update(report=str(args.report.resolve()), sha256=hashlib.sha256(data).hexdigest())
    if args.markdown:
        markdown(summary)
        print("\nSHA256: " + summary["sha256"])
    else:
        print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
