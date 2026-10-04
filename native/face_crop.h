#ifndef MIRROR_FACE_CROP_H
#define MIRROR_FACE_CROP_H

#include <math.h>
#include <stdint.h>

/* Keep this scalar oracle's arithmetic identical to the originally validated JNI crop. */
static void mirror_crop_reference(const uint8_t *pixels,int width,int height,int stride,float *out,
        int size,float cx,float cy,float side,float rotation,float mean,float std) {
    float cs=cosf(rotation),sn=sinf(rotation);
    for(int y=0;y<size;y++) for(int x=0;x<size;x++) {
        float u=(x/(float)size-.5f)*side,v=(y/(float)size-.5f)*side;
        float px=cx+cs*u-sn*v,py=cy+sn*u+cs*v;
        int ix=(int)floorf(px),iy=(int)floorf(py); float dx=px-ix,dy=py-iy;
        float rgb[3]={0};
        for(int j=0;j<2;j++) for(int k=0;k<2;k++) {
            int sx=ix+k,sy=iy+j;
            if(sx<0||sy<0||sx>=width||sy>=height) continue;
            const uint8_t *p=pixels+sy*stride+sx*4;
            float w=(k?dx:1-dx)*(j?dy:1-dy);
            for(int ch=0;ch<3;ch++) rgb[ch]+=p[ch]*w;
        }
        for(int ch=0;ch<3;ch++) *out++=(roundf(rgb[ch])-mean)/std;
    }
}

/* Same coordinates, weights, accumulation order and rounding; only remove repeated work. */
static void mirror_crop_optimized(const uint8_t *pixels,int width,int height,int stride,float *out,
        int size,float cx,float cy,float side,float rotation,float mean,float std) {
    float cs=cosf(rotation),sn=sinf(rotation);
    float horizontal[1024]; /* JNI limits size to 1024; no per-frame allocation. */
    for(int x=0;x<size;x++) horizontal[x]=(x/(float)size-.5f)*side;
    for(int y=0;y<size;y++) {
        float v=(y/(float)size-.5f)*side;
        for(int x=0;x<size;x++) {
            float u=horizontal[x];
            // Do not hoist sn*v/cs*v or replace these expressions by coordinate increments:
            // that can change compiler FMA contraction and cross a roundf half-value boundary.
            float px=cx+cs*u-sn*v,py=cy+sn*u+cs*v;
            int ix=(int)floorf(px),iy=(int)floorf(py); float dx=px-ix,dy=py-iy;
            float rgb[3]={0};
            if(ix>=0&&iy>=0&&ix<width-1&&iy<height-1) {
                const uint8_t *p00=pixels+iy*stride+ix*4,*p10=p00+4,*p01=p00+stride,*p11=p01+4;
                float w00=(1-dx)*(1-dy),w10=dx*(1-dy),w01=(1-dx)*dy,w11=dx*dy;
                for(int ch=0;ch<3;ch++) {
                    rgb[ch]+=p00[ch]*w00;
                    rgb[ch]+=p10[ch]*w10;
                    rgb[ch]+=p01[ch]*w01;
                    rgb[ch]+=p11[ch]*w11;
                }
            } else {
                // Exact original zero-border handling for partial and fully out-of-image crops.
                for(int j=0;j<2;j++) for(int k=0;k<2;k++) {
                    int sx=ix+k,sy=iy+j;
                    if(sx<0||sy<0||sx>=width||sy>=height) continue;
                    const uint8_t *p=pixels+sy*stride+sx*4;
                    float w=(k?dx:1-dx)*(j?dy:1-dy);
                    for(int ch=0;ch<3;ch++) rgb[ch]+=p[ch]*w;
                }
            }
            for(int ch=0;ch<3;ch++) *out++=(roundf(rgb[ch])-mean)/std;
        }
    }
}
#endif
