"""Small public-document contracts; prose and historical research remain free to evolve."""
from decimal import Decimal
from pathlib import Path
import re
import subprocess
import unittest
from urllib.parse import unquote, urlsplit

import yaml

ROOT = Path(__file__).resolve().parents[2]
GUIDES = ("README.md", "CONTRIBUTING.md", "SECURITY.md", "THIRD_PARTY_NOTICES.md",
          "AGENTS.md", "ARCHITECTURE.md", "docs/repository-protection.md")
FORMS = (".github/ISSUE_TEMPLATE/bug.yml", ".github/ISSUE_TEMPLATE/feature.yml")
PR_TEMPLATE = ".github/PULL_REQUEST_TEMPLATE.md"
CONFIG = ".github/ISSUE_TEMPLATE/config.yml"
REQUIRED_FILES = GUIDES + FORMS + (PR_TEMPLATE, CONFIG, ".gitignore", "LICENSE")
LINK = re.compile(r"\[[^\]\n]+\]\(([^)\s]+)\)")
SECRET = re.compile(r"\b(?:tokens?|passwords?|device[ _-]?codes?|raw[ _-]?account[ _-]?data)\b", re.I)
# AGP-provided tasks are declared via the Android plugin/variant, not tasks.register.
ANDROID_TASKS = {"assembleDebug", "lintDebug", "testDebugUnitTest",
                 "compileDebugUnitTestKotlin", "compileDebugAndroidTestKotlin",
                 "assembleDebugAndroidTest", "connectedDebugAndroidTest"}


def relative_links(text, source, root):
    """Resolve local Markdown targets without network or historical-document crawling."""
    for link in LINK.findall(text):
        url = urlsplit(link)
        if url.scheme or url.netloc:
            continue
        target = (source.parent / unquote(url.path)).resolve() if url.path else source.resolve()
        assert target.is_relative_to(root.resolve()), f"Link escapes repository: {source}: {link}"
        assert target.exists(), f"Broken relative link: {source}: {link}"
        if url.fragment and target.suffix == ".md":
            headings = re.findall(r"^#{1,6}\s+(.+)$", target.read_text(), re.M)
            anchors = {re.sub(r"[^\w -]", "", heading.lower()).replace(" ", "-") for heading in headings}
            assert unquote(url.fragment) in anchors, f"Broken heading link: {source}: {link}"


def documented_tasks(text, build, init, workflow):
    custom = set(re.findall(r"tasks\.register\(['\"](\w+)", build + init))
    tasks = set(re.findall(r":app:([A-Za-z]\w*)", text))
    assert tasks, "No documented Gradle tasks"
    for task in tasks:
        if task not in custom:
            assert "id 'com.android.application'" in build and task in ANDROID_TASKS, task
            assert f":app:{task}" in workflow, f"Task absent from hosted commands: {task}"
        # Registered tasks may be dependency-invoked (e.g. jacocoDebugReport).


def coverage_minimum(text, build):
    source = re.search(r"minimum\s*=\s*(0\.\d+)\b", build)
    stated = re.findall(r"Coverage minimum:\s*[*`]*(\d+(?:\.\d+)?)%", text)
    assert source and stated, "Coverage minimum absent"
    actual = Decimal(source[1])
    assert actual == Decimal("0.90"), "Current coverage policy must remain 0.90"
    assert all(Decimal(value) / 100 == actual for value in stated), "Coverage documentation drift"
    assert "INSTRUCTION" in text, "Coverage counter absent"


