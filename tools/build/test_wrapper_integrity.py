"""Independent Gradle wrapper integrity checks; no Gradle execution."""
import hashlib
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class WrapperIntegrityTests(unittest.TestCase):
    def test_wrapper_source_and_independent_jar_checksum(self):
        expected = {
            "gradle/wrapper/gradle-wrapper.jar": "3b8a25775a69158b5ad2b1d17a80a88dc7b40a352a73eb27d06c612a7ce68e98",
            "gradlew": "e01b5c97892572c82405c02b96a3382379100e7d825ce7c48883d95c26928750",
            "gradlew.bat": "ad2fac6060c5b929bed15d428e09483e52747d0120874346861ad4ec324af64c",
        }
        for path, digest in expected.items():
            self.assertEqual(hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), digest, path)
        self.assertTrue((ROOT / "gradlew").stat().st_mode & 0o111)
        wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
        self.assertIn("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.2-bin.zip", wrapper)
        self.assertIn("distributionSha256Sum=21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607", wrapper)
