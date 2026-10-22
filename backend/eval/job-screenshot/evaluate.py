#!/usr/bin/env python3
"""Seed-rendered screenshots and a live V41 HTTP evaluator. Never stores credentials."""
import argparse
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import re
import subprocess
import time
import unicodedata
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[3]
SEED = ROOT / 'backend/offerpilot-job/src/main/resources/jobs/snapshot.json'
FIELDS = ('title', 'company', 'locations', 'description', 'requirement')


def norm(value):
    return re.sub(r'\s+', '', unicodedata.normalize('NFKC', value or '')).casefold()


def lines(value):
    return {norm(re.sub(r'^\s*(?:[-*•·●▪]|\d{1,2}\s*[.、)）]|[（(]\d{1,2}[)）])\s*', '', line))
            for line in (value or '').splitlines() if line.strip()}


def cities(value):
    return {norm(city) for city in re.split(r'[、,，/;；\s]+', value or '') if city}


def line_score(expected, actual):
    truth, prediction = lines(expected), lines(actual)
    hit = len(truth & prediction)
    precision = hit / len(prediction) if prediction else float(not truth)
    recall = hit / len(truth) if truth else float(not prediction)
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {'precision': precision, 'recall': recall, 'f1': f1}


def score(expected, actual):
    actual = actual or {}
    values = {field: float(norm(expected.get(field)) == norm(actual.get(field)))
              for field in ('title', 'company', 'department', 'nature')}
    values['locations'] = float(cities(expected.get('locations')) == cities(actual.get('locations')))
    for field in ('description', 'requirement'):
        values[field] = line_score(expected.get(field), actual.get(field))
    return values


