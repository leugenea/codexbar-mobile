"""Retain only the Gradle verification evidence for PR 259's Compose marker."""

import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.parse import unquote, urlsplit

GROUP = 'org.jetbrains.kotlin.plugin.compose'
MODULE = GROUP + '.gradle.plugin'
VERSION = '2.4.20'
COORDINATE = f'{GROUP}:{MODULE}:{VERSION}'
POM = f'{MODULE}-{VERSION}.pom'
REPOSITORIES = {
    'Google': 'https://dl.google.com/dl/android/maven2',
    'MavenRepo': 'https://repo.maven.apache.org/maven2',
    'Gradle Central Plugin Repository': 'https://plugins.gradle.org/m2',
}
URLS = {
    name: f'{base}/{GROUP.replace(".", "/")}/{MODULE}/{VERSION}/{POM}'
    for name, base in REPOSITORIES.items()
}
CHECKSUM = re.compile(r'Expected a sha256 checksum of ([0-9a-f]{64}) but was ([0-9a-f]{64})', re.I)
MAX_REPORT_BYTES = 8 * 1024 * 1024
MAX_POM_BYTES = 64 * 1024


class VerificationRows(HTMLParser):
    """Read only the fixed module, artifact link, repository and digest fields."""

    def __init__(self):
        super().__init__()
        self.cells = None
        self.links = []
        self.repository = None
        self.matches = []

    def handle_starttag(self, tag, attrs):
        if tag == 'tr':
            self.cells, self.links, self.repository = [], [], None
        elif self.cells is not None:
            if tag == 'td':
                self.cells.append('')
            if len(self.cells) == 2:
                attrs = dict(attrs)
                if tag == 'a':
                    self.links.append(attrs.get('href') or '')
                elif tag == 'div':
                    for name in REPOSITORIES:
                        if attrs.get('uk-tooltip') == f"title: From repository '{name}'":
                            self.repository = name

    def handle_data(self, data):
        if self.cells:
            self.cells[-1] += data

    def handle_endtag(self, tag):
        if tag != 'tr' or self.cells is None:
            return
        if (len(self.cells) == 3 and self.cells[0].strip() == COORDINATE
                and self.cells[1].strip().split()[:1] == [POM]):
            self.matches.append((self.repository, self.links[0] if self.links else '',
                                 CHECKSUM.findall(' '.join(self.cells[2].split()))))
        self.cells = None


def bounded_read(path, limit):
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    return data if len(data) <= limit else None


def linked_pom_sha256(link, gradle_home):
    if not link.startswith('file:///'):
        return None
    uri = urlsplit(link)
    if uri.netloc or uri.query or uri.fragment:
        return None
    candidate = Path(unquote(uri.path))
    cache = gradle_home / 'caches/modules-2/files-2.1' / GROUP / MODULE / VERSION
    # Gradle's file link may point to its cache entry or an ephemeral download file.
    in_cache = (candidate.parent.parent == cache and candidate.name == POM
                and re.fullmatch(r'[0-9a-f]{1,40}', candidate.parent.name))
    in_temp = (candidate.parent == gradle_home / '.tmp'
               and re.fullmatch(r'gradle_download-?\d+bin', candidate.name))
    if not (in_cache or in_temp) or candidate.resolve() != candidate or not candidate.is_file():
        return None
    data = bounded_read(candidate, MAX_POM_BYTES)
    return hashlib.sha256(data).hexdigest() if data is not None else None


def observed_resolution(log):
    """Record only known marker URLs seen in Gradle's log, not candidate URLs."""
    observations = []
    if log.is_file():
        with log.open(encoding='utf-8', errors='replace') as stream:
            for raw in stream:
                line = raw.rstrip('\r\n')
                for repository, url in URLS.items():
                    if line == f'Resource missing. [HTTP GET: {url}]':
                        event = 'missing'
                    elif line.startswith(f'Downloading {url} to '):
                        event = 'downloading'
                    else:
                        continue
                    item = {'repository': repository, 'url': url, 'event': event}
                    if item not in observations:
                        observations.append(item)
    return observations


def collect(workspace, gradle_home):
    reports = []
    for path in sorted(workspace.glob('build/reports/dependency-verification/at-*/dependency-verification-report.html')):
        if path.resolve() == path and re.fullmatch(r'at-\d+', path.parent.name) and path.is_file():
            reports.append(path)
    snippets = []
    for path in reports:
        data = bounded_read(path, MAX_REPORT_BYTES)
        if data is None:
            continue
        parser = VerificationRows()
        parser.feed(data.decode('utf-8', errors='replace'))
        parser.close()
        for repository, link, digests in parser.matches:
            pom_hash = linked_pom_sha256(link, gradle_home)
            snippets.append({
                'report': str(path.relative_to(workspace)),
                'repository': repository,
                'sha256_mismatches': [{'expected': expected.lower(), 'actual': actual.lower()}
                                      for expected, actual in digests],
                'report_linked_pom_sha256': pom_hash,
            })
    return {
        'coordinate': COORDINATE,
        'marker': POM,
        'report_status': ('marker_found' if snippets else
                          'marker_not_captured' if reports else 'report_not_found'),
        'report_marker_rows': snippets,
        'resolution_log_observations': observed_resolution(workspace / 'evidence/codeql-build.log'),
    }


def main():
    workspace = Path(os.environ['GITHUB_WORKSPACE']).resolve()
    evidence = collect(workspace, Path(os.environ['GRADLE_USER_HOME']).resolve())
    evidence['provenance'] = {key.lower(): os.environ[key] for key in (
        'GITHUB_REPOSITORY', 'GITHUB_EVENT_NAME', 'GITHUB_REF', 'GITHUB_RUN_ID',
        'GITHUB_RUN_ATTEMPT', 'GITHUB_SHA', 'COMPILE_OUTCOME',
    )}
    for label, revision in [('checkout_commit', 'HEAD'), ('checkout_tree', 'HEAD^{tree}')]:
        evidence['provenance'][label] = subprocess.check_output(
            ['git', 'rev-parse', revision], cwd=workspace, text=True).strip()
    output = Path(os.environ['RUNNER_TEMP']) / 'codeql-compose-marker'
    output.mkdir(mode=0o700, exist_ok=False)
    (output / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
