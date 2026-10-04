package com.mirror.bench;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * CPU-only GLB 2.0 profile v1a. Morph triangles, rigid nodes and opaque factor-only materials.
 * No skin, animation, cameras, external URI or unknown extensions. An explicit mirrorAlbedoAtlas=1
 * root profile additionally permits one bounded embedded RGB8 PNG and TEXCOORD_0 base colour.
 * This is an importer, not a renderer or a claim that an asset is a complete facial rig.
 * Format: https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html
 */
public final class AvatarGlbLoader {
    public static final int MAX_FILE_BYTES=32*1024*1024, MAX_JSON_BYTES=1024*1024;
    public static final long MAX_DECODED_BYTES=80L*1024*1024;
    private static final long LEGACY_DECODED_BYTES=32L*1024*1024;
    private static final int MAX_VERTICES=20_000, MAX_TRIANGLES=30_000, MAX_PRIMITIVES=8;
    private static final String UNLIT="KHR_materials_unlit";

    public static final class FormatException extends IOException {
        FormatException(String message){super(message);}
        FormatException(String message,Throwable cause){super(message,cause);}
    }
    private AvatarGlbLoader(){}
    /** Does not close the caller-owned stream. Input and JSON sizes are bounded before decoding. */
    public static AvatarAsset load(InputStream stream)throws IOException {
        if(stream==null)throw new FormatException("input: null stream");
        byte[] header=new byte[12];readFully(stream,header,0,header.length);
        ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        require(h.getInt()==0x46546c67,"header: expected glTF magic");require(h.getInt()==2,"version: only GLB 2 supported");
        long length=uint(h.getInt());require(length>=28&&length<=MAX_FILE_BYTES,"header/file length budget");
        byte[] file=new byte[(int)length];System.arraycopy(header,0,file,0,header.length);
        readFully(stream,file,header.length,file.length-header.length);
        require(stream.read()==-1,"header length: trailing stream bytes");return load(file);
    }
    private static void readFully(InputStream input,byte[] data,int start,int count)throws IOException {
        int end=start+count;while(start<end){int n=input.read(data,start,end-start);if(n<0)throw new FormatException("header/chunk: truncated stream");
            if(n==0){int one=input.read();if(one<0)throw new FormatException("header/chunk: truncated stream");data[start++]=(byte)one;}else start+=n;}
    }
    public static AvatarAsset load(byte[] bytes)throws FormatException {return load(bytes,MAX_DECODED_BYTES);}
    /** Lowering this limit is useful for smaller runtime profiles and deterministic budget tests. */
    public static AvatarAsset load(byte[] bytes,long decodedBudget)throws FormatException {
        require(decodedBudget>0&&decodedBudget<=MAX_DECODED_BYTES,"decoded budget outside profile");
        require(bytes!=null&&bytes.length>=28&&bytes.length<=MAX_FILE_BYTES,"header/file length budget");
        ByteBuffer file=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        require(file.getInt()==0x46546c67,"header: expected glTF magic");
        require(file.getInt()==2,"version: only GLB 2 supported");
        require(uint(file.getInt())==bytes.length,"header length mismatch");
        int jsonLength=chunkLength(file,bytes.length,"JSON");
        require(file.getInt()==0x4e4f534a,"chunk: JSON must be first");
        require(jsonLength>0&&jsonLength<=MAX_JSON_BYTES,"JSON budget exceeded");
        String text;
        try {
            text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,file.position(),jsonLength)).toString();
        } catch(CharacterCodingException e){throw new FormatException("JSON: invalid UTF-8",e);}
        file.position(file.position()+jsonLength);
        require(file.remaining()>=8,"chunk: embedded BIN required");
        int binLength=chunkLength(file,bytes.length,"BIN");
        require(file.getInt()==0x004e4942,"chunk: expected BIN");
        int binStart=file.position();require((long)binStart+binLength==bytes.length,"chunk: extra or truncated chunks unsupported");
        try {
            new JsonSyntax(text).validate();
            JSONObject root=new JSONObject(text);
            return new Parser(root,ByteBuffer.wrap(bytes,binStart,binLength).slice().order(ByteOrder.LITTLE_ENDIAN),decodedBudget).parse();
        } catch(JSONException e){throw new FormatException("JSON schema: "+e.getMessage(),e);}
    }
    private static int chunkLength(ByteBuffer b,int total,String label)throws FormatException {
        require(b.remaining()>=8,"chunk: truncated "+label+" header");long n=uint(b.getInt());
        require((n&3)==0&&n<=total-b.position()-4,"chunk: invalid "+label+" length");return(int)n;
    }

    private static final class Parser {
        final JSONObject root;
        final ByteBuffer bin;
        long budget;
        long charged;
        JSONArray views,accessors;
        final Map<Integer,float[]> decoded=new HashMap<>();
        final List<AvatarAsset.Mesh> meshes=new ArrayList<>();
        final List<AvatarAsset.Material> materials=new ArrayList<>();
        int primitiveCount,geometryVertices,geometryTriangles;
        boolean atlasProfile;
        boolean pbrProfile;
        AvatarAsset.AlbedoAtlas atlas,normalMap,ormMap;
        Parser(JSONObject root,ByteBuffer bin,long budget){this.root=root;this.bin=bin;this.budget=budget;}
        AvatarAsset parse()throws JSONException,FormatException {
            JSONObject asset=obj(root,"asset");require("2.0".equals(string(asset,"version","")),"asset.version: only 2.0 supported");
            require(!asset.has("minVersion")||"2.0".equals(string(asset,"minVersion","")),"asset.minVersion unsupported");
            if(root.has("extras")){
                JSONObject extras=obj(root,"extras");
                if(extras.has("mirrorAlbedoAtlas")){
                    intValue(extras.get("mirrorAlbedoAtlas"),1,1,"albedo profile");atlasProfile=true;
                }
                if(extras.has("mirrorPbrAtlas")){
                    intValue(extras.get("mirrorPbrAtlas"),1,1,"PBR profile");pbrProfile=true;
                    require(atlasProfile,"PBR profile requires albedo profile");
                }
            }
            if(!pbrProfile)budget=Math.min(budget,LEGACY_DECODED_BYTES);
            for(String key:new String[]{"skins","animations","cameras"})
                require(!root.has(key),key+": unsupported in factor-only morph profile");
            require(!root.has("samplers"),"samplers: unsupported; atlas uses fixed linear clamp sampling");
            if(!atlasProfile)for(String key:new String[]{"textures","images"})
                require(!root.has(key),key+": unsupported in factor-only morph profile");
            extensions(root,"root",0);
            for(String key:new String[]{"extensionsUsed","extensionsRequired"})
                for(Object value:values(array(root,key,false,32)))require(UNLIT.equals(value),key+": unsupported extension "+value);
            JSONArray buffers=array(root,"buffers",true,1);require(buffers.length()==1,"buffers: exactly one embedded buffer required");
            JSONObject buffer=buffers.getJSONObject(0);require(!buffer.has("uri"),"buffers[0].URI: external/data URI unsupported");
            int logical=integer(buffer,"byteLength",1,MAX_FILE_BYTES,-1);
            require(bin.capacity()>=logical&&bin.capacity()-logical<=3,"BIN/buffer byteLength or padding mismatch");
            for(int i=logical;i<bin.capacity();i++)require(bin.get(i)==0,"BIN padding must be zero");
            bin.limit(logical);
            views=array(root,"bufferViews",false,1024);accessors=array(root,"accessors",true,1024);
            for(int i=0;i<views.length();i++)view(i);
            for(int i=0;i<accessors.length();i++)accessor(i);
            if(atlasProfile){atlas=embeddedMap(0);if(pbrProfile){normalMap=embeddedMap(1);ormMap=embeddedMap(2);}}
            JSONArray mats=array(root,"materials",false,8);
            for(int i=0;i<mats.length();i++)materials.add(material(mats.getJSONObject(i),"materials["+i+"]"));
            // A missing primitive material uses glTF defaults; its metallic factor is unsupported here.
            JSONArray sourceMeshes=array(root,"meshes",true,8);require(sourceMeshes.length()>0,"meshes: empty");
            for(int i=0;i<sourceMeshes.length();i++)meshes.add(mesh(sourceMeshes.getJSONObject(i),i));
            return nodes();
        }
        void reserve(long bytes,String path)throws FormatException {
            require(bytes>=0&&bytes<=budget-charged,path+": decoded memory budget exceeded");charged+=bytes;
        }
        AvatarAsset.AlbedoAtlas embeddedMap(int index)throws JSONException,FormatException {
            JSONArray images=array(root,"images",true,pbrProfile?3:2),textures=array(root,"textures",true,pbrProfile?3:2);
            int count=pbrProfile?3:1;
            require(images.length()==count&&textures.length()==count,pbrProfile?"PBR atlas: exactly three images and textures required":"atlas: exactly one image and texture required");
            JSONObject image=images.getJSONObject(index),texture=textures.getJSONObject(index);
            require(!image.has("uri"),"image URI unsupported");
            onlyKeys(image,Set.of("name","bufferView","mimeType"),"atlas image");
            onlyKeys(texture,Set.of("name","source"),"atlas texture");
            intValue(texture.get("source"),index,index,"atlas texture source");
            require("image/png".equals(string(image,"mimeType","")),"atlas: PNG only");
            JSONObject v=view(integer(image,"bufferView",0,views.length()-1,-1));
            require(!v.has("byteStride")&&!v.has("target"),"atlas image bufferView must be packed");
            int length=integer(v,"byteLength",1,4*1024*1024,-1),offset=integer(v,"byteOffset",0,Integer.MAX_VALUE,0);
            reserve(length,"atlas PNG");byte[] data=new byte[length];ByteBuffer encoded=bin.duplicate();encoded.position(offset);encoded.get(data);
            int[] size=validatePng(data);reserve(4L*size[0]*size[1],"atlas RGBA decode");
            return new AvatarAsset.AlbedoAtlas(data,size[0],size[1]);
        }
        JSONObject view(int index)throws JSONException,FormatException {
            require(index>=0&&index<views.length(),"bufferView index out of range");JSONObject v=views.getJSONObject(index);
            require(integer(v,"buffer",0,0,-1)==0,"bufferView: embedded buffer only");
            long off=integer(v,"byteOffset",0,Integer.MAX_VALUE,0),len=integer(v,"byteLength",1,Integer.MAX_VALUE,-1);
            require(off+len<=bin.limit(),"bufferView range outside BIN");
            if(v.has("byteStride")){int stride=integer(v,"byteStride",4,252,-1);require(stride%4==0,"bufferView stride must be multiple of four");}
            if(v.has("target")){int t=integer(v,"target",0,65535,-1);require(t==34962||t==34963,"bufferView target unsupported");}
            return v;
        }
        JSONObject accessor(int index)throws JSONException,FormatException {
            require(index>=0&&index<accessors.length(),"accessor index out of range");JSONObject a=accessors.getJSONObject(index);
            int count=integer(a,"count",1,MAX_TRIANGLES*3,-1),size=components(a),component=componentSize(a);
            long off=integer(a,"byteOffset",0,Integer.MAX_VALUE,0);
            if(a.has("bufferView"))range(view(integer(a,"bufferView",0,views.length()-1,-1)),off,count,size*component,component,false);
            else require(off==0,"accessor range: offset without bufferView");
            if(a.has("normalized"))bool(a,"normalized",false);
            return a;
        }
        int components(JSONObject a)throws JSONException,FormatException {
            String type=string(a,"type","");
            int n=switch(type){case "SCALAR"->1;case "VEC2"->2;case "VEC3"->3;case "VEC4"->4;default->0;};
            require(n>0,"accessor type unsupported: "+type);return n;
        }
        int componentSize(JSONObject a)throws JSONException,FormatException {
            int type=integer(a,"componentType",0,65535,-1);
            int size=switch(type){case 5121->1;case 5123->2;case 5125,5126->4;default->0;};
            require(size>0,"accessor componentType unsupported: "+type);return size;
        }
        int range(JSONObject v,long off,int count,int element,int alignment,boolean packed)throws JSONException,FormatException {
            int base=integer(v,"byteOffset",0,Integer.MAX_VALUE,0),length=integer(v,"byteLength",1,Integer.MAX_VALUE,-1);
            int stride=integer(v,"byteStride",4,252,element);
            if(packed)require(!v.has("byteStride")&&!v.has("target"),"sparse bufferView: stride/target forbidden");
            require(stride>=element,"accessor stride smaller than element");
            require(off%alignment==0&&(base+off)%alignment==0,"accessor range alignment");
            require(off+(long)(count-1)*stride+element<=length,"accessor range outside bufferView");
            return base+(int)off;
        }
        float[] floats(int index,int count,int width,String path,boolean color)throws JSONException,FormatException {
            JSONObject a=accessor(index);require(integer(a,"count",1,MAX_TRIANGLES*3,-1)==count,path+": accessor count mismatch");
            require(components(a)==width,path+": accessor vector type mismatch");
            int type=integer(a,"componentType",0,65535,-1);boolean norm=bool(a,"normalized",false);
            require((type==5126&&!norm)||(color&&(type==5121||type==5123)&&norm),path+": float or normalized color component required");
            float[] cached=decoded.get(index);if(cached!=null)return cached;
            reserve((long)count*width*4,path);float[] result=new float[count*width];int size=componentSize(a);
            if(a.has("bufferView")){
                JSONObject v=view(integer(a,"bufferView",0,views.length()-1,-1));int stride=integer(v,"byteStride",4,252,width*size);
                int start=range(v,integer(a,"byteOffset",0,Integer.MAX_VALUE,0),count,width*size,size,false);
                require(start%4==0&&stride%4==0,path+": vertex attribute alignment must be four bytes");
                for(int i=0;i<count;i++)for(int c=0;c<width;c++)result[i*width+c]=readFloat(start+i*stride+c*size,type,norm,path);
            }
            if(a.has("sparse")){
                JSONObject s=obj(a,"sparse");int n=integer(s,"count",1,count,-1);
                JSONObject ix=obj(s,"indices"),va=obj(s,"values");
                int it=integer(ix,"componentType",0,65535,-1);require(it==5121||it==5123||it==5125,path+": sparse index type");
                int is=it==5121?1:it==5123?2:4;
                int ip=range(view(integer(ix,"bufferView",0,views.length()-1,-1)),integer(ix,"byteOffset",0,Integer.MAX_VALUE,0),n,is,is,true);
                int vp=range(view(integer(va,"bufferView",0,views.length()-1,-1)),integer(va,"byteOffset",0,Integer.MAX_VALUE,0),n,width*size,size,true);
                long previous=-1;
                for(int i=0;i<n;i++){
                    long dst=readUnsigned(ip+i*is,it);require(dst>previous&&dst<count,path+": sparse index order/range");previous=dst;
                    for(int c=0;c<width;c++)result[(int)dst*width+c]=readFloat(vp+(i*width+c)*size,type,norm,path);
                }
            }
            decoded.put(index,result);return result;
        }
        float readFloat(int offset,int type,boolean normalized,String path)throws FormatException {
            float value=type==5126?bin.getFloat(offset):(float)readUnsigned(offset,type);
            if(normalized)value/=type==5121?255f:65535f;
            require(Float.isFinite(value),path+": non-finite data");return value;
        }
        long readUnsigned(int offset,int type){return type==5121?Byte.toUnsignedInt(bin.get(offset)):type==5123?Short.toUnsignedInt(bin.getShort(offset)):uint(bin.getInt(offset));}
        int[] indices(JSONObject primitive,int vertices,String path)throws JSONException,FormatException {
            if(!primitive.has("indices")){
                require(vertices%3==0,path+": indexless TRIANGLES count");reserve(vertices*4L,path);
                int[] result=new int[vertices];for(int i=0;i<vertices;i++)result[i]=i;return result;
            }
            JSONObject a=accessor(integer(primitive,"indices",0,accessors.length()-1,-1));
            int count=integer(a,"count",1,MAX_TRIANGLES*3,-1),type=integer(a,"componentType",0,65535,-1);
            require(components(a)==1&&type!=5126&&!bool(a,"normalized",false),path+": invalid index type");
            require(count%3==0,path+": TRIANGLES index count");
            require(!a.has("sparse"),path+": sparse indices unsupported");
            require(a.has("bufferView"),path+": index bufferView required");
            JSONObject v=view(integer(a,"bufferView",0,views.length()-1,-1));require(!v.has("byteStride"),path+": index stride forbidden");
            int size=componentSize(a),start=range(v,integer(a,"byteOffset",0,Integer.MAX_VALUE,0),count,size,size,false);
            reserve(count*4L,path);int[] result=new int[count];
            long restart=type==5121?255:type==5123?65535:0xffffffffL;
            for(int i=0;i<count;i++){long value=readUnsigned(start+i*size,type);require(value<vertices&&value!=restart,path+": index outside vertex range or reserved restart value");result[i]=(int)value;}
            return result;
        }
        AvatarAsset.Mesh mesh(JSONObject source,int number)throws JSONException,FormatException {
            String path="meshes["+number+"]";JSONArray prims=array(source,"primitives",true,8);require(prims.length()>0,path+": no primitives");
            List<AvatarAsset.Primitive> output=new ArrayList<>();int targetCount=-1;
            for(int i=0;i<prims.length();i++){
                String pp=path+".primitives["+i+"]";JSONObject pr=prims.getJSONObject(i),attrs=obj(pr,"attributes");
                require(++primitiveCount<=MAX_PRIMITIVES,"primitive budget exceeded");
                require(integer(pr,"mode",0,6,4)==4,pp+": only TRIANGLES supported");
                onlyKeys(attrs,Set.of("POSITION","NORMAL","TEXCOORD_0","COLOR_0"),pp+".attributes");
                int pos=integer(attrs,"POSITION",0,accessors.length()-1,-1);
                int count=integer(accessor(pos),"count",1,MAX_VERTICES,-1);
                geometryVertices+=count;require(geometryVertices<=MAX_VERTICES,"vertex budget exceeded");
                float[] positions=floats(pos,count,3,pp+".POSITION",false);
                float[] normals=optionalFloats(attrs,"NORMAL",count,3,pp);
                float[] uv=optionalFloats(attrs,"TEXCOORD_0",count,2,pp),colors=null;
                if(attrs.has("COLOR_0")){
                    int ci=integer(attrs,"COLOR_0",0,accessors.length()-1,-1),cw=components(accessor(ci));require(cw==3||cw==4,pp+": COLOR_0 type");
                    float[] input=floats(ci,count,cw,pp+".COLOR_0",true);reserve(count*16L,pp);colors=new float[count*4];
                    for(int v=0;v<count;v++)for(int c=0;c<4;c++){float value=c<cw?input[v*cw+c]:1;require(value>=0&&value<=1,pp+": COLOR_0 range");colors[v*4+c]=value;}
                }
                int[] ids=indices(pr,count,pp);geometryTriangles+=ids.length/3;require(geometryTriangles<=MAX_TRIANGLES,"triangle budget exceeded");
                JSONArray targets=array(pr,"targets",false,64);
                require(targetCount<0||targetCount==targets.length(),path+": targets count differs between primitives");targetCount=targets.length();
                List<AvatarAsset.Morph> morphs=new ArrayList<>();
                for(int j=0;j<targets.length();j++){
                    JSONObject t=targets.getJSONObject(j);onlyKeys(t,Set.of("POSITION","NORMAL"),pp+".targets");
                    require(t.length()>0,pp+": empty morph target");
                    require(!t.has("NORMAL")||normals!=null,pp+": morph NORMAL requires base NORMAL");
                    morphs.add(new AvatarAsset.Morph(optionalFloats(t,"POSITION",count,3,pp+".morph"),optionalFloats(t,"NORMAL",count,3,pp+".morph")));
                }
                int material=integer(pr,"material",0,materials.size()-1,-1);
                require(material>=0,pp+": explicit supported material required (default metallic material unsupported)");
                require(!materials.get(material).textured()||uv!=null,pp+": textured material requires UV TEXCOORD_0");
                output.add(new AvatarAsset.Primitive(positions,normals,uv,colors,ids,morphs,material));
            }
            List<String> names=new ArrayList<>();
            if(source.has("extras")&&source.opt("extras") instanceof JSONObject extra&&extra.has("targetNames")){
                JSONArray named=array(extra,"targetNames",true,64);require(named.length()==targetCount,path+": targetNames length mismatch");
                Set<String> seen=new HashSet<>();for(Object value:values(named)){require(value instanceof String&&!((String)value).trim().isEmpty(),path+": targetNames invalid name");String name=(String)value;require(seen.add(name),path+": duplicate targetNames "+name);names.add(name);}
            }
            float[] weights=vector(source,"weights",targetCount,new float[targetCount],path);reserve(weights.length*4L,path);
            return new AvatarAsset.Mesh(string(source,"name",""),output,names,weights);
        }
        float[] optionalFloats(JSONObject attrs,String name,int count,int width,String path)throws JSONException,FormatException {
            return attrs.has(name)?floats(integer(attrs,name,0,accessors.length()-1,-1),count,width,path+"."+name,false):null;
        }
        AvatarAsset.Material material(JSONObject m,String path)throws JSONException,FormatException {
            require(!m.has("emissiveTexture"),path+": texture unsupported: emissiveTexture");
            require("OPAQUE".equals(string(m,"alphaMode","OPAQUE")),path+": alpha mode unsupported");
            require(!bool(m,"doubleSided",false),path+": doubleSided unsupported");
            float[] emissive=vector(m,"emissiveFactor",3,new float[3],path);
            require(emissive[0]==0&&emissive[1]==0&&emissive[2]==0,path+": emissive factor unsupported");
            boolean unlit=m.has("extensions")&&obj(m,"extensions").has(UNLIT);
            JSONObject p=m.has("pbrMetallicRoughness")?obj(m,"pbrMetallicRoughness"):new JSONObject();
            boolean maps=m.has("normalTexture")||p.has("metallicRoughnessTexture")||m.has("occlusionTexture");
            float normalScale=1,occlusionStrength=0;
            if(maps){
                require(pbrProfile,path+": texture unsupported");
                require(!unlit,path+": PBR maps unsupported on unlit material");
                require(m.has("normalTexture")&&p.has("metallicRoughnessTexture"),path+": paired normal and ORM maps required");
                JSONObject nt=obj(m,"normalTexture"),rt=obj(p,"metallicRoughnessTexture");
                mapIndex(nt,1,Set.of("index","texCoord","scale"),path+".normalTexture");
                mapIndex(rt,2,Set.of("index","texCoord"),path+".metallicRoughnessTexture");
                normalScale=number(nt,"scale",1);require(normalScale>=0&&normalScale<=2,path+": normal scale range");
                if(m.has("occlusionTexture")){
                    JSONObject ot=obj(m,"occlusionTexture");mapIndex(ot,2,Set.of("index","texCoord","strength"),path+".occlusionTexture");
                    occlusionStrength=number(ot,"strength",1);require(occlusionStrength>=0&&occlusionStrength<=1,path+": occlusion strength range");
                }
            }
            boolean textured=p.has("baseColorTexture");
            require(!maps||textured,path+": PBR maps require baseColorTexture/UV");
            if(textured){
                require(atlasProfile,path+": texture unsupported");JSONObject t=obj(p,"baseColorTexture");
                onlyKeys(t,Set.of("index","texCoord"),path+".baseColorTexture");
                intValue(t.get("index"),0,0,path+".baseColorTexture index");integer(t,"texCoord",0,0,0);
            }
            float[] color=vector(p,"baseColorFactor",4,new float[]{1,1,1,1},path);
            for(float v:color)require(v>=0&&v<=1,path+": baseColorFactor range");
            float metal=number(p,"metallicFactor",1),rough=number(p,"roughnessFactor",1);
            require(metal>=0&&metal<=1&&rough>=0&&rough<=1,path+": PBR factor range");
            require(pbrProfile||unlit||metal==0,path+": metallic material unsupported by v1a lit profile");reserve(16,path);
            return new AvatarAsset.Material(string(m,"name",""),color,rough,unlit,textured,maps,metal,normalScale,occlusionStrength);
        }
        void mapIndex(JSONObject t,int index,Set<String> allowed,String path)throws JSONException,FormatException{
            onlyKeys(t,allowed,path);intValue(t.get("index"),index,index,path+" index");integer(t,"texCoord",0,0,0);
        }
        AvatarAsset nodes()throws JSONException,FormatException {
            JSONArray source=array(root,"nodes",true,128);int n=source.length();require(n>0,"nodes: empty");
            String[] names=new String[n];int[] mesh=new int[n],parent=new int[n];Arrays.fill(parent,-1);
            int[][] children=new int[n][];float[][] local=new float[n][],world=new float[n][],weights=new float[n][];
            for(int i=0;i<n;i++){
                JSONObject node=source.getJSONObject(i);String path="nodes["+i+"]";
                require(!node.has("skin")&&!node.has("camera"),path+": skin/camera unsupported");
                names[i]=string(node,"name","");mesh[i]=node.has("mesh")?integer(node,"mesh",0,meshes.size()-1,-1):-1;
                JSONArray ch=array(node,"children",false,128);children[i]=new int[ch.length()];
                for(int c=0;c<ch.length();c++){int child=intValue(ch.get(c),0,n-1,path+".children");require(parent[child]<0,path+": duplicate/multiple parent");parent[child]=i;children[i][c]=child;}
                local[i]=transform(node,path);int wc=mesh[i]<0?0:meshes.get(mesh[i]).targetCount();
                float[] defaults=new float[wc];if(mesh[i]>=0)meshes.get(mesh[i]).weights().get(defaults);
                weights[i]=vector(node,"weights",wc,defaults,path);reserve(128L+4L*(ch.length()+wc),path);
            }
            int[] state=new int[n];for(int i=0;i<n;i++)world(i,parent,local,world,state,0);
            JSONArray scenes=array(root,"scenes",true,16);require(scenes.length()>0,"scenes: empty");
            int selected=integer(root,"scene",0,scenes.length()-1,0);int[] roots=null;
            for(int s=0;s<scenes.length();s++){
                JSONArray list=array(scenes.getJSONObject(s),"nodes",true,128);Set<Integer> seen=new HashSet<>();int[] r=new int[list.length()];
                for(int j=0;j<r.length;j++){r[j]=intValue(list.get(j),0,n-1,"scene root");require(parent[r[j]]<0&&seen.add(r[j]),"scene root has parent or duplicate");}
                if(s==selected)roots=r;
            }
            require(roots!=null&&roots.length>0,"selected scene: no roots");
            int[] totals=new int[3];for(int r:roots)sceneCounts(r,children,mesh,world,weights,totals);
            require(totals[0]>0,"selected scene: no drawable mesh");
            List<AvatarAsset.Node> output=new ArrayList<>();
            for(int i=0;i<n;i++)output.add(new AvatarAsset.Node(names[i],mesh[i],children[i],local[i],world[i],weights[i]));
            return new AvatarAsset(meshes,output,materials,roots,totals[1],totals[2],charged,atlas,normalMap,ormMap);
        }
        void world(int i,int[] parent,float[][] local,float[][] output,int[] state,int depth)throws FormatException {
            require(depth<=64,"node depth budget");require(state[i]!=1,"nodes: cycle detected");if(state[i]==2)return;state[i]=1;
            if(parent[i]>=0){world(parent[i],parent,local,output,state,depth+1);output[i]=multiply(output[parent[i]],local[i]);}
            else output[i]=local[i].clone();
            for(float v:output[i])require(Float.isFinite(v),"world matrix non-finite overflow");state[i]=2;
        }
        void sceneCounts(int i,int[][] children,int[] mesh,float[][] world,float[][] weights,int[] totals)throws FormatException {
            if(mesh[i]>=0)for(AvatarAsset.Primitive p:meshes.get(mesh[i]).primitives()){
                totals[0]++;totals[1]+=p.vertexCount();totals[2]+=p.indices().remaining()/3;
                require(totals[0]<=MAX_PRIMITIVES&&totals[1]<=MAX_VERTICES&&totals[2]<=MAX_TRIANGLES,"scene instance geometry budget exceeded");
                FloatBuffer positions=p.positions(),normals=p.normals();float[] m=world[i];
                FloatBuffer[] dp=new FloatBuffer[p.morphs().size()],dn=new FloatBuffer[dp.length];
                for(int j=0;j<dp.length;j++){dp[j]=p.morphs().get(j).positions();dn[j]=p.morphs().get(j).normals();}
                double[] point=new double[3];
                for(int v=0;v<p.vertexCount();v++){
                    for(int c=0;c<3;c++){
                        int at=v*3+c;double x=positions.get(at),normal=normals==null?0:normals.get(at);
                        for(int j=0;j<dp.length;j++){
                            if(dp[j]!=null)x+=weights[i][j]*(double)dp[j].get(at);
                            if(dn[j]!=null)normal+=weights[i][j]*(double)dn[j].get(at);
                        }
                        require(Double.isFinite(x)&&Math.abs(x)<=Float.MAX_VALUE&&Double.isFinite(normal)&&Math.abs(normal)<=Float.MAX_VALUE,
                                "default morph position/normal non-finite overflow");point[c]=x;
                    }
                    for(int row=0;row<3;row++){
                        double x=m[row]*point[0]+m[4+row]*point[1]+m[8+row]*point[2]+m[12+row];
                        require(Double.isFinite(x)&&Math.abs(x)<=Float.MAX_VALUE,"world position non-finite overflow");
                    }
                }
            }
            for(int c:children[i])sceneCounts(c,children,mesh,world,weights,totals);
        }
    }

    /** Exact RGB8/noninterlaced PNG subset; streaming inflate is bounded by IHDR dimensions. */
    private static int[] validatePng(byte[] data)throws FormatException {
        require(data.length>=45,"atlas PNG: truncated");
        byte[] signature={(byte)137,80,78,71,13,10,26,10};
        for(int i=0;i<8;i++)require(data[i]==signature[i],"atlas PNG: signature");
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);b.position(8);
        int width=0,height=0;long expected=0,decoded=0;boolean header=false,idat=false,end=false;
        Inflater inflater=new Inflater();byte[] output=new byte[8192];
        try {
            while(b.remaining()>0){
                require(!end&&b.remaining()>=12,"atlas PNG: trailing or truncated chunk");
                long length=uint(b.getInt());require(length<=b.remaining()-8,"atlas PNG: chunk length");
                int type=b.getInt(),start=b.position(),n=(int)length;
                CRC32 crc=new CRC32();crc.update(data,start-4,n+4);long stored=uint(b.getInt(start+n));
                require(crc.getValue()==stored,"atlas PNG: CRC mismatch");
                if(type==0x49484452){
                    require(!header&&!idat&&start==16&&n==13,"atlas PNG: IHDR chunk");
                    width=b.getInt(start);height=b.getInt(start+4);
                    require(width>0&&width<=2048&&height>0&&height<=2048,"atlas PNG: dimensions outside profile");
                    require(data[start+8]==8&&data[start+9]==2,"atlas PNG: RGB8 required");
                    require(data[start+10]==0&&data[start+11]==0&&data[start+12]==0,"atlas PNG: compression/filter/interlace unsupported");
                    expected=(3L*width+1)*height;header=true;
                } else if(type==0x49444154){
                    require(header&&!end&&n>0&&!inflater.finished(),"atlas PNG: IDAT chunk order");idat=true;
                    require(inflater.needsInput(),"atlas PNG: inflate unconsumed chunk");inflater.setInput(data,start,n);
                    while(!inflater.needsInput()&&!inflater.finished()){
                        int got=inflater.inflate(output);require(got>0||inflater.finished(),"atlas PNG: inflate stalled/dictionary");
                        require(decoded+got<=expected,"atlas PNG: inflate exceeds dimensions");
                        long stride=3L*width+1;
                        for(int i=0;i<got;i++)if((decoded+i)%stride==0)require(Byte.toUnsignedInt(output[i])<=4,"atlas PNG: invalid row filter");
                        decoded+=got;
                    }
                    if(inflater.finished())require(inflater.getRemaining()==0,"atlas PNG: trailing inflate bytes");
                } else if(type==0x49454e44){
                    require(header&&idat&&n==0&&inflater.finished()&&decoded==expected,"atlas PNG: inflate truncated at IEND");end=true;
                } else throw new FormatException("atlas PNG: unsupported chunk "+Integer.toHexString(type));
                b.position(start+n+4);
            }
            require(end,"atlas PNG: missing IEND");return new int[]{width,height};
        } catch(DataFormatException e){throw new FormatException("atlas PNG: inflate invalid",e);}
        finally {inflater.end();}
    }
    private static float[] transform(JSONObject node,String path)throws JSONException,FormatException {
        if(node.has("matrix")){
            require(!node.has("translation")&&!node.has("rotation")&&!node.has("scale"),path+": matrix and TRS are mutually exclusive");
            float[] m=vector(node,"matrix",16,null,path);
            require(m[3]==0&&m[7]==0&&m[11]==0&&m[15]==1,path+": matrix must be affine");
            double[] lengths=new double[3];for(int c=0;c<3;c++){for(int r=0;r<3;r++)lengths[c]+=m[c*4+r]*(double)m[c*4+r];require(lengths[c]>1e-12,path+": matrix zero scale");}
            for(int a=0;a<3;a++)for(int b=a+1;b<3;b++){double dot=0;for(int r=0;r<3;r++)dot+=m[a*4+r]*(double)m[b*4+r];require(Math.abs(dot)/Math.sqrt(lengths[a]*lengths[b])<1e-4,path+": matrix shear unsupported");}
            double det=m[0]*((double)m[5]*m[10]-(double)m[6]*m[9])-m[4]*((double)m[1]*m[10]-(double)m[2]*m[9])+m[8]*((double)m[1]*m[6]-(double)m[2]*m[5]);
            require(det>0,path+": matrix reflected/negative scale unsupported");return m;
        }
        float[] t=vector(node,"translation",3,new float[3],path),q=vector(node,"rotation",4,new float[]{0,0,0,1},path),s=vector(node,"scale",3,new float[]{1,1,1},path);
        for(float v:s)require(v>1e-6,path+": nonpositive/degenerate scale unsupported");
        double norm=0;for(float v:q)norm+=v*(double)v;require(Math.abs(norm-1)<1e-3,path+": rotation quaternion must be unit length");
        double x=q[0]/Math.sqrt(norm),y=q[1]/Math.sqrt(norm),z=q[2]/Math.sqrt(norm),w=q[3]/Math.sqrt(norm);
        return new float[]{(float)(1-2*(y*y+z*z))*s[0],(float)(2*(x*y+z*w))*s[0],(float)(2*(x*z-y*w))*s[0],0,
                (float)(2*(x*y-z*w))*s[1],(float)(1-2*(x*x+z*z))*s[1],(float)(2*(y*z+x*w))*s[1],0,
                (float)(2*(x*z+y*w))*s[2],(float)(2*(y*z-x*w))*s[2],(float)(1-2*(x*x+y*y))*s[2],0,t[0],t[1],t[2],1};
    }
    private static float[] multiply(float[] a,float[] b){float[] out=new float[16];for(int c=0;c<4;c++)for(int r=0;r<4;r++){double sum=0;for(int k=0;k<4;k++)sum+=a[k*4+r]*(double)b[c*4+k];out[c*4+r]=(float)sum;}return out;}
    private static float[] vector(JSONObject o,String key,int width,float[] defaults,String path)throws JSONException,FormatException {
        if(!o.has(key))return defaults;JSONArray a=array(o,key,true,Math.max(64,width));require(a.length()==width,path+"."+key+": vector length");
        float[] out=new float[width];for(int i=0;i<width;i++)out[i]=finite(a.get(i),path+"."+key);return out;
    }
    private static float number(JSONObject o,String key,float fallback)throws JSONException,FormatException{return o.has(key)?finite(o.get(key),key):fallback;}
    private static float finite(Object value,String path)throws FormatException {require(value instanceof Number,path+": numeric finite value required");double d=((Number)value).doubleValue();require(Double.isFinite(d)&&Math.abs(d)<=Float.MAX_VALUE,path+": non-finite/overflow float");return(float)d;}
    private static int integer(JSONObject o,String key,int min,int max,int fallback)throws JSONException,FormatException {
        if(o.has(key))return intValue(o.get(key),min,max,key);
        require(fallback!=-1,key+": required integer missing");return fallback;
    }
    private static int intValue(Object value,int min,int max,String path)throws FormatException {require(value instanceof Number,path+": integer required");double d=((Number)value).doubleValue();require(Double.isFinite(d)&&d==Math.rint(d)&&d>=min&&d<=max,path+": integer range "+min+".."+max);return(int)d;}
    private static String string(JSONObject o,String key,String fallback)throws JSONException,FormatException {if(!o.has(key))return fallback;Object v=o.get(key);require(v instanceof String,key+": string required");return(String)v;}
    private static boolean bool(JSONObject o,String key,boolean fallback)throws JSONException,FormatException {if(!o.has(key))return fallback;Object v=o.get(key);require(v instanceof Boolean,key+": boolean required");return(Boolean)v;}
    private static JSONObject obj(JSONObject o,String key)throws JSONException,FormatException {require(o.has(key)&&o.get(key) instanceof JSONObject,key+": object required");return o.getJSONObject(key);}
    private static JSONArray array(JSONObject o,String key,boolean required,int max)throws JSONException,FormatException {if(!o.has(key)){require(!required,key+": array required");return new JSONArray();}require(o.get(key) instanceof JSONArray,key+": array required");JSONArray a=o.getJSONArray(key);require(a.length()<=max,key+": array budget exceeded");return a;}
    private static List<Object> values(JSONArray a)throws JSONException {List<Object> out=new ArrayList<>();for(int i=0;i<a.length();i++)out.add(a.get(i));return out;}
    private static void onlyKeys(JSONObject o,Set<String> supported,String path)throws FormatException {for(Iterator<String> it=o.keys();it.hasNext();){String k=it.next();require(supported.contains(k),path+": unsupported semantic "+k);}}
    private static void extensions(Object value,String path,int depth)throws JSONException,FormatException {
        require(depth<=64,"JSON nesting budget");
        if(value instanceof JSONObject o){
            for(Iterator<String> it=o.keys();it.hasNext();){String key=it.next();Object v=o.get(key);
                if(key.equals("extensions")){require(v instanceof JSONObject,path+": extensions object required");JSONObject ex=(JSONObject)v;
                    for(Iterator<String> ei=ex.keys();ei.hasNext();){String e=ei.next();require(e.equals(UNLIT)&&path.matches("root\\.materials\\[\\d+\\]"),path+": unsupported extension "+e);require(ex.get(e) instanceof JSONObject&&((JSONObject)ex.get(e)).length()==0,path+": invalid unlit extension");}}
                else if(!key.equals("extras"))extensions(v,path+"."+key,depth+1);
            }
        } else if(value instanceof JSONArray a)for(int i=0;i<a.length();i++)extensions(a.get(i),path+"["+i+"]",depth+1);
    }
    private static long uint(int n){return Integer.toUnsignedLong(n);}
    private static void require(boolean condition,String message)throws FormatException {if(!condition)throw new FormatException(message);}

    /** Bound DOM work and reject org.json's lenient syntax/duplicate keys consistently on Android/JVM. */
    private static final class JsonSyntax {
        final String source;int at,tokens;
        JsonSyntax(String source){this.source=source;}
        void validate()throws FormatException,JSONException {value(0);space();require(at==source.length(),"JSON trailing content");}
        void space(){while(at<source.length()&&" \t\r\n".indexOf(source.charAt(at))>=0)at++;}
        boolean take(char c){space();if(at<source.length()&&source.charAt(at)==c){at++;return true;}return false;}
        void value(int depth)throws FormatException,JSONException {
            require(depth<=48&&++tokens<=30_000,"JSON nesting/token budget");space();require(at<source.length(),"JSON truncated value");char c=source.charAt(at);
            if(c=='{'){at++;Set<String> keys=new HashSet<>();if(take('}'))return;do{space();require(at<source.length()&&source.charAt(at)=='"',"JSON object key must be quoted");String key=quoted();require(keys.add(key),"JSON duplicate key: "+key);require(take(':'),"JSON missing colon");value(depth+1);if(take('}'))return;require(take(','),"JSON missing comma");}while(true);}
            if(c=='['){at++;if(take(']'))return;do{value(depth+1);if(take(']'))return;require(take(','),"JSON missing comma");}while(true);}
            if(c=='"'){quoted();return;}
            int start=at;while(at<source.length()&&" \t\r\n,]}".indexOf(source.charAt(at))<0)at++;
            String token=source.substring(start,at);require(token.equals("true")||token.equals("false")||token.equals("null")||token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"),"JSON invalid literal");
        }
        String quoted()throws FormatException,JSONException {
            int start=at++;while(at<source.length()){
                char c=source.charAt(at++);require(c>=32,"JSON string control character");
                if(c=='"')return(String)new JSONTokener(source.substring(start,at)).nextValue();
                if(c=='\\'){require(at<source.length(),"JSON truncated escape");char escaped=source.charAt(at++);require("\"\\/bfnrtu".indexOf(escaped)>=0,"JSON invalid escape");if(escaped=='u'){require(at+4<=source.length(),"JSON truncated unicode");for(int j=0;j<4;j++)require(Character.digit(source.charAt(at++),16)>=0,"JSON invalid unicode");}}
            }
            throw new FormatException("JSON unterminated string");
        }
    }
}
