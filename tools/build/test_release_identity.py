"""Finite pure offline identity/counter/ancestry tests; actual Git fixtures are hosted."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from release_identity import (admit_counter, identity_digest, positive_code, read_target,
                              resolve_identity, stable_anchor, validate_identity)


def fixture_identity(channel='dev'):
    identity = dict(schema=1, targetVersion='0.1.0', channel=channel, versionName='0.1.0-dev.7+g' + 'a' * 12,
                    versionCode=41, sourceSha='a' * 40, sourceTree='b' * 40, shortSha='a' * 12,
                    stableAncestor=None, devDistance=7, tag=None, tagObjectSha=None,
                    purpose='development-packaging-fixture', counterAuthority='development', officialAdmission=None)
    if channel != 'dev':
        tag = 'v0.1.0-rc.1' if channel == 'rc' else 'v0.1.0'
        identity.update(versionName=tag[1:], tag=tag, tagObjectSha='c' * 40, devDistance=None,
                        purpose='release-identity-fixture', counterAuthority='fixture')
    return identity


class IdentityContracts(unittest.TestCase):
    def test_target_is_one_canonical_line(self):
        with tempfile.TemporaryDirectory(dir='/opt/data/tmp') as scratch:
            path = Path(scratch)/'VERSION'
            path.write_text('0.1.0\n')
            self.assertEqual(read_target(path), '0.1.0')
            for malformed in ('', '0.1.0', '0.1.0\n\n', '01.1.0\n', '0.1.0-rc.1\n', ' 0.1.0\n', '0.1.0+gabc\n'):
                path.write_text(malformed)
                with self.subTest(value=malformed), self.assertRaises(ValueError):
                    read_target(path)

    def test_positive_counter_is_bounded_canonical_and_not_bool(self):
        self.assertEqual(positive_code('41'), 41)
        self.assertEqual(positive_code(2147483647), 2147483647)
        for value in (True, 0, -1, 2147483648, '01', '1.0', '1\n', 'run_number', None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                positive_code(value)

    def test_exact_identity_channel_source_and_authority(self):
        for channel in ('dev', 'rc', 'stable'):
            identity = fixture_identity(channel)
            self.assertEqual(validate_identity(identity), identity)
            for key, wrong in (('channel', 'beta'), ('versionName', '0.9.0'), ('sourceSha', 'd' * 40),
                               ('purpose', 'official-input'), ('officialAdmission', {'approved': True}),
                               ('versionCode', True), ('targetVersion', '01.1.0')):
                with self.subTest(channel=channel, key=key), self.assertRaises(ValueError):
                    validate_identity(identity | {key: wrong})
            self.assertNotEqual(identity_digest(identity), identity_digest(identity | {'versionCode': 42}))
        for tag in ('v0.1.0-rc.0', 'v0.1.0-rc.01', 'v0.1.0-beta.1', 'v0.1.0-rc.1+gabc', 'v0.2.0-rc.1'):
            with self.assertRaises(ValueError):
                validate_identity(fixture_identity('rc') | {'tag': tag, 'versionName': tag[1:]})

    def test_new_codes_increase_and_reruns_preserve_every_identity_field(self):
        rc, stable = fixture_identity('rc'), fixture_identity('stable') | {'versionCode': 42}
        admit_counter(rc, {'fixturePreviousCode': 40})
        admit_counter(stable, rc)
        admit_counter(rc, copy.deepcopy(rc), True)
        for previous in (None, {'fixturePreviousCode': 41}, {'fixturePreviousCode': 42}, {'fixturePreviousCode': True}):
            with self.assertRaises(ValueError):
                admit_counter(rc, previous)
        for key, value in (('versionCode', 42), ('tagObjectSha', 'd' * 40), ('sourceTree', 'e' * 40)):
            with self.assertRaises(ValueError):
                admit_counter(rc | {key: value}, rc, True)

    def test_stable_selection_is_reachable_ancestry_not_tag_clock_or_rc(self):
        class FakeGit:
            def text(self, *args):
                return {'head': 'head\nnew\nold\nroot', 'new': 'new\nold\nroot', 'old': 'old\nroot', 'other': 'other\nroot'}[args[-1]]
        tags = {'refs/tags/v0.1.0': 'old', 'refs/tags/v0.2.0': 'new', 'refs/tags/v0.3.0-rc.1': 'head'}
        self.assertEqual(stable_anchor(FakeGit(), tags, 'head'), 'new')
        self.assertIsNone(stable_anchor(FakeGit(), {}, 'head'))
        with self.assertRaises(ValueError):
            stable_anchor(FakeGit(), tags | {'refs/tags/v0.2.1': 'new'}, 'head')
        class MergeGit(FakeGit):
            def text(self, *args):
                return 'head\nnew\nold\nother\nroot' if args[-1] == 'head' else super().text(*args)
        with self.assertRaises(ValueError):
            stable_anchor(MergeGit(), tags | {'refs/tags/v0.4.0': 'other'}, 'head')

    def test_resolver_never_fetches_or_invents_missing_acquisition(self):
        with patch('release_identity.read_snapshot', side_effect=ValueError('No acquisition')) as snapshot:
            with self.assertRaises(ValueError):
                resolve_identity('/opt/data/tmp/synthetic')
            snapshot.assert_called_once()