def check_contexts(table, workflows):
    rows = re.findall(r"^\| `([^`]+\.yml)` \| `([^`]+)` \| ([^|]+) \|$", table, re.M)
    assert rows, "Check context mapping absent"
    actual = {(file, job["name"]) for file, data in workflows.items() for job in data["jobs"].values()}
    documented = {(file, name) for file, name, _ in rows}
    assert len(documented) == len(rows), "Duplicate documented context"
    assert documented == actual, f"Workflow/document contexts differ: {documented ^ actual}"
    names = [name for _, name in actual]
    assert len(names) == len(set(names)), "Check contexts must be unique"
    for file, _, category in rows:
        allowed = {"Informational", "Main-only; not required for PRs"} if file == "code-metrics.yml" else {"Required"}
        assert category.strip() in allowed, f"Unexpected check recommendation: {category}"


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
    def test_gitignore_does_not_match_tracked_files(self):
        ignored = subprocess.run(
            ["git", "ls-files", "-ci", "--exclude-standard"],
            cwd=ROOT, check=True, capture_output=True, text=True)
        self.assertEqual(ignored.stdout, "", f"Tracked files are ignored:\n{ignored.stdout}")

    def test_gitignore_allows_new_source_files(self):
        for file in ("tools/build/new_test.py", "tools/policy/new_test.py",
                     "docs/new-guide.md", ".github/workflows/new-workflow.yml",
                     "app/src/main/java/NewSource.kt", "gradle/new-config.gradle"):
            with self.subTest(file=file):
                ignored = subprocess.run(
                    ["git", "check-ignore", "-q", "--no-index", file],
                    cwd=ROOT, capture_output=True, text=True)
                self.assertEqual(ignored.returncode, 1,
                                 f"Source path is ignored or Git failed: {file}: {ignored.stderr}")

    def test_gitignore_still_excludes_gradle_build_outputs(self):
        for file in ("build/generated.txt", "app/build/generated.txt", "buildSrc/build/generated.txt"):
            with self.subTest(file=file):
                ignored = subprocess.run(
                    ["git", "check-ignore", "-q", "--no-index", file],
                    cwd=ROOT, capture_output=True, text=True)
                self.assertEqual(ignored.returncode, 0,
                                 f"Build output is not ignored or Git failed: {file}: {ignored.stderr}")

    def test_named_files_are_present_and_nonempty(self):
        for file in REQUIRED_FILES:
            with self.subTest(file=file):
                self.assertTrue((ROOT / file).is_file(), file)
                self.assertTrue((ROOT / file).read_text().strip(), file)

    def test_relative_links_resolve(self):
        for file in GUIDES + (PR_TEMPLATE,):
            relative_links((ROOT / file).read_text(), ROOT / file, ROOT)

    def test_documented_commands_and_tasks_exist(self):
        text = "\n".join((ROOT / file).read_text() for file in GUIDES)
        self.assertIn("bash tools/check.sh", text)
        for command in set(re.findall(r"\b(tools/[\w./-]+\.(?:sh|py))\b", text)):
            self.assertTrue((ROOT / command).is_file(), command)
        documented_tasks(text, (ROOT / "app/build.gradle").read_text(),
                         (ROOT / "tools/build/toolchain.init.gradle").read_text(),
                         (ROOT / ".github/workflows/android.yml").read_text() +
                         (ROOT / "tools/build/run-hosted-native-smoke.sh").read_text())

    def test_current_coverage_documents_match_build(self):
        for file in ("CONTRIBUTING.md", "AGENTS.md", "ARCHITECTURE.md"):
            coverage_minimum((ROOT / file).read_text(), (ROOT / "app/build.gradle").read_text())

    def test_check_context_table_matches_all_workflow_job_names(self):
        workflows = {path.name: yaml.safe_load(path.read_text())
                     for path in (ROOT / ".github/workflows").glob("*.yml")}
        check_contexts((ROOT / "docs/repository-protection.md").read_text(), workflows)

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

    def test_link_checker_rejects_missing_target_and_escape(self):
        for link in ("missing-readiness-document.md", "../outside.md"):
            with self.subTest(link=link), self.assertRaises(AssertionError):
                relative_links(f"[example]({link})", ROOT / "README.md", ROOT)

    def test_coverage_checker_rejects_drift_and_weakened_source(self):
        for text, build in (("Coverage minimum: **95%** INSTRUCTION", "minimum = 0.90"),
                            ("Coverage minimum: **80%** INSTRUCTION", "minimum = 0.80")):
            with self.assertRaises(AssertionError):
                coverage_minimum(text, build)

    def test_task_checker_rejects_invented_command(self):
        with self.assertRaises(AssertionError):
            documented_tasks(":app:inventedTask", "id 'com.android.application'", "", "")

    def test_context_checker_rejects_missing_renamed_and_duplicate_rows(self):
        workflows = {"android.yml": {"jobs": {"build": {"name": "Build"}}}}
        for table in ("| `android.yml` | `Renamed` | Required |", "no rows",
                      "| `android.yml` | `Build` | Required |\n| `android.yml` | `Build` | Required |"):
            with self.assertRaises(AssertionError):
                check_contexts(table, workflows)

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
