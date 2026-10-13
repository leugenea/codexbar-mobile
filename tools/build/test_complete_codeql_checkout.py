"""Pure offline acquisition security/argv/state controls; no Git or network probes."""
import json
import os
import tempfile
import copy
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

from complete_codeql_checkout import (Git, PUBLIC_URL, config_entries, release_refs,
                                     safe_environment, validate_config, validate_snapshot, prepare,
                                     host_scratch, job_context, require_admission, canonical, digest, _PrivateTransport, TRANSPORT_OVERRIDES,
                                     publish_observation, read_observation, snapshot_path, AcquisitionError, STAGES, CATEGORIES)


SCRATCH = host_scratch({'RUNNER_TEMP': os.environ.get('RUNNER_TEMP') if os.environ.get('GITHUB_ACTIONS') == 'true' else os.environ.get('BUILD_CONTRACT_SCRATCH')})


def private_args(argv):
    index = 3
    if argv[index].startswith('--git-dir='):
        index += 1
    while argv[index] == '-c':
        index += 2
    return tuple(argv[index:])


def private_baseline(argv, **options):
    """PURE filesystem/native-output transcript; never launches Git."""
    args = private_args(argv)
    directory = Path(options['cwd'])
    text = None
    if args == ('--version',):
        text = 'git version 2.55.0'
    elif args[0] == 'init':
        for relative in ('objects/pack', 'objects/info', 'refs/tags', 'refs/heads'):
            (directory/relative).mkdir(parents=True, exist_ok=True)
        (directory/'HEAD').write_text('ref: refs/heads/acquisition\n')
        (directory/'config').write_text('SYNTHETIC minimal bare config bytes')
        text = ''
    elif args == ('config', '--local', '--no-includes', '--null', '--list'):
        text = 'core.repositoryformatversion\n0\0core.filemode\ntrue\0core.bare\ntrue\0'
    elif args == ('rev-parse', '--show-object-format'):
        text = 'sha1'
    elif args == ('rev-parse', '--show-ref-format'):
        text = 'files'
    elif args == ('cat-file', '--batch-check', '--batch-all-objects'):
        text = ''
    return None if text is None else subprocess.CompletedProcess(argv, 0, text.encode(), b'')


