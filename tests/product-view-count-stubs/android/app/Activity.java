package android.app;

import android.content.ContextWrapper;
import android.content.SharedPreferences;

/** Host preferences/recreation counter only; production instances allocated without Android constructors. */
public class Activity extends ContextWrapper {
    public static SharedPreferences preferences;
    public static int recreates;
    public Activity(){super(null);}
    public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
    public void recreate(){recreates++;}
}
