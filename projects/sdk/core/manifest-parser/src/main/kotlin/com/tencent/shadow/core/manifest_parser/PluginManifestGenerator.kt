package com.tencent.shadow.core.manifest_parser

import com.squareup.javapoet.*
import com.tencent.shadow.core.runtime.PluginManifest
import java.io.File
import java.util.*
import javax.lang.model.element.Modifier

/**
 * PluginManifest.java生成器
 *
 * 将Loader所需的插件Manifest信息生成为Java文件，
 * 添加runtime中PluginManifest接口的实现方法
 */
class PluginManifestGenerator {
    /**
     * 生成器入口方法
     *
     * 根据AndroidManifestReader输出的Map生成PluginManifest.java到outputDir目录中。
     *
     * @param manifestMap   AndroidManifestReader#read的输出Map
     * @param outputDir     生成文件的输出目录
     * @param packageName   生成类的包名
     */
    fun generate(
        manifestMap: ManifestMap,
        outputDir: File,
        packageName: String,
        resourcePackageName: String? = null,
        resourceReferencePackageLookup: Map<String, String> = emptyMap()
    ) {
        val pluginManifestBuilder = PluginManifestBuilder(
            manifestMap,
            resourcePackageName,
            resourceReferencePackageLookup
        )
        val pluginManifest = pluginManifestBuilder.build()
        JavaFile.builder(packageName, pluginManifest)
            .build()
            .writeTo(outputDir)
    }
}

