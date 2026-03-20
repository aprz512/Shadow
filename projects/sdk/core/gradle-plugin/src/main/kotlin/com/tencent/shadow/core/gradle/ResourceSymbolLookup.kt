package com.tencent.shadow.core.gradle

import com.tencent.shadow.core.manifest_parser.normalizeResourceReference
import java.io.File
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal object ResourceSymbolLookup {

    fun parsePackageAwareRFile(symbolFile: File): Map<String, String> {
        val lines = symbolFile.readLines()
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (lines.isEmpty()) {
            return emptyMap()
        }
        val packageName = lines.first()
        return lines.drop(1).mapNotNull { line ->
            parseResourceSymbol(line)?.let { reference -> reference to packageName }
        }.toMap()
    }

    fun parseAarResourceSymbols(aarFile: File): Map<String, String> {
        ZipFile(aarFile).use { zipFile ->
            val rEntry = zipFile.getEntry("R.txt") ?: return emptyMap()
            val references = zipFile.getInputStream(rEntry).bufferedReader().useLines { lines ->
                lines.map(String::trim)
                    .filter(String::isNotEmpty)
                    .mapNotNull(::parseResourceSymbol)
                    .toList()
            }
            if (references.isEmpty()) {
                return emptyMap()
            }

            val manifestEntry = zipFile.getEntry("AndroidManifest.xml")
                ?: throw IllegalStateException("AAR缺少AndroidManifest.xml: $aarFile")
            val packageName = zipFile.getInputStream(manifestEntry).use(::parseManifestPackageName)
                ?: throw IllegalStateException("AAR的AndroidManifest.xml缺少package属性: $aarFile")

            return references.associateWith { packageName }
        }
    }

    fun selectResourceOwner(
        reference: String,
        owners: Set<String>,
        resourcePackageName: String?
    ): String {
        if (resourcePackageName != null && owners.contains(resourcePackageName)) {
            return resourcePackageName
        }
        if (owners.size == 1) {
            return owners.first()
        }
        throw IllegalStateException(
            "资源引用@$reference 在多个包中定义，无法唯一确定归属：${owners.joinToString()}"
        )
    }

    internal fun parseResourceSymbol(line: String): String? {
        val parts = line.split(Regex("\\s+"))
        val reference = when {
            parts.size == 2 -> "${parts[0]}/${parts[1]}"
            parts.size >= 3 && parts[0] == "int" -> "${parts[1]}/${parts[2]}"
            else -> null
        }
        return reference?.let(::normalizeResourceReference)
    }

    private fun parseManifestPackageName(inputStream: java.io.InputStream): String? {
        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setXIncludeAware(false)
            isExpandEntityReferences = false
        }

        val document = documentBuilderFactory.newDocumentBuilder().parse(inputStream)
        return document.documentElement?.getAttribute("package")?.takeIf(String::isNotBlank)
    }
}
