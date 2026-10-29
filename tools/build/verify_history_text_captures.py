"""Fail-closed collection receipt for synthetic native history component captures.

This validates artifact/configuration integrity, not visual appearance. Native JUnit
establishes the labels/layout/actions; independent actual-image inspection is still required.
"""
import hashlib
from io import BytesIO
import json
import math
from pathlib import Path
import warnings

from PIL import Image

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
    "history-chart-gap-landscape-dark": (4, 3, 0),
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
    "history-integrated-navigation-portrait-light": ["history-select-FIVE_HOUR",
                                                    "history-select-WEEKLY", "history-first-page", "history-next-page"],
    "history-integrated-chart-portrait-light": [],
}
CAPTURES.update(INTEGRATED_CAPTURES)

# Representative assembled viewports, not a theme/orientation Cartesian product.
ASSEMBLED_CAPTURES = {
    "history-assembled-fresh-portrait-light": (1, False, 1.0),
    "history-assembled-single-portrait-dark": (1, True, 1.0),
    "history-assembled-breaks-landscape-dark": (2, True, 1.0),
    "history-assembled-unknown-large-font": (1, False, 2.0),
    "history-assembled-page-landscape-light": (2, False, 1.0),
    "history-current-light-portrait": (1, False, 1.0),
    "history-current-dark-portrait": (1, True, 1.0),
}
ASSEMBLED_CONTROLS = {
    "history-assembled-fresh-portrait-light": ["open-drawer", "open-account-settings"],
    "history-assembled-single-portrait-dark": [],
    "history-assembled-breaks-landscape-dark": ["history-select-FIVE_HOUR", "history-select-WEEKLY"],
    "history-assembled-unknown-large-font": ["history-select-FIVE_HOUR", "history-select-WEEKLY"],
    "history-assembled-page-landscape-light": ["history-first-page", "history-next-page"],
    "history-current-light-portrait": [],
    "history-current-dark-portrait": [],
}
ASSEMBLED_COUNTS = {
    "history-assembled-single-portrait-dark": (1, 0, 1, 0),
    "history-current-light-portrait": (5, 4, 2, 2),
    "history-current-dark-portrait": (5, 4, 2, 2),
}
CAPTURES.update(ASSEMBLED_CAPTURES)


PRODUCTION_ROUTES = {
    "history-integrated-navigation-portrait-light": "LOCAL_HISTORY_DETAILS",
    "history-integrated-chart-portrait-light": "DASHBOARD",
    "history-assembled-fresh-portrait-light": "DASHBOARD",
    "history-assembled-single-portrait-dark": "DASHBOARD",
    "history-assembled-breaks-landscape-dark": "LOCAL_HISTORY_DETAILS",
    "history-assembled-unknown-large-font": "LOCAL_HISTORY_DETAILS",
    "history-assembled-page-landscape-light": "LOCAL_HISTORY_DETAILS",
    "history-current-light-portrait": "DASHBOARD",
    "history-current-dark-portrait": "DASHBOARD",
}
DASHBOARD_CAPTURES = {
    "dashboard-main-portrait-light": (1, False, 1.0, "DASHBOARD", "CURRENT", "FIVE_HOUR"),
    "dashboard-weekly-portrait-light": (1, False, 1.0, "DASHBOARD", "CURRENT", "WEEKLY"),
    "dashboard-banked-table-portrait-light": (1, False, 1.0, "DASHBOARD", "CURRENT", "NONE"),
    "dashboard-drawer-portrait-dark": (1, True, 1.0, "DASHBOARD", "CURRENT", "NONE"),
    "dashboard-main-landscape-dark-large-font": (2, True, 2.0, "DASHBOARD", "CURRENT", "FIVE_HOUR"),
    "dashboard-connection-details-landscape-dark": (2, True, 1.0, "CONNECTION_DETAILS", "IDLE", "NONE"),
    "dashboard-local-history-details-large-font": (1, False, 2.0, "LOCAL_HISTORY_DETAILS", "PAGE", "NONE"),
}

