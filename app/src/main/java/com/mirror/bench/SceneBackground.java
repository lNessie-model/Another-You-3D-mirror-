package com.mirror.bench;

/** Screen-plane background composited once during interlacing; no background mesh or per-view pass. */
final class SceneBackground {
    private SceneBackground(){}
    static String fragment(String original){
        String main="void main(){color=vec4(texture(uViews,vec3(vUv,viewAt(0.0))).r,texture(uViews,vec3(vUv,viewAt(1.0))).g,texture(uViews,vec3(vUv,viewAt(2.0))).b,1);}";
        if(!original.contains(main))throw new IllegalArgumentException("Unexpected interlace source");
        return original.replace("out vec4 color;","out vec4 color;\nuniform int uBackground;\nuniform sampler2D uBackgroundImage;").replace(main,FUNCTIONS+"""
            void main(){
                vec4 r=textureLod(uViews,vec3(vUv,viewAt(0.0)),0.0);
                vec4 g=textureLod(uViews,vec3(vUv,viewAt(1.0)),0.0);
                vec4 b=textureLod(uViews,vec3(vUv,viewAt(2.0)),0.0);
                vec3 bg=backgroundAt(vUv);
                // Transparent-black clears make interpolated model samples premultiplied by coverage.
                color=vec4(r.r+(1.0-r.a)*bg.r,g.g+(1.0-g.a)*bg.g,b.b+(1.0-b.a)*bg.b,1.0);
            }
            """);
    }
    private static final String FUNCTIONS="""
        vec3 backgroundAt(vec2 uv){
            // Android bitmap row zero is the top; screen UV zero is the bottom.
            if(uBackground>=8)return texture(uBackgroundImage,vec2(uv.x,1.0-uv.y)).rgb;
            if(uBackground==0)return vec3(0.0);
            if(uBackground==1)return vec3(0.025);
            if(uBackground==2)return vec3(0.012,0.018,0.030);
            if(uBackground==3)return vec3(0.032,0.009,0.015);
            if(uBackground==4){
                vec2 grid=abs(fract(uv*vec2(10.0,16.0)-0.5)-0.5);
                float line=1.0-smoothstep(0.005,0.018,min(grid.x,grid.y));
                return vec3(0.012,0.016,0.022)+line*vec3(0.040,0.050,0.060);
            }
            if(uBackground==5){
                vec2 d=abs(uv-0.5)/vec2(0.43,0.43);
                float edge=1.0-smoothstep(0.002,0.012,abs(max(d.x,d.y)-1.0));
                return vec3(0.008,0.012,0.018)+edge*vec3(0.10,0.13,0.16);
            }
            if(uBackground==6){
                vec2 p=(uv-vec2(0.5,0.35))*vec2(1.6,1.0);
                float glow=exp(-dot(p,p)*5.0);
                return vec3(0.006,0.010,0.018)+glow*vec3(0.018,0.028,0.040);
            }
            vec2 cell=floor(uv*vec2(25.0,40.0)),p=fract(uv*vec2(25.0,40.0))-0.5;
            float seed=fract(cell.x*0.1031+cell.y*0.11369);
            seed=fract(seed*(seed+33.33)*(seed+1.0));
            float star=step(0.985,seed)*(1.0-smoothstep(0.012,0.050,length(p)));
            return vec3(0.006,0.009,0.018)+star*vec3(0.16,0.18,0.22);
        }
        """;
}
