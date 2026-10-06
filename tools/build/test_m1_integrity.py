"""Offline checks of the exact independently audited M1 import; no network/Gradle."""
import copy
import hashlib
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
NS = "{https://schema.gradle.org/dependency-verification}"
SEMANTIC_SHA256 = "f692e8c2fd402671343ed88c35324d6b95cbe4fe8239e801a19b48fa6c9ab594"
AUDIT_SHA256 = "f2f1e8ee295614be97737ea88fbe2639d6d2055135b75a544af4bc6c4946e4d7"
UNION_SEMANTIC_SHA256 = "ba94604993d8d03d66d6e8b48fcdeb258aec36b62c3e851da32a6bed064434a3"
# Exact approved JaCoCo delta audited in PR https://github.com/leugenea/codexbar-mobile/pull/17.
APPROVED_M2_PAIRS = (
    ("org.jacoco", "org.jacoco.agent", "0.8.15", "org.jacoco.agent-0.8.15-runtime.jar",
     "fb5b0036a0899ea97edfa0fc2c7985b55f3f7c5695028163e5e60d4f3cf6075d"),
    ("org.jacoco", "org.jacoco.agent", "0.8.15", "org.jacoco.agent-0.8.15.jar",
     "81607adaa5b03a687a04049dee6b07e9665708c3e6f7b0626892ca9659be587c"),
    ("org.jacoco", "org.jacoco.agent", "0.8.15", "org.jacoco.agent-0.8.15.pom",
     "47eb24b1577e473cbba634399bc56fe7b366b18401bf911d0d8771f56f228966"),
    ("org.jacoco", "org.jacoco.ant", "0.8.15", "org.jacoco.ant-0.8.15.jar",
     "54fb6af97c95352d7deeb49113d7ad63f190eaa8cc9ae8e068fdb2f2a6de009e"),
    ("org.jacoco", "org.jacoco.ant", "0.8.15", "org.jacoco.ant-0.8.15.pom",
     "96d1688a3e8723539de36eaeb3f0789ec05914585d69eae0f453c2924b559a0a"),
    ("org.jacoco", "org.jacoco.build", "0.8.15", "org.jacoco.build-0.8.15.pom",
     "3a2c89f80c2ab9caf1b403a62da4bcbd909ee52ea53747391267a5cdc968c123"),
    ("org.jacoco", "org.jacoco.core", "0.8.15", "org.jacoco.core-0.8.15.jar",
     "ade1e0fdb4cd80d8537b57c6edb70a1360c47fdc518c20c65314517479bc4d0d"),
    ("org.jacoco", "org.jacoco.core", "0.8.15", "org.jacoco.core-0.8.15.pom",
     "7b283b35bb2bda3b827b4da89ce968d257d1d98dd8594d7c8cb5b2f6d8cd9b15"),
    ("org.jacoco", "org.jacoco.report", "0.8.15", "org.jacoco.report-0.8.15.jar",
     "66a79e36edc77a1ebf6689e76a976e12ab4f1af515cbd09555840fba775714f4"),
    ("org.jacoco", "org.jacoco.report", "0.8.15", "org.jacoco.report-0.8.15.pom",
     "a1ea901ca04547f1dc24e75dde104b3a7171ff9d0c2b8f43b3153be09ac3c08a"),
    ("org.ow2.asm", "asm", "9.10.1", "asm-9.10.1.jar",
     "ed825d10ab1399c8c0cb669e688cf0c8c82629b4c8399b58352b68e92ca10fcb"),
    ("org.ow2.asm", "asm", "9.10.1", "asm-9.10.1.pom",
     "e1b1c3832ca3ac0a9e563c405f4c760118b5556a4920daee14058886197e094d"),
    ("org.ow2.asm", "asm-bom", "9.10.1", "asm-bom-9.10.1.pom",
     "709472d69cf785d4b679eee0e6e0575d4b104476ddf35265ef1117e73c129cd4"),
    ("org.ow2.asm", "asm-commons", "9.10.1", "asm-commons-9.10.1.jar",
     "6d0abefb7cbf972ea16edb37ec14835372505063a45f976ab7ea889ed9497895"),
    ("org.ow2.asm", "asm-commons", "9.10.1", "asm-commons-9.10.1.pom",
     "ceb2c43f3a273db88af8652d89ed6312f3e91a1a1e0ffc6f7823169278cf795c"),
    ("org.ow2.asm", "asm-tree", "9.10.1", "asm-tree-9.10.1.jar",
     "3dfb0d5b6a106cd40b5b250e39935fbf2f927f4477546a5369a3ac609cf0506b"),
    ("org.ow2.asm", "asm-tree", "9.10.1", "asm-tree-9.10.1.pom",
     "963eebdea6e2e1ff25c9068b64a238e1585c99f8acd455595ac159a7f52b6c41"),
)


