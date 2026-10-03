package com.mirror.bench;

import com.google.mediapipe.formats.proto.MatrixDataProto.MatrixData;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import java.io.IOException;

/** Validate actual MatrixData metadata before discarding it for the renderer's column-major array. */
final class FacePoseMatrix {
    private static final int MAX_METADATA_BYTES=4096;
    private FacePoseMatrix(){}
    static float[] copyValidated(MatrixData matrix){
        if(matrix==null||matrix.getRows()!=4||matrix.getCols()!=4||matrix.getPackedDataCount()!=16)
            throw new IllegalArgumentException("Expected one 4x4 face pose matrix with 16 values");
        if(matrix.getLayout()!=MatrixData.Layout.COLUMN_MAJOR)
            throw new IllegalArgumentException("Face pose matrix must use COLUMN_MAJOR layout");
        // proto2's unknown enum values survive in unknown fields, while getLayout() returns its
        // COLUMN_MAJOR default. Check only that known field, with protobuf's own bounded decoder.
        if(matrix.getSerializedSize()>MAX_METADATA_BYTES)throw new IllegalArgumentException("Face pose metadata exceeds bounded size");
        CodedInputStream input=CodedInputStream.newInstance(matrix.toByteArray());
        input.setSizeLimit(MAX_METADATA_BYTES);input.setRecursionLimit(16);
        try {
            for(int tag;(tag=input.readTag())!=0;){
                int wire=WireFormat.getTagWireType(tag);
                // MatrixData defines no groups. This protobuf version's skipField(group) does not
                // enforce recursionLimit, so never recurse through unknown group structures.
                if(wire==WireFormat.WIRETYPE_START_GROUP||wire==WireFormat.WIRETYPE_END_GROUP)
                    throw new IllegalArgumentException("Groups are not valid face pose metadata");
                if(WireFormat.getTagFieldNumber(tag)==MatrixData.LAYOUT_FIELD_NUMBER){
                    if(wire!=WireFormat.WIRETYPE_VARINT||input.readUInt64()!=MatrixData.Layout.COLUMN_MAJOR_VALUE)
                        throw new IllegalArgumentException("Face pose has unknown or malformed layout metadata");
                } else if(!input.skipField(tag))throw new IllegalArgumentException("Unexpected face pose metadata end group");
            }
        } catch(IOException invalid){throw new IllegalArgumentException("Cannot validate bounded face pose metadata",invalid);}
        float[] copy=new float[16];
        for(int i=0;i<16;i++){
            copy[i]=matrix.getPackedData(i);
            if(!Float.isFinite(copy[i]))throw new IllegalArgumentException("Face pose matrix values must be finite");
        }
        return copy;
    }
}