DASHBOARD_LAYOUTS = {
    "dashboard-main-portrait-light": ("history-chart-five-hour-pace", ["connection-screen", "dashboard"]),
    "dashboard-weekly-portrait-light": ("history-chart-weekly-pace", ["connection-screen", "dashboard"]),
    "dashboard-banked-table-portrait-light": ("banked-disclosure-title", ["connection-screen", "dashboard"]),
    "dashboard-drawer-portrait-dark": ("drawer-LOCAL_HISTORY_DETAILS", ["connection-screen", "dashboard", "details-drawer"]),
    "dashboard-main-landscape-dark-large-font": ("history-chart-five-hour-pace", ["connection-screen", "dashboard"]),
    "dashboard-connection-details-landscape-dark": ("banked-container", ["connection-screen", "connection-details"]),
    "dashboard-local-history-details-large-font": ("history-page-cursor", ["connection-screen", "connection-history"]),
}


BANKED_TABLE_ROWS = [
    dict(expiry="Jan 15, 09:00", remaining="1 hour", textCellCount=2,
         description="Banked reset 1, expires Jan 15, 09:00, 1 hour remaining"),
    dict(expiry="Jan 15, 07:00", remaining="Expired", textCellCount=2,
         description="Banked reset 2, expired Jan 15, 07:00"),
    dict(expiry="—", remaining="Unknown", textCellCount=2,
         description="Banked reset 3, expiry unknown, state Unknown"),
]

# Bounds cover the hosted phone captures before pixel allocation. Pillow owns
# PNG parsing, checksums, filters and decompression; it is tooling-only.
MAX_PNG_BYTES = 64 * 1024 * 1024
MAX_PIXEL_BYTES = 64 * 1024 * 1024
MAX_DIMENSION = 8192


def decode_png(data: bytes) -> Image.Image:
    if len(data) > MAX_PNG_BYTES:
        raise ValueError("PNG file exceeds capture budget")
    # Pillow verify() stops at IEND's header; require its complete fixed trailer
    # as well, rather than accepting a file truncated in the final checksum.
    if not data.endswith(b"\0\0\0\0IEND\xaeB`\x82"):
        raise ValueError("Incomplete PNG trailer")
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(data), formats=["PNG"]) as image:
                width, height = image.size
                if not (0 < width <= MAX_DIMENSION and 0 < height <= MAX_DIMENSION):
                    raise ValueError("PNG dimensions exceed capture budget")
                if width * height * 4 > MAX_PIXEL_BYTES:
                    raise ValueError("PNG pixels exceed capture budget")
                if image.n_frames != 1:
                    raise ValueError("Capture PNG must be a single image")
                image.verify()
            # verify() does not decode pixels and invalidates the first image.
            with Image.open(BytesIO(data), formats=["PNG"]) as image:
                image.load()
                return image.convert("RGBA")
    except (OSError, SyntaxError, Image.DecompressionBombError, Image.DecompressionBombWarning) as error:
        raise ValueError("Invalid capture PNG") from error


def read_png(path: Path) -> bytes:
    if path.is_symlink() or not path.is_file():
        raise ValueError("Capture must be a regular PNG file")
    with path.open("rb") as stream:
        data = stream.read(MAX_PNG_BYTES + 1)
    if len(data) > MAX_PNG_BYTES:
        raise ValueError("PNG file exceeds capture budget")
    return data


def valid_configuration(metadata: dict, expected: dict, size: tuple[int, int]) -> bool:
    matches = all(metadata.get(key) == value for key, value in expected.items())
    geometry = all(type(metadata.get(key)) is int and metadata[key] == value for key, value in zip(("width", "height"), size))
    numbers = all(type(metadata.get(key)) in (int, float) and math.isfinite(metadata[key]) and metadata[key] > 0
                  for key in ("density", "fontScale"))
    booleans = metadata.get("synthetic") is True and type(metadata.get("dark")) is bool
    return (matches and geometry and numbers and booleans and type(metadata.get("orientation")) is int
            and isinstance(metadata.get("renderedLocale"), str) and bool(metadata["renderedLocale"]))


def verify_chart_metadata(metadata: dict, name: str, size: tuple[int, int]) -> None:
    expected = dict(zip(("observationCount", "connectionCount", "referenceCount"), CHART_COUNTS[name]))
    if any(type(metadata.get(key)) is not int or metadata[key] != value for key, value in expected.items()):
        raise ValueError("Chart capture drawing identities/counts changed")
    trend = int(CHART_COUNTS[name][0] >= 2)
    if type(metadata.get("trendCount")) is not int or metadata["trendCount"] != trend:
        raise ValueError("Chart capture trend identities/count changed")
    if metadata.get("trendPixelOraclePassed") is not (name == "history-chart-measured-portrait-dark"):
        raise ValueError("Chart capture lacks the translucent dashed trend pixel oracle")
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


