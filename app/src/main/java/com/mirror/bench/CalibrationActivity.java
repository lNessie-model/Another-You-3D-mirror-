package com.mirror.bench;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;

/** Draft-only editor: preview/default/cancel do not persist. Save replaces a validated versioned config. */
public final class CalibrationActivity extends Activity {
    private EditText pitch,tan,phase;
    private Spinner units,order,origin;
    private CheckBox reverse;
    private TextView error;
    static Intent intent(Context context,int views,boolean debug) {
        return RuntimeViewCount.forwardConfigured(new Intent(context,CalibrationActivity.class),views,debug);
    }
    static String previewLabel(int views) {return "预览"+views+"视点测试图";}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        final boolean debug=(getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        MirrorSettings settings=MirrorSettings.load(this);
        final int viewCount;
        try {viewCount=RuntimeViewCount.readConfigured(getIntent().getExtras(),debug,settings.viewCount);}
        catch(RuntimeException bad) {
            TextView message=new TextView(this);message.setText("校准配置错误："+bad.getMessage());setContentView(message);return;
        }
        LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(24,12,24,16);
        ScrollView scroll=new ScrollView(this); scroll.addView(form); setContentView(scroll);
        text(form,"屏幕交织校准 · 未经实屏光学确认\n保存的是交织参数，软件像素核对通过也不代表光学对齐。\n预览中是"+viewCount+"张不同颜色和01–"+viewCount+"数字的视点图。");
        if(!settings.warning.isEmpty())text(form,settings.warning);
        pitch=number(form,"Pitch（1–4096，按所选单位）",101);
        units=choice(form,"Pitch单位",new String[]{"子像素 SUBPIXELS","像素 PIXELS"},102);
        tan=number(form,"Tan（−4–4，水平像素 / 垂直像素）",103);
        phase=number(form,"Offset / Phase（周期偏移；1为一个光栅周期，保存时归一到 [0,1)）",104);
        order=choice(form,"面板物理子像素排列（不交换图像颜色）",new String[]{"RGB","BGR"},105);
        reverse=new CheckBox(this); reverse.setId(106); reverse.setText("反转视点顺序"); form.addView(reverse);
        origin=choice(form,"垂直坐标原点",new String[]{"底部 BOTTOM","顶部 TOP（height − pixel center）"},107);
        error=text(form,""); error.setTextColor(0xffff7777);
        fill(settings.panel);
        button(form,previewLabel(viewCount),()-> {
            try { PanelCalibration panel=read(); startActivity(PanelPreviewActivity.intent(this,panel,viewCount,debug)); error.setText(""); }
            catch(RuntimeException bad) { error.setText("参数无效："+bad.getMessage()); }
        });
        button(form,"恢复默认（仅修改草稿）",()-> {fill(PanelCalibration.defaults());error.setText("草稿已恢复；保存后生效");});
        Button save=button(form,"保存并返回主画面",()-> {
            try { MirrorSettings.savePanel(this,read()); setResult(RESULT_OK); finish(); }
            catch(RuntimeException bad) { error.setText("未保存："+bad.getMessage()); }
        }); save.setEnabled(settings.writable);
        button(form,"取消，保留原配置",this::finish);
    }
    private PanelCalibration read() {
        return new PanelCalibration(Float.parseFloat(pitch.getText().toString().trim()),
                Float.parseFloat(tan.getText().toString().trim()),
                units.getSelectedItemPosition()==0?PanelCalibration.PitchUnits.SUBPIXELS:PanelCalibration.PitchUnits.PIXELS,
                Float.parseFloat(phase.getText().toString().trim()),
                order.getSelectedItemPosition()==0?PanelCalibration.SubpixelOrder.RGB:PanelCalibration.SubpixelOrder.BGR,
                reverse.isChecked(),origin.getSelectedItemPosition()==0?PanelCalibration.YOrigin.BOTTOM:PanelCalibration.YOrigin.TOP);
    }
    private void fill(PanelCalibration p) {
        pitch.setText(Float.toString(p.pitch())); tan.setText(Float.toString(p.tan())); phase.setText(Float.toString(p.phaseCycles()));
        units.setSelection(p.pitchUnits()==PanelCalibration.PitchUnits.SUBPIXELS?0:1);
        order.setSelection(p.subpixelOrder()==PanelCalibration.SubpixelOrder.RGB?0:1);
        reverse.setChecked(p.reverseViews()); origin.setSelection(p.yOrigin()==PanelCalibration.YOrigin.BOTTOM?0:1);
    }
    private TextView text(LinearLayout form,String value) {TextView t=new TextView(this);t.setText(value);form.addView(t);return t;}
    private EditText number(LinearLayout form,String label,int id) {
        text(form,label);EditText t=new EditText(this);t.setId(id);
        t.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        t.setSingleLine();form.addView(t);return t;
    }
    private Spinner choice(LinearLayout form,String label,String[] values,int id) {
        text(form,label);Spinner s=new Spinner(this);s.setId(id);
        s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));form.addView(s);return s;
    }
    private Button button(LinearLayout form,String label,Runnable action) {
        Button b=new Button(this);b.setText(label);b.setOnClickListener(v->action.run());form.addView(b);return b;
    }
}
