package test;

import android.content.Context;
import android.content.Intent;

import com.tencent.shadow.core.runtime.ShadowActivity;
import com.tencent.shadow.core.runtime.ShadowApplication;

public class UseInstrumentationMethodCall {

    ShadowApplication useNewApplication(
            TestInstrumentation instrumentation,
            ClassLoader classLoader,
            String className,
            Context context
    ) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return instrumentation.newApplication(classLoader, className, context);
    }

    ShadowActivity useNewActivity(
            TestInstrumentation instrumentation,
            ClassLoader classLoader,
            String className,
            Intent intent
    ) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return instrumentation.newActivity(classLoader, className, intent);
    }
}
