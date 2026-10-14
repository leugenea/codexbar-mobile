"""Fail closed on permissions, cleartext-off and single-process launcher policy."""
import argparse
import json
import os
import hashlib
import subprocess
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"
NAMESPACE = "io.github.leugenea.codexbarmobile"
PACKAGE = NAMESPACE + ".dev"  # Default synthetic/debug API expectation, never APK-derived.
MAIN_ACTIVITY = NAMESPACE + ".MainActivity"
VARIANTS = {"debug": PACKAGE, "release": NAMESPACE}
RECEIVER_PERMISSION = PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
SOURCE_PERMISSIONS = frozenset({"android.permission.INTERNET"})
# androidx.core 1.18.0 already contributes this signature-only receiver permission.
APP_PERMISSIONS = SOURCE_PERMISSIONS | {RECEIVER_PERMISSION}


def check_permissions(permissions, expected=APP_PERMISSIONS) -> list[str]:
    actual = set(permissions)
    if actual != expected:
        raise ValueError(f"Permission allowlist mismatch: missing={sorted(expected - actual)}; "
                         f"unexpected={sorted(actual - expected, key=str)}")
    return sorted(actual)


def check_merge_policy(manifest, path: Path) -> None:
    for node in manifest.iter():
        if node.get(TOOLS + "node") in {"remove", "removeAll", "replace"}:
            raise ValueError(f"Destructive manifest merge directive: {path}")
        for directive in ("remove", "replace"):
            attributes = node.get(TOOLS + directive)
            if attributes is None:
                continue
            names = {name.strip().split(":")[-1] for name in attributes.split(",")}
            if (node.tag in {"permission", "uses-permission", "uses-permission-sdk-23"}
                    or names & {"usesCleartextTraffic", "networkSecurityConfig", "launchMode", "process"}):
                raise ValueError(f"Protected manifest policy merge directive: {path}")
        if node.get(ANDROID + "networkSecurityConfig") is not None:
            raise ValueError(f"Cleartext policy override is forbidden: {path}")


def check_source_manifests(root: Path) -> dict:
    # App variants must inherit the reviewed main policy, not merge an overlay.
    overlays = sorted(path for path in (root / "app/src").glob("*/AndroidManifest.xml")
                      if path.parent.name not in {"main", "test", "androidTest"})
    if overlays:
        raise ValueError(f"App manifest source-set overlays are forbidden: {overlays}")
    return check_manifest(root / "app/src/main/AndroidManifest.xml", SOURCE_PERMISSIONS)


def activity_name(name: str) -> str:
    if name.startswith("."):
        return NAMESPACE + name
    return name if "." in name else NAMESPACE + "." + name


def check_process_owner(application, path: Path, *, source=False) -> None:
    if any(node.get(ANDROID + "process") is not None for node in application.iter()):
        raise ValueError(f"App components must use the default single process: {path}")
    activities = [node for node in application.findall("activity")
                  if (activity_name(node.get(ANDROID + "name", "")) if source else node.get(ANDROID + "name")) == MAIN_ACTIVITY]
    if len(activities) != 1 or activities[0].get(ANDROID + "launchMode") != "singleTask":
        raise ValueError(f"MainActivity must declare singleTask launch mode: {path}")


def check_manifest(path: Path, expected=None, *, variant="debug", identity=None) -> dict:
    package, receiver, variant_permissions = variant_policy(variant)
    expected = variant_permissions if expected is None else expected
    if expected not in (SOURCE_PERMISSIONS, variant_permissions):
        raise ValueError("Permissions must come from reviewed source/variant")
    manifest = ET.parse(path).getroot()
    if manifest.tag != "manifest":
        raise ValueError(f"Not an Android manifest: {path}")
    check_merge_policy(manifest, path)
    nodes = [node for node in manifest if node.tag in ("uses-permission", "uses-permission-sdk-23")]
    if any(node.get(ANDROID + "maxSdkVersion") is not None for node in nodes):
        raise ValueError(f"Conditional manifest permission: {path}")
    permissions = check_permissions((node.get(ANDROID + "name") for node in nodes), expected)
    declarations = manifest.findall("permission")
    expected_declarations = {receiver} if expected != SOURCE_PERMISSIONS else set()
    if ({node.get(ANDROID + "name") for node in declarations} != expected_declarations
            or any(node.get(ANDROID + "protectionLevel") != "signature" for node in declarations)):
        raise ValueError(f"Permission declarations must preserve the signature-only receiver: {path}")
    applications = manifest.findall("application")
    if (len(applications) != 1 or applications[0].get(ANDROID + "usesCleartextTraffic") != "false"
            or applications[0].get(ANDROID + "networkSecurityConfig") is not None):
        raise ValueError(f"Cleartext must be explicitly disabled without a policy override: {path}")
    check_process_owner(applications[0], path, source=expected == SOURCE_PERMISSIONS)
    if identity is not None:
        check_generated_policy(manifest, variant, identity)
    return {"manifest": str(path), "requestedPermissions": permissions, "cleartextPermitted": False,
            "mainActivityLaunchMode": "singleTask", "defaultProcessOnly": True}


