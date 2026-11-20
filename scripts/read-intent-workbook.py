"""Export the input rows from an OOXML workbook to the intent test runner."""

import json
import posixpath
import re
import sys
from xml.etree import ElementTree
from zipfile import ZipFile

SHEET = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
REL = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
PACKAGE = "{http://schemas.openxmlformats.org/package/2006/relationships}"


def read_xml(bundle, part):
    return ElementTree.fromstring(bundle.read(part))


def text_of(node):
    return "".join(part.text or "" for part in node.iter(SHEET + "t"))


def column(address):
    letters = re.match(r"[A-Z]+", address).group()
    index = 0
    for letter in letters:
        index = index * 26 + ord(letter) - ord("A") + 1
    return index - 1


def read_rows(sheet, strings):
    for row in sheet.findall(f"{SHEET}sheetData/{SHEET}row"):
        values = []
        for cell in row.findall(SHEET + "c"):
            offset = column(cell.attrib["r"])
            values.extend([None] * (offset + 1 - len(values)))
            raw = cell.findtext(SHEET + "v")
            kind = cell.get("t")
            if kind == "s":
                values[offset] = strings[int(raw)]
            elif kind == "inlineStr":
                values[offset] = text_of(cell)
            else:
                values[offset] = raw
        if values:
            yield values


def workbook(path):
    with ZipFile(path) as bundle:
        strings = []
        if "xl/sharedStrings.xml" in bundle.namelist():
            strings = [text_of(item) for item in read_xml(bundle, "xl/sharedStrings.xml")]
        targets = {
            row.attrib["Id"]: row.attrib["Target"]
            for row in read_xml(bundle, "xl/_rels/workbook.xml.rels").findall(PACKAGE + "Relationship")
        }
        for sheet in read_xml(bundle, "xl/workbook.xml").findall(f"{SHEET}sheets/{SHEET}sheet"):
            target = targets[sheet.attrib[REL + "id"]]
            part = target.lstrip("/") if target.startswith("/") else posixpath.join("xl", target)
            yield {"name": sheet.attrib["name"], "rows": list(read_rows(read_xml(bundle, part), strings))}


if __name__ == "__main__":
    json.dump(list(workbook(sys.argv[1])), sys.stdout, ensure_ascii=True)
