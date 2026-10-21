import unittest
from check_compose import ROOT, validate


class ComposeContractTest(unittest.TestCase):
    def setUp(self):
        self.compose = (ROOT / 'deploy/docker-compose.yml').read_text()
        self.env = (ROOT / '.env.example').read_text()
        self.readme = (ROOT / 'README.md').read_text()

    def test_repository_configuration(self):
        self.assertEqual([], validate(self.compose, self.env, self.readme))

    def test_dependency_port_embedding_and_documentation_drift_fail(self):
        for old, new in [('127.0.0.1:', '0.0.0.0:'), ('condition: service_healthy', 'condition: service_started'),
                         ('ollama pull bge-m3', 'ollama list'), ('profiles: ["full"]', 'profiles: ["unused"]')]:
            with self.subTest(old=old):
                self.assertTrue(validate(self.compose.replace(old, new), self.env, self.readme))
        self.assertTrue(validate(self.compose, self.env.replace('LLM_MODEL_EMBEDDING=bge-m3', 'LLM_MODEL_EMBEDDING=other'), self.readme))
        self.assertTrue(validate(self.compose, self.env, self.readme.replace('--profile full up -d --build', 'up -d')))
