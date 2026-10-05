#!/usr/bin/env python3
"""Recompute the published demo results from console logs; never calls an API."""
import argparse
import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'experiments' / '2026-10-05'
ROUND = re.compile(r'第(\d+)回合 · (普通AI|激进AI) · AI · 收割(\d+) · 恢复(\d+) · 麦田(\d+)/48 · 缺粮(\d+)人')
CALL = re.compile(r'VC_ROUND source=(\S+) ai_profile=(\S+) requests=(\d+).*latency_ms=(\d+)')
FIELDS = ['profile', 'round', 'harvest', 'regrowth', 'mature_wheat', 'shortage_people', 'source', 'requests', 'latency_ms']


def parse_log(path, profile):
    rows, pending = [], None
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        match = ROUND.search(line)
        if match:
            if pending is not None:
                raise ValueError(f'{path.name}: round missing VC_ROUND metadata')
            n, label, harvest, regrowth, wheat, shortage = match.groups()
            if label != {'normal': '普通AI', 'greedy': '激进AI'}[profile]:
                raise ValueError(f'{path.name}: unexpected profile')
            pending = dict(zip(FIELDS[:6], [profile, int(n), int(harvest), int(regrowth), int(wheat), int(shortage)]))
        call = CALL.search(line)
        if call:
            source, actual_profile, requests, latency = call.groups()
            if pending is None or source != 'AI' or actual_profile != profile:
                raise ValueError(f'{path.name}: inconsistent source or round metadata')
            pending.update(source=source, requests=int(requests), latency_ms=int(latency))
            rows.append(pending)
            pending = None
    if pending is not None or len(rows) < 25:
        raise ValueError(f'{path.name}: incomplete comparison log')
    previous = 48
    for number, row in enumerate(rows, 1):
        if row['round'] != number:
            raise ValueError(f'{path.name}: duplicate or discontinuous rounds')
        remaining = previous - row['harvest']
        expected_growth = min(48 - remaining, remaining // 4)
        if not 0 <= row['harvest'] <= min(previous, 12) or row['regrowth'] != expected_growth:
            raise ValueError(f'{path.name}: resource formula mismatch at R{number}')
        if row['mature_wheat'] != remaining + expected_growth or not 0 <= row['shortage_people'] <= 4:
            raise ValueError(f'{path.name}: invalid completed state at R{number}')
        previous = row['mature_wheat']
    return rows


def summarize(rows):
    return {
        'completed_rounds': len(rows),
        'all_sources_ai': all(row['source'] == 'AI' for row in rows),
        'first_depletion_round': next((r['round'] for r in rows if r['mature_wheat'] == 0), None),
        'first_shortage_round': next((r['round'] for r in rows if r['shortage_people'] > 0), None),
        'round_25': rows[24],
        'last_round': rows[-1],
    }


def chart(groups):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib.ticker import MaxNLocator

    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 11, 'axes.spines.top': False, 'axes.spines.right': False})
    fig, axes = plt.subplots(2, 1, figsize=(11, 6.8), sharex=True, constrained_layout=True)
    colors = {'normal': '#287b65', 'greedy': '#bd583f'}
    labels = {'normal': 'Normal AI', 'greedy': 'Greedy AI'}
    for profile, rows in groups.items():
        data = rows[:25]
        x = [r['round'] for r in data]
        for ax, key in zip(axes, ('mature_wheat', 'shortage_people')):
            ax.plot(x, [r[key] for r in data], color=colors[profile], label=labels[profile], linewidth=2.5, marker='o', markersize=3.5)
    axes[0].set_title('Public wheat field: AI decisions change shared resources', loc='left', pad=14, fontweight='bold')
    axes[0].set_ylabel('Mature wheat (of 48)')
    axes[0].set_ylim(-3, 54)
    axes[0].set_yticks([0, 12, 24, 36, 48])
    axes[0].legend(loc='center right', frameon=False)
    axes[0].annotate('Field depleted at round 8', xy=(8, 0), xytext=(10.5, 17), color=colors['greedy'], arrowprops={'arrowstyle': '->', 'color': colors['greedy']})
    axes[1].set_ylabel('People without food')
    axes[1].set_xlabel('Completed round (first 25 per profile)')
    axes[1].set_ylim(-0.25, 4.6)
    axes[1].set_yticks(range(5))
    axes[1].annotate('First shortage at round 21', xy=(21, 1), xytext=(10.5, 2.8), color=colors['greedy'], arrowprops={'arrowstyle': '->', 'color': colors['greedy']})
    for ax in axes:
        ax.set_xlim(0.5, 25.5)
        ax.grid(axis='y', color='#e8eceb', linewidth=0.8)
        ax.set_axisbelow(True)
        ax.xaxis.set_major_locator(MaxNLocator(integer=True, nbins=9))
    assets = ROOT / 'docs' / 'assets'
    assets.mkdir(exist_ok=True)
    fig.savefig(assets / 'experiment-comparison.png', dpi=180, facecolor='white')
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--chart', action='store_true', help='Also regenerate the README PNG (requires matplotlib)')
    args = parser.parse_args()
    groups = {p: parse_log(DATA / f'{p}.log', p) for p in ('normal', 'greedy')}
    with (DATA / 'rounds.csv').open('w', encoding='utf-8', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        for rows in groups.values():
            writer.writerows(rows)
    summary = {
        'date': '2026-10-05', 'project_version': '0.3.1', 'comparison_rounds_per_profile': 25,
        'runs_per_profile': 1, 'model_id': None, 'sampling_parameters': None,
        'model_metadata_note': 'The supplied console logs do not include model ID or sampling parameters.',
        'initial_state': {'mature_wheat': 48, 'villagers': 4, 'private_wheat_per_villager': 1},
        'initial_state_source': 'Scenario setup in the project; not an additional completed log round.',
        'normal_budget_pause': {'attempted_round': 3, 'requests': 0, 'committed': False, 'resumed_at_round': 3},
        'profiles': {p: summarize(rows) for p, rows in groups.items()},
    }
    (DATA / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if args.chart:
        chart(groups)
    print(f"Parsed and validated {sum(map(len, groups.values()))} completed AI rounds; comparison uses 25 per profile.")


if __name__ == '__main__':
    main()
