"""Console filtering and shipped pipeline checks using synthetic output, never Gradle."""
import os
from pathlib import Path
import select
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
    "Caching disabled for AarTransform: /SYNTHETIC/transforms/library because:\n"
    "  Caching not enabled.\n"
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

    def test_manifest_near_matches_preserve_the_literal_dot_boundary(self):
        for action in ('Loading', 'Merging'):
            for name in ('AndroidManifestXxml', 'AndroidManifest-xml', 'AndroidManifest.xml.bak'):
                with self.subTest(action=action, name=name):
                    text = f'{action} library manifest /SYNTHETIC/library/{name}\n'
                    result = self.filter(text)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(result.stdout, text)

    def test_kept_bytes_and_unterminated_headers_are_preserved_exactly(self):
        header = b'Caching disabled for AarTransform: /SYNTHETIC/\xff because:'
        for text in (b'WARNING: invalid UTF-8 \xff\xfe\r\n', b'last diagnostic \xff',
                     header, header + b'\r\n', header + b'\r\n  Other reason \xfe\r\n'):
            with self.subTest(text=text):
                result = subprocess.run(['bash', str(FILTER)], input=text,
                                        capture_output=True, timeout=5)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, text)

    def test_kept_line_is_readable_before_producer_closes_stdin(self):
        line = b'GATE_TASK_OUTCOME :app:lintDebug SUCCESS\r\n'
        with subprocess.Popen(['bash', str(FILTER)], stdin=subprocess.PIPE,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0) as process:
            assert process.stdin is not None
            assert process.stdout is not None
            assert process.stderr is not None
            try:
                process.stdin.write(line)
                process.stdin.flush()
                readable, _, _ = select.select([process.stdout], [], [], 2)
                self.assertTrue(readable, 'Kept console line buffered until EOF')
                self.assertEqual(os.read(process.stdout.fileno(), len(line)), line)
                process.stdin.close()
                self.assertEqual(process.wait(timeout=5), 0)
                self.assertEqual(process.stderr.read(), b'')
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait(timeout=5)

    def test_empty_or_all_noise_is_success_not_grep_exit_one(self):
        for text in ("", NOISE):
            with self.subTest(text=text):
                result = self.filter(text)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "")

    def test_transform_blocks_filter_only_exact_no_caching_reason(self):
        for name in ('AarTransform', 'MergeInstrumentationAnalysisTransform',
                     'ExternalDependencyInstrumentingArtifactTransform', 'IdentityTransform',
                     'NewPluginTransform'):
            block = f'Caching disabled for {name}: /SYNTHETIC/transforms/library because:\n  Caching not enabled.\n'
            for ending in ('\n', '\r\n'):
                with self.subTest(name=name, ending=ending):
                    result = subprocess.run(["bash", str(FILTER)],
                                            input=(block + DIAGNOSTICS).replace('\n', ending).encode(),
                                            capture_output=True, timeout=5)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(result.stdout, DIAGNOSTICS.replace('\n', ending).encode())

    def test_transform_other_reasons_and_incomplete_blocks_are_preserved(self):
        header = 'Caching disabled for AarTransform: /SYNTHETIC/transforms/library because:\n'
        for reason in ('  Different caching reason.\n', '  Build cache is disabled\n',
                       '  Caching has not been enabled for the task\n', ' Caching not enabled.\n',
                       '  Caching not enabled. extra\n', '  Caching not enabled. \n', '', '\n'):
            with self.subTest(reason=reason):
                text = header + reason
                result = self.filter(text)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, text)
        # A lone first header must not conceal a subsequent complete noise block.
        self.assertEqual(self.filter(header + header + '  Caching not enabled.\n').stdout, header)

    def test_transform_reason_never_filters_task_level_or_near_match_diagnostics(self):
        lines = ('  Caching not enabled.\n', '> Task :app:lintDebug SKIPPED\n',
                 'WARNING: Caching disabled for AarTransform: /SYNTHETIC/library because:\n',
                 'Caching disabled for NotAnArtifact: /SYNTHETIC/library because:\n',
                 'Caching disabled for AarTransform: /SYNTHETIC/library because: warning\n')
        for line in lines:
            text = line + '  Caching not enabled.\n'
            with self.subTest(line=line):
                result = self.filter(text)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, text)
        task = "Caching disabled for task ':app:lintDebug' because:\n  Caching not enabled.\n"
        # Retain #104 task-header filtering, but not the new transform-only reason.
        self.assertEqual(self.filter(task).stdout, '  Caching not enabled.\n')

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
            for source in (FILTER, ROOT / 'tools/build/filter_gradle_console.py'):
                shutil.copyfile(source, tools / source.name)
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
