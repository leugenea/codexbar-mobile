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

    def test_exact_m1_base_plus_independently_approved_m2_delta(self):
        delta_path = ROOT / "docs/build/m2-coverage-integrity.json"
        delta = json.loads(delta_path.read_text())
        self.assertEqual(hashlib.sha256(delta_path.read_bytes()).hexdigest(),
                         "22fec472348cc62a2e774459ac7a9be3f482a7c9011e63e24f9ce278d0aeaf36")
        full = ET.fromstring(self.full_bytes)
        base_ids = {tuple(row[:4]) for row in self.record["artifacts"]}
        delta_ids = {tuple(row[:4]) for row in delta["artifacts"]}
        self.assertFalse(base_ids & delta_ids)
        self.assertEqual(len(delta_ids), 17)
        self.assertEqual(len({tuple(row[:3]) for row in delta["artifacts"]}), 9)
        pairs = []
        full_components = full.find(NS + "components")
        assert full_components is not None
        for component in full_components:
            for artifact in component:
                identity = [component.get(k) for k in ("group", "name", "version")] + [artifact.get("name")]
                self.assertEqual(artifact.tag, NS + "artifact")
                self.assertEqual(set(artifact.attrib), {"name"})
                self.assertEqual(len(artifact), 1)
                checksum = artifact[0]
                self.assertEqual(checksum.tag, NS + "sha256")
                self.assertEqual(set(checksum.attrib), {"value", "origin"})
                if tuple(identity) in delta_ids:
                    row = next(row for row in delta["artifacts"] if row[:4] == identity)
                    evidence = ("publisher SHA256 sidecar/module" if row[5] == "published-sha256" else
                                "weaker published SHA1 + freshly retrieved original SHA256")
                    self.assertEqual(checksum.get("origin"), f"Independent M2 audit: {evidence}; docs/build/m2-coverage-integrity.json; not authenticated publisher identity")
                pairs.append(identity + [checksum.get("value")])
        self.assertEqual(sorted(pairs), sorted(row[:5] for row in self.record["artifacts"] + delta["artifacts"]))
        self.assertEqual(len(pairs), len({tuple(p) for p in pairs}))
        self.assertEqual(len(full_components), 364 + 9)
        self.assertEqual(len(pairs), 602 + 17)
        self.assertEqual(hashlib.sha256(json.dumps(sorted(pairs), separators=(",", ":")).encode()).hexdigest(),
                         "ba94604993d8d03d66d6e8b48fcdeb258aec36b62c3e851da32a6bed064434a3")
        comparisons = {tuple(a[k] for k in ("group", "module", "version", "file")): a
                       for a in delta["publisherComparisons"]}
        tiers = {"published-sha256": 0, "published-sha1+original-sha256": 0}
        for row in delta["artifacts"]:
            tiers[row[5]] += 1
            comparison = comparisons[tuple(row[:4])]
            self.assertEqual(comparison["sha256"], row[4])
            self.assertEqual(comparison["trustTier"], row[5])
            self.assertEqual(comparison["url"], delta["repositories"][row[6][0]] + row[6][1])
            matching = [p for p in comparison["publisherChecksums"] if p.get("match") is True and
                        p["algorithm"] == ("sha256" if row[5] == "published-sha256" else "sha1")]
            self.assertEqual(len(matching), 1)
            proof = matching[0]
            self.assertEqual(proof["publisherValue"], proof["computedValue"])
            self.assertEqual(proof["url"], delta["repositories"][row[7][0][1]] + row[7][0][2])
            self.assertEqual(proof["publisherValue"], row[4] if row[5] == "published-sha256" else row[7][0][3])
        self.assertEqual(tiers, {"published-sha256": 7, "published-sha1+original-sha256": 10})

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
