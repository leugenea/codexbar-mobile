"""Offline strict-verification contracts; approved identities live only in the XML."""
import copy
import difflib
import hashlib
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
NS = "{https://schema.gradle.org/dependency-verification}"
METADATA_PATH = "gradle/verification-metadata.xml"
# Full reviewed union: M1's 602 pairs + the 17 JaCoCo pairs approved in PR #17.
# Dependency updates must review the XML pair delta and update this digest in
# the same PR. No parallel artifact list or audit receipt is maintained.
APPROVED_METADATA_SHA256 = "ba94604993d8d03d66d6e8b48fcdeb258aec36b62c3e851da32a6bed064434a3"
APPROVED_ORIGINS = {
    f"Independent {milestone} audit: {evidence}; not authenticated publisher identity"
    for milestone in ("M1", "M2")
    for evidence in ("publisher SHA256 sidecar/module",
                     "weaker published SHA1 + freshly retrieved original SHA256")
}


def metadata_pairs(xml, *, check_origin=True):
    """Reject incomplete hashes, broadened trust and ambiguous identities."""
    assert xml.tag == NS + "verification-metadata"
    assert xml.attrib == {
        "{http://www.w3.org/2001/XMLSchema-instance}schemaLocation":
        "https://schema.gradle.org/dependency-verification "
        "https://schema.gradle.org/dependency-verification/dependency-verification-1.4.xsd"
    }
    assert [node.tag for node in xml] == [NS + "configuration", NS + "components"]
    configuration, components = xml
    assert not configuration.attrib and not components.attrib
    assert [node.tag for node in configuration] == [NS + "verify-metadata", NS + "verify-signatures"]
    assert [node.text for node in configuration] == ["true", "false"]
    assert all(not node.attrib and not len(node) for node in configuration)
    pairs = []
    component_ids, artifact_ids = set(), set()
    for component in components:
        assert component.tag == NS + "component"
        assert set(component.attrib) == {"group", "name", "version"}
        assert len(component) > 0
        component_id = tuple(component.get(key) for key in ("group", "name", "version"))
        assert all(component_id) and component_id not in component_ids
        component_ids.add(component_id)
        for artifact in component:
            assert artifact.tag == NS + "artifact" and set(artifact.attrib) == {"name"}
            assert artifact.get("name") and len(artifact) == 1
            checksum = artifact[0]
            assert checksum.tag == NS + "sha256" and set(checksum.attrib) == {"value", "origin"}
            assert not len(checksum)
            value = checksum.get("value")
            assert len(value) == 64 and all(char in "0123456789abcdef" for char in value)
            if check_origin:
                assert checksum.get("origin") in APPROVED_ORIGINS
            identity = component_id + (artifact.get("name"),)
            assert identity not in artifact_ids
            artifact_ids.add(identity)
            pairs.append(identity + (value,))
    return sorted(pairs)


def semantic_digest(pairs):
    encoded = json.dumps(sorted(pairs), separators=(",", ":"), ensure_ascii=True).encode()
    return hashlib.sha256(encoded).hexdigest()


def approved_reference_pairs():
    """Best-effort diagnostics only; the digest gate never depends on Git history.

    HEAD covers uncommitted edits, HEAD^ the PR merge/base or previous push.
    The checkpoint checkout retains its parent for this diagnostic. Never call
    an unaudited reference the baseline, even if it happens to exist locally.
    """
    for ref in ("HEAD", "HEAD^"):
        try:
            data = subprocess.check_output(
                ["git", "show", f"{ref}:{METADATA_PATH}"], cwd=ROOT,
                stderr=subprocess.DEVNULL, timeout=5)
            # Historical origin annotations are not executable trust policy.
            pairs = metadata_pairs(ET.fromstring(data), check_origin=False)
            if semantic_digest(pairs) == APPROVED_METADATA_SHA256:
                return pairs
        except (subprocess.SubprocessError, OSError, ET.ParseError, AssertionError):
            continue
    return None


def integrity_failure(pairs, baseline_pairs):
    digest = semantic_digest(pairs)
    lines = [
        f"Unapproved dependency identities/checksums: new semantic SHA-256={digest}",
        "Review every added/removed (group, name, version, file, sha256) pair against publisher sources.",
        "Only after approval, update APPROVED_METADATA_SHA256 in tools/build/test_m1_integrity.py",
        "in the same dependency PR (including Dependabot); never regenerate a receipt or auto-approve.",
    ]
    if baseline_pairs is None:
        lines.append("Approved Git baseline unavailable; fetch the reviewed base into HEAD^ to print its pair diff.")
    else:
        rows = lambda values: [json.dumps(row, separators=(",", ":")) + "\n" for row in sorted(values)]
        lines.append("Pair diff (- approved / + candidate):\n" + "".join(difflib.unified_diff(
            rows(baseline_pairs), rows(pairs), fromfile="approved pairs", tofile="candidate pairs")))
    return "\n".join(lines)


def validate_metadata(xml, *, baseline_pairs=None):
    pairs = metadata_pairs(xml)
    if semantic_digest(pairs) != APPROVED_METADATA_SHA256:
        if baseline_pairs is None:
            baseline_pairs = approved_reference_pairs()
        raise AssertionError(integrity_failure(pairs, baseline_pairs))
    return pairs


