"""Static configuration, derived source/run admission and verified official Release history."""
import base64
import json
from pathlib import Path
import re
import urllib.parse

from historical_apk_contract import LEGACY_MANIFEST_SHA256, contract_for
from release_publication import history_body, identifier, notes_text, read_assets, release_binding, release_body, require
from release_runtime import fingerprint, load, members
from release_signing import _unique, verify_assets
from version_identity import MAX_CODE, official_inputs, parse_tag, target_version

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = '.github/workflows/release.yml'
SOURCE_GATES = ('android.yml', 'repository-policy.yml', 'research-contract.yml', 'codeql.yml')


def configuration(text, channel):
    from jsonschema import Draft7Validator
    require(channel in ('rc', 'stable'), 'Unknown release channel')
    require(isinstance(text, str) and bool(text.strip()), 'RELEASE_CONFIG is unset; release denied')
    config = json.loads(text, object_pairs_hook=_unique)
    Draft7Validator(load(ROOT / 'tools/build/schemas/release-config.schema.json')).validate(config)
    require(type(config['schema']) is int, 'Configuration schema must be a canonical integer')
    require(all(not isinstance(value, str) or (value == value.strip() and '\n' not in value and '\r' not in value)
                for value in config.values()), 'Configuration strings must be canonical single-line values')
    require(config[channel + 'Enabled'] is True, 'Release channel is disabled')
    return config


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
    require(len(reviewers) == 1 and bool(reviewers[0].get('reviewers')),
            'Environment reviewer protection required')
    require(environment['deployment_branch_policy'] == {'protected_branches': False, 'custom_branch_policies': True},
            'Environment needs exact main-only custom branch policy')
    policies = api.pages(path + '/deployment-branch-policies', 'branch_policies')
    require(len(policies) == 1 and policies[0]['name'] == 'main' and policies[0]['type'] == 'branch',
            'Environment ref restriction must be exactly branch main')


def admit(api, config, context):
    require(context['event'] == 'workflow_dispatch' and context['ref'] == 'refs/heads/main'
            and context['operation'] in ('prepare', 'publish')
            and context['repository'] == api.repository == 'leugenea/codexbar-mobile'
            and context['workflowSha'] == context['sha']
            and context['workflowRef'] == api.repository + '/' + WORKFLOW + '@refs/heads/main',
            'Untrusted event/controller')
    notes = notes_text(context.get('notes', ''))
    require(context['operation'] == 'publish' or not notes, 'Release notes are only valid for publish')
    require(context['operation'] != 'publish' or context['channel'] != 'stable' or notes,
            'Stable publication requires release notes')
    repository = api.get('')
    require(repository['id'] == context['repositoryId'] and repository['full_name'] == api.repository
            and repository['default_branch'] == 'main' and repository['fork'] is False, 'Repository identity differs')
    workflow = api.get('/actions/workflows/release.yml')
    require(workflow['path'] == WORKFLOW and workflow['state'] == 'active', 'Workflow identity inactive')
    run_identity(api, context['runId'], workflow['id'], sha=context['sha'], number=context['runNumber'])
    main = api.get('/git/ref/heads/main')['object']['sha']
    require(main == context['sha'], 'Controller is stale against current main')
    source = tag_source(api, context['tag'])
    tree = api.get('/git/commits/' + source)['tree']['sha']
    comparison = api.get('/compare/' + source + '...' + main)
    require(comparison['status'] in ('ahead', 'identical'), 'Source must be accepted main history')
    require(workflow_tree(api, source) == workflow_tree(api, main),
            'Workflow subtree drift requires Workflows-write; GITHUB_TOKEN release denied')
    content = api.get('/contents/VERSION?ref=' + source)
    require(content['encoding'] == 'base64', 'Unsupported VERSION encoding')
    target = target_version(base64.b64decode(content['content']).decode())
    # Exact-source main push gates, including the latest attempt outcome.
    for filename in SOURCE_GATES:
        runs = api.pages('/actions/workflows/' + filename + '/runs?head_sha=' + source + '&event=push', 'workflow_runs')
        valid = [r for r in runs if r['head_sha'] == source and r['head_branch'] == 'main'
                 and r['event'] == 'push' and r['path'] == '.github/workflows/' + filename
                 and r['repository']['id'] == context['repositoryId']]
        require(bool(valid), 'Exact-source main gates missing: ' + filename)
        latest = max(valid, key=lambda r: r['id'])
        require(latest['status'] == 'completed' and latest['conclusion'] == 'success', 'Source gate not accepted: ' + filename)
    for name in (config['signingEnvironment'], config['publicationEnvironment']):
        protected_environment(api, name)
    record = {**config, **context, 'controllerSha': main, 'workflowId': workflow['id'],
              'sourceSha': source, 'sourceTree': tree}
    code, origin = context['runNumber'], None
    if context['operation'] == 'publish':
        prepare = context['prepareRunId']
        require(isinstance(prepare, str) and re.fullmatch('[1-9][0-9]*', prepare), 'Canonical prepare_run_id required')
        origin = run_identity(api, int(prepare), workflow['id'])
        require(origin['id'] != context['runId'] and origin['status'] == 'completed' and origin['conclusion'] == 'success'
                and origin['display_title'] == f'prepare {context["channel"]} {context["tag"]}',
                'Successful prepare run for this tag/channel required')
        record['prepareRunId'] = origin['id']
        record['prepareAttempt'] = origin['run_attempt']
        code = origin['run_number']
    else:
        require(not context['prepareRunId'], 'prepare_run_id is only valid for publish')
    official_inputs(target, context['channel'], {'RELEASE_TAG': context['tag'],
                    'RELEASE_VERSION_CODE': str(code), 'RELEASE_PREVIOUS_VERSION_CODE': '0'})
    expected = {'source': {'sha': source, 'tree': tree}, 'target': target,
                'channel': context['channel'], 'tag': context['tag'], 'versionName': context['tag'][1:],
                'versionCode': code, 'debugPackage': 'io.github.leugenea.codexbarmobile.dev',
                'releasePackage': 'io.github.leugenea.codexbarmobile'}
    return record, expected, origin


