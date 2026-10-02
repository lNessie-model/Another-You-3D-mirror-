package com.mirror.bench;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Product correctives read original inputs; actual GLB evidence uses the production rig/deformer. */
public final class AvatarRigV2Test {
    private static int checks;
    public static void main(String[] args)throws Exception {
        exactProductsAndAtomicUpdate(); invalidManifests(); legacyContract();
        for(String arg:args)actual(Path.of(arg));
        System.out.println("AvatarRigV2Test: "+checks+" assertions passed");
    }
    private static void exactProductsAndAtomicUpdate()throws Exception {
        JSONObject config=manifest();
        config.getJSONArray("bindings").getJSONObject(2).put("gain",.5);
        AvatarRig rig=new AvatarRig(fixture(),config.toString());float[] input=new float[52],out=new float[5],matrix=new float[16];
        input[25]=.8f;input[27]=.5f;input[9]=.5f;input[21]=.25f;input[19]=.75f;
        rig.update(input,new float[3]);rig.copyMeshWeights(0,out);
        near(out[2],.4f,"direct curve remains independent");near(out[3],.4f,"product uses original jaw, not mapped jaw");
        near(out[4],.5f*.25f*.75f*.5f,"three original inputs and gain");
        rig.copyWorldMatrix(5,matrix);near(matrix[9],-(float)Math.sin(Math.toRadians(22*.8f)),"mouthClose does not suppress rigid jaw");
        input[25]=2;input[27]=.5f;input[9]=-1;input[15]=2;input[13]=-.5f;
        rig.update(input,new float[3]);rig.copyMeshWeights(0,out);
        near(out[2],.5f,"v2 clamps before direct curve");near(out[3],.5f,"v2 clamps before product");near(out[4],0,"negative input clamps to zero");
        rig.copyWorldMatrix(5,matrix);near(matrix[9],-(float)Math.sin(Math.toRadians(22)),"clamped jaw shared with joints");
        rig.copyWorldMatrix(3,matrix);near(matrix[8],(float)Math.sin(Math.toRadians(18)),"clamped gaze shared with joints");
        float[] old=out.clone(),oldMatrix=matrix.clone();input[51]=Float.NaN;
        rejects(()->rig.update(input,new float[3]),"nonfinite late input");rig.copyMeshWeights(0,out);rig.copyWorldMatrix(3,matrix);
        check(Arrays.equals(old,out)&&Arrays.equals(oldMatrix,matrix),"invalid input preserves complete committed state");
        input[51]=0;Arrays.fill(input,0);rig.update(input,new float[3]);rig.copyMeshWeights(0,out);
        for(float value:out)near(value,0,"derived weights reset each frame");
    }
    private static void invalidManifests()throws Exception {
        bad(j->j.put("schemaVersion",2.00000001),"fractional schema cannot round into v2");
        bad(j->j.getJSONArray("requiredRigFeatures").put("future-feature"),"unknown required feature");
        bad(j->j.getJSONArray("requiredRigFeatures").put("product-correctives-v1"),"duplicate required feature");
        bad(j->j.put("requiredRigFeatures","product-correctives-v1"),"feature array type");
        bad(j->j.remove("requiredRigFeatures"),"derived feature must be explicit");
        bad(j->j.put("derivedBindings",new JSONObject()),"derived array type");
        bad(j->first(j).put("operation","sum"),"unsupported operation");
        bad(j->first(j).put("gamma",2),"unknown derived key");
        bad(j->first(j).put("sources",new JSONArray().put("jawOpen")),"too few inputs");
        bad(j->first(j).put("sources",new JSONArray().put("jawOpen").put("mouthClose").put("eyeBlinkLeft").put("eyeBlinkRight")),"too many inputs");
        bad(j->first(j).put("sources",new JSONArray().put("jawOpen").put("jawOpen")),"duplicate original input");
        bad(j->first(j).put("sources",new JSONArray().put("correctiveJaw").put("mouthClose")),"recursive output name");
        bad(j->first(j).put("sources",new JSONArray().put("_neutral").put("mouthClose")),"neutral is not a geometry action");
        bad(j->first(j).put("sources",new JSONArray().put(25).put("mouthClose")),"source coercion");
        bad(j->first(j).put("target","jawOpen"),"direct/derived duplicate destination");
        bad(j->j.getJSONArray("derivedBindings").put(new JSONObject(first(j).toString())),"derived/derived duplicate destination");
        bad(j->first(j).put("target","missing"),"unknown target");
        bad(j->first(j).put("meshIndex",0),"ambiguous destination mesh");
        bad(j->first(j).put("gain",1.001),"gain exceeds documented unit-weight bounds");
        bad(j->first(j).put("gain",-1),"negative gain");
        bad(j->first(j).put("gain","1"),"gain coercion");
        bad(j->j.getJSONArray("ignoredSources").put("mouthClose"),"derived input cannot also be ignored");
        bad(j->j.put("schemaVersion",1),"v1 cannot silently ignore v2 feature fields");
    }
    private static void legacyContract()throws Exception {
        AvatarRig rig=new AvatarRig(AvatarRigTest.fixture(),AvatarRigTest.manifest().toString());float[] v=new float[52];v[25]=1.1f;
        rejects(()->rig.update(v,new float[3]),"v1 range contract unchanged");
        JSONObject unknown=AvatarRigTest.manifest();unknown.put("requiredRigFeatures",new JSONArray().put("unknown"));
        rejects(()->new AvatarRig(AvatarRigTest.fixture(),unknown.toString()),"v1 cannot ignore required features");
    }
    private static void actual(Path model)throws Exception {
        byte[] raw=Files.readAllBytes(model);AvatarAsset asset=AvatarGlbLoader.load(raw);
        JSONObject manifest=new JSONObject(Files.readString(model.resolveSibling("avatar.json")));
        AvatarRig rig=new AvatarRig(asset,manifest.toString());AvatarDeformer deformer=new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED);
        check(rig.completeSourceCoverage(),"staging retains all 51 source actions");
        int face=-1;for(int i=0;i<asset.meshes().size();i++)if(asset.meshes().get(i).name().equals("Face"))face=i;
        check(face>=0,"real face present");var mesh=asset.meshes().get(face);float[] weights=new float[mesh.targetCount()],input=new float[52],matrix=new float[16];
        int jawTarget=mesh.targetIndex("jawOpen"),closeTarget=mesh.targetIndex("mouthClose"),corrective=mesh.targetIndex("corrective_jawOpen_mouthClose");
        FloatBuffer base=mesh.primitives().get(0).positions(),jaw=mesh.primitives().get(0).morphs().get(jawTarget).positions(),close=mesh.primitives().get(0).morphs().get(closeTarget).positions();
        FloatBuffer correction=mesh.primitives().get(0).morphs().get(corrective).positions();
        int nonLipMoved=0;
        for(int k=0;k<=20;k++) {
            input[25]=k/20f;input[27]=1;rig.update(input,new float[3]);rig.copyMeshWeights(face,weights);deformer.updateMesh(face,weights);
            near(weights[jawTarget],input[25],"real jaw morph remains active");near(weights[corrective],input[25],"closed mouth product at each jaw amount");
            rig.copyWorldMatrix(rig.jawNodeIndex(),matrix);near(matrix[9],-(float)Math.sin(Math.toRadians(22*input[25])),"real jaw attachment remains active");
            FloatBuffer output=deformer.primitives(face).get(0).positions();
            for(int v=0;v<base.limit();v+=3)if(close.get(v)==0&&close.get(v+1)==0&&close.get(v+2)==0) {
                for(int axis=0;axis<3;axis++) {
                    check(correction.get(v+axis)==0,"mouth correction does not alter non-lip vertices");
                    near(output.get(v+axis),(float)(base.get(v+axis)+(double)jaw.get(v+axis)*input[25]),"chin/cheek jaw deformation preserved");
                    if(k==20&&Math.abs(jaw.get(v+axis))>1e-6)nonLipMoved++;
                }
            }
        }
        check(nonLipMoved>100,"actual mandible movement is nontrivial");
        int length=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
        JSONObject glb=new JSONObject(new String(raw,20,length,StandardCharsets.UTF_8)),groups=null;
        JSONArray meshes=glb.getJSONArray("meshes");for(int i=0;i<meshes.length();i++)if(meshes.getJSONObject(i).getString("name").equals("Face"))groups=meshes.getJSONObject(i).getJSONObject("extras").getJSONObject("validationGroups");
        check(groups!=null,"actual eye seam evidence indices present");double maxGap=0;
        for(String side:new String[]{"Left","Right"})for(int wide=0;wide<=10;wide++)for(int squint=0;squint<=10;squint++) {
            Arrays.fill(input,0);input[BlendshapeSchema.indexOf("eyeBlink"+side)]=1;
            input[BlendshapeSchema.indexOf("eyeWide"+side)]=wide/10f;input[BlendshapeSchema.indexOf("eyeSquint"+side)]=squint/10f;
            rig.update(input,new float[3]);rig.copyMeshWeights(face,weights);deformer.updateMesh(face,weights);
            FloatBuffer p=deformer.primitives(face).get(0).positions();JSONArray rim=groups.getJSONArray(side);
            for(int k=1;k<32;k++){int a=rim.getInt(k)*3,b=rim.getInt(64-k)*3;double gap=0;for(int axis=0;axis<3;axis++)gap+=Math.pow(p.get(a+axis)-p.get(b+axis),2);maxGap=Math.max(maxGap,Math.sqrt(gap));}
        }
        check(maxGap<1e-7,"actual fully closed eyes remain sealed with wide/squint products, gap="+maxGap);
        AvatarFraming framing=AvatarGeometryBounds.fromAsset(asset,rig);check(framing.scale(.625f)>0,"actual corrected asset has finite framing");
        System.out.println("Actual staging: 21 jaw/closed poses preserve non-lip jaw; 242 eye combination seams max gap="+maxGap+" m; portrait scale="+framing.scale(.625f));
    }
    private static AvatarAsset fixture() {
        AvatarAsset a=AvatarRigTest.fixture();var old=a.meshes().get(0);var p=old.primitives().get(0);var morphs=new ArrayList<>(p.morphs());
        morphs.add(new AvatarAsset.Morph(null,null));morphs.add(new AvatarAsset.Morph(null,null));
        var mesh=new AvatarAsset.Mesh("Face",List.of(new AvatarAsset.Primitive(copy(p.positions()),null,null,null,new int[]{0,1,2},morphs,0)),
                List.of("eyeBlinkLeft","eyeBlinkRight","jawOpen","correctiveJaw","correctiveEye"),new float[5]);
        return new AvatarAsset(List.of(mesh),a.nodes(),a.materials(),new int[]{0},3,1,100);
    }
    private static JSONObject manifest()throws Exception {
        JSONObject j=AvatarRigTest.manifest();j.put("schemaVersion",2).put("requiredRigFeatures",new JSONArray().put("product-correctives-v1"));
        j.put("derivedBindings",new JSONArray().put(new JSONObject("{\"operation\":\"product\",\"sources\":[\"jawOpen\",\"mouthClose\"],\"mesh\":\"Face\",\"target\":\"correctiveJaw\",\"gain\":1}"))
                .put(new JSONObject("{\"operation\":\"product\",\"sources\":[\"eyeBlinkLeft\",\"eyeWideLeft\",\"eyeSquintLeft\"],\"mesh\":\"Face\",\"target\":\"correctiveEye\",\"gain\":0.5}")));
        return j;
    }
    private static JSONObject first(JSONObject j)throws Exception{return j.getJSONArray("derivedBindings").getJSONObject(0);}
    private static float[] copy(FloatBuffer b){float[] out=new float[b.remaining()];b.get(out);return out;}
    private interface Mutation{void apply(JSONObject j)throws Exception;}
    private static void bad(Mutation action,String label)throws Exception{JSONObject j=manifest();action.apply(j);rejects(()->new AvatarRig(fixture(),j.toString()),label);}
    private interface Checked{void run()throws Exception;}
    private static void rejects(Checked action,String label)throws Exception{try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("Expected rejection: "+label);}
    private static void near(float a,float b,String label){check(Math.abs(a-b)<1e-6f,label+" expected="+b+" actual="+a);}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
}
