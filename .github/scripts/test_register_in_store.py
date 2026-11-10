import unittest
from register_in_store import merge_entry


class T(unittest.TestCase):
    def test_adds_once(self):
        s, ch = merge_entry({"schema": 1, "apps": [{"repo": "a/store"}]}, "a/app")
        self.assertTrue(ch); self.assertEqual(len(s["apps"]), 2)
        s, ch = merge_entry(s, "A/APP")
        self.assertFalse(ch); self.assertEqual(len(s["apps"]), 2)

    def test_keeps_admin_edits(self):
        s, ch = merge_entry({"schema": 1, "apps": [{"repo": "a/app", "featured": True, "category": "tools"}]}, "a/app")
        self.assertFalse(ch); self.assertTrue(s["apps"][0]["featured"])

    def test_empty(self):
        s, ch = merge_entry({"schema": 1}, "a/app")
        self.assertTrue(ch); self.assertEqual(s["apps"][0]["repo"], "a/app")


if __name__ == "__main__":
    unittest.main()
