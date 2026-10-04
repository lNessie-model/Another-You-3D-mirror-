package android.app;

/** Admission fixture only; production Activities are allocated without Android constructors. */
public class Activity extends android.content.ContextWrapper {
    public Activity(){super(null);}
    public boolean isFinishing(){return false;}
    public boolean isDestroyed(){return false;}
}
