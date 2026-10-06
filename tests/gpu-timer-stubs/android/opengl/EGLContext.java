package android.opengl;
public final class EGLContext {
    private final int id;public EGLContext(int id){this.id=id;}
    @Override public boolean equals(Object v){return v instanceof EGLContext&&((EGLContext)v).id==id;}
    @Override public int hashCode(){return id;}
}
