"""Independent dashboard capture/count oracles.

Mandatory dashboard method names live in verify_test_reports, not a second list here.
"""

EXPECTED_DASHBOARD_CAPTURE_SPECS = {
    "dashboard-main-portrait-light": (1, False, 1.0, "DASHBOARD", "CURRENT", "FIVE_HOUR"),
    "dashboard-weekly-portrait-light": (1, False, 1.0, "DASHBOARD", "CURRENT", "WEEKLY"),
    "dashboard-banked-table-portrait-light": (1, False, 1.0, "DASHBOARD", "CURRENT", "NONE"),
    "dashboard-drawer-portrait-dark": (1, True, 1.0, "DASHBOARD", "CURRENT", "NONE"),
    "dashboard-main-landscape-dark-large-font": (2, True, 2.0, "DASHBOARD", "CURRENT", "FIVE_HOUR"),
    "dashboard-connection-details-landscape-dark": (2, True, 1.0, "CONNECTION_DETAILS", "IDLE", "NONE"),
    "dashboard-local-history-details-large-font": (1, False, 2.0, "LOCAL_HISTORY_DETAILS", "PAGE", "NONE"),
}
EXPECTED_DASHBOARD_LAYOUTS = {
    "dashboard-main-portrait-light": ("history-chart-five-hour-pace", ["connection-screen", "dashboard"]),
    "dashboard-weekly-portrait-light": ("history-chart-weekly-pace", ["connection-screen", "dashboard"]),
    "dashboard-banked-table-portrait-light": ("banked-disclosure-title", ["connection-screen", "dashboard"]),
    "dashboard-drawer-portrait-dark": ("drawer-LOCAL_HISTORY_DETAILS", ["connection-screen", "dashboard", "details-drawer"]),
    "dashboard-main-landscape-dark-large-font": ("history-chart-five-hour-pace", ["connection-screen", "dashboard"]),
    "dashboard-connection-details-landscape-dark": ("banked-container", ["connection-screen", "connection-details"]),
    "dashboard-local-history-details-large-font": ("history-page-cursor", ["connection-screen", "connection-history"]),
}
EXPECTED_BANKED_TABLE_ROWS = [
    dict(expiry="Jan 15, 09:00", remaining="1 hour", textCellCount=2,
         description="Banked reset 1, expires Jan 15, 09:00, 1 hour remaining"),
    dict(expiry="Jan 15, 07:00", remaining="Expired", textCellCount=2,
         description="Banked reset 2, expired Jan 15, 07:00"),
    dict(expiry="—", remaining="Unknown", textCellCount=2,
         description="Banked reset 3, expiry unknown, state Unknown"),
]

EXPECTED_COMPACT_LEGEND = (
    "Solid line: measured observations connected in time order; single observation: short tick\n"
    "Faint short dashes: least-squares usage trend when available\n"
    "Long dashes: nominal analytical full-quota reference, not a forecast"
)
EXPECTED_COMPACT_COPY = {
    # Chart captures observe 08:37:59: retain minutes, never truncate to 08:00 or round to 08:38.
    kind: dict(updated="Updated Jan 15, 08:37", first=first, last="Jan 15, 09:00",
               legendDescription=EXPECTED_COMPACT_LEGEND, visibleLegendCount=0)
    for kind, first in (("FIVE_HOUR", "Jan 15, 04:00"), ("WEEKLY", "Jan 8, 09:00"))
}

EXPECTED_CURRENT_HOST = {"context": "MATCH", "selection": "MATCH", "periods": "MATCH", "projection": "MATCH"}

# Literal review budgets, independently checked against the enumerated sets.
EXPECTED_DASHBOARD_NATIVE_COUNT = 17
EXPECTED_DASHBOARD_JVM_COUNT = 27
