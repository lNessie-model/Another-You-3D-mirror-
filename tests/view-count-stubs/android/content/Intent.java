package android.content;

import android.os.Bundle;

/** Only a host value-container fixture for actual production Intent factories, never Android lifecycle. */
public class Intent {
    private Bundle extras;
    public Intent(Context ignored,Class<?> target) {}
    public Bundle getExtras(){return extras;}
    private Bundle extras(){if(extras==null)extras=new Bundle();return extras;}
    public Intent putExtra(String key,int value){extras().putInt(key,value);return this;}
    public Intent putExtra(String key,float value){extras().putFloat(key,value);return this;}
    public Intent putExtra(String key,boolean value){extras().putBoolean(key,value);return this;}
    public Intent putExtra(String key,String value){extras().putString(key,value);return this;}
}
