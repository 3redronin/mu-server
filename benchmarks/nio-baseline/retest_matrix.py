"""Explicit workload inventory for the resumable three-way baseline."""
import baseline as b
import roundness as r
import downstream_wait as w


def catalog(smoke=False):
    rows = []

    def add(name, family, fixture='async', engine='native', case=None, **extra):
        rows.append(dict(name=name, family=family, fixture=fixture, engine=engine,
                         case=case, paused=0, target_rps=None, window_bits=16, **extra))

    for name in b.CORE:
        add('sync-' + name, 'core', 'sync', case=b.case(name))
    for protocol in ('http1', 'https1', 'h2'):
        case = dict(b.case('small'), protocol=protocol, clients=4)
        for count in (0, 8 if smoke else 128):
            add(f'sync-isolation-{protocol}-{count}', 'isolation', 'sync', case=case)
            rows[-1].update(paused=count, target_rps=200 if smoke else 2000)
    for name in ('h1bulk', 'tlsbulk', 'h2bulk'):
        add('async-' + name, 'async', case=b.case(name))
    add('async-h2multiplex', 'async', case=dict(b.case('h2bulk'), clients=1, streams=8))
    for entropy in ('high', 'low'):
        add('gzip-' + entropy, 'async', case=dict(b.case('h2bulk'), size=65536, gzip=True, compressibility=entropy))
    for protocol in ('https1', 'h2'):
        add('raw-upload-' + protocol, 'async', case=dict(b.case('h2bulk'), protocol=protocol, kind='raw', compressibility='low'))
    add('multipart-h2', 'async', case=dict(b.case('h2bulk'), kind='multipart', compressibility='low'))
    add('small-256-connections', 'async', case=dict(b.case('small'), clients=256))
    for size in (16384, 65536, 131072, 262144):
        add('tls-size-' + str(size), 'sizes', case=dict(b.case('tlsbulk'), size=size))
    for protocol in ('http1', 'https1', 'h2'):
        add('small-eight-' + protocol, 'sizes', case=dict(b.case('small'), protocol=protocol, clients=8))
    for clients, streams, window in ((1, 8, 10), (8, 1, 10), (8, 1, 21)):
        add(f'h2-window-{clients}-{streams}-{window}', 'windows',
            case=dict(b.case('h2bulk'), clients=clients, streams=streams))
        rows[-1]['window_bits'] = window
    for name, size, rate in [('tlsbulk', 1048576, 4000), ('tlsbulk', 65536, 25000), ('h2bulk', 1048576, 1000)]:
        add(f'matched-{name}-{size}', 'matched', case=dict(b.case(name), size=size))
        rows[-1]['target_rps'] = rate
    for size, rate in ((128, 2000), (1048576, None)):
        for count in (0, 8 if smoke else 32):
            add(f'bounded-tls-{size}-{count}', 'bounded', 'bounded',
                case=dict(b.case('tlsbulk'), size=size, clients=4 if size == 128 else 8))
            rows[-1].update(paused=count, target_rps=rate)
    for protocol, counts in [('https1', (0, 128)), ('h2', (0, 128, 768))]:
        for count in counts:
            actual = min(count, 8) if smoke else count
            add(f'streaming-{protocol}-{count}', 'streaming', 'streaming',
                case=dict(b.case('small'), protocol=protocol, clients=4))
            rows[-1].update(paused=actual, target_rps=200 if smoke else 2000)
    for suite in ('multiplex', 'burst', 'fairness'):
        for name, case, rate, stalled, window in r.cases(suite):
            add(f'{suite}-{name}', 'rounded', 'streaming' if suite == 'fairness' else 'async',
                engine='shared' if suite == 'fairness' else 'native', case=case, burst=suite == 'burst', stalled_streams=stalled)
            rows[-1].update(target_rps=rate, window_bits=window)
    for protocol in ('http1', 'https1'):
        for count in (0, 1024, 4096):
            add(f'density-{protocol}-{count}', 'density', 'sync', 'density',
                case=dict(b.case('small'), protocol=protocol, clients=16), idle=min(count, 64) if smoke else count,
                cap_mib=512, heap='256m')
            rows[-1]['target_rps'] = 10000
    for cap in (512, 1024):
        for heap in (cap // 4, cap // 2, cap * 3 // 4, cap):
            for idle in (0, 4096):
                add(f'heap-{cap}-{heap}-{idle}', 'heap-tuning', 'sync', 'density',
                    case=dict(b.case('small'), protocol='https1', clients=16), idle=min(idle, 64) if smoke else idle,
                    cap_mib=cap, heap=f'{heap}m', direct_memory_mib=cap)
                rows[-1]['target_rps'] = 10000
        for idle in (0, 4096):
            add(f'heap-confirm-{cap}-{idle}', 'heap-confirmation', 'sync', 'density',
                case=dict(b.case('small'), protocol='https1', clients=16), idle=min(idle, 64) if smoke else idle,
                cap_mib=cap, selected_heap=True, direct_memory_mib=cap)
            rows[-1]['target_rps'] = 10000
    for protocol in ('http1', 'https1'):
        for background in (0, 8):
            add(f'writers-{protocol}-{background}', 'writers', engine='writers',
                protocol=protocol, background_clients=background)
            rows[-1]['target_rps'] = 1000
    for window in (65535, 2097152):
        for background in (False, True):
            add(f'writers-h2-{window}-{int(background)}', 'writers', engine='h2-writers',
                protocol='h2', stream_window=window, background=background)
            rows[-1]['target_rps'] = 500
    for collector, names in [('G1', ('small', 'h1bulk', 'h2bulk')), ('Serial', ('small',))]:
        for name in names:
            add(f'gc-{collector}-{name}', 'collectors', case=b.case(name), collector=collector)
    for item in w.plan(jdks=(21,), delays=(0, 50, 250), roles=('nio',)):
        add(f'downstream-{item["protocol"]}-{item["style"]}-{item["delay_ms"]}', 'downstream', 'downstream', 'downstream',
            protocol=item['protocol'], style=item['style'], delay_ms=item['delay_ms'])
    if len({row['name'] for row in rows}) != len(rows):
        raise AssertionError('Duplicate workload identity')
    return rows


def plan(settings):
    cases = catalog(settings['profile'] == 'smoke')
    if settings.get('cases'):
        unknown = set(settings['cases']) - {c['name'] for c in cases}
        if unknown: raise ValueError('Unknown cases: ' + repr(sorted(unknown)))
        cases = [c for c in cases if c['name'] in settings['cases']]
    if settings.get('families'):
        unknown = set(settings['families']) - {c['family'] for c in cases}
        if unknown: raise ValueError('Unknown families: ' + repr(sorted(unknown)))
        cases = [c for c in cases if c['family'] in settings['families']]
    if not cases: raise ValueError('No cases selected')
    if any(c['family'] == 'heap-confirmation' for c in cases):
        needed = {c['name'] for c in catalog(settings['profile'] == 'smoke') if c['family'] == 'heap-tuning'}
        if not needed <= {c['name'] for c in cases}:
            raise ValueError('Heap confirmation requires the complete independent tuning grid')
    result = []
    # Complete tuning before any independent confirmation. Do not optimize using confirmation results.
    ordered = sorted(enumerate(cases), key=lambda pair: pair[1]['family'] != 'heap-tuning')
    for phase in ('tuning', 'comparison'):
        for repeat in range(1 if phase == 'tuning' else settings['repeats']):
            for jdk in settings['jdks']:
                for index, case in ordered:
                    if (case['family'] == 'heap-tuning') != (phase == 'tuning'): continue
                    offset = (index + repeat + (jdk == 25)) % 3
                    roles = [role for role in b.ROLES[offset:] + b.ROLES[:offset] if role in settings['roles']]
                    result.append(dict(case, id=f'{phase}/{repeat}/{jdk}/{case["name"]}',
                        repeat=repeat, jdk=jdk, roles=roles, phase=phase,
                        warmup=min(settings['warmup'], 5) if phase == 'tuning' else settings['warmup'],
                        duration=min(settings['duration'], 10) if phase == 'tuning' else settings['duration']))
    return result
