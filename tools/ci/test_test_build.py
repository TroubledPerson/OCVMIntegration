# SPDX-License-Identifier: GPL-2.0-or-later
"""In-memory CI permissions, pins and report-accounting regressions."""
import json
import unittest
import check_test_build as ci


class CiPolicyTests(unittest.TestCase):
    def setUp(self):
        self.workflow = json.loads((ci.ROOT / ".github/workflows/test.yml").read_text())

    def test_declared_inputs_and_licences(self):
        ci.inputs()

    def test_readonly_workflow(self):
        ci.workflow_policy(self.workflow)

    def test_reject_write_token(self):
        self.workflow["permissions"]["contents"] = "write"
        with self.assertRaises(ValueError):
            ci.workflow_policy(self.workflow)

    def test_reject_added_release_job(self):
        self.workflow["jobs"]["release"] = {}
        with self.assertRaises(ValueError):
            ci.workflow_policy(self.workflow)

    def test_reject_artifact_upload(self):
        self.workflow["jobs"]["offline"]["steps"].append({"uses": "actions/upload-artifact@any"})
        with self.assertRaises(ValueError):
            ci.workflow_policy(self.workflow)

    def test_reject_persisted_credentials(self):
        self.workflow["jobs"]["offline"]["steps"][0]["with"]["persist-credentials"] = True
        with self.assertRaises(ValueError):
            ci.workflow_policy(self.workflow)

    def test_reject_privileged_pr_trigger(self):
        self.workflow["on"]["pull_request_target"] = {}
        with self.assertRaises(ValueError):
            ci.workflow_policy(self.workflow)

    def test_reject_unpinned_action(self):
        self.workflow["jobs"]["offline"]["steps"][1]["uses"] = "actions/setup-java@main"
        with self.assertRaises(ValueError):
            ci.workflow_policy(self.workflow)

    def test_reject_secret_reference(self):
        self.workflow["jobs"]["offline"]["steps"][-1]["env"] = {"TOKEN": "${{ secrets.TOKEN }}"}
        with self.assertRaises(ValueError):
            ci.workflow_policy(self.workflow)

    def test_complete_reports(self):
        data = b'<testsuite name="ok" tests="1"><testcase name="one"/></testsuite>'
        self.assertEqual(ci.report_counts([data], 1), 1)

    def test_reject_missing_reports(self):
        with self.assertRaises(ValueError):
            ci.report_counts([])

    def test_reject_failure_skip_error_and_bad_count(self):
        for value in (b'<testsuite name="bad" tests="1" failures="1"><testcase/></testsuite>',
                      b'<testsuite name="bad" tests="1" skipped="1"><testcase/></testsuite>',
                      b'<testsuite name="bad" tests="1" errors="1"><testcase/></testsuite>',
                      b'<testsuite name="bad" tests="2"><testcase/></testsuite>',
                      b'<testsuite name="bad" tests="1"><testcase><failure/></testcase></testsuite>'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                ci.report_counts([value], 1)

    def test_reject_missing_coverage(self):
        with self.assertRaises(ValueError):
            ci.report_counts([b'<testsuite name="small" tests="1"><testcase/></testsuite>'])

    def test_reject_duplicate_suite(self):
        data = b'<testsuite name="same" tests="1"><testcase/></testsuite>'
        with self.assertRaises(ValueError):
            ci.report_counts([data, data], 1)

    def test_inventory_allows_source_and_wrapper(self):
        ci.inventory_policy([{"path": "src/main/Test.java"},
                             {"path": "gradle/wrapper/gradle-wrapper.jar"}])

    def test_inventory_rejects_retained_outputs(self):
        for path in ("build/test-results/test/TEST-X.xml", "verification/report.json",
                     "deliverables/private.jar", "logs/output.txt", ".git/config"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                ci.inventory_policy([{"path": path}])

    def test_inventory_rejects_unsafe_and_duplicate_paths(self):
        for path in ("../secret", "/absolute", "C:/private", "dir\\private"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                ci.inventory_policy([{"path": path}])
        with self.assertRaises(ValueError):
            ci.inventory_policy([{"path": "README.md"}, {"path": "README.md"}])

    def test_inventory_rejects_runtime_binaries(self):
        for path in ("native/qemu.exe", "native/runtime.dll", "addon.jar", "vm/disk.qcow2"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                ci.inventory_policy([{"path": path}])


if __name__ == "__main__":
    unittest.main()
