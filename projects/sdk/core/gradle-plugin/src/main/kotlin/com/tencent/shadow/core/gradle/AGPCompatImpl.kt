package com.tencent.shadow.core.gradle

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import javax.xml.parsers.DocumentBuilderFactory

internal class AGPCompatImpl : AGPCompat {

    override fun getNamespace(commonExtension: CommonExtension, project: Project): String? {
        commonExtension.namespace?.takeIf(String::isNotBlank)?.let { return it }

        val manifestFile = project.file("src/main/AndroidManifest.xml")
        if (!manifestFile.exists()) {
            return null
        }

        return try {
            val documentBuilder = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder()
            val manifest = documentBuilder.parse(manifestFile).documentElement
            manifest.getAttribute("package").takeIf(String::isNotBlank)
        } catch (_: Exception) {
            null
        }
    }

    override fun getAaptAdditionalParameters(commonExtension: CommonExtension): List<String> {
        return commonExtension.androidResources.additionalParameters.toList()
    }

    override fun addFlavorDimension(commonExtension: CommonExtension, dimensionName: String) {
        val existingDimensions = commonExtension.flavorDimensions.toList()
        if (existingDimensions.contains(dimensionName)) {
            return
        }
        commonExtension.flavorDimensions.add(dimensionName)
    }

    override fun setProductFlavorDefault(productFlavor: Any, isDefault: Boolean) {
        val setter = productFlavor.javaClass.methods.firstOrNull { method ->
            method.name in setOf("setDefault", "setIsDefault") &&
                    method.parameterTypes.size == 1 &&
                    (method.parameterTypes[0] == java.lang.Boolean.TYPE ||
                            method.parameterTypes[0] == java.lang.Boolean::class.java)
        } ?: return

        try {
            setter.invoke(productFlavor, isDefault)
        } catch (_: Exception) {
            // AGP 的 flavor DSL 在版本间实现类变化很大。
            // 这里仅在暴露出兼容 setter 时设置默认 flavor，
            // 没有 setter 时保持兼容而不是依赖内部实现类。
        }
    }
}