class AuditedImportTests(unittest.TestCase):
    def setUp(self):
        self.xml = ET.parse(ROOT / METADATA_PATH).getroot()
        self.pairs = metadata_pairs(self.xml)

    def test_exact_approved_full_identity_checksum_set(self):
        validate_metadata(self.xml)

    def test_semantic_digest_ignores_order_and_xml_formatting(self):
        xml = copy.deepcopy(self.xml)
        components = xml[1]
        components[:] = reversed(components)
        for component in components:
            component[:] = reversed(component)
        ET.indent(xml, space="    ")
        self.assertEqual(validate_metadata(xml), self.pairs)

    def test_rejects_changed_sha_removed_pair_and_unaudited_additions(self):
        for mutation in ("base-hash", "coverage-hash", "missing-pair", "missing-component",
                         "extra-component", "extra-artifact", "missing-coverage"):
            with self.subTest(mutation=mutation):
                xml = copy.deepcopy(self.xml)
                components = xml[1]
                coverage = next(node for node in components if node.get("group") == "org.jacoco")
                artifact = components[0][0]
                if mutation == "base-hash":
                    artifact[0].set("value", "0" * 64)
                elif mutation == "coverage-hash":
                    coverage[0][0].set("value", "0" * 64)
                elif mutation == "missing-pair":
                    coverage.remove(coverage[0])
                elif mutation == "missing-component":
                    components.remove(components[0])
                elif mutation == "extra-component":
                    extra = copy.deepcopy(components[0])
                    extra.set("group", "unaudited")
                    components.append(extra)
                elif mutation == "extra-artifact":
                    extra = copy.deepcopy(artifact)
                    extra.set("name", "unaudited.jar")
                    components[0].append(extra)
                else:
                    for node in list(components):
                        if node.get("group") == "org.jacoco":
                            components.remove(node)
                with self.assertRaisesRegex(AssertionError, "Unapproved dependency identities/checksums"):
                    validate_metadata(xml, baseline_pairs=self.pairs)

    def test_rejects_incomplete_hashes_ambiguous_ids_and_broadened_trust(self):
        for mutation in ("sha1", "no-hash", "extra-hash", "bad-hash", "trusted-artifacts", "ignored-keys",
                         "artifact-ignore", "metadata-off", "signatures-on", "empty-component",
                         "duplicate-component", "duplicate-artifact", "false-origin", "config-attributes"):
            with self.subTest(mutation=mutation):
                xml = copy.deepcopy(self.xml)
                config, components = xml
                artifact = components[0][0]
                if mutation == "sha1":
                    artifact[0].tag = NS + "sha1"
                elif mutation == "no-hash":
                    artifact.remove(artifact[0])
                elif mutation == "extra-hash":
                    artifact.append(copy.deepcopy(artifact[0]))
                elif mutation == "bad-hash":
                    artifact[0].set("value", "not-a-sha256")
                elif mutation in ("trusted-artifacts", "ignored-keys"):
                    ET.SubElement(config, NS + mutation)
                elif mutation == "artifact-ignore":
                    ET.SubElement(artifact, NS + "ignored-keys")
                elif mutation == "metadata-off":
                    config[0].text = "false"
                elif mutation == "signatures-on":
                    config[1].text = "true"
                elif mutation == "empty-component":
                    components[0][:] = []
                elif mutation == "duplicate-component":
                    components.append(copy.deepcopy(components[0]))
                elif mutation == "duplicate-artifact":
                    components[0].append(copy.deepcopy(artifact))
                elif mutation == "false-origin":
                    artifact[0].set("origin", "authenticated publisher signature")
                else:
                    config[0].set("enabled", "false")
                with self.assertRaises(AssertionError):
                    validate_metadata(xml, baseline_pairs=self.pairs)

    def test_digest_failure_prints_new_digest_review_instructions_and_pair_delta(self):
        xml = copy.deepcopy(self.xml)
        checksum = xml[1][0][0][0]
        old_sha = checksum.get("value")
        checksum.set("value", "0" * 64)
        with self.assertRaises(AssertionError) as result:
            validate_metadata(xml, baseline_pairs=self.pairs)
        message = str(result.exception)
        self.assertIn(semantic_digest(metadata_pairs(xml)), message)
        self.assertIn(old_sha, message)
        self.assertIn("0" * 64, message)
        self.assertIn("--- approved pairs", message)
        self.assertIn("+++ candidate pairs", message)
        self.assertIn("same dependency PR (including Dependabot)", message)

    def test_git_baseline_lookup_is_diagnostic_only_and_rejects_unaudited_refs(self):
        original = ET.tostring(self.xml)
        xml = copy.deepcopy(self.xml)
        xml[1][0][0][0].set("value", "0" * 64)
        with patch.object(subprocess, "check_output", side_effect=[ET.tostring(xml), original]):
            self.assertEqual(approved_reference_pairs(), self.pairs)
        with patch.object(subprocess, "check_output", side_effect=OSError("no git history")):
            validate_metadata(self.xml)
            with self.assertRaisesRegex(AssertionError, "Approved Git baseline unavailable"):
                validate_metadata(xml)


if __name__ == "__main__":
    unittest.main()
