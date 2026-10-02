#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "face_crop.h"

static uint32_t random_state=0x4d495252u;
static uint32_t next_random(void) {
    random_state^=random_state<<13; random_state^=random_state>>17; random_state^=random_state<<5;
    return random_state;
}
int main(void) {
    int cases=0; size_t compared=0;
    for(int image=0;image<3;image++) {
        int width=image==0?37:640,height=image==0?29:480,stride=width*4+12;
        uint8_t *pixels=malloc((size_t)stride*height);
        float *reference=malloc(256*256*3*sizeof(float)),*optimized=malloc(256*256*3*sizeof(float));
        if(!pixels||!reference||!optimized) return 2;
        for(int y=0;y<height;y++) for(int x=0;x<stride;x++)
            pixels[y*stride+x]=image==2?(uint8_t)(((x/4+y)%2)?1:0):(uint8_t)next_random();
        for(int size=128;size<=256;size*=2) for(int normalization=0;normalization<2;normalization++) {
            float mean=normalization?127.5f:0.f,std=normalization?127.5f:255.f;
            for(int sample=0;sample<80;sample++) {
                float cx,cy,side,rotation;
                if(sample<4) {
                    cx=size*.5f+.5f; cy=size*.5f+.5f; side=(float)size; rotation=0;
                    if(sample==1) cx=nextafterf(cx,INFINITY);
                    if(sample==2) cy=nextafterf(cy,-INFINITY);
                    if(sample==3) { cx=-.5f; cy=-.5f; }
                } else {
                    cx=((int)(next_random()%30001)-10000)*width/10000.f;
                    cy=((int)(next_random()%30001)-10000)*height/10000.f;
                    side=1.f+(next_random()%20001)*width/10000.f;
                    rotation=((int)(next_random()%62833)-31416)/10000.f;
                }
                mirror_crop_reference(pixels,width,height,stride,reference,size,cx,cy,side,rotation,mean,std);
                mirror_crop_optimized(pixels,width,height,stride,optimized,size,cx,cy,side,rotation,mean,std);
                size_t bytes=(size_t)size*size*3*sizeof(float);
                if(memcmp(reference,optimized,bytes)) {
                    for(int i=0;i<size*size*3;i++) if(memcmp(reference+i,optimized+i,sizeof(float))) {
                        fprintf(stderr,"Mismatch image=%d size=%d normalization=%d sample=%d element=%d: %.9g != %.9g\n",
                                image,size,normalization,sample,i,reference[i],optimized[i]); break;
                    }
                    return 1;
                }
                cases++; compared+=bytes;
            }
        }
        free(pixels); free(reference); free(optimized);
    }
    printf("{\"passed\":true,\"cases\":%d,\"compared_bytes\":%zu,\"mismatched_bytes\":0}\n",cases,compared);
    return 0;
}