def xmltree_elements(xmltree: str, element: str) -> list[str]:
    lines = xmltree.splitlines()
    blocks = []
    for index, line in enumerate(lines):
        match = re.fullmatch(r"([ \t]*)E: " + re.escape(element) + r" \(line=\d+\)", line)
        if not match:
            continue
        indent = len(match[1])
        attributes = []
        for following in lines[index + 1:]:
            if following.strip() and len(following) - len(following.lstrip()) <= indent:
                break
            if following.lstrip().startswith("E:"):
                break
            attributes.append(following)
        blocks.append("\n".join(attributes))
    return blocks


def check_apk_process_owner(xmltree: str) -> None:
    if re.search(r"(?m)^\s*A: android:process\(", xmltree):
        raise ValueError("APK app components must use the default single process")
    activities = []
    for block in xmltree_elements(xmltree, "activity"):
        names = re.findall(r'android:name\([^\n]*?\)="([^"]+)"', block)
        if names == [MAIN_ACTIVITY]:
            activities.append(block)
    mode = r"(?m)^\s*A: android:launchMode\([^\n]*?\)=\(type 0x10\)0x2[ \t]*$"
    if len(activities) != 1 or len(re.findall(mode, activities[0])) != 1:
        raise ValueError("APK MainActivity must declare singleTask launch mode")


def check_apk_dump(permissions: str, xmltree: str, *, variant="debug", identity=None) -> list[str]:
    package, receiver, expected = variant_policy(variant)
    if not re.search(r"(?m)^package: " + re.escape(package) + r"\s*$", permissions):
        raise ValueError("Missing APK package identity")
    names = []
    for line in permissions.splitlines():
        if line.startswith("uses-permission"):
            match = re.fullmatch(r"uses-permission(?:-sdk-23)?: name='([^']+)'(?: maxSdkVersion='\d+')?", line)
            if not match or "maxSdkVersion" in line:
                raise ValueError("Malformed or conditional APK permission")
            names.append(match[1])
    result = check_permissions(names, expected)
    declarations = []
    # aapt XML-tree elements are nested by indentation; read only each declaration.
    lines = xmltree.splitlines()
    for index, line in enumerate(lines):
        match = re.fullmatch(r"([ \t]*)E: permission \(line=\d+\)", line)
        if not match:
            continue
        indent = len(match[1])
        attributes = []
        for following in lines[index + 1:]:
            if following.strip() and len(following) - len(following.lstrip()) <= indent:
                break
            attributes.append(following)
        block = "\n".join(attributes)
        name = re.findall(r'android:name\([^\n]*?\)="([^"]+)"', block)
        signature = re.findall(r"android:protectionLevel\([^\n]*?\)=\(type 0x11\)0x2[ \t]*$", block, re.M)
        if name != [receiver] or len(signature) != 1:
            raise ValueError("APK receiver permission must remain signature-only")
        declarations.append(name[0])
    if declarations != [receiver]:
        raise ValueError("APK receiver permission declaration missing or unexpected")
    cleartext = re.findall(r"(?m)^\s*A: android:usesCleartextTraffic\([^\n]*", xmltree)
    if (len(cleartext) != 1 or not re.search(r"=\(type 0x12\)0x0\s*$", cleartext[0])
            or "android:networkSecurityConfig(" in xmltree):
        raise ValueError("APK cleartext policy must be explicitly false without an override")
    check_apk_process_owner(xmltree)
    if identity is not None:
        check_apk_identity(xmltree, variant, identity)
    return result


