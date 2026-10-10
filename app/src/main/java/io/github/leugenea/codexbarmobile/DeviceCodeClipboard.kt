package io.github.leugenea.codexbarmobile

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import io.github.leugenea.codexbarmobile.credentials.SensitiveValue

/** Write-only seam: callers supply only an owner-authorized device-login user code. */
internal fun interface DeviceCodeClipboard {
    fun copy(userCode: SensitiveValue): Boolean
}

internal class AndroidDeviceCodeClipboard(private val label: String,
    private val write: (ClipData) -> Unit) : DeviceCodeClipboard {
    constructor(context: Context) : this(context.getString(R.string.device_code_clip_label),
        context.applicationContext.getSystemService(ClipboardManager::class.java)::setPrimaryClip)

    override fun copy(userCode: SensitiveValue): Boolean = try {
        write(sensitiveDeviceCodeClip(userCode, label))
        true
    } catch (_: RuntimeException) {
        // Platform failure is not success; never log the exception or the clip.
        false
    }
}

internal fun sensitiveDeviceCodeClip(userCode: SensitiveValue, label: String,
    sdk: Int = Build.VERSION.SDK_INT): ClipData {
    val clip = ClipData.newPlainText(label, userCode.copyBytes().toString(Charsets.UTF_8))
    val key = if (sdk >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
    clip.description.extras = PersistableBundle().apply { putBoolean(key, true) }
    return clip
}
