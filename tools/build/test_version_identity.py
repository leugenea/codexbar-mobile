"""Pure synthetic version/Git-response tests; never execute Git, Java, SDK or Gradle."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import version_identity as version

SHA = 'a' * 40
TREE = 'b' * 40


class VersionTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / 'VERSION').write_text('0.1.0\n')

    def git(self, root, *args):
        responses = {('rev-parse', '--is-shallow-repository'): 'false', ('rev-parse', 'HEAD'): SHA,
                     ('rev-parse', 'HEAD^{tree}'): TREE, ('tag', '--list', 'v[0-9]*'): '',
                     ('tag', '--merged', 'HEAD', '--list', 'v[0-9]*'): '',
                     ('rev-list', '--count', 'HEAD'): '27', ('status', '--porcelain'): ''}
        if args[0] == 'rev-parse' and args[1].startswith('refs/tags/'):
            return SHA
        return responses[args]

    def official(self, channel='rc', tag='v0.1.0-rc.2', code='6', previous='5'):
        return {'RELEASE_CHANNEL': channel, 'RELEASE_TAG': tag, 'RELEASE_VERSION_CODE': code,
                'RELEASE_PREVIOUS_VERSION_CODE': previous}

    def test_default_development_and_exact_official_modes(self):
        with patch.object(version, 'git', side_effect=self.git):
            dev = version.resolve(self.root, {})
            self.assertEqual(dev['versionName'], '0.1.0-dev.27+g' + SHA[:12])
            self.assertEqual((dev['versionCode'], dev['tag']), (1, None))
            self.assertEqual(dev['debugPackage'], version.PACKAGE + '.dev')
            self.assertEqual(dev['releasePackage'], version.PACKAGE)
            self.assertEqual(version.resolve(self.root, self.official())['versionName'], '0.1.0-rc.2')
            stable = version.resolve(self.root, self.official('stable', 'v0.1.0'))
            self.assertEqual((stable['versionName'], stable['versionCode']), ('0.1.0', 6))

    def test_version_tag_channel_and_counter_rejections(self):
        for text in ('', 'v0.1.0', '01.1.0', '0.1', '0.1.0-rc.1', '0.1.0+meta', '0.1.0\n\n', ' 0.1.0', '0.1.0\r\n'):
            with self.subTest(target=text), self.assertRaises(ValueError):
                version.target_version(text)
        for tag in ('0.1.0', 'v00.1.0', 'v0.1.0-rc.0', 'v0.1.0-rc.01', 'v0.1.0-dev.1', 'v0.1.0+foo'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                version.parse_tag(tag)
        for channel, tag in (('rc', 'v0.1.0'), ('stable', 'v0.1.0-rc.1'), ('rc', 'v0.2.0-rc.1')):
            with self.subTest(channel=channel, tag=tag), self.assertRaises(ValueError):
                version.official_inputs('0.1.0', channel, self.official(channel, tag))
        for code in ('', '0', '-1', '+1', '01', '1.0', ' 2', '2100000001', '9999999999999'):
            with self.subTest(code=code), self.assertRaises(ValueError):
                version.official_inputs('0.1.0', 'rc', self.official(code=code))
        for previous in ('6', '7', '-1', '01', ''):
            with self.subTest(previous=previous), self.assertRaises(ValueError):
                version.official_inputs('0.1.0', 'rc', self.official(previous=previous))
        self.assertEqual(version.official_inputs('0.1.0', 'rc', self.official(code='2100000000', previous='0'))[1], 2100000000)

    def test_missing_counter_and_conflicting_development_inputs_fail(self):
        with patch.object(version, 'git', side_effect=self.git):
            for key in ('RELEASE_VERSION_CODE', 'RELEASE_PREVIOUS_VERSION_CODE'):
                env = self.official()
                del env[key]
                with self.subTest(key=key), self.assertRaises(ValueError):
                    version.resolve(self.root, env)
            for env in ({'RELEASE_CHANNEL': 'preview'}, {'RELEASE_TAG': ''}, {'RELEASE_VERSION_CODE': '1'},
                        {'RELEASE_PREVIOUS_VERSION_CODE': '0'}):
                with self.subTest(env=env), self.assertRaises(ValueError):
                    version.resolve(self.root, env)

    def test_shallow_wrong_source_and_dirty_official_checkouts_fail(self):
        for command, response, env, expected in (
            (('rev-parse', '--is-shallow-repository'), 'true', {}, None),
            (('rev-parse', 'HEAD'), 'invalid', {}, None),
            (('status', '--porcelain'), ' M VERSION', self.official(), None),
            (('rev-parse', 'refs/tags/v0.1.0-rc.2^{commit}'), 'c' * 40, self.official(), None),
            (('rev-parse', 'HEAD'), SHA, {}, 'd' * 40),
        ):
            def git(root, *args):
                return response if args == command else self.git(root, *args)
            with self.subTest(command=command), patch.object(version, 'git', side_effect=git), self.assertRaises(ValueError):
                version.resolve(self.root, env, expected)

    def test_reachable_stable_distance_and_tagged_dev(self):
        responses = {('tag', '--list', 'v[0-9]*'): 'v0.0.8\nv0.0.9\nv0.1.0-rc.1\nv9.0.0',
                     ('tag', '--merged', 'HEAD', '--list', 'v[0-9]*'): 'v0.0.8\nv0.0.9\nv0.1.0-rc.1',
                     ('merge-base', 'HEAD', 'refs/tags/v0.0.8'): '8' * 40,
                     ('rev-parse', 'v0.0.8^{commit}'): '8' * 40,
                     ('rev-list', '--count', 'v0.0.8..HEAD'): '10',
                     ('merge-base', 'HEAD', 'refs/tags/v0.0.9'): '9' * 40,
                     ('rev-parse', 'v0.0.9^{commit}'): '9' * 40,
                     ('rev-list', '--count', 'v0.0.9..HEAD'): '3',
                     ('merge-base', 'HEAD', 'refs/tags/v9.0.0'): 'e' * 40,
                     ('rev-parse', 'v9.0.0^{commit}'): 'f' * 40}
        def git(root, *args):
            return responses[args] if args in responses else self.git(root, *args)
        with patch.object(version, 'git', side_effect=git):
            self.assertIn('-dev.3+', version.resolve(self.root, {})['versionName'])
        responses.update({('tag', '--list', 'v[0-9]*'): 'v0.1.0',
                          ('tag', '--merged', 'HEAD', '--list', 'v[0-9]*'): 'v0.1.0',
                          ('merge-base', 'HEAD', 'refs/tags/v0.1.0'): SHA,
                          ('rev-parse', 'v0.1.0^{commit}'): SHA,
                          ('rev-list', '--count', 'v0.1.0..HEAD'): '0'})
        with patch.object(version, 'git', side_effect=git):
            self.assertIn('-dev.0+', version.resolve(self.root, {})['versionName'])
            (self.root / 'VERSION').write_text('0.0.9\n')
            with self.assertRaisesRegex(ValueError, 'precedes'):
                version.resolve(self.root, {})
        responses[('tag', '--list', 'v[0-9]*')] = 'v0.1.0-rc.0'
        with patch.object(version, 'git', side_effect=git), self.assertRaisesRegex(ValueError, 'Malformed'):
            version.resolve(self.root, {})

    def test_git_wrapper_is_read_only_and_errors_do_not_fabricate_identity(self):
        with patch.object(version.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, SHA + '\n', '')) as run:
            self.assertEqual(version.git(self.root, 'rev-parse', 'HEAD'), SHA)
            self.assertEqual(run.call_args.args[0], ['git', '-C', str(self.root), 'rev-parse', 'HEAD'])
            self.assertTrue(run.call_args.kwargs['check'])
            self.assertEqual(run.call_args.kwargs['timeout'], 30)
        with patch.object(version, 'git', side_effect=subprocess.CalledProcessError(1, ['git'])):
            with self.assertRaises(subprocess.CalledProcessError):
                version.resolve(self.root, {})


if __name__ == '__main__':
    unittest.main()
