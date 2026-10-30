package cn.yuusei.m3x;

import android.app.Instrumentation;
import android.os.Bundle;

/** Standalone opt-in runner for MuMu images that omit android.test.runner.jar. */
public final class AppearanceTestRunner extends Instrumentation {
    static Instrumentation current;
    static Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); current=this; arguments=args; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            UiAppearanceRevisionTest test=new UiAppearanceRevisionTest();
            test.setName("testRevision70AndEndfieldOnEmulator"); test.runBare();
            result.putString("stream", "\nOK (1 test)\n"); finish(-1,result);
        } catch(Throwable error) {
            result.putString("stream", "\nFAILURES!!!\n"+android.util.Log.getStackTraceString(error)); finish(0,result);
        }
    }
}
