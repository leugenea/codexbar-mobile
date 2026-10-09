"""Fail-closed collection receipt for synthetic native history component captures.

This validates artifact/configuration integrity, not visual appearance. Native JUnit
establishes the labels/layout/actions; independent actual-image inspection is still required.
"""
import hashlib
import json
from pathlib import Path
import struct
import zlib

from coverage_gate import current_identity

ROOT = Path(__file__).resolve().parents[2]
CAPTURES = {
    "history-measured-portrait-light": (1, False, 1.0),
    "history-exact-measurement-portrait-light": (1, False, 1.0),
    "history-gap-landscape-dark": (2, True, 1.0),
    "history-unknown-large-font": (1, False, 2.0),
}


def png_size(data: bytes) -> tuple[int, int]:
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Missing PNG signature")
    offset = 8
    kinds = []
    size = None
    while offset < len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        body = data[offset + 8:offset + 8 + length]
        checksum = data[offset + 8 + length:offset + 12 + length]
        if len(body) != length or len(checksum) != 4:
            raise ValueError("Truncated PNG chunk")
        if zlib.crc32(kind + body) != struct.unpack(">I", checksum)[0]:
            raise ValueError("Invalid PNG chunk checksum")
        if kind == b"IHDR":
            if kinds or length != 13:
                raise ValueError("Invalid PNG header")
            size = struct.unpack(">II", body[:8])
        kinds.append(kind)
        offset += length + 12
    if not complete_png(size, kinds):
        raise ValueError("Incomplete PNG")
    assert size is not None
    return size


def complete_png(size: tuple[int, int] | None, kinds: list[bytes]) -> bool:
    return size is not None and min(size) > 0 and b"IDAT" in kinds and kinds[-1] == b"IEND"


def valid_configuration(metadata: dict, expected: dict, size: tuple[int, int]) -> bool:
    matches = all(metadata.get(key) == value for key, value in expected.items())
    booleans = metadata.get("synthetic") is True and type(metadata.get("dark")) is bool
    geometry = size == (metadata.get("width"), metadata.get("height"))
    return matches and booleans and geometry and metadata.get("density", 0) > 0 and bool(metadata.get("renderedLocale"))


def verify_captures(directory: Path) -> list[dict]:
    images = sorted(directory.rglob("history-*.png"))
    if {path.stem for path in images} != set(CAPTURES) or len(images) != len(CAPTURES):
        raise ValueError("Missing, extra or duplicate native history screenshots")
    receipts = []
    for path in images:
        metadata = json.loads(path.with_suffix(".json").read_text())
        orientation, dark, font = CAPTURES[path.stem]
        size = png_size(path.read_bytes())
        expected = dict(name=path.stem, synthetic=True, api=36, orientation=orientation, dark=dark, fontScale=font)
        if not valid_configuration(metadata, expected, size):
            raise ValueError(f"Invalid observed capture configuration: {path.name}")
        if (orientation == 1 and size[0] >= size[1]) or (orientation == 2 and size[0] <= size[1]):
            raise ValueError(f"PNG aspect does not match observed orientation: {path.name}")
        receipts.append(dict(metadata, path=str(path), pngSha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                             metadataSha256=hashlib.sha256(path.with_suffix(".json").read_bytes()).hexdigest()))
    if len({item["density"] for item in receipts}) != 1:
        raise ValueError("Capture target density changed")
    return receipts


def main() -> None:
    captures = verify_captures(ROOT / "app/build/outputs/connected_android_test_additional_output")
    destination = ROOT / "evidence/native/history-text-captures.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(dict(current_identity(), captures=captures,
                                           visualInspection="pending independent actual-image review"), indent=2) + "\n")
    print(f"Collected {len(captures)} native component screenshots; image review is not automated")


if __name__ == "__main__":
    main()
