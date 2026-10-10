"""Console filtering and shipped pipeline checks using synthetic output, never Gradle."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRATCH = Path(os.environ.get("RUNNER_TEMP", os.environ.get("BUILD_CONTRACT_SCRATCH", tempfile.gettempdir())))
FILTER = ROOT / "tools/build/filter-gradle-console.sh"
NOISE = (
    "Caching disabled for task ':app:testDebugUnitTest' because:\n"
    "  Build cache is disabled\n"
    "  Caching has not been enabled for the task\n"
    "Loading library manifest /SYNTHETIC/library/AndroidManifest.xml\n"
    "Merging library manifest /SYNTHETIC/library/AndroidManifest.xml\n"
)
DIAGNOSTICS = (
    "> Task :app:testDebugUnitTest FAILED\n"
    "COVERAGE_TASK_OUTCOME :app:connectedDebugAndroidTest SUCCESS\n"
    "Collecting code coverage data.\n"
    "adb: device offline\n"
    "testExample > syntheticFailure FAILED\n"
    "WARNING: Build cache is disabled\n"
    "ERROR: Loading library manifest /SYNTHETIC/library/AndroidManifest.xml\n"
    "Loading library manifest /SYNTHETIC/library/AndroidManifest.xml: warning\n"
    "Merging library manifest failed\n"
    "e: compiler error\n"
    "w: compiler warning\n"
    "FAILURE: Build failed with an exception.\n"
    "BUILD SUCCESSFUL in 1s\n"
    "\tat synthetic.Stack.trace(Stack.java:1)\n"
)


class GradleConsoleTests(unittest.TestCase):
    def filter(self, text):
        return subprocess.run(["bash", str(FILTER)], input=text, text=True,
                              capture_output=True, timeout=5)

    def test_only_complete_known_info_lines_are_filtered(self):
        result = self.filter(NOISE + DIAGNOSTICS)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, DIAGNOSTICS)
        result = subprocess.run(["bash", str(FILTER)], input=(NOISE + DIAGNOSTICS).replace("\n", "\r\n").encode(),
                                capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, DIAGNOSTICS.replace("\n", "\r\n").encode())

    def test_empty_or_all_noise_is_success_not_grep_exit_one(self):
        for text in ("", NOISE):
            with self.subTest(text=text):
                result = self.filter(text)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "")

    def run_pipeline(self, kind, text, exit_code, helper_failure=""):
        # Execute the actual workflow/native pipeline against a synthetic producer.
        # The native prefix ends before report/retry/device operations.
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        if kind == "build":
            step = workflow.split("- name: Build, lint and unit tests with strict verification", 1)[1].split("- name: Upload", 1)[0]
            shell = textwrap.dedent(step.split("        run: |\n", 1)[1])
            log_paths = ("evidence/strict.log",)
        else:
            script = (ROOT / "tools/build/run-hosted-native-smoke.sh").read_text()
            shell = "set +e\n  timeout --signal=TERM" + script.split("  set +e\n  timeout --signal=TERM", 1)[1].split("  python3 tools/build/coverage_gate.py phases", 1)[0]
            shell = 'remaining=30\nattempt_dir=evidence/native/attempt-1\n' + shell + '\nexit "$test_status"\n'
            log_paths = ("evidence/native/strict-connected.log", "evidence/native/attempt-1/strict-connected.log")
        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=SCRATCH) as temporary:
            base = Path(temporary)
            tools = base / "tools/build"
            tools.mkdir(parents=True)
            shutil.copyfile(FILTER, tools / FILTER.name)
            # Parser doubles isolate log-pipeline exits; real gate semantics are tested separately.
            for filename in ("verify_manifests.py", "verify_test_reports.py", "verify_gradle_execution.py"):
                (tools / filename).write_text("from pathlib import Path\nPath('parsers-ran').touch()\n")
            (base / "evidence/native/attempt-1").mkdir(parents=True)
            (base / "gradlew").write_text(
                f"#!{sys.executable}\nimport sys\nsys.stdout.write({text!r})\nsys.stdout.flush()\nsys.exit({exit_code})\n")
            (base / "gradlew").chmod(0o755)
            binary = base / "bin"
            binary.mkdir()
            (binary / "python3").symlink_to(sys.executable)
            if helper_failure == "filter":
                # Consume the entire stream, then fail; do not SIGPIPE the producer.
                (tools / FILTER.name).write_text(f"#!/bin/sh\n{sys.executable} -c 'import sys; sys.stdin.read()'\nexit 19\n")
            elif helper_failure == "tee":
                (binary / "tee").write_text(f"#!/bin/sh\n{sys.executable} -c 'import sys; sys.stdout.write(sys.stdin.read())'\nexit 17\n")
                (binary / "tee").chmod(0o755)
            environment = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"])
            result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", shell], cwd=base,
                                    env=environment, text=True, capture_output=True, timeout=5)
            logs = [((base / path).read_text() if (base / path).exists() else None) for path in log_paths]
            return result, logs, (base / "parsers-ran").exists()

    def test_shipped_pipelines_keep_full_evidence_and_exact_producer_exit(self):
        for kind in ("build", "native"):
            for status in (0, 7, 23, 124, 130, 143):
                with self.subTest(kind=kind, status=status):
                    result, logs, parsers_ran = self.run_pipeline(kind, NOISE + DIAGNOSTICS, status)
                    self.assertEqual(result.returncode, status, result.stdout + result.stderr)
                    self.assertTrue(all(log == NOISE + DIAGNOSTICS for log in logs))
                    self.assertNotIn("Caching disabled for task", result.stdout)
                    self.assertNotIn("Loading library manifest /SYNTHETIC/library/AndroidManifest.xml", result.stdout.splitlines())
                    self.assertIn("COVERAGE_TASK_OUTCOME", result.stdout)
                    if kind == "build":
                        self.assertEqual(parsers_ran, status == 0)
            for text in ("", NOISE):
                with self.subTest(kind=kind, all_noise=text):
                    result, logs, _ = self.run_pipeline(kind, text, 0)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertTrue(all(log == text for log in logs))

    def test_helper_failure_cannot_mask_graph_failure_or_pass_a_green_graph(self):
        for kind in ("build", "native"):
            for helper in ("tee", "filter"):
                for status in (0, 23, 124):
                    with self.subTest(kind=kind, helper=helper, status=status):
                        result, _, parsers_ran = self.run_pipeline(kind, NOISE + DIAGNOSTICS, status, helper)
                        self.assertEqual(result.returncode, status or 1, result.stdout + result.stderr)
                        self.assertFalse(parsers_ran)


if __name__ == "__main__":
    unittest.main()