def artifact_assets(api, artifact):
    require(artifact['expired'] is False, 'Expired unresolved signed candidate; reconcile before another allocation')
    digest = artifact['digest']
    require(isinstance(digest, str) and re.fullmatch('sha256:[0-9a-f]{64}', digest), 'Candidate archive digest missing')
    raw = api.request('GET', api.prefix + '/actions/artifacts/' + identifier(artifact['id']) + '/zip', binary=True)
    require('sha256:' + fingerprint(raw)['sha256'] == digest, 'Candidate archive differs')
    return members(raw)


def bound_contract(api, identity, tag):
    """Resolve independently before policy selection; asset identity is only a claim."""
    source = tag_source(api, tag)
    tree = api.get('/git/commits/' + source)['tree']['sha']
    require(identity['tag'] == tag and identity['source']['sha'] == source, 'History tag/source drift')
    require(identity['source'] == {'sha': source, 'tree': tree}, 'History tree differs')
    contract = contract_for(tag, identity['source'])
    if contract is not None:
        content = api.get('/contents/app/src/main/AndroidManifest.xml?ref=' + source)
        require(content['encoding'] == 'base64', 'Unsupported historical manifest encoding')
        raw = base64.b64decode(''.join(content['content'].split()), validate=True)
        require(fingerprint(raw)['sha256'] == LEGACY_MANIFEST_SHA256, 'Historical source manifest differs')
    return {'tag': tag, 'source': {'sha': source, 'tree': tree}}, contract


def verify_remote_assets(api, record, assets, sdk, reports, *, tag=None, expected_authority=None, expected_inputs=None):
    """Authenticate historical/retained source and origin before inspecting any APK."""
    claimed = json.loads(assets['release-manifest.json'], object_pairs_hook=_unique)
    identity, authority = claimed['buildInputs'], claimed['authority']
    if expected_inputs is not None:
        require(identity == expected_inputs, 'Prepare tag/source/channel/counter differs')
    if expected_authority is not None:
        require(authority == expected_authority, 'Release origin differs')
    require(authority['repository'] == record['repository'] and authority['workflowId'] == record['workflowId'],
            'History workflow reset')
    origin = run_identity(api, authority['runId'], record['workflowId'], sha=authority['controllerSha'],
                          number=identity['versionCode'], attempt=authority['runAttempt'])
    require(origin['status'] == 'completed' and origin['conclusion'] == 'success', 'History origin attempt not accepted')
    binding, contract = bound_contract(api, identity, identity['tag'] if tag is None else tag)
    manifest, _ = verify_assets(ROOT, assets, sdk, reports, expected_certificate=record['certificateSha256'],
                                expected_authority=authority, expected_binding=binding, apk_contract=contract)
    require(manifest['buildInputs'] == identity and manifest['authority'] == authority, 'Verified release identity differs')
    return manifest


