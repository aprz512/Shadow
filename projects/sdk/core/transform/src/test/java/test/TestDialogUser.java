package test;

import android.app.Dialog;

import com.tencent.shadow.core.runtime.ShadowActivity;

public class TestDialogUser {
    ShadowActivity use(Dialog dialog, ShadowActivity activity) {
        dialog.setOwnerActivity(activity);
        return dialog.getOwnerActivity();
    }
}