def verify_production_root(metadata: dict, expected_roots: tuple[str, ...] = ("connection-screen",)) -> None:
    # Tabs are retired. An observed real screen root remains mandatory even when
    # the chart viewport has no visible clickable controls.
    if metadata.get("visibleRoots") != list(expected_roots):
        raise ValueError("Production capture lacks the observed connection screen root")


def verify_integrated_metadata(metadata: dict, name: str, size: tuple[int, int]) -> None:
    verify_production_root(metadata)
    if metadata.get("route") != PRODUCTION_ROUTES[name]:
        raise ValueError("Integrated detail/dashboard route changed")
    if metadata.get("productionEntry") is not True or metadata.get("visibleControls") != INTEGRATED_CONTROLS[name]:
        raise ValueError("Integrated production entry/visible controls changed")
    if name == "history-integrated-chart-portrait-light":
        verify_integrated_canvas(metadata, size)


def decoded_samples(image: Image.Image, samples: list[dict]) -> list[int]:
    values = []
    for item in samples:
        x, y = item.get("x"), item.get("y")
        if type(x) is not int or type(y) is not int or not (0 <= x < image.width and 0 <= y < image.height):
            raise ValueError("Invalid pixel registration")
        red, green, blue, alpha = image.getpixel((x, y))
        value = (alpha << 24) | (red << 16) | (green << 8) | blue
        values.append(value if value < 2 ** 31 else value - 2 ** 32)
    return values


def verify_sample(item: dict, actual: int) -> None:
    target = item.get("targetArgb")
    if type(target) is not int or not (-2 ** 31 <= target < 2 ** 31):
        raise ValueError("Invalid assembled target color")
    matched = all(abs(((actual >> shift) & 255) - ((target >> shift) & 255)) <= 12 for shift in (0, 8, 16))
    if type(item.get("actualArgb")) is not int or item["actualArgb"] != actual or item.get("matched") is not matched:
        raise ValueError("Assembled sample receipt disagrees with decoded PNG pixels")
    required = {"observation": True, "edge": True, "bridge": True, "dash": matched}
    if item.get("kind") not in required or matched != required[item["kind"]]:
        raise ValueError("Assembled native paint oracle failed")


def verify_assembled_pixels(metadata: dict, name: str, image: Image.Image) -> None:
    counts = ASSEMBLED_COUNTS[name]
    keys = ("observationCount", "connectionCount", "referenceCount", "boundaryCount")
    if any(type(metadata.get(key)) is not int or metadata[key] != value for key, value in zip(keys, counts)):
        raise ValueError("Assembled drawing identities/counts changed")
    samples = metadata.get("pixelSamples")
    expected = dict(zip(("observation", "edge", "dash", "bridge"), (counts[0], counts[1], counts[2] * 56, counts[3])))
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
    if name.startswith("history-current-"):
        verify_current_targets(metadata, samples)
    for item, actual in zip(samples, decoded_samples(image, samples)):
        verify_sample(item, actual)
    dashes = [item["matched"] for item in samples if item["kind"] == "dash"]
    if dashes and (dashes.count(True) < 10 or dashes.count(False) < 10):
        raise ValueError("Analytical reference lacks actual painted pixels or dash gaps")



def verify_current_targets(metadata: dict, samples: list[dict]) -> None:
    # Material3 1.4.0 default palette: Primary40/80 and NeutralVariant30/80.
    primary, reference = ((-10006364, -11975345) if not metadata["dark"] else (-3097345, -3488560))
    for item in samples:
        expected = reference if item.get("kind") == "dash" else primary
        if item.get("targetArgb") != expected:
            raise ValueError("Current graph paint target differs from registered measured/reference role")


