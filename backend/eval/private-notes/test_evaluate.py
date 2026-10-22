"""Offline logic/protocol checks only. These numbers are NOT retrieval measurements."""
import copy
import json
from pathlib import Path
import unittest
from unittest.mock import patch
from evaluate import Client, local_url, probe_embedding, run, summarize, validate


DATA = json.loads(Path(__file__).with_name('fixtures.json').read_text())


class EvaluationTest(unittest.TestCase):
    def test_metrics_denominators_mrr_recall_failures_and_no_answer(self):
        data = copy.deepcopy(DATA)
        data['questions'] = [
            {'id': 'multi', 'owner': 'A', 'relevant': ['redis', 'sql']},
            {'id': 'failed', 'owner': 'A', 'relevant': ['board']},
            {'id': 'empty', 'owner': 'A', 'relevant': []},
        ]
        result = summarize(data, [
            {'id': 'multi', 'ranked': ['star', 'redis', 'thread']},
            {'id': 'failed', 'ranked': [], 'error': 'TimeoutError'},
            {'id': 'empty', 'ranked': []},
        ])
        self.assertEqual(result['hitAt3'], .5)
        self.assertEqual(result['recallAt3'], .25)
        self.assertEqual(result['mrrAt3'], .25)
        self.assertEqual(result['negativeEmptyAt3'], 1)
        self.assertEqual(result['failedQuestions'], 1)
        with self.assertRaises(ValueError):
            summarize(data, [])

    def test_owner_leak_and_bad_fixture_cannot_be_hidden(self):
        rows = [{'id': q['id'], 'ranked': []} for q in DATA['questions']]
        rows[0]['ranked'] = ['foreign']
        self.assertEqual(summarize(DATA, rows)['ownerViolations'], 1)
        rows[0]['ranked'] = ['redis', 'redis']
        with self.assertRaises(ValueError):
            summarize(DATA, rows)
        bad = copy.deepcopy(DATA)
        bad['questions'][0]['relevant'] = ['foreign']
        with self.assertRaises(ValueError):
            validate(bad)
        for value in ('https://example.com', 'http://example.com', 'http://secret@localhost'):
            with self.assertRaises(ValueError):
                local_url(value)

    def test_http_embedding_protocol_without_socket(self):
        from unittest.mock import MagicMock
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b'{"data":[{"embedding":[1]}]}'
        with patch('evaluate.urlopen', return_value=response) as transport:
            with self.assertRaises(ValueError):
                probe_embedding(Client('http://localhost:11434'), 'bge-m3')
            request = transport.call_args.args[0]
            self.assertEqual(request.full_url, 'http://localhost:11434/v1/embeddings')
            self.assertEqual(json.loads(request.data)['model'], 'bge-m3')
            self.assertEqual(request.get_method(), 'POST')

    def test_live_runner_uses_real_result_order_and_cleans_fixture_notes(self):
        class FakeApi:
            def __init__(self):
                self.seq, self.deleted, self.saved = 0, [], {}
            def call(self, method, path, body=None, token=None):
                if path.endswith('/guest'):
                    self.seq += 1
                    return {'accessToken': str(self.seq)}
                if method == 'POST':
                    key = 'db-' + str(len(self.saved))
                    self.saved[key] = body
                    return {'id': key}
                if method == 'DELETE':
                    self.deleted.append(path)
                    return None
                if '/search?' in path:
                    return {'vectorAvailable': True, 'items': []}
                return {'indexStatus': 'READY'}
        class FakeEmbedding:
            def call(self, *args, **kwargs):
                return {'data': [{'embedding': [1] + [0] * 1023}]}
        api = FakeApi()
        result = run(DATA, api, FakeEmbedding(), 'bge-m3')
        self.assertEqual(result['metrics']['hitAt3'], 0)  # Never substitute relevance labels
        self.assertEqual(len(result['rows']), len(DATA['questions']))
        self.assertEqual(len(api.deleted), len(DATA['notes']))
        with self.assertRaises(ValueError):
            probe_embedding(FakeEmbedding(), 'unapproved-model')


if __name__ == '__main__':
    unittest.main()
