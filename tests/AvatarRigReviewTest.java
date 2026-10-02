package com.mirror.bench;

import java.util.ArrayList;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent adverse fixtures and numeric expectations; no GL/native mocks or device ownership. */
public final class AvatarRigReviewTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        rejectHiddenControlsAndRoundedIndices();
        rejectDoubleDrivenJawMesh();
        bindingCurveAndAtomicFailures();
        matrixHierarchyAndRigidAttachments();
        fullSnapshotIsolationAndRecovery();
        completePostOutputValidation();
        System.out.println("AvatarRigReviewTest: "+checks+" assertions passed");
    }
    private static void rejectHiddenControlsAndRoundedIndices() throws Exception {
        AvatarAsset asset=AvatarRigTest.fixture();
        JSONObject bad=AvatarRigTest.manifest();bad.getJSONObject("rig").put("mirror",true);
        rejectManifest(asset,bad,"unknown rig operation");
        bad=AvatarRigTest.manifest();bad.getJSONObject("coordinates").put("mirrorInput",true);
        rejectManifest(asset,bad,"unknown coordinate conversion");
        bad=AvatarRigTest.manifest();bad.put("materialProfile","unsupported-pbr");
        rejectManifest(asset,bad,"unsupported material profile");
        bad=AvatarRigTest.manifest();bad.getJSONArray("bindings").getJSONObject(0).remove("target");
        bad.getJSONArray("bindings").getJSONObject(0).put("targetIndex",0.000000000000000000000000000000000000000000000000001);
        rejectManifest(asset,bad,"fractional index rounds to float zero");
        bad=AvatarRigTest.manifest();bad.getJSONArray("bindings").getJSONObject(1).remove("target");
        bad.getJSONArray("bindings").getJSONObject(1).put("targetIndex",1.00000001);
        rejectManifest(asset,bad,"fractional index rounds to float one");
        bad=AvatarRigTest.manifest();bad.put("ignoredSources",new JSONObject());
        rejectManifest(asset,bad,"mistyped ignored sources must not disappear");
        bad=AvatarRigTest.manifest();bad.getJSONObject("rig").put("gazeMode","morph");
        rejectManifest(asset,bad,"morph mode cannot silently ignore joint controls");
    }
    private static void rejectDoubleDrivenJawMesh() throws Exception {
        AvatarAsset base=AvatarRigTest.fixture();var nodes=new ArrayList<>(base.nodes());
        var head=nodes.get(1);var jaw=nodes.get(5);var face=nodes.get(6);
        nodes.set(1,new AvatarAsset.Node("Head",-1,new int[]{3,4,5},copy(head.localMatrix()),copy(head.worldMatrix()),new float[0]));
        nodes.set(5,new AvatarAsset.Node("JawAttachments",-1,new int[]{6},copy(jaw.localMatrix()),copy(jaw.worldMatrix()),new float[0]));
        float[] world=copy(face.worldMatrix());world[13]=.25f;
        nodes.set(6,new AvatarAsset.Node("Face",0,new int[0],copy(face.localMatrix()),world,new float[0]));
        AvatarAsset doubleDrive=new AvatarAsset(base.meshes(),nodes,base.materials(),new int[]{0},3,1,100);
        rejectManifest(doubleDrive,AvatarRigTest.manifest(),"same face vertices receive jaw morph and rigid rotation");
    }
    private static void bindingCurveAndAtomicFailures() throws Exception {
        JSONObject config=AvatarRigTest.manifest();JSONObject binding=config.getJSONArray("bindings").getJSONObject(0);
        binding.put("deadZone",.25).put("gamma",2).put("gain",2).put("bias",.1).put("min",.2).put("max",.8);
        AvatarRig rig=new AvatarRig(AvatarRigTest.fixture(),config.toString());float[] w=new float[52],out=new float[3];
        rig.copyMeshWeights(0,out);near(out[0],.2f,"neutral below min clamps");
        w[9]=.625f;rig.update(w,new float[3]);rig.copyMeshWeights(0,out);near(out[0],.6f,"((.625-.25)/.75)^2*2+.1");
        w[9]=1;rig.update(w,new float[3]);rig.copyMeshWeights(0,out);near(out[0],.8f,"maximum clamp");
        float[] prior=out.clone(),matrix=new float[16];rig.copyWorldMatrix(3,matrix);float[] priorMatrix=matrix.clone();
        w[51]=Float.NaN;rejects(()->rig.update(w,new float[3]),"late input validation");
        rig.copyMeshWeights(0,out);equal(out,prior,"weights atomic on invalid last input");
        rig.copyWorldMatrix(3,matrix);equal(matrix,priorMatrix,"transforms atomic on invalid input");
        w[51]=0;rejects(()->rig.update(w,new float[]{0,Float.POSITIVE_INFINITY,0}),"invalid pose");
        rig.copyMeshWeights(0,out);equal(out,prior,"weights atomic on invalid pose");
        out[0]=0;rig.copyMeshWeights(0,out);near(out[0],.8f,"copy output cannot mutate rig");
    }
    private static void matrixHierarchyAndRigidAttachments() throws Exception {
        AvatarRig rig=new AvatarRig(AvatarRigTest.fixture(),AvatarRigTest.manifest().toString());
        float[] values=new float[52],matrix=new float[16];
        rig.update(values,new float[]{0,0,90});rig.copyWorldMatrix(3,matrix);
        near(matrix[12],-.1f,"head roll rotates eye x about head pivot");
        near(matrix[13],.24f,"head translation remains outside rotation");
        near(matrix[14],.06f,"roll preserves eye z");
        rig.copyWorldMatrix(2,matrix);near(matrix[0],1,"shoulders stationary");near(matrix[13],0,"shoulders position stationary");
        values[24]=1;values[23]=.5f;values[25]=1;rig.update(values,new float[3]);rig.copyWorldMatrix(5,matrix);
        near(matrix[12],.00845f,"jaw anatomical left is +X");near(matrix[14],.0039f,"jaw forward +Z");
        near(matrix[13],.25f,"jaw pivot keeps head ancestry");near(matrix[9],-(float)Math.sin(Math.toRadians(22)),"jaw rotation sign");
        values[24]=0;values[26]=1;rig.update(values,new float[3]);rig.copyWorldMatrix(5,matrix);near(matrix[12],-.00845f,"jaw right is -X");
    }
    private static void fullSnapshotIsolationAndRecovery() {
        InteractionController c=new InteractionController();float[] full=new float[52],pose=FaceFrame.identity();
        for(int i=0;i<52;i++)full[i]=(i+1)/52f;
        c.accept(FaceFrame.present(0,0,0,full,pose));c.sample(0);
        pose[12]=.123f;c.accept(FaceFrame.present(1,250_000_000,260_000_000,full,pose));
        var snapshot=c.sample(270_000_000);float alpha=(float)-Math.expm1(-270_000_000/70_000_000.0);
        float[] mapped=snapshot.blendshapes52();
        for(int i=0;i<52;i++)near(mapped[i],full[i]*alpha,"all-channel propagation "+i);
        near(snapshot.pose()[12],.123f,"pose and coefficients share result");
        check(snapshot.sequence()==1&&snapshot.completedNs()==260_000_000,"frame metadata");
        Arrays.fill(full,0);Arrays.fill(pose,0);Arrays.fill(mapped,0);
        near(snapshot.blendshapes52()[51],alpha,"snapshot survives producer/consumer mutation");
        c.setError();var failed=c.sample(420_000_000);
        near(failed.blendshapes52()[51],alpha*(float)Math.exp(-1),"all channels fade on error");
        check(failed.sequence()==-1&&!failed.facePresent(),"error has no false source metadata");
        c.clearError();for(float value:c.sample(421_000_000).blendshapes52())near(value,0,"clearError neutral");
        check(!c.accept(FaceFrame.present(1,422_000_000,422_000_000,new float[52],FaceFrame.identity())),"late old sequence rejected after recovery");
    }
    private static void completePostOutputValidation() throws Exception {
        float[] weights=new float[52],pose=FaceFrame.identity(),landmarks=new float[1434];
        weights[51]=.75f;landmarks[1433]=.25f;
        float[][] output=BlendshapeSchema.copyValidatedPostOutput(weights,pose,landmarks);
        weights[51]=.5f;pose[12]=8;landmarks[1433]=3;
        near(output[0][51],.75f,"post output owns weights");near(output[1][12],0,"post output owns pose");
        near(output[2][1433],.25f,"post output owns landmarks");
        rejects(()->BlendshapeSchema.copyValidatedPostOutput(weights,new float[15],landmarks),"missing pose");
        rejects(()->BlendshapeSchema.copyValidatedPostOutput(weights,pose,new float[1433]),"missing landmark");
        pose[15]=Float.NaN;
        rejects(()->BlendshapeSchema.copyValidatedPostOutput(weights,pose,landmarks),"last pose value invalid");
        pose[15]=1;landmarks[1433]=Float.POSITIVE_INFINITY;
        rejects(()->BlendshapeSchema.copyValidatedPostOutput(weights,pose,landmarks),"last landmark invalid");
        landmarks[1433]=0;weights[51]=1.001f;
        rejects(()->BlendshapeSchema.copyValidatedPostOutput(weights,pose,landmarks),"coefficient outside sigmoid range");
        near(output[0][51],.75f,"failed new output cannot mutate old output");
    }
    private static float[] copy(java.nio.FloatBuffer b){float[] a=new float[b.remaining()];b.get(a);return a;}
    private static void rejectManifest(AvatarAsset a,JSONObject j,String label)throws Exception{String text=j.toString();rejects(()->new AvatarRig(a,text),label);}
    private interface Checked {void run()throws Exception;}
    private static void rejects(Checked action,String label)throws Exception{try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("Expected rejection: "+label);}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static void near(float actual,float expected,String label){check(Math.abs(actual-expected)<1e-5f,label+" expected "+expected+" got "+actual);}
    private static void equal(float[] actual,float[] expected,String label){check(Arrays.equals(actual,expected),label);}
}
