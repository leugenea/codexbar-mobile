"""Fail-closed collection receipt for synthetic native history component captures.

This validates artifact/configuration integrity, not visual appearance. Native JUnit
establishes the labels/layout/actions; independent actual-image inspection is still required.
"""
import hashlib
import json
import math
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


CHART_CAPTURES = {
    "history-chart-reference-portrait-light": (1, False, 1.0),
    "history-chart-measured-portrait-dark": (1, True, 1.0),
    "history-chart-gap-landscape-dark": (2, True, 1.0),
    "history-chart-unknown-large-font": (1, False, 2.0),
    "history-chart-measured-landscape-light": (2, False, 1.0),
    "history-chart-invalid-portrait-light": (1, False, 1.0),
}
CHART_COUNTS = {
    "history-chart-reference-portrait-light": (1, 0, 1),
    "history-chart-measured-portrait-dark": (4, 3, 0),
    "history-chart-gap-landscape-dark": (4, 2, 0),
    "history-chart-unknown-large-font": (1, 0, 0),
    "history-chart-measured-landscape-light": (4, 3, 0),
    "history-chart-invalid-portrait-light": (0, 0, 0),
}
# Keep all four textual captures and their exact configuration assertions. The
# same collector/workflow now also requires the bounded actual chart gallery.
CAPTURES.update(CHART_CAPTURES)

INTEGRATED_CAPTURES = {
    "history-integrated-navigation-portrait-light": (1, False, 1.0),
    "history-integrated-chart-portrait-light": (1, False, 1.0),
}
INTEGRATED_CONTROLS = {
    "history-integrated-navigation-portrait-light": ["connection-tab", "history-select-FIVE_HOUR",
                                                    "history-select-WEEKLY", "history-first-page", "history-next-page"],
    "history-integrated-chart-portrait-light": ["connection-tab"],
}
CAPTURES.update(INTEGRATED_CAPTURES)

# Representative assembled acceptance classes, not a theme/orientation Cartesian
# product. Earlier twelve captures and their exact invariants remain mandatory.
ASSEMBLED_CAPTURES = {
    "history-assembled-fresh-portrait-light": (1, False, 1.0),
    "history-assembled-single-portrait-dark": (1, True, 1.0),
    "history-assembled-breaks-landscape-dark": (2, True, 1.0),
    "history-assembled-unknown-large-font": (1, False, 2.0),
    "history-assembled-page-landscape-light": (2, False, 1.0),
}
ASSEMBLED_PAGES = {
    "history-assembled-fresh-portrait-light": ("IDLE", "UNAVAILABLE", "NONE", 256, 0, 0, 0, 0, 0, 0),
    "history-assembled-single-portrait-dark": ("RESTORED", "READY", "FIVE_HOUR", 32, 0, 1, 1, 1, 5, 2),
    "history-assembled-breaks-landscape-dark": ("RESTORED", "READY", "FIVE_HOUR", 32, 0, 1, 7, 7, 5, 2),
    "history-assembled-unknown-large-font": ("RESTORED", "READY", "FIVE_HOUR", 32, 0, 1, 1, 1, 5, 2),
    "history-assembled-page-landscape-light": ("RESTORED", "READY", "FIVE_HOUR", 32, 32, 33, 35, 3, 5, 2),
}
ASSEMBLED_CONTROLS = {
    "history-assembled-fresh-portrait-light": ["connection-tab", "history-select-FIVE_HOUR", "history-select-WEEKLY"],
    "history-assembled-single-portrait-dark": ["connection-tab"],
    "history-assembled-breaks-landscape-dark": ["connection-tab"],
    "history-assembled-unknown-large-font": ["connection-tab"],
    "history-assembled-page-landscape-light": ["connection-tab", "history-first-page", "history-next-page"],
}
ASSEMBLED_COUNTS = {
    "history-assembled-single-portrait-dark": (1, 0, 1, 0),
    "history-assembled-breaks-landscape-dark": (6, 2, 0, 3),
    "history-assembled-unknown-large-font": (1, 0, 0, 0),
}
CAPTURES.update(ASSEMBLED_CAPTURES)


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


def verify_chart_metadata(metadata: dict, name: str, size: tuple[int, int]) -> None:
    expected = dict(zip(("markerCount", "connectionCount", "referenceCount"), CHART_COUNTS[name]))
    if any(type(metadata.get(key)) is not int or metadata[key] != value for key, value in expected.items()):
        raise ValueError("Chart capture drawing identities/counts changed")
    if metadata.get("pixelOraclePassed") is not True:
        raise ValueError("Chart capture lacks native pixel oracle")
    verify_canvas_bounds(metadata, size)


