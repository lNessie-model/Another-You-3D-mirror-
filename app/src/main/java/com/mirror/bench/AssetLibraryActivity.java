package com.mirror.bench;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/** Static own-source previews. Live activation reuses the verified bundled-role contract. */
public final class AssetLibraryActivity extends Activity {
    private static final String SAVED_ID="asset_library_selected_id";
    private final ExecutorService metadata=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"AssetLibraryMetadata");thread.setDaemon(true);return thread;
    });
    private final SelectionLifetime lifetime=new SelectionLifetime();
    private boolean preparing;
    private Future<?> loadingTask;
    private List<AssetLibraryCatalog.Entry> entries=List.of();
    private AssetLibraryCatalog.Entry selected;
    private String restoreId;
    private Bitmap shown;
    private Spinner selector;
    private ImageView image;
    private TextView status,title,description;
    private Button use,back;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);MirrorTheme.immersive(this);
        restoreId=saved==null?null:saved.getString(SAVED_ID);
        var root=MirrorTheme.page(this);LinearLayout body=MirrorTheme.safeScroll(this,root);
        body.addView(MirrorTheme.text(this,"素材库",25,true));
        body.addView(MirrorTheme.text(this,"查看角色原件、场景与图片\n选择预览不会改变当前魔镜角色",13,false));
        selector=MirrorTheme.selectionSpinner(this,"选择素材");selector.setEnabled(false);
        body.addView(selector,new LinearLayout.LayoutParams(-1,MirrorTheme.dp(this,52)));
        title=MirrorTheme.text(this,"",20,true);body.addView(title);
        image=new ImageView(this);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        body.addView(image,new LinearLayout.LayoutParams(-1,MirrorTheme.dp(this,260)));
        description=MirrorTheme.text(this,"",13,false);body.addView(description);
        status=MirrorTheme.text(this,"正在读取素材目录…",13,false);status.setTextColor(MirrorTheme.MUTED);body.addView(status);
        use=MirrorTheme.button(this,"使用角色",true,this::useRole);use.setVisibility(View.GONE);MirrorTheme.addButton(body,use);
        back=MirrorTheme.button(this,"返回角色页",false,this::returnToRoles);MirrorTheme.addButton(body,back);
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(isActive()&&position>=0&&position<entries.size()&&!preparing){
                    AssetLibraryCatalog.Entry entry=entries.get(position);
                    if(selected!=entry)show(entry);
                }
            }
            public void onNothingSelected(AdapterView<?> parent){}
        });
        setContentView(root);
    }
    @Override protected void onResume(){
        super.onResume();lifetime.resume();reload();
    }
    private void reload(){
        final long token=next();selector.setEnabled(false);use.setEnabled(false);status.setText("正在读取素材目录…");
        loadingTask=metadata.submit(()->{
            if(!current(token))return;
            try{
                List<AssetLibraryCatalog.Entry> read=AssetLibraryCatalog.read(getAssets());
                runOnUiThread(()->{
                    if(!current(token))return;
                    entries=read;selected=null;String[] labels=new String[read.size()];int position=0;
                    for(int i=0;i<read.size();i++){
                        var entry=read.get(i);labels[i]=entry.displayName+" · "+kind(entry);
                        if(entry.id.equals(restoreId))position=i;
                    }
                    ArrayAdapter<String> rows=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);
                    rows.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    selector.setAdapter(rows);selector.setSelection(position);selector.setEnabled(true);
                    show(read.get(position));
                });
            }catch(Exception failure){runOnUiThread(()->{
                if(!current(token))return;entries=List.of();selected=null;clearPreview();title.setText("目录暂时不可用");
                description.setText("请返回角色页后重试。当前魔镜角色保持原选择。");status.setText("未能读取完整素材目录");use.setVisibility(View.GONE);
            });}
        });
    }
    private void show(AssetLibraryCatalog.Entry entry){
        final long token=next();selected=entry;restoreId=entry.id;clearPreview();title.setText(entry.displayName);
        image.setContentDescription(entry.displayName+"静态预览");
        description.setText(kind(entry)+"\n"+entry.sourceLabel+"\n"+entry.notes);
        status.setText("正在读取所选预览…");use.setVisibility(entry.status.equals("runtime_ready")?View.VISIBLE:View.GONE);use.setEnabled(false);
        loadingTask=metadata.submit(()->{
            if(!current(token))return;
            Bitmap bitmap=null;
            try{
                byte[] bytes=AssetLibraryCatalog.readPreview(getAssets(),entry);
                int largest=Math.max(ByteBuffer.wrap(bytes).getInt(16),ByteBuffer.wrap(bytes).getInt(20));
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
                while(largest/options.inSampleSize>1024)options.inSampleSize*=2;
                options.inPreferredConfig=Bitmap.Config.ARGB_8888;
                bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                if(bitmap==null)throw new IOException("Cannot decode selected PNG");
                Bitmap ready=bitmap;bitmap=null;
                runOnUiThread(()->{
                    if(!current(token)){ready.recycle();return;}
                    shown=ready;image.setImageBitmap(ready);status.setText(hint(entry));use.setEnabled(entry.status.equals("runtime_ready"));
                    MirrorMotion.previewReady(image);
                });
            }catch(Exception failure){
                if(bitmap!=null)bitmap.recycle();
                runOnUiThread(()->{if(current(token)){status.setText("所选预览暂时不可用。当前角色保持原选择。");use.setEnabled(false);}});
            }
        });
    }
    private void useRole(){
        AssetLibraryCatalog.Entry entry=selected;if(!isActive()||entry==null||!entry.status.equals("runtime_ready")||preparing)return;
        final long token=next();preparing=true;
        selector.setEnabled(false);use.setEnabled(false);status.setText("正在准备角色…可返回取消");
        loadingTask=metadata.submit(()->{
            if(!current(token))return;
            boolean success=false;String message="角色未保存，原选择保持。请重试。";
            try{
                var live=AssetLibraryCatalog.verifiedRuntimeEntry(getAssets(),entry);
                // Serialize pause/destroy against the small preferences transaction. A backgrounded
                // preview must not silently activate a role after its owner has gone away.
                success=lifetime.commitIfCurrent(token,()->!isFinishing()&&!isDestroyed(),
                    ()->BundledAvatarSelection.save(this,live.id));
            }catch(Exception failure){/* The original selection is restored by the selection store. */}
            final boolean saved=success;final String result=message;
            runOnUiThread(()->{
                if(!current(token))return;
                preparing=false;
                if(saved){setResult(RESULT_OK);finish();}
                else{status.setText(result);selector.setEnabled(true);back.setEnabled(true);use.setEnabled(shown!=null);}
            });
        });
    }
    private static String kind(AssetLibraryCatalog.Entry entry){
        if(entry.status.equals("archive"))return "历史档案";
        if(entry.status.equals("runtime_ready"))return "可用角色";
        return entry.type.equals("head")?"待校正角色":entry.type.equals("scene")?"场景预览":"图片素材";
    }
    private static String hint(AssetLibraryCatalog.Entry entry){
        if(entry.status.equals("archive"))return "保留原件供查阅，尚未作为可用素材。";
        if(entry.status.equals("runtime_ready"))return "可选择当前已接入魔镜的角色。";
        if(entry.type.equals("head"))return "面部校正中，暂不用于实时魔镜。";
        return "静态预览，使用方式见素材说明。";
    }
    private long next(){long token=lifetime.next();cancelLoading();return token;}
    private boolean isActive(){return lifetime.active()&&!isFinishing()&&!isDestroyed();}
    private void cancelLoading(){if(loadingTask!=null){loadingTask.cancel(true);loadingTask=null;}}
    private boolean current(long token){return lifetime.current(token)&&!isFinishing()&&!isDestroyed();}
    private void clearPreview(){MirrorMotion.reset(image);image.setImageDrawable(null);if(shown!=null){shown.recycle();shown=null;}}
    @Override protected void onSaveInstanceState(Bundle state){state.putString(SAVED_ID,restoreId);super.onSaveInstanceState(state);}
    private void returnToRoles(){
        lifetime.invalidate();cancelLoading();preparing=false;finish();
    }
    @Override public void onBackPressed(){returnToRoles();}
    @Override protected void onPause(){
        lifetime.invalidate();cancelLoading();preparing=false;
        back.setEnabled(true);selector.setEnabled(false);use.setEnabled(false);clearPreview();super.onPause();
    }
    @Override protected void onDestroy(){
        lifetime.invalidate();cancelLoading();metadata.shutdownNow();clearPreview();super.onDestroy();
    }
    /** Only the short preference transaction holds this lock; hashing remains cancellable. */
    static final class SelectionLifetime {
        private boolean active;
        private long generation;
        @FunctionalInterface interface Action {void run()throws IOException;}
        synchronized void resume(){active=true;generation++;}
        synchronized long next(){return ++generation;}
        synchronized boolean active(){return active;}
        synchronized boolean current(long token){return active&&token==generation;}
        synchronized void invalidate(){active=false;generation++;}
        synchronized boolean commitIfCurrent(long token,BooleanSupplier ownerPresent,Action action)throws IOException{
            if(!active||token!=generation||!ownerPresent.getAsBoolean())return false;
            action.run();return true;
        }
    }
}
