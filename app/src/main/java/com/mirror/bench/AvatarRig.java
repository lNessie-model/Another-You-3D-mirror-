package com.mirror.bench;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Compiled named bindings and rigid head/eye/jaw control. Single owner; no per-frame JSON or strings. */
public final class AvatarRig {
    private final AvatarAsset asset;
    private final Binding[] bindings;
    private final ProductBinding[] products;
    private final int schemaVersion;
    private final float[] inputs = new float[52];
    private final float[][] defaults;
    private float[][] weights, stagingWeights;
    private float[] world, stagingWorld;
    private final float[] baseLocal;
    private final int[] parents, order;
    private final int head, leftEye, rightEye, jaw;
    private final float yawDegrees, pitchDegrees, jawDegrees, jawLateral, jawForward, rootScale;
    private final float[] jawAxis, local = new float[16], delta = new float[16], temp = new float[16];
    private final String id, displayName, normalPolicy;
    private final List<String> missing;
    private final boolean jointGaze;

    public AvatarRig(AvatarAsset asset, String manifest) throws JSONException {
        if (asset == null || manifest == null || manifest.length() > 262_144) throw invalid("Missing/oversized avatar manifest");
        this.asset = asset;
        JSONObject json = new JSONObject(manifest);
        schemaVersion=integer(json,"schemaVersion",3);
        if (schemaVersion<1 || !BlendshapeSchema.ID.equals(string(json,"inputSchema")))
            throw invalid("Unsupported avatar input schema");
        boolean productFeature=false;
        if(schemaVersion==1&&(json.has("requiredRigFeatures")||json.has("derivedBindings")))
            throw invalid("Rig feature declarations require schemaVersion 2");
        if(schemaVersion==2) {
            JSONArray features=array(json,"requiredRigFeatures");Set<String> seen=new HashSet<>();
            for(int i=0;i<features.length();i++) {
                Object feature=features.get(i);
                if(!(feature instanceof String)||!feature.equals("product-correctives-v1")||!seen.add((String)feature))
                    throw invalid("Unknown/duplicate required rig feature: "+feature);
                productFeature=true;
            }
            if(json.has("derivedBindings")&&!productFeature)throw invalid("derivedBindings require product-correctives-v1");
            if(productFeature&&!json.has("derivedBindings"))throw invalid("Product feature requires derivedBindings");
        }
        id = string(json,"id"); displayName = string(json,"displayName");
        String filename = string(json,"model");
        if (!filename.matches("[A-Za-z0-9_-]+\\.glb")) throw invalid("Model must be a sibling GLB filename");
        normalPolicy = string(json,"normalPolicy");
        if (!normalPolicy.equals("recompute-deformed") && !normalPolicy.equals("authored") && !normalPolicy.equals("smooth-deformed-v1")) throw invalid("Unsupported normal policy");
        String materialProfile=json.has("materialProfile")?string(json,"materialProfile"):"mirror-lit-v1";
        if(!materialProfile.equals("mirror-lit-v1")&&!materialProfile.equals("mirror-pbr-v1"))throw invalid("Unsupported material profile");
        if(materialProfile.equals("mirror-pbr-v1")!=(asset.normalMap()!=null))throw invalid("Material profile differs from embedded PBR maps");
        JSONObject coords = json.getJSONObject("coordinates");
        keys(coords,Set.of("up","forward","subjectLeft","units","rootScale","headPivot"),"coordinate operation");
        if (!string(coords,"up").equals("+Y") || !string(coords,"forward").equals("+Z")
                || !string(coords,"subjectLeft").equals("+X") || !string(coords,"units").equals("meters"))
            throw invalid("Avatar coordinates must be +Y up, +Z forward, +X subject-left, meters");
        rootScale = number(coords,"rootScale",.001f,1000);
        JSONObject controls = json.getJSONObject("rig");
        keys(controls,Set.of("headNode","jawMode","jawAttachmentNode","jawAxis","jawOpenDegrees",
                "jawLateralMeters","jawForwardMeters","gazeMode","leftEyeNode","rightEyeNode",
                "gazeYawDegrees","gazePitchDegrees"),"rig operation");
        head = nodeIndex(string(controls,"headNode"));
        if (!string(controls,"jawMode").equals("morph")) throw invalid("Only morph jaw with rigid attachments is supported");
        jaw = nodeIndex(string(controls,"jawAttachmentNode"));
        String gaze = string(controls,"gazeMode");
        if (!gaze.equals("joint") && !gaze.equals("morph")) throw invalid("Unsupported gaze mode");
        jointGaze = gaze.equals("joint");
        if(!jointGaze)for(String key:new String[]{"leftEyeNode","rightEyeNode","gazeYawDegrees","gazePitchDegrees"})
            if(controls.has(key))throw invalid("Joint gaze controls are not used in morph gaze mode: "+key);
        leftEye = jointGaze ? nodeIndex(string(controls,"leftEyeNode")) : -1;
        rightEye = jointGaze ? nodeIndex(string(controls,"rightEyeNode")) : -1;
        yawDegrees = jointGaze ? number(controls,"gazeYawDegrees",1,45) : 0;
        pitchDegrees = jointGaze ? number(controls,"gazePitchDegrees",1,45) : 0;
        jawDegrees = number(controls,"jawOpenDegrees",1,45);
        jawLateral = number(controls,"jawLateralMeters",0,.05f);
        jawForward = number(controls,"jawForwardMeters",0,.05f);
        jawAxis = vector(controls,"jawAxis",3);
        double axisLength = Math.sqrt(jawAxis[0]*jawAxis[0]+jawAxis[1]*jawAxis[1]+jawAxis[2]*jawAxis[2]);
        if (Math.abs(axisLength-1) > .001) throw invalid("Jaw axis must be unit length");
        int count = asset.nodes().size();
        parents = new int[count]; Arrays.fill(parents,-2);
        order = new int[count]; baseLocal = new float[count*16];
        int[] next = {0};
        var roots = asset.sceneRoots();
        for (int i=0;i<roots.limit();i++) visit(roots.get(i),-1,next);
        for (int i=next[0];i<count;i++) order[i]=-1;
        for (int index : new int[]{head,jaw,leftEye,rightEye}) if (index>=0 && parents[index]==-2) throw invalid("Rig node is outside selected scene");
        if (!descendant(jaw,head) || (jointGaze && (!descendant(leftEye,head) || !descendant(rightEye,head)
                || leftEye==rightEye || leftEye==jaw || rightEye==jaw))) throw invalid("Eyes and jaw must be distinct descendants of Head");
        // Rigid controls must not contain one another or the same geometry would be driven twice.
        if (jointGaze && (descendant(leftEye,jaw)||descendant(rightEye,jaw)||descendant(jaw,leftEye)
                ||descendant(jaw,rightEye)||descendant(leftEye,rightEye)||descendant(rightEye,leftEye)))
            throw invalid("Nested eye/jaw control nodes are unsupported");
        float[] pivot = vector(coords,"headPivot",3);
        FloatBuffer headWorld = asset.nodes().get(head).worldMatrix();
        for(int i=0;i<3;i++) if(Math.abs(headWorld.get(12+i)-pivot[i])>1e-5f) throw invalid("headPivot differs from Head bind origin");
        defaults = new float[asset.meshes().size()][];
        weights = new float[defaults.length][]; stagingWeights = new float[defaults.length][];
        for (int m=0;m<defaults.length;m++) defaults[m]=copy(asset.meshes().get(m).weights());
        boolean[] instanceSeen=new boolean[defaults.length];
        for (int n:order) if(n>=0) {
            var node=asset.nodes().get(n); int m=node.meshIndex(); if(m<0) continue;
            float[] nodeWeights=node.weights().limit()==0 ? copy(asset.meshes().get(m).weights()) : copy(node.weights());
            if(instanceSeen[m]&&!Arrays.equals(defaults[m],nodeWeights)) throw invalid("Mesh instances with different initial morph weights need separate meshes");
            defaults[m]=nodeWeights; instanceSeen[m]=true;
        }
        for(int m=0;m<defaults.length;m++) {
            weights[m]=defaults[m].clone(); stagingWeights[m]=defaults[m].clone();
        }
        JSONArray list=json.getJSONArray("bindings");
        if(list.length()>512) throw invalid("Too many avatar bindings");
        Set<String> destinations=new HashSet<>(); boolean[] covered=new boolean[52];
        ArrayList<Binding> compiled=new ArrayList<>();
        for(int i=0;i<list.length();i++) {
            JSONObject row=list.getJSONObject(i);
            Set<String> allowed=Set.of("source","mesh","meshIndex","target","targetIndex","gain","bias","deadZone","gamma","min","max");
            var keys=row.keys(); while(keys.hasNext()) if(!allowed.contains(keys.next())) throw invalid("Unsupported binding operation");
            int source=BlendshapeSchema.indexOf(string(row,"source"));
            if(source<=0) throw invalid("Unknown or neutral source cannot drive morph geometry");
            if(jointGaze && source>=11&&source<=18) throw invalid("Gaze cannot drive both joints and morphs");
            if(row.has("mesh")==row.has("meshIndex")) throw invalid("Specify exactly one mesh name/index");
            int mesh=row.has("mesh")?meshIndex(string(row,"mesh")):integer(row,"meshIndex",defaults.length);
            if(!instanceSeen[mesh]) throw invalid("Binding mesh is outside selected scene");
            if(source>=23&&source<=26)for(int node:order)if(node>=0&&asset.nodes().get(node).meshIndex()==mesh
                    &&(node==jaw||descendant(node,jaw)))
                throw invalid("Jaw morph geometry must not also be transformed by JawAttachments");
            if(row.has("target")==row.has("targetIndex")) throw invalid("Specify exactly one target name/index");
            int target=row.has("target")?asset.meshes().get(mesh).targetIndex(string(row,"target"))
                    :integer(row,"targetIndex",defaults[mesh].length);
            if(target<0) throw invalid("Unknown morph target");
            if(!destinations.add(mesh+":"+target)) throw invalid("Multiple bindings per target require an explicit composition profile");
            compiled.add(new Binding(source,mesh,target,optional(row,"gain",1,0,4),optional(row,"bias",0,-1,1),
                    optional(row,"deadZone",0,0,.999f),optional(row,"gamma",1,.1f,4),
                    optional(row,"min",0,0,1),optional(row,"max",1,0,1)));
            covered[source]=true;
        }
        ArrayList<ProductBinding> compiledProducts=new ArrayList<>();
        boolean[] derivedInputs=new boolean[52];
        if(productFeature) {
            JSONArray derived=array(json,"derivedBindings");
            if(derived.length()==0||derived.length()>128||derived.length()+list.length()>512)
                throw invalid("Product binding count outside supported range");
            for(int i=0;i<derived.length();i++) {
                JSONObject row=derived.getJSONObject(i);
                keys(row,Set.of("operation","sources","mesh","meshIndex","target","targetIndex","gain"),"derived binding operation");
                if(!string(row,"operation").equals("product"))throw invalid("Only product derived bindings are supported");
                JSONArray names=array(row,"sources");
                if(names.length()<2||names.length()>3)throw invalid("Product requires two or three original sources");
                int[] sources=new int[names.length()];Set<Integer> seen=new HashSet<>();boolean hasJaw=false;
                for(int s=0;s<sources.length;s++) {
                    Object name=names.get(s);int source=name instanceof String?BlendshapeSchema.indexOf((String)name):-1;
                    if(source<=0||!seen.add(source))throw invalid("Product sources must be distinct original non-neutral input names");
                    sources[s]=source;derivedInputs[source]=true;hasJaw|=source>=23&&source<=26;
                }
                if(row.has("mesh")==row.has("meshIndex"))throw invalid("Specify exactly one product mesh name/index");
                int mesh=row.has("mesh")?meshIndex(string(row,"mesh")):integer(row,"meshIndex",defaults.length);
                if(!instanceSeen[mesh])throw invalid("Product mesh is outside selected scene");
                if(hasJaw)for(int node:order)if(node>=0&&asset.nodes().get(node).meshIndex()==mesh&&(node==jaw||descendant(node,jaw)))
                    throw invalid("Jaw corrective geometry must not also be transformed by JawAttachments");
                if(row.has("target")==row.has("targetIndex"))throw invalid("Specify exactly one product target name/index");
                int target=row.has("target")?asset.meshes().get(mesh).targetIndex(string(row,"target")):integer(row,"targetIndex",defaults[mesh].length);
                if(target<0)throw invalid("Unknown product morph target");
                if(!destinations.add(mesh+":"+target))throw invalid("Duplicate direct/product morph destination");
                // Auto-framing encloses target weights in [0,1]; gain cannot silently enlarge that envelope.
                compiledProducts.add(new ProductBinding(sources,mesh,target,optional(row,"gain",1,0,1)));
            }
        }
        if(jointGaze) for(int i=11;i<=18;i++) covered[i]=true;
        if(!covered[9]||!covered[10]||!covered[25]) throw invalid("Independent left/right blink and jawOpen are required");
        if(!jointGaze) for(int i=11;i<=18;i++) if(!covered[i]) throw invalid("Morph gaze requires all eight eye channels");
        if(json.has("ignoredSources")&&!(json.get("ignoredSources") instanceof JSONArray))throw invalid("ignoredSources must be an array");
        JSONArray ignored=json.optJSONArray("ignoredSources");
        boolean[] explicitlyIgnored=new boolean[52];
        if(ignored!=null) for(int i=0;i<ignored.length();i++) {
            Object value=ignored.get(i); int source=value instanceof String?BlendshapeSchema.indexOf((String)value):-1;
            if(source<0||covered[source]||derivedInputs[source]||explicitlyIgnored[source]) throw invalid("Invalid/duplicate ignored source");
            explicitlyIgnored[source]=true;
        }
        ArrayList<String> absent=new ArrayList<>();
        for(int i=1;i<52;i++) if(!covered[i]) absent.add(BlendshapeSchema.name(i)+(explicitlyIgnored[i]?" (explicitly ignored)":" (missing)"));
        missing=java.util.Collections.unmodifiableList(absent);
        bindings=compiled.toArray(new Binding[0]);
        products=compiledProducts.toArray(new ProductBinding[0]);
        world=new float[count*16]; stagingWorld=new float[count*16];
        update(new float[52],new float[3]);
    }
    public String id(){return id;}
    public String displayName(){return displayName;}
    public String normalPolicy(){return normalPolicy;}
    public float rootScale(){return rootScale;}
    /** Immutable source and static control metadata for conservative scene bounds; no live state changes. */
    public AvatarAsset asset(){return asset;}
    public int headNodeIndex(){return head;}
    public int leftEyeNodeIndex(){return leftEye;}
    public int rightEyeNodeIndex(){return rightEye;}
    public int jawNodeIndex(){return jaw;}
    public float jawOpenDegrees(){return jawDegrees;}
    public float jawLateralMeters(){return jawLateral;}
    public float jawForwardMeters(){return jawForward;}
    public void copyJawAxis(float[] destination){
        if(destination==null||destination.length!=3)throw invalid("Jaw axis destination requires three entries");
        System.arraycopy(jawAxis,0,destination,0,3);
    }
    public boolean completeSourceCoverage(){return missing.isEmpty();}
    public List<String> missingSources(){return missing;}
    public void copyMeshWeights(int mesh,float[] destination){
        if(destination.length!=weights[mesh].length) throw invalid("Weight destination size differs");
        System.arraycopy(weights[mesh],0,destination,0,destination.length);
    }
    public void copyWorldMatrix(int node,float[] destination){
        if(destination.length!=16 || parents[node]==-2) throw invalid("Invalid world matrix destination/node");
        System.arraycopy(world,node*16,destination,0,16);
    }
    public boolean activeNode(int node){return parents[node]!=-2;}
    /** Head Euler angles use Rz(roll) Ry(yaw) Rx(pitch), degrees. Inputs are already filtered once. */
    public void update(float[] values,float[] headAngles) {
        if(values==null||values.length!=52||headAngles==null||headAngles.length!=3) throw invalid("Expected 52 inputs and 3 head angles");
        for(float value:values) if(!Float.isFinite(value)||(schemaVersion==1&&(value<0||value>1)))
            throw invalid("Blendshape weights must be finite; schema v1 requires [0,1]");
        for(float value:headAngles) if(!Float.isFinite(value)||Math.abs(value)>90) throw invalid("Head angle outside supported range");
        for(int i=0;i<inputs.length;i++)inputs[i]=Math.max(0,Math.min(1,values[i]));
        for(int m=0;m<defaults.length;m++) System.arraycopy(defaults[m],0,stagingWeights[m],0,defaults[m].length);
        for(Binding binding:bindings) stagingWeights[binding.mesh][binding.target]=binding.map(inputs[binding.source]);
        for(ProductBinding product:products)stagingWeights[product.mesh][product.target]=product.map(inputs);
        for(int node:order) {
            if(node<0) break;
            System.arraycopy(baseLocal,node*16,local,0,16);
            if(node==head) rotation(delta,headAngles[0],headAngles[1],headAngles[2]);
            else if(jointGaze && node==leftEye) rotation(delta,pitchDegrees*(inputs[11]-inputs[17]),yawDegrees*(inputs[15]-inputs[13]),0);
            else if(jointGaze && node==rightEye) rotation(delta,pitchDegrees*(inputs[12]-inputs[18]),yawDegrees*(inputs[14]-inputs[16]),0);
            else if(node==jaw) {
                axisRotation(delta,jawDegrees*inputs[25],jawAxis);
                delta[12]=jawLateral*(inputs[24]-inputs[26]); delta[14]=jawForward*inputs[23];
            } else identity(delta);
            multiply(temp,0,local,0,delta,0);
            if(parents[node]<0) System.arraycopy(temp,0,stagingWorld,node*16,16);
            else multiply(stagingWorld,node*16,stagingWorld,parents[node]*16,temp,0);
            for(int i=0;i<16;i++) if(!Float.isFinite(stagingWorld[node*16+i])) throw invalid("Dynamic node transform overflow");
        }
        float[] old=world; world=stagingWorld; stagingWorld=old;
        float[][] oldWeights=weights;weights=stagingWeights;stagingWeights=oldWeights;
    }
    private void visit(int node,int parent,int[] next) {
        if(parents[node]!=-2) throw invalid("Scene is not a tree");
        parents[node]=parent;order[next[0]++]=node;
        asset.nodes().get(node).localMatrix().get(baseLocal,node*16,16);
        var children=asset.nodes().get(node).children();
        for(int i=0;i<children.limit();i++) visit(children.get(i),node,next);
    }
    private boolean descendant(int node,int ancestor) { for(int p=parents[node];p>=0;p=parents[p]) if(p==ancestor)return true;return false; }
    private int nodeIndex(String name) { int found=-1;for(int i=0;i<asset.nodes().size();i++) if(asset.nodes().get(i).name().equals(name)) {if(found>=0)throw invalid("Ambiguous node name: "+name);found=i;}if(found<0)throw invalid("Missing node: "+name);return found; }
    private int meshIndex(String name) { int found=-1;for(int i=0;i<asset.meshes().size();i++) if(asset.meshes().get(i).name().equals(name)) {if(found>=0)throw invalid("Ambiguous mesh name: "+name);found=i;}if(found<0)throw invalid("Missing mesh: "+name);return found; }
    private static final class Binding {
        final int source,mesh,target; final float gain,bias,deadZone,gamma,min,max;
        Binding(int source,int mesh,int target,float gain,float bias,float deadZone,float gamma,float min,float max){
            if(min>max)throw invalid("Binding min exceeds max");this.source=source;this.mesh=mesh;this.target=target;
            this.gain=gain;this.bias=bias;this.deadZone=deadZone;this.gamma=gamma;this.min=min;this.max=max;
        }
        float map(float value){double normalized=Math.max(0,(value-deadZone)/(1-deadZone));return (float)Math.max(min,Math.min(max,Math.pow(normalized,gamma)*gain+bias));}
    }
    private static final class ProductBinding {
        final int[] sources;final int mesh,target;final float gain;
        ProductBinding(int[] sources,int mesh,int target,float gain){this.sources=sources;this.mesh=mesh;this.target=target;this.gain=gain;}
        float map(float[] inputs){float value=1;for(int source:sources)value*=inputs[source];return value*gain;}
    }
    private static String string(JSONObject json,String key)throws JSONException {Object value=json.get(key);if(!(value instanceof String)||((String)value).isEmpty()||((String)value).length()>256)throw invalid("Expected string: "+key);return (String)value;}
    private static JSONArray array(JSONObject json,String key){Object value=json.opt(key);if(!(value instanceof JSONArray))throw invalid("Expected array: "+key);return (JSONArray)value;}
    private static float number(JSONObject json,String key,float min,float max)throws JSONException {Object value=json.get(key);if(!(value instanceof Number))throw invalid("Expected number: "+key);double n=((Number)value).doubleValue();if(!Double.isFinite(n)||n<min||n>max)throw invalid("Number out of range: "+key);return (float)n;}
    private static float optional(JSONObject json,String key,float fallback,float min,float max)throws JSONException{return json.has(key)?number(json,key,min,max):fallback;}
    private static int integer(JSONObject json,String key,int size)throws JSONException {
        Object raw=json.get(key);if(!(raw instanceof Number))throw invalid("Expected integer: "+key);
        double value=((Number)raw).doubleValue();
        if(!Double.isFinite(value)||value<0||value>=size||value!=Math.rint(value))throw invalid("Integer out of range: "+key);
        return (int)value;
    }
    private static void keys(JSONObject json,Set<String> allowed,String kind) {
        var keys=json.keys();while(keys.hasNext()){String key=keys.next();if(!allowed.contains(key))throw invalid("Unsupported "+kind+": "+key);}
    }
    private static float[] vector(JSONObject json,String key,int count)throws JSONException {JSONArray a=json.getJSONArray(key);if(a.length()!=count)throw invalid("Vector length: "+key);float[] out=new float[count];for(int i=0;i<count;i++){Object v=a.get(i);if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue())||Math.abs(((Number)v).doubleValue())>1e6)throw invalid("Invalid vector: "+key);out[i]=((Number)v).floatValue();}return out;}
    private static float[] copy(FloatBuffer buffer){float[] result=new float[buffer.remaining()];buffer.get(result);return result;}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
    static void identity(float[] out){Arrays.fill(out,0);out[0]=out[5]=out[10]=out[15]=1;}
    static void multiply(float[] out,int o,float[] a,int x,float[] b,int y){
        for(int col=0;col<4;col++)for(int row=0;row<4;row++){
            double value=0;for(int k=0;k<4;k++)value+=(double)a[x+k*4+row]*b[y+col*4+k];out[o+col*4+row]=(float)value;
        }
    }
    static void rotation(float[] out,float pitch,float yaw,float roll){
        double x=Math.toRadians(pitch),y=Math.toRadians(yaw),z=Math.toRadians(roll);
        double cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z);
        identity(out);out[0]=(float)(cz*cy);out[1]=(float)(sz*cy);out[2]=(float)-sy;
        out[4]=(float)(cz*sy*sx-sz*cx);out[5]=(float)(sz*sy*sx+cz*cx);out[6]=(float)(cy*sx);
        out[8]=(float)(cz*sy*cx+sz*sx);out[9]=(float)(sz*sy*cx-cz*sx);out[10]=(float)(cy*cx);
    }
    private static void axisRotation(float[] out,float degrees,float[] axis){
        double radians=Math.toRadians(degrees),s=Math.sin(radians),c=Math.cos(radians),d=1-c,x=axis[0],y=axis[1],z=axis[2];
        identity(out);out[0]=(float)(c+x*x*d);out[1]=(float)(y*x*d+z*s);out[2]=(float)(z*x*d-y*s);
        out[4]=(float)(x*y*d-z*s);out[5]=(float)(c+y*y*d);out[6]=(float)(z*y*d+x*s);
        out[8]=(float)(x*z*d+y*s);out[9]=(float)(y*z*d-x*s);out[10]=(float)(c+z*z*d);
    }
}
