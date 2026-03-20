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

package com.tencent.shadow.coding.aar_to_jar_plugin

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.TaskProvider

class AarToJarPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.afterEvaluate {
            val android = it.extensions.getByType(CommonExtension::class.java)
            android.buildTypes.forEach { buildType ->
                val createJarPackageTask = createJarPackageTask(project, buildType.name)
                addJarConfiguration(project, buildType.name, createJarPackageTask)
            }
        }
    }

    private fun createJarPackageTask(project: Project, buildType: String): TaskProvider<Copy> {
        val capitalizedBuildType = buildType.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        val taskName = "jar${capitalizedBuildType}Package"
        val aarFileName = "${project.name}-${buildType}"
        val aarFile = project.layout.buildDirectory.file("outputs/aar/${aarFileName}.aar")
        val outputDir = project.layout.buildDirectory.dir("outputs/jar")

        return project.tasks.register(taskName, Copy::class.java) {
            it.from(project.zipTree(aarFile))
            it.into(outputDir)
            it.include("classes.jar")
            it.rename("classes.jar", "${aarFileName}.jar")
            it.group = "build"
            it.description = "生成jar包"
            it.dependsOn(project.tasks.named("assemble${capitalizedBuildType}"))
        }
    }

    /**
     * 添加一个额外的Configuration，用于buildScript中以classpath方式依赖
     */
    private fun addJarConfiguration(
        project: Project,
        buildType: String,
        createJarPackageTask: TaskProvider<Copy>
    ) {
        val configurationName = "jar-${buildType}"
        val jarFile = project.layout.buildDirectory.file("outputs/jar/${project.name}-${buildType}.jar")
        project.configurations.create(configurationName)
        project.artifacts.add(configurationName, jarFile) {
            it.builtBy(createJarPackageTask)
        }
    }
}
