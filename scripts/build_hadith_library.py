"""Build the offline Arabic library from pinned, attributed public source editions.

Use --download to obtain the four source files, or --source-dir for an existing copy.
Only original Arabic hadith text is imported: no modern translations or commentary.
"""
import argparse
import csv
import gzip
import hashlib
import html
import json
from pathlib import Path
import re
import sqlite3
import tempfile
import unicodedata
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "android-native/app/src/main/assets/hadith"
OPEN_SHA = "1515f6cba21efed20d8916bf55acef1dffa0d2d5"
NAWAWI_SHA = "ca32fd72aa16eeeb9a819c80bb65c9e78766532d"
SOURCES = [
    {"id": "nawawi", "title": "الأربعون النووية", "author": "الإمام يحيى بن شرف النووي", "count": 42,
     "file": "baset-nawawi40.json", "hash": "d824cec8d82751669a9ed1d41f2854219f8e6770a2cab151ed87f9a501357def",
     "url": f"https://raw.githubusercontent.com/AhmedBaset/hadith-json/{NAWAWI_SHA}/db/by_book/forties/nawawi40.json",
     "source": "النص العربي التراثي للأربعين النووية • نسخة hadith-json المأخوذة من Sunnah.com",
     "sourceUrl": "https://github.com/AhmedBaset/hadith-json/tree/" + NAWAWI_SHA,
     "edition": "ترقيم الأربعين النووية: 42 حديثًا"},
    {"id": "bukhari", "title": "صحيح البخاري", "author": "الإمام محمد بن إسماعيل البخاري", "count": 7008,
     "file": "open-bukhari.csv", "hash": "b03f661876fc4089520800b9231b57a1564baff734344ea8914494b86e2eb708",
     "path": "Sahih_Al-Bukhari/sahih_al-bukhari_ahadith_mushakkala_mufassala.utf8.csv"},
    {"id": "muslim", "title": "صحيح مسلم", "author": "الإمام مسلم بن الحجاج النيسابوري", "count": 5362,
     "file": "open-muslim.csv", "hash": "79232c8d7c27fef161d8f33ca169f9754741cfe14957dde2ac76893374998943",
     "path": "Sahih_Muslim/sahih_muslim_ahadith_mushakkala_mufassala.utf8.csv"},
    {"id": "malik", "title": "موطأ مالك", "author": "الإمام مالك بن أنس", "count": 1594,
     "file": "open-malik.csv", "hash": "d306930f056bb0a74745a4453fbef04cc80765213554b052a29427e24702ece0",
     "path": "Maliks_Muwataa/maliks_muwataa_ahadith_mushakkala_mufassala.utf8.csv"},
]
for source in SOURCES[1:]:
    source.update(url=f"https://raw.githubusercontent.com/mhashim6/Open-Hadith-Data/{OPEN_SHA}/" + source["path"],
                  source="نسخة Islamware العربية • Open-Hadith-Data / mhashim6 • ترخيص قاعدة البيانات ODbL 1.0",
                  sourceUrl="https://github.com/mhashim6/Open-Hadith-Data/tree/" + OPEN_SHA,
                  edition="ترقيم نسخة Islamware المضمنة؛ يختلف جمع الروايات وترقيمها عن طبعات أخرى")


def clean_text(value):
    value = html.unescape(re.sub(r"<[^>]*>", "", value))
    value = re.sub(r"[\u200e\u200f\u202a-\u202e\u2066-\u2069]", "", value)
    return re.sub(r"[ \t]+", " ", value).strip()


def normalize(value):
    value = value.replace("ﷺ", " صلى الله عليه وسلم ").replace("ﷲ", "الله").replace("ٱ", "ا").replace("ى", "ي").replace("ـ", "")
    chars = []
    for char in unicodedata.normalize("NFD", value.lower()):
        if unicodedata.category(char).startswith("M"):
            continue
        if char.isdecimal():
            chars.append(str(unicodedata.decimal(char)))
        else:
            chars.append(char if char.isalpha() else " ")
    return " ".join("".join(chars).split())