def verify_canvas_bounds(metadata: dict, size: tuple[int, int], prefix: str = "canvas") -> None:
    bounds = [metadata.get(prefix + side) for side in ("Left", "Top", "Right", "Bottom")]
    if any(type(value) not in (int, float) or not math.isfinite(value) for value in bounds):
        raise ValueError("Chart capture lacks finite observed Canvas bounds")
    left, top, right, bottom = bounds
    if not (0 <= left < right <= size[0] and 0 <= top < bottom <= size[1]):
        raise ValueError("Chart Canvas is not entirely within captured pixels")


def verify_integrated_canvas(metadata: dict, size: tuple[int, int]) -> None:
    # Only integrated captures add independent full/unclipped and clipped bounds.
    # Keep inherited component receipts and their native pixel oracles unchanged.
    verify_canvas_bounds(metadata, size)
    verify_canvas_bounds(metadata, size, "canvasClipped")
    if any(metadata["canvas" + side] != metadata["canvasClipped" + side]
           for side in ("Left", "Top", "Right", "Bottom")):
        raise ValueError("Integrated Canvas is partially clipped in its captured root")


def verify_integrated_metadata(metadata: dict, name: str, size: tuple[int, int]) -> None:
    expected = dict(queryLimit=32, queryAfter=0, firstOrdinal=1, lastOrdinal=32, pageEntries=32, requests=5, gets=2)
    if any(type(metadata.get(key)) is not int or metadata[key] != value for key, value in expected.items()):
        raise ValueError("Integrated capture lacks bounded dormant page/request identities")
    if metadata.get("productionEntry") is not True or metadata.get("hasMore") is not True:
        raise ValueError("Integrated capture is not a production entry with more retained data")
    identity = (metadata.get("activity"), metadata.get("phase"), metadata.get("queryKind"), metadata.get("visibleControls"))
    if identity != ("io.github.leugenea.codexbarmobile.MainActivity", "RESTORED", "FIVE_HOUR", INTEGRATED_CONTROLS[name]):
        raise ValueError("Integrated navigation/owner identity changed")
    if name == "history-integrated-chart-portrait-light":
        verify_integrated_canvas(metadata, size)


def paeth(left: int, above: int, corner: int) -> int:
    prediction = left + above - corner
    distances = (abs(prediction - left), abs(prediction - above), abs(prediction - corner))
    return (left, above, corner)[distances.index(min(distances))]


def reconstruct_row(filtered: bytes, previous: bytes, channels: int, kind: int) -> bytes:
    if kind == 0:
        return filtered
    if kind == 2:
        return bytes((value + previous[index]) & 255 for index, value in enumerate(filtered))
    row = bytearray(filtered)
    for index, value in enumerate(filtered):
        left = row[index - channels] if index >= channels else 0
        if kind == 1:
            predictor = left
        else:
            above = previous[index]
            corner = previous[index - channels] if index >= channels else 0
            predictor = (left + above) // 2 if kind == 3 else paeth(left, above, corner)
        row[index] = (value + predictor) & 255
    return bytes(row)


def decoded_samples(image: bytes, samples: list[dict]) -> list[int]:
    chunks = png_chunks(image)
    width, height, stride, color = png_header(chunks[0])
    # New native Bitmap sample receipts use opaque 8-bit RGB/RGBA. Preserve the
    # inherited decoder's wider 8/16-bit direct-color integrity contract.
    if chunks[0][1][8] != 8 or color not in (2, 6):
        raise ValueError("Unsupported assembled pixel sample format")
    coordinates = []
    for item in samples:
        x, y = item.get("x"), item.get("y")
        if type(x) is not int or type(y) is not int or not (0 <= x < width and 0 <= y < height):
            raise ValueError("Invalid assembled pixel registration")
        coordinates.append((int(x), int(y)))
    compressed = png_image_data(chunks, color)
    validate_png_pixels(compressed, stride, height)
    pixels = zlib.decompressobj().decompress(compressed, stride * height + 1)
    channels = PNG_CHANNELS[color]
    previous = bytes(stride - 1)
    wanted = {}
    for x, y in set(coordinates):
        wanted.setdefault(y, []).append(x)
    observed = {}
    for y in range(max(item[1] for item in coordinates) + 1):
        start = y * stride
        row = reconstruct_row(pixels[start + 1:start + stride], previous, channels, pixels[start])
        for x in wanted.get(y, []):
            offset = x * channels
            red, green, blue = row[offset:offset + 3]
            alpha = row[offset + 3] if channels == 4 else 255
            value = (alpha << 24) | (red << 16) | (green << 8) | blue
            observed[x, y] = value if value < 2 ** 31 else value - 2 ** 32
        previous = row
    return [observed[coordinate] for coordinate in coordinates]


