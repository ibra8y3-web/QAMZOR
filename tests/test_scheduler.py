import time
import unittest

from qamzor.scheduler import bounded_ordered_map


class BoundedSchedulerTests(unittest.TestCase):
    def test_parallel_map_preserves_input_order(self):
        def work(value):
            time.sleep((5 - value) * 0.001)
            return value * value

        self.assertEqual(
            list(bounded_ordered_map(work, range(6), workers=3, max_pending=3)),
            [value * value for value in range(6)],
        )

    def test_single_worker_path(self):
        self.assertEqual(list(bounded_ordered_map(lambda x: x + 1, [1, 2, 3])), [2, 3, 4])

    def test_invalid_worker_count(self):
        with self.assertRaises(ValueError):
            list(bounded_ordered_map(str, [1], workers=0))

    def test_invalid_pending_limit(self):
        with self.assertRaises(ValueError):
            list(bounded_ordered_map(str, [1], workers=2, max_pending=0))


if __name__ == "__main__":
    unittest.main()
