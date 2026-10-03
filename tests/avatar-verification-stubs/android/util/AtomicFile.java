package android.util;

import java.io.*;

/** Exercise the production report bytes and filenames using local files, without Android fsync. */
public class AtomicFile {
    private final File file;
    public AtomicFile(File file){this.file=file;}
    public FileOutputStream startWrite()throws IOException{return new FileOutputStream(file);}
    public void finishWrite(FileOutputStream stream)throws IOException{stream.close();}
    public void failWrite(FileOutputStream stream){try{stream.close();}catch(IOException ignored){}}
}
