package com.mirror.bench;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Validated CPU asset; no Android/GL handles. Buffer accessors return read-only independent views. */
public final class AvatarAsset {
    private final List<Mesh> meshes;
    private final List<Node> nodes;
    private final List<Material> materials;
    private final int[] sceneRoots;
    private final int vertices, triangles;
    private final long decodedBytes;
    private final AlbedoAtlas atlas;
    private final AlbedoAtlas normalMap,ormMap;

    // Package-private constructors take ownership of fresh loader arrays, never caller input arrays.
    AvatarAsset(List<Mesh> meshes,List<Node> nodes,List<Material> materials,int[] roots,int vertices,int triangles,long bytes) {
        this(meshes,nodes,materials,roots,vertices,triangles,bytes,null);
    }
    AvatarAsset(List<Mesh> meshes,List<Node> nodes,List<Material> materials,int[] roots,int vertices,int triangles,long bytes,AlbedoAtlas atlas) {
        this(meshes,nodes,materials,roots,vertices,triangles,bytes,atlas,null,null);
    }
    AvatarAsset(List<Mesh> meshes,List<Node> nodes,List<Material> materials,int[] roots,int vertices,int triangles,long bytes,AlbedoAtlas atlas,AlbedoAtlas normalMap,AlbedoAtlas ormMap) {
        this.meshes=freeze(meshes);this.nodes=freeze(nodes);this.materials=freeze(materials);
        sceneRoots=roots;this.vertices=vertices;this.triangles=triangles;decodedBytes=bytes;
        this.atlas=atlas;
        this.normalMap=normalMap;this.ormMap=ormMap;
    }
    public List<Mesh> meshes(){return meshes;}
    public List<Node> nodes(){return nodes;}
    public List<Material> materials(){return materials;}
    public IntBuffer sceneRoots(){return ints(sceneRoots);}
    /** Total geometry submitted by the selected scene, including mesh instances. */
    public int vertexCount(){return vertices;}
    public int triangleCount(){return triangles;}
    /** Charged arrays plus optional encoded atlas and one RGBA decode, excluding input/JSON DOM. */
    public long decodedBytes(){return decodedBytes;}
    /** Null for the original factor-only asset profile. No GPU handles are cached here. */
    public AlbedoAtlas albedoAtlas(){return atlas;}
    public AlbedoAtlas normalMap(){return normalMap;}
    public AlbedoAtlas ormMap(){return ormMap;}
    public static final class AlbedoAtlas {
        private final byte[] png;
        private final int width,height;
        AlbedoAtlas(byte[] png,int width,int height){this.png=png;this.width=width;this.height=height;}
        public InputStream openStream(){return new ByteArrayInputStream(png);}
        public int encodedBytes(){return png.length;}
        public int width(){return width;}
        public int height(){return height;}
    }

    public static final class Mesh {
        private final String name;
        private final List<Primitive> primitives;
        private final List<String> names;
        private final float[] weights;
        Mesh(String name,List<Primitive> primitives,List<String> names,float[] weights){
            this.name=name;this.primitives=freeze(primitives);this.names=freeze(names);this.weights=weights;
        }
        public String name(){return name;}
        public List<Primitive> primitives(){return primitives;}
        /** Empty means unnamed targets; manifest must bind explicit target indices. */
        public List<String> targetNames(){return names;}
        public int targetIndex(String name){return names.indexOf(name);}
        public int targetCount(){return weights.length;}
        public FloatBuffer weights(){return floats(weights);}
    }
    public static final class Primitive {
        private final float[] positions,normals,texCoords,colors;
        private final int[] indices;
        private final List<Morph> morphs;
        private final int material;
        Primitive(float[] p,float[] n,float[] uv,float[] c,int[] indices,List<Morph> morphs,int material){
            positions=p;normals=n;texCoords=uv;colors=c;this.indices=indices;this.morphs=freeze(morphs);this.material=material;
        }
        public FloatBuffer positions(){return floats(positions);}
        /** Null when absent; renderer must recompute normals before lit rendering. */
        public FloatBuffer normals(){return floats(normals);}
        public FloatBuffer texCoords(){return floats(texCoords);}
        /** Optional RGBA float values, expanded from VEC3/VEC4. */
        public FloatBuffer colors(){return floats(colors);}
        public IntBuffer indices(){return ints(indices);}
        public List<Morph> morphs(){return morphs;}
        public int materialIndex(){return material;}
        public int vertexCount(){return positions.length/3;}
    }
    public static final class Morph {
        private final float[] positions,normals;
        Morph(float[] positions,float[] normals){this.positions=positions;this.normals=normals;}
        /** Missing semantics are null (zero delta), never silently substituted for malformed data. */
        public FloatBuffer positions(){return floats(positions);}
        public FloatBuffer normals(){return floats(normals);}
    }
    public static final class Node {
        private final String name;
        private final int mesh;
        private final int[] children;
        private final float[] local,world,weights;
        Node(String name,int mesh,int[] children,float[] local,float[] world,float[] weights){
            this.name=name;this.mesh=mesh;this.children=children;this.local=local;this.world=world;this.weights=weights;
        }
        public String name(){return name;}
        public int meshIndex(){return mesh;}
        public IntBuffer children(){return ints(children);}
        public FloatBuffer localMatrix(){return floats(local);}
        /** Column-major parent * local transform in bind pose, before live head/eye controls. */
        public FloatBuffer worldMatrix(){return floats(world);}
        public FloatBuffer weights(){return floats(weights);}
    }
    public static final class Material {
        private final String name;
        private final float[] color;
        private final float roughness;
        private final boolean unlit;
        private final boolean textured;
        private final boolean pbrMaps;
        private final float metallic,normalScale,occlusionStrength;
        Material(String name,float[] color,float roughness,boolean unlit){this(name,color,roughness,unlit,false);}
        Material(String name,float[] color,float roughness,boolean unlit,boolean textured){this(name,color,roughness,unlit,textured,false,0,1,0);}
        Material(String name,float[] color,float roughness,boolean unlit,boolean textured,boolean pbrMaps,float metallic,float normalScale,float occlusionStrength){
            this.name=name;this.color=color;this.roughness=roughness;this.unlit=unlit;this.textured=textured;
            this.pbrMaps=pbrMaps;this.metallic=metallic;this.normalScale=normalScale;this.occlusionStrength=occlusionStrength;
        }
        public String name(){return name;}
        public FloatBuffer baseColor(){return floats(color);}
        public float roughness(){return roughness;}
        public boolean unlit(){return unlit;}
        public boolean textured(){return textured;}
        public boolean pbrMaps(){return pbrMaps;}
        public float metallic(){return metallic;}
        public float normalScale(){return normalScale;}
        public float occlusionStrength(){return occlusionStrength;}
    }
    private static FloatBuffer floats(float[] a){return a==null?null:FloatBuffer.wrap(a).asReadOnlyBuffer();}
    private static IntBuffer ints(int[] a){return IntBuffer.wrap(a).asReadOnlyBuffer();}
    private static <T> List<T> freeze(List<T> list){return Collections.unmodifiableList(new ArrayList<>(list));}
}
