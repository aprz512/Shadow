package test.fragment;

import android.content.Context;

public class UseSuperGetContextFragment extends TestFragment {

    Context test() {
        return super.getContext();
    }
}