def validate_metadata(xml, record):
    assert [node.tag for node in xml] == [NS + "configuration", NS + "components"]
    configuration = xml.find(NS + "configuration")
    assert [node.tag for node in configuration] == [NS + "verify-metadata", NS + "verify-signatures"]
    assert [node.text for node in configuration] == ["true", "false"]
    components = xml.find(NS + "components")
    assert len(components) == 364
    pairs = []
    tiers = {tuple(row[:4]): row[5] for row in record["artifacts"]}
    for component in components:
        assert component.tag == NS + "component"
        assert set(component.attrib) == {"group", "name", "version"}
        for artifact in component:
            assert artifact.tag == NS + "artifact" and set(artifact.attrib) == {"name"}
            assert len(artifact) == 1
            digest = artifact[0]
            assert digest.tag == NS + "sha256" and set(digest.attrib) == {"value", "origin"}
            identity = [component.get("group"), component.get("name"), component.get("version"), artifact.get("name")]
            tier = tiers[tuple(identity)]
            evidence = "publisher SHA256 sidecar/module" if tier == "published-sha256" else "weaker published SHA1 + freshly retrieved original SHA256"
            assert digest.get("origin") == f"Independent M1 audit: {evidence}; docs/build/m1-integrity.json; not authenticated publisher identity"
            pairs.append(identity + [digest.get("value")])
    assert len(pairs) == len({tuple(row) for row in pairs}) == 602
    assert sorted(pairs) == sorted(row[:5] for row in record["artifacts"])
    encoded = json.dumps(sorted(pairs), separators=(",", ":"), ensure_ascii=True).encode()
    assert hashlib.sha256(encoded).hexdigest() == SEMANTIC_SHA256


def validate_extended_metadata(xml, record):
    """Require exactly the M1 base plus the independently approved JaCoCo delta."""
    assert [node.tag for node in xml] == [NS + "configuration", NS + "components"]
    configuration = xml.find(NS + "configuration")
    assert [node.tag for node in configuration] == [NS + "verify-metadata", NS + "verify-signatures"]
    assert [node.text for node in configuration] == ["true", "false"]
    approved_m2 = {row[:4]: row[4] for row in APPROVED_M2_PAIRS}
    expected = {tuple(row[:4]): row[4] for row in record["artifacts"]}
    assert len(APPROVED_M2_PAIRS) == len(approved_m2) == 17
    assert expected.keys().isdisjoint(approved_m2)
    expected.update(approved_m2)
    pairs = {}
    component_ids = set()
    for component in xml.find(NS + "components"):
        assert component.tag == NS + "component"
        assert set(component.attrib) == {"group", "name", "version"}
        assert len(component) > 0
        component_id = tuple(component.get(key) for key in ("group", "name", "version"))
        assert component_id not in component_ids
        component_ids.add(component_id)
        for artifact in component:
            assert artifact.tag == NS + "artifact" and set(artifact.attrib) == {"name"}
            assert len(artifact) == 1
            checksum = artifact[0]
            assert checksum.tag == NS + "sha256" and set(checksum.attrib) == {"value", "origin"}
            value = checksum.get("value")
            assert len(value) == 64 and all(char in "0123456789abcdef" for char in value)
            identity = component_id + (artifact.get("name"),)
            assert identity not in pairs
            pairs[identity] = value
            if identity in approved_m2:
                evidence = ("publisher SHA256 sidecar/module" if identity[0] == "org.ow2.asm"
                            else "weaker published SHA1 + freshly retrieved original SHA256")
                assert checksum.get("origin") == f"Independent M2 audit: {evidence}; not authenticated publisher identity"
    assert pairs == expected
    encoded = json.dumps(sorted(identity + (value,) for identity, value in pairs.items()),
                         separators=(",", ":"), ensure_ascii=True).encode()
    assert hashlib.sha256(encoded).hexdigest() == UNION_SEMANTIC_SHA256


