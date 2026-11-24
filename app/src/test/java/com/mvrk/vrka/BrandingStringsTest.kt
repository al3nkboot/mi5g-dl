package com.mvrk.vrka

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * User-visible product-name strings must say Mi5G Dl, not VRKA.
 * About credit and the upstream-updates heading are allowed to name VRKA.
 */
class BrandingStringsTest {
    private val allowed = setOf("mi5g_about_credit", "mi5g_upstream_updates_title")

    @Test
    fun productNameIsMi5GInEveryLocale() {
        val resDir = listOf(File("src/main/res"), File("app/src/main/res"))
            .first { it.isDirectory }
        val locales = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue(locales.isNotEmpty())
        var checked = 0
        for (dir in locales) {
            val file = File(dir, "strings.xml")
            if (!file.isFile) continue
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                val name = element.getAttribute("name")
                val value = element.textContent.orEmpty()
                if (name in allowed) {
                    assertTrue("$dir:$name should credit VRKA", value.contains("VRKA"))
                    continue
                }
                assertFalse("$dir:$name still names VRKA as the product: $value", value.contains("VRKA"))
                checked++
            }
        }
        assertTrue(checked > 100)
    }

    @Test
    fun homeScreenUsesMi5GWordmarkNotUpstreamPng() {
        val file = listOf(
            File("src/main/java/com/mvrk/vrka/HomeScreen.kt"),
            File("app/src/main/java/com/mvrk/vrka/HomeScreen.kt"),
        ).first { it.isFile }
        val text = file.readText()
        assertTrue(text.contains("stringResource(R.string.app_name)"))
        assertFalse(text.contains("R.drawable.vrka_wordmark"))
        assertFalse(text.contains("contentDescription = \"VRKA\""))
    }
}
