package com.mirror.bench;

import android.content.Context;
import android.content.SharedPreferences;

/** Separate versioned component; loading and draft preview never edit existing runtime calibration. */
final class SceneViewPreferences {
    private SceneViewPreferences(){}
    static final class Loaded {
        final SceneViewSettings value;final boolean writable;final String warning;
        Loaded(SceneViewSettings value,boolean writable,String warning){this.value=value;this.writable=writable;this.warning=warning;}
    }
    private static SharedPreferences preferences(Context context){return context.getSharedPreferences("mirror-scene-view",Context.MODE_PRIVATE);}
    static Loaded load(Context context){
        var map=preferences(context).getAll();
        try{return new Loaded(SceneViewSettings.fromMap(map),true,"");}
        catch(IllegalArgumentException failure){
            boolean known=map.get("schema_version") instanceof Integer version&&version==1;
            return new Loaded(SceneViewSettings.DEFAULT,known,"原画面配置未覆盖："+failure.getMessage());
        }
    }
    static void save(Context context,SceneViewSettings value){
        if(value==null||!load(context).writable)throw new IllegalArgumentException("原画面配置受保护，未保存");
        var edit=preferences(context).edit();
        for(var entry:value.toMap().entrySet()){
            if(entry.getValue() instanceof Float number)edit.putFloat(entry.getKey(),number);
            else if(entry.getValue() instanceof Boolean flag)edit.putBoolean(entry.getKey(),flag);
            else edit.putInt(entry.getKey(),(Integer)entry.getValue());
        }
        if(!edit.commit())throw new IllegalStateException("画面配置写入失败，未报告保存成功");
    }
}
