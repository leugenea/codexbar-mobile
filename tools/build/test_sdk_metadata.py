"""Offline regressions for installed SDK metadata; fixtures are synthetic."""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from write_sdk_package_metadata import COMMON, SDK, XSI, local_package_xml, main

ROOT = Path(__file__).resolve().parents[2]
BASELINE = ROOT / "docs/research/m0/toolchain.json"


def synthetic_repository():
    repository = ET.Element("{" + SDK + "}sdk-repository")
    ET.SubElement(repository, "license", {"id": "android-sdk-license", "type": "text"}).text = "Synthetic license only"
    for path, selected in json.loads(BASELINE.read_text())["sdkPackages"].items():
        remote = ET.SubElement(repository, "remotePackage", {"path": path})
        platform = path.startswith("platforms;")
        details = ET.SubElement(remote, "type-details", {"{" + XSI + "}type":
                                "sdk:platformDetailsType" if platform else "generic:genericDetailsType"})
        if platform:
            for key, value in (("api-level", "37.0"), ("codename", ""), ("extension-level", "22"), ("base-extension", "true")):
                ET.SubElement(details, key).text = value
            ET.SubElement(details, "layoutlib", {"api": "15"})
        revision = ET.SubElement(remote, "revision")
        for name, number in zip(("major", "minor", "micro"), selected["revision"].split(".")):
            ET.SubElement(revision, name).text = number
        ET.SubElement(remote, "display-name").text = "Synthetic " + path
        ET.SubElement(remote, "uses-license", {"ref": "android-sdk-license"})
        ET.SubElement(remote, "channelRef", {"ref": "channel-0"})
        archives = ET.SubElement(remote, "archives")
        for selected_archive in selected["archives"]:
            archive = ET.SubElement(archives, "archive")
            complete = ET.SubElement(archive, "complete")
            ET.SubElement(complete, "url").text = selected_archive["archive"]
            ET.SubElement(complete, "checksum", {"type": selected_archive["checksumAlgorithm"]}).text = selected_archive["checksum"]
            if selected_archive["os"] != "all":
                ET.SubElement(archive, "host-os").text = selected_archive["os"]
    return repository


class SdkMetadataContract(unittest.TestCase):
    def test_local_record_preserves_official_fields_and_qname_bindings(self):
        repository = synthetic_repository()
        for path, selected in json.loads(BASELINE.read_text())["sdkPackages"].items():
            data = local_package_xml(repository, path, selected)
            output = ET.fromstring(data)
            self.assertEqual(output.tag, "{" + COMMON + "}repository")
            local = output.find("localPackage")
            self.assertEqual(local.get("path"), path)
            remote = next(p for p in repository.findall("remotePackage") if p.get("path") == path)
            for tag in ("type-details", "revision", "display-name", "uses-license"):
                # Ignore pretty-print whitespace only, never metadata values.
                original = deepcopy(remote.find(tag))
                ET.indent(original, level=2)
                self.assertEqual(ET.tostring(local.find(tag)).strip(), ET.tostring(original).strip())
            self.assertIsNone(local.find("archives"))
            self.assertIsNone(local.find("channelRef"))
            self.assertIn(('xmlns:sdk="' + SDK + '"').encode(), data)
            self.assertIn(b'xmlns:generic=', data)
            self.assertEqual(output.find("license").text, "Synthetic license only")

    def test_wrong_or_ambiguous_selected_identity_fails_closed(self):
        path = "platforms;android-37.0"
        selected = json.loads(BASELINE.read_text())["sdkPackages"][path]
        changes = (
            ("revision/major", "3"), ("archives/archive/complete/url", "platform-37.1_r02.zip"),
            ("archives/archive/complete/checksum", "0" * 40), ("type-details/api-level", "37.1"),
            ("type-details/codename", "Preview"), ("type-details/extension-level", "23"),
            ("type-details/base-extension", "false"),
        )
        for tag, text in changes:
            with self.subTest(tag=tag):
                repository = synthetic_repository()
                repository.find("remotePackage/" + tag).text = text
                with self.assertRaises(ValueError):
                    local_package_xml(repository, path, selected)
        for sabotage in ("duplicate-package", "duplicate-linux", "license", "type", "schema", "preview", "checksum-algorithm"):
            with self.subTest(sabotage=sabotage):
                repository = synthetic_repository()
                remote = repository.find("remotePackage")
                if sabotage == "duplicate-package":
                    repository.append(deepcopy(remote))
                elif sabotage == "duplicate-linux":
                    archives = remote.find("archives")
                    archives.append(deepcopy(archives.find("archive")))
                elif sabotage == "license":
                    repository.remove(repository.find("license"))
                elif sabotage == "type":
                    remote.find("type-details").set("{" + XSI + "}type", "generic:genericDetailsType")
                elif sabotage == "schema":
                    repository.tag = "repository"
                elif sabotage == "preview":
                    ET.SubElement(remote.find("type-details"), "beta-number").text = "1"
                else:
                    remote.find("archives/archive/complete/checksum").set("type", "sha256")
                with self.assertRaises(ValueError):
                    local_package_xml(repository, path, selected)

    def test_writer_adds_metadata_only_and_validates_all_packages_before_writing(self):
        scratch = ROOT.parent / "codexbar-m1-metadata-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as temporary:
            root = Path(temporary)
            sdk = root / "sdk"
            evidence = root / "evidence"
            evidence.mkdir()
            repository_file = root / "repository.xml"
            ET.ElementTree(synthetic_repository()).write(repository_file)
            originals = {}
            for path, selected in json.loads(BASELINE.read_text())["sdkPackages"].items():
                directory = sdk.joinpath(*path.split(";"))
                directory.mkdir(parents=True)
                props = ("Pkg.Revision=" + selected["revision"] + "\nAndroidVersion.ApiLevel=37.0\n").encode()
                (directory / "source.properties").write_bytes(props)
                originals[directory] = props
                (directory / "payload").write_bytes(b"unchanged synthetic payload")
            build_tools = sdk / "build-tools/36.0.0/source.properties"
            build_tools.write_text("Pkg.Revision=36.0.1\n")
            with self.assertRaises(ValueError):
                main(sdk, repository_file, BASELINE, evidence)
            self.assertEqual(list(sdk.rglob("package.xml")), [])
            build_tools.write_bytes(originals[build_tools.parent])
            main(sdk, repository_file, BASELINE, evidence)
            for directory, props in originals.items():
                self.assertEqual((directory / "source.properties").read_bytes(), props)
                self.assertEqual((directory / "payload").read_bytes(), b"unchanged synthetic payload")
                name = "platform" if directory.parent.name == "platforms" else "build-tools"
                self.assertEqual((directory / "package.xml").read_bytes(), (evidence / (name + "-package.xml")).read_bytes())


if __name__ == "__main__":
    unittest.main()
