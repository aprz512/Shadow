package com.tencent.shadow.core.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ResourceSymbolLookupTest {

    @Test
    fun parsePackageAwareRFile_readsPackageAndSymbols() {
        val symbolFile = File.createTempFile("package-aware-r", ".txt").apply {
            writeText(
                """
                com.example.test
                int style AppTheme 0x7f010001
                int layout activity_main 0x7f020001
                """.trimIndent()
            )
        }

        val result = ResourceSymbolLookup.parsePackageAwareRFile(symbolFile)

        assertEquals("com.example.test", result["style/AppTheme"])
        assertEquals("com.example.test", result["layout/activity_main"])
    }

    @Test
    fun parseAarResourceSymbols_readsPackageFromManifest() {
        val aarFile = createTempAar(
            manifestXml = """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest package="androidx.appcompat" />
            """.trimIndent(),
            rTxt = """
                int style Theme_AppCompat 0x7f010001
                int layout abc_action_bar_title_item 0x7f020001
            """.trimIndent()
        )

        val result = ResourceSymbolLookup.parseAarResourceSymbols(aarFile)

        assertEquals("androidx.appcompat", result["style/Theme_AppCompat"])
        assertEquals("androidx.appcompat", result["layout/abc_action_bar_title_item"])
    }

    @Test
    fun selectResourceOwner_prefersCurrentPackageWhenPresent() {
        val owner = ResourceSymbolLookup.selectResourceOwner(
            "style/AppTheme",
            linkedSetOf("com.example.dep", "com.example.host"),
            "com.example.host"
        )

        assertEquals("com.example.host", owner)
    }

    @Test
    fun parseAarResourceSymbols_returnsEmptyWhenNoSymbols() {
        val aarFile = createTempAar(
            manifestXml = """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest package="androidx.empty" />
            """.trimIndent(),
            rTxt = ""
        )

        val result = ResourceSymbolLookup.parseAarResourceSymbols(aarFile)

        assertTrue(result.isEmpty())
    }

    private fun createTempAar(
        manifestXml: String,
        rTxt: String
    ): File {
        val aarFile = File.createTempFile("resource-symbol-lookup", ".aar")
        ZipOutputStream(aarFile.outputStream().buffered()).use { output ->
            output.putNextEntry(ZipEntry("AndroidManifest.xml"))
            output.write(manifestXml.toByteArray())
            output.closeEntry()

            output.putNextEntry(ZipEntry("R.txt"))
            output.write(rTxt.toByteArray())
            output.closeEntry()
        }
        return aarFile
    }
}