def load_rows(source, folder, download=False):
    path = folder / source["file"]
    if download and not path.exists():
        folder.mkdir(parents=True, exist_ok=True)
        request = urllib.request.Request(source["url"], headers={"User-Agent": "TawasolAlattaSourceCheck/1.0"})
        with urllib.request.urlopen(request, timeout=120) as response, path.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
    raw = path.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == source["hash"], "Source changed: " + source["file"]
    if path.suffix == ".json":
        rows = [(row["idInBook"], clean_text(row["arabic"])) for row in json.loads(raw)["hadiths"]]
    else:
        csv.field_size_limit(2_000_000)
        with path.open(encoding="utf-8-sig", newline="") as stream:
            # العمود الثالث للتعليق التوضيحي، ويُستبعد عمدًا من المكتبة.
            rows = [(int(row[0]), clean_text(row[1])) for row in csv.reader(stream)]
    assert [number for number, _ in rows] == list(range(1, source["count"] + 1)), "Numbering gap in " + source["id"]
    assert all(len(normalize(body)) >= 20 for _, body in rows), "Empty/incomplete Arabic text in " + source["id"]
    return rows


def build(folder, destination=ASSETS, download=False):
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        path = Path(temporary) / "library.db"
        db = sqlite3.connect(path)
        db.executescript("""
            PRAGMA page_size=4096;
            PRAGMA journal_mode=DELETE;
            PRAGMA user_version=1;
            CREATE TABLE books(id TEXT PRIMARY KEY, title TEXT NOT NULL, author TEXT NOT NULL,
                count INTEGER NOT NULL, ordinal INTEGER NOT NULL, source TEXT NOT NULL, source_url TEXT NOT NULL, edition TEXT NOT NULL);
            CREATE TABLE hadiths(id INTEGER PRIMARY KEY, book_id TEXT NOT NULL REFERENCES books(id),
                number INTEGER NOT NULL, section INTEGER NOT NULL, body TEXT NOT NULL, search TEXT NOT NULL,
                UNIQUE(book_id,number));
            CREATE INDEX hadith_section ON hadiths(book_id,section,number);
            CREATE VIRTUAL TABLE hadith_search USING fts4(search, content='hadiths', tokenize=unicode61);
        """)
        books = []
        for ordinal, source in enumerate(SOURCES):
            rows = load_rows(source, folder, download)
            db.execute("INSERT INTO books VALUES(?,?,?,?,?,?,?,?)", (source["id"], source["title"], source["author"], len(rows), ordinal, source["source"], source["sourceUrl"], source["edition"]))
            digest = hashlib.sha256()
            for number, body in rows:
                digest.update((str(number) + "\n" + body + "\n").encode())
                db.execute("INSERT INTO hadiths(book_id,number,section,body,search) VALUES(?,?,?,?,?)", (source["id"], number, (number - 1) // 100 + 1, body, normalize(body)))
            books.append({"id": source["id"], "title": source["title"], "count": len(rows), "sourceUrl": source["sourceUrl"], "sourceFile": source["url"], "sourceSha256": source["hash"], "textSha256": digest.hexdigest()})
        db.execute("INSERT INTO hadith_search(hadith_search) VALUES('rebuild')")
        db.execute("INSERT INTO hadith_search(hadith_search) VALUES('optimize')")
        db.commit()
        db.execute("VACUUM")
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        db.close()
        raw = path.read_bytes()
        packed = gzip.compress(raw, compresslevel=9, mtime=0)
        # تقسيم البيانات إلى أجزاء صغيرة ثابتة يُبقي الرفع إلى مستودع الشيفرة ضمن حدوده؛ ويقرأ Android تدفق gzip واحدًا.
        for previous in destination.glob("library.db.gz*"):
            previous.unlink()
        parts = []
        for index, start in enumerate(range(0, len(packed), 1024 * 1024), 1):
            part = packed[start:start + 1024 * 1024]
            name = f"library.db.gz.{index:03}"
            (destination / name).write_bytes(part)
            parts.append({"file": name, "bytes": len(part), "sha256": hashlib.sha256(part).hexdigest()})
        manifest = {"schemaVersion": 1, "dbBytes": len(raw), "dbSha256": hashlib.sha256(raw).hexdigest(),
                    "packedBytes": len(packed), "packedSha256": hashlib.sha256(packed).hexdigest(),
                    "total": sum(book["count"] for book in books), "books": books, "parts": parts}
        (destination / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps({"total": manifest["total"], "databaseBytes": len(raw), "compressedBytes": len(packed), "books": [{"id": b["id"], "count": b["count"]} for b in books]}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    build(args.source_dir, download=args.download)
