package com.mirror.bench;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Browsing settings owns no camera, inference worker or GL surface. Editors retain their original draft transactions. */
public final class MirrorSettingsActivity extends Activity {
    static final String RETURN_TO_SETTINGS="return_to_settings";
    private AlertDialog about;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);MirrorTheme.immersive(this);
        var root=MirrorTheme.page(this);LinearLayout body=MirrorTheme.safeScroll(this,root);
        body.addView(MirrorTheme.text(this,"设置",25,true));
        note(body,"调整镜中的自己",14);
        MirrorTheme.addButton(body,MirrorTheme.button(this,"画面、镜像与表情",false,()->openEditor("scene")));
        MirrorTheme.addButton(body,MirrorTheme.button(this,"相机与动作校准",false,()->openEditor("camera")));
        note(body,"进入魔镜预览，完成后返回设置",12);
        MirrorTheme.addButton(body,MirrorTheme.button(this,"屏幕与运行参数（高级）",false,()->openEditor("maintenance")));
        MirrorTheme.addButton(body,MirrorTheme.button(this,"关于另一个你",false,this::showAbout));
        MirrorTheme.addButton(body,MirrorTheme.button(this,"返回首页",true,this::finish));
        setContentView(root);
    }
    private void note(LinearLayout body,String value,int size){
        TextView text=MirrorTheme.text(this,value,size,false);text.setTextColor(MirrorTheme.MUTED);body.addView(text);
    }
    private void openEditor(String action){
        startActivity(new Intent(this,MirrorActivity.class)
                .putExtra(MirrorHomeActivity.PRODUCT_ACTION,action).putExtra(RETURN_TO_SETTINGS,true));
    }
    private void showAbout(){
        if(about!=null)return;
        String version;
        try{
            var info=getPackageManager().getPackageInfo(getPackageName(),0);
            version="版本 "+info.versionName+"（"+info.versionCode+"）";
        }catch(PackageManager.NameNotFoundException unavailable){version="版本信息暂不可用";}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Another You / 另一个你")
                .setMessage("镜中的角色，用表情回应你。\n\n"+version
                        +"\n\n相机画面用于本机互动。个人中性基准只用于本次互动，安装与画面设置在明确保存后保留。"
                        +"\n\n更多角色正在逐项校正。")
                .setPositiveButton("返回",null).create();
        about=dialog;dialog.setOnDismissListener(d->{if(about==dialog)about=null;});
        dialog.show();MirrorTheme.safeDialog(this,dialog);
    }
    @Override protected void onPause(){
        if(about!=null)about.dismiss();super.onPause();
    }
}
