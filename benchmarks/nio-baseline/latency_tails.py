#!/usr/bin/env python3
"""Post-process complete native samples; leave measured ledgers unchanged."""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
import statistics


def tail_percentiles(performance, expected_samples):
    samples = performance['samples']
    if not samples or len(samples) != expected_samples:
        raise ValueError('Exact tail percentiles require the complete declared latency sample vector')
    if any(not math.isfinite(value) or value < 0 for value in samples):
        raise ValueError('Latency samples must be finite nonnegative seconds')
    if any(a > b for a, b in zip(samples, samples[1:])):
        raise ValueError('Native latency sample vector must be sorted')
    if not math.isclose(samples[-1], performance['max'], rel_tol=1e-9, abs_tol=1e-12):
        raise ValueError('Native sample maximum disagrees with the native summary')
    def percentile(fraction): return samples[math.ceil(len(samples) * fraction)-1] * 1000
    return dict(samples=len(samples), p99_ms=percentile(.99), p999_ms=percentile(.999),
                p9999_ms=percentile(.9999), max_ms=samples[-1] * 1000)


def analyze(run):
    ledger = json.loads((run/'results.json').read_text())
    if not ledger['complete']: raise ValueError('Wait for the run to complete before processing tails')
    annotations_path = run/'post-audit-findings.json'
    annotations = json.loads(annotations_path.read_text()) if annotations_path.exists() else {}
    extra_exclusions = annotations.get('performance_exclusions', [])
    rows, groups = [], collections.defaultdict(list)
    for row in ledger['trials']:
        if 'measurement' not in row: continue
        raw = json.loads((run/'trials'/row['key']/'h2load.json').read_text())
        completed = raw['measurements']['requests']['done']
        if completed != row['measurement']['latency_sample_count']:
            raise ValueError('Native vector is incomplete for the declared completed responses')
        tails = tail_percentiles(raw['measurements']['performance']['request'], completed)
        entry = dict(key=row['key'], jdk=row['jdk'], role=row['role'], idle=row['idle'],
                     outcome=row['outcome'], original_performance_exclusions=row['performance_exclusions'],
                     performance_exclusions=row['performance_exclusions'] + extra_exclusions, **tails)
        rows.append(entry); groups[(row['jdk'], row['role'], row['idle'])].append(entry)
    summaries = []
    for (jdk, role, idle), entries in groups.items():
        summaries.append(dict(jdk=jdk, role=role, idle=idle, saved=len(entries),
            valid=sum(e['outcome']=='pass' and not e['performance_exclusions'] for e in entries),
            **{key:statistics.median(e[key] for e in entries) for key in ('p99_ms','p999_ms','p9999_ms','max_ms')},
            worst_max_ms=max(e['max_ms'] for e in entries)))
    return dict(method='Nearest rank ceil(N*q)-1 on complete sorted native seconds, converted to milliseconds. '
                       'Summaries are medians of per-trial percentiles/maxima; worst_max_ms is the worst observed trial maximum. '
                       'Exclusions remain diagnostic. Native request latency is not an open-loop arrival-to-completion SLA.',
                performance_annotations=annotations, controller_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), rows=rows, groups=summaries)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('runs',type=Path,nargs='+')
    for run in parser.parse_args().runs:
        output=run/'latency-tails.json'
        output.write_text(json.dumps(analyze(run),indent=2)+'\n')
        print(output)


if __name__=='__main__':main()
