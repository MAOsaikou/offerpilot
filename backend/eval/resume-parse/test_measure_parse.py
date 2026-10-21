import pathlib
import unittest
from unittest.mock import patch
import measure_parse as m


class MeasurementTest(unittest.TestCase):
    def test_nearest_rank_and_failures_are_not_dropped(self):
        self.assertEqual(18, m.pct(list(range(20)), 95))
        result = m.summary([{'kind': 'pdf', 'status': 'READY', 'seconds': 5},
                            {'kind': 'pdf', 'status': 'TIMEOUT', 'seconds': 120}])[0]
        self.assertEqual(2, result['n'])
        self.assertEqual(1, result['failed'])
        self.assertFalse(result['passesTimingGate'])
        self.assertIsNone(m.summary([{'kind': 'docx', 'status': 'READY', 'seconds': 12}])[0]['passesTimingGate'])

    def test_progress_is_observed_and_terminal_and_errors_remain_in_denominator(self):
        with patch.object(m, 'upload', return_value='r1'), patch.object(m, 'call', side_effect=[
            {'data': {'parseStatus': 'QUEUED', 'parseProgress': 0}},
            {'data': {'parseStatus': 'PARSING', 'parseProgress': 50}},
            {'data': {'parseStatus': 'READY', 'parseProgress': 100}},
        ]), patch.object(m.time, 'sleep'):
            result = m.one('http://fixture', 'private-token', pathlib.Path('fictional.png'), 5)
            self.assertEqual('READY', result['status'])
            self.assertEqual([0, 50, 100], [state['progress'] for state in result['observedProgress']])
        with patch.object(m, 'upload', side_effect=RuntimeError('private-token and provider body')):
            result = m.one('http://fixture', 'private-token', pathlib.Path('fictional.jpg'), 5)
            self.assertEqual('ERROR', result['status'])
            self.assertNotIn('private-token', str(result))
            self.assertNotIn('provider body', str(result))
