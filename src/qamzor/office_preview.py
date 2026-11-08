"""Bounded text extraction from common Office Open XML documents in QZRA."""
from __future__ import annotations

import io
import re
import zipfile
import xml.etree.ElementTree as ET

from .format import QamzorError

MAX_OFFICE_ARCHIVE_BYTES = 32 * 1024 * 1024
MAX_OFFICE_EXPANDED_BYTES = 64 * 1024 * 1024
MAX_OFFICE_MEMBERS = 20_000


def _safe_xml(data: bytes) -> ET.Element:
    # ElementTree does not fetch external resources; reject DTD/entity declarations
    # explicitly to keep the preview parser's input language narrow.
    if re.search(rb"<!\s*(?:DOCTYPE|ENTITY)\b", data, flags=re.IGNORECASE):
        raise QamzorError("Office preview refuses XML with DTD/entity declarations")
    try:
        return ET.fromstring(data)
    except ET.ParseError as exc:
        raise QamzorError("Office document contains malformed XML") from exc


def _text_nodes(root: ET.Element) -> list[str]:
    return [text for node in root.iter() if node.tag.endswith("}t") and (text := (node.text or "")).strip()]


def preview_office_document(reader, path: str, *, max_expanded_bytes: int = MAX_OFFICE_EXPANDED_BYTES) -> dict:
    """Preview DOCX/XLSX/PPTX text while verifying the full source file hash.

    The source is decoded from QZRA into memory only after applying strict input
    and ZIP expansion limits; nothing is written to the user's extraction folder.
    """
    if not isinstance(max_expanded_bytes, int) or isinstance(max_expanded_bytes, bool) or not 1 <= max_expanded_bytes <= 256 * 1024 * 1024:
        raise QamzorError("Office expanded preview limit must be between 1 byte and 256 MiB")
    entry = reader._entry_map.get(path)
    if entry is None or entry.kind != "file":
        raise QamzorError(f"Office file does not exist: {path}")
    if entry.size > MAX_OFFICE_ARCHIVE_BYTES:
        raise QamzorError(f"Office preview is limited to {MAX_OFFICE_ARCHIVE_BYTES // (1024 * 1024)} MiB source files")
    import tempfile
    with tempfile.SpooledTemporaryFile(max_size=8 * 1024 * 1024, mode="w+b") as buffer:
        reader._decode_file(entry, buffer)
        buffer.seek(0)
        raw = buffer.read(MAX_OFFICE_ARCHIVE_BYTES + 1)
    if len(raw) > MAX_OFFICE_ARCHIVE_BYTES:
        raise QamzorError("Office source exceeded the preview limit")
    try:
        package = zipfile.ZipFile(io.BytesIO(raw), "r")
    except zipfile.BadZipFile as exc:
        raise QamzorError("File is not a valid Office Open XML package") from exc
    with package:
        infos = package.infolist()
        names_in_order = [info.filename for info in infos]
        if len(names_in_order) != len(set(names_in_order)):
            raise QamzorError("Office package contains duplicate ZIP member names")
        if len(infos) > MAX_OFFICE_MEMBERS:
            raise QamzorError("Office package contains too many internal components")
        total = sum(info.file_size for info in infos)
        if total > max_expanded_bytes:
            raise QamzorError("Office package expands beyond the preview safety limit")
        for info in infos:
            if info.file_size > 0 and info.compress_size == 0:
                raise QamzorError("Office package contains a suspicious zero-size compressed member")
            if info.compress_size and info.file_size / info.compress_size > 1000:
                raise QamzorError("Office package compression ratio exceeds the safety limit")
        names = set(package.namelist())
        suffix = path.rsplit(".", 1)[-1].lower()
        fragments: list[str] = []
        if suffix == "docx":
            targets = [name for name in names if name == "word/document.xml" or re.fullmatch(r"word/(header|footer)\d*\.xml", name)]
            if "word/document.xml" not in names:
                raise QamzorError("DOCX package is missing its main document")
            for name in sorted(targets):
                fragments.extend(_text_nodes(_safe_xml(package.read(name))))
            text = "\n".join(fragments)
        elif suffix == "pptx":
            slides = sorted((name for name in names if re.fullmatch(r"ppt/slides/slide\d+\.xml", name)),
                            key=lambda value: int(re.search(r"slide(\d+)", value).group(1)))
            if not slides:
                raise QamzorError("PPTX package contains no slides")
            for index, name in enumerate(slides, 1):
                fragments.extend([f"[Slide {index}]", *_text_nodes(_safe_xml(package.read(name)))])
            text = "\n".join(fragments)
        elif suffix == "xlsx":
            shared: list[str] = []
            if "xl/sharedStrings.xml" in names:
                shared_root = _safe_xml(package.read("xl/sharedStrings.xml"))
                shared = ["".join(_text_nodes(si)) for si in shared_root if si.tag.endswith("}si")]
            sheets = sorted((name for name in names if re.fullmatch(r"xl/worksheets/sheet\d+\.xml", name)),
                            key=lambda value: int(re.search(r"sheet(\d+)", value).group(1)))
            if not sheets:
                raise QamzorError("XLSX package contains no worksheets")
            for index, name in enumerate(sheets, 1):
                root = _safe_xml(package.read(name))
                fragments.append(f"[Sheet {index}]")
                for cell in root.iter():
                    if not cell.tag.endswith("}c"):
                        continue
                    cell_ref = cell.attrib.get("r", "")
                    cell_type = cell.attrib.get("t")
                    value_node = next((node for node in cell if node.tag.endswith("}v")), None)
                    inline = next((node for node in cell if node.tag.endswith("}is")), None)
                    value = ""
                    if cell_type == "s" and value_node is not None:
                        try:
                            value = shared[int(value_node.text or "-1")]
                        except (ValueError, IndexError):
                            raise QamzorError("XLSX shared-string reference is invalid")
                    elif inline is not None:
                        value = " ".join(_text_nodes(inline))
                    elif value_node is not None:
                        value = value_node.text or ""
                    if value:
                        fragments.append(f"{cell_ref}: {value}")
            text = "\n".join(fragments)
        else:
            raise QamzorError("Advanced preview supports DOCX, XLSX and PPTX files")
        return {"path": path, "format": suffix.upper(), "text": text, "verified": True,
                "source_bytes": entry.size, "expanded_bytes": total}