def check_installed_dump(text: str, *, variant="debug", identity=None) -> list[str]:
    package, _, expected = variant_policy(variant)
    if len(re.findall(r"(?m)^\s*Package \[" + re.escape(package) + r"\]", text)) != 1:
        raise ValueError("Missing installed package identity")
    sections = re.findall(r"(?m)^([ \t]*)requested permissions:[ \t]*$", text)
    if len(sections) != 1:
        raise ValueError("Missing or ambiguous installed requested permissions")
    lines = text.splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == "requested permissions:")
    indent = len(sections[0])
    names = []
    for line in lines[start + 1:]:
        if not line.strip():
            continue
        if len(line) - len(line.lstrip()) <= indent:
            break
        names.append(line.strip())
    result = check_permissions(names, expected)
    if identity is not None:
        names = re.findall(r"(?m)^\s*versionName=(\S+)\s*$", text)
        codes = re.findall(r"(?m)^\s*versionCode=(\d+)\b", text)
        if names != [identity["versionName"]] or codes != [str(identity["versionCode"])]:
            raise ValueError("Installed version/source identity mismatch")
    # dumpsys does not expose FLAG_USES_CLEARTEXT_TRAFFIC in its flag-name table.
    # Installed cleartext/protection-level observations are asserted by androidTest.
    return result


def check_installed_path(text: str, *, variant="debug") -> None:
    package, _, _ = variant_policy(variant)
    paths = text.splitlines()
    pattern = (r"package:/data/app/(?:[^/\s]+/)*" + re.escape(package)
               + r"-[^/\s]+/(?:base|split_[^/\s]+)\.apk")
    if not paths or any(not re.fullmatch(pattern, path) for path in paths):
        raise ValueError("Missing or wrong installed package path")



def variant_policy(variant):
    if variant not in VARIANTS:
        raise ValueError("Unknown reviewed variant")
    package = VARIANTS[variant]
    receiver = package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    return package, receiver, SOURCE_PERMISSIONS | {receiver}


def source_metadata(identity):
    return {NAMESPACE + ".SOURCE_REVISION": identity["sourceSha"],
            NAMESPACE + ".SOURCE_TREE": identity["sourceTree"],
            NAMESPACE + ".BUILD_CHANNEL": identity["channel"]}


def check_generated_policy(manifest, variant, identity):
    package, _, _ = variant_policy(variant)
    if (manifest.get("package") != package or manifest.get(ANDROID + "versionName") != identity["versionName"]
            or manifest.get(ANDROID + "versionCode") != str(identity["versionCode"])):
        raise ValueError("Generated package/version identity mismatch")
    app = manifest.find("application")
    require_xml_enabled(app)
    if app.get(ANDROID + "allowBackup") != "false":
        raise ValueError("Backup must remain disabled")
    for key, value in (("fullBackupContent", "@xml/backup_rules"), ("dataExtractionRules", "@xml/data_extraction_rules")):
        if app.get(ANDROID + key) != value:
            raise ValueError("Backup policy resource reference mismatch")
    if app.get(ANDROID + "debuggable", "false") != ("true" if variant == "debug" else "false"):
        raise ValueError("Wrong variant debuggability")
    if app.get(ANDROID + "testOnly", "false") != "false" or manifest.findall("instrumentation"):
        raise ValueError("App must exclude instrumentation/testOnly")
    metadata = [(node.get(ANDROID + "name"), node.get(ANDROID + "value")) for node in app.findall("meta-data")]
    for key, value in source_metadata(identity).items():
        if [item[1] for item in metadata if item[0] == key] != [value]:
            raise ValueError("Generated source/channel metadata mismatch")
    check_release_components([(node.tag, node.get(ANDROID + "name", "")) for node in app], variant)
    activities = [node for node in app.findall("activity") if node.get(ANDROID + "name") == MAIN_ACTIVITY]
    if len(activities) != 1 or activities[0].get(ANDROID + "exported") != "true":
        raise ValueError("Production launcher must remain exported")
    require_xml_enabled(activities[0])
    filters = activities[0].findall("intent-filter")
    launcher = [node for node in filters if any(c.get(ANDROID + "name") == "android.intent.category.LAUNCHER" for c in node.findall("category"))]
    if len(launcher) != 1:
        raise ValueError("Production MAIN/LAUNCHER mismatch")
    owned = launcher[0]
    expected = [("action", "android.intent.action.MAIN"), ("category", "android.intent.category.LAUNCHER")]
    if (owned.attrib or [(n.tag, n.get(ANDROID + "name")) for n in owned] != expected
            or any(n.attrib != {ANDROID + "name": name} or list(n)
                   for n, (_, name) in zip(owned, expected))):
        raise ValueError("Launcher must be the direct data-free reviewed filter")
    for tag, name in expected:
        if sum(n.get(ANDROID + "name") == name for n in app.iter(tag)) != 1:
            raise ValueError("Generated launcher names must belong to one filter")