class AcquisitionContracts(unittest.TestCase):
    def test_environment_discards_credentials_proxy_tracing_config_and_lazy_overrides(self):
        original = dict(PATH='/usr/bin', GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='http.extraheader',
                        GIT_CONFIG_VALUE_0='synthetic-secret', GH_TOKEN='synthetic-secret',
                        HTTPS_PROXY='https://wrong.invalid', GIT_TRACE='1', GIT_ASKPASS='/unsafe',
                        GIT_OBJECT_DIRECTORY='/unsafe', GIT_ALTERNATE_OBJECT_DIRECTORIES='/unsafe')
        environment = safe_environment(original)
        for key in original.keys() - {'PATH', 'GIT_ASKPASS'}:
            self.assertNotIn(key, environment)
        self.assertEqual(environment['GIT_ASKPASS'], '/bin/false')
        self.assertEqual(environment['GIT_OPTIONAL_LOCKS'], '0')
        self.assertEqual(environment['GIT_NO_LAZY_FETCH'], '1')
        self.assertEqual(environment['GIT_CONFIG_GLOBAL'], '/dev/null')
        self.assertEqual(environment['GIT_ALLOW_PROTOCOL'], 'https')

    def test_public_transport_argv_resets_headers_helpers_and_side_effects_without_values(self):
        calls = []
        def fake(argv, **kwargs):
            calls.append((argv, kwargs))
            return subprocess.CompletedProcess(argv, 0, b'', b'')
        git = Git(SCRATCH/'synthetic-root', {'PATH': '/usr/bin', 'RUNNER_TEMP': str(SCRATCH)},
                  lambda argv, **kw: private_baseline(argv, **kw) or fake(argv, **kw))
        git.headers = ['http.https://github.com/.extraheader']
        with self.assertRaises(AcquisitionError):
            git.call('fetch', PUBLIC_URL, 'a' * 40)
        transport = _PrivateTransport(git)
        try:
            transport.fetch('a' * 40, {})
        finally:
            transport.close()
        argv, options = calls[0]
        self.assertEqual([argv[n+1] for n, item in enumerate(argv) if item == '-c'],
                         list(TRANSPORT_OVERRIDES) + ['fetch.fsckObjects=true', 'fetch.unpackLimit=0'])
        for guard in ('credential.helper=', 'http.extraHeader=', 'http.followRedirects=false',
                      'http.proxy=', 'http.cookieFile=', 'core.hooksPath=/dev/null',
                      'core.fsmonitor=false', 'protocol.allow=never'):
            self.assertIn(guard, argv)
        for forbidden in ('http.sslCert=', 'http.sslKey=', 'http.https://github.com/.extraheader='):
            self.assertNotIn(forbidden, argv)
        self.assertEqual(argv[-2:], [PUBLIC_URL, 'a' * 40 + ':refs/acquisition/source'])
        self.assertFalse(options['check'])
        self.assertLessEqual(options['timeout'], 60)
        self.assertNotIn('origin', argv)

    def test_failure_never_exposes_git_diagnostics_or_retries(self):
        calls = []
        def refused(argv, **kwargs):
            baseline = private_baseline(argv, **kwargs)
            if baseline is not None:
                return baseline
            calls.append(argv)
            return subprocess.CompletedProcess(argv, 1, b'Authorization: synthetic-secret', b'synthetic-secret')
        git = Git(SCRATCH/'synthetic', {'RUNNER_TEMP': str(SCRATCH)}, refused)
        transport = _PrivateTransport(git)
        try:
            with self.assertRaises(AcquisitionError) as error:
                transport.fetch('a' * 40, {})
            self.assertEqual(error.exception.category, 'TRANSPORT_REFUSED')
            self.assertNotIn('synthetic-secret', error.exception.receipt())
            self.assertEqual(len(calls), 1)
            def timeout(argv, **kwargs):
                calls.append(argv)
                raise subprocess.TimeoutExpired(argv, 60, stderr='synthetic-secret')
            git.run = timeout
            with self.assertRaises(AcquisitionError) as error:
                transport.fetch('a' * 40, {})
            self.assertEqual(error.exception.category, 'TRANSPORT_TIMED_OUT')
            self.assertEqual(len(calls), 2, 'Timeout reaches the injected subprocess')
        finally:
            transport.close()

    def test_config_parsing_resets_managed_headers_but_rejects_unsafe_scopes(self):
        raw = b'core.filemode\ntrue\0http.https://github.com/.extraheader\nAuthorization: synthetic\0credential.helper\nunsafe-helper\0'
        headers, includes = validate_config(config_entries(raw), str(SCRATCH))
        self.assertEqual(headers, ['http.https://github.com/.extraheader'])
        self.assertEqual(includes, [])
        for key in ('url.https://wrong.invalid.insteadof', 'http.proxy', 'http.sslcert',
                    'include.path', 'remote.origin.promisor', 'extensions.partialclone',
                    'credential.https://github.com.helper', 'credential.username'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_config([(key, 'synthetic')], str(SCRATCH))
        for key in ('http.https://userinfo:synthetic@github.com/.extraheader', 'http.https://wrong.invalid/.extraheader'):
            with self.assertRaises(ValueError):
                validate_config([(key, 'synthetic')], str(SCRATCH))

    def test_authorization_include_is_only_nonrecursive_runner_temp_checkout_data(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as scratch:
            root = Path(scratch)/'source'
            path = Path(scratch)/'git-credentials-abc-123.config'
            path.write_text('SYNTHETIC shape data; never credentials')
            keys = ['includeif.gitdir:' + item + '.path' for item in (
                str(root/'.git'), str(root/'.git/worktrees/*'), '/github/workspace/.git', '/github/workspace/.git/worktrees/*')]
            entries = [(key, str(path)) for key in keys]
            self.assertEqual(validate_config(entries, scratch, mode='managed', root=root)[1], [path])
            with self.assertRaises(ValueError):
                validate_config(entries, scratch, mode='normal', root=root)
            for wrong in ('../git-credentials-a.config', '/wrong/git-credentials-a.config', str(Path(scratch)/'other.config')):
                with self.assertRaises(ValueError):
                    validate_config([(key, wrong) for key in keys], scratch, mode='managed', root=root)
            with self.assertRaises(ValueError):
                validate_config([('includeif.onbranch:main.path', str(path))], scratch, mode='managed', root=root)
            with self.assertRaises(ValueError):
                validate_config(entries[:1], scratch, mode='managed', root=root)

    def test_release_advertisement_requires_exact_raw_canonical_ref_objects(self):
        text = 'a' * 40 + '\trefs/tags/v0.1.0\n' + 'b' * 40 + '\trefs/tags/v0.1.0-rc.1\n'
        self.assertEqual(len(release_refs(text)), 2)
        for malformed in (text + text, 'bad\trefs/tags/v0.1.0', 'a' * 40 + '\trefs/tags/v0.1.0-rc.0',
                          'a' * 40 + '\trefs/heads/main', 'a' * 40 + '\trefs/tags/v01.1.0'):
            with self.assertRaises(ValueError):
                release_refs(malformed)
        self.assertEqual(release_refs('a' * 40 + '\trefs/tags/unrelated'), {})

    def test_missing_extractor_marker_fails_before_any_git_or_network(self):
        def forbidden(*args, **kwargs):
            self.fail('Missing marker reached a Git subprocess')
        with self.assertRaises(ValueError):
            prepare(SCRATCH/'synthetic', 'managed', {}, forbidden)

    def test_snapshot_cannot_replace_actual_local_graph_or_source_with_a_stamp(self):
        context = {'fixture': True, 'mode': 'fixture', 'admissionHeld': False}
        state = {'head': 'a' * 40}
        from complete_codeql_checkout import canonical, digest
        snapshot = dict(schema=1, mode='fixture', context=context, state=state,
                        tags={}, peeled={}, tagMapSha256=digest(canonical({})))
        with patch('complete_codeql_checkout.fingerprint', return_value=state), patch('complete_codeql_checkout.verify_tags', return_value={}) as actual:
            self.assertEqual(validate_snapshot(object(), snapshot, context), snapshot)
            actual.assert_called_once()
        for change in ('context', 'state', 'tagMapSha256'):
            broken = snapshot | {change: 'tampered'}
            with patch('complete_codeql_checkout.fingerprint', return_value=state), self.assertRaises(ValueError):
                validate_snapshot(object(), broken, context)
        with patch('complete_codeql_checkout.fingerprint', return_value=state), patch('complete_codeql_checkout.verify_tags', side_effect=ValueError('Missing parent/object')), self.assertRaises(ValueError):
            validate_snapshot(object(), snapshot, context)
        with self.assertRaises(ValueError):
            validate_snapshot(object(), {'completed': True}, context)

    def test_settings_prepares_before_app_and_preserves_extraction_cache_guard(self):
        source = (Path(__file__).resolve().parents[2] / 'settings.gradle').read_text()
        self.assertIn("environmentVariable('CODEQL_EXTRACTOR_JAVA_TRAP_DIR').orElse('').get().trim()", source)
        self.assertIn("'tools/build/complete_codeql_checkout.py', '--mode', 'managed'", source)
        self.assertLess(source.index('complete_codeql_checkout.py'), source.index("include ':app'"))
        self.assertIn('enabled = false', source)
        self.assertIn('preparation.standardError.asText.get().readLines()', source)
        self.assertNotIn('standardError =', source)
        self.assertIn('categories.contains(it)', source)
        self.assertIn('ignoreExitValue = true', source)


class PreparationTranscript:
    """Synthetic filesystem and injected Git stdout only; never executes Git."""
    head, source, base, tree, blob = (c * 40 for c in 'abcde')

    def __init__(self, directory, defect=None):
        self.root = Path(directory)/'source'
        self.root.mkdir()
        (self.root/'.git/objects/pack').mkdir(parents=True)
        (self.root/'.git/refs/tags').mkdir(parents=True)
        (self.root/'VERSION').write_text('0.1.0\n')
        for name, value in (('index', 'SYNTHETIC raw index'), ('HEAD', self.head), ('config', 'SYNTHETIC local config')):
            (self.root/'.git'/name).write_text(value)
        self.event = Path(directory)/'event.json'
        self.payload = {'repository': {'full_name': 'leugenea/codexbar-mobile'}, 'number': 150,
                        'pull_request': {'number': 150, 'base': {'repo': {'full_name': 'leugenea/codexbar-mobile'}},
                                         'head': {'sha': self.source}, 'merge_commit_sha': self.head}}
        self.event.write_text(json.dumps(self.payload))
        self.environment = dict(GITHUB_ACTIONS='true', GITHUB_RUN_ID='7', GITHUB_RUN_ATTEMPT='1', GITHUB_JOB='build',
                                RUNNER_TEMP=str(directory), GITHUB_WORKSPACE=str(self.root), GITHUB_REPOSITORY='leugenea/codexbar-mobile',
                                GITHUB_EVENT_NAME='pull_request', GITHUB_EVENT_PATH=str(self.event), GITHUB_SHA=self.head,
                                GITHUB_REF='refs/pull/150/merge', PATH='/synthetic-path')
        self.defect, self.calls, self.network_count = defect, [], 0
        self.index = f'100644 {self.blob} 0\tVERSION\0'.encode()
        if defect == 'workingBytes':
            (self.root/'VERSION').write_text('SYNTHETIC dirty bytes')
        if defect == 'graft':
            (self.root/'.git/info').mkdir()
            (self.root/'.git/info/grafts').write_text('SYNTHETIC graft')
        if defect == 'alternate':
            (self.root/'.git/objects/info').mkdir(parents=True)
            (self.root/'.git/objects/info/alternates').write_text('SYNTHETIC alternate')
        if defect == 'promisor':
            (self.root/'.git/objects/pack').mkdir(parents=True, exist_ok=True)
            (self.root/'.git/objects/pack/SYNTHETIC.promisor').write_text('SYNTHETIC promisor')

    def run(self, argv, **options):
        self.calls.append((argv, options))
        args = private_args(argv)
        if any(item.startswith('--git-dir=') for item in argv):
            baseline = private_baseline(argv, **options)
            if baseline is not None:
                return baseline
        if args[0] in ('fetch', 'ls-remote'):
            self.network_count += 1
            home = Path(options['env']['HOME'])
            assert home.is_relative_to(Path(self.environment['RUNNER_TEMP'])) and home.is_dir() and not list(home.iterdir())
            assert options['env']['XDG_CONFIG_HOME'] == str(home)
            assert 'RUNNER_TEMP' not in options['env'] and 'GITHUB_SHA' not in options['env']
            if self.defect == 'refusal':
                return subprocess.CompletedProcess(argv, 1, b'SYNTHETIC secret', b'SYNTHETIC secret')
            if self.defect == 'timeout':
                raise subprocess.TimeoutExpired(argv, 60, stderr='SYNTHETIC secret')
        text = ''
        if args == ('rev-parse', 'HEAD'):
            text = self.head
        elif args == ('rev-parse', 'HEAD^{tree}'):
            text = self.tree
        elif args == ('rev-parse', '--show-object-format'):
            text = 'sha1'
        elif args == ('rev-parse', '--show-ref-format'):
            text = 'files'
        elif args == ('rev-parse', '--show-toplevel'):
            text = str(self.root)
        elif args == ('rev-parse', '--git-common-dir'):
            text = '.git'
        elif args[:2] == ('rev-parse', '--git-path'):
            text = '.git/' + args[2]
        elif args == ('rev-parse', '--is-shallow-repository'):
            text = 'true' if self.defect == 'shallow' else 'false'
        elif args == ('config', '--local', '--no-includes', '--null', '--list'):
            origin = 'https://wrong.invalid' if self.defect == 'origin' else PUBLIC_URL
            text = 'remote.origin.url\n' + origin + '\0core.filemode\ntrue\0'
            if self.defect == 'config':
                text += 'url.https://wrong.invalid.insteadof\nhttps://github.com/\0'
            if self.defect == 'partial':
                text += 'remote.origin.promisor\ntrue\0'
        elif args == ('ls-files', '--stage', '-z'):
            return subprocess.CompletedProcess(argv, 0, self.index, b'')
        elif args == ('ls-files', '--others', '--exclude-standard', '-z'):
            text = 'SYNTHETIC-untracked\0' if self.defect == 'untracked' else ''
        elif args == ('ls-tree', '-r', '-z', 'HEAD'):
            text = f'100644 blob {self.blob}\tVERSION\0'
        elif args == ('cat-file', 'blob', self.blob):
            text = '0.1.0\n'
        elif args == ('cat-file', '-p', self.head):
            text = f'parent {self.source}\nparent {self.base}\n'
        elif args == ('rev-parse', 'refs/tags/v0.0.1^{commit}'):
            text = self.source
        elif args[0] == 'for-each-ref':
            if args[-1] == 'refs/tags':
                if self.defect in ('lightweight', 'annotated'):
                    text = (self.source if self.defect == 'lightweight' else 'f' * 40) + ' refs/tags/v0.0.1\n'
                elif self.defect == 'tagConflict':
                    text = self.blob + ' refs/tags/v0.1.0\n'
                elif self.defect == 'tagMissing':
                    text = ''
            elif args[-1] == 'refs/replace':
                text = 'refs/replace/' + self.head if self.defect == 'replacement' else ''
            else:
                text = 'refs/remotes/origin/main ' + (self.blob if self.defect == 'refDrift' and self.network_count >= 2 else self.base)
        elif args[:3] == ('ls-remote', '--tags', '--refs'):
            text = self.head + '\trefs/tags/v0.1.0\n' if self.defect in ('tagConflict', 'tagMissing') else ''
            if self.defect in ('lightweight', 'annotated'):
                text = (self.source if self.defect == 'lightweight' else 'f' * 40) + '\trefs/tags/v0.0.1\n'
            if self.defect == 'tagMoved' and self.network_count == 2:
                text = self.blob + '\trefs/tags/v0.1.0\n'
            if self.network_count == 2 and self.defect in ('indexDrift', 'configDrift'):
                (self.root/'.git'/('index' if self.defect == 'indexDrift' else 'config')).write_text('SYNTHETIC drift')
        elif args[:3] == ('rev-list', '--objects', '--missing=print'):
            text = '\n'.join((self.head, self.source, self.base, self.tree, self.blob))
            if self.defect == 'missingObject':
                text += '\n?' + 'f' * 40
        elif args == ('cat-file', '--batch-check', '--batch-all-objects'):
            text = '\n'.join(oid + ' commit 1' for oid in (self.head, self.source, self.base, self.tree, self.blob))
            if self.defect == 'annotated':
                text += '\n' + 'f' * 40 + ' tag 1'
            if self.defect == 'absentObject':
                text = text.replace(self.blob + ' commit 1', '')
        elif args[:2] == ('rev-list', '--parents'):
            text = f'{self.head} {self.source} {self.base}\n{self.source}\n{self.base}'
            if self.defect == 'missingParent':
                text = f'{self.head} ' + 'f' * 40
        else:
            raise AssertionError('Unspecified synthetic Git command')
        return subprocess.CompletedProcess(argv, 0, text.encode(), b'')


class PreparationContracts(unittest.TestCase):
    def test_successful_normal_prepare_and_readonly_reuse_execute_complete_transcript(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            fake = PreparationTranscript(directory)
            before = {n: (fake.root/'.git'/n).read_bytes() for n in ('index', 'config', 'HEAD')}
            observation = prepare(fake.root, 'normal', fake.environment, fake.run)
            self.assertEqual(observation['context']['checkoutRole'], 'normal_pr_merge')
            self.assertEqual(observation['context']['sourceHeadSha'], fake.source)
            self.assertEqual(observation['context']['producerEventSha'], fake.head)
            self.assertEqual(fake.network_count, 2)
            self.assertEqual(prepare(fake.root, 'normal', fake.environment, fake.run), observation)
            self.assertEqual(fake.network_count, 2, 'Reuse must not query an origin again')
            self.assertEqual({n: (fake.root/'.git'/n).read_bytes() for n in before}, before)
            path = snapshot_path(fake.root, fake.environment)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(read_observation(path), observation)

    def test_prepare_negative_matrix_preserves_failures_without_fallback(self):
        for defect in ('origin', 'config', 'partial', 'workingBytes', 'untracked', 'graft', 'alternate', 'promisor',
                       'replacement', 'shallow', 'missingObject', 'absentObject', 'missingParent', 'tagConflict',
                       'tagMissing', 'tagMoved', 'indexDrift', 'configDrift', 'refDrift', 'refusal', 'timeout'):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
                fake = PreparationTranscript(directory, defect)
                with self.assertRaises(AcquisitionError) as error:
                    prepare(fake.root, 'normal', fake.environment, fake.run)
                self.assertIn(error.exception.stage, STAGES)
                self.assertIn(error.exception.category, CATEGORIES)
                self.assertNotIn('SYNTHETIC secret', error.exception.receipt())
                self.assertFalse(snapshot_path(fake.root, fake.environment).exists())
                if defect in ('origin', 'config', 'partial', 'workingBytes', 'untracked', 'graft', 'alternate', 'promisor', 'replacement'):
                    self.assertEqual(fake.network_count, 0)
                if defect in ('refusal', 'timeout'):
                    self.assertEqual(fake.network_count, 1)

    def test_observation_collision_links_and_late_preservation_drift_fail(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            fake = PreparationTranscript(directory)
            path = snapshot_path(fake.root, fake.environment)
            outside = Path(directory)/'outside-owned-controlled-target'
            path.symlink_to(outside)
            with self.assertRaises(AcquisitionError):
                prepare(fake.root, 'normal', fake.environment, fake.run)
            self.assertFalse(outside.exists())
            self.assertEqual(fake.network_count, 0)
            with self.assertRaises(ValueError):
                publish_observation(path, {})
            path.unlink()
            path.write_text('SYNTHETIC existing bytes')
            with self.assertRaises(OSError):
                publish_observation(path, {})
            self.assertEqual(path.read_text(), 'SYNTHETIC existing bytes')
            path.unlink()
            def late_drift(target, observation):
                publish_observation(target, observation)
                (fake.root/'.git/index').write_text('SYNTHETIC after-write drift')
            with patch('complete_codeql_checkout.publish_observation', side_effect=late_drift), self.assertRaises(AcquisitionError):
                prepare(fake.root, 'normal', fake.environment, fake.run)

    def test_observation_context_graph_ref_and_state_tampering_never_reacquires(self):
        for field in ('context', 'state', 'mode', 'tagMapSha256', 'tags'):
            with self.subTest(field=field), tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
                fake = PreparationTranscript(directory)
                observation = prepare(fake.root, 'normal', fake.environment, fake.run)
                path = snapshot_path(fake.root, fake.environment)
                changed = copy.deepcopy(observation)
                changed[field] = 'SYNTHETIC tampering'
                path.write_bytes(canonical(changed))
                with self.assertRaises(AcquisitionError):
                    prepare(fake.root, 'normal', fake.environment, fake.run)
                self.assertEqual(fake.network_count, 2)
        for field in ('GITHUB_JOB', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'):
            with self.subTest(field=field), tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
                fake = PreparationTranscript(directory)
                observation = prepare(fake.root, 'normal', fake.environment, fake.run)
                wrong = fake.environment | {field: 'another_job' if field == 'GITHUB_JOB' else '8'}
                snapshot_path(fake.root, wrong).write_bytes(canonical(observation))
                with self.assertRaises(AcquisitionError):
                    prepare(fake.root, 'normal', wrong, fake.run)
                self.assertEqual(fake.network_count, 2)
        for defect in ('missingObject', 'refDrift', 'indexDrift'):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
                fake = PreparationTranscript(directory)
                prepare(fake.root, 'normal', fake.environment, fake.run)
                fake.defect = defect
                if defect == 'indexDrift':
                    (fake.root/'.git/index').write_text('SYNTHETIC post-observation drift')
                with self.assertRaises(AcquisitionError):
                    prepare(fake.root, 'normal', fake.environment, fake.run)
                self.assertEqual(fake.network_count, 2)

    def test_event_name_missing_empty_wrong_kind_ref_source_and_repository_never_downgrade(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            fake = PreparationTranscript(directory)
            valid = fake.payload
            context = job_context(fake.environment, fake.root, fake.head)
            self.assertEqual(context['eventName'], 'pull_request')
            for payload in (valid | {'pull_request': None}, valid | {'pull_request': {}},
                            {k: v for k, v in valid.items() if k != 'pull_request'},
                            valid | {'repository': {'full_name': 'wrong/public'}},
                            valid | {'number': 151}):
                fake.event.write_text(json.dumps(payload))
                with self.assertRaises(ValueError):
                    job_context(fake.environment, fake.root, fake.head)
            fake.event.write_text(json.dumps(valid))
            for changes in ({'GITHUB_EVENT_NAME': ''}, {'GITHUB_EVENT_NAME': 'dynamic'}, {'GITHUB_EVENT_NAME': 'push'},
                            {'GITHUB_REF': 'refs/pull/150/head'}, {'GITHUB_SHA': fake.source},
                            {'GITHUB_REPOSITORY': 'wrong/public'}, {'GITHUB_WORKSPACE': str(SCRATCH/'different')}):
                with self.subTest(changes=list(changes)), self.assertRaises(ValueError):
                    job_context(fake.environment | changes, fake.root, fake.head)
            with self.assertRaises(ValueError):
                job_context(fake.environment, fake.root, fake.source)

    def test_managed_head_recognition_is_bound_but_every_unadopted_tuple_stays_held(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            fake = PreparationTranscript(directory)
            managed = fake.environment | {'CODEQL_EXTRACTOR_JAVA_TRAP_DIR': 'SYNTHETIC-marker', 'CODE_SCANNING_REF': 'refs/pull/150/head'}
            for raw in (fake.head, fake.source, 'f' * 40):
                context = job_context(managed | {'GITHUB_SHA': raw}, fake.root, fake.source, mode='managed')
                self.assertEqual(context['checkoutRole'], 'managed_pr_head')
                self.assertEqual(context['compiledCheckoutSha'], fake.source)
                self.assertEqual(context['producerEventSha'], raw)
                self.assertTrue(context['admissionHeld'])
                with self.assertRaises(AcquisitionError):
                    require_admission(context)
            for change in ({'CODE_SCANNING_REF': 'refs/pull/151/head'}, {'CODE_SCANNING_REF': 'refs/pull/150/merge'},
                           {'CODEQL_EXTRACTOR_JAVA_TRAP_DIR': ''}):
                with self.assertRaises(AcquisitionError):
                    job_context(managed | change, fake.root, fake.source, mode='managed')
            with self.assertRaises(AcquisitionError):
                job_context(managed, fake.root, fake.head, mode='managed')

    def test_direct_push_and_dispatch_are_event_bound_not_pr_fallbacks(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            fake = PreparationTranscript(directory)
            for kind in ('push', 'workflow_dispatch'):
                payload = {'repository': {'full_name': 'leugenea/codexbar-mobile'}, 'ref': 'refs/heads/main',
                           'after': fake.head, 'head_commit': {'id': fake.head}}
                fake.event.write_text(json.dumps(payload))
                env = fake.environment | {'GITHUB_EVENT_NAME': kind, 'GITHUB_REF': 'refs/heads/main'}
                self.assertEqual(job_context(env, fake.root, fake.head)['checkoutRole'], 'direct')
                for changed in (payload | {'pull_request': {}}, payload | {'ref': 'refs/heads/wrong'}):
                    fake.event.write_text(json.dumps(changed))
                    with self.assertRaises(ValueError):
                        job_context(env, fake.root, fake.head)
                fake.event.write_text(json.dumps(payload))

    def test_host_scratch_is_original_validated_input_not_a_child_environment_fallback(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            valid = Path(directory)
            file = valid/'regular-file'
            file.write_text('SYNTHETIC')
            link = valid/'link'
            link.symlink_to(valid)
            for value in (None, '', 'relative', str(file), str(valid/'absent'), str(link)):
                with self.subTest(value=value), self.assertRaises(AcquisitionError):
                    host_scratch({'RUNNER_TEMP': value, 'BUILD_CONTRACT_SCRATCH': str(valid)})
            self.assertEqual(host_scratch({'RUNNER_TEMP': str(valid)}), valid)
            original = dict(PATH='/synthetic', RUNNER_TEMP=str(valid), GH_TOKEN='SYNTHETIC-secret', HOME='/unsafe', GIT_TRACE='1')
            before = original.copy()
            calls = []
            def fake(argv, **options):
                calls.append(options['env'])
                self.assertTrue(Path(options['env']['HOME']).is_dir())
                self.assertTrue(Path(options['env']['HOME']).is_relative_to(valid))
                return subprocess.CompletedProcess(argv, 0, b'', b'')
            git = Git(valid/'synthetic', original, lambda argv, **kw: private_baseline(argv, **kw) or fake(argv, **kw))
            transport = _PrivateTransport(git)
            try:
                transport.advertise('ADVERTISEMENT_BEFORE')
            finally:
                transport.close()
            self.assertEqual(original, before)
            self.assertEqual(len(calls), 1)
            self.assertFalse(Path(calls[0]['HOME']).exists())
            for key in ('RUNNER_TEMP', 'GH_TOKEN', 'GIT_TRACE'):
                self.assertNotIn(key, calls[0])

    def test_fixed_diagnostics_cannot_emit_host_or_exception_values(self):
        with self.assertRaises(ValueError):
            AcquisitionError('SYNTHETIC-path', 'TRANSPORT_REFUSED')
        with self.assertRaises(ValueError):
            AcquisitionError('HOST_CONTEXT', 'SYNTHETIC-secret')
        with self.assertRaises(ValueError):
            AcquisitionError('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED', ('SYNTHETIC-secret',))
        receipt = AcquisitionError('HOST_CONTEXT', 'EVENT_ROLE_UNSUPPORTED', ('EVENT_PR_MISSING',), message='SYNTHETIC-secret').receipt()
        self.assertNotIn('SYNTHETIC-secret', receipt)
        self.assertEqual(receipt, 'ACQUISITION_DIAGNOSTIC HOST_CONTEXT EVENT_ROLE_UNSUPPORTED EVENT_PR_MISSING')

    def test_managed_credential_include_shape_is_observed_but_transport_is_inert_and_held(self):
        from complete_codeql_checkout import inspect_config
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            root = Path(directory)/'source'
            (root/'.git').mkdir(parents=True)
            config = root/'.git/config'
            config.write_text('SYNTHETIC immutable config bytes')
            include = Path(directory)/'git-credentials-abc-123.config'
            include.write_text('SYNTHETIC opaque bytes; not actual credentials')
            keys = ['includeif.gitdir:' + item + '.path' for item in (
                str(root/'.git'), str(root/'.git/worktrees/*'), '/github/workspace/.git', '/github/workspace/.git/worktrees/*')]
            raw = ('remote.origin.url\n' + PUBLIC_URL + '\0' + ''.join(key + '\n' + str(include) + '\0' for key in keys)).encode()
            commands = []
            def fake(argv, **options):
                index = 3
                while argv[index] == '-c':
                    index += 2
                args = argv[index:]
                commands.append(args)
                if args[:2] == ['config', '--local']:
                    stdout = raw
                elif args[:2] == ['config', '--file']:
                    self.assertIn('--no-includes', args)
                    self.assertIn('--name-only', args)
                    stdout = b'http.https://github.com/.extraheader\0'
                elif args == ['rev-parse', '--git-path', 'config']:
                    stdout = str(config).encode()
                else:
                    self.fail('Anonymous transport attempted to load recognized credentials')
                return subprocess.CompletedProcess(argv, 0, stdout, b'')
            git = Git(root, {'RUNNER_TEMP': directory}, fake)
            git.mode = 'managed'
            before = config.read_bytes(), include.read_bytes()
            self.assertEqual(inspect_config(git), PUBLIC_URL)
            self.assertEqual(git.includes, [include])
            with self.assertRaises(AcquisitionError) as held:
                git.call('ls-remote', '--tags', '--refs', PUBLIC_URL)
            self.assertIn('CONFIG_INCLUDE_CREDENTIAL_ONLY_RECOGNIZED', held.exception.receipt())
            self.assertEqual((config.read_bytes(), include.read_bytes()), before)
            self.assertFalse(any(args[0] == 'ls-remote' for args in commands))
            for unsupported in (b'credential.helper\0', b'include.path\0', b'http.proxy\0'):
                def wrong(argv, **options):
                    result = fake(argv, **options)
                    return subprocess.CompletedProcess(argv, 0, unsupported if '--file' in argv else result.stdout, b'')
                with self.assertRaises(AcquisitionError):
                    invalid = Git(root, {'RUNNER_TEMP': directory}, wrong)
                    invalid.mode = 'managed'
                    inspect_config(invalid)


    def test_lightweight_and_annotated_acquired_maps_are_peeled_in_successful_prepare_transcripts(self):
        for kind in ('lightweight', 'annotated'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
                fake = PreparationTranscript(directory, kind)
                observation = prepare(fake.root, 'normal', fake.environment, fake.run)
                self.assertEqual(observation['tags'], {'refs/tags/v0.0.1': fake.source if kind == 'lightweight' else 'f' * 40})
                self.assertEqual(observation['peeled'], {'refs/tags/v0.0.1': fake.source})
                self.assertEqual(observation['tagMapSha256'], digest(canonical(observation['tags'])))
                self.assertEqual(fake.network_count, 2)

    def test_observation_ancestry_links_and_nonregular_entries_never_publish_or_read(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            root = Path(directory)
            (root/'real').mkdir()
            (root/'parent').symlink_to(root/'real')
            with self.assertRaises(ValueError):
                publish_observation(root/'parent/observation.json', {})
            self.assertFalse((root/'real/observation.json').exists())
            (root/'directory-entry').mkdir()
            with self.assertRaises((ValueError, OSError)):
                read_observation(root/'directory-entry')

    def test_fixture_script_isolates_inherited_tags_and_models_real_managed_source_without_event_rewrite(self):
        source = (Path(__file__).resolve().parents[2]/'tools/build/run-hosted-release-fixtures.sh').read_text()
        self.assertIn('git clone --no-tags --no-local --bare "$GITHUB_WORKSPACE"', source)
        self.assertIn('git clone --no-tags --no-local "$scratch/origin.git"', source)
        self.assertIn('inheritedTargetTagsIsolated', source)
        self.assertIn("context['sourceHeadSha'] if context['checkoutRole'] == 'normal_pr_merge'", source)
        self.assertIn('"$scratch/managed" "$managed_source_sha"', source)
        self.assertNotIn('GITHUB_EVENT_PATH=', source)
        self.assertNotIn('GITHUB_SHA=', source)
        self.assertNotIn('git tag --force', source)


    def test_cli_argument_failure_emits_only_fixed_categories_without_running_git(self):
        import io
        from contextlib import redirect_stderr
        from complete_codeql_checkout import main
        stderr = io.StringIO()
        with patch('sys.argv', ['helper', '--mode', 'SYNTHETIC-sensitive-value']), redirect_stderr(stderr), patch('complete_codeql_checkout.prepare') as execution:
            with self.assertRaises(SystemExit) as error:
                main()
        self.assertEqual(error.exception.code, 2)
        self.assertEqual(stderr.getvalue(), 'ACQUISITION_DIAGNOSTIC HOST_CONTEXT EVENT_ROLE_UNSUPPORTED\n')
        execution.assert_not_called()

    def test_original_reader_keeps_vector_but_never_dispatches_network_or_mutations(self):
        calls = []
        git = Git(SCRATCH/'mock-reader', {'RUNNER_TEMP': str(SCRATCH)},
                  lambda argv, **kw: calls.append(argv) or subprocess.CompletedProcess(argv, 0, b'', b''))
        git.call('config', '--local', '--no-includes', '--null', '--list')
        for guard in ('http.sslCert=', 'http.sslKey=', 'credential.helper=', 'http.sslVerify=true'):
            self.assertIn(guard, calls[0])
        for command in (('fetch', PUBLIC_URL), ('ls-remote', PUBLIC_URL), ('update-ref', 'refs/tags/v0.1.0', 'a'*40),
                        ('config', 'credential.helper', 'unsafe'), ('index-pack', 'unsafe'), ('maintenance', 'run')):
            with self.assertRaises(AcquisitionError):
                git.call(*command)
        self.assertEqual(len(calls), 1)

    def test_private_transport_observer_covers_both_inits_and_all_network_windows(self):
        phases = []
        class Observer:
            def arm(self, phase):
                phases.append(('arm', phase))
            def drain(self, phase):
                phases.append(('drain', phase))
        calls = []
        def fake(argv, **kw):
            self.assertTrue(Path(kw['cwd']).is_absolute())
            self.assertIn('--git-dir=' + kw['cwd'], argv)
            self.assertEqual(kw['env']['PATH'], '/usr/bin:/bin')
            calls.append(private_args(argv))
            return private_baseline(argv, **kw) or subprocess.CompletedProcess(argv, 0, b'', b'')
        original = {'RUNNER_TEMP': str(SCRATCH), 'HOME': '/unsafe', 'GIT_CONFIG_COUNT': '1', 'GH_TOKEN': 'SYNTHETIC'}
        git = Git(SCRATCH/'mock-source', original, fake)
        transport = _PrivateTransport(git, observer=Observer())
        try:
            transport.advertise('ADVERTISEMENT_BEFORE')
            transport.fetch('a'*40, {'refs/tags/v0.1.0': 'b'*40})
            transport.advertise('ADVERTISEMENT_AFTER')
            self.assertEqual(phases, [(kind, phase) for phase in ('INIT_TRANSPORT', 'INIT_VALIDATOR',
                             'ADVERTISEMENT_BEFORE', 'FETCH_EXACT', 'ADVERTISEMENT_AFTER') for kind in ('arm', 'drain')])
            self.assertEqual(sum(args[0] == 'fetch' for args in calls), 1)
            for operation in (('T', 'READ', 'config', '--file', '/unsafe', '--list'),
                              ('T', 'FETCH_EXACT', 'fetch', PUBLIC_URL, 'refs/heads/main'),
                              ('V', 'EXPORT', 'pack-objects', '--thin'), ('T', 'READ', 'cat-file', '--filters')):
                with self.assertRaises(ValueError):
                    transport.call(*operation)
        finally:
            private = transport.root
            transport.close()
        self.assertFalse(private.exists())
        self.assertEqual(git.environment, original)

    def test_private_metadata_replacement_version_and_watcher_refusal_are_not_success(self):
        def run(argv, **kw):
            return private_baseline(argv, **kw) or subprocess.CompletedProcess(argv, 0, b'', b'')
        git = Git(SCRATCH/'mock-source', {'RUNNER_TEMP': str(SCRATCH)}, run)
        transport = _PrivateTransport(git)
        original = (transport.root/'T/config').read_bytes()
        try:
            (transport.root/'T/config').write_text('SYNTHETIC altered private config')
            with self.assertRaises(ValueError):
                transport.advertise('ADVERTISEMENT_BEFORE')
            (transport.root/'T/config').write_bytes(original)
        finally:
            transport.close()
        def wrong_version(argv, **kw):
            if private_args(argv) == ('--version',):
                return subprocess.CompletedProcess(argv, 0, b'git version unknown', b'')
            return run(argv, **kw)
        with self.assertRaises(ValueError):
            _PrivateTransport(Git(SCRATCH/'mock-source', {'RUNNER_TEMP': str(SCRATCH)}, wrong_version))
        class Lost:
            def arm(self, phase):
                raise ValueError('SYNTHETIC observer loss')
            def drain(self, phase):
                raise AssertionError('Unarmed drain')
        with self.assertRaises(ValueError):
            _PrivateTransport(git, observer=Lost())

    def test_dynamic_producer_comparison_is_observational_only_and_has_zero_outbound(self):
        for raw, category in ((None, 'MISSING'), ('', 'MISSING'), ('A'*40, 'INVALID'), ('a'*39, 'INVALID'),
                              (' a' + 'a'*39, 'INVALID'), ('a'*40, 'MATCHES_COMPILED_HEAD'), ('b'*40, 'DIFFERS_COMPILED_HEAD')):
            with self.subTest(category=category), tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
                fake = PreparationTranscript(directory)
                fake.payload = {'repository': {'full_name': 'leugenea/codexbar-mobile'}}
                fake.event.write_text(json.dumps(fake.payload))
                fake.environment.update(GITHUB_EVENT_NAME='dynamic', GITHUB_REF='refs/pull/150/head',
                                        CODE_SCANNING_REF='refs/pull/150/head', CODEQL_EXTRACTOR_JAVA_TRAP_DIR='SYNTHETIC-marker')
                if raw is None:
                    fake.environment.pop('GITHUB_SHA')
                else:
                    fake.environment['GITHUB_SHA'] = raw
                with self.assertRaises(AcquisitionError) as refusal:
                    prepare(fake.root, 'managed', fake.environment, fake.run)
                self.assertEqual(refusal.exception.stage, 'EVENT_KIND')
                self.assertIn('EVENT_PRODUCER_SHA_' + category, refusal.exception.observations)
                self.assertIn('EVENT_PR_MISSING', refusal.exception.observations)
                self.assertEqual(fake.network_count, 0)
                self.assertTrue(any(private_args(argv)[0] == 'ls-tree' for argv, _ in fake.calls))
                self.assertFalse(snapshot_path(fake.root, fake.environment).exists())

    def test_compiled_capture_drift_cannot_emit_a_fabricated_comparison(self):
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            fake = PreparationTranscript(directory, 'workingBytes')
            fake.event.write_text(json.dumps({'repository': {'full_name': 'leugenea/codexbar-mobile'}}))
            fake.environment.update(GITHUB_EVENT_NAME='dynamic', CODE_SCANNING_REF='refs/pull/150/head',
                                    CODEQL_EXTRACTOR_JAVA_TRAP_DIR='SYNTHETIC-marker')
            with self.assertRaises(AcquisitionError) as refusal:
                prepare(fake.root, 'managed', fake.environment, fake.run)
            self.assertEqual(refusal.exception.stage, 'PROTECTED_SOURCE_INDEX_STATE')
            self.assertFalse(any(x.endswith(('MATCHES_COMPILED_HEAD', 'DIFFERS_COMPILED_HEAD')) for x in refusal.exception.observations))
            self.assertEqual(fake.network_count, 0)

    def test_descriptor_publication_is_no_clobber_preserves_old_bytes_and_retires_shallow_last(self):
        from complete_codeql_checkout import _SourceStore
        with tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
            fake = PreparationTranscript(directory)
            git = Git(fake.root, fake.environment, fake.run)
            from complete_codeql_checkout import inspect_config
            inspect_config(git)
            store = _SourceStore(git)
            # PURE named byte-copy collision control, not native pack validity.
            pack = Path(directory)/('pack-' + 'f'*40 + '.pack')
            index = pack.with_suffix('.idx')
            pack.write_bytes(b'SYNTHETIC already-validated pack transcript')
            index.write_bytes(b'SYNTHETIC already-validated index transcript')
            try:
                before = {n:(fake.root/'.git'/n).read_bytes() for n in ('index', 'config', 'HEAD')}
                store.import_pack(pack, index)
                store.cleanup()
                self.assertEqual({n:(fake.root/'.git'/n).read_bytes() for n in before}, before)
                for name in (pack.name, index.name):
                    self.assertEqual((fake.root/'.git/objects/pack'/name).stat().st_mode & 0o777, 0o444)
                self.assertFalse(store.owned)
                store.import_pack(pack, index)
                index.write_bytes(b'SYNTHETIC different pair')
                with self.assertRaises(ValueError):
                    store.import_pack(pack, index)
            finally:
                store.cleanup()
                store.close_descriptors()

    def test_partial_pair_shared_layout_lock_and_symlink_publication_refuse_without_repair(self):
        from complete_codeql_checkout import _SourceStore, inspect_config
        for defect in ('partial', 'lock', 'symlink', 'shared'):
            with self.subTest(defect=defect), tempfile.TemporaryDirectory(dir=SCRATCH) as directory:
                fake = PreparationTranscript(directory)
                path = fake.root/'.git/objects/pack'/('pack-' + 'f'*40 + '.pack')
                material = Path(directory)/path.name
                material.write_bytes(b'SYNTHETIC')
                index = material.with_suffix('.idx'); index.write_bytes(b'SYNTHETIC')
                if defect == 'partial':
                    path.write_bytes(material.read_bytes())
                elif defect == 'lock':
                    (fake.root/'.git/shallow.lock').write_text('SYNTHETIC foreign lock')
                elif defect == 'symlink':
                    path.symlink_to(material)
                else:
                    path.hardlink_to(material)
                git = Git(fake.root, fake.environment, fake.run); inspect_config(git)
                if defect == 'partial':
                    store = _SourceStore(git)
                    try:
                        with self.assertRaises(ValueError):
                            store.import_pack(material, index)
                        self.assertTrue(path.exists())
                        self.assertFalse(path.with_suffix('.idx').exists())
                    finally:
                        store.cleanup(); store.close_descriptors()
                else:
                    with self.assertRaises(ValueError):
                        _SourceStore(git)

    def test_exact_raw_object_closure_hash_type_parent_tree_and_nested_annotations(self):
        from complete_codeql_checkout import _closure, MAX_OBJECT
        import hashlib
        objects = {}
        def add(kind, raw):
            oid = hashlib.sha1(f'{kind} {len(raw)}\0'.encode()+raw).hexdigest()
            objects[oid] = (kind, raw)
            return oid
        blob = add('blob', b'SYNTHETIC parser bytes; not a hosted Git object receipt\n')
        tree_oid = add('tree', b'100644 VERSION\0'+bytes.fromhex(blob))
        head = add('commit', ('tree '+tree_oid+'\n\nSYNTHETIC parser commit\n').encode())
        inner = add('tag', ('object '+head+'\ntype commit\ntag synthetic\n\nannotation\n').encode())
        outer = add('tag', ('object '+inner+'\ntype tag\ntag synthetic-nested\n\nannotation\n').encode())
        class Reader:
            defect = None
            def call(self, *args, input=None):
                if args == ('rev-parse', '--is-shallow-repository'):
                    return b'true' if self.defect == 'shallow' else b'false'
                if args == ('rev-parse', head+'^{tree}'):
                    return ('f'*40 if self.defect == 'tree' else tree_oid).encode()
                if args[:2] == ('cat-file', '-t'):
                    return objects[args[2]][0].encode()
                if args[:2] == ('cat-file', 'tag'):
                    return objects[args[2]][1]
                if args[:4] == ('rev-list', '--objects', '--no-object-names', '--missing=print'):
                    return ('\n'.join(sorted((head, tree_oid, blob))) + ('\n?'+'f'*40 if self.defect == 'missing' else '')).encode()
                if args[:2] == ('rev-list', '--parents'):
                    return (head + (' '+'f'*40 if self.defect == 'parent' else '')).encode()
                ids = input.decode().splitlines()
                if args == ('cat-file', '--batch-check'):
                    return ''.join(f'{x} {objects[x][0]} {MAX_OBJECT+1 if self.defect == "oversize" else len(objects[x][1])}\n' for x in ids).encode()
                if args == ('cat-file', '--batch'):
                    raw = b''.join(f'{x} {objects[x][0]} {len(objects[x][1])}\n'.encode()+objects[x][1]+b'\n' for x in ids)
                    return raw.replace(b'SYNTHETIC', b'CORRUPTED', 1) if self.defect == 'content' else raw
                raise AssertionError('Unspecified PURE raw-reader transcript')
            def text(self, *args):
                return self.call(*args).decode().strip()
        reader = Reader()
        closure = _closure(reader, head, {'refs/tags/v0.1.0': outer})
        self.assertEqual(set(closure['objects']), set(objects))
        self.assertEqual(closure['tree'], tree_oid)
        self.assertEqual(closure['peeled'], {'refs/tags/v0.1.0': head})
        for defect in ('shallow', 'missing', 'parent', 'oversize', 'content', 'tree'):
            reader.defect = defect
            with self.subTest(defect=defect), self.assertRaises(ValueError):
                _closure(reader, head, {'refs/tags/v0.1.0': outer})

    def test_finite_budget_controls_reject_transcripts_before_publication(self):
        from complete_codeql_checkout import _object_metadata, MAX_OBJECT, MAX_METADATA
        class Reader:
            def __init__(self, output):
                self.output = output
            def call(self, *args, **kw):
                return self.output
        for output in ((('a'*40)+' blob '+str(MAX_OBJECT+1)+'\n').encode(),
                       ((('a'*40)+' blob 1\n')*2).encode(), b'missing\n', b'x'*(MAX_METADATA+1)):
            with self.assertRaises(ValueError):
                _object_metadata(Reader(output), {'a'*40})
        git = Git(SCRATCH/'mock-source', {'RUNNER_TEMP': str(SCRATCH)},
                  lambda argv, **kw: private_baseline(argv, **kw) or subprocess.CompletedProcess(argv, 0, b'', b''))
        transport = _PrivateTransport(git)
        try:
            git.deadline = 0
            with self.assertRaises(ValueError):
                transport.advertise('ADVERTISEMENT_BEFORE')
        finally:
            transport.close()
