package com.mirror.bench;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Metadata and small previews only. The selected runtime owns GLB decoding and hardware. */
public final class MirrorRolesActivity extends Activity {
    private final ExecutorService metadata=Executors.newSingleThreadExecutor();
    private final List<Button> choices=new ArrayList<>();
    private final List<Button> navigation=new ArrayList<>();
    private LinearLayout gallery;
    private TextView status;
    private Button enter;
    private int generation;
    private boolean saving;
    private record Preview(BundledAvatarCatalog.Entry entry,Bitmap bitmap,String warning) {}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);MirrorTheme.immersive(this);
        var root=MirrorTheme.page(this);LinearLayout body=MirrorTheme.safeScroll(this,root);
        body.addView(MirrorTheme.text(this,"镜中角色",25,true));
        status=MirrorTheme.text(this,"正在读取角色…",13,false);body.addView(status);
        enter=MirrorTheme.button(this,"进入魔镜",true,this::openMirror);MirrorTheme.addButton(body,enter);
        Button library=MirrorTheme.button(this,"素材库",false,
                ()->startActivity(new Intent(this,AssetLibraryActivity.class)));navigation.add(library);MirrorTheme.addButton(body,library);
        gallery=new LinearLayout(this);gallery.setOrientation(LinearLayout.VERTICAL);body.addView(gallery);
        Button manage=MirrorTheme.button(this,"导入与管理本地角色",false,
                ()->startActivity(new Intent(this,AvatarManagementActivity.class)));navigation.add(manage);MirrorTheme.addButton(body,manage);
        Button back=MirrorTheme.button(this,"返回",false,this::finish);navigation.add(back);MirrorTheme.addButton(body,back);
        TextView note=MirrorTheme.text(this,"旧版参考角色保留原有绑定\n其他角色校正完成后将加入这里",12,false);
        note.setTextColor(MirrorTheme.MUTED);body.addView(note);setContentView(root);
    }
    @Override protected void onResume(){super.onResume();reload();}
    private void reload(){
        final int token=++generation;status.setText("正在读取角色…");enter.setEnabled(false);
        metadata.execute(()->{
            List<Preview> previews=new ArrayList<>();String current;boolean valid=false;
            try{
                var entries=BundledAvatarCatalog.read(getAssets());
                for(var entry:entries){
                    Bitmap bitmap=null;String warning="";
                    try{bitmap=thumbnail(entry.thumbnail);}catch(Exception failure){warning="预览暂时不可用";}
                    previews.add(new Preview(entry,bitmap,warning));
                }
                try{current=currentRole(entries);valid=true;}catch(Exception failure){current="无法读取所选角色，请选择下方角色修复";}
            }catch(Exception failure){current="角色目录暂时不可用，请返回首页后重试";}
            final String message=current;final boolean selectionValid=valid;
            runOnUiThread(()->{
                if(token!=generation||isFinishing()||isDestroyed()){for(var preview:previews)if(preview.bitmap!=null)preview.bitmap.recycle();return;}
                gallery.removeAllViews();choices.clear();status.setText(message);enter.setEnabled(selectionValid&&!previews.isEmpty());
                for(var preview:previews)addRole(preview);
            });
        });
    }
    private String currentRole(List<BundledAvatarCatalog.Entry> entries)throws IOException{
        if(!BundledAvatarSelection.hasSelection(this)&&AvatarPackageStore.supportsAndroidApi(Build.VERSION.SDK_INT)){
            var catalog=AvatarPackageStore.open(new File(getFilesDir(),"avatars"),Build.VERSION.SDK_INT).readCatalog();
            if(catalog.current!=null)return "当前："+catalog.current.displayName+" · 本地导入";
        }
        var entry=BundledAvatarCatalog.find(entries,BundledAvatarSelection.load(this));
        return "当前："+entry.displayName+("legacy_reference".equals(entry.status)?" · 旧版参考":"");
    }
    private Bitmap thumbnail(String path)throws IOException{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream input=getAssets().open(path)){BitmapFactory.decodeStream(input,null,bounds);}
        if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>16384||bounds.outHeight>16384)throw new IOException("Invalid role preview");
        BitmapFactory.Options decode=new BitmapFactory.Options();decode.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/decode.inSampleSize>512)decode.inSampleSize*=2;
        decode.inPreferredConfig=Bitmap.Config.ARGB_8888;
        try(InputStream input=getAssets().open(path)){
            Bitmap bitmap=BitmapFactory.decodeStream(input,null,decode);if(bitmap==null)throw new IOException("Cannot decode role preview");return bitmap;
        }
    }
    private void addRole(Preview preview){
        var entry=preview.entry;LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        int padding=MirrorTheme.dp(this,12);card.setPadding(padding,padding,padding,padding);card.setBackground(MirrorTheme.surface(this,MirrorTheme.SURFACE));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.topMargin=MirrorTheme.dp(this,12);gallery.addView(card,layout);
        if(preview.bitmap!=null){ImageView image=new ImageView(this);image.setImageBitmap(preview.bitmap);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(entry.displayName+"角色预览");card.addView(image,new LinearLayout.LayoutParams(-1,MirrorTheme.dp(this,170)));}
        card.addView(MirrorTheme.text(this,entry.displayName,20,true));
        String kind="device_verified".equals(entry.status)?"已完成本机验证":("corrected_trial".equals(entry.status)?"已校正 · 可试用":("legacy_reference".equals(entry.status)?"旧版参考 · 原有绑定":"待校正角色"));
        TextView detail=MirrorTheme.text(this,kind+(preview.warning.isEmpty()?"":"\n"+preview.warning),12,false);detail.setTextColor(MirrorTheme.MUTED);card.addView(detail);
        Button choose=MirrorTheme.button(this,"选择"+entry.displayName,false,()->choose(entry));choices.add(choose);MirrorTheme.addButton(card,choose);
    }
    private void choose(BundledAvatarCatalog.Entry entry){
        if(saving)return;saving=true;for(Button button:choices)button.setEnabled(false);for(Button button:navigation)button.setEnabled(false);enter.setEnabled(false);
        status.setText("正在保存："+entry.displayName+"…");
        metadata.execute(()->{
            String message;boolean success;
            try{BundledAvatarSelection.save(this,entry.id);success=true;message="已保存选择："+entry.displayName+"\n点击进入魔镜即可使用";}
            catch(Exception failure){success=false;message="选择未保存，原角色保持。请重试。";}
            final boolean saved=success;final String result=message;
            runOnUiThread(()->{saving=false;if(isFinishing()||isDestroyed())return;status.setText(result);for(Button button:choices)button.setEnabled(true);for(Button button:navigation)button.setEnabled(true);enter.setEnabled(saved);});
        });
    }
    @Override public void onBackPressed(){
        if(saving){android.widget.Toast.makeText(this,"正在保存选择，请稍候。",android.widget.Toast.LENGTH_SHORT).show();return;}
        super.onBackPressed();
    }
    private void openMirror(){
        if(saving)return;
        if(getIntent().getBooleanExtra("from_mirror",false))setResult(RESULT_OK);
        else startActivity(new Intent(this,MirrorActivity.class).putExtra(MirrorHomeActivity.PRODUCT_ACTION,"mirror"));
        // Returning a result lets the old runtime recreate without leaving a second one behind it.
        finish();
    }
    @Override protected void onPause(){generation++;super.onPause();}
    @Override protected void onDestroy(){generation++;metadata.shutdown();super.onDestroy();}
}
