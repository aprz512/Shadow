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

package com.tencent.shadow.core.transform.specific

import com.tencent.shadow.core.transform_kit.AbstractTransformTest
import javassist.CtClass
import org.junit.Assert
import org.junit.Test

class InstrumentationTransformTest : AbstractTransformTest() {

    private fun transform(vararg classes: CtClass): InstrumentationTransform {
        val allInputClass = classes.toSet()
        val transform = InstrumentationTransform()
        transform.mClassPool = sLoader
        transform.setup(allInputClass)

        transform.list.forEach { step ->
            step.filter(allInputClass).forEach { step.transform(it) }
        }
        return transform
    }

    @Test
    fun testInstrumentationTransform() {
        val targetClass = sLoader["test.TestInstrumentation"]

        transform(targetClass)

        setOf(targetClass).forEach {
            Assert.assertEquals(
                "Instrumentation父类应该都变为了ShadowInstrumentation",
                "com.tencent.shadow.core.runtime.ShadowInstrumentation",
                it.classFile.superclass
            )
        }
    }

    @Test
    fun testInstrumentationMethodCallFilter() {
        val instrumentationSubclass = sLoader["test.TestInstrumentation"]
        val methodCallClass = sLoader["test.UseInstrumentationMethodCall"]
        val fieldOnlyClass = sLoader["test.InstrumentationFieldOnly"]
        val allInputClass = setOf(instrumentationSubclass, methodCallClass, fieldOnlyClass)

        val transform = InstrumentationTransform()
        transform.mClassPool = sLoader
        transform.setup(allInputClass)

        val renameStep = transform.list[0]
        renameStep.filter(allInputClass).forEach { renameStep.transform(it) }

        val methodRedirectStep = transform.list[1]
        val selectedClasses = methodRedirectStep.filter(allInputClass)

        Assert.assertTrue(
            "调用 newApplication/newActivity 的类应该被选中",
            selectedClasses.contains(methodCallClass)
        )
        Assert.assertFalse(
            "仅引用 Instrumentation 子类的类不应被选中",
            selectedClasses.contains(fieldOnlyClass)
        )

        methodRedirectStep.transform(methodCallClass)

        val shadowInstrumentation = sLoader["com.tencent.shadow.core.runtime.ShadowInstrumentation"]
        Assert.assertTrue(
            "newShadowApplication 调用应该可以找到",
            matchMethodCallInClass(
                shadowInstrumentation.getMethod(
                    "newShadowApplication",
                    "(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Lcom/tencent/shadow/core/runtime/ShadowApplication;"
                ),
                methodCallClass
            )
        )
        Assert.assertTrue(
            "newShadowActivity 调用应该可以找到",
            matchMethodCallInClass(
                shadowInstrumentation.getMethod(
                    "newShadowActivity",
                    "(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Intent;)Lcom/tencent/shadow/core/runtime/ShadowActivity;"
                ),
                methodCallClass
            )
        )
    }
}
