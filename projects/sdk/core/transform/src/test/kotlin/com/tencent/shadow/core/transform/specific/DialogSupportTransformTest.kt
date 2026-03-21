package com.tencent.shadow.core.transform.specific

import com.tencent.shadow.core.transform_kit.AbstractTransformTest
import org.junit.Assert
import org.junit.Test

class DialogSupportTransformTest : AbstractTransformTest() {

    @Test
    fun testDialogMethodCallFilter() {
        val dialogUserClass = sLoader["test.TestDialogUser"]
        val fieldOnlyClass = sLoader["test.DialogFieldOnly"]
        val allInputClass = setOf(dialogUserClass, fieldOnlyClass)

        val transform = DialogSupportTransform()
        transform.mClassPool = sLoader
        transform.setup(allInputClass)

        val methodRedirectStep = transform.list.single()
        val selectedClasses = methodRedirectStep.filter(allInputClass)

        Assert.assertTrue(
            "调用 Dialog owner activity 方法的类应该被选中",
            selectedClasses.contains(dialogUserClass)
        )
        Assert.assertFalse(
            "仅持有 Dialog 字段的类不应被选中",
            selectedClasses.contains(fieldOnlyClass)
        )

        methodRedirectStep.transform(dialogUserClass)

        val shadowDialogSupport = sLoader["com.tencent.shadow.core.runtime.ShadowDialogSupport"]
        Assert.assertTrue(
            "dialogSetOwnerActivity 调用应该可以找到",
            matchMethodCallInClass(
                shadowDialogSupport.getMethod(
                    "dialogSetOwnerActivity",
                    "(Landroid/app/Dialog;Lcom/tencent/shadow/core/runtime/ShadowActivity;)V"
                ),
                dialogUserClass
            )
        )
        Assert.assertTrue(
            "dialogGetOwnerActivity 调用应该可以找到",
            matchMethodCallInClass(
                shadowDialogSupport.getMethod(
                    "dialogGetOwnerActivity",
                    "(Landroid/app/Dialog;)Lcom/tencent/shadow/core/runtime/ShadowActivity;"
                ),
                dialogUserClass
            )
        )
    }
}
