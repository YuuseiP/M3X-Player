import unittest

from range_handler import byte_range


class ByteRangeTest(unittest.TestCase):
    def test_ranges(self):
        for header, expected in [
            ("bytes=0-99", (0, 99)),
            ("bytes=40-", (40, 99)),
            ("bytes=-10", (90, 99)),
            ("bytes=-200", (0, 99)),
            ("bytes=90-200", (90, 99)),
        ]:
            with self.subTest(header=header):
                self.assertEqual(byte_range(header, 100), expected)

    def test_invalid_ranges(self):
        for header in ["bytes=-", "bytes=-0", "bytes=100-", "bytes=10-5", "bytes=0-1,5-6", "bad", "bytes=a-b"]:
            with self.subTest(header=header), self.assertRaises(ValueError):
                byte_range(header, 100)
        with self.assertRaises(ValueError):
            byte_range("bytes=0-", 0)


if __name__ == "__main__":
    unittest.main()
