"""Exact effective component contract for the pinned WorkManager release (minSdk 26)."""
import re
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
WORK = 'androidx.work.'
COMPONENTS = {
    ('service', WORK + 'impl.background.systemjob.SystemJobService'): ('true', 'android.permission.BIND_JOB_SERVICE'),
    ('service', 'androidx.room.MultiInstanceInvalidationService'): ('false', None),
    ('receiver', WORK + 'impl.utils.ForceStopRunnable$BroadcastReceiver'): ('false', None),
    ('receiver', WORK + 'impl.background.systemalarm.RescheduleReceiver'): ('false', None),
    ('receiver', WORK + 'impl.diagnostics.DiagnosticsReceiver'): ('true', 'android.permission.DUMP'),
    ('receiver', 'androidx.profileinstaller.ProfileInstallReceiver'): ('true', 'android.permission.DUMP'),
    ('provider', 'androidx.startup.InitializationProvider'): ('false', None),
}
INITIALIZERS = {
    'androidx.work.WorkManagerInitializer', 'androidx.lifecycle.ProcessLifecycleInitializer',
    'androidx.profileinstaller.ProfileInstallerInitializer', 'androidx.emoji2.text.EmojiCompatInitializer',
    'okhttp3.internal.platform.PlatformInitializer',
}
RECEIVER_ACTIONS = {
    WORK + 'impl.utils.ForceStopRunnable$BroadcastReceiver': set(),
    WORK + 'impl.background.systemalarm.RescheduleReceiver': {'android.intent.action.BOOT_COMPLETED'},
    WORK + 'impl.diagnostics.DiagnosticsReceiver': {'androidx.work.diagnostics.REQUEST_DIAGNOSTICS'},
    'androidx.profileinstaller.ProfileInstallReceiver': {
        'androidx.profileinstaller.action.INSTALL_PROFILE', 'androidx.profileinstaller.action.SKIP_FILE',
        'androidx.profileinstaller.action.SAVE_PROFILE', 'androidx.profileinstaller.action.BENCHMARK_OPERATION'},
}


def check_component(node):
    key = (node.tag, node.get(ANDROID + 'name'))
    if (node.get(ANDROID + 'exported'), node.get(ANDROID + 'permission')) != COMPONENTS[key]:
        raise ValueError(f'Scheduler component protection mismatch: {key}')
    if any(node.get(ANDROID + attribute) is not None for attribute in ('process', 'isolatedProcess', 'externalService')):
        raise ValueError('Scheduler component process override')
    if node.tag != 'provider' and node.get(ANDROID + 'directBootAware') != (
            'true' if key[1] == 'androidx.room.MultiInstanceInvalidationService' else 'false'):
        raise ValueError(f'Scheduler direct-boot contract mismatch: {key}')
    if node.tag == 'receiver':
        actions = [action.get(ANDROID + 'name') for action in node.findall('intent-filter/action')]
        if len(actions) != len(set(actions)) or set(actions) != RECEIVER_ACTIONS[key[1]]:
            raise ValueError(f'Scheduler receiver actions mismatch: {key}')


def check_startup(startup, package):
    if startup.get(ANDROID + 'directBootAware') not in {None, 'false'}:
        raise ValueError('Scheduler Startup may not run before unlock')
    if startup.get(ANDROID + 'authorities') != package + '.androidx-startup':
        raise ValueError('Scheduler Startup authority mismatch')
    initializers = [(node.get(ANDROID + 'name'), node.get(ANDROID + 'value')) for node in startup.findall('meta-data')]
    if len(initializers) != len(INITIALIZERS) or set(initializers) != {(name, 'androidx.startup') for name in INITIALIZERS}:
        raise ValueError('Scheduler Startup initializer contract mismatch')


def check_components(application, package):
    if application.get(ANDROID + 'process') is not None:
        raise ValueError('Application process override')
    nodes = [node for node in application if node.tag in {'service', 'receiver', 'provider'}]
    keys = [(node.tag, node.get(ANDROID + 'name')) for node in nodes]
    if len(keys) != len(set(keys)) or set(keys) != set(COMPONENTS):
        raise ValueError(f'Pinned scheduler component allowlist mismatch: {keys}')
    for node in nodes:
        check_component(node)
    check_startup(next(node for node in nodes if node.tag == 'provider'), package)


def decoded_attribute(value):
    quoted = re.search(r'="([^"]*)"', value)
    if quoted:
        return quoted.group(1)
    integer = re.search(r'\(type 0x(?:10|12)\)(0x[0-9a-f]+)', value)
    if integer:
        return 'false' if int(integer.group(1), 16) == 0 else 'true'
    return value.split('=', 1)[-1].split(' ', 1)[0]


def apk_components(xmltree, package):
    """Decode aapt2 indentation (including attributes) before applying the same contract."""
    root = ET.Element('root')
    stack = [(-1, root)]
    application = None
    for line in xmltree.splitlines():
        element = re.match(r'^(\s*)E: ([\w-]+)(?:\s|$)', line)
        if element:
            indent = len(element.group(1))
            while stack[-1][0] >= indent:
                stack.pop()
            node = ET.SubElement(stack[-1][1], element.group(2))
            stack.append((indent, node))
            if node.tag == 'application':
                application = node
        else:
            attribute = re.match(r'^\s*A: android:([\w]+)(?:\([^)]*\))?=(.*)', line)
            if attribute:
                stack[-1][1].set(ANDROID + attribute.group(1), decoded_attribute('=' + attribute.group(2)))
    if application is None:
        raise ValueError('APK application node missing')
    check_components(application, package)
