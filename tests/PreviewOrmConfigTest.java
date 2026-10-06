package com.mirror.bench;

import android.os.Bundle;
import java.lang.reflect.*;

/** Actual compiled diagnostic dispatch, independent of Android GL or a mock renderer. */
public final class PreviewOrmConfigTest {
    private static int checks;
    private static Method read;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static boolean enabled(Bundle bundle,boolean debug,boolean other)throws Exception{
        return (Boolean)read.invoke(null,bundle,debug,other);
    }
    private static void rejected(Bundle bundle,boolean debug,boolean other)throws Exception{
        try{enabled(bundle,debug,other);throw new AssertionError("Invalid ORM diagnostic accepted");}
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException);}
    }
    public static void main(String[] args)throws Exception{
        read=Class.forName("com.mirror.bench.AvatarPreviewActivity").getDeclaredMethod("readOrmVerification",Bundle.class,boolean.class,boolean.class);
        read.setAccessible(true);
        check(!enabled(null,true,false));check(!enabled(null,false,false));
        check(!enabled(new Bundle(),true,false));check(!enabled(new Bundle(),false,false));
        Bundle bundle=new Bundle();
        for(boolean value:new boolean[]{false,true}){
            bundle.putBoolean("verify_orm_rg8",value);check(enabled(bundle,true,false)==value);
            rejected(bundle,false,false);
        }
        bundle.putBoolean("verify_orm_rg8",true);rejected(bundle,true,true);
        bundle.putBoolean("verify_orm_rg8",false);check(!enabled(bundle,true,true));
        bundle.putString("verify_orm_rg8",null);rejected(bundle,true,false);rejected(bundle,false,false);
        bundle.putString("verify_orm_rg8","true");rejected(bundle,true,false);
        bundle.putInt("verify_orm_rg8",1);rejected(bundle,true,false);
        bundle.putLong("verify_orm_rg8",1L);rejected(bundle,true,false);
        bundle.putFloat("verify_orm_rg8",1f);rejected(bundle,true,false);
        System.out.println("PreviewOrmConfigTest: "+checks+" checks passed; diagnostic dispatch only");
    }
}
