package com.mirror.bench;

import org.json.JSONArray;
import org.json.JSONObject;

/** Fixture/report plumbing using the host Bitmap fake, not Android pixel evidence. */
public final class CameraTransformFixtureTest {
    public static void main(String[] args) throws Exception {
        JSONObject result=CameraTransformCheckActivity.checkBitmaps();
        expect(result.getBoolean("passed"),"fixture failed: "+result);
        expect(result.getInt("case_count")==8,"eight distinct orientations");
        expect(result.getInt("assertions")==344,"all ownership and pixel assertions executed");
        expect(result.getInt("failure_count")==0,"no fixture failures");
        JSONArray rows=result.getJSONArray("cases");
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i);
            expect(row.getInt("clockwise_degrees")==(i/2)*90,"angle order");
            expect(row.getBoolean("reflect_after_rotation")==((i&1)==1),"reflection order");
            expect(row.getBoolean("source_alias")==(i==0),"identity source alias");
            expect(row.getBoolean("output_reused"),"output reuse");
            expect(!row.getBoolean("source_recycled_after_close"),"source ownership");
            expect(row.getBoolean("output_recycled_after_close")!=(i==0),"output ownership");
            for(String field:new String[]{"first_actual_argb","second_actual_argb"}) {
                JSONArray pixels=row.getJSONArray(field);
                expect(pixels.length()==6,"six pixels");
                for(int p=0;p<6;p++)expect(pixels.getLong(p)>>>24==255,"fully opaque fixture color");
            }
        }
        // Make an explicit oracle incorrect to prove a mismatch cannot report PASS.
        java.lang.reflect.Field field=CameraTransformCheckActivity.class.getDeclaredField("ORACLES");
        field.setAccessible(true);
        int[][] oracles=(int[][])field.get(null);
        int original=oracles[2][0];
        try {
            oracles[2][0]=0;
            JSONObject failed=CameraTransformCheckActivity.checkBitmaps();
            expect(!failed.getBoolean("passed"),"pixel mismatch must fail top-level report");
            expect(failed.getInt("failure_count")==2,"both changed-color passes detect mismatch");
            expect(!failed.getJSONArray("cases").getJSONObject(2).getBoolean("passed"),"case failure preserved");
        } finally {oracles[2][0]=original;}
        System.out.println("CameraTransformFixtureTest: "+assertions+" host assertions; Android check still required");
    }
    private static int assertions;
    private static void expect(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
