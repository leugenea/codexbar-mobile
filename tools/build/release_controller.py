"""Trusted manual workflow entrypoints. Source compilation runs only in the unprivileged job."""
import argparse
import os
from pathlib import Path
import shutil

import release_artifact
import release_bundle
import release_policy as policy
from release_publication import GitHub, identifier, publish, require
from release_runtime import canonical, fingerprint, load, members
from release_signing import asset_names, materialize, package_assets, sign, verify_assets
from version_identity import resolve


def context(env):
    return {'repository': env['GITHUB_REPOSITORY'], 'repositoryId': int(env['GITHUB_REPOSITORY_ID']),
            'event': env['GITHUB_EVENT_NAME'], 'ref': env['GITHUB_REF'], 'sha': env['GITHUB_SHA'],
            'workflowSha': env['GITHUB_WORKFLOW_SHA'], 'workflowRef': env['GITHUB_WORKFLOW_REF'],
            'runId': int(env['GITHUB_RUN_ID']), 'runNumber': int(env['GITHUB_RUN_NUMBER']),
            'operation': env['REQUEST_OPERATION'], 'channel': env['REQUEST_CHANNEL'],
            'tag': env['REQUEST_TAG'], 'prepareRunId': env.get('REQUEST_PREPARE_RUN_ID', ''),
            'notes': env.get('REQUEST_NOTES', '')}


def admitted(env, *, reader=True):
    ctx = context(env)
    config = policy.configuration(env.get('RELEASE_CONFIG', ''), ctx['channel'])
    api = GitHub(ctx['repository'], env['GH_TOKEN'], read_only=reader)
    record, expected, origin = policy.admit(api, config, ctx)
    return api, record, expected, origin


def output(**values):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
        for name, value in values.items():
            text = str(value)
            require('\n' not in text and '\r' not in text, 'Unsafe workflow output')
            stream.write(name + '=' + text + '\n')


def download_artifact(api, artifact_id, digest, run_id, attempt, name, controller_sha, destination):
    record = api.get('/actions/artifacts/' + identifier(artifact_id))
    require(record['id'] == artifact_id and record['name'] == name and record['expired'] is False
            and record['digest'] == 'sha256:' + digest and record['workflow_run']['id'] == run_id
            and record['workflow_run']['head_sha'] == controller_sha, 'Artifact provenance differs/expired')
    # Exact per-attempt name + exact successful/run attempt join, not a latest-name lookup.
    require(name.endswith('-' + str(run_id) + '-' + str(attempt)), 'Artifact attempt binding differs')
    raw = api.request('GET', api.prefix + '/actions/artifacts/' + identifier(artifact_id) + '/zip', binary=True)
    require(fingerprint(raw)['sha256'] == digest, 'Downloaded Actions artifact digest differs')
    files = members(raw)
    require(not destination.exists(), 'Artifact destination must be new')
    destination.mkdir(parents=True)
    for name, data in files.items():
        path = destination / name  # members() has already rejected traversal/symlinks/duplicates.
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    return files


