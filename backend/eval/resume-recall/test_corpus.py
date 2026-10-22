"""Deterministic corpus checks, no model calls; generates artifacts only under /tmp."""
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


class CorpusTest(unittest.TestCase):
    def test_generated_sources_variants_hashes_and_reproduction(self):
        script = pathlib.Path(__file__).with_name('generate.py')
        manifests = []
        for _ in range(2):
            out = pathlib.Path(tempfile.mkdtemp(prefix='offerpilot-recall-test-'))
            subprocess.run([sys.executable, str(script), str(out), '--pdf-engine', 'reportlab', '--image-variants'],
                           check=True, capture_output=True)
            truth = json.loads((out / 'truth.json').read_text())
            manifest = json.loads((out / 'manifest.json').read_text())
            self.assertEqual('generated_synthetic', manifest['provenance'])
            self.assertEqual(24, len({row.get('sourceId', row['id']) for row in truth}))
            self.assertEqual(36, len(truth))
            self.assertEqual(6, len({row['layout'] for row in truth if 'sourceId' not in row}))
            self.assertEqual({'.pdf', '.docx', '.png', '.jpg', '.txt'}, {pathlib.Path(row['file']).suffix for row in truth})
            self.assertEqual(len(truth), len({row['id'] for row in truth}))
            for row in manifest['samples']:
                self.assertEqual(row['sha256'], hashlib.sha256((out / row['file']).read_bytes()).hexdigest())
            manifests.append(manifest)
        self.assertEqual(manifests[0], manifests[1])
