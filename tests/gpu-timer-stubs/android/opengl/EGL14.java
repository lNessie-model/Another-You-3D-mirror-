package android.opengl;
public final class EGL14 {
    public static final EGLContext EGL_NO_CONTEXT=new EGLContext(0);
    public static EGLContext current=new EGLContext(1);
    public static EGLContext eglGetCurrentContext(){return current;}
}
