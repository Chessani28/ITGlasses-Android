package com.itglasses.itglassesandroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The installation identity and the stored shared token must never leave the phone in a backup or transfer. */
class BackupRulesTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val required = setOf("sharedpref" to "itglasses_pairing.xml", "sharedpref" to "itglasses_network_config.xml")

    private fun file(path: String): File = listOf(File(path), File("app", path)).firstOrNull { it.isFile }
        ?: throw AssertionError("No se encontró $path desde ${File(".").absolutePath}")

    private fun root(path: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file(path)).documentElement

    private fun children(parent: Element, tag: String): List<Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
    }

    private fun excludes(parent: Element) =
        children(parent, "exclude").map { it.getAttribute("domain") to it.getAttribute("path") }.toSet()

    @Test fun fullBackupExcludesPairingAndNetworkConfig() {
        val rules = root("src/main/res/xml/backup_rules.xml")
        assertEquals("full-backup-content", rules.tagName)
        assertTrue(excludes(rules).containsAll(required))
    }

    @Test fun cloudBackupAndDeviceTransferExcludePairingAndNetworkConfig() {
        val rules = root("src/main/res/xml/data_extraction_rules.xml")
        assertEquals("data-extraction-rules", rules.tagName)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val found = children(rules, section)
            assertEquals(section, 1, found.size)
            assertTrue(section, excludes(found.single()).containsAll(required))
        }
    }

    @Test fun manifestUsesBothRuleFiles() {
        val application = children(root("src/main/AndroidManifest.xml"), "application").single()
        assertEquals("@xml/backup_rules", application.getAttributeNS(android, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(android, "dataExtractionRules"))
    }
}
