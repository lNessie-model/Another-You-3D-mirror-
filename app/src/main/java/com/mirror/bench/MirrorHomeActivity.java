package com.mirror.bench;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The only product launcher. Hardware belongs to MirrorActivity, never this navigation page. */
public final class MirrorHomeActivity extends Activity {
    static final String PRODUCT_ACTION="product_action";
    private final ExecutorService metadata=Executors.newSingleThreadExecutor();
    private TextView selection;
    private int generation;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);MirrorTheme.immersive(this);
        var root=MirrorTheme.page(this);LinearLayout body=MirrorTheme.safeScroll(this,root);
        body.addView(MirrorTheme.text(this,"ANOTHER YOU",29,true));
        body.addView(MirrorTheme.text(this,"另一个你",21,false));
        TextView subtitle=MirrorTheme.text(this,"与镜中的另一个自己相遇",13,false);subtitle.setTextColor(MirrorTheme.MUTED);body.addView(subtitle);
        selection=MirrorTheme.text(this,"正在读取你的角色与场景…",13,false);selection.setTextColor(MirrorTheme.MUTED);body.addView(selection);
        MirrorTheme.addButton(body,MirrorTheme.button(this,"进入魔镜",true,()->openMirror("mirror")));
        MirrorTheme.addButton(body,MirrorTheme.button(this,"角色",false,()->startActivity(new Intent(this,MirrorRolesActivity.class))));
        MirrorTheme.addButton(body,MirrorTheme.button(this,"场景",false,()->openMirror("scene")));
        MirrorTheme.addButton(body,MirrorTheme.button(this,"设置",false,()->startActivity(new Intent(this,MirrorSettingsActivity.class))));
        TextView note=MirrorTheme.text(this,"离线魔镜 · 随时切换角色与场景\n更多角色正在逐项校正",12,false);note.setTextColor(MirrorTheme.MUTED);body.addView(note);
        MirrorMotion.brandReveal(root,saved==null&&(getIntent().getFlags()&Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)==0);
        setContentView(root);
    }
    private void openMirror(String action){
        startActivity(new Intent(this,MirrorActivity.class).putExtra(PRODUCT_ACTION,action));
    }
    @Override protected void onResume(){
        super.onResume();final int token=++generation;
        var scene=SceneViewPreferences.load(this);
        String background=SceneViewSettings.BACKGROUNDS[scene.value.background];
        selection.setText("正在读取角色 · 场景："+background);
        metadata.execute(()->{
            String role;
            try{
                String imported=null;
                boolean explicit=BundledAvatarSelection.hasSelection(this);
                if(!explicit&&AvatarPackageStore.supportsAndroidApi(Build.VERSION.SDK_INT)){
                    var store=AvatarPackageStore.open(new File(getFilesDir(),"avatars"),Build.VERSION.SDK_INT);
                    var catalog=store.readCatalog();if(catalog.current!=null)imported=catalog.current.displayName;
                }
                if(imported!=null)role=imported+" · 本地导入";
                else{
                    var entry=BundledAvatarCatalog.find(BundledAvatarCatalog.read(getAssets()),BundledAvatarSelection.load(this));
                    role=entry.displayName+("legacy_reference".equals(entry.status)?" · 旧版参考":"");
                }
            }catch(Exception failure){role="无法读取所选角色，请在角色页重新选择";}
            final String text="当前角色："+role+"\n当前场景："+background+(scene.warning.isEmpty()?"":"\n画面配置需要检查");
            runOnUiThread(()->{if(token==generation&&!isFinishing()&&!isDestroyed())selection.setText(text);});
        });
    }
    @Override protected void onPause(){generation++;super.onPause();}
    @Override protected void onDestroy(){generation++;metadata.shutdownNow();super.onDestroy();}
}
