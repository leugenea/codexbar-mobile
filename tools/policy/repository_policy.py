#!/usr/bin/env python3
"""Semantic workflow/Dependabot policy; schema bytes are checksum verified.

Action-line scanning is adapted from leugenea/qmix public_readiness_test.py
at 0cc3f1eb8bfb1902ac8e2fae7fba729ef53cc9f0.
Copyright (c) 2026 QMix contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import urllib.request

import jsonschema
import yaml

ROOT = Path(__file__).resolve().parents[2]
SCHEMA_URL = (
    "https://raw.githubusercontent.com/SchemaStore/schemastore/"
    "b2454041c2a32171bd55f171cf506e9a5b91579b/src/schemas/json/dependabot-2.0.json"
)
SCHEMA_SHA256 = "57efbbcba05d2c26ad8e00b2ab295f797931652174d4e7d5272615069ecea61f"
ACTION_USE = re.compile(
    r"^\s*-?\s*uses:\s+(?P<reference>[^\s#]+)(?:\s+#\s+(?P<comment>\S+))?\s*$"
)
ACTION_REFERENCE = re.compile(
    r"[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*@[0-9a-fA-F]{40}"
)
# Single-quoted expression literals escape quotes by doubling them. Delimiters
# inside those literals must not end the expression or expose fake identifiers.
EXPRESSION = re.compile(r"\$\{\{((?:'(?:[^']|'')*'|[^'])*?)\}\}", re.DOTALL)
EXPRESSION_TOKEN = re.compile(r"'(?:[^']|'')*'|[A-Za-z_][A-Za-z0-9_-]*|[^\s]")
WRITERS = {
    "history": (
        {"contents": "write"},
        "${{ always() && github.event_name == 'push' && github.ref == 'refs/heads/main' && "
        "(needs.erosion.result == 'success' || needs.duplication.result == 'success') }}",
    ),
    "pages": (
        {"contents": "read", "pages": "write", "id-token": "write"},
        "${{ github.event_name == 'push' && github.ref == 'refs/heads/main' && needs.history.result == 'success' }}",
    ),
}


class PolicyLoader(yaml.SafeLoader):
    """YAML 1.2-style booleans keep GitHub's 'on' key intact; reject duplicates."""


PolicyLoader.yaml_implicit_resolvers = {
    key: [(tag, pattern) for tag, pattern in values if tag != "tag:yaml.org,2002:bool"]
    for key, values in yaml.SafeLoader.yaml_implicit_resolvers.items()
}
PolicyLoader.add_implicit_resolver("tag:yaml.org,2002:bool", re.compile(r"^(?:true|false)$"), list("tf"))


def unique_mapping(loader, node):
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node)
        require(key not in result, f"duplicate YAML key {key!r}")
        result[key] = loader.construct_object(value_node)
    return result


PolicyLoader.add_constructor("tag:yaml.org,2002:map", unique_mapping)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load_yaml(text):
    return yaml.load(text, Loader=PolicyLoader)


def workflow_files(directory):
    return sorted((*directory.glob("*.yml"), *directory.glob("*.yaml")))


def action_identity(reference):
    """Canonical owner/repository identity, independent of subpath and revision."""
    return "/".join(reference.split("@", 1)[0].split("/")[:2]).casefold()


def action_policy(text):
    """Offline pin/comment syntax, not proof of release-to-SHA correspondence."""
    lines = text.splitlines()
    # Semantic node locations also catch quoted keys and flow-style mappings;
    # text inside a run block is not an Action reference.
    pending = [yaml.compose(text, Loader=PolicyLoader)]
    visited = set()
    while pending:
        node = pending.pop()
        if node is None or id(node) in visited:
            continue
        visited.add(id(node))
        if isinstance(node, yaml.SequenceNode):
            pending.extend(node.value)
        elif isinstance(node, yaml.MappingNode):
            for key, value in node.value:
                pending.append(value)
                if key.value != "uses":
                    continue
                number = key.start_mark.line + 1
                match = ACTION_USE.fullmatch(lines[number - 1])
                if match is None:
                    raise ValueError(f"line {number}: malformed uses entry")
                reference = match.group("reference")
                require(ACTION_REFERENCE.fullmatch(reference)
                        and not {".", ".."}.intersection(reference.split("@", 1)[0].split("/")),
                        f"line {number}: external Action must use a full 40-character SHA")
                require(re.fullmatch(r"v\d+(?:\.\d+){0,2}", match.group("comment") or ""),
                        f"line {number}: Action needs an adjacent semver release comment")


def contains_key(value, key):
    if isinstance(value, dict):
        return any(str(name).casefold() == key.casefold() for name in value) or any(
            contains_key(child, key) for child in value.values()
        )
    if isinstance(value, list):
        return any(contains_key(child, key) for child in value)
    return False


def contains_secrets_context(value):
    """Inspect decoded YAML expressions, not comments, literals or property names."""
    if isinstance(value, dict):
        for name, child in value.items():
            # GitHub evaluates job/step if conditions even without ${{ }}.
            if str(name).casefold() == "if" and isinstance(child, str):
                child = "${{ " + child + " }}"
            if contains_secrets_context(name) or contains_secrets_context(child):
                return True
    if isinstance(value, list):
        return any(contains_secrets_context(child) for child in value)
    if isinstance(value, str):
        for expression in EXPRESSION.finditer(value):
            previous = None
            for token in EXPRESSION_TOKEN.findall(expression.group(1)):
                if token.casefold() == "secrets" and previous != ".":
                    return True
                previous = token
    return False


