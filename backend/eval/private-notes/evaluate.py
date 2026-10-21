#!/usr/bin/env python3
"""CP-01: evaluate actual private-note search HTTP results; fixtures are synthetic."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import time
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen


def local_url(value):
    parsed = urlsplit(value)
    if parsed.scheme != 'http' or parsed.hostname not in {
        'localhost', '127.0.0.1', '::1', 'host.docker.internal', 'ollama'
    } or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('Only local HTTP services without URL credentials are allowed')
    return value.rstrip('/')


class Client:
    def __init__(self, base, api_key=None):
        self.base = local_url(base)
        self.api_key = api_key

    def call(self, method, path, body=None, token=None, envelope=True):
        headers = {'Content-Type': 'application/json'}
        if token or self.api_key:
            headers['Authorization'] = 'Bearer ' + (token or self.api_key)
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
        with urlopen(Request(self.base + path, data=data, headers=headers, method=method), timeout=60) as response:
            value = json.load(response)
        if envelope:
            if value.get('code') != 0:
                raise ValueError('Application request failed')
            return value.get('data')
        return value


def validate(data):
    if data.get('kind') != 'SYNTHETIC_FIXTURE':
        raise ValueError('Only explicitly synthetic fixtures are accepted')
    notes = {n['id']: n for n in data['notes']}
    if len(notes) != len(data['notes']) or not notes:
        raise ValueError('Duplicate or empty notes')
    seen = set()
    for q in data['questions']:
        if q['id'] in seen:
            raise ValueError('Duplicate question')
        seen.add(q['id'])
        for key in q['relevant']:
            if key not in notes or notes[key]['owner'] != q['owner']:
                raise ValueError('Relevant note must belong to question owner')
        if any(key not in notes for key in q.get('forbidden', [])):
            raise ValueError('Unknown forbidden note')
        if set(q.get('forbidden', [])) & set(q['relevant']):
            raise ValueError('Contradictory labels')
    return notes


def summarize(data, rows, k=3):
    """No missing/failed query may silently improve the denominator."""
    notes = validate(data)
    questions = data['questions']
    if k < 1 or len(rows) != len(questions) or {r['id'] for r in rows} != {q['id'] for q in questions}:
        raise ValueError('One result per question required')
    by_id = {r['id']: r for r in rows}
    scored, negative, violations = [], [], 0
    for q in questions:
        row = by_id[q['id']]
        ranked = row['ranked']
        if len(set(ranked)) != len(ranked) or any(key not in notes for key in ranked):
            raise ValueError('Unknown or duplicate result ID')
        violations += sum(notes[key]['owner'] != q['owner'] or key in q.get('forbidden', []) for key in ranked)
        if row.get('error'):
            ranked = []
        relevant = set(q['relevant'])
        if relevant:
            ranks = [i + 1 for i, key in enumerate(ranked[:k]) if key in relevant]
            scored.append((int(bool(ranked) and ranked[0] in relevant), int(bool(ranks)),
                           len(ranks) / len(relevant), 1 / min(ranks) if ranks else 0))
        else:
            negative.append(not ranked and not row.get('error'))
    return {
        'positiveQuestions': len(scored), 'negativeQuestions': len(negative),
        'hitAt1': sum(r[0] for r in scored) / len(scored) if scored else None,
        f'hitAt{k}': sum(r[1] for r in scored) / len(scored) if scored else None,
        f'recallAt{k}': sum(r[2] for r in scored) / len(scored) if scored else None,
        f'mrrAt{k}': sum(r[3] for r in scored) / len(scored) if scored else None,
        f'negativeEmptyAt{k}': sum(negative) / len(negative) if negative else None,
        'ownerViolations': violations, 'failedQuestions': sum(bool(r.get('error')) for r in rows),
    }


def probe_embedding(client, model):
    if model not in {'bge-m3', 'bge-m3:latest'}:
        raise ValueError('This evaluation requires local bge-m3')
    value = client.call('POST', '/v1/embeddings', {'model': model, 'input': ['合成检索评测连通检查']}, envelope=False)
    vector = value['data'][0]['embedding']
    if len(vector) != 1024 or not all(isinstance(x, (int, float)) and math.isfinite(x) for x in vector) or not any(vector):
        raise ValueError('Expected a finite nonzero 1024-dimensional bge-m3 vector')


def run(data, api, embedding, model, wait_seconds=240):
    notes = validate(data)
    probe_embedding(embedding, model)
    tokens, created, rows = {}, [], []
    try:
        for owner in sorted({n['owner'] for n in notes.values()}):
            tokens[owner] = api.call('POST', '/api/v1/auth/guest')['accessToken']
        mapping = {}
        for note in notes.values():
            value = api.call('POST', '/api/v1/knowledge/notes',
                             {k: note[k] for k in ('title', 'content', 'category')}, tokens[note['owner']])
            mapping[value['id']] = note['id']
            created.append((note['owner'], value['id']))
        deadline = time.monotonic() + wait_seconds
        while True:
            states = [api.call('GET', '/api/v1/knowledge/notes/' + key, token=tokens[owner])['indexStatus']
                      for owner, key in created]
            if any(s == 'FAILED' for s in states):
                raise ValueError('Indexing failed; no live quality result produced')
            if all(s == 'READY' for s in states):
                break
            if time.monotonic() >= deadline:
                raise TimeoutError('Indexing timed out; no live quality result produced')
            time.sleep(1)
        for q in data['questions']:
            try:
                result = api.call('GET', '/api/v1/knowledge/notes/search?' + urlencode({'q': q['query'], 'limit': 3}), token=tokens[q['owner']])
                if result.get('vectorAvailable') is not True:
                    raise ValueError('Local bge-m3 retrieval unavailable')
                rows.append({'id': q['id'], 'ranked': [mapping[h['id']] for h in result['items']],
                             'hits': result['items'], 'vectorAvailable': True})
            except Exception as error:
                # Do not print transport exceptions: they may include credentials.
                rows.append({'id': q['id'], 'ranked': [], 'error': type(error).__name__})
        metrics = summarize(data, rows)
        return {'kind': 'SYNTHETIC_LIVE_RETRIEVAL', 'model': model, 'rows': rows, 'metrics': metrics,
                'status': 'INVALID' if metrics['failedQuestions'] or metrics['ownerViolations'] else 'MEASURED',
                'scope': 'private-note retrieval only; no answer or citation-generation quality claim'}
    finally:
        for owner, key in created:
            api.call('DELETE', '/api/v1/knowledge/notes/' + key, token=tokens[owner])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixtures', type=Path, default=Path(__file__).with_name('fixtures.json'))
    parser.add_argument('--api-base', default=os.environ.get('EVAL_API_BASE', 'http://127.0.0.1:8081'))
    parser.add_argument('--embedding-base', default=os.environ.get('EMBEDDING_BASE_URL', 'http://127.0.0.1:11434'))
    parser.add_argument('--model', default='bge-m3')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    raw = args.fixtures.read_bytes()
    report = run(json.loads(raw), Client(args.api_base), Client(args.embedding_base, os.environ.get('EMBEDDING_API_KEY')), args.model)
    report.update(fixtureSha256=hashlib.sha256(raw).hexdigest(), measuredAt=time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
                  apiBase=local_url(args.api_base), embeddingBase=local_url(args.embedding_base))
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    return 0 if report['status'] == 'MEASURED' else 1


if __name__ == '__main__':
    raise SystemExit(main())
