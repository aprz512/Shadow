package com.tencent.shadow.core.manifest_parser

import org.junit.Assert
import org.junit.Test
import java.io.File

class PluginManifestGeneratorTest {

    @Test
    fun testCompileCaseAsLittleAsPossible() {
        testCompile("case_as_little_as_possible.xml")
    }

    @Test
    fun testNoAppComponentFactory() {
        testCompile("noAppComponentFactory.xml")
    }

    @Test
    fun testCompileSampleApp() {
        testCompile("sample-app.xml")
    }

    @Test
    fun testCompileSymbolicManifestValues() {
        testCompile("symbolic-manifest-values.xml")
    }

    @Test
    fun testCompileWithResourceReferencePackageLookup() {
        val testFile = File(javaClass.classLoader.getResource("resource-package-lookup.xml")!!.toURI())
        val androidManifest = AndroidManifestReader().read(testFile)
        val generator = PluginManifestGenerator()

        val tempBuildDir = File("build", "PluginManifestGeneratorTest")
        val outputDir = File(tempBuildDir, "resource-package-lookup.xml")
        generator.generate(
            androidManifest,
            outputDir,
            "test",
            "test.app",
            mapOf("style/CustomActivityTheme" to "test.lib")
        )

        val rSourceDir = File(outputDir, "test/lib")
        rSourceDir.mkdirs()
        val rSourceFile = File(rSourceDir, "R.java")
        rSourceFile.writeText(
            """
            package test.lib;

            public final class R {
              public static final class style {
                public static int CustomActivityTheme;
              }
            }
            """.trimIndent()
        )

        testCompile(outputDir, rSourceFile)
    }

    private fun testCompile(case: String) {
        val testFile = File(javaClass.classLoader.getResource(case)!!.toURI())
        val androidManifest = AndroidManifestReader().read(testFile)
        val generator = PluginManifestGenerator()

        val tempBuildDir = File("build", "PluginManifestGeneratorTest")
        val outputDir = File(tempBuildDir, case)
        println("outputDir==$outputDir")
        generator.generate(androidManifest, outputDir, "test")

        testCompile(outputDir)
    }

    private fun testCompile(outputDir: File, vararg extraSources: File) {
        val sources = buildList {
            add(File(outputDir, "test/PluginManifest.java").absolutePath)
            extraSources.forEach { add(it.absolutePath) }
        }.joinToString(" ")
        val cmd = "javac -cp ../runtime/build/classes/java/main:build/classes/java/test $sources"
        val process = Runtime.getRuntime().exec(cmd)
        val ret = process.waitFor()
        Assert.assertEquals(cmd, 0, ret)
    }
}