private class PluginManifestBuilder(
    val manifestMap: ManifestMap,
    private val resourcePackageName: String?,
    private val resourceReferencePackageLookup: Map<String, String>
) {
    val classBuilder: TypeSpec.Builder =
        TypeSpec.classBuilder("PluginManifest")
            .addSuperinterface(ClassName.get(PluginManifest::class.java))
            .addModifiers(Modifier.PUBLIC)!!

    fun build(): TypeSpec {
        listOf(
            *buildApplicationFields(),
            buildActivityInfoArrayField(),
            buildServiceInfoArrayField(),
            buildReceiverInfoArrayField(),
            buildProviderInfoArrayField(),
        ).forEach { fieldSpec ->
            val getterMethod = buildGetterMethod(fieldSpec)
            classBuilder.addField(fieldSpec)
            classBuilder.addMethod(getterMethod)
        }
        return classBuilder.build()
    }

    private fun buildApplicationFields(): Array<FieldSpec> {
        val stringFields = mapOf(
            "applicationPackageName" to AndroidManifestKeys.`package`,
            "applicationClassName" to AndroidManifestKeys.name,
            "appComponentFactory" to AndroidManifestKeys.appComponentFactory,
        ).map { (fieldName, key) ->
            buildStringField(fieldName, key)
        }

        val resIdFields = mapOf(
            "applicationTheme" to AndroidManifestKeys.theme,
        ).map { (fieldName, key) ->
            buildResIdField(fieldName, key)
        }

        return (stringFields + resIdFields).toTypedArray()
    }

    private fun buildActivityInfoArrayField() = buildComponentArrayField(
        AndroidManifestKeys.activity,
        "ActivityInfo",
        "activities",
        ::toNewActivityInfo,
    )

    private fun buildServiceInfoArrayField() = buildComponentArrayField(
        AndroidManifestKeys.service,
        "ServiceInfo",
        "services",
        ::toNewServiceInfo,
    )

    private fun buildReceiverInfoArrayField() = buildComponentArrayField(
        AndroidManifestKeys.receiver,
        "ReceiverInfo",
        "receivers",
        ::toNewReceiverInfo,
    )

    private fun buildProviderInfoArrayField() = buildComponentArrayField(
        AndroidManifestKeys.provider,
        "ProviderInfo",
        "providers",
        ::toNewProviderInfo,
    )

    private fun buildComponentArrayField(
        key: String,
        subClassName: String,
        fieldName: String,
        transform: (ComponentMap) -> String
    ): FieldSpec {
        @Suppress("UNCHECKED_CAST")
        val componentMapArray = manifestMap[key] as Array<ComponentMap>
        val literal = componentMapArray.joinToString(
            separator = ",\n",
            prefix = "{\n",
            postfix = "\n}",
            transform = transform
        )

        val componentInfoArrayTypeName = ArrayTypeName.of(
            ClassName.get(
                "com.tencent.shadow.core.runtime",
                "PluginManifest",
                subClassName
            )
        )

        val codeBlock = if (componentMapArray.isNotEmpty()) {
            CodeBlock.of("new \$1T \$2L", componentInfoArrayTypeName, literal)
        } else {
            nullCodeBlock()
        }
        return privateStaticFinalFieldBuilder(
            componentInfoArrayTypeName,
            fieldName,
        ).initializer(
            codeBlock
        ).build()
    }

    private fun buildStringField(fieldName: String, key: String): FieldSpec {
        val value = manifestMap[key]
        val codeBlock = if (value != null) {
            CodeBlock.of("\"$1L\"", value)
        } else {
            nullCodeBlock()
        }
        return privateStaticFinalStringFieldBuilder(fieldName)
            .initializer(codeBlock).build()
    }

    private fun buildGetterMethod(fieldSpec: FieldSpec): MethodSpec =
        MethodSpec.methodBuilder(
            "get" + fieldSpec.name.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
        )
            .addModifiers(
                Modifier.PUBLIC,
                Modifier.FINAL,
            )
            .returns(fieldSpec.type)
            .addStatement(CodeBlock.of("return ${fieldSpec.name}"))
            .build()

    private fun buildResIdField(fieldName: String, key: String): FieldSpec {
        val manifestValue = manifestMap[key]
        return if (manifestValue != null) {
            buildResIdFieldWithValue(fieldName, manifestValue)
        } else {
            privateStaticFinalIntFieldBuilder(fieldName)
                .initializer(
                    CodeBlock.of("$1L", "0")
                ).build()
        }
    }

    private fun buildResIdFieldWithValue(
        fieldName: String,
        manifestValue: Any,
    ): FieldSpec {

        val resIdLiteral = themeStringToResId(manifestValue)
        return privateStaticFinalIntFieldBuilder(fieldName)
            .initializer(
                CodeBlock.of("$1L", resIdLiteral)
            ).build()
    }


    private fun toNewActivityInfo(componentMap: ComponentMap): String {
        fun makeResIdLiteral(
            key: String,
            defaultValue: String = "0",
            valueToResId: (value: String) -> String
        ): String {
            val value = componentMap[key] as String?
            val literal = if (value != null) {
                valueToResId(value)
            } else {
                defaultValue
            }
            return literal
        }

        val themeLiteral = makeResIdLiteral(AndroidManifestKeys.theme) {
            themeStringToResId(it)
        }
        val configChangesLiteral = makeResIdLiteral(AndroidManifestKeys.configChanges) {
            activityConfigChangesStringToLiteral(it)
        }
        val softInputModeLiteral = makeResIdLiteral(AndroidManifestKeys.windowSoftInputMode) {
            windowSoftInputModeStringToLiteral(it)
        }

        val screenOrientation = makeResIdLiteral(AndroidManifestKeys.screenOrientation, "-1") {
            screenOrientationStringToLiteral(it)
        }

        return "new com.tencent.shadow.core.runtime.PluginManifest" +
                ".ActivityInfo(" +
                "\"${componentMap[AndroidManifestKeys.name]}\", " +
                "$themeLiteral ," +
                "$configChangesLiteral ," +
                "$softInputModeLiteral ," +
                screenOrientation +
                ")"
    }

    private fun toNewServiceInfo(componentMap: ComponentMap): String {
        return "new com.tencent.shadow.core.runtime.PluginManifest" +
                ".ServiceInfo(\"${componentMap[AndroidManifestKeys.name]}\")"
    }

    private fun toNewReceiverInfo(componentMap: ComponentMap): String {
        @Suppress("UNCHECKED_CAST")
        val actions = componentMap[AndroidManifestKeys.action] as List<String>?
        val actionsLiteral =
            actions?.joinToString(
                prefix = "new String[]{\"",
                separator = "\", \"",
                postfix = "\"}"
            ) ?: "null"

        return "new com.tencent.shadow.core.runtime.PluginManifest" +
                ".ReceiverInfo(\"${componentMap[AndroidManifestKeys.name]}\", " +
                actionsLiteral +
                ")"
    }

    private fun toNewProviderInfo(componentMap: ComponentMap): String {
        val authoritiesValue = componentMap[AndroidManifestKeys.authorities]
        //如果未传值使用android.content.pm.ProviderInfo.grantUriPermissions的默认值false
        val grantUriPermissions = componentMap[AndroidManifestKeys.grantUriPermissions] ?: false

        val authoritiesLiteral =
            if (authoritiesValue != null) {
                "\"${authoritiesValue}\""
            } else {
                "null"
            }

        return "new com.tencent.shadow.core.runtime.PluginManifest" +
                ".ProviderInfo(\"${componentMap[AndroidManifestKeys.name]}\", $authoritiesLiteral,$grantUriPermissions)"
    }

    private fun themeStringToResId(manifestValue: Any): String {
        val formatValue = manifestValue as String
        return when {
            formatValue.startsWith("@ref/") -> formatValue.removePrefix("@ref/")
            formatValue.startsWith("@0x") -> formatValue.removePrefix("@")
            formatValue.startsWith("@android:") ->
                resourceReferenceToCode(formatValue.removePrefix("@android:"), "android.R")

            formatValue.startsWith("@") ->
                localResourceReferenceToCode(formatValue.removePrefix("@"))

            else ->
                throw TODO("不支持其他格式: $formatValue")
        }
    }

    private fun localResourceReferenceToCode(reference: String): String {
        val rClassName = localResourceClassName(reference)
        return resourceReferenceToCode(reference, rClassName)
    }

    private fun localResourceClassName(reference: String): String {
        val packageName = resourceReferencePackageLookup[normalizeResourceReference(reference)]
            ?: resourceReferencePackageLookup[reference]
            ?: resourcePackageName
        return packageName?.takeIf(String::isNotBlank)?.let { "$it.R" } ?: "R"
    }

    companion object {
        fun privateStaticFinalFieldBuilder(type: TypeName, fieldName: String) = FieldSpec.builder(
            type,
            fieldName,
            Modifier.PRIVATE,
            Modifier.STATIC,
            Modifier.FINAL,
        )!!

        fun privateStaticFinalStringFieldBuilder(fieldName: String) =
            privateStaticFinalFieldBuilder(
                ClassName.get(String::class.java),
                fieldName,
            )

        fun privateStaticFinalIntFieldBuilder(fieldName: String) =
            privateStaticFinalFieldBuilder(
                TypeName.INT,
                fieldName,
            )

        fun nullCodeBlock() = CodeBlock.of("null")!!

        private val activityConfigChangesValues = mapOf(
            "mcc" to 0x0001,
            "mnc" to 0x0002,
            "locale" to 0x0004,
            "touchscreen" to 0x0008,
            "keyboard" to 0x0010,
            "keyboardHidden" to 0x0020,
            "navigation" to 0x0040,
            "orientation" to 0x0080,
            "screenLayout" to 0x0100,
            "uiMode" to 0x0200,
            "screenSize" to 0x0400,
            "smallestScreenSize" to 0x0800,
            "density" to 0x1000,
            "layoutDirection" to 0x2000,
            "colorMode" to 0x4000,
            "grammaticalGender" to 0x8000,
            "fontWeightAdjustment" to 0x10000000,
            "fontScale" to 0x40000000,
        )

        private val windowSoftInputModeValues = mapOf(
            "stateUnspecified" to 0x00,
            "stateUnchanged" to 0x01,
            "stateHidden" to 0x02,
            "stateAlwaysHidden" to 0x03,
            "stateVisible" to 0x04,
            "stateAlwaysVisible" to 0x05,
            "adjustUnspecified" to 0x00,
            "adjustResize" to 0x10,
            "adjustPan" to 0x20,
            "adjustNothing" to 0x30,
            "isForwardNavigation" to 0x100,
        )

        private val screenOrientationValues = mapOf(
            "unspecified" to -1,
            "landscape" to 0,
            "portrait" to 1,
            "user" to 2,
            "behind" to 3,
            "sensor" to 4,
            "nosensor" to 5,
            "sensorLandscape" to 6,
            "sensorPortrait" to 7,
            "reverseLandscape" to 8,
            "reversePortrait" to 9,
            "fullSensor" to 10,
            "userLandscape" to 11,
            "userPortrait" to 12,
            "fullUser" to 13,
            "locked" to 14,
        )

        private fun activityConfigChangesStringToLiteral(value: String): String =
            manifestValueToLiteral(
                attributeName = AndroidManifestKeys.configChanges,
                value = value,
                symbolicValues = activityConfigChangesValues,
                allowFlagCombination = true
            )

        private fun windowSoftInputModeStringToLiteral(value: String): String =
            manifestValueToLiteral(
                attributeName = AndroidManifestKeys.windowSoftInputMode,
                value = value,
                symbolicValues = windowSoftInputModeValues,
                allowFlagCombination = true
            )

        private fun screenOrientationStringToLiteral(value: String): String =
            manifestValueToLiteral(
                attributeName = AndroidManifestKeys.screenOrientation,
                value = value,
                symbolicValues = screenOrientationValues,
                allowFlagCombination = false
            )

        private fun manifestValueToLiteral(
            attributeName: String,
            value: String,
            symbolicValues: Map<String, Int>,
            allowFlagCombination: Boolean
        ): String {
            if (isNumericLiteral(value)) {
                return value
            }

            val segments = value.split('|').map(String::trim).filter(String::isNotEmpty)
            if (segments.isEmpty()) {
                throw IllegalArgumentException("$attributeName 为空，不支持生成")
            }
            if (!allowFlagCombination && segments.size != 1) {
                throw IllegalArgumentException("$attributeName 不支持组合值: $value")
            }

            val resolvedValue = segments.fold(0) { acc, segment ->
                val flagValue = symbolicValues[segment] ?: throw IllegalArgumentException(
                    "$attributeName 存在未知取值: $segment (原始值: $value)"
                )
                acc or flagValue
            }
            return intLiteral(resolvedValue)
        }

        private fun isNumericLiteral(value: String): Boolean =
            value.matches(Regex("-?(0x[0-9a-fA-F]+|\\d+)"))

        private fun intLiteral(value: Int): String =
            if (value < 0) {
                value.toString()
            } else {
                "0x" + Integer.toHexString(value).uppercase(Locale.ROOT)
            }

        private fun resourceReferenceToCode(reference: String, rClassName: String): String {
            val parts = normalizeResourceReference(reference).split("/", limit = 2)
            if (parts.size != 2) {
                throw TODO("不支持其他资源引用格式: @$reference")
            }
            val type = parts[0]
            val entryName = parts[1]
            return "$rClassName.$type.$entryName"
        }
    }
}