def verify_sample(item: dict, actual: int) -> None:
    target = item.get("targetArgb")
    if type(target) is not int or not (-2 ** 31 <= target < 2 ** 31):
        raise ValueError("Invalid assembled target color")
    matched = all(abs(((actual >> shift) & 255) - ((target >> shift) & 255)) <= 12 for shift in (0, 8, 16))
    if type(item.get("actualArgb")) is not int or item["actualArgb"] != actual or item.get("matched") is not matched:
        raise ValueError("Assembled sample receipt disagrees with decoded PNG pixels")
    required = {"marker": True, "edge": True, "break": False, "dash": matched}
    if item.get("kind") not in required or matched != required[item["kind"]]:
        raise ValueError("Assembled native paint oracle failed")


def verify_assembled_pixels(metadata: dict, name: str, image: bytes) -> None:
    counts = ASSEMBLED_COUNTS[name]
    keys = ("markerCount", "connectionCount", "referenceCount", "boundaryCount")
    if any(type(metadata.get(key)) is not int or metadata[key] != value for key, value in zip(keys, counts)):
        raise ValueError("Assembled drawing identities/counts changed")
    samples = metadata.get("pixelSamples")
    expected = dict(zip(("marker", "edge", "dash", "break"), (counts[0], counts[1], counts[2] * 56, counts[3])))
    if not isinstance(samples, list) or not samples or len(samples) > 512 or any(not isinstance(item, dict) for item in samples):
        raise ValueError("Missing/bounded assembled PNG sample evidence")
    observed = {kind: sum(item.get("kind") == kind for item in samples) for kind in expected}
    if observed != expected or sum(observed.values()) != len(samples) or metadata.get("pixelOraclePassed") is not True:
        raise ValueError("Missing assembled native pixel oracle/sample identities")
    for item in samples:
        x, y = item.get("x"), item.get("y")
        if type(x) is not int or type(y) is not int or not (metadata["canvasLeft"] <= x < metadata["canvasRight"] and
                                                                         metadata["canvasTop"] <= y < metadata["canvasBottom"]):
            raise ValueError("Assembled pixel sample is outside its full Canvas")
    for item, actual in zip(samples, decoded_samples(image, samples)):
        verify_sample(item, actual)
    dashes = [item["matched"] for item in samples if item["kind"] == "dash"]
    if dashes and (dashes.count(True) < 10 or dashes.count(False) < 10):
        raise ValueError("Analytical reference lacks actual painted pixels or dash gaps")


def verify_assembled_metadata(metadata: dict, name: str, size: tuple[int, int], image: bytes) -> None:
    keys = ("phase", "readiness", "queryKind", "queryLimit", "queryAfter", "firstOrdinal", "lastOrdinal", "pageEntries", "requests", "gets")
    expected = dict(zip(keys, ASSEMBLED_PAGES[name]))
    if any(type(metadata.get(key)) is not type(value) or metadata[key] != value for key, value in expected.items()):
        raise ValueError("Assembled owner/page/request identities changed")
    if metadata.get("productionEntry") is not True or metadata.get("hasMore") is not False:
        raise ValueError("Assembled capture is not an honest bounded production entry")
    if metadata.get("activity") != "io.github.leugenea.codexbarmobile.MainActivity" or metadata.get("visibleControls") != ASSEMBLED_CONTROLS[name]:
        raise ValueError("Assembled Activity/visible control identities changed")
    numbers = ("fontScale", "density", "layoutFontScale", "layoutDensity")
    if any(type(metadata.get(key)) not in (int, float) or not math.isfinite(metadata[key]) or metadata[key] <= 0 for key in numbers):
        raise ValueError("Invalid effective native configuration")
    if type(metadata.get("orientation")) is not int or metadata.get("layoutFontScale") != metadata["fontScale"] or metadata.get("layoutDensity") != metadata["density"]:
        raise ValueError("Effective native layout configuration disagrees with resource configuration")
    after = expected["queryAfter"]
    if metadata.get("uiCursor") != f"Exclusive admission cursor: {after} · Page limit: 32":
        raise ValueError("Assembled visible UI cursor changed")
    if name in ASSEMBLED_COUNTS:
        verify_integrated_canvas(metadata, size)
        verify_assembled_pixels(metadata, name, image)


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
    if path.stem in CHART_CAPTURES:
        verify_chart_metadata(metadata, path.stem, size)
    if path.stem in INTEGRATED_CAPTURES:
        verify_integrated_metadata(metadata, path.stem, size)
    if path.stem in ASSEMBLED_CAPTURES:
        verify_assembled_metadata(metadata, path.stem, size, image)
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
    print(f"Collected {len(captures)} native history screenshots; image review is not automated")


if __name__ == "__main__":
    main()
