package com.mirror.bench;

import java.io.IOException;

public final class BundledAvatarSelectionTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private interface Checked {void run()throws Exception;}
    private static IOException rejected(Checked task,String message)throws Exception {
        try{task.run();throw new AssertionError("Accepted "+message);}catch(IOException expected){checks++;return expected;}
    }
    public static void main(String[] args)throws Exception {
        var context=new BundledCatalogTestSupport.TestContext();
        context.getSharedPreferences("mirror_runtime",0).edit().putString("existing-setting","keep").commit();
        check(!BundledAvatarSelection.hasSelection(context),"No explicit selection on upgrade");
        check(BundledAvatarSelection.load(context).equals("geralt"),"Absent choice resolves default without persisting it");
        check(!BundledAvatarSelection.hasSelection(context),"Default load must not override imported avatar");
        BundledAvatarSelection.save(context,"builtin-guide");
        check(BundledAvatarSelection.hasSelection(context)&&BundledAvatarSelection.load(context).equals("builtin-guide"),"Saved stable identity");
        rejected(()->BundledAvatarSelection.save(context,"unknown"),"unknown choice");
        rejected(()->BundledAvatarSelection.save(context,"../geralt"),"invalid choice");
        check(BundledAvatarSelection.load(context).equals("builtin-guide"),"Invalid choices preserve prior selection");
        context.selection().failedCommits=1;
        rejected(()->BundledAvatarSelection.save(context,"geralt"),"failed disk save");
        check(BundledAvatarSelection.load(context).equals("builtin-guide"),"Memory-before-disk failure restores old identity");
        check(context.selection().disk.get("selected_id").equals("builtin-guide"),"Failed save preserves disk identity");
        context.selection().failedCommits=1;
        rejected(()->BundledAvatarSelection.clear(context),"failed disk clear");
        check(BundledAvatarSelection.hasSelection(context)&&BundledAvatarSelection.load(context).equals("builtin-guide"),"Failed clear preserves explicitness and id");
        context.selection().failedCommits=2;
        IOException failure=rejected(()->BundledAvatarSelection.save(context,"geralt"),"save plus rollback disk failure");
        check(failure.getSuppressed().length>0,"Rollback persistence failure remains observable");
        check(BundledAvatarSelection.load(context).equals("builtin-guide"),"Double disk failure restores visible old memory");
        BundledAvatarSelection.clear(context);
        check(!BundledAvatarSelection.hasSelection(context)&&BundledAvatarSelection.load(context).equals("geralt"),"Clear restores implicit default");
        context.selection().failedCommits=1;
        rejected(()->BundledAvatarSelection.save(context,"builtin-guide"),"failed first explicit choice");
        check(!BundledAvatarSelection.hasSelection(context),"Failed initial save does not create explicit selection");
        check(BundledAvatarSelection.load(context).equals("geralt"),"Failed initial save keeps default");
        context.selection().memory.put("selected_id","removed-avatar");
        rejected(()->BundledAvatarSelection.load(context),"saved identity removed from catalog");
        check(context.selection().memory.get("selected_id").equals("removed-avatar"),"Missing avatar is not silently relabeled");
        BundledAvatarSelection.clear(context);
        check(!BundledAvatarSelection.hasSelection(context),"Explicit clear can recover a removed string identity");
        check(context.getSharedPreferences("mirror_runtime",0).getString("existing-setting","").equals("keep"),"Existing runtime preferences untouched");
        rejected(()->BundledAvatarSelection.save(null,"geralt"),"null context");
        rejected(()->BundledAvatarSelection.save(context,null),"null choice");
        System.out.println("BundledAvatarSelectionTest PASS "+checks+" checks");
    }
}
