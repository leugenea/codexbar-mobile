package io.github.leugenea.codexbarmobile

import org.junit.Assert.assertEquals
import org.junit.Test

/** Diagnostic test-source compilation only; meaningful M1 behavior tests are pending. */
class BootstrapCompileTest {
    @Test
    fun compilerCanLinkJvmTestRuntime() {
        assertEquals("17", System.getProperty("java.specification.version"))
    }
}
