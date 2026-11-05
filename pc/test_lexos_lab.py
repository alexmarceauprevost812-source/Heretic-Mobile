import json
from pathlib import Path
import sys
import tempfile
import unittest
from lexos_lab import Lab


class LabTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / 'personal.gguf'
        self.source.write_bytes(b'GGUF-source')
        self.lab = Lab({'models': {'test': str(self.source)}, 'recipes': {}}, self.root / 'jobs')

    def tearDown(self):
        self.lab.pool.shutdown(wait=True)
        self.temp.cleanup()

    def test_rejects_uninstalled_operations_and_unknown_models(self):
        with self.assertRaises(ValueError):
            self.lab.submit('lora', 'test')
        self.lab.config['recipes']['quantize'] = ['tool', '{input}', '{output}']
        with self.assertRaises(ValueError):
            self.lab.submit('quantize', '../../secret')
        with self.assertRaises(ValueError):
            self.lab.status('../../secret')
        self.assertEqual(['quantize'], self.lab.capabilities()['operations'])

    def test_job_progress_artifact_and_source_preservation(self):
        # Simulates an installed tool, deliberately modifies its input working copy.
        self.lab.config['recipes']['quantize'] = [sys.executable, '-c',
            'import pathlib,sys; pathlib.Path(sys.argv[1]).write_bytes(b"modified"); '
            'pathlib.Path(sys.argv[2]).write_bytes(b"GGUF-result"); print(\'{"progress":50,"message":"half"}\')',
            '{input}', '{output}']
        job = self.lab.submit('quantize', 'test')
        self.lab.pool.shutdown(wait=True)
        final = self.lab.status(job['id'])
        self.assertEqual('completed', final['state'])
        self.assertEqual(100, final['progress'])
        self.assertEqual(64, len(final['sha256']))
        self.assertEqual(b'GGUF-source', self.source.read_bytes())
        self.assertFalse((self.lab.root / job['id'] / 'input.gguf').exists())

    def test_failure_and_restart_are_visible(self):
        self.lab.config['recipes']['train'] = [sys.executable, '-c', 'import sys; sys.exit(1)', '{input}', '{output}']
        job = self.lab.submit('train', 'test')
        self.lab.pool.shutdown(wait=True)
        self.assertEqual('failed', self.lab.status(job['id'])['state'])
        directory = self.lab.root / ('a' * 32)
        directory.mkdir()
        (directory / 'status.json').write_text(json.dumps({'id': 'a' * 32, 'state': 'running'}))
        restarted = Lab(self.lab.config, self.lab.root)
        self.assertEqual('interrupted', restarted.status('a' * 32)['state'])
        restarted.pool.shutdown()


if __name__ == '__main__':
    unittest.main()
