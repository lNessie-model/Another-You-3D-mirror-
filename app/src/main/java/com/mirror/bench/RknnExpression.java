package com.mirror.bench;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Experimental normalized-input expression suffix; no default pipeline integration. */
final class RknnExpression implements AutoCloseable {
    static final String MODEL_SHA256="17b0773a404521a8cf9624fa8147467d9fb3fdb309a641f244244b2910d4831c";
    static final String RUNTIME_SHA256="01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de";
    static final String RUNTIME_ENTRY="lib/arm64-v8a/librknnrt.so";
    private final NativeCalls calls;
    private long handle;
    private boolean faulted;

    /** modelPath is a fixed private asset copy; applicationApkPath is ApplicationInfo.sourceDir. */
    RknnExpression(String modelPath,String applicationApkPath)throws IOException {
        this(modelPath,applicationApkPath,BRIDGE);
    }
    // Package boundary used by host failure/concurrency tests; it retains all real file/hash gates.
    RknnExpression(String modelPath,String applicationApkPath,NativeCalls nativeCalls)throws IOException {
        if(nativeCalls==null)throw new IllegalArgumentException("Missing expression native calls");
        byte[] model=verifiedModel(modelPath);String runtime=verifiedRuntime(applicationApkPath);
        calls=nativeCalls;
        try {
            handle=calls.open(model,runtime);
            if(handle==0)throw new IllegalStateException("Expression native open returned no context");
            if(calls.info(handle)==null)throw new IllegalStateException("Missing expression metadata");
        } catch(Throwable error) {
            if(handle!=0){long owned=handle;handle=0;try{calls.close(owned);}catch(Throwable cleanup){if(cleanup!=error)error.addSuppressed(cleanup);}}
            throw error;
        }
    }
    /** Serialize lifecycle across caller threads; caller must not mutate input until this returns. */
    synchronized float[] run(ByteBuffer input) {
        requireOpen();if(faulted)throw new IllegalStateException("Expression context failed; close before retry");
        validateInput(input);
        try {
            float[] output=calls.run(handle,input);
            if(output==null||output.length!=52)throw new IllegalStateException("Invalid expression output shape");
            for(float v:output)if(!Float.isFinite(v)||v<0f||v>1f)throw new IllegalStateException("Invalid expression output probability");
            return output;
        } catch(Throwable error) {faulted=true;throw error;}
    }
    synchronized String info() {requireOpen();return calls.info(handle);}
    @Override public synchronized void close() {
        if(handle==0)return;
        long owned=handle;handle=0;faulted=true;
        calls.close(owned); // Failure is visible; the destroyed native wrapper is never retried.
    }
    static ByteBuffer buffer(){return ByteBuffer.allocateDirect(292*4).order(ByteOrder.nativeOrder());}
    private void requireOpen(){if(handle==0)throw new IllegalStateException("Closed expression model");}
    private static void validateInput(ByteBuffer input){
        if(input==null||!input.isDirect()||input.capacity()!=1168||input.position()!=0||input.limit()!=1168||input.order()!=ByteOrder.nativeOrder())
            throw new IllegalArgumentException("Expected 292 native-order direct float32 values, position zero and full limit");
        for(int i=0;i<292;i++)if(!Float.isFinite(input.getFloat(i*4)))throw new IllegalArgumentException("Nonfinite normalized expression input");
    }
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes){out.append(Character.forDigit((b>>>4)&15,16));out.append(Character.forDigit(b&15,16));}return out.toString();}
    private static byte[] verifiedModel(String path)throws IOException {
        if(path==null)throw new IOException("Missing expression model path");
        File file=new File(path).getCanonicalFile();
        if(!file.isFile()||file.length()!=1209569)throw new IOException("Expression model size mismatch");
        byte[] bytes;
        try(InputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream(1209569)){
            byte[] chunk=new byte[16384];int total=0,n;
            while((n=input.read(chunk))!=-1){total+=n;if(total>1209569)throw new IOException("Expression model changed size");output.write(chunk,0,n);}
            if(total!=1209569)throw new IOException("Incomplete expression model");bytes=output.toByteArray();
        }
        if(!MODEL_SHA256.equals(hex(sha256().digest(bytes))))throw new IOException("Expression model SHA256 mismatch");
        return bytes;
    }
    private static String verifiedRuntime(String path)throws IOException {
        if(path==null)throw new IOException("Missing application APK path");
        File file=new File(path).getCanonicalFile();
        if(!file.isFile()||file.getPath().contains("!"))throw new IOException("Invalid application APK path");
        try(ZipFile zip=new ZipFile(file)){
            ZipEntry entry=null;Enumeration<? extends ZipEntry> all=zip.entries();int seen=0;
            while(all.hasMoreElements()){
                ZipEntry next=all.nextElement();if(++seen>50000)throw new IOException("APK entry count exceeded");
                if(RUNTIME_ENTRY.equals(next.getName())){if(entry!=null)throw new IOException("Duplicate runtime APK entry");entry=next;}
            }
            if(entry==null||entry.isDirectory()||entry.getMethod()!=ZipEntry.STORED||entry.getSize()!=5518336||entry.getCompressedSize()!=5518336)
                throw new IOException("App RKNN runtime entry is missing or incompatible");
            MessageDigest digest=sha256();long total=0;
            try(InputStream input=zip.getInputStream(entry)){
                byte[] chunk=new byte[16384];int n;
                while((n=input.read(chunk))!=-1){total+=n;if(total>5518336)throw new IOException("Runtime entry size overflow");digest.update(chunk,0,n);}
            }
            if(total!=5518336||!RUNTIME_SHA256.equals(hex(digest.digest())))throw new IOException("App RKNN runtime SHA256 mismatch");
        }
        return file.getPath()+"!/"+RUNTIME_ENTRY;
    }
    interface NativeCalls {long open(byte[] model,String runtime);String info(long handle);float[] run(long handle,ByteBuffer input);void close(long handle);}
    private static final NativeCalls BRIDGE=new NativeCalls(){
        public long open(byte[] model,String runtime){NativeLibrary.load();return openNative(model,runtime);}
        public String info(long h){return infoNative(h);}
        public float[] run(long h,ByteBuffer input){return runNative(h,input);}
        public void close(long h){closeNative(h);}
    };
    private static final class NativeLibrary {static{System.loadLibrary("mirror_rknn_expression");}static void load(){}}
    private static native long openNative(byte[] model,String runtimePath);
    private static native String infoNative(long handle);
    private static native float[] runNative(long handle,ByteBuffer input);
    private static native void closeNative(long handle);
}
