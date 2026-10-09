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


# Bitmap.compress uses Skia's non-interlaced direct-color PNG encoder. Support
# its grayscale/RGB/gray-alpha/RGBA 8/16-bit output, not palette or Adam7 files.
# These budgets exceed the hosted phone capture sizes, without trusting IHDR
# to authorize unbounded allocation, inflation or millions of tiny chunks.
MAX_PNG_BYTES = 64 * 1024 * 1024
MAX_PIXEL_BYTES = 64 * 1024 * 1024
MAX_DIMENSION = 8192
MAX_CHUNKS = 8192
PNG_CHANNELS = {0: 1, 2: 3, 4: 2, 6: 4}


def png_chunks(data: bytes) -> list[tuple[bytes, bytes]]:
    if len(data) > MAX_PNG_BYTES:
        raise ValueError("PNG file exceeds capture budget")
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Missing PNG signature")
    offset = 8
    chunks = []
    while offset < len(data):
        if len(chunks) >= MAX_CHUNKS:
            raise ValueError("PNG chunk count exceeds capture budget")
        if len(data) - offset < 12:
            raise ValueError("Truncated PNG chunk")
        length = struct.unpack_from(">I", data, offset)[0]
        kind = data[offset + 4:offset + 8]
        end = offset + 12 + length
        if end > len(data):
            raise ValueError("Truncated PNG chunk")
        if not kind.isalpha() or kind[2] & 32:
            raise ValueError("Invalid PNG chunk type")
        body = data[offset + 8:end - 4]
        if zlib.crc32(body, zlib.crc32(kind)) != struct.unpack_from(">I", data, end - 4)[0]:
            raise ValueError("Invalid PNG chunk checksum")
        chunks.append((kind, body))
        offset = end
    return chunks


def png_header(chunk: tuple[bytes, bytes]) -> tuple[int, int, int, int]:
    kind, body = chunk
    if kind != b"IHDR" or len(body) != 13:
        raise ValueError("Invalid PNG header")
    width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", body)
    if depth not in (8, 16) or color not in PNG_CHANNELS:
        raise ValueError("Unsupported capture PNG pixel format")
    if (compression, filtering, interlace) != (0, 0, 0):
        raise ValueError("Unsupported capture PNG encoding")
    if not (0 < width <= MAX_DIMENSION and 0 < height <= MAX_DIMENSION):
        raise ValueError("PNG dimensions exceed capture budget")
    stride = 1 + width * PNG_CHANNELS[color] * (depth // 8)
    if stride * height > MAX_PIXEL_BYTES:
        raise ValueError("PNG pixels exceed capture budget")
    return width, height, stride, color


def png_extra_chunk(kind: bytes, body: bytes, color: int, palette: bool, started: bool) -> bool:
    if kind == b"PLTE":
        if palette or started or color not in (2, 6):
            raise ValueError("Invalid PNG palette order or pixel format")
        if not body or len(body) > 768 or len(body) % 3:
            raise ValueError("Invalid PNG palette")
        return True
    if not kind[0] & 32:
        raise ValueError("Unexpected critical PNG chunk")
    # Ancillary metadata is not needed to decode direct-color pixel samples.
    return palette


def png_image_data(chunks: list[tuple[bytes, bytes]], color: int) -> bytes:
    if chunks[-1] != (b"IEND", b""):
        raise ValueError("Invalid PNG image trailer")
    bodies = []
    closed = False
    palette = False
    for kind, body in chunks[1:-1]:
        if kind == b"IDAT":
            if closed:
                raise ValueError("Nonconsecutive PNG image data")
            bodies.append(body)
        else:
            palette = png_extra_chunk(kind, body, color, palette, bool(bodies))
            closed = bool(bodies)
    if not bodies:
        raise ValueError("Missing PNG image data")
    return b"".join(bodies)


def validate_png_pixels(compressed: bytes, stride: int, height: int) -> None:
    expected = stride * height
    decoder = zlib.decompressobj()
    try:
        # Never flush without a bound. One extra byte detects overlong/bomb data.
        pixels = decoder.decompress(compressed, expected + 1)
    except zlib.error as error:
        raise ValueError("Undecodable PNG pixel stream") from error
    if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError("Incomplete or trailing PNG pixel stream")
    if len(pixels) != expected:
        raise ValueError("Invalid PNG scanline length")
    if any(pixels[offset] > 4 for offset in range(0, expected, stride)):
        raise ValueError("Invalid PNG scanline filter")
    # For these direct-color formats every channel bit pattern is a valid sample.
    # Filters 0..4 are total, invertible byte transformations: exact complete rows
    # with legal filter bytes prove pixel decodability without another full image
    # allocation or a Python per-pixel reconstruction loop. This is not CRC-only.


def png_size(data: bytes) -> tuple[int, int]:
    chunks = png_chunks(data)
    if not chunks:
        raise ValueError("Incomplete PNG")
    width, height, stride, color = png_header(chunks[0])
    validate_png_pixels(png_image_data(chunks, color), stride, height)
    return width, height


def read_png(path: Path) -> bytes:
    with path.open("rb") as stream:
        data = stream.read(MAX_PNG_BYTES + 1)
    if len(data) > MAX_PNG_BYTES:
        raise ValueError("PNG file exceeds capture budget")
    return data


def valid_configuration(metadata: dict, expected: dict, size: tuple[int, int]) -> bool:
    matches = all(metadata.get(key) == value for key, value in expected.items())
    booleans = metadata.get("synthetic") is True and type(metadata.get("dark")) is bool
    geometry = size == (metadata.get("width"), metadata.get("height"))
    return matches and booleans and geometry and metadata.get("density", 0) > 0 and bool(metadata.get("renderedLocale"))


def capture_receipt(path: Path) -> dict:
    metadata = json.loads(path.with_suffix(".json").read_text())
    orientation, dark, font = CAPTURES[path.stem]
    image = read_png(path)
    size = png_size(image)
    expected = dict(name=path.stem, synthetic=True, api=36, orientation=orientation, dark=dark, fontScale=font)
    if not valid_configuration(metadata, expected, size):
        raise ValueError(f"Invalid observed capture configuration: {path.name}")
    if (orientation == 1 and size[0] >= size[1]) or (orientation == 2 and size[0] <= size[1]):
        raise ValueError(f"PNG aspect does not match observed orientation: {path.name}")
    return dict(metadata, path=str(path), pngSha256=hashlib.sha256(image).hexdigest(),
                metadataSha256=hashlib.sha256(path.with_suffix(".json").read_bytes()).hexdigest())


def verify_captures(directory: Path) -> list[dict]:
    images = sorted(directory.rglob("history-*.png"))
    if {path.stem for path in images} != set(CAPTURES) or len(images) != len(CAPTURES):
        raise ValueError("Missing, extra or duplicate native history screenshots")
    receipts = [capture_receipt(path) for path in images]
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