def verify_assembled_metadata(metadata: dict, name: str, size: tuple[int, int], image: Image.Image) -> None:
    verify_production_root(metadata)
    if metadata.get("route") != PRODUCTION_ROUTES[name]:
        raise ValueError("Assembled detail/dashboard route changed")
    if metadata.get("productionEntry") is not True or metadata.get("visibleControls") != ASSEMBLED_CONTROLS[name]:
        raise ValueError("Assembled production entry/visible controls changed")
    verify_layout_configuration(metadata)
    if metadata["route"] == "LOCAL_HISTORY_DETAILS":
        expected_label = "history-page-cursor"
    else:
        expected_label = "dashboard-no-info-FIVE_HOUR" if name == "history-assembled-fresh-portrait-light" else "history-chart-five-hour-pace"
    if metadata.get("layoutLabel") != expected_label:
        raise ValueError("Assembled visible layout label changed")
    if name in ASSEMBLED_COUNTS:
        verify_assembled_compact_legend(metadata)
        verify_integrated_canvas(metadata, size)
        verify_assembled_pixels(metadata, name, image)
    elif "compactLegend" in metadata:
        raise ValueError("Non-chart captures must not claim a compact legend")


def verify_assembled_compact_legend(metadata: dict) -> None:
    legend = ("Solid line: measured observations connected in time order; single observation: short tick\n"
              "Faint short dashes: least-squares usage trend when available\n"
              "Long dashes: nominal analytical full-quota reference, not a forecast")
    expected = dict(plotTag="history-chart-five-hour-plot", legendDescription=legend,
                    visibleLegendCount=0, hiddenFromAccessibility=False)
    receipt = metadata.get("compactLegend")
    if not isinstance(receipt, dict) or any(type(receipt.get(key)) is not type(value) or receipt[key] != value
                                            for key, value in expected.items()):
        raise ValueError("Assembled dashboard explanation must be exact, accessible and plot-only")


def capture_receipt(path: Path) -> dict:
    sidecar = path.with_suffix(".json")
    if sidecar.is_symlink() or not sidecar.is_file():
        raise ValueError("Capture metadata must be a regular file")
    metadata_bytes = sidecar.read_bytes()
    metadata = json.loads(metadata_bytes)
    data = read_png(path)
    with decode_png(data) as image:
        size = image.size
        if path.stem in LIVE_CAPTURES:
            verify_live_metadata(metadata, path.stem, size, image)
        elif path.stem in DASHBOARD_CAPTURES:
            verify_dashboard_metadata(metadata, path.stem, size, image)
        else:
            orientation, dark, font = CAPTURES[path.stem]
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
    return dict(metadata, path=str(path), pngSha256=hashlib.sha256(data).hexdigest(),
                metadataSha256=hashlib.sha256(metadata_bytes).hexdigest())


def verify_captures(directory: Path) -> list[dict]:
    images = sorted(directory.rglob("history-*.png"))
    if {path.stem for path in images} != set(CAPTURES) or len(images) != len(CAPTURES):
        raise ValueError("Missing, extra or duplicate native history screenshots")
    receipts = [capture_receipt(path) for path in images]
    if len({item["density"] for item in receipts}) != 1:
        raise ValueError("Capture target density changed")
    return receipts


# Separate screen/locale identities; history identities and all 17 inherited PNGs remain exact.
LIVE_CAPTURES = {"live-usage-portrait-light": False, "live-usage-portrait-dark": True}
LIVE_FACTS = {
    "live-five-hour-percent": "12.5% used",
    "live-weekly-percent": "76.25% used",
    "live-five-hour-reset-absolute": "Reset: Oct 8, 2026, 01:00",
    "live-five-hour-reset-relative": "Reset: 1 hour 30 minutes remaining",
    "live-weekly-reset-absolute": "Reset: Oct 10, 2026, 01:00",
    "live-weekly-reset-relative": "Reset: 2 days 1 hour remaining",
}


def verify_live_samples(metadata: dict, image: Image.Image, size: tuple[int, int]) -> None:
    samples = metadata.get("pixelSamples")
    kinds = {"background", "card", "content", "progress", "track"}
    if (not isinstance(samples, list) or len(samples) != len(kinds) or
            any(not isinstance(item, dict) for item in samples) or
            {item.get("kind") for item in samples} != kinds or metadata.get("pixelOraclePassed") is not True):
        raise ValueError("Missing live theme paint samples")
    for item, actual in zip(samples, decoded_samples(image, samples)):
        verify_live_sample_bounds(item, size)
        # Same channel tolerance/actual-byte check as the inherited chart oracle.
        verify_sample(dict(item, kind="observation"), actual)