def candidate_assets(api, record, expected, origin, sdk, reports):
    """Resolve the retained prepare artifact, then reverify its bytes and signed identity."""
    name = f'signed-{origin["id"]}-{origin["run_attempt"]}'
    artifacts = api.pages('/actions/runs/' + identifier(origin['id']) + '/artifacts', 'artifacts')
    matches = [a for a in artifacts if a['name'] == name]
    require(len(matches) == 1, 'Exactly one retained prepare artifact required')
    artifact = api.get('/actions/artifacts/' + identifier(matches[0]['id']))
    require(artifact['id'] == matches[0]['id'] and artifact['name'] == name and artifact['expired'] is False
            and artifact['workflow_run']['id'] == origin['id']
            and artifact['workflow_run']['head_sha'] == origin['head_sha'], 'Prepare artifact provenance differs/expired')
    assets = artifact_assets(api, artifact)
    expected_authority = {'repository': api.repository, 'workflowId': record['workflowId'],
                          'controllerSha': origin['head_sha'], 'runId': origin['id'], 'runAttempt': origin['run_attempt']}
    manifest = verify_remote_assets(api, record, assets, sdk, reports, expected_authority=expected_authority,
                                    expected_inputs=expected)
    require(manifest['buildInputs'] == expected, 'Prepare tag/source/channel/counter differs')
    return assets


def prepared_tags(api, record, expected, accepted_runs, sdk, reports):
    """Retained official candidates reserve their tag; ordinary CI artifacts never allocate."""
    runs = api.pages('/actions/workflows/' + identifier(record['workflowId'])
                     + '/runs?event=workflow_dispatch', 'workflow_runs')
    own_run = record.get('prepareRunId') or record['runId']
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
            expected_authority = {'repository': record['repository'], 'workflowId': record['workflowId'],
                                  'controllerSha': run['head_sha'], 'runId': run['id'],
                                  'runAttempt': int(artifact['name'].rsplit('-', 1)[1])}
            candidate = verify_remote_assets(api, record, artifact_assets(api, artifact), sdk,
                                              reports / identifier(artifact['id']), expected_authority=expected_authority)
            origin = candidate['authority']
            require(origin['runId'] == run['id'] and origin['workflowId'] == record['workflowId']
                    and origin['repository'] == record['repository'] and origin['controllerSha'] == run['head_sha']
                    and artifact['name'] == f'signed-{run["id"]}-{origin["runAttempt"]}'
                    and candidate['buildInputs']['versionCode'] == run['run_number'], 'Retained candidate origin differs')
            require(candidate['buildInputs']['tag'] != expected['tag'], 'Tag already admitted by a different prepare run')


def history(api, record, expected, sdk, reports):
    """Draft-capable GET token required by caller. A failed/partial read is never empty history."""
    accepted, same = {}, False
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
        manifest = verify_remote_assets(api, record, assets, sdk, reports / identifier(release['id']), tag=tag)
        identity, authority = manifest['buildInputs'], manifest['authority']
        accepted_runs.add(authority['runId'])
        official_inputs(identity['target'], identity['channel'], {'RELEASE_TAG': tag,
                        'RELEASE_VERSION_CODE': str(identity['versionCode']), 'RELEASE_PREVIOUS_VERSION_CODE': '0'})
        body = history_body(release['body'], assets)
        if record['operation'] == 'publish' and tag == expected['tag']:
            body = release_body(assets, record.get('notes', ''))
        release_binding(release, identity, body)
        if tag == expected['tag']:
            require(identity == expected and authority['runId'] == (record.get('prepareRunId') or record['runId']),
                    'Tag already belongs to a different allocation')
            # The verified manifest's successful exact origin attempt is authoritative;
            # a later prepare no-op rerun does not replace the published candidate.
            same = True
        accepted[tag] = identity['versionCode']
    codes = list(accepted.values())
    require(len(set(codes)) == len(codes), 'Reused official versionCode')
    floor = max((code for tag, code in accepted.items() if tag != expected['tag']), default=0)
    require(expected['versionCode'] > floor, 'Counter reset/non-increasing candidate')
    prepared_tags(api, record, expected, accepted_runs, sdk, reports / 'prepared')
    return floor, same


def authority(record, attempt):
    return {'repository': record['repository'], 'workflowId': record['workflowId'],
            'controllerSha': record['controllerSha'], 'runId': record['runId'], 'runAttempt': attempt}
