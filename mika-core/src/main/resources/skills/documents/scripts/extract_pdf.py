# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 Mika authors
"""Get the text out of a text-based PDF (not scanned images: those need OCR, which this does not do).

Usage:
  extract_pdf.py FILE.pdf [--pages RANGE] [--metadata] [--out FILE.txt] [--password PASSWORD]

  --pages     1-based pages, e.g. "3", "1-5" or "1,4,7-9" (default: all)
  --metadata  print title, author, page count etc. as JSON instead of the text
  --out       write the text to this file and print a one-line JSON summary instead
  --password  password of an encrypted PDF

Each page starts with a line "--- page N ---". A page with no extractable text is reported on
stderr; if no page has text, the PDF is probably scanned and the exit code is 3.
Needs pypdf.
"""
import argparse
import json
import logging
import os
import sys


def fail(msg, code=2):
    print(f"extract_pdf.py: {msg}", file=sys.stderr)
    sys.exit(code)


def parse_pages(spec, count):
    """'1,4,7-9' -> [0, 3, 6, 7, 8]; raises ValueError for anything outside 1..count."""
    out = []
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        lo, _, hi = part.partition("-")
        a, b = int(lo), int(hi) if hi else int(lo)
        if a < 1 or b > count or a > b:
            raise ValueError(f"page range '{part}' is outside 1-{count}")
        out.extend(range(a - 1, b))
    if not out:
        raise ValueError("no pages selected")
    return out


def main(argv):
    p = argparse.ArgumentParser(prog="extract_pdf.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("pdf")
    p.add_argument("--pages")
    p.add_argument("--metadata", action="store_true")
    p.add_argument("--out")
    p.add_argument("--password")
    a = p.parse_args(argv)
    if not os.path.isfile(a.pdf):
        fail(f"file not found: {a.pdf}")
    try:
        from pypdf import PdfReader
        from pypdf.errors import PdfReadError
    except ImportError:
        fail("pypdf is not installed")
    # pypdf logs repair warnings for damaged files; the one-line reason below is enough
    logging.getLogger("pypdf").setLevel(logging.CRITICAL)
    try:
        reader = PdfReader(a.pdf)
        if reader.is_encrypted:
            if not a.password or not reader.decrypt(a.password):
                fail("the PDF is encrypted; pass the right --password")
        count = len(reader.pages)
        if a.metadata:
            meta = reader.metadata or {}
            info = {k.lstrip("/").lower(): str(v) for k, v in meta.items()}
            info["pages"] = count
            print(json.dumps(info, ensure_ascii=False))
            return 0
        indexes = parse_pages(a.pages, count) if a.pages else list(range(count))
        chunks, empty = [], []
        for i in indexes:
            text = (reader.pages[i].extract_text() or "").strip()
            if not text:
                empty.append(i + 1)
            chunks.append(f"--- page {i + 1} ---\n{text}\n")
    except ValueError as e:
        fail(str(e))
    except PdfReadError as e:
        fail(f"not a readable PDF: {e}")
    except Exception as e:  # damaged files raise many kinds of errors inside pypdf
        fail(f"could not read the PDF: {type(e).__name__}: {e}")
    if empty:
        print(f"extract_pdf.py: no text on page(s) {', '.join(map(str, empty))}", file=sys.stderr)
    body = "\n".join(chunks)
    if a.out:
        try:
            with open(a.out, "w", encoding="utf-8") as f:
                f.write(body)
        except OSError as e:
            fail(f"could not write {a.out}: {e}")
        print(json.dumps({"output": a.out, "pages": len(indexes), "chars": len(body), "emptyPages": empty}))
    else:
        sys.stdout.write(body)
    if len(empty) == len(indexes):
        fail("no extractable text: the PDF is probably scanned images and needs OCR", 3)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