def require_xml_enabled(node):
    if node.get(ANDROID + "enabled", "true") != "true":
        raise ValueError("Launcher/application must be effectively enabled")


def require_tree_enabled(block):
    lines = [line.strip() for line in block.splitlines()
             if re.match(r"A: android:enabled(?:\(|=)", line.strip())]
    if lines and (len(lines) != 1 or not re.fullmatch(
            r"A: android:enabled\(0x[0-9a-f]+\)=\(type 0x12\)0xffffffff", lines[0])):
        raise ValueError("APK launcher/application enabled must be a literal typed true")


def check_tree_launcher(block, parent_indent):
    # Complete structural join: exactly two direct leaf elements, with no filter
    # attributes, data nodes, nested names or additional attribute selectors.
    lines = [line for line in block.splitlines() if line.strip()]
    if len(lines) != 4:
        raise ValueError("APK launcher filter must be direct and data-free")
    indent = parent_indent + 2
    for offset, (tag, name) in zip((0, 2), (("action", "android.intent.action.MAIN"),
                                           ("category", "android.intent.category.LAUNCHER"))):
        if (not re.fullmatch(" " * indent + r"E: " + tag + r" \(line=\d+\)", lines[offset])
                or not re.fullmatch(" " * (indent + 2) + r'A: android:name\(0x[0-9a-f]+\)="'
                                    + re.escape(name) + r'"(?: \(Raw: "' + re.escape(name) + r'"\))?', lines[offset + 1])):
            raise ValueError("APK launcher hierarchy/attributes mismatch")


def check_release_components(components, variant):
    if variant == "release":
        activities = [name for tag, name in components if tag in ("activity", "activity-alias")]
        if activities != [MAIN_ACTIVITY]:
            raise ValueError("Release excludes debug/test Activity components")
        if any(name.startswith(("androidx.test.", "androidx.compose.ui.test.")) for _, name in components):
            raise ValueError("Release excludes test components")


def tree_attribute(block, key, *, string=False, optional=False):
    # aapt uses raw strings beside typed integers/references. Never accept the
    # first value when duplicate attributes disagree.
    lines = [line.strip() for line in block.splitlines() if re.match(r"A: " + re.escape(key) + r"(?:\(|=)", line.strip())]
    if not lines and optional:
        return None
    if len(lines) != 1:
        raise ValueError("Missing/duplicate APK attribute: " + key)
    text = lines[0].split("=", 1)[1]
    if string:
        match = re.match(r'"([^"\n]*)"(?: \(Raw: "[^"\n]*"\))?$', text)
        if not match:
            raise ValueError("Malformed APK string attribute: " + key)
        return match[1]
    match = re.fullmatch(r"\(type 0x(?:10|11|12)\)0x([0-9a-f]+)", text)
    if not match:
        raise ValueError("Malformed APK typed attribute: " + key)
    return int(match[1], 16)


