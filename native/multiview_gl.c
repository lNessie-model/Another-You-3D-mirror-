#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

JNIEXPORT void JNICALL Java_com_mirror_bench_MultiviewGl_attach(
    JNIEnv *env, jclass klass, jint attachment, jint texture, jint base_view, jint count) {
    (void)klass;
    PFNGLFRAMEBUFFERTEXTUREMULTIVIEWOVRPROC attach =
        (PFNGLFRAMEBUFFERTEXTUREMULTIVIEWOVRPROC)eglGetProcAddress("glFramebufferTextureMultiviewOVR");
    if (!attach || eglGetCurrentContext() == EGL_NO_CONTEXT) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                        "OVR multiview function or current GL context unavailable");
        return;
    }
    attach(GL_FRAMEBUFFER, (GLenum)attachment, (GLuint)texture, 0, base_view, count);
}