def workflow_policy(text, filename):
    workflow = load_yaml(text)
    require(isinstance(workflow, dict), "workflow must be a mapping")
    action_policy(text)
    require(workflow.get("permissions") == {"contents": "read"},
            "workflow permissions must explicitly grant only contents: read")
    triggers = workflow.get("on", {})
    require(isinstance(triggers, (dict, list, str)), "missing workflow events")
    require("pull_request_target" not in triggers, "pull_request_target is prohibited")
    if "pull_request" in triggers:
        require(not contains_secrets_context(workflow) and not contains_key(workflow, "secrets"),
                "PR workflow must not use secrets")
    jobs = workflow.get("jobs")
    require(isinstance(jobs, dict) and jobs, "workflow must have jobs")
    for name, job in jobs.items():
        require(isinstance(job, dict), f"{name}: job must be a mapping")
        runner = job.get("runs-on")
        # Explicit GitHub-hosted labels only: no self-hosted or dynamic labels.
        require(runner in ("ubuntu-24.04", "ubuntu-22.04", "ubuntu-latest", "windows-latest", "macos-latest"),
                f"{name}: runner must be an explicit GitHub-hosted label")
        permission = job.get("permissions", workflow["permissions"])
        if filename == "code-metrics.yml" and name in WRITERS:
            expected, condition = WRITERS[name]
            require(permission == expected, f"{name}: unexpected writer permissions")
            require(job.get("if") == condition, f"{name}: writer must run only on push to main")
        else:
            require(permission in ({}, {"contents": "read"}), f"{name}: job permissions must be read-only")
        for step in job.get("steps", []):
            if action_identity(str(step.get("uses", ""))) == "actions/checkout":
                require(step.get("with", {}).get("persist-credentials") is False,
                        f"{name}: checkout must use persist-credentials: false")


def dependabot_policy(config):
    require(isinstance(config, dict) and config.get("version") == 2, "Dependabot version must be 2")
    updates = config.get("updates")
    require(isinstance(updates, list) and updates, "Dependabot updates must be a non-empty list")
    required = {("github-actions", "/"), ("gradle", "/"), ("pip", "/.github"), ("pip", "/tools/research")}
    seen = set()
    for update in updates:
        require(isinstance(update, dict), "Dependabot update must be a mapping")
        key = update.get("package-ecosystem"), update.get("directory")
        require(key not in seen, f"duplicate Dependabot entry: {key}")
        seen.add(key)
        schedule = update.get("schedule", {})
        require(schedule.get("interval") == "weekly", f"{key}: weekly schedule required")
        limit = update.get("open-pull-requests-limit")
        require(type(limit) is int and 1 <= limit <= 3, f"{key}: open PR limit must be between 1 and 3")
        require(update.get("commit-message", {}).get("prefix") == {
            "github-actions": "chore(actions)", "gradle": "chore(gradle)", "pip": "chore(python)"
        }.get(key[0]), f"{key}: missing dependency commit prefix")
        groups = update.get("groups", {})
        require(isinstance(groups, dict) and groups, f"{key}: minor/patch group required")
        routine = False
        for group in groups.values():
            require(isinstance(group, dict), f"{key}: malformed dependency group")
            require(group.get("applies-to") == "version-updates", f"{key}: group must apply only to version updates")
            require(group.get("update-types") == ["minor", "patch"], f"{key}: majors must remain separate")
            require(group.get("patterns") == ["*"] and not group.get("exclude-patterns"),
                    f"{key}: routine group must cover every dependency")
            routine = True
        require(routine, f"{key}: minor/patch group required")
        require(not update.get("ignore") and not update.get("allow") and not update.get("exclude-paths"),
                f"{key}: updates must not exclude dependencies")
    require(required <= seen, f"missing required Dependabot entries: {sorted(required - seen)}")
    require("automerge" not in json.dumps(config).lower() and "auto-merge" not in json.dumps(config).lower(),
            "Dependabot automerge is prohibited")


def verified_schema(path=None):
    if path:
        data = Path(path).read_bytes()
    else:
        with urllib.request.urlopen(SCHEMA_URL, timeout=30) as response:
            data = response.read()
    require(hashlib.sha256(data).hexdigest() == SCHEMA_SHA256, "Dependabot schema checksum mismatch")
    schema = json.loads(data)
    jsonschema.Draft7Validator.check_schema(schema)
    return schema


def check_repository(root, schema):
    paths = workflow_files(root / ".github/workflows")
    require(paths, "no workflows found")
    for path in paths:
        try:
            workflow_policy(path.read_text(), path.name)
        except (ValueError, yaml.YAMLError) as error:
            raise ValueError(f"{path}: {error}") from error
    config = load_yaml((root / ".github/dependabot.yml").read_text())
    jsonschema.Draft7Validator(schema).validate(config)
    dependabot_policy(config)
    print(f"Repository policy passed: {len(paths)} workflows; Dependabot schema and semantics")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--schema", type=Path, help="local pinned schema (checksum still required)")
    args = parser.parse_args()
    check_repository(ROOT, verified_schema(args.schema))


if __name__ == "__main__":
    main()
