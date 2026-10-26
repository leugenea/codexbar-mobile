"""Captured pre-WorkManager parser fixtures, not a local SDK or signature result.

Source manifest: 296e030c68e79235397d7e4d082aaf463ce95f7e (identical at all five
reviewed legacy sources). APK text: hosted Android main run 39659625939,
artifact 12373607263 (v0.1.0 source); independently matched by run 40120503438,
artifact 12571217527 (v0.2.0 source). These are unsigned build observations;
release-signature and complete downloaded-asset acceptance remain hosted gates.
"""

SOURCE_MANIFEST = b'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Only selected authenticated routes use the network; cleartext stays disabled. -->
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:usesCleartextTraffic="false"
        android:allowBackup="false"
        android:fullBackupContent="@xml/backup_rules"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.CodexBarMobile">
        <activity android:name="io.github.leugenea.codexbarmobile.MainActivity" android:exported="true" android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
'''

PERMISSIONS = '''package: io.github.leugenea.codexbarmobile
uses-permission: name='android.permission.INTERNET'
permission: io.github.leugenea.codexbarmobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='io.github.leugenea.codexbarmobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
'''

TREE = '''N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=2)
    A: android:versionCode(0x0101021b)=(type 0x10)0x1
    A: android:versionName(0x0101021c)="0.1.0-dev.72+gd9b7298e5e68" (Raw: "0.1.0-dev.72+gd9b7298e5e68")
    A: android:compileSdkVersion(0x01010572)=(type 0x10)0x25
    A: android:compileSdkVersionCodename(0x01010573)="17" (Raw: "17")
    A: package="io.github.leugenea.codexbarmobile" (Raw: "io.github.leugenea.codexbarmobile")
    A: platformBuildVersionCode=(type 0x10)0x25
    A: platformBuildVersionName=(type 0x10)0x11
    E: uses-sdk (line=7)
      A: android:minSdkVersion(0x0101020c)=(type 0x10)0x1a
      A: android:targetSdkVersion(0x01010270)=(type 0x10)0x25
    E: uses-permission (line=11)
      A: android:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")
    E: permission (line=13)
      A: android:name(0x01010003)="io.github.leugenea.codexbarmobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "io.github.leugenea.codexbarmobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
      A: android:protectionLevel(0x01010009)=(type 0x11)0x2
    E: uses-permission (line=17)
      A: android:name(0x01010003)="io.github.leugenea.codexbarmobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "io.github.leugenea.codexbarmobile.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    E: application (line=19)
      A: android:theme(0x01010000)=@0x7f0b0009
      A: android:label(0x01010001)=@0x7f0a0012
      A: android:icon(0x01010002)=@0x7f080000
      A: android:allowBackup(0x01010280)=(type 0x12)0x0
      A: android:supportsRtl(0x010103af)=(type 0x12)0xffffffff
      A: android:extractNativeLibs(0x010104ea)=(type 0x12)0x0
      A: android:fullBackupContent(0x010104eb)=@0x7f0d0000
      A: android:usesCleartextTraffic(0x010104ec)=(type 0x12)0x0
      A: android:appComponentFactory(0x0101057a)="androidx.core.app.CoreComponentFactory" (Raw: "androidx.core.app.CoreComponentFactory")
      A: android:dataExtractionRules(0x0101063e)=@0x7f0d0001
      E: activity (line=30)
        A: android:name(0x01010003)="io.github.leugenea.codexbarmobile.MainActivity" (Raw: "io.github.leugenea.codexbarmobile.MainActivity")
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        A: android:launchMode(0x0101001d)=(type 0x10)0x2
        E: intent-filter (line=34)
          E: action (line=35)
            A: android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
          E: category (line=37)
            A: android:name(0x01010003)="android.intent.category.LAUNCHER" (Raw: "android.intent.category.LAUNCHER")
      E: provider (line=41)
        A: android:name(0x01010003)="androidx.startup.InitializationProvider" (Raw: "androidx.startup.InitializationProvider")
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:authorities(0x01010018)="io.github.leugenea.codexbarmobile.androidx-startup" (Raw: "io.github.leugenea.codexbarmobile.androidx-startup")
        E: meta-data (line=45)
          A: android:name(0x01010003)="androidx.emoji2.text.EmojiCompatInitializer" (Raw: "androidx.emoji2.text.EmojiCompatInitializer")
          A: android:value(0x01010024)="androidx.startup" (Raw: "androidx.startup")
        E: meta-data (line=48)
          A: android:name(0x01010003)="androidx.lifecycle.ProcessLifecycleInitializer" (Raw: "androidx.lifecycle.ProcessLifecycleInitializer")
          A: android:value(0x01010024)="androidx.startup" (Raw: "androidx.startup")
        E: meta-data (line=51)
          A: android:name(0x01010003)="okhttp3.internal.platform.PlatformInitializer" (Raw: "okhttp3.internal.platform.PlatformInitializer")
          A: android:value(0x01010024)="androidx.startup" (Raw: "androidx.startup")
        E: meta-data (line=54)
          A: android:name(0x01010003)="androidx.profileinstaller.ProfileInstallerInitializer" (Raw: "androidx.profileinstaller.ProfileInstallerInitializer")
          A: android:value(0x01010024)="androidx.startup" (Raw: "androidx.startup")
      E: uses-library (line=59)
        A: android:name(0x01010003)="androidx.window.extensions" (Raw: "androidx.window.extensions")
        A: android:required(0x0101028e)=(type 0x12)0x0
      E: uses-library (line=62)
        A: android:name(0x01010003)="androidx.window.sidecar" (Raw: "androidx.window.sidecar")
        A: android:required(0x0101028e)=(type 0x12)0x0
      E: receiver (line=66)
        A: android:name(0x01010003)="androidx.profileinstaller.ProfileInstallReceiver" (Raw: "androidx.profileinstaller.ProfileInstallReceiver")
        A: android:permission(0x01010006)="android.permission.DUMP" (Raw: "android.permission.DUMP")
        A: android:enabled(0x0101000e)=(type 0x12)0xffffffff
        A: android:exported(0x01010010)=(type 0x12)0xffffffff
        A: android:directBootAware(0x01010505)=(type 0x12)0x0
        E: intent-filter (line=72)
          E: action (line=73)
            A: android:name(0x01010003)="androidx.profileinstaller.action.INSTALL_PROFILE" (Raw: "androidx.profileinstaller.action.INSTALL_PROFILE")
        E: intent-filter (line=75)
          E: action (line=76)
            A: android:name(0x01010003)="androidx.profileinstaller.action.SKIP_FILE" (Raw: "androidx.profileinstaller.action.SKIP_FILE")
        E: intent-filter (line=78)
          E: action (line=79)
            A: android:name(0x01010003)="androidx.profileinstaller.action.SAVE_PROFILE" (Raw: "androidx.profileinstaller.action.SAVE_PROFILE")
        E: intent-filter (line=81)
          E: action (line=82)
            A: android:name(0x01010003)="androidx.profileinstaller.action.BENCHMARK_OPERATION" (Raw: "androidx.profileinstaller.action.BENCHMARK_OPERATION")
'''
