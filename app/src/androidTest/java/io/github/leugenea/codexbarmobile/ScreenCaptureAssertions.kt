package io.github.leugenea.codexbarmobile

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Full-display platform capture, not a Compose-only image or a Recents thumbnail. */
internal fun assertPlatformScreenshot(compose: ComposeTestRule, name: String, tag: String) {
    val node = compose.onNodeWithTag(tag).assertIsDisplayed()
    val semantics = node.fetchSemanticsNode()
    val view = (semantics.root as ViewRootForTest).view
    val location = IntArray(2)
    compose.runOnUiThread {
        assertTrue("$name root is attached", view.isAttachedToWindow)
        val flags = (view.rootView.layoutParams as WindowManager.LayoutParams).flags
        assertEquals("$name window permits capture", 0, flags and WindowManager.LayoutParams.FLAG_SECURE)
        view.getLocationOnScreen(location)
    }
    val bounds = semantics.boundsInRoot
    val left = location[0] + bounds.left.roundToInt()
    val top = location[1] + bounds.top.roundToInt()
    // An independently registered native label is the reference. Checking its ink
    // separately prevents a blank/solid screenshot from passing on background alone.
    val reference = node.captureToImage().asAndroidBitmap()
    var screenshot: Bitmap? = null
    var last = "no platform screenshot"
    try {
        val pixels = IntArray(reference.width * reference.height)
        reference.getPixels(pixels, 0, reference.width, 0, 0, reference.width, reference.height)
        val background = pixels.asIterable().groupingBy { it }.eachCount().maxBy { it.value }.key
        val ink = pixels.indices.filter { !captureColorsMatch(pixels[it], background) }
        assertTrue("$name reference must contain actual foreground glyphs", ink.size >= 20)
        try {
            compose.waitUntil(timeoutMillis = 5_000) {
                screenshot?.recycle()
                screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                val image = screenshot
                if (image == null) {
                    last = "platform returned null"
                    false
                } else {
                    assertTrue("$name registered label must fit actual display: ($left,$top) ${reference.width}x${reference.height} in ${image.width}x${image.height}",
                        left >= 0 && top >= 0 && left + reference.width <= image.width && top + reference.height <= image.height)
                    val actual = IntArray(pixels.size)
                    image.getPixels(actual, 0, reference.width, left, top, reference.width, reference.height)
                    val matching = pixels.indices.count { captureColorsMatch(pixels[it], actual[it]) }
                    val matchingInk = ink.count { captureColorsMatch(pixels[it], actual[it]) }
                    last = "pixels=$matching/${pixels.size}, ink=$matchingInk/${ink.size}, origin=$left,$top, label=${reference.width}x${reference.height}, display=${image.width}x${image.height}"
                    matching * 100L >= pixels.size * 95L && matchingInk * 100L >= ink.size * 95L
                }
            }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("$name ordinary screenshot must contain $tag; last observed: $last", error)
        }
    } finally {
        try {
            val directory = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")), "screen-capture")
            check(directory.isDirectory || directory.mkdirs())
            screenshot?.let { image ->
                File(directory, "screen-capture-$name.png").outputStream().use { stream ->
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, stream))
                }
            }
            File(directory, "screen-capture-$name.txt").writeText("source=UiAutomation.takeScreenshot()\nreferenceTag=$tag\n$last\nnotRecentsEvidence=true\n")
        } finally {
            screenshot?.recycle()
            reference.recycle()
        }
    }
}

private fun captureColorsMatch(one: Int, two: Int): Boolean = listOf(0, 8, 16).all { shift ->
    abs(((one shr shift) and 255) - ((two shr shift) and 255)) <= 12
}
