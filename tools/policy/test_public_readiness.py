"""Public contribution safety and the current documented coverage floor."""
from decimal import Decimal
from pathlib import Path
import re
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
FORMS = (".github/ISSUE_TEMPLATE/bug.yml", ".github/ISSUE_TEMPLATE/feature.yml")
PR_TEMPLATE = ".github/PULL_REQUEST_TEMPLATE.md"
CONFIG = ".github/ISSUE_TEMPLATE/config.yml"
SECRET = re.compile(r"\b(?:tokens?|passwords?|device[ _-]?codes?|raw[ _-]?account[ _-]?data)\b", re.I)


def coverage_minimum(text, build):
    source = re.search(r"minimum\s*=\s*(0\.\d+)\b", build)
    stated = re.findall(r"Coverage minimum:\s*[*`]*(\d+(?:\.\d+)?)%", text)
    assert source and stated, "Coverage minimum absent"
    actual = Decimal(source[1])
    assert actual == Decimal("0.90"), "Current coverage policy must remain 0.90"
    assert all(Decimal(value) / 100 == actual for value in stated), "Coverage documentation drift"
    assert "INSTRUCTION" in text, "Coverage counter absent"


def safe_pr_template(text):
    # Headings and colon-labeled editable fields must not ask for private inputs.
    labels = re.findall(r"^\s*(?:#{1,6}\s+(.+)|[-*]\s+([^:\n]+):)", text, re.M)
    for heading, field in labels:
        assert not SECRET.search(heading or field), "Secret-requesting PR field"
    assert not re.search(r"(?:paste|provide|enter|upload|attach)\s+(?:your\s+|the\s+)?"
                         + SECRET.pattern, text, re.I), "Secret-requesting PR prompt"
    for term in ("tokens", "passwords", "device codes", "raw account data", "sanitized"):
        assert term in text.lower(), f"Missing PR safety guidance: {term}"


def safe_form(form):
    assert isinstance(form, dict) and form.get("name") and form.get("description")
    fields = form.get("body")
    assert isinstance(fields, list) and fields, "Form fields absent"
    ids = []
    for field in fields:
        assert field.get("type") in {"markdown", "input", "textarea", "dropdown", "checkboxes"}
        attributes = field.get("attributes", {})
        if field["type"] == "markdown":
            continue
        ids.append(field["id"])
        # Examine editable field identities, not protective warnings/options.
        assert not SECRET.search(field["id"] + " " + attributes.get("label", "")), "Secret-requesting field"
        for key in ("placeholder", "value", "description"):
            value = attributes.get(key, "")
            assert not re.search(r"(?:paste|provide|enter|upload|attach|include)\s+(?:your\s+|the\s+)?"
                                 + SECRET.pattern, value, re.I), "Secret-requesting prompt"
    assert len(ids) == len(set(ids)), "Duplicate form field ID"
    rendered = yaml.safe_dump(form).lower()
    for term in ("tokens", "passwords", "device codes", "raw account data", "sanitized"):
        assert term in rendered, f"Missing safety guidance: {term}"
    assert any(field["type"] == "checkboxes" and any(
        option.get("required") is True for option in field["attributes"].get("options", []))
        for field in fields), "Required safety acknowledgement absent"


class PublicReadinessTests(unittest.TestCase):
    def test_current_coverage_documents_match_build(self):
        for file in ("CONTRIBUTING.md", "AGENTS.md", "ARCHITECTURE.md"):
            coverage_minimum((ROOT / file).read_text(), (ROOT / "app/build.gradle").read_text())

    def test_templates_do_not_request_secrets(self):
        for file in FORMS:
            safe_form(yaml.safe_load((ROOT / file).read_text()))
        safe_pr_template((ROOT / PR_TEMPLATE).read_text())
        config = yaml.safe_load((ROOT / CONFIG).read_text())
        self.assertIs(config.get("blank_issues_enabled"), False)
        links = config.get("contact_links", [])
        self.assertTrue(any(link.get("url") ==
                            "https://github.com/leugenea/codexbar-mobile/blob/main/SECURITY.md"
                            for link in links))

    def test_security_policy_uses_enabled_private_reporting_channel(self):
        text = " ".join((ROOT / "SECURITY.md").read_text().split())
        self.assertIn("GitHub private vulnerability reporting is enabled.", text)
        self.assertIn("https://github.com/leugenea/codexbar-mobile/security/advisories/new", text)
        self.assertNotRegex(text.lower(), r"(?:private vulnerability reporting|reporting channel) is disabled")
        self.assertIn("If the form is unavailable, do not disclose the vulnerability publicly", text)
        self.assertIn("a non-sensitive issue asking how to report privately", text)
        self.assertIn("with no exploit details or private data", text)

    def test_coverage_checker_rejects_drift_and_weakened_source(self):
        for text, build in (("Coverage minimum: **95%** INSTRUCTION", "minimum = 0.90"),
                            ("Coverage minimum: **80%** INSTRUCTION", "minimum = 0.80")):
            with self.assertRaises(AssertionError):
                coverage_minimum(text, build)

    def test_pr_checker_rejects_secret_fields_and_prompts(self):
        text = (ROOT / PR_TEMPLATE).read_text()
        for added in ("\n## Password", "\n- Token:", "\nPaste your device code"):
            with self.subTest(added=added), self.assertRaises(AssertionError):
                safe_pr_template(text + added)

    def test_form_checker_rejects_secret_fields_and_prompts(self):
        form = yaml.safe_load((ROOT / FORMS[0]).read_text())
        for attributes in ({"label": "Access token"}, {"label": "Diagnostic", "description": "Paste your password"}):
            with self.subTest(attributes=attributes), self.assertRaises(AssertionError):
                safe_form({**form, "body": form["body"] +
                           [{"type": "textarea", "id": "unsafe", "attributes": attributes}]})


if __name__ == "__main__":
    unittest.main()
