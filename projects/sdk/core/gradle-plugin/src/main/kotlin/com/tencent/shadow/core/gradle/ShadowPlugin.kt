/*
 * Tencent is pleased to support the open source community by making Tencent Shadow available.
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.tencent.shadow.core.gradle

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ScopedArtifacts
import com.android.sdklib.AndroidVersion.VersionCodes
import com.tencent.shadow.core.gradle.extensions.PackagePluginExtension
import com.tencent.shadow.core.manifest_parser.collectManifestResourceReferences
import com.tencent.shadow.core.manifest_parser.generatePluginManifest
import com.tencent.shadow.core.transform.GradleTransformWrapper
import com.tencent.shadow.core.transform.ShadowTransform
import com.tencent.shadow.core.transform_kit.AndroidClassPoolBuilder
import com.tencent.shadow.core.transform_kit.ClassPoolBuilder
import org.gradle.api.*
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.io.File
import java.util.Properties

class ShadowPlugin : Plugin<Project> {

    private lateinit var androidClassPoolBuilder: ClassPoolBuilder
    private lateinit var contextClassLoader: ClassLoader
    private lateinit var agpCompat: AGPCompat

    override fun apply(project: Project) {
        agpCompat = buildAgpCompat()
        val androidExtension = project.extensions.getByType(ApplicationExtension::class.java)
        val androidComponentsExtension =
            project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

        //在这里取到的contextClassLoader包含运行时库(classpath方式引入的)shadow-runtime
        contextClassLoader = Thread.currentThread().contextClassLoader
        val lateInitBuilder = object : ClassPoolBuilder {
            override fun build() = androidClassPoolBuilder.build()
        }

        addFlavorForTransform(androidExtension)

        val shadowExtension = project.extensions.create("shadow", ShadowExtension::class.java)
        if (!project.hasProperty("disable_shadow_transform")) {
            val shadowTransform = ShadowTransform(
                project,
                lateInitBuilder,
                { shadowExtension.transformConfig.useHostContext },
                { shadowExtension.transformConfig.skipTransformPackages }
            )
            androidComponentsExtension.onVariants(
                selector = androidComponentsExtension.selector()
                    .withFlavor(
                        ShadowTransform.DimensionName
                                to ShadowTransform.ApplyShadowTransformFlavorName
                    )
            ) { variant ->
                val taskProvider = project.tasks.register(
                    "${variant.name}ShadowTransform",
                    GradleTransformWrapper::class.java,
                    shadowTransform
                )
                variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
                    .use<GradleTransformWrapper>(taskProvider)
                    .toTransform(
                        ScopedArtifact.CLASSES,
                        GradleTransformWrapper::allJars,
                        GradleTransformWrapper::allDirectories,
                        GradleTransformWrapper::output
                    )
            }
        }

        project.extensions.create("packagePlugin", PackagePluginExtension::class.java, project)

        androidComponentsExtension.onVariants(
            androidComponentsExtension.selector().withFlavor(
                ShadowTransform.DimensionName to ShadowTransform.ApplyShadowTransformFlavorName
            )
        ) { pluginVariant ->
            checkAaptPackageIdConfig(androidExtension, pluginVariant)
            createGeneratePluginManifestTasks(project, pluginVariant)
        }

        project.afterEvaluate {
            initAndroidClassPoolBuilder(androidExtension, project)
            createPackagePluginTasks(project)
        }

        checkKotlinAndroidPluginForPluginManifestTask(project)
    }

    /**
     * GeneratePluginManifestTask会向android DSL添加新的java源码目录，
     * 而kotlin-android会在syncKotlinAndAndroidSourceSets中接管java的源码目录，
     * 从而使后添加到android DSL中的java目录失效。
     */
    private fun checkKotlinAndroidPluginForPluginManifestTask(project: Project) {
        if (project.plugins.hasPlugin("kotlin-android")) {
            throw Error("必须在kotlin-android之前应用com.tencent.shadow.plugin")
        }
    }

    private fun createPackagePluginTasks(project: Project) {
        val packagePlugin = project.extensions.findByName("packagePlugin")
        val extension = packagePlugin as PackagePluginExtension
        val buildTypes = extension.buildTypes

        val tasks = mutableListOf<Task>()
        for (i in buildTypes) {
            project.logger.info("buildTypes = " + i.name)
            val task = createPackagePluginTask(project, i)
            tasks.add(task)
        }
        if (tasks.isNotEmpty()) {
            project.tasks.register("packageAllPlugin") {
                it.group = "plugin"
                it.description = "打包所有插件"
                it.dependsOn(tasks)
            }
        }
    }

    /**
     * 创建生成PluginManifest.java的任务
     */
    private fun createGeneratePluginManifestTasks(
        project: Project,
        pluginVariant: ApplicationVariant
    ) {
        val variantName = pluginVariant.name
        val capitalizeVariantName = variantName.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        val mergedManifest = pluginVariant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val runtimeSymbols = pluginVariant.artifacts.get(SingleArtifact.RUNTIME_SYMBOL_LIST)

        // 添加生成PluginManifest.java任务
        val pluginManifestSourceDir = project.layout.buildDirectory.dir(
            "generated/source/pluginManifest/$variantName"
        )
        val generatePluginManifestTask =
            project.tasks.register("generate${capitalizeVariantName}PluginManifest") {
                it.inputs.file(mergedManifest).withPropertyName("mergedManifest")
                it.inputs.file(runtimeSymbols).withPropertyName("runtimeSymbols")
                it.outputs.dir(pluginManifestSourceDir).withPropertyName("pluginManifestSourceDir")

                it.doLast {
                    val processedManifestFile = mergedManifest.get().asFile
                    val resourcePackageName = pluginVariant.namespace.get()
                    val manifestResourceReferences =
                        collectManifestResourceReferences(processedManifestFile)
                    val resourceReferencePackageLookup = resolveResourceReferencePackageLookup(
                        project,
                        variantName,
                        resourcePackageName,
                        manifestResourceReferences
                    )
                    generatePluginManifest(
                        processedManifestFile,
                        pluginManifestSourceDir.get().asFile,
                        "com.tencent.shadow.core.manifest_parser",
                        resourcePackageName,
                        resourceReferencePackageLookup
                    )
                }
            }
        pluginVariant.configureJavaCompileTask { javacTask ->
            javacTask.dependsOn(generatePluginManifestTask)
            javacTask.source(pluginManifestSourceDir)
        }
    }

    /**
     * 检查插件是否修改了资源ID分区
     *
     * 因为CreateResourceBloc在为插件创建Resources对象时，
     * 将宿主和插件的apk都放进去了，所以不能让宿主和插件的资源ID冲突。详见CreateResourceBloc注释。
     *
     * 此任务只是检查任务，对构建无影响。
     */
    private fun checkAaptPackageIdConfig(
        androidExtension: ApplicationExtension,
        pluginVariant: ApplicationVariant
    ) {
        val minSdkVersion = pluginVariant.minSdk.apiLevel
        val parameterList = agpCompat.getAaptAdditionalParameters(androidExtension)
        var foundPackageIdParameter = false
        parameterList.forEachIndexed { index, parameter ->
            if (parameter == "--package-id" && parameterList.size >= index + 2) {
                val packageIdSetting = parameterList[index + 1]
                val packageIdValue = Integer.decode(packageIdSetting)

                if (minSdkVersion > VersionCodes.O) {
                    if (packageIdValue <= 0x7f) {
                        throw Error("minSdkVersion大于26时--package-id必须大于0x7f")
                    } else {
                        foundPackageIdParameter = true
                    }
                } else {
                    /*
                    为了兼容minSDK小于26，且packageId大于0x7f时Android系统的bug，aapt对id进行了修改，
                    导致Resources中记录的id值和layout中使用的id值不一致。
                    但是minSDK小于26时可以使用--allow-reserved-package-id选项使用小于0x7f的值。
                    https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/readme.md#version-2_14
                    https://developer.android.com/studio/command-line/aapt2#link_options
                     */
                    if (packageIdValue >= 0x7f) {
                        throw Error(
                            "minSdkVersion小于26时--package-id必须小于0x7f，" +
                                    "同时使用--allow-reserved-package-id选项。"
                        )
                    } else {
                        foundPackageIdParameter = true
                    }
                }
            }
        }
        if (!foundPackageIdParameter) {
            val example1 = "androidResources {\n" +
                    "    additionalParameters += [\"--package-id\", \"0x80\"]\n" +
                    "}"
            val example2 = "androidResources {\n" +
                    "    additionalParameters += [\"--package-id\", \"0x7E\", \"--allow-reserved-package-id\"]\n" +
                    "}"
            val example = if (minSdkVersion > VersionCodes.O) example1 else example2
            throw Error(
                "插件需要利用aapt2的修改资源ID前缀的选项使其与宿主不同。\n" +
                        "没有找到--package-id参数。示例：\n" + example
            )
        }
    }

    private fun resolveResourceReferencePackageLookup(
        project: Project,
        variantName: String,
        resourcePackageName: String?,
        manifestResourceReferences: Set<String>
    ): Map<String, String> {
        if (manifestResourceReferences.isEmpty()) {
            return emptyMap()
        }
        val symbolOwners = linkedMapOf<String, LinkedHashSet<String>>()
        collectCompileClasspathProjects(project, variantName).forEach { dependencyProject ->
            findPackageAwareRFiles(dependencyProject).forEach { symbolFile ->
                ResourceSymbolLookup.parsePackageAwareRFile(symbolFile)
                    .forEach { (reference, packageName) ->
                        if (reference in manifestResourceReferences) {
                            symbolOwners.getOrPut(reference) { linkedSetOf() }.add(packageName)
                        }
                    }
            }
        }
        collectCompileClasspathAars(project, variantName).forEach { aarFile ->
            ResourceSymbolLookup.parseAarResourceSymbols(aarFile)
                .forEach { (reference, packageName) ->
                    if (reference in manifestResourceReferences) {
                        symbolOwners.getOrPut(reference) { linkedSetOf() }.add(packageName)
                    }
                }
        }

        return symbolOwners.mapValues { (reference, owners) ->
            ResourceSymbolLookup.selectResourceOwner(reference, owners, resourcePackageName)
        }
    }

    private fun collectCompileClasspathProjects(
        project: Project,
        variantName: String
    ): Set<Project> {
        val result = linkedSetOf<Project>()

        fun visit(target: Project) {
            if (!result.add(target)) {
                return
            }
            val compileClasspath = findCompileClasspathConfiguration(target, variantName) ?: return
            compileClasspath.allDependencies
                .withType(ProjectDependency::class.java)
                .forEach { dependency ->
                    val dependencyProject = project.rootProject.findProject(dependency.path)
                        ?: throw IllegalStateException("找不到ProjectDependency: ${dependency.path}")
                    visit(dependencyProject)
                }
        }

        visit(project)
        return result
    }

    private fun findCompileClasspathConfiguration(
        project: Project,
        variantName: String
    ) = sequenceOf(
        "${variantName}CompileClasspath",
        "debugCompileClasspath",
        "releaseCompileClasspath"
    ).mapNotNull(project.configurations::findByName).firstOrNull()

    private fun collectCompileClasspathAars(
        project: Project,
        variantName: String
    ): Set<File> {
        val gradleUserHomeDir = project.gradle.gradleUserHomeDir
        val aarFiles = linkedSetOf<File>()
        collectCompileClasspathProjects(project, variantName)
            .mapNotNull { dependencyProject ->
                findCompileClasspathConfiguration(dependencyProject, variantName)
            }
            .forEach { compileClasspath ->
                compileClasspath.incoming.resolutionResult.allComponents.forEach { component ->
                    val moduleId = component.id as? ModuleComponentIdentifier ?: return@forEach
                    findCachedAarFile(
                        gradleUserHomeDir,
                        moduleId.group,
                        moduleId.module,
                        moduleId.version
                    )?.let(aarFiles::add)
                }
            }
        return aarFiles
    }

    private fun findCachedAarFile(
        gradleUserHomeDir: File,
        group: String,
        module: String,
        version: String
    ): File? {
        val artifactCacheDir = File(
            gradleUserHomeDir,
            "caches/modules-2/files-2.1/$group/$module/$version"
        )
        if (!artifactCacheDir.isDirectory) {
            return null
        }
        return artifactCacheDir.walkTopDown()
            .filter { candidate ->
                candidate.isFile &&
                        candidate.extension.equals("aar", ignoreCase = true) &&
                        candidate.name.startsWith("$module-$version")
            }
            .firstOrNull()
    }

    private fun findPackageAwareRFiles(project: Project): List<File> {
        val symbolRoot = project.layout.buildDirectory
            .dir("intermediates/symbol_list_with_package_name")
            .get()
            .asFile
        if (!symbolRoot.exists()) {
            return emptyList()
        }
        return symbolRoot.walkTopDown()
            .filter { it.isFile && it.name == "package-aware-r.txt" }
            .toList()
    }

    private fun addFlavorForTransform(commonExtension: CommonExtension) {
        agpCompat.addFlavorDimension(commonExtension, ShadowTransform.DimensionName)
        try {
            commonExtension.productFlavors.create(ShadowTransform.NoShadowTransformFlavorName) {
                it.dimension = ShadowTransform.DimensionName
                agpCompat.setProductFlavorDefault(it, true)
            }
            commonExtension.productFlavors.create(ShadowTransform.ApplyShadowTransformFlavorName) {
                it.dimension = ShadowTransform.DimensionName
                agpCompat.setProductFlavorDefault(it, false)
            }
        } catch (e: InvalidUserDataException) {
            throw Error("请在android{} DSL之前apply plugin: 'com.tencent.shadow.plugin'", e)
        }
    }

    private fun initAndroidClassPoolBuilder(
        commonExtension: CommonExtension,
        project: Project
    ) {
        val sdkDirectory = resolveSdkDirectory(project)
        val compileSdkVersion =
            commonExtension.compileSdk?.toString()
                ?: commonExtension.compileSdkPreview
                ?: throw IllegalStateException("compileSdk获取失败")
        val normalizedCompileSdkVersion = normalizeCompileSdkVersion(compileSdkVersion)
        val androidJarPath = "platforms/${normalizedCompileSdkVersion}/android.jar"
        val androidJar = File(sdkDirectory, androidJarPath)

        androidClassPoolBuilder = AndroidClassPoolBuilder(project, contextClassLoader, androidJar)
    }

    open class ShadowExtension {
        var transformConfig = TransformConfig()
        fun transform(action: Action<in TransformConfig>) {
            action.execute(transformConfig)
        }
    }

    class TransformConfig {
        var useHostContext: Array<String> = emptyArray()
        var skipTransformPackages: Array<String> = emptyArray()
    }

    companion object {
        private fun resolveSdkDirectory(project: Project): File {
            val propertiesFile = project.rootProject.file("local.properties")
            if (propertiesFile.exists()) {
                val properties = Properties()
                propertiesFile.inputStream().use(properties::load)
                val sdkDir = properties.getProperty("sdk.dir")
                if (!sdkDir.isNullOrBlank()) {
                    return File(sdkDir)
                }
            }

            val sdkDir = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            if (!sdkDir.isNullOrBlank()) {
                return File(sdkDir)
            }

            throw IllegalStateException("SDK location not found. Please set sdk.dir or ANDROID_HOME/ANDROID_SDK_ROOT")
        }

        private fun normalizeCompileSdkVersion(compileSdkVersion: String): String {
            return if (compileSdkVersion.startsWith("android-")) {
                compileSdkVersion
            } else {
                "android-$compileSdkVersion"
            }
        }

        private fun buildAgpCompat(): AGPCompat {
            return AGPCompatImpl()
        }
    }

}