class AuditedImportTests(unittest.TestCase):
    def setUp(self):
        self.path = ROOT / "gradle/verification-metadata.xml"
        self.full_bytes = self.path.read_bytes()
        marker = b"   <!-- BEGIN independently audited M2 coverage delta -->\n"
        end = b"   <!-- END independently audited M2 coverage delta -->\n"
        prefix, tail = self.full_bytes.split(marker)
        _, suffix = tail.split(end)
        self.base_bytes = prefix + suffix
        self.xml = ET.fromstring(self.base_bytes)
        self.record = json.loads((ROOT / "docs/build/m1-integrity.json").read_text())

    def test_exact_approved_metadata_and_file_identity(self):
        validate_metadata(self.xml, self.record)
        self.assertEqual(self.record["auditManifest"]["sha256"], AUDIT_SHA256)
        self.assertEqual(self.record["importedMetadata"]["semanticPairSha256"], SEMANTIC_SHA256)
        self.assertEqual(self.record["importedMetadata"]["sha256"], hashlib.sha256(self.base_bytes).hexdigest())
        self.assertEqual(self.record["inputs"]["UNTRUSTED-discovered-verification-metadata.xml"]["sha256"],
                         "a083a574908401043606876d80a00e64b2ef4c1f1441945be30e03ebec6c6998")
        self.assertEqual(self.record["inputs"]["resolved-toolchain.json"]["sha256"],
                         "d0390bf6c3d3229b315cb84c3e8660644e9e70997d94b615621f5c662498cae5")

    def test_exact_m1_union_approved_m2_pairs_and_semantic_digest(self):
        validate_extended_metadata(ET.fromstring(self.full_bytes), self.record)

    def test_extended_metadata_rejects_unapproved_pair_sets(self):
        for mutation in ("m2-hash", "missing-m2-pair", "missing-m2-delta", "extra-component",
                         "extra-artifact", "m1-hash", "false-m2-origin"):
            with self.subTest(mutation=mutation):
                xml = ET.fromstring(self.full_bytes)
                components = xml.find(NS + "components")
                assert components is not None
                component = next(node for node in components if node.get("group") == "org.jacoco")
                artifact = component[0]
                if mutation == "m2-hash":
                    artifact[0].set("value", "0" * 64)
                elif mutation == "missing-m2-pair":
                    component.remove(artifact)
                elif mutation == "missing-m2-delta":
                    m2_ids = {row[:3] for row in APPROVED_M2_PAIRS}
                    for node in list(components):
                        if tuple(node.get(key) for key in ("group", "name", "version")) in m2_ids:
                            components.remove(node)
                elif mutation == "extra-component":
                    extra = ET.SubElement(components, NS + "component", group="unaudited", name="extra", version="1")
                    extra.append(copy.deepcopy(artifact))
                elif mutation == "extra-artifact":
                    extra = copy.deepcopy(artifact)
                    extra.set("name", "unaudited.jar")
                    component.append(extra)
                elif mutation == "m1-hash":
                    components[0][0][0].set("value", "0" * 64)
                else:
                    artifact[0].set("origin", "authenticated publisher signature")
                with self.assertRaises(AssertionError):
                    validate_extended_metadata(xml, self.record)

    def test_extended_metadata_rejects_base_changes_and_broadened_trust(self):
        for mutation in ("base-hash", "missing-base", "sha1", "extra-hash", "bad-hash",
                         "trusted-artifacts", "ignored-keys", "artifact-ignore", "metadata-off",
                         "empty-component", "duplicate-artifact"):
            with self.subTest(mutation=mutation):
                xml = ET.fromstring(self.full_bytes)
                config = xml.find(NS + "configuration")
                components = xml.find(NS + "components")
                assert config is not None and components is not None
                artifact = components[-1][0]
                if mutation == "base-hash":
                    components[0][0][0].set("value", "0" * 64)
                elif mutation == "missing-base":
                    components.remove(components[0])
                elif mutation == "sha1":
                    artifact[0].tag = NS + "sha1"
                elif mutation == "extra-hash":
                    ET.SubElement(artifact, NS + "sha256", value="0" * 64)
                elif mutation == "bad-hash":
                    artifact[0].set("value", "not-a-sha256")
                elif mutation in ("trusted-artifacts", "ignored-keys"):
                    ET.SubElement(config, NS + mutation)
                elif mutation == "artifact-ignore":
                    ET.SubElement(artifact, NS + "ignored-keys")
                elif mutation == "metadata-off":
                    config[0].text = "false"
                elif mutation == "empty-component":
                    for child in list(components[-1]):
                        components[-1].remove(child)
                else:
                    components[-1].append(copy.deepcopy(artifact))
                with self.assertRaises(AssertionError):
                    validate_extended_metadata(xml, self.record)

    def test_per_artifact_sources_and_honest_trust_tiers(self):
        counts = {"published-sha256": 0, "published-sha1+original-sha256": 0}
        repositories = self.record["repositories"]
        self.assertEqual(repositories, {"google": "https://dl.google.com/dl/android/maven2/",
                                        "central": "https://repo.maven.apache.org/maven2/"})
        for row in self.record["artifacts"]:
            self.assertEqual(len(row), 8)
            group, module, version, filename, digest, tier, original, proofs = row
            counts[tier] += 1
            self.assertRegex(digest, r"^[a-f0-9]{64}$")
            self.assertIn(original[0], repositories)
            # Published Gradle module file.name may differ from file.url (e.g. ui.aar).
            # Keep the audited identity verbatim; do not manufacture a Maven filename.
            self.assertTrue(original[1].startswith(group.replace(".", "/") + "/" + module + "/" + version + "/"))
            self.assertNotIn("..", original[1].split("/"))
            self.assertTrue(proofs)
            for proof in proofs:
                method, repository, path = proof[:3]
                self.assertIn(repository, repositories)
                self.assertFalse(path.startswith("/"))
                self.assertNotIn("..", path.split("/"))
                if tier == "published-sha256":
                    self.assertEqual(len(proof), 3)
                    self.assertIn(method, ("publisher_sha256_sidecar", "publisher_module_sha256"))
                    self.assertTrue(path.endswith(".sha256" if method == "publisher_sha256_sidecar" else ".module"))
                else:
                    self.assertEqual(len(proof), 4)
                    self.assertEqual(method, "publisher_sha1_sidecar")
                    self.assertTrue(path.endswith(".sha1"))
                    self.assertRegex(proof[3], r"^[a-f0-9]{40}$")
        self.assertEqual(counts, {"published-sha256": 373, "published-sha1+original-sha256": 229})
        self.assertEqual(self.record["counts"]["authenticated_signatures"], 0)
        self.assertEqual(self.record["counts"]["signature_availability"], {
            "present_not_authenticated": 554, "not_published_at_official_artifact_signature_endpoint": 48})
        self.assertEqual(self.record["counts"]["artifact_checksum_failures"], 0)
        self.assertEqual(self.record["counts"]["receipt_rows"], 824)
        self.assertEqual(self.record["counts"]["receipt_unique_identities"], 210)
        self.assertEqual(self.record["loadedKgp"]["receipt"], {
            "file": "kotlin-gradle-plugin-2.4.20-gradle96.jar",
            "sha256": "6f49688f2c6715d60f5dc52d4c1ada992ce4bed6053d1bd6e579c9392d7ba0ed"})

    def test_rejects_checksum_changes_extra_trust_and_verification_weakening(self):
        for mutation in ("hash", "extra_hash", "broad_trust", "ignore", "metadata_off", "false_origin"):
            with self.subTest(mutation=mutation):
                xml = copy.deepcopy(self.xml)
                config = xml.find(NS + "configuration")
                components = xml.find(NS + "components")
                assert config is not None and components is not None
                artifact = components[0][0]
                if mutation == "hash":
                    artifact[0].set("value", "0" * 64)
                elif mutation == "extra_hash":
                    ET.SubElement(artifact, NS + "sha256", value="0" * 64)
                elif mutation == "broad_trust":
                    ET.SubElement(config, NS + "trusted-artifacts")
                elif mutation == "ignore":
                    ET.SubElement(config, NS + "ignored-keys")
                elif mutation == "metadata_off":
                    config[0].text = "false"
                else:
                    artifact[0].set("origin", "authenticated publisher signature")
                with self.assertRaises(AssertionError):
                    validate_metadata(xml, self.record)


if __name__ == "__main__":
    unittest.main()
