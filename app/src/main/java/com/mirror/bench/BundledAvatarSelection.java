package com.mirror.bench;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;

/** Independent explicit bundled choice. Call disk-reading/writing methods on a background worker. */
public final class BundledAvatarSelection {
    private static final String PREFERENCES="bundled_avatar_selection_v1",KEY="selected_id";
    private BundledAvatarSelection(){}

    /** An implicit default does not supersede an existing imported avatar on upgrade. */
    public static synchronized boolean hasSelection(Context context)throws IOException {
        try{return preferences(context).contains(KEY);}
        catch(RuntimeException failure){throw new IOException("Cannot read bundled role selection",failure);}
    }
    /** Missing key resolves the catalog default; a saved invalid/removed identity is an error. */
    public static synchronized String load(Context context)throws IOException {
        SharedPreferences preferences=preferences(context);
        Snapshot state=snapshot(preferences);
        BundledAvatarCatalog.Parsed catalog=BundledAvatarCatalog.parse(context.getAssets());
        return BundledAvatarCatalog.find(catalog.entries,state.present?state.value:catalog.defaultId).id;
    }
    public static synchronized void save(Context context,String id)throws IOException {
        SharedPreferences preferences=preferences(context);
        BundledAvatarCatalog.find(BundledAvatarCatalog.read(context.getAssets()),id);
        change(preferences,snapshot(preferences),id,false);
    }
    /** Clearing after explicit import restores imported-store precedence; it does not delete a package. */
    public static synchronized void clear(Context context)throws IOException {
        SharedPreferences preferences=preferences(context);
        change(preferences,snapshot(preferences),null,true);
    }

    private static SharedPreferences preferences(Context context)throws IOException {
        if(context==null)throw new IOException("Application context is required for role selection");
        try {
            SharedPreferences preferences=context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE);
            if(preferences==null)throw new IOException("Bundled role preferences are unavailable");
            return preferences;
        }catch(RuntimeException failure){throw new IOException("Cannot open bundled role preferences",failure);}
    }
    private static final class Snapshot {
        final boolean present;final String value;
        Snapshot(boolean present,String value){this.present=present;this.value=value;}
    }
    private static Snapshot snapshot(SharedPreferences preferences)throws IOException {
        try {
            boolean present=preferences.contains(KEY);
            String value=present?preferences.getString(KEY,null):null;
            if(present&&value==null)throw new IOException("Saved bundled role identity is corrupt");
            return new Snapshot(present,value);
        }catch(RuntimeException failure){throw new IOException("Saved bundled role identity is not text",failure);}
    }
    private static void change(SharedPreferences preferences,Snapshot before,String id,boolean clear)throws IOException {
        IOException failure;
        try {
            SharedPreferences.Editor editor=preferences.edit();
            if(clear)editor.remove(KEY);else editor.putString(KEY,id);
            if(editor.commit())return;
            failure=new IOException("Bundled role selection was not saved");
        }catch(RuntimeException cause){failure=new IOException("Bundled role selection was not saved",cause);}
        // SharedPreferences.commit() publishes in memory even when its disk result is false.
        // Restore that visible state as well as attempting to rewrite the previous disk value.
        try {
            SharedPreferences.Editor rollback=preferences.edit();
            if(before.present)rollback.putString(KEY,before.value);else rollback.remove(KEY);
            if(!rollback.commit())failure.addSuppressed(new IOException("Previous role selection disk rewrite failed"));
        }catch(RuntimeException rollback){failure.addSuppressed(new IOException("Cannot restore previous role selection",rollback));}
        try {
            Snapshot restored=snapshot(preferences);
            if(restored.present!=before.present||(before.present&&!before.value.equals(restored.value)))
                failure.addSuppressed(new IOException("Previous role selection could not be restored in memory"));
        }catch(IOException inspection){failure.addSuppressed(inspection);}
        throw failure;
    }
}
