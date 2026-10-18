"""Release-specific semantic privilege/event/ref/secret boundaries and adversarial controls."""
import copy
from pathlib import Path
import unittest

import yaml
from repository_policy import ROOT, load_yaml, workflow_policy


class GuardedReleaseWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.text = (ROOT / '.github/workflows/release.yml').read_text()
        self.workflow = load_yaml(self.text)

    def check(self, workflow):
        workflow_policy(yaml.safe_dump(workflow, sort_keys=False), 'release.yml')

    def test_manual_default_off_separate_channels_and_minimum_job_permissions(self):
        workflow_policy(self.text, 'release.yml')
        self.assertEqual(set(self.workflow['on']), {'workflow_dispatch'})
        self.assertEqual(self.workflow['on']['workflow_dispatch']['inputs']['operation']['options'], ['prepare', 'publish'])
        activation = self.workflow['env']['CHANNEL_ACTIVATION']
        self.assertIn('vars.RELEASE_RC_ACTIVATION', activation)
        self.assertIn('vars.RELEASE_STABLE_ACTIVATION', activation)
        self.assertIn("inputs.channel == 'stable'", activation)
        self.assertNotIn('secrets.', str(self.workflow['jobs']['publish']))
        for name in ('admit', 'unsigned', 'sign'):
            self.assertEqual(self.workflow['jobs'][name]['permissions'], {'contents': 'read', 'actions': 'read'})

    def test_different_filename_never_inherits_writer_exception(self):
        with self.assertRaises(ValueError):
            workflow_policy(self.text, 'lookalike.yml')

    def test_event_ref_dependency_permissions_and_environment_mutations_deny(self):
        # Keep Action source lines intact: yaml.safe_dump would erase mandatory release comments.
        for old, new in [("github.event_name == 'workflow_dispatch'", 'true'),
                         ("github.ref == 'refs/heads/main'", 'true'),
                         ("needs.admit.outputs.allow == 'true'", 'true'),
                         ("needs.remote-state.outputs.allow == 'true'", 'true'),
                         ('    needs: [admit, remote-state, unsigned]', '    needs: [unsigned]'),
                         ('    environment: ${{ needs.admit.outputs.signing_environment }}', '    environment: production'),
                         ('      contents: read\n      actions: read', '      contents: write\n      actions: read'),
                         ('  workflow_dispatch:\n', '  push:\n  workflow_dispatch:\n'),
                         ('  cancel-in-progress: false', '  cancel-in-progress: true'),
                         ('          ref: ${{ github.workflow_sha }}', '          ref: ${{ inputs.source_sha }}')]:
            self.assertIn(old, self.text)
            with self.subTest(old=old), self.assertRaises(ValueError):
                workflow_policy(self.text.replace(old, new, 1), 'release.yml')

    def test_secret_scope_and_alternate_secret_expressions_are_not_blanket_exempted(self):
        for old, new in [('  REQUEST_OPERATION: ${{ inputs.operation }}', '  REQUEST_OPERATION: ${{ secrets.TOKEN }}'),
                         ('          GH_TOKEN: ${{ github.token }}', '          GH_TOKEN: ${{ SeCrEtS.TOKEN }}'),
                         ('          ANDROID_RELEASE_KEY_ALIAS: ${{ secrets.ANDROID_RELEASE_KEY_ALIAS }}',
                          '          ANDROID_RELEASE_KEY_ALIAS: ${{ secrets.OTHER_ALIAS }}'),
                         ('    timeout-minutes: 45', '    timeout-minutes: 45\n    env:\n      LEAK: ${{ secrets.TOKEN }}')]:
            with self.subTest(old=old), self.assertRaises(ValueError):
                workflow_policy(self.text.replace(old, new, 1), 'release.yml')


if __name__ == '__main__':
    unittest.main()
