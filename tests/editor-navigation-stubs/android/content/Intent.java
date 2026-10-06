package android.content;

import android.os.Bundle;

/** Host extras container only. */
public class Intent {
    private final Bundle extras=new Bundle();
    public Intent(Context ignored,Class<?> target){}
    public Bundle getExtras(){return extras;}
    public Intent putExtra(String key,String value){extras.putString(key,value);return this;}
    public Intent putExtra(String key,boolean value){extras.putBoolean(key,value);return this;}
    public String getStringExtra(String key){return (String)extras.get(key);}
    public boolean getBooleanExtra(String key,boolean fallback){Object v=extras.get(key);return v==null?fallback:(Boolean)v;}
}
