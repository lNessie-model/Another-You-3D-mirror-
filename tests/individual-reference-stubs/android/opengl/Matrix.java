package android.opengl;

/** Arithmetic host fixture for the fixed lookAt/frustum/multiply use. Not an Android implementation claim. */
public final class Matrix {
    /** Independent host arithmetic sufficient for routing tests, not Android bit-equivalence. */
    public static boolean invertM(float[] out,int offset,float[] input,int start){
        double[][] m=new double[4][8];for(int r=0;r<4;r++)for(int c=0;c<4;c++){m[r][c]=input[start+c*4+r];m[r][4+c]=r==c?1:0;}
        for(int c=0;c<4;c++){
            int pivot=c;for(int r=c+1;r<4;r++)if(Math.abs(m[r][c])>Math.abs(m[pivot][c]))pivot=r;
            if(m[pivot][c]==0)return false;double[] swap=m[c];m[c]=m[pivot];m[pivot]=swap;
            double d=m[c][c];for(int k=0;k<8;k++)m[c][k]/=d;
            for(int r=0;r<4;r++)if(r!=c){double f=m[r][c];for(int k=0;k<8;k++)m[r][k]-=f*m[c][k];}
        }
        for(int r=0;r<4;r++)for(int c=0;c<4;c++)out[offset+c*4+r]=(float)m[r][4+c];return true;
    }
    private Matrix(){}
    public static void setLookAtM(float[] out,int offset,float ex,float ey,float ez,float cx,float cy,float cz,float ux,float uy,float uz){
        if(ey!=0||ez!=3||cx!=ex||cy!=0||cz!=0||ux!=0||uy!=1||uz!=0)throw new AssertionError("Unexpected camera fixture");
        for(int i=0;i<16;i++)out[offset+i]=0;
        out[offset]=out[offset+5]=out[offset+10]=out[offset+15]=1;
        out[offset+12]=-ex;out[offset+13]=-ey;out[offset+14]=-ez;
    }
    public static void frustumM(float[] out,int offset,float left,float right,float bottom,float top,float near,float far){
        for(int i=0;i<16;i++)out[offset+i]=0;
        float rw=1/(right-left),rh=1/(top-bottom),rd=1/(near-far);
        out[offset]=2*(near*rw);out[offset+5]=2*(near*rh);
        out[offset+8]=(right+left)*rw;out[offset+9]=(top+bottom)*rh;
        out[offset+10]=(far+near)*rd;out[offset+11]=-1;out[offset+14]=2*(far*near*rd);
    }
    public static void multiplyMM(float[] out,int offset,float[] left,int lo,float[] right,int ro){
        for(int c=0;c<4;c++)for(int r=0;r<4;r++)out[offset+c*4+r]=left[lo+r]*right[ro+c*4]
                +left[lo+4+r]*right[ro+c*4+1]+left[lo+8+r]*right[ro+c*4+2]+left[lo+12+r]*right[ro+c*4+3];
    }
}
