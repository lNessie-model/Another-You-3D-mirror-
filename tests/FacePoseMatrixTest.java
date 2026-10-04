package com.mirror.bench;

import com.google.mediapipe.formats.proto.MatrixDataProto.MatrixData;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;

/** Actual tasks-core 1.0.0 + protobuf-javalite classes, including unknown proto2 enum round-trip. */
public final class FacePoseMatrixTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        MatrixData implicit=valid().build();
        check(!implicit.hasLayout()&&implicit.getLayout()==MatrixData.Layout.COLUMN_MAJOR,"official proto2 default column-major");
        float[] copy=FacePoseMatrix.copyValidated(implicit);
        for(int i=0;i<16;i++)check(Float.floatToRawIntBits(copy[i])==Float.floatToRawIntBits(implicit.getPackedData(i)),"default layout preserves every packed float bit");
        copy[0]=99;check(FacePoseMatrix.copyValidated(implicit)[0]==1,"result does not alias protobuf");
        FacePoseMatrix.copyValidated(valid().setLayout(MatrixData.Layout.COLUMN_MAJOR).build());checks++;
        rejects(valid().setLayout(MatrixData.Layout.ROW_MAJOR).build(),"explicit row-major");
        rejects(valid().setRows(3).build(),"wrong rows");rejects(valid().clearCols().build(),"missing columns");
        rejects(valid().clearPackedData().build(),"incomplete packed data");rejects(valid().addPackedData(1).build(),"extra packed data");
        rejects(valid().setPackedData(3,Float.NaN).build(),"nonfinite packed data");
        MatrixData unknown=append(implicit,4,99,false);
        check(!unknown.hasLayout()&&unknown.getLayout()==MatrixData.Layout.COLUMN_MAJOR,"real generated getter hides unknown proto2 enum behind default");
        rejects(unknown,"unknown enum despite getter default");
        rejects(append(valid().setLayout(MatrixData.Layout.COLUMN_MAJOR).build(),4,99,false),"known layout plus unknown duplicate");
        rejects(append(implicit,4,-1,false),"negative enum");rejects(append(implicit,4,0,true),"wrong wire type for layout");
        MatrixData extension=append(implicit,100,7,false);FacePoseMatrix.copyValidated(extension);checks++;
        var huge=new ByteArrayOutputStream();huge.write(implicit.toByteArray());var out=CodedOutputStream.newInstance(huge);out.writeByteArray(100,new byte[4097]);out.flush();
        rejects(MatrixData.parseFrom(huge.toByteArray()),"bounded metadata scan");
        var nested=new ByteArrayOutputStream();nested.write(implicit.toByteArray());out=CodedOutputStream.newInstance(nested);
        for(int i=0;i<20;i++)out.writeTag(100,3);for(int i=0;i<20;i++)out.writeTag(100,4);out.flush();
        rejects(MatrixData.parseFrom(nested.toByteArray()),"bounded unknown group nesting");
        System.out.println("FacePoseMatrixTest: "+checks+" checks passed");
    }
    private static MatrixData.Builder valid(){var b=MatrixData.newBuilder().setRows(4).setCols(4);for(float v:new float[]{1,0,-0f,0,0,1,0,0,0,0,1,0,2,3,4,1})b.addPackedData(v);return b;}
    private static MatrixData append(MatrixData matrix,int field,int value,boolean bytes) throws Exception {var data=new ByteArrayOutputStream();data.write(matrix.toByteArray());var out=CodedOutputStream.newInstance(data);if(bytes)out.writeByteArray(field,new byte[]{(byte)value});else out.writeEnum(field,value);out.flush();return MatrixData.parseFrom(data.toByteArray());}
    private static void rejects(MatrixData m,String why){try{FacePoseMatrix.copyValidated(m);throw new AssertionError("Accepted "+why);}catch(IllegalArgumentException expected){checks++;}}
    private static void check(boolean okay,String why){checks++;if(!okay)throw new AssertionError(why);}
}