def verify_live_sample_bounds(item: dict, size: tuple[int, int]) -> None:
    bounds = item.get("bounds")
    if not isinstance(bounds, list) or len(bounds) != 4:
        raise ValueError("Missing live sample owner bounds")
    if not all(type(value) in (int, float) and math.isfinite(value) for value in bounds):
        raise ValueError("Missing finite live sample owner bounds")
    left, top, right, bottom = bounds
    contained = (0 <= left < right <= size[0], 0 <= top < bottom <= size[1],
                 left <= item["x"] < right, top <= item["y"] < bottom)
    if not all(contained):
        raise ValueError("Live paint sample outside its native owner bounds")


def verify_live_metadata(metadata: dict, name: str, size: tuple[int, int], image: Image.Image) -> None:
    expected = dict(name=name, screen="live-usage", synthetic=True, api=36, orientation=1,
                    dark=LIVE_CAPTURES[name], fontScale=1.0, productionEntry=True,
                    activity="io.github.leugenea.codexbarmobile.MainActivity", renderedLocale="en-US", phase="OBSERVED")
    if not valid_configuration(metadata, expected, size) or size[0] >= size[1]:
        raise ValueError("Invalid observed live configuration or production screen identity")
    verify_production_root(metadata)
    if metadata.get("route") != "CONNECTION_DETAILS":
        raise ValueError("Live full facts must be on the connection detail route")
    if metadata.get("visibleFacts") != LIVE_FACTS or metadata.get("visibleControls") != []:
        raise ValueError("Live usage/reset/control identities changed")
    verify_layout_configuration(metadata)
    verify_live_samples(metadata, image, size)


def verify_layout_configuration(metadata: dict) -> None:
    for key in ("density", "fontScale", "layoutDensity", "layoutFontScale"):
        if type(metadata.get(key)) not in (int, float) or not math.isfinite(metadata[key]) or metadata[key] <= 0:
            raise ValueError("Invalid effective layout configuration")
    if metadata["density"] != metadata["layoutDensity"] or metadata["fontScale"] != metadata["layoutFontScale"]:
        raise ValueError("Native layout disagrees with resource configuration")


def verify_dashboard_metadata(metadata: dict, name: str, size: tuple[int, int], image: Image.Image) -> None:
    orientation, dark, font, route, mode, kind = DASHBOARD_CAPTURES[name]
    expected = dict(name=name, synthetic=True, productionEntry=True, api=36, orientation=orientation,
                    dark=dark, fontScale=font, route=route, requestMode=mode, sourceKind=kind,
                    activity="io.github.leugenea.codexbarmobile.MainActivity")
    if not valid_configuration(metadata, expected, size) or ((orientation == 1) != (size[0] < size[1])):
        raise ValueError("Dashboard capture route/configuration changed")
    verify_production_root(metadata, tuple(DASHBOARD_LAYOUTS[name][1]))
    verify_layout_configuration(metadata)
    if metadata.get("layoutLabel") != DASHBOARD_LAYOUTS[name][0]:
        raise ValueError("Dashboard native layout label changed")
    verify_dashboard_compact_copy(metadata, kind, font)
    if name == "dashboard-drawer-portrait-dark" and metadata.get("drawerLabels") != ["Full local history details", "Full live connection details", "Account settings"]:
        raise ValueError("Owner drawer rows changed")
    if name in {"dashboard-banked-table-portrait-light", "dashboard-connection-details-landscape-dark"}:
        if metadata.get("inventoryCount") != 7 or metadata.get("inventoryRows") != 3:
            raise ValueError("Reported count/retained inventory rows changed")
    if name == "dashboard-banked-table-portrait-light":
        rows = metadata.get("bankedTableRows")
        if rows != BANKED_TABLE_ROWS or any(type(row.get("textCellCount")) is not int for row in rows):
            raise ValueError("Banked table must retain two cells and announce each expiry/state/countdown once")
    if kind != "NONE":
        verify_dashboard_pixels(metadata, size, image)