def selected():
    seed = json.loads(SEED.read_text())
    # Stable, spread across companies, roles and recruitment types, without creating new JD text.
    jobs = seed['jobs']
    if len(jobs) < 20:
        raise ValueError('Need at least 20 existing seed jobs')
    return seed, [jobs[i * (len(jobs) - 1) // 19] for i in range(20)]


def content(job):
    return '\n'.join([
        '职位名称：' + job['title'], '公司：' + job['company'],
        '工作城市：' + job['locations'], '部门：' + job['department'],
        '招聘类型：' + {'campus': '校园招聘', 'intern': '实习', 'social': '社会招聘'}[job['nature']],
        '岗位职责：', job['description'], '任职要求：', job['requirement']])


def generate(output):
    output.mkdir(parents=True, exist_ok=True)
    seed, jobs = selected()
    samples = []
    for i, job in enumerate(jobs):
        layout = ('app-card', 'web-detail', 'long-image')[i % 3]
        samples.append({'id': job['id'], 'layout': layout, 'text': content(job),
                        'expected': {k: job[k] for k in (*FIELDS, 'department', 'nature')},
                        'images': []})
    manifest = {'source': str(SEED.relative_to(ROOT)), 'sourceSha256': hashlib.sha256(SEED.read_bytes()).hexdigest(),
                'sourceDescription': seed['sourceDescription'], 'sampleKind': 'seed-rendered-not-user-screenshots', 'samples': samples}
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        # Exact same source and annotation, browser wrapping. No hand-built replacement JD.
        path = output / 'manifest.json'
        path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
        subprocess.run(['node', str(Path(__file__).with_name('render.mjs')), str(path)], check=True)
        return
    font_path = os.environ.get('SCREENSHOT_FONT', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')
    if not Path(font_path).exists():
        raise ValueError('Set SCREENSHOT_FONT to a CJK font; refusing unreadable placeholder glyphs')
    for i, sample in enumerate(samples):
        width, size, padding = (780, 28, 40) if sample['layout'] == 'app-card' else (1200, 30, 70) if sample['layout'] == 'web-detail' else (680, 28, 32)
        font = ImageFont.truetype(font_path, size)
        wrapped = []
        for line in sample['text'].splitlines():
            buf = ''
            for char in line:
                if font.getlength(buf + char) > width - padding * 2 and buf:
                    wrapped.append(buf); buf = ''
                buf += char
            wrapped.append(buf)
        line_height = size + 15
        height = padding * 2 + line_height * (len(wrapped) + 1)
        image = Image.new('RGB', (width, height), '#f4f6fa')
        draw = ImageDraw.Draw(image)
        draw.rounded_rectangle((padding // 2, padding // 2, width - padding // 2, height - padding // 2), radius=20, fill='white')
        draw.rectangle((padding, padding, width - padding, padding + 5), fill=('#176960' if i % 3 == 0 else '#365a9c'))
        for index, line in enumerate(wrapped):
            draw.text((padding, padding + 22 + index * line_height), line, font=font, fill='#192d3e')
        # Long images also exercise 2/3-page ordered input; slices share a complete line for overlap.
        parts = 1 if sample['layout'] != 'long-image' else 2 + i % 2
        for page in range(parts):
            top = 0 if page == 0 else max(0, page * height // parts - line_height)
            bottom = height if page == parts - 1 else (page + 1) * height // parts
            crop = image.crop((0, top, width, bottom))
            suffix = ('png', 'jpg', 'webp')[(i + page) % 3]
            filename = f'{i+1:02d}-{sample["layout"]}-{page+1}.{suffix}'
            crop.save(output / filename, quality=90)
            sample['images'].append(filename)
    (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))


class Backend:
    def __init__(self, base, token, timeout):
        self.base, self.token, self.timeout = base.rstrip('/'), token, timeout

    def request(self, method, path, body=None, content_type='application/json'):
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=body, method=method,
                                         headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': content_type})
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                envelope = json.load(response)
                if envelope.get('code', 0) != 0:
                    raise RuntimeError(envelope.get('message', 'API failed'))
                return envelope['data']
        except urllib.error.HTTPError as error:
            # Do not log request headers / tokens.
            raise RuntimeError(f'HTTP {error.code}: {error.read(2000).decode(errors="replace")}') from None

    def parse(self, directory, sample):
        ids = []
        for name in sample['images']:
            path = directory / name
            boundary = 'screenshot-eval-' + uuid.uuid4().hex
            body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\n'
                    f'Content-Type: {mimetypes.guess_type(name)[0]}\r\n\r\n').encode() + path.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
            ids.append(self.request('POST', '/jobs/import/screenshots', body, 'multipart/form-data; boundary=' + boundary)['fileId'])
        task = self.request('POST', '/generation-tasks', {'kind': 'JOB_IMPORT', 'entityId': 'current',
                            'requestId': str(uuid.uuid4()), 'input': {'fileIds': ids}})
        deadline = time.monotonic() + self.timeout
        while task['status'] in ('QUEUED', 'RUNNING') and time.monotonic() < deadline:
            time.sleep(1)
            task = self.request('GET', '/generation-tasks/' + task['id'])
        if task['status'] != 'SUCCEEDED':
            raise RuntimeError(f'task {task["id"]}: {task["status"]}: {task.get("error") or "poll timeout; task may still be running"}')
        return self.request('GET', '/generation-tasks/' + task['id'] + '/result')['draft']


class StubBackend:
    """Scorer/fixture plumbing smoke test only. Deliberately returns annotations, never claims accuracy."""
    def parse(self, directory, sample):
        assert 1 <= len(sample['images']) <= 3
        for name in sample['images']:
            assert 0 < (directory / name).stat().st_size <= 5 * 1024 * 1024
        return dict(sample['expected'])


def evaluate(directory, output, backend, stub):
    manifest = json.loads((directory / 'manifest.json').read_text())
    results = []
    for sample in manifest['samples']:
        try:
            actual = backend.parse(directory, sample)
            error = None
        except Exception as ex:
            actual, error = {}, str(ex)
        metrics = score(sample['expected'], actual)
        if error:  # Every failed sample counts as zero, including empty metadata.
            metrics = {k: ({'precision': 0, 'recall': 0, 'f1': 0} if isinstance(v, dict) else 0) for k, v in metrics.items()}
        results.append({'id': sample['id'], 'layout': sample['layout'], 'actual': actual, 'metrics': metrics, 'error': error})
        print(f'{sample["id"]}: {error or "scored"}')
    def mean(field, sub=None):
        return sum(r['metrics'][field][sub] if sub else r['metrics'][field] for r in results) / len(results)
    summary = {field: ({k: mean(field, k) for k in ('precision', 'recall', 'f1')} if field in ('description', 'requirement') else mean(field)) for field in (*FIELDS, 'department', 'nature')}
    accuracy = sum(summary[f]['f1'] if isinstance(summary[f], dict) else summary[f] for f in FIELDS) / len(FIELDS)
    report = {'mode': 'stub-annotations-not-live' if stub else 'live', 'sampleKind': manifest['sampleKind'],
              'sourceSha256': manifest['sourceSha256'], 'count': len(results), 'failures': sum(bool(r['error']) for r in results),
              'summary': summary, 'macroAccuracy': accuracy, 'threshold': .85,
              'passesLiveGate': None if stub else accuracy >= .85, 'results': results}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps({k: v for k, v in report.items() if k != 'results'}, ensure_ascii=False, indent=2))
    return report


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('action', choices=['generate', 'evaluate'])
    cli.add_argument('--samples', type=Path, default=Path('/tmp/offerpilot-job-screenshot'))
    cli.add_argument('--output', type=Path, default=Path('/tmp/offerpilot-job-screenshot-results.json'))
    cli.add_argument('--base-url', default='http://127.0.0.1:8081/api/v1')
    cli.add_argument('--timeout', type=int, default=300)
    cli.add_argument('--stub', action='store_true')
    args = cli.parse_args()
    if args.action == 'generate':
        generate(args.samples)
        print('Generated seed-rendered screenshots:', args.samples)
    else:
        token = os.environ.get('OFFERPILOT_ACCESS_TOKEN', '')
        if not args.stub and not token:
            cli.error('Set OFFERPILOT_ACCESS_TOKEN to a local backend user token (never a command-line argument)')
        backend = StubBackend() if args.stub else Backend(args.base_url, token, args.timeout)
        report = evaluate(args.samples, args.output, backend, args.stub)
        if not args.stub and not report['passesLiveGate']:
            raise SystemExit(1)


if __name__ == '__main__':
    main()
