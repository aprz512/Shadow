package com.tencent.shadow.core.manifest_parser

import java.io.File

/**
 * manifest-parser的入口方法
 *
 * @param xmlFile       com.android.build.gradle.tasks.ManifestProcessorTask任务的输出文件，
 *                      一般位于apk工程的build/intermediates/merged_manifest目录中。
 * @param outputDir     生成文件的输出目录
 * @param packageName   生成类的包名
 * @param resourcePackageName 本地资源R类的包名
 */
fun generatePluginManifest(
    xmlFile: File,
    outputDir: File,
    packageName: String,
    resourcePackageName: String? = null,
    resourceReferencePackageLookup: Map<String, String> = emptyMap()
) {
    val androidManifest = AndroidManifestReader().read(xmlFile)
    val generator = PluginManifestGenerator()
    generator.generate(
        androidManifest,
        outputDir,
        packageName,
        resourcePackageName,
        resourceReferencePackageLookup
    )
}

fun collectManifestResourceReferences(xmlFile: File): Set<String> {
    val androidManifest = AndroidManifestReader().read(xmlFile)
    return collectManifestResourceReferences(androidManifest)
}

fun normalizeResourceReference(reference: String): String {
    val parts = reference.split("/", limit = 2)
    if (parts.size != 2) {
        return reference
    }
    val type = parts[0]
    val entryName = parts[1]
        .replace('.', '_')
        .replace('-', '_')
    return "$type/$entryName"
}

internal fun collectManifestResourceReferences(manifestMap: ManifestMap): Set<String> {
    val references = linkedSetOf<String>()

    fun collect(value: Any?) {
        when (value) {
            null -> Unit
            is String -> {
                if (value.startsWith("@") &&
                    !value.startsWith("@android:") &&
                    !value.startsWith("@0x") &&
                    !value.startsWith("@ref/")
                ) {
                    references.add(normalizeResourceReference(value.removePrefix("@")))
                }
            }

            is Map<*, *> -> value.values.forEach(::collect)
            is Array<*> -> value.forEach(::collect)
            is Iterable<*> -> value.forEach(::collect)
        }
    }

    collect(manifestMap)
    return references
}
