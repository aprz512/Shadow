package test;

import android.app.Dialog;

public class DialogFieldOnly {
    private Dialog dialog;

    int keep(int value) {
        try {
            return value + 1;
        } catch (Throwable throwable) {
            return value;
        }
    }
}
