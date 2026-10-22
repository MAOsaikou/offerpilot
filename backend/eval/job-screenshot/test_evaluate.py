"""Deterministic scorer and HTTP protocol smoke checks; no provider calls."""
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import evaluate as ev


class EvaluationTests(unittest.TestCase):
    def test_normalization_and_line_precision_recall_penalize_hallucination(self):
        self.assertEqual(ev.norm(' Ｊａｖａ 工程师 '), ev.norm('java工程师'))
        self.assertEqual(ev.cities('北京,上海'), ev.cities('上海、北京'))
        values = ev.line_score('1. 熟悉 Java\n2. 了解 SQL', '• 熟悉 Java\n编造经历')
        self.assertEqual(values, {'precision': .5, 'recall': .5, 'f1': .5})
        self.assertEqual(ev.line_score('会 SQL', '')['recall'], 0)
        self.assertEqual(ev.score({'company': ''}, {'company': '猜测公司'})['company'], 0)

    def test_real_client_uploads_ordered_ids_polls_and_reads_draft(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / 'page.png').write_bytes(b'image-stub')
            responses = [ {'fileId': 'file-1'}, {'id': 'gen-1', 'status': 'RUNNING'},
                         {'id': 'gen-1', 'status': 'SUCCEEDED'}, {'draft': {'title': 'seed title'}} ]
            requests = []
            def respond(request, **_kwargs):
                requests.append(request)
                return io.BytesIO(json.dumps({'code': 0, 'data': responses.pop(0)}).encode())
            with patch('urllib.request.urlopen', side_effect=respond), patch('time.sleep'):
                actual = ev.Backend('http://localhost/api/v1', 'stub-credential', 5).parse(Path(directory), {'images': ['page.png']})
            self.assertEqual(actual['title'], 'seed title')
            self.assertIn(b'filename="page.png"', requests[0].data)
            self.assertEqual(json.loads(requests[1].data)['input'], {'fileIds': ['file-1']})
            self.assertTrue(requests[-1].full_url.endswith('/gen-1/result'))

    def test_failures_are_zero_and_stub_cannot_pass_live_gate(self):
        class Failed:
            def parse(self, *_):
                raise RuntimeError('stub failure')
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            (directory / 'manifest.json').write_text(json.dumps({'sampleKind': 'seed-rendered-not-user-screenshots', 'sourceSha256': 'fixture',
                'samples': [{'id': 'seed', 'layout': 'web-detail', 'expected': {}, 'images': ['x.png']}]}))
            report = ev.evaluate(directory, directory / 'result.json', Failed(), True)
            self.assertEqual(report['macroAccuracy'], 0)
            self.assertIsNone(report['passesLiveGate'])


if __name__ == '__main__':
    unittest.main()
