import io
import unittest

from qamzor.chunking import iter_file_chunks, split_content_defined


class ContentDefinedChunkingTests(unittest.TestCase):
    def test_empty_input_has_no_chunks(self):
        self.assertEqual(list(split_content_defined([])), [])

    def test_reassembles_input_exactly(self):
        source = (b"QAMZOR-content-defined-chunking-" * 50000) + bytes(range(256)) * 100
        chunks = list(split_content_defined([source[:12345], source[12345:777777], source[777777:]]))
        self.assertEqual(b"".join(chunks), source)
        self.assertTrue(all(0 < len(chunk) <= 1024 * 1024 for chunk in chunks))

    def test_boundaries_do_not_depend_on_input_block_sizes(self):
        source = (b"abcd1234" * 150000) + (b"z" * 900000)
        whole = list(split_content_defined([source]))
        fragmented = list(split_content_defined(source[i:i + 997] for i in range(0, len(source), 997)))
        self.assertEqual(whole, fragmented)

    def test_file_reader(self):
        source = b"abc" * 100000
        chunks = list(iter_file_chunks(io.BytesIO(source), read_size=4093))
        self.assertEqual(b"".join(chunks), source)

    def test_invalid_read_size(self):
        with self.assertRaises(ValueError):
            list(iter_file_chunks(io.BytesIO(b"x"), read_size=0))

    def test_rejects_non_bytes_input(self):
        with self.assertRaises(TypeError):
            list(split_content_defined(["not bytes"]))


if __name__ == "__main__":
    unittest.main()
