package android.opengl;

import android.content.Context;
import android.view.View;

/** Surface lifecycle boundary counts only; never creates EGL or a renderer thread. */
public class GLSurfaceView extends View {
    public int pauses,resumes;
    public GLSurfaceView(Context context){super(context);}
    public void onPause(){pauses++;}
    public void onResume(){resumes++;}
}