def build_unsigned(source, expected, destination, sdk, reports, run_id, attempt):
    # Gradle was run by the credential-free workflow step; never run it in the signer.
    require(resolve(source, expected_sha=expected['source']['sha']) == expected, 'Compiled source identity differs')
    destination.mkdir(parents=True)
    name = f'codexbar-mobile-{expected["versionName"]}-{expected["versionCode"]}-unsigned.apk'
    apk = destination / name
    shutil.copyfile(source / 'app/build/outputs/apk/release/app-release-unsigned.apk', apk)
    manifest = destination / 'artifact-manifest.json'
    manifest.write_bytes(canonical(release_artifact.inventory(apk, expected, run_id, attempt, sdk, reports / 'inspection')))
    args = (source, expected, apk, manifest, source / 'app/build/runtime-release/runtime-resolution.json', sdk, reports)
    release_bundle.write_bundle(destination / 'bundle', release_bundle.unsigned_files(*args), [apk, manifest, reports])
    release_bundle.verify_files(destination / 'bundle', release_bundle.unsigned_files(*args))
    return fingerprint((destination / 'bundle/bundle-manifest.json').read_bytes())['sha256']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('admit', 'remote-state', 'unsigned-inputs', 'unsigned', 'sign', 'publish'))
    parser.add_argument('--source', type=Path)
    parser.add_argument('--work', type=Path, required=True)
    args = parser.parse_args()
    env = os.environ
    args.work.mkdir(parents=True, exist_ok=True)
    sdk = Path(env.get('ANDROID_HOME', '.'))
    if args.operation in ('admit', 'remote-state'):
        output(allow='false')
    api, record, expected, origin = admitted(env, reader=args.operation != 'publish')
    attempt = int(env['GITHUB_RUN_ATTEMPT'])
    if args.operation == 'admit':
        output(allow='true', signing_environment=record['signingEnvironment'],
               publication_environment=record['publicationEnvironment'])
        with open(env['GITHUB_STEP_SUMMARY'], 'a') as stream:
            summary = {key: expected[key] for key in ('channel', 'tag', 'source', 'versionCode')}
            summary['notes'] = record['notes'].strip()
            stream.write('## Owner confirmation — check before Approve\n```json\n' + canonical(summary).decode() + '```\n')
        print('Owner confirmation:', canonical(summary).decode())
        return
    if args.operation == 'remote-state':
        # This GET-only helper has a technically Contents-write job token for draft visibility.
        floor, same = policy.history(api, record, expected, sdk, args.work / 'history')
        if origin is not None and not same:
            policy.candidate_assets(api, record, expected, origin, sdk, args.work / 'candidate-verification')
        output(allow='true', previous=str(floor), noop=str(same).lower())
        return
    previous = env.get('ACCEPTED_PREVIOUS_CODE', '')
    require(previous.isdecimal() and int(previous) < expected['versionCode'], 'Missing admitted history floor')
    if args.operation == 'unsigned-inputs':
        output(source=record['sourceSha'], code=expected['versionCode'])
        with open(env['GITHUB_ENV'], 'a') as stream:
            stream.write(f'RELEASE_CHANNEL={expected["channel"]}\nRELEASE_TAG={expected["tag"]}\n'
                         f'RELEASE_VERSION_CODE={expected["versionCode"]}\nRELEASE_PREVIOUS_VERSION_CODE={previous}\n')
        return
    if args.operation == 'unsigned':
        digest = build_unsigned(args.source, expected, args.work / 'unsigned', sdk, args.work / 'reports',
                                env['GITHUB_RUN_ID'], env['GITHUB_RUN_ATTEMPT'])
        output(bundle_digest=digest)
        return
    if args.operation == 'sign':
        # The draft-capable remote-state job already admitted this serialized operation.
        # Reverify accepted/public history at key use with the signer's read-only token;
        # do not claim this token independently proves draft absence.
        current_floor, already_accepted = policy.history(api, record, expected, sdk, args.work / 'sign-history')
        require(current_floor == int(previous) and not already_accepted, 'History changed before signing')
        unsigned = args.work / 'download'
        download_artifact(api, int(env['UNSIGNED_ARTIFACT_ID']), env['UNSIGNED_ARTIFACT_DIGEST'], record['runId'], attempt,
                          f'unsigned-{record["runId"]}-{attempt}', record['controllerSha'], unsigned)
        apk_name, _ = asset_names(expected)
        unsigned_apk = unsigned / apk_name.replace('.apk', '-unsigned.apk')
        receipt = load(unsigned / 'artifact-manifest.json')
        require(receipt['runId'] == str(record['runId']) and receipt['runAttempt'] == str(attempt), 'Unsigned origin differs')
        signed_apk = args.work / apk_name
        final_bundle = args.work / 'final-bundle'
        # Sign secrets are step-scoped; all private files are removed before any artifact upload.
        with materialize(env, env['RUNNER_TEMP']) as key:
            sign(policy.ROOT, expected, unsigned_apk, unsigned / 'bundle', env['UNSIGNED_BUNDLE_DIGEST'],
                 record['certificateSha256'], sdk, args.work / 'inspection', signed_apk, final_bundle, key)
        assets = package_assets(signed_apk, final_bundle, policy.authority(record, attempt))
        verify_assets(policy.ROOT, assets, sdk, args.work / 'verify', expected_certificate=record['certificateSha256'])
        destination = args.work / 'candidate'
        destination.mkdir()
        for name, data in assets.items():
            (destination / name).write_bytes(data)
        return
    _, accepted = policy.history(api, record, expected, sdk, args.work / 'publish-history')
    if accepted:
        matches = [r for r in api.releases() if r['tag_name'] == expected['tag'] and r['draft'] is False]
        require(len(matches) == 1, 'Accepted Release changed during no-op readback')
        if expected['channel'] == 'stable':
            require(api.get('/releases/latest')['id'] == matches[0]['id'], 'Accepted stable latest readback differs')
        print('Exact accepted Release verified without rebuilding/replacing:', matches[0]['id'])
        return
    assets = policy.candidate_assets(api, record, expected, origin, sdk, args.work / 'verify')
    def recheck():
        fresh_api, fresh_record, fresh_expected, _ = admitted(env, reader=False)
        require(fresh_record == record and fresh_expected == expected, 'Authorization drift')
        policy.history(fresh_api, record, expected, sdk, args.work / 'current-history')
    release_id = publish(api, expected, assets, recheck, notes=record['notes'])
    print('Exact Release ID verified:', release_id)


if __name__ == '__main__':
    main()