def check_apk_identity(xmltree, variant, identity):
    package, _, _ = variant_policy(variant)
    manifests, apps = xmltree_elements(xmltree, "manifest"), xmltree_elements(xmltree, "application")
    if len(manifests) != 1 or len(apps) != 1:
        raise ValueError("Missing/ambiguous APK manifest/application")
    manifest, app = manifests[0], apps[0]
    require_tree_enabled(app)
    if (tree_attribute(manifest, "package", string=True) != package
            or tree_attribute(manifest, "android:versionName", string=True) != identity["versionName"]
            or tree_attribute(manifest, "android:versionCode") != identity["versionCode"]):
        raise ValueError("APK package/version identity mismatch")
    if tree_attribute(app, "android:allowBackup") != 0:
        raise ValueError("APK backup must remain disabled")
    debuggable = tree_attribute(app, "android:debuggable", optional=True) or 0
    if bool(debuggable) != (variant == "debug"):
        raise ValueError("APK variant debuggability mismatch")
    if tree_attribute(app, "android:testOnly", optional=True) not in (None, 0) or xmltree_elements(xmltree, "instrumentation"):
        raise ValueError("APK contains testOnly/instrumentation")
    for key in ("android:fullBackupContent", "android:dataExtractionRules"):
        if len(re.findall(r"(?m)^\s*A: " + re.escape(key) + r"\([^\n]*\)=@0x[0-9a-f]+\s*$", app)) != 1:
            raise ValueError("Missing APK backup resource reference")
    for key, value in source_metadata(identity).items():
        matches = [block for block in xmltree_elements(xmltree, "meta-data")
                   if tree_attribute(block, "android:name", string=True) == key]
        if len(matches) != 1 or tree_attribute(matches[0], "android:value", string=True) != value:
            raise ValueError("APK source/channel metadata mismatch")
    components = [(tag, tree_attribute(block, "android:name", string=True))
                  for tag in ("activity", "activity-alias", "service", "receiver", "provider")
                  for block in xmltree_elements(xmltree, tag)]
    check_release_components(components, variant)
    main = [block for block in xmltree_elements(xmltree, "activity")
            if tree_attribute(block, "android:name", string=True) == MAIN_ACTIVITY]
    if len(main) != 1 or tree_attribute(main[0], "android:exported") != 0xffffffff:
        raise ValueError("APK launcher must remain exported")
    require_tree_enabled(main[0])
    # Keep hierarchy: global occurrence counts cannot prove a launcher filter.
    activities = list(zip(xmltree_elements(xmltree, "activity"), tree_subtrees(xmltree, "activity")))
    owned = [subtree for attrs, subtree in activities
             if tree_attribute(attrs, "android:name", string=True) == MAIN_ACTIVITY]
    filters = tree_subtrees(owned[0], "intent-filter") if len(owned) == 1 else []
    if len(filters) != 1:
        raise ValueError("APK MAIN/LAUNCHER identity mismatch")
    depth = min(len(line) - len(line.lstrip()) for line in owned[0].splitlines() if line.strip())
    headers = [line for line in owned[0].splitlines() if line.strip().startswith('E: intent-filter')]
    if len(headers) != 1 or len(headers[0]) - len(headers[0].lstrip()) != depth:
        raise ValueError("APK launcher filter is not an Activity direct child")
    check_tree_launcher(filters[0], len(headers[0]) - len(headers[0].lstrip()))
    for tag, name in (("action", "android.intent.action.MAIN"), ("category", "android.intent.category.LAUNCHER")):
        names = [tree_attribute(block, "android:name", string=True) for block in xmltree_elements(filters[0], tag)]
        if names != [name] or sum(tree_attribute(block, "android:name", string=True) == name
                                 for block in xmltree_elements(xmltree, tag)) != 1:
            raise ValueError("APK MAIN/LAUNCHER identity mismatch")


def tree_subtrees(text, element):
    lines = text.splitlines()
    blocks = []
    for index, line in enumerate(lines):
        match = re.fullmatch(r"([ \t]*)E: " + re.escape(element) + r" \(line=\d+\)", line)
        if not match:
            continue
        indent = len(match[1])
        following = []
        for child in lines[index + 1:]:
            if child.strip() and len(child) - len(child.lstrip()) <= indent:
                break
            following.append(child)
        blocks.append("\n".join(following))
    return blocks


def check_backup_dump(text, extraction=False):
    domains = {"root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"}
    root = "data-extraction-rules" if extraction else "full-backup-content"
    if len(xmltree_elements(text, root)) != 1 or xmltree_elements(text, "include"):
        raise ValueError("Packaged backup exclusion structure mismatch")
    sections = []
    if extraction:
        for section in ("cloud-backup", "device-transfer"):
            blocks = tree_subtrees(text, section)
            if len(blocks) != 1:
                raise ValueError("Packaged backup section missing/duplicate")
            sections += blocks
    else:
        sections = [text]
    for section in sections:
        blocks = xmltree_elements(section, "exclude")
        actual = [tree_attribute(block, "domain", string=True) for block in blocks]
        if len(actual) != len(domains) or set(actual) != domains or any(tree_attribute(block, "path", string=True) != "." for block in blocks):
            raise ValueError("Packaged backup exclusions weakened")


