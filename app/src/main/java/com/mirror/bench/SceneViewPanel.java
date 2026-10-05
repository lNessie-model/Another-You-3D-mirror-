package com.mirror.bench;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.*;
import java.util.Locale;

/** Floating live draft. Save is explicit; cancel and Back restore the original immutable snapshot. */
final class SceneViewPanel {
    interface Host {void preview(SceneViewSettings value);void closed();}
    private final Activity activity;private final Host host;private final Dialog dialog;
    private final SceneViewSettings original;private SceneViewSettings draft;
    private final SeekBar[] sliders=new SeekBar[10];private final TextView[] labels=new TextView[10];
    private final CheckBox mirrorMotion;
    private final TextView error;private final Spinner background;private boolean saved,closed;
    private static final String[] NAMES={"缩放","水平位置","垂直位置","出入屏（正值向前）","俯仰角","左右旋转","画面旋转","相机总间距","零视差平面位置","嘴部 / 眉部表情强度"};
    private static final float[] LOW={.25f,-1,-1,-1.25f,-180,-180,-180,0,-1.5f,.5f};
    private static final float[] HIGH={3,1,1,1.25f,180,180,180,1.2f,1.5f,4};
    private static final float[] STEP={.01f,.01f,.01f,.01f,1,1,1,.005f,.01f,.05f};
    SceneViewPanel(Activity activity,SceneViewSettings original,Host host,int viewCount){
        this.activity=activity;this.original=original;draft=original;this.host=host;dialog=new Dialog(activity);
        dialog.setTitle("画面、景深与背景");
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(16),dp(12),dp(16),dp(12));form.setBackgroundColor(0xf020252b);
        text(form,"画面调节",18);text(form,"拖动立即预览；保存后下次仍生效。\n左右 / 上下为屏幕半幅比例；放大或平移可能裁切边缘。",13);
        button(form,"全屏预览 3 秒",()->{
            dialog.hide();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
                if(!closed&&!activity.isFinishing()&&!activity.isDestroyed())dialog.show();
            },3000);
        });
        for(int i=0;i<sliders.length;i++){
            final int index=i;labels[i]=text(form,"",14);sliders[i]=new SeekBar(activity);sliders[i].setId(i==9?210:200+i);
            sliders[i].setMax(Math.round((HIGH[i]-LOW[i])/STEP[i]));sliders[i].setContentDescription(NAMES[i]);form.addView(sliders[i]);
            sliders[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar bar,int value,boolean fromUser){
                    if(fromUser&&!closed){float number=Math.max(LOW[index],Math.min(HIGH[index],LOW[index]+value*STEP[index]));draft=draft.withValue(index,number);host.preview(draft);}
                    label(index);
                }
                public void onStartTrackingTouch(SeekBar bar){}
                public void onStopTrackingTouch(SeekBar bar){}
            });
        }
        mirrorMotion=new CheckBox(activity);mirrorMotion.setId(211);mirrorMotion.setText("镜像动作（像照镜子）");mirrorMotion.setTextColor(Color.WHITE);form.addView(mirrorMotion);
        mirrorMotion.setOnCheckedChangeListener((button,checked)->{if(!closed){draft=draft.withMirrorMotion(checked);host.preview(draft);}});
        text(form,"嘴部和抬眉采用平滑强度曲线；眉间下压、眨眼和睁眼分别响应，眼球、眯眼及闭嘴补偿保持原比例。",13);
        text(form,"相邻视点间距 = 总间距 ÷ "+Math.max(1,viewCount-1)+"。零平面处没有视差；出入屏与零平面共同决定前后位置。",13);
        text(form,"背景（固定在屏幕平面）",14);background=MirrorTheme.selectionSpinner(activity,"选择背景");background.setId(209);
        background.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,SceneViewSettings.BACKGROUNDS));form.addView(background);
        background.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> parent,android.view.View view,int position,long id){if(!closed){draft=draft.withBackground(position);host.preview(draft);}}
            public void onNothingSelected(AdapterView<?> parent){}
        });
        error=text(form,"",13);error.setTextColor(0xffffa5a5);
        var loaded=SceneViewPreferences.load(activity);if(!loaded.warning.isEmpty())error.setText(loaded.warning);
        button(form,"恢复默认（仅预览）",()->{draft=SceneViewSettings.DEFAULT;fill();host.preview(draft);});
        Button save=button(form,"保存画面设置",()->{
            try{SceneViewPreferences.save(activity,draft);saved=true;host.preview(draft);dialog.dismiss();}
            catch(RuntimeException failure){error.setText("未保存："+failure.getMessage());}
        });save.setEnabled(loaded.writable);
        button(form,"取消，恢复原画面",dialog::dismiss);
        ScrollView scroll=new ScrollView(activity);scroll.addView(form);dialog.setContentView(scroll);
        dialog.setOnDismissListener(ignored->{closed=true;if(!saved)host.preview(original);host.closed();});fill();
    }
    void show(){
        dialog.show();var window=dialog.getWindow();
        if(window!=null){window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);MirrorTheme.safeDialog(activity,dialog);}
    }
    void dismiss(){dialog.dismiss();}
    private void fill(){for(int i=0;i<sliders.length;i++){sliders[i].setProgress(Math.round((draft.value(i)-LOW[i])/STEP[i]));label(i);}mirrorMotion.setChecked(draft.mirrorMotion);background.setSelection(draft.background);}
    private void label(int i){labels[i].setText(NAMES[i]+"："+String.format(Locale.ROOT,i>=4&&i<=6?"%.0f°":"%.3f",draft.value(i)));}
    private TextView text(LinearLayout form,String value,int size){TextView label=new TextView(activity);label.setText(value);label.setTextSize(size);label.setTextColor(Color.WHITE);form.addView(label);return label;}
    private Button button(LinearLayout form,String label,Runnable action){Button button=new Button(activity);button.setText(label);button.setMinHeight(dp(48));button.setOnClickListener(ignored->action.run());form.addView(button);return button;}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
