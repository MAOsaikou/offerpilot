"""RS-01 upload-to-terminal timing. Standard library only; live HTTP requires explicit invocation.

Every sample counts, including errors/timeouts. Observed progress is polling evidence, not fabricated
intermediate stages. No answer cache, backend safety or quota overrides.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import math
import pathlib
import statistics
import time
import urllib.request
import uuid

TYPES = {'.pdf': 'application/pdf', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
         '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'}


def call(req, timeout=120):
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.load(response)


def guest(base):
    return call(urllib.request.Request(base + '/api/v1/auth/guest', data=b'', method='POST'))['data']['accessToken']


def upload(base, token, path, timeout=120):
    boundary = uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
            f'Content-Type: {TYPES[path.suffix.lower()]}\r\n\r\n').encode() + path.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
    return call(urllib.request.Request(base + '/api/v1/resumes', data=body, method='POST', headers={
        'Authorization': 'Bearer ' + token, 'Content-Type': 'multipart/form-data; boundary=' + boundary}), timeout)['data']['id']


def one(base, token, path, limit):
    start = time.monotonic()
    observations = []
    try:
        resume = upload(base, token, path, limit)
        while time.monotonic() - start < limit:
            remaining = max(0.001, limit - (time.monotonic() - start))
            data = call(urllib.request.Request(base + '/api/v1/resumes/' + resume,
                        headers={'Authorization': 'Bearer ' + token}), remaining)['data']
            now = time.monotonic() - start
            state = {'status': data['parseStatus'], 'progress': data['parseProgress']}
            if not observations or any(state[key] != observations[-1][key] for key in state):
                observations.append({**state, 'seconds': now})
            if state['status'] in ('READY', 'FAILED'):
                return {'status': state['status'], 'seconds': now, 'observedProgress': observations}
            time.sleep(min(0.25, max(0, limit - now)))
        return {'status': 'TIMEOUT', 'seconds': time.monotonic() - start, 'observedProgress': observations}
    except Exception as error:
        # Never include HTTP bodies, private content, provider errors or bearer tokens.
        return {'status': 'ERROR', 'errorType': type(error).__name__,
                'seconds': time.monotonic() - start, 'observedProgress': observations}


def pct(values, percentile):
    return sorted(values)[max(0, math.ceil(percentile / 100 * len(values)) - 1)]


def summary(rows):
    groups = []
    for kind in sorted({row['kind'] for row in rows}):
        group = [row for row in rows if row['kind'] == kind]
        ok = [row['seconds'] for row in group if row['status'] == 'READY']
        # DOCX has no published latency threshold. Image PDF is explicitly labelled by filename/manifest.
        budget = 30 if kind in ('png', 'jpg', 'jpeg', 'scanned-pdf') else 10 if kind == 'pdf' else None
        groups.append({'kind': kind, 'n': len(group), 'ready': len(ok), 'failed': len(group) - len(ok),
                       'p50ReadySeconds': statistics.median(ok) if ok else None,
                       'p95ReadySeconds': pct(ok, 95) if ok else None, 'maxReadySeconds': max(ok) if ok else None,
                       'budgetSeconds': budget, 'withinBudget': sum(value <= budget for value in ok) if budget else None,
                       'passesTimingGate': len(ok) == len(group) and max(ok) <= budget if budget else None})
    return groups


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('dir', type=pathlib.Path)
    parser.add_argument('--base', default='http://127.0.0.1:18082')
    parser.add_argument('--per-guest', type=int, default=4)
    parser.add_argument('--workers', type=int, default=1, help='concurrent uploads, including backend queue time')
    parser.add_argument('--limit', type=float, default=120)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    if args.workers < 1 or args.per_guest < 1 or args.limit <= 0:
        parser.error('workers/per-guest/limit must be positive')
    # Prefer corpus manifest to avoid counting intermediate DOCX/page PNG artifacts as independent samples.
    manifest = args.dir / 'manifest.json'
    expected_hashes = {}
    if manifest.exists():
        samples = json.loads(manifest.read_text())['samples']
        files = [args.dir / sample['file'] for sample in samples if pathlib.Path(sample['file']).suffix.lower() in TYPES]
        expected_hashes = {sample['file']: sample['sha256'] for sample in samples}
    else:
        files = sorted(path for path in args.dir.iterdir() if path.suffix.lower() in TYPES)
    if not files:
        parser.error('no supported input samples')
    def run(index_path):
        index, path = index_path
        # Each concurrent group uses its own identity; never bypass server limits.
        digest = None
        try:
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if path.name in expected_hashes and digest != expected_hashes[path.name]:
                raise ValueError('source fingerprint mismatch')
            token = tokens[index // args.per_guest]
            if token is None:
                raise RuntimeError('identity unavailable')
            result = one(args.base.rstrip('/'), token, path, args.limit)
        except Exception as error:
            result = {'status': 'ERROR', 'errorType': type(error).__name__, 'seconds': 0, 'observedProgress': []}
        return {'file': path.name, 'sha256': digest,
                'kind': 'scanned-pdf' if path.name.endswith('-scan.pdf') else path.suffix[1:].lower(), **result}
    tokens = []
    for _ in range(math.ceil(len(files) / args.per_guest)):
        try:
            tokens.append(guest(args.base.rstrip('/')))
        except Exception:
            tokens.append(None)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        rows = list(pool.map(run, enumerate(files)))
    report = {'samples': rows, 'summary': summary(rows), 'workers': args.workers,
              'timingScope': 'upload start through terminal observation; includes backend queue',
              'allReady': all(row['status'] == 'READY' for row in rows)}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({key: value for key, value in report.items() if key != 'samples'}, ensure_ascii=False))
    return 0 if report['allReady'] and all(g['passesTimingGate'] is not False for g in report['summary']) else 1


if __name__ == '__main__':
    raise SystemExit(main())
