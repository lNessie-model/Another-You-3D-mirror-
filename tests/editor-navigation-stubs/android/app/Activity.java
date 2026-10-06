package android.app;

import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;

/** Editor host boundary only; activity constructors/lifecycle are not Android simulation. */
public class Activity extends ContextWrapper {
    public static SharedPreferences preferences;
    public Intent hostIntent;
    public int finishes;
    public boolean hostFinishing,hostDestroyed;
    public Activity(){super(null);}
    public Intent getIntent(){return hostIntent;}
    public boolean isFinishing(){return hostFinishing;}
    public boolean isDestroyed(){return hostDestroyed;}
    public void finish(){finishes++;hostFinishing=true;}
    public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
    protected void onPause(){}
}
