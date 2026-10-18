"""Fail-closed exact-run source/channel admission and verified official Release history."""
import base64
import json
from pathlib import Path
import re
import urllib.parse

from release_publication import identifier, marker, read_assets, release_binding, require
from release_runtime import canonical, fingerprint, load, members
from release_signing import _unique, verify_assets
from version_identity import MAX_CODE, official_inputs, parse_tag, target_version

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = '.github/workflows/release.yml'
SOURCE_GATES = ('android.yml', 'repository-policy.yml', 'research-contract.yml', 'codeql.yml')


def activation(text, channel):
    from jsonschema import Draft7Validator
    require(channel in ('rc', 'stable'), 'Unknown release channel')
    require(isinstance(text, str) and bool(text.strip()), 'Channel activation is unset; release denied')
    record = json.loads(text, object_pairs_hook=_unique)
    Draft7Validator(load(ROOT / 'tools/build/schemas/release-activation.schema.json')).validate(record)
    require(record['channel'] == channel, 'RC and stable authorizations cannot be shared')
    for values in (record, record['previous'], record.get('candidate', {})):
        require(all(not isinstance(value, str) or (value == value.strip() and '\n' not in value and '\r' not in value)
                    for value in values.values()), 'Activation strings must be canonical single-line values')
    if 'emptyHistory' in record['previous']:
        require(record['previous']['emptyHistory'] is True, 'Explicit Boolean empty-history acknowledgment required')
    # JSON Schema numeric equality allows 1.0; IDs/counters must be actual JSON integers.
    for key in ('schema', 'repositoryId', 'workflowId', 'runId', 'runNumber'):
        require(type(record[key]) is int, 'Activation requires canonical integer ' + key)
    for value in (record.get('candidate', {}), record['previous']):
        for key in ('runId', 'runAttempt', 'artifactId', 'releaseId', 'versionCode'):
            if key in value:
                require(type(value[key]) is int, 'Activation requires canonical integer ' + key)
    return record


def tag_source(api, tag):
    parse_tag(tag)
    obj = api.get('/git/ref/tags/' + urllib.parse.quote(tag, safe=''))['object']
    seen = set()
    for _ in range(8):
        require(bool(re.fullmatch('[0-9a-f]{40}', obj['sha'])), 'Invalid tag object SHA')
        require(obj['sha'] not in seen, 'Cyclic tag')
        seen.add(obj['sha'])
        if obj['type'] == 'commit':
            return obj['sha']
        require(obj['type'] == 'tag', 'Release tag must peel to a commit')
        obj = api.get('/git/tags/' + obj['sha'])['object']
    raise ValueError('Too many annotated tag levels')


def workflow_tree(api, sha):
    current = api.get('/git/commits/' + sha)['tree']['sha']
    for directory in ('.github', 'workflows'):
        tree = api.get('/git/trees/' + current)
        require(tree.get('truncated') is False, 'Workflow tree read incomplete')
        entries = [r for r in tree['tree'] if r['path'] == directory and r['type'] == 'tree']
        require(len(entries) == 1, 'Workflow subtree is missing/ambiguous')
        current = entries[0]['sha']
    return current


def run_identity(api, run_id, workflow_id, *, sha=None, number=None, attempt=None):
    path = '/actions/runs/' + identifier(run_id)
    if attempt is not None:
        path += '/attempts/' + identifier(attempt)
    run = api.get(path)
    require(run['id'] == run_id and run['workflow_id'] == workflow_id and run['event'] == 'workflow_dispatch'
            and run['head_branch'] == 'main' and run['path'] == WORKFLOW
            and run['repository']['full_name'] == api.repository,
            'Origin run is not the dedicated trusted main workflow')
    require(type(run['run_number']) is int and 1 <= run['run_number'] <= MAX_CODE, 'Official counter reset/invalid')
    if sha is not None:
        require(run['head_sha'] == sha, 'Controller source differs')
    if number is not None:
        require(run['run_number'] == number, 'Official run_number differs')
    if attempt is not None:
        require(run['run_attempt'] == attempt, 'Origin attempt differs')
    return run


def protected_environment(api, name):
    path = '/environments/' + urllib.parse.quote(name, safe='')
    environment = api.get(path)
    require(environment['name'] == name, 'Environment identity differs')
    # The GET response exposes reviewer/ref rules, not the UI's administrator-bypass setting.
    # Owner custody approval must audit that setting; do not fabricate an API observation.
    reviewers = [r for r in environment['protection_rules'] if r['type'] == 'required_reviewers']
    require(len(reviewers) == 1 and reviewers[0].get('prevent_self_review') is True
            and bool(reviewers[0].get('reviewers')), 'Independent environment reviewer protection required')
    require(environment['deployment_branch_policy'] == {'protected_branches': False, 'custom_branch_policies': True},
            'Environment needs exact main-only custom branch policy')
    policies = api.pages(path + '/deployment-branch-policies', 'branch_policies')
    require(len(policies) == 1 and policies[0]['name'] == 'main' and policies[0]['type'] == 'branch',
            'Environment ref restriction must be exactly branch main')