def backup_resource_files(manifest, resources, *, variant='release'):
    """Strict aapt2 resource-table parser. Every selected configuration is checked.

    Synthetic transcripts exercise this parser; actual Build Tools 36.0.0 output
    is an indispensable hosted compatibility gate, with no unknown-format pass.
    """
    package, _, _ = variant_policy(variant)
    apps = xmltree_elements(manifest, 'application')
    if len(apps) != 1:
        raise ValueError('Ambiguous backup application')
    selected = {}
    for attribute, name in (('fullBackupContent', 'backup_rules'), ('dataExtractionRules', 'data_extraction_rules')):
        ids = re.findall(r'(?m)^\s*A: android:' + attribute + r'\([^\n]*\)=@(0x[0-9a-f]{8})\s*$', apps[0])
        if len(ids) != 1 or ids[0] in selected:
            raise ValueError('Invalid backup resource identity')
        selected[ids[0]] = name
    result, current, package_indent, type_indent, resource_indent = {}, None, None, None, None
    seen_ids, enclosing_type = set(), None
    for line in resources.splitlines():
        if not line.strip():
            continue
        if "\t" in line:
            raise ValueError('Unknown resource-table indentation')
        indent = len(line) - len(line.lstrip(' '))
        body = line[indent:]
        package_header = re.fullmatch(r'Package name=([^ ]+) id=([0-9a-f]{2})', body)
        if body.startswith('Package '):
            if (not package_header or package_indent is not None or indent != 0
                    or package_header.groups() != (package, '7f')):
                raise ValueError('Unknown/premature resource-table package')
            package_indent = indent
            continue
        type_header = re.fullmatch(r'type ([A-Za-z0-9_]+) id=([0-9a-f]{2}) entryCount=([0-9]+)', body)
        if body.startswith('type '):
            if not type_header or package_indent is None or indent != package_indent + 2:
                raise ValueError('Unknown/premature resource-table type')
            type_indent, enclosing_type = indent, type_header.groups()
            current, resource_indent = None, None
            continue
        header = re.fullmatch(r'resource (0x[0-9a-f]{8}) ([^ ]+)(?: PUBLIC)?', body)
        if body.startswith('resource '):
            if not header or type_indent is None or indent != type_indent + 2:
                raise ValueError('Unknown/premature resource-table entry')
            oid = header[1]
            if oid in seen_ids:
                raise ValueError('Duplicate resource-table ID')
            seen_ids.add(oid)
            current, resource_indent = oid, indent
            if current in selected:
                if enclosing_type[:2] != ('xml', oid[4:6]) or oid[2:4] != '7f':
                    raise ValueError('Selected backup type/package ID mismatch')
                expected_name = 'xml/' + selected[current]
                if header[2] not in (expected_name, package + ':' + expected_name):
                    raise ValueError('Selected backup resource-table name mismatch')
                result[current] = []
            continue
        if resource_indent is None or indent != resource_indent + 2:
            raise ValueError('Orphan/unknown resource-table continuation')
        if current not in selected:
            # Nonselected values have no backup authority. They still require a
            # legal entry/config depth and cannot act as an enclosing boundary.
            if not body.startswith('('):
                raise ValueError('Unknown resource-table continuation')
            continue
        value = re.fullmatch(r'\(([A-Za-z0-9-]*)\) \(file\) (res/[A-Za-z0-9_./-]+\.xml) type=XML', body)
        if not value or '..' in value[2] or '//' in value[2]:
            raise ValueError('Unknown/uninspected selected backup configuration')
        if not re.fullmatch(r'res/xml(?:-[A-Za-z0-9-]+)?/' + selected[current] + r'\.xml', value[2]):
            raise ValueError('Selected backup resource file mismatch')
        result[current].append({'configuration': value[1], 'path': value[2]})
    if set(result) != set(selected):
        raise ValueError('Manifest backup ID missing from resource table')
    for variants in result.values():
        configs = [v['configuration'] for v in variants]
        if not configs or len(configs) > 16 or '' not in configs or len(set(configs)) != len(configs):
            raise ValueError('Missing/duplicate selected backup configuration')
    return {selected[oid]: values for oid, values in result.items()}


