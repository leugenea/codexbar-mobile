"""Independent synthetic pinned-component observations, never merged/APK evidence."""
import xml.etree.ElementTree as ET

A = '{http://schemas.android.com/apk/res/android}'
COMPONENT_XML = '''<application xmlns:android="http://schemas.android.com/apk/res/android">
<service android:name="androidx.work.impl.background.systemjob.SystemJobService" android:exported="true" android:directBootAware="false" android:permission="android.permission.BIND_JOB_SERVICE"/>
<service android:name="androidx.room.MultiInstanceInvalidationService" android:exported="false" android:directBootAware="true"/>
<receiver android:name="androidx.work.impl.utils.ForceStopRunnable$BroadcastReceiver" android:exported="false" android:directBootAware="false"/>
<receiver android:name="androidx.work.impl.background.systemalarm.RescheduleReceiver" android:exported="false" android:directBootAware="false"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter></receiver>
<receiver android:name="androidx.work.impl.diagnostics.DiagnosticsReceiver" android:exported="true" android:directBootAware="false" android:permission="android.permission.DUMP"><intent-filter><action android:name="androidx.work.diagnostics.REQUEST_DIAGNOSTICS"/></intent-filter></receiver>
<receiver android:name="androidx.profileinstaller.ProfileInstallReceiver" android:exported="true" android:directBootAware="false" android:permission="android.permission.DUMP">
<intent-filter><action android:name="androidx.profileinstaller.action.INSTALL_PROFILE"/></intent-filter>
<intent-filter><action android:name="androidx.profileinstaller.action.SKIP_FILE"/></intent-filter>
<intent-filter><action android:name="androidx.profileinstaller.action.SAVE_PROFILE"/></intent-filter>
<intent-filter><action android:name="androidx.profileinstaller.action.BENCHMARK_OPERATION"/></intent-filter>
</receiver>
<provider android:name="androidx.startup.InitializationProvider" android:authorities="PACKAGE.androidx-startup" android:exported="false">
<meta-data android:name="androidx.work.WorkManagerInitializer" android:value="androidx.startup"/>
<meta-data android:name="androidx.lifecycle.ProcessLifecycleInitializer" android:value="androidx.startup"/>
<meta-data android:name="androidx.profileinstaller.ProfileInstallerInitializer" android:value="androidx.startup"/>
<meta-data android:name="androidx.emoji2.text.EmojiCompatInitializer" android:value="androidx.startup"/>
</provider></application>'''


def components_xml(package):
    return COMPONENT_XML.replace('PACKAGE', package).split('>', 1)[1].removesuffix('</application>')


def components_apk(package):
    def render(node, indent):
        text = ' ' * indent + f'E: {node.tag} (line=99)\n'
        for key, value in node.attrib.items():
            encoded = ('(type 0x12)' + ('0xffffffff' if value == 'true' else '0x0')) if value in {'true', 'false'} else '"' + value + '"'
            text += ' ' * (indent + 2) + 'A: android:' + key.removeprefix(A) + '(0x01010000)=' + encoded + '\n'
        return text + ''.join(render(child, indent + 2) for child in node)
    application = ET.fromstring(COMPONENT_XML.replace('PACKAGE', package))
    return ''.join(render(node, 4) for node in application)