def admit(api, record, context):
    comparisons = {'repository': 'repository', 'repositoryId': 'repositoryId', 'controllerSha': 'sha',
                   'controllerRef': 'ref', 'operation': 'operation', 'runId': 'runId', 'runNumber': 'runNumber',
                   'channel': 'channel', 'sourceSha': 'sourceSha'}
    for field, key in comparisons.items():
        require(record[field] == context[key] and type(record[field]) is type(context[key]), 'Activation mismatch: ' + field)
    require(context['event'] == 'workflow_dispatch' and context['ref'] == 'refs/heads/main'
            and context['workflowSha'] == record['controllerSha']
            and context['workflowRef'] == record['repository'] + '/' + WORKFLOW + '@refs/heads/main'
            and record['tagRef'] == 'refs/tags/' + context['tag'], 'Untrusted event/controller/tag')
    repository = api.get('')
    require(repository['id'] == record['repositoryId'] and repository['full_name'] == record['repository']
            and repository['default_branch'] == 'main' and repository['fork'] is False, 'Repository identity differs')
    workflow = api.get('/actions/workflows/' + identifier(record['workflowId']))
    require(workflow['id'] == record['workflowId'] and workflow['path'] == WORKFLOW and workflow['state'] == 'active',
            'Workflow identity reset/inactive')
    run_identity(api, record['runId'], record['workflowId'], sha=record['controllerSha'], number=record['runNumber'])
    main = api.get('/git/ref/heads/main')['object']['sha']
    require(main == record['controllerSha'], 'Controller activation is stale against current main')
    require(tag_source(api, context['tag']) == record['sourceSha'], 'Tag/source drift')
    commit = api.get('/git/commits/' + record['sourceSha'])
    require(commit['tree']['sha'] == record['sourceTree'], 'Source tree differs')
    comparison = api.get('/compare/' + record['sourceSha'] + '...' + main)
    require(comparison['status'] in ('ahead', 'identical'), 'Source must be accepted main history')
    require(workflow_tree(api, record['sourceSha']) == workflow_tree(api, main),
            'Workflow subtree drift requires Workflows-write; GITHUB_TOKEN release denied')
    content = api.get('/contents/VERSION?ref=' + record['sourceSha'])
    require(content['encoding'] == 'base64', 'Unsupported VERSION encoding')
    target = target_version(base64.b64decode(content['content']).decode())
    # Explicit known workflows, exact source, push/main, latest attempt outcome. No arbitrary green run.
    for filename in SOURCE_GATES:
        runs = api.pages('/actions/workflows/' + filename + '/runs?head_sha=' + record['sourceSha'] + '&event=push', 'workflow_runs')
        valid = [r for r in runs if r['head_sha'] == record['sourceSha'] and r['head_branch'] == 'main'
                 and r['event'] == 'push' and r['path'] == '.github/workflows/' + filename
                 and r['repository']['id'] == record['repositoryId']]
        require(bool(valid), 'Exact-source main gates missing: ' + filename)
        latest = max(valid, key=lambda r: r['id'])
        require(latest['status'] == 'completed' and latest['conclusion'] == 'success', 'Source gate not accepted: ' + filename)
    for name in (record['signingEnvironment'], record['publicationEnvironment']):
        protected_environment(api, name)
    code = record['runNumber']
    origin = None
    if record['operation'] == 'publish':
        candidate = record['candidate']
        origin = run_identity(api, candidate['runId'], record['workflowId'], attempt=candidate['runAttempt'])
        require(origin['status'] == 'completed' and origin['conclusion'] == 'success', 'Prepare attempt not successful')
        code = origin['run_number']
    env = {'RELEASE_CHANNEL': record['channel'], 'RELEASE_TAG': context['tag'],
           'RELEASE_VERSION_CODE': str(code), 'RELEASE_PREVIOUS_VERSION_CODE': '0'}
    official_inputs(target, record['channel'], env)
    expected = {'source': {'sha': record['sourceSha'], 'tree': record['sourceTree']}, 'target': target,
                'channel': record['channel'], 'tag': context['tag'], 'versionName': context['tag'][1:],
                'versionCode': code, 'debugPackage': 'io.github.leugenea.codexbarmobile.dev',
                'releasePackage': 'io.github.leugenea.codexbarmobile'}
    return expected, origin


