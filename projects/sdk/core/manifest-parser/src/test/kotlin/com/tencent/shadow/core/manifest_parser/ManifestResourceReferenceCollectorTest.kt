package com.tencent.shadow.core.manifest_parser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ManifestResourceReferenceCollectorTest {

    @Test
    fun collectManifestResourceReferences_onlyReturnsLocalManifestReferences() {
        val manifestFile = File("src/test/resources/resource-package-lookup.xml")

        val references = collectManifestResourceReferences(manifestFile)

        assertEquals(
            linkedSetOf("style/CustomActivityTheme"),
            references
        )
    }

    @Test
    fun normalizeResourceReference_matchesGeneratedRFieldNaming() {
        assertEquals("style/Theme_AppCompat", normalizeResourceReference("style/Theme.AppCompat"))
    }
}