def verify_dashboard_compact_copy(metadata: dict, kind: str, font: float) -> None:
    if kind == "NONE":
        if "compactCopy" not in metadata or metadata["compactCopy"] is not None:
            raise ValueError("Non-chart captures must not claim compact chart copy")
        return
    copy = metadata.get("compactCopy")
    first = "Jan 8, 09:00" if kind == "WEEKLY" else "Jan 15, 04:00"
    legend = ("Solid line: measured observations connected in time order; single observation: short tick\n"
              "Faint short dashes: least-squares usage trend when available\n"
              "Long dashes: nominal analytical full-quota reference, not a forecast")
    expected = dict(updated="Updated Jan 15, 08:37", first=first, last="Jan 15, 09:00",
                    legendDescription=legend, visibleLegendCount=0)
    if not isinstance(copy, dict) or any(copy.get(key) != value for key, value in expected.items()):
        raise ValueError("Compact header/axes or accessible-only legend changed")
    if type(copy["visibleLegendCount"]) is not int:
        raise ValueError("Visible legend count must be an integer")
    verify_compact_axis_lines(copy, font)


def verify_compact_axis_lines(copy: dict, font: float) -> None:
    for key in ("firstLineCount", "lastLineCount"):
        lines = copy.get(key)
        if type(lines) is not int or lines < 1 or (font == 1 and lines != 1):
            raise ValueError("Compact axis labels must fit one line at normal font and remain complete at large font")


def verify_dashboard_pixels(metadata: dict, size: tuple[int, int], image: Image.Image) -> None:
    verify_integrated_canvas(metadata, size)
    samples = metadata.get("pixelSamples")
    if metadata.get("pixelOraclePassed") is not True or not isinstance(samples, list):
        raise ValueError("Dashboard lacks actual registered pixel receipts")
    expected_counts = dict(observationCount=1, connectionCount=0, referenceCount=1, boundaryCount=0)
    if any(type(metadata.get(k)) is not int or metadata[k] != v for k, v in expected_counts.items()):
        raise ValueError("Dashboard single-point/reference paint identities changed")
    if [item.get("kind") for item in samples].count("observation") != 1 or [item.get("kind") for item in samples].count("dash") != 56:
        raise ValueError("Dashboard marker/dash samples missing or duplicated")
    if any(item.get("kind") not in {"observation", "dash"} for item in samples):
        raise ValueError("Dashboard single observation cannot claim measured edges")
    primary, reference = ((-10006364, -11975345) if not metadata["dark"] else (-3097345, -3488560))
    for item, actual in zip(samples, decoded_samples(image, samples)):
        if not (metadata["canvasLeft"] <= item["x"] < metadata["canvasRight"] and metadata["canvasTop"] <= item["y"] < metadata["canvasBottom"]):
            raise ValueError("Dashboard sample outside its actual Canvas")
        if item.get("targetArgb") != (reference if item["kind"] == "dash" else primary):
            raise ValueError("Dashboard paint target differs from registered role")
        verify_sample(item, actual)
    marker = next(item for item in samples if item["kind"] == "observation")
    dashes = [item for item in samples if item["kind"] == "dash"]
    if not marker["matched"] or sum(item["matched"] for item in dashes) < 10 or sum(not item["matched"] for item in dashes) < 10:
        raise ValueError("Dashboard actual marker/dashed hits and gaps required")


def verify_gallery(directory: Path) -> list[dict]:
    history = verify_captures(directory)
    live = sorted(directory.rglob("live-usage-*.png"))
    if {path.stem for path in live} != set(LIVE_CAPTURES) or len(live) != len(LIVE_CAPTURES):
        raise ValueError("Missing, extra or duplicate native live screenshots")
    dashboard = sorted(directory.rglob("dashboard-*.png"))
    if {path.stem for path in dashboard} != set(DASHBOARD_CAPTURES) or len(dashboard) != len(DASHBOARD_CAPTURES):
        raise ValueError("Missing, extra or duplicate native dashboard screenshots")
    receipts = history + [capture_receipt(path) for path in live + dashboard]
    if len({item["density"] for item in receipts}) != 1:
        raise ValueError("Capture gallery target density changed")
    return receipts


def main() -> None:
    captures = verify_gallery(ROOT / "app/build/outputs/connected_android_test_additional_output")
    destination = ROOT / "evidence/native/history-text-captures.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(dict(current_identity(), captures=captures,
                                           visualInspection="pending independent actual-image review"), indent=2) + "\n")
    print(f"Collected {len(captures)} native history/live screenshots; image review is not automated")


if __name__ == "__main__":
    main()