def check_backup_resources(manifest, resources, inspections, *, variant='release'):
    files = backup_resource_files(manifest, resources, variant=variant)
    if not isinstance(inspections, dict) or set(inspections) != set(files):
        raise ValueError('Selected backup inspection set mismatch')
    for name, expected in files.items():
        actual = inspections[name]
        if not isinstance(actual, list) or len(actual) != len(expected):
            raise ValueError('Selected backup configuration was not inspected')
        for row, selection in zip(actual, expected):
            if not isinstance(row, dict) or set(row) != {'configuration', 'path', 'xmltree'} or any(row[k] != v for k, v in selection.items()):
                raise ValueError('Selected backup resource inspection binding mismatch')
            check_backup_dump(row['xmltree'], name == 'data_extraction_rules')
    return files


def inspect_backup_resources(apk, sdk, manifest, *, variant='release'):
    tools = Path(sdk) / 'build-tools/36.0.0'
    resources = subprocess.run([str(tools/'aapt2'), 'dump', 'resources', str(apk)],
                               capture_output=True, text=True, timeout=60, check=True).stdout
    files = backup_resource_files(manifest, resources, variant=variant)
    inspections, texts = {}, {}
    for name, selections in files.items():
        inspections[name] = []
        for selection in selections:
            text = subprocess.run([str(tools/'aapt'), 'dump', 'xmltree', str(apk), selection['path']],
                                  capture_output=True, text=True, timeout=60, check=True).stdout
            inspections[name].append(selection | {'xmltree': text})
            if selection['configuration'] == '':
                texts['backup.txt' if name == 'backup_rules' else 'extraction.txt'] = text
    check_backup_resources(manifest, resources, inspections, variant=variant)
    texts['resources.txt'] = resources
    texts['backup-resources.json'] = json.dumps(inspections, sort_keys=True, separators=(',', ':')) + '\n'
    return texts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-permissions", type=Path)
    parser.add_argument("--apk-xmltree", type=Path)
    parser.add_argument("--installed-path", type=Path)
    parser.add_argument("--installed-package", type=Path)
    parser.add_argument("--variant", choices=VARIANTS)
    parser.add_argument("--identity", type=Path)
    args = parser.parse_args()
    from release_identity import resolve_identity, validate_identity
    identity = validate_identity(json.loads(args.identity.read_text())) if args.identity else resolve_identity(ROOT)
    if args.apk_permissions or args.apk_xmltree or args.installed_path or args.installed_package:
        if args.variant is None:
            parser.error("Explicit reviewed --variant required for supplied observations")
        if bool(args.apk_permissions) != bool(args.apk_xmltree):
            parser.error("APK permissions and XML tree are required together")
        if args.installed_package and not (args.apk_permissions and args.installed_path):
            parser.error("Installed dump requires the APK observations and installed path")
        if args.apk_permissions:
            supplied = args.apk_xmltree.read_text()
            check_apk_dump(args.apk_permissions.read_text(), supplied, variant=args.variant, identity=identity)
            apk_name = 'app-debug.apk' if args.variant == 'debug' else 'app-release-unsigned.apk'
            apk = ROOT / 'app/build/outputs/apk' / args.variant / apk_name
            before = hashlib.sha256(apk.read_bytes()).digest()
            current = subprocess.run([str(Path(os.environ['ANDROID_HOME'])/'build-tools/36.0.0/aapt'),
                                      'dump', 'xmltree', str(apk), 'AndroidManifest.xml'],
                                     capture_output=True, text=True, timeout=60, check=True).stdout
            if current != supplied:
                raise ValueError('Supplied manifest differs from current APK')
            inspect_backup_resources(apk, os.environ['ANDROID_HOME'], current, variant=args.variant)
            if hashlib.sha256(apk.read_bytes()).digest() != before:
                raise ValueError('APK changed during resource inspection')
        if args.installed_path:
            check_installed_path(args.installed_path.read_text(), variant=args.variant)
        if args.installed_package:
            check_installed_dump(args.installed_package.read_text(), variant=args.variant, identity=identity)
        print("Supplied APK/path/installed observations verified; installed cleartext policy "
              "is asserted by native tests")
        return
    receipt = [check_source_manifests(ROOT)]
    for variant in ("debug", "release"):
        path = (ROOT / "app/build/intermediates/merged_manifests" / variant
                / f"process{variant.title()}Manifest/AndroidManifest.xml")
        receipt.append(check_manifest(path, variant=variant, identity=identity))
    destination = ROOT / "evidence/verified-manifests.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(receipt, indent=2) + "\n")
    print("Source and merged debug/release manifests: exact permissions; cleartext disabled; "
          "singleTask launcher in the default process")


if __name__ == "__main__":
    main()
