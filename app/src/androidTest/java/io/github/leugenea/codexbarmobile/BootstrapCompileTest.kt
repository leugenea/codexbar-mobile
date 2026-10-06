package io.github.leugenea.codexbarmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Resolves the future native test graph; NOT a launcher/semantics smoke test. */
@RunWith(AndroidJUnit4::class)
class BootstrapCompileTest {
    @Test
    fun instrumentationCanLinkApplicationActivity() {
        assertNotNull(MainActivity::class.java)
    }
}
