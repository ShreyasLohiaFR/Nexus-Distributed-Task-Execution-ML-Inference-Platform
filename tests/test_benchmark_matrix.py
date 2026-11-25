"""Unit tests for benchmark correctness gates and reported statistics."""
import copy
import unittest
from scripts.benchmark_matrix import summarize


def report(rate=100.0, label="1x2"):
    return {"workers_label": label, "requested": 10, "accepted": 10,
            "succeeded": 10, "submission_errors": 0,
            "failed_or_cancelled": 0, "unfinished": 0,
            "observed_successes_per_second": rate,
            "submit_latency_ms": {"p95": 12},
            "successful_queue_to_completion_ms": {"p95": 30}}


class MatrixTest(unittest.TestCase):
    def test_median_and_range(self):
        summary = summarize([report(100), report(200), report(150),
                             report(250, "2x2")], 10)
        self.assertEqual(summary["1x2"]["median_jobs_per_second"], 150)
        self.assertEqual(summary["1x2"]["min_jobs_per_second"], 100)
        self.assertEqual(summary["1x2"]["max_jobs_per_second"], 200)
        self.assertEqual(summary["1x2"]["successful_jobs"], 30)
        self.assertEqual(summary["2x2"]["repetitions"], 1)
        self.assertEqual(summary["1x2"]["median_run_p95_completion_ms"], 30)

    def test_every_correctness_failure_is_rejected(self):
        for key, value in [("requested", 11), ("accepted", 9), ("succeeded", 9),
                           ("submission_errors", 1), ("failed_or_cancelled", 1),
                           ("unfinished", 1)]:
            with self.subTest(key=key):
                row = report()
                row[key] = value
                with self.assertRaises(ValueError):
                    summarize([row], 10)

    def test_missing_latency_is_rejected(self):
        row = copy.deepcopy(report())
        row["submit_latency_ms"]["p95"] = None
        with self.assertRaises(ValueError):
            summarize([row], 10)


if __name__ == "__main__":
    unittest.main()
