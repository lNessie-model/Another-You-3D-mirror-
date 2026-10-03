package com.mirror.bench;

import android.os.Bundle;

/** The experiment must never activate through defaults, release builds or loosely typed extras. */
public final class RuntimeExpressionBackendTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if(!ok)throw new AssertionError("check "+checks); }
    private static void rejected(Bundle extras,boolean debug) {
        try { RuntimeExpressionBackend.read(extras,debug); throw new AssertionError("accepted override"); }
        catch(IllegalArgumentException expected){checks++;}
    }
    public static void main(String[] args) {
        check(!RuntimeExpressionBackend.read(null,false));check(!RuntimeExpressionBackend.read(null,true));
        Bundle empty=new Bundle();check(!RuntimeExpressionBackend.read(empty,false));check(!RuntimeExpressionBackend.read(empty,true));
        Bundle enabled=new Bundle();enabled.putBoolean("test_npu_blendshapes",true);
        check(RuntimeExpressionBackend.read(enabled,true));rejected(enabled,false);
        Bundle disabled=new Bundle();disabled.putBoolean("test_npu_blendshapes",false);
        check(!RuntimeExpressionBackend.read(disabled,true));rejected(disabled,false);
        Bundle text=new Bundle();text.putString("test_npu_blendshapes","true");rejected(text,true);
        Bundle integer=new Bundle();integer.putInt("test_npu_blendshapes",1);rejected(integer,true);
        Bundle absentValue=new Bundle();absentValue.putString("test_npu_blendshapes",null);rejected(absentValue,true);
        Bundle unrelated=new Bundle();unrelated.putBoolean("test_persistent_fbos",true);check(!RuntimeExpressionBackend.read(unrelated,true));
        System.out.println("Expression debug option: "+checks+" checks passed");
    }
}
