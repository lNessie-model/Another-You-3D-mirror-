package com.mirror.bench;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Browsing is read-only. Only an explicit use action persists a role; no model or hardware loading. */
public final class MirrorRolesActivity extends Activity {
    private final ExecutorService metadata=Executors.newSingleThreadExecutor(r->new Thread(r,"MirrorRoleMetadata"));
    private List<BundledAvatarCatalog.Entry> entries=List.of();
    private TextView status,name,detail,count,placeholder;
    private ImageView image;
    private Button previous,next,use,enter,library,manage,back;
    private Bitmap bitmap;
    private String browsedId,currentId;
    private int generation,index;
    private volatile int previewGeneration;
    private boolean loading,saving,selectionValid;
    private record Selection(String id,String message) {}

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);MirrorTheme.immersive(this);
        if(saved!=null)browsedId=saved.getString("browsed_role");
        var root=MirrorTheme.page(this);LinearLayout body=MirrorTheme.safeScroll(this,root);
        LinearLayout heading=row(body);
        back=MirrorTheme.button(this,"返回",false,this::onBackPressed);back.setSingleLine(true);
        heading.addView(back,new LinearLayout.LayoutParams(MirrorTheme.dp(this,88),-2));
        heading.addView(MirrorTheme.text(this,"镜中角色",25,true),new LinearLayout.LayoutParams(0,-2,1));
        status=MirrorTheme.text(this,"正在读取角色…",12,false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(status);
        FrameLayout preview=new FrameLayout(this);preview.setBackground(MirrorTheme.surface(this,MirrorTheme.SURFACE));
        image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.addView(image,new FrameLayout.LayoutParams(-1,-1));
        placeholder=MirrorTheme.text(this,"正在载入角色预览…",13,false);
        preview.addView(placeholder,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        body.addView(preview,new LinearLayout.LayoutParams(-1,MirrorTheme.dp(this,156)));
        name=MirrorTheme.text(this,"",20,true);body.addView(name);
        detail=MirrorTheme.text(this,"",12,false);detail.setTextColor(MirrorTheme.MUTED);body.addView(detail);
        LinearLayout browse=row(body);
        previous=MirrorTheme.button(this,"上一位",false,()->browse(-1));
        next=MirrorTheme.button(this,"下一位",false,()->browse(1));
        count=MirrorTheme.text(this,"",12,false);count.setTextColor(MirrorTheme.MUTED);
        browse.addView(previous,new LinearLayout.LayoutParams(0,-2,1));
        browse.addView(count,new LinearLayout.LayoutParams(MirrorTheme.dp(this,56),-2));
        browse.addView(next,new LinearLayout.LayoutParams(0,-2,1));
        use=MirrorTheme.button(this,"使用此角色",true,this::choose);MirrorTheme.addButton(body,use);
        enter=MirrorTheme.button(this,"进入魔镜",false,this::openMirror);MirrorTheme.addButton(body,enter);
        LinearLayout resources=row(body);
        library=MirrorTheme.button(this,"素材库",false,()->startActivity(new Intent(this,AssetLibraryActivity.class)));
        manage=MirrorTheme.button(this,"本地角色",false,()->startActivity(new Intent(this,AvatarManagementActivity.class)));
        resources.addView(library,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.leftMargin=MirrorTheme.dp(this,8);
        resources.addView(manage,right);
        TextView note=MirrorTheme.text(this,"浏览不会更换角色，点击“使用”后生效\n更多角色将在校正完成后加入",11,false);
        note.setTextColor(MirrorTheme.MUTED);body.addView(note);
        setControls();setContentView(root);
    }
    private LinearLayout row(LinearLayout parent){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=MirrorTheme.dp(this,8);parent.addView(row,p);return row;
    }
    @Override protected void onResume(){super.onResume();reload();}
    private void reload(){
        final int token=++generation;loading=true;status.setText("正在读取角色…");setControls();
        metadata.execute(()->{
            List<BundledAvatarCatalog.Entry> loaded=List.of();Selection selected=null;String error="";
            try{
                loaded=BundledAvatarCatalog.read(getAssets());
                try{selected=currentRole(loaded);}catch(Exception failure){error="无法读取当前角色，请重新选择";}
            }catch(Exception failure){error="角色目录暂时不可用，请返回后重试";}
            final var catalog=loaded;final var selection=selected;final String failure=error;
            runOnUiThread(()->{
                if(token!=generation||isFinishing()||isDestroyed())return;
                loading=false;entries=catalog;selectionValid=selection!=null;currentId=selection==null?null:selection.id;
                status.setText(selection==null?failure:selection.message);
                if(entries.isEmpty()){
                    clearBitmap();name.setText("暂无可浏览角色");detail.setText("");count.setText("0 / 0");
                    placeholder.setText("预览暂时不可用");placeholder.setVisibility(View.VISIBLE);setControls();return;
                }
                index=findIndex(browsedId);if(index<0)index=findIndex(currentId);if(index<0)index=0;showRole();
            });
        });
    }
    private int findIndex(String id){
        if(id!=null)for(int i=0;i<entries.size();i++)if(entries.get(i).id.equals(id))return i;return -1;
    }
    private Selection currentRole(List<BundledAvatarCatalog.Entry> catalog)throws IOException{
        if(!BundledAvatarSelection.hasSelection(this)&&AvatarPackageStore.supportsAndroidApi(Build.VERSION.SDK_INT)){
            var imported=AvatarPackageStore.open(new File(getFilesDir(),"avatars"),Build.VERSION.SDK_INT).readCatalog();
            if(imported.current!=null)return new Selection(null,"当前使用："+imported.current.displayName+" · 本地角色");
        }
        var entry=BundledAvatarCatalog.find(catalog,BundledAvatarSelection.load(this));
        return new Selection(entry.id,"当前使用："+entry.displayName);
    }
    private void browse(int direction){
        if(loading||saving||entries.isEmpty())return;
        int target=index+direction;if(target<0||target>=entries.size())return;index=target;showRole();
    }
    private void showRole(){
        var entry=entries.get(index);browsedId=entry.id;
        name.setText(entry.displayName);count.setText((index+1)+" / "+entries.size());
        count.setContentDescription("第"+(index+1)+"位，共"+entries.size()+"位角色");
        String kind=switch(entry.status){
            case "device_verified"->"已完成本机验证";
            case "corrected_trial"->"已校正 · 可试用";
            case "legacy_reference"->"旧版参考 · 原有绑定";
            default->"待校正角色";
        };
        detail.setText(kind);clearBitmap();placeholder.setText("正在载入角色预览…");placeholder.setVisibility(View.VISIBLE);
        image.setContentDescription(entry.displayName+"角色预览");setControls();
        final int token=++previewGeneration;
        metadata.execute(()->{
            if(token!=previewGeneration)return;
            Bitmap decoded=null;
            try{decoded=thumbnail(entry.thumbnail);}catch(Exception failure){/* Browsing remains available without a thumbnail. */}
            final Bitmap preview=decoded;
            runOnUiThread(()->{
                if(token!=previewGeneration||isFinishing()||isDestroyed()){if(preview!=null)preview.recycle();return;}
                bitmap=preview;image.setImageBitmap(preview);
                if(preview!=null)MirrorMotion.previewReady(image);
                placeholder.setText("预览暂时不可用");placeholder.setVisibility(preview==null?View.VISIBLE:View.GONE);
            });
        });
    }
    private Bitmap thumbnail(String path)throws IOException{
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream input=getAssets().open(path)){BitmapFactory.decodeStream(input,null,bounds);}
        if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>16384||bounds.outHeight>16384)throw new IOException("Invalid role preview");
        BitmapFactory.Options decode=new BitmapFactory.Options();decode.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/decode.inSampleSize>512)decode.inSampleSize*=2;
        decode.inPreferredConfig=Bitmap.Config.ARGB_8888;
        try(InputStream input=getAssets().open(path)){
            Bitmap result=BitmapFactory.decodeStream(input,null,decode);if(result==null)throw new IOException("Cannot decode role preview");return result;
        }
    }
    private void clearBitmap(){MirrorMotion.reset(image);image.setImageDrawable(null);if(bitmap!=null){bitmap.recycle();bitmap=null;}}
    private void setControls(){
        boolean ready=!loading&&!saving&&!entries.isEmpty();
        previous.setEnabled(ready&&index>0);next.setEnabled(ready&&index+1<entries.size());
        boolean selected=ready&&entries.get(index).id.equals(currentId);
        String label=selected?"正在使用此角色":ready?"使用"+entries.get(index).displayName:"使用此角色";
        use.setText(label);use.setContentDescription(label);use.setEnabled(ready&&!selected);
        enter.setEnabled(!loading&&!saving&&selectionValid);
        library.setEnabled(!saving);manage.setEnabled(!saving);back.setEnabled(!saving);
    }
    private void choose(){
        if(loading||saving||entries.isEmpty())return;
        var entry=entries.get(index);if(entry.id.equals(currentId))return;
        final int token=generation;final var catalog=entries;saving=true;setControls();status.setText("正在保存："+entry.displayName+"…");
        metadata.execute(()->{
            boolean success=false;Selection selection=null;
            try{BundledAvatarSelection.save(this,entry.id);success=true;}catch(Exception failure){/* Inspect actual state even if rollback failed. */}
            try{selection=currentRole(catalog);}catch(Exception failure){/* Keep enter disabled when selection integrity is unknown. */}
            final boolean saved=success;final Selection actual=selection;
            runOnUiThread(()->{
                saving=false;if(token!=generation||isFinishing()||isDestroyed())return;
                selectionValid=actual!=null;currentId=actual==null?null:actual.id;
                status.setText(saved&&actual!=null?actual.message:"选择未保存，请重试"+(actual==null?"":"\n"+actual.message));setControls();
            });
        });
    }
    @Override public void onBackPressed(){
        if(saving){android.widget.Toast.makeText(this,"正在保存选择，请稍候。",android.widget.Toast.LENGTH_SHORT).show();return;}
        super.onBackPressed();
    }
    private void openMirror(){
        if(loading||saving||!selectionValid)return;
        if(getIntent().getBooleanExtra("from_mirror",false))setResult(RESULT_OK);
        else startActivity(new Intent(this,MirrorActivity.class).putExtra(MirrorHomeActivity.PRODUCT_ACTION,"mirror"));
        finish();
    }
    @Override protected void onSaveInstanceState(Bundle out){out.putString("browsed_role",browsedId);super.onSaveInstanceState(out);}
    @Override protected void onPause(){generation++;previewGeneration++;super.onPause();}
    @Override protected void onDestroy(){generation++;previewGeneration++;clearBitmap();metadata.shutdown();super.onDestroy();}
}