def prepared_tags(api, record, expected, accepted_runs, sdk, reports):
    """Retained official candidates reserve their tag; ordinary CI artifacts never allocate."""
    runs = api.pages('/actions/workflows/' + identifier(record['workflowId'])
                     + '/runs?event=workflow_dispatch', 'workflow_runs')
    own_run = record.get('candidate', {}).get('runId', record['runId'])
    for run in runs:
        if run['id'] in accepted_runs or run['id'] == own_run or run['status'] != 'completed':
            continue
        # Foreign-ref dispatches cannot enter the official main-only job boundary.
        if run['head_branch'] != 'main':
            continue
        require(run['path'] == WORKFLOW and run['event'] == 'workflow_dispatch'
                and run['repository']['id'] == record['repositoryId'], 'Unknown official candidate run')
        for artifact in api.pages('/actions/runs/' + identifier(run['id']) + '/artifacts', 'artifacts'):
            if not re.fullmatch(r'signed-' + str(run['id']) + r'-[1-9][0-9]*', artifact['name']):
                continue
            require(artifact['expired'] is False, 'Expired unresolved signed candidate; reconcile before another allocation')
            digest = artifact['digest']
            require(isinstance(digest, str) and re.fullmatch(r'sha256:[0-9a-f]{64}', digest), 'Candidate archive digest missing')
            raw = api.request('GET', api.prefix + '/actions/artifacts/' + identifier(artifact['id']) + '/zip', binary=True)
            require('sha256:' + fingerprint(raw)['sha256'] == digest, 'Retained candidate archive differs')
            candidate, _ = verify_assets(ROOT, members(raw), sdk, reports / identifier(artifact['id']),
                                         expected_certificate=record['certificateSha256'])
            origin = candidate['authority']
            require(origin['runId'] == run['id'] and origin['workflowId'] == record['workflowId']
                    and origin['repository'] == record['repository'] and origin['controllerSha'] == run['head_sha']
                    and artifact['name'] == f'signed-{run["id"]}-{origin["runAttempt"]}'
                    and candidate['buildInputs']['versionCode'] == run['run_number'], 'Retained candidate origin differs')
            require(candidate['buildInputs']['tag'] != expected['tag'], 'Tag already admitted by a different prepare run')


def history(api, record, expected, sdk, reports):
    """Draft-capable GET token required by caller. A failed/partial read is never empty history."""
    accepted, same = [], False
    accepted_runs = set()
    releases = api.releases()
    require(len({r['tag_name'] for r in releases}) == len(releases), 'Duplicate release tags')
    for release in releases:
        tag = release['tag_name']
        parse_tag(tag)  # Unknown repository release state must be reconciled, not ignored.
        require(type(release['draft']) is bool, 'Unknown draft state')
        if release['draft']:
            require(record['operation'] == 'publish' and tag == expected['tag'], 'Unresolved draft blocks official allocation')
            continue  # Exact draft candidate ownership/assets are checked by publisher before any mutation.
        assets = read_assets(api, release['id'])
        manifest, _ = verify_assets(ROOT, assets, sdk, reports / identifier(release['id']),
                                    expected_certificate=record['certificateSha256'])
        identity, authority = manifest['buildInputs'], manifest['authority']
        require(identity['tag'] == tag and tag_source(api, tag) == identity['source']['sha'], 'History tag/source drift')
        require(api.get('/git/commits/' + identity['source']['sha'])['tree']['sha'] == identity['source']['tree'], 'History tree differs')
        require(authority['repository'] == record['repository'] and authority['workflowId'] == record['workflowId'], 'History workflow reset')
        origin = run_identity(api, authority['runId'], record['workflowId'], sha=authority['controllerSha'],
                              number=identity['versionCode'], attempt=authority['runAttempt'])
        require(origin['status'] == 'completed' and origin['conclusion'] == 'success', 'History origin attempt not accepted')
        accepted_runs.add(authority['runId'])
        official_inputs(identity['target'], identity['channel'], {'RELEASE_TAG': tag,
                        'RELEASE_VERSION_CODE': str(identity['versionCode']), 'RELEASE_PREVIOUS_VERSION_CODE': '0'})
        release_binding(release, identity, marker(assets))
        if tag == expected['tag']:
            candidate = record.get('candidate', {'runId': record['runId']})
            require(identity == expected and authority['runId'] == candidate['runId'], 'Tag already belongs to a different allocation')
            if record['operation'] == 'publish':
                require(authority['runAttempt'] == candidate['runAttempt']
                        and manifest['bundleManifestSha256'] == candidate['bundleManifestSha256'], 'Accepted candidate differs')
            same = True
        accepted.append({'releaseId': release['id'], 'versionCode': identity['versionCode'],
                         'bundleManifestSha256': manifest['bundleManifestSha256'], 'tag': tag})
    codes = [r['versionCode'] for r in accepted]
    require(len(set(codes)) == len(codes), 'Reused official versionCode')
    older = [r for r in accepted if r['tag'] != expected['tag']]
    floor = max(older, key=lambda r: r['versionCode']) if older else None
    anchor = {'emptyHistory': True} if floor is None else {k: floor[k] for k in ('releaseId', 'versionCode', 'bundleManifestSha256')}
    require(record['previous'] == anchor, 'Accepted history anchor is stale/missing; owner reconciliation required')
    require(expected['versionCode'] > (floor['versionCode'] if floor else 0), 'Counter reset/non-increasing candidate')
    prepared_tags(api, record, expected, accepted_runs, sdk, reports / 'prepared')
    return (floor['versionCode'] if floor else 0), same


def authority(record, attempt):
    return {'repository': record['repository'], 'workflowId': record['workflowId'],
            'controllerSha': record['controllerSha'], 'runId': record['runId'], 'runAttempt': attempt}
