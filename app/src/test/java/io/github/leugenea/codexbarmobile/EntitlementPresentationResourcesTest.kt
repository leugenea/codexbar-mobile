package io.github.leugenea.codexbarmobile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/** Source wording only; C2 owns Android rendering, not this pure projection. */
class EntitlementPresentationResourcesTest {
    @Test fun availabilityAndExpiryWordingDoesNotPromiseActivationOrCallExpiryAUsageReset() {
        val path = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
            .map(::File).first(File::isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path)
        val nodes = document.getElementsByTagName("string")
        val strings = (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
        val expected = mapOf(
            "entitlement_known" to "Provider-reported banked entitlements",
            "entitlement_empty" to "Provider reports no available banked entitlements",
            "entitlement_unsupported" to "Banked entitlement information unsupported",
            "entitlement_unknown" to "Banked entitlement information unknown",
            "entitlement_inaccessible" to "Banked entitlement information inaccessible",
            "entitlement_malformed" to "Malformed banked entitlement information",
            "entitlement_discrepant" to "Conflicting banked entitlement information · provider counts retained",
            "entitlement_expiry" to "Banked entitlement expiry",
        )
        for ((name, text) in expected) assertEquals(name, text, strings[name])
        assertEquals("Entitlement expired", strings["time_expired"])
        assertEquals(listOf(
            R.string.entitlement_known, R.string.entitlement_empty, R.string.entitlement_unsupported,
            R.string.entitlement_unknown, R.string.entitlement_inaccessible, R.string.entitlement_malformed,
            R.string.time_expired, R.string.entitlement_discrepant,
        ), EntitlementState.entries.map { it.labelResource })
    }
}
