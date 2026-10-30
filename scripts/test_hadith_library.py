"""Check the actual offline corpus, source completeness, FTS index and paging boundaries."""
import gzip
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

from build_hadith_library import ASSETS, SOURCES, normalize

EXPECTED_COUNTS = {"nawawi": 42, "bukhari": 7008, "muslim": 5362, "malik": 1594}
# بصمات مثبتة بشكل مستقل لكل صف عربي مرقّم بعد التنظيف الموثّق.
EXPECTED_TEXT = {
    "nawawi": "629441efea3dbd7eec75f11e84e769d07434f0336343d49dacd1312f54161e09",
    "bukhari": "353a20bf27bff8fd3a818748484a60a8c6ba23c143ea9050aadb76f76d4437b7",
    "muslim": "3d6323cd598173a513608e390186df6cd123e282ea2e4f1db3bfae07b2b14ba1",
    "malik": "662a2aac7db9d751881d05f89e712da4a43a91505b6ffab09dfb0f4676e09965",
}


class HadithLibraryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads((ASSETS / "manifest.json").read_text())
        cls.packed = b"".join((ASSETS / part["file"]).read_bytes() for part in cls.manifest["parts"])
        cls.raw = gzip.decompress(cls.packed)
        cls.temporary = tempfile.TemporaryDirectory()
        path = Path(cls.temporary.name) / "library.db"
        path.write_bytes(cls.raw)
        cls.db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)

    @classmethod
    def tearDownClass(cls):
        cls.db.close()
        cls.temporary.cleanup()

    def test_actual_parts_and_database_are_complete_and_unchanged(self):
        manifest = self.manifest
        self.assertEqual(1, manifest["schemaVersion"])
        self.assertEqual([f"library.db.gz.{i:03}" for i in range(1, len(manifest["parts"]) + 1)],
                         [p["file"] for p in manifest["parts"]])
        self.assertEqual(set(p["file"] for p in manifest["parts"]), set(p.name for p in ASSETS.glob("library.db.gz*")))
        for part in manifest["parts"]:
            data = (ASSETS / part["file"]).read_bytes()
            self.assertEqual(part["bytes"], len(data))
            self.assertEqual(part["sha256"], hashlib.sha256(data).hexdigest())
            self.assertLessEqual(len(data), 1024 * 1024)
        for data, size, digest in ((self.packed, "packedBytes", "packedSha256"), (self.raw, "dbBytes", "dbSha256")):
            self.assertEqual(manifest[size], len(data))
            self.assertEqual(manifest[digest], hashlib.sha256(data).hexdigest())
        self.assertLess(len(self.raw), 128 * 1024 * 1024)
        self.assertEqual(("ok",), self.db.execute("PRAGMA integrity_check").fetchone())
        self.assertEqual((1,), self.db.execute("PRAGMA user_version").fetchone())

    def test_all_four_books_preserve_every_source_row_without_gaps_or_truncation(self):
        self.assertEqual(EXPECTED_COUNTS, dict(self.db.execute("SELECT id,count FROM books")))
        self.assertEqual(14006, self.manifest["total"])
        self.assertEqual((14006,), self.db.execute("SELECT count(*) FROM hadiths").fetchone())
        for book in self.manifest["books"]:
            rows = self.db.execute("SELECT number,body,search FROM hadiths WHERE book_id=? ORDER BY number", (book["id"],)).fetchall()
            self.assertEqual(list(range(1, EXPECTED_COUNTS[book["id"]] + 1)), [row[0] for row in rows])
            digest = hashlib.sha256()
            for number, body, search in rows:
                self.assertGreaterEqual(len(search), 20)
                self.assertEqual(normalize(body), search)
                self.assertNotRegex(body, r"[\u200e\u200f\u202a-\u202e\u2066-\u2069]")
                digest.update(f"{number}\n{body}\n".encode())
            self.assertEqual(EXPECTED_TEXT[book["id"]], digest.hexdigest())
            self.assertEqual(book["textSha256"], digest.hexdigest())

    def test_first_and_last_narrations_match_the_actual_source_editions(self):
        fixtures = {
            ("nawawi", 1): "انما الاعمال بالنيات",
            ("nawawi", 42): "لو بلغت ذنوبك عنان السماء",
            ("bukhari", 1): "انما الاعمال بالنيات",
            ("bukhari", 7008): "كلمتان حبيبتان الي الرحمن",
            ("muslim", 1): "من حدث عني بحديث يري انه كذب",
            ("muslim", 5362): "هذان خصمان اختصموا في ربهم",
            ("malik", 1): "عمر بن عبد العزيز اخر الصلاة",
            ("malik", 1594): "لي خمسة اسماء انا محمد وانا احمد",
        }
        for (book, number), phrase in fixtures.items():
            text, = self.db.execute("SELECT search FROM hadiths WHERE book_id=? AND number=?", (book, number)).fetchone()
            self.assertIn(phrase, text)

    def test_fts_matches_full_scan_and_handles_unvocalized_and_vocalized_queries(self):
        for query in ("إِنَّمَا الأَعْمَالُ بِالنِّيَّاتِ", "كلمتان خفيفتان", "خَمْسَة أسماء", "غفرت لك"):
            terms = normalize(query).split()
            match = " ".join(term + "*" for term in terms)
            found = {row[0] for row in self.db.execute("SELECT docid FROM hadith_search WHERE hadith_search MATCH ?", (match,))}
            expected = {row[0] for row in self.db.execute("SELECT id,search FROM hadiths")
                        if all(any(word.startswith(term) for word in row[1].split()) for term in terms)}
            self.assertEqual(expected, found, query)
            self.assertTrue(found, query)
        result = self.db.execute("SELECT h.book_id,h.number FROM hadiths h JOIN hadith_search ON hadith_search.docid=h.id WHERE h.book_id=? AND hadith_search MATCH ? ORDER BY h.number", ("bukhari", "انما* الاعمال* بالنيات*")).fetchall()
        self.assertIn(("bukhari", 1), result)
        self.assertTrue(all(book == "bukhari" for book, _ in result))

    def test_number_search_and_paging_can_reach_every_last_record(self):
        for book, count in EXPECTED_COUNTS.items():
            number = int(normalize(str(count).translate(str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩"))))
            self.assertEqual((count,), self.db.execute("SELECT number FROM hadiths WHERE book_id=? AND number=?", (book, number)).fetchone())
            offset = ((count - 1) // 12) * 12
            page = self.db.execute("SELECT number FROM hadiths WHERE book_id=? ORDER BY id LIMIT 12 OFFSET ?", (book, offset)).fetchall()
            self.assertEqual(count, page[-1][0])
            self.assertIn(count, [value[0] for value in page])
            self.assertLessEqual(len(page), 12)
        self.assertIsNone(self.db.execute("SELECT number FROM hadiths WHERE book_id='bukhari' AND number=7009").fetchone())

    def test_reading_ranges_cover_each_book_once_including_last_short_range(self):
        for book, count in EXPECTED_COUNTS.items():
            ranges = self.db.execute("SELECT section,min(number),max(number),count(*) FROM hadiths WHERE book_id=? GROUP BY section ORDER BY section", (book,)).fetchall()
            self.assertEqual(list(range(1, count + 1)), [number for _, first, last, _ in ranges for number in range(first, last + 1)])
            for section, first, last, size in ranges:
                self.assertEqual((section - 1) * 100 + 1, first)
                self.assertEqual(last - first + 1, size)
                self.assertLessEqual(size, 100)

    def test_source_snapshots_are_pinned_and_no_commentary_or_translations_are_bundled(self):
        by_id = {row["id"]: row for row in SOURCES}
        for book in self.manifest["books"]:
            source = by_id[book["id"]]
            self.assertEqual(source["hash"], book["sourceSha256"])
            self.assertEqual(source["url"], book["sourceFile"])
            self.assertEqual(source["sourceUrl"], book["sourceUrl"])
            metadata = self.db.execute("SELECT source,source_url,edition FROM books WHERE id=?", (book["id"],)).fetchone()
            self.assertEqual((source["source"], source["sourceUrl"], source["edition"]), metadata)
        self.assertEqual(["id", "book_id", "number", "section", "body", "search"],
                         [row[1] for row in self.db.execute("PRAGMA table_info(hadiths)")])
        notice = (ASSETS / "NOTICE.txt").read_text()
        self.assertIn("ODbL", notice)
        self.assertIn("DbCL", notice)
        self.assertIn("Modern translations and commentary are not included", notice)


if __name__ == "__main__":
    unittest.main()
