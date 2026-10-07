package com.mirror.bench;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.widget.*;
import java.util.Locale;

/** UI-owned draft with a single bounded, worker-produced preview slot. No hardware ownership. */
final class CameraCalibrationPanel {
    interface Host {
        void changeInput(CameraControlSettings controls);
        void retryInput(CameraCalibrationPanel owner);
        boolean changeCalibration(Input expected,FaceControlCalibration calibration);
        boolean confirmCalibration(CameraCalibrationPanel owner,Input expected,NeutralCalibrationCollector.Session session,
                NeutralCalibrationCollector.Result result,FaceControlCalibration calibration);
        void save(CameraCalibrationPanel owner,Input expected,CameraControlSettings controls);
        void closed(CameraCalibrationPanel panel,boolean saved,boolean inputChanged);
    }
    static final class Input {
        final Object token=new Object();
        final CameraControlSettings effective;
        final String warning;
        Input(CameraControlSettings effective,String warning){this.effective=effective;this.warning=warning;}
    }
    private final Host host;
    private final AlertDialog dialog;
    private final TextView identity,progress,actions;
    private final TextView avatarStatus;
    private final CameraCalibrationAvatarPreview avatarPreview;
    private final ImageView image;
    private final Spinner rotation;
    private final CheckBox reflect,mirror,personal,checked;
    private final Button collect,confirm,retry;
    private CameraControlSettings draft;
    private FaceControlCalibration calibration;
    private Input input;
    private NeutralCalibrationCollector collector;
    private NeutralCalibrationCollector.Session session;
    private NeutralCalibrationCollector.Result ready;
    private Bitmap pendingPreview;
    private Object pendingToken;
    private long previewAt;
    private boolean closed,binding,saved,inputChanged,initialized;
    private final boolean writable;
    private boolean inputUsable,retryAvailable;
    CameraCalibrationPanel(Activity activity,CameraControlSettings initial,FaceControlCalibration initialCalibration,boolean writable,Host host){
        this.host=host;this.draft=initial;this.calibration=new FaceControlCalibration(initialCalibration.revision(),initial.mirrorInteraction,null,null);this.writable=writable;
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(MirrorTheme.dp(activity,14),MirrorTheme.dp(activity,8),MirrorTheme.dp(activity,14),MirrorTheme.dp(activity,8));
        TextView directionTitle=text(activity,"01 · 核对方向",18);body.addView(directionTitle);
        identity=text(activity,"正在准备相机……");body.addView(identity);
        retry=MirrorTheme.button(activity,"重新连接相机",false,()->host.retryInput(this));
        retry.setVisibility(View.GONE);body.addView(retry);
        body.addView(text(activity,"用文字或字母 F 核对相机方向，再检查角色的左右眨眼、张口和转头。"));
        image=new ImageView(activity);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout previews=new LinearLayout(activity);previews.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout cameraColumn=new LinearLayout(activity);cameraColumn.setOrientation(LinearLayout.VERTICAL);
        TextView cameraLabel=MirrorTheme.text(activity,"相机画面",13,false);cameraColumn.addView(cameraLabel);
        cameraColumn.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        previews.addView(cameraColumn,new LinearLayout.LayoutParams(0,-1,1));
        avatarStatus=text(activity,"角色正在加载……");
        avatarPreview=new CameraCalibrationAvatarPreview(activity,new java.io.File(activity.getFilesDir(),"avatars"),
                android.os.Build.VERSION.SDK_INT,value->{setTextIfChanged(avatarStatus,avatarLabel(value));if(initialized&&!isClosed())refreshEnabled();});
        try{
        LinearLayout roleColumn=new LinearLayout(activity);roleColumn.setOrientation(LinearLayout.VERTICAL);
        roleColumn.addView(MirrorTheme.text(activity,"角色动作",13,false));
        roleColumn.addView(avatarPreview,new LinearLayout.LayoutParams(-1,0,1));
        previews.addView(roleColumn,new LinearLayout.LayoutParams(0,-1,1));
        body.addView(previews,new LinearLayout.LayoutParams(-1,MirrorTheme.dp(activity,180)));body.addView(avatarStatus);
        rotation=MirrorTheme.selectionSpinner(activity,"相机旋转方向");ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,new String[]{"顺时针旋转 0°","顺时针旋转 90°","顺时针旋转 180°","顺时针旋转 270°"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);rotation.setAdapter(adapter);body.addView(rotation);
        reflect=check(activity,"校正摄像头自带的左右颠倒（旋转后）");body.addView(reflect);
        mirror=check(activity,"镜像互动（只改变角色动作）");body.addView(mirror);
        TextView baselineTitle=text(activity,"02 · 放松，建立基准",18);body.addView(baselineTitle);
        personal=check(activity,"本次同时校准个人眉眼、嘴部与视线偏置");personal.setChecked(true);body.addView(personal);
        body.addView(text(activity,"正视、眉毛放松、自然睁眼并闭口，保持约 2 秒。采集后点击确认，再检查挑眉、皱眉、眨眼和张口。"));
        LinearLayout buttons=new LinearLayout(activity);body.addView(buttons);
        collect=new Button(activity);collect.setText("采集中性");buttons.addView(collect,new LinearLayout.LayoutParams(0,-2,1));
        confirm=new Button(activity);confirm.setText("确认本次中性");confirm.setEnabled(false);buttons.addView(confirm,new LinearLayout.LayoutParams(0,-2,1));
        Button clear=new Button(activity);clear.setText("清除本次基准 / 取消采集");body.addView(clear);
        progress=text(activity,"头部与个人基准仅用于本次会话，不写入安装设置。");body.addView(progress);
        actions=text(activity,"等待动作数据");body.addView(actions);
        TextView checkTitle=text(activity,"03 · 检查并保存",18);body.addView(checkTitle);
        checked=check(activity,"已核对文字方向、挑眉与皱眉、左右眨眼、张口、视线与转头");body.addView(checked);
        ScrollView scroll=new ScrollView(activity);scroll.addView(body);
        dialog=new AlertDialog.Builder(activity).setTitle("相机与动作校准").setView(scroll)
                .setPositiveButton("保存安装设置",null).setNegativeButton("取消",null).setNeutralButton("默认草稿",null).create();
        syncControls();
        rotation.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(!binding&&position*90!=draft.rotationDegrees)editInput();}
        });
        reflect.setOnCheckedChangeListener((v,b)->{if(!binding&&b!=draft.reflectInput)editInput();});
        mirror.setOnCheckedChangeListener((v,b)->{if(binding)return;cancelCollection();checked.setChecked(false);
            draft=draft.withMirror(b,nextRevision());calibration=new FaceControlCalibration(draft.revision,b,calibration.neutralPose(),calibration.personalBaseline());host.changeCalibration(input,calibration);});
        collect.setOnClickListener(v->{cancelCollection();synchronized(this){collector=new NeutralCalibrationCollector(personal.isChecked());session=collector.begin(SystemClock.elapsedRealtimeNanos());}progress.setText("保持正视、放松；20 秒内完成连续采集。");});
        personal.setOnCheckedChangeListener((v,b)->cancelCollection());
        confirm.setOnClickListener(v->{if(collector==null||!collector.isCurrentReady(session,ready))return;
            FaceControlCalibration proposed=new FaceControlCalibration(nextRevision(),draft.mirrorInteraction,ready.neutralPose(),ready.personalBaseline());
            if(!host.confirmCalibration(this,input,session,ready,proposed)){cancelCollection();progress.setText("输入或采集状态已改变，请重新采集中性。");return;}
            calibration=proposed;
            cancelCollection();checked.setChecked(false);progress.setText("已用于本次运行。请检查动作；取消退出、离开镜头或更换输入后需重新采集，重启不保留。");});
        clear.setOnClickListener(v->{clearNeutral();progress.setText("已清除本次基准，采集已取消。");});
        checked.setOnCheckedChangeListener((v,b)->refreshEnabled());
        dialog.setOnDismissListener(d->finish());
        dialog.show();MirrorTheme.safeDialog(activity,dialog);
        if(dialog.getWindow()!=null)dialog.getWindow().setBackgroundDrawable(MirrorTheme.surface(activity,MirrorTheme.SURFACE|0xff000000));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x44d1ba91),MirrorTheme.surface(activity,MirrorTheme.WINE),null));
        for(TextView heading:new TextView[]{directionTitle,baselineTitle,checkTitle}){heading.setTextColor(MirrorTheme.GOLD);if(android.os.Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);}
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->dialog.dismiss());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
            if(input==null)return;draft=new CameraControlSettings(input.effective.cameraId,input.effective.fingerprint,640,480,0,false,false,nextRevision());
            inputChanged=true;clearNeutral();syncControls();checked.setChecked(false);host.changeInput(draft);
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!readyForInput()||!avatarReady()||!checked.isChecked()||!writable)return;
            try{host.save(this,input,draft);saved=true;dialog.dismiss();}
            catch(RuntimeException error){progress.setText("未确认保存成功："+error.getMessage()+"。草稿仍在，请重试或取消。");}
        });
        refreshEnabled();
        initialized=true;
        }catch(RuntimeException|Error failure){avatarPreview.close();throw failure;}
    }
    void startPreview(){avatarPreview.resumePreview();}
    void preview(InteractionController.Snapshot mapped,boolean active){if(!isClosed())avatarPreview.submit(mapped,active);}
    CameraCalibrationAvatarPreview.Status previewStatus(){return avatarPreview.status();}
    private static String avatarLabel(CameraCalibrationAvatarPreview.Status value){
        String state=switch(value.state()){
            case "LOADING" -> "正在加载角色";case "WAITING_FOR_GL" -> "正在准备画面";
            case "READY" -> "角色动作预览";case "ERROR" -> "角色预览失败";
            case "PAUSED" -> "角色预览已暂停";case "CLOSED" -> "角色预览已关闭";default -> value.state();};
        return state+(value.displayName().isEmpty()?"":" · "+value.displayName())
                +(value.error().isEmpty()?"":"\n"+value.error());
    }
    private long nextRevision(){return Math.addExact(Math.max(draft.revision,calibration.revision()),1);}
    private static void setTextIfChanged(TextView target,String value){if(!value.contentEquals(target.getText()))target.setText(value);}
    private static TextView text(Activity a,String value){return text(a,value,14);}
    private static TextView text(Activity a,String value,int size){TextView v=new TextView(a);v.setText(value);v.setTextSize(size);v.setTextColor(MirrorTheme.INK);v.setPadding(0,MirrorTheme.dp(a,6),0,MirrorTheme.dp(a,6));return v;}
    private static CheckBox check(Activity a,String value){CheckBox v=new CheckBox(a);v.setText(value);v.setTextSize(14);return v;}
    private void syncControls(){binding=true;rotation.setSelection(draft.rotationDegrees/90);reflect.setChecked(draft.reflectInput);mirror.setChecked(draft.mirrorInteraction);binding=false;}
    private void editInput(){
        if(input==null){syncControls();return;}cancelCollection();checked.setChecked(false);inputChanged=true;
        draft=new CameraControlSettings(input.effective.cameraId,input.effective.fingerprint,640,480,rotation.getSelectedItemPosition()*90,reflect.isChecked(),mirror.isChecked(),nextRevision());
        clearNeutral();host.changeInput(draft);
    }
    private void clearNeutral(){cancelCollection();calibration=new FaceControlCalibration(nextRevision(),draft.mirrorInteraction,null,null);host.changeCalibration(input,calibration);checked.setChecked(false);}
    private void cancelCollection(){synchronized(this){if(collector!=null&&session!=null)collector.cancel(session);collector=null;session=null;ready=null;}confirm.setEnabled(false);}
    private boolean readyForInput(){return inputUsable&&input!=null&&draft.matches(input.effective.cameraId,input.effective.fingerprint,640,480)
            &&input.effective.rotationDegrees==draft.rotationDegrees&&input.effective.reflectInput==draft.reflectInput;}
    private boolean avatarReady(){return "READY".equals(avatarPreview.status().state());}
    private void refreshEnabled(){
        boolean ready=readyForInput();collect.setEnabled(ready);rotation.setEnabled(input!=null);reflect.setEnabled(input!=null);mirror.setEnabled(input!=null);
        checked.setEnabled(ready&&avatarReady());
        retry.setVisibility(retryAvailable?View.VISIBLE:View.GONE);retry.setEnabled(retryAvailable);
        Button defaults=dialog.getButton(AlertDialog.BUTTON_NEUTRAL);if(defaults!=null)defaults.setEnabled(input!=null);
        Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE);if(save!=null)save.setEnabled(ready&&avatarReady()&&writable&&checked.isChecked());
    }
    /** UI-thread update. Identity transitions revoke draft sampling; no obsolete raw frame is relabelled. */
    void tick(Input current,FaceFrame raw,Object rawToken,InteractionController.Snapshot mapped,String runtimeError){
        if(isClosed())return;
        if(current!=input){
            boolean hadInput=input!=null;cancelCollection();synchronized(this){input=current;}image.setImageBitmap(null);checked.setChecked(false);
            if(current!=null){
                if(!draft.matches(current.effective.cameraId,current.effective.fingerprint,640,480)){
                    inputChanged=true;draft=current.effective;syncControls();
                }
                if(hadInput)inputChanged=true;
                clearNeutral();
            }
        }
        CameraCalibrationInputStatus.Status status=CameraCalibrationInputStatus.describe(current!=null,
                current==null?draft.rotationDegrees:current.effective.rotationDegrees,current==null?"":current.warning,
                mapped==null?InteractionController.State.WAITING:mapped.state(),runtimeError);
        boolean wasUsable=inputUsable;inputUsable=status.usable();retryAvailable=status.canRetry();setTextIfChanged(identity,status.label());
        if(!inputUsable){cancelCollection();checked.setChecked(false);if(wasUsable){image.setImageBitmap(null);progress.setText("输入已暂停，采集已取消；相机恢复后请重新采集中性。");}}
        Bitmap preview=takePreview(!inputUsable||current==null?null:current.token);if(preview!=null)image.setImageBitmap(preview);
        if(collector!=null){
            NeutralCalibrationCollector.Update update=collector.poll(session,SystemClock::elapsedRealtimeNanos);
            ready=update.result();confirm.setEnabled(collector.isCurrentReady(session,ready));
            progress.setText(collectorStatus(update.status())+" · "+update.samples()+" 个样本 / "+update.stableMillis()+" ms\n"+reason(update.reason())
                    +(ready==null?"":"\n采集完成，点击确认后才用于角色。"));
        }
        if(mapped!=null&&inputUsable){
            if(mapped.state()==InteractionController.State.WAITING&&(calibration.hasNeutralPose()||calibration.personalBaseline()!=null)){
                clearNeutral();progress.setText("已离开互动，当前个人基准已清除；可重新采集。");
            }
            float[] w=mapped.blendshapes52();setTextIfChanged(actions,String.format(Locale.ROOT,"角色输入 · 左皱眉 %.2f / 右皱眉 %.2f\n左眨眼 %.2f / 右眨眼 %.2f / 张口 %.2f\n%s",w[1],w[2],w[9],w[10],w[25],mapped.calibrationError()));}
        else setTextIfChanged(actions,"尚无有效动作数据；相机恢复后再核对左右眨眼、张口与转头。");
        refreshEnabled();
    }
    private static String collectorStatus(NeutralCalibrationCollector.Status state){return switch(state){
        case IDLE -> "尚未开始";case COLLECTING -> "采集中";case READY -> "采集完成";
        case TIMED_OUT -> "采集超时";case CANCELLED -> "采集已取消";case STALE_SESSION -> "输入会话已改变";
    };}
    /** Every raw result is gated here, even if a UI tick is delayed. Never accesses Android Views. */
    synchronized boolean isCurrentReady(Input expected,NeutralCalibrationCollector.Session expectedSession,NeutralCalibrationCollector.Result result){
        return !closed&&expected!=null&&expected==input&&session==expectedSession&&ready==result&&collector!=null
                &&collector.isCurrentReady(expectedSession,result);
    }
    synchronized void offerRaw(Input owner,FaceFrame frame){
        if(!closed&&owner!=null&&owner==input&&collector!=null&&session!=null){
            // A READY draft is not transferable to a newly arriving face.
            if(!frame.present()&&collector.poll(session,SystemClock::elapsedRealtimeNanos).status()==NeutralCalibrationCollector.Status.READY)
                collector.cancel(session);
            else collector.offer(session,frame,SystemClock::elapsedRealtimeNanos);
        }
    }
    private static String reason(NeutralCalibrationCollector.Reason reason){return switch(reason){
        case NONE,DUPLICATE_OR_OLD_SEQUENCE -> "保持正视、自然放松";
        case NO_FACE -> "未看到人脸，请进入镜头范围";
        case EYES_NOT_RELAXED -> "请自然睁眼";
        case MOUTH_NOT_RELAXED -> "请闭口放松，不做表情";
        case BROWS_NOT_RELAXED -> "请放松眉毛，不挑眉也不皱眉，再采集中性";
        case GAZE_NOT_CENTERED -> "请看向正前方";
        case HEAD_MOVING,COEFFICIENT_CHANGED -> "检测到动作，请保持稳定，重新累计";
        case TIME_LIMIT -> "采集超时，可重新采集";
        case FRAME_GAP,STALE_FRAME -> "面捕不连续，正在重新累计";
        default -> "暂不能采集："+reason;
    };}
    /** Only a scaled owned Bitmap crosses to UI. Pending replacement is safe to recycle; displayed images use GC lifetime. */
    synchronized void offerPreview(Input owner,Bitmap source){
        if(closed)return;long now=SystemClock.elapsedRealtimeNanos();if(now-previewAt<200_000_000L)return;previewAt=now;
        int width=source.getWidth()>=source.getHeight()?192:144,height=source.getWidth()>=source.getHeight()?144:192;
        Bitmap next=Bitmap.createScaledBitmap(source,width,height,true);
        if(next==source)next=source.copy(Bitmap.Config.ARGB_8888,false);
        if(pendingPreview!=null)pendingPreview.recycle();pendingPreview=next;pendingToken=owner.token;
    }
    private synchronized Bitmap takePreview(Object owner){
        Bitmap next=pendingPreview;pendingPreview=null;if(next!=null&&pendingToken!=owner){next.recycle();return null;}return next;
    }
    private synchronized boolean isClosed(){return closed;}
    void dismiss(){dialog.dismiss();}
    private void finish(){
        synchronized(this){if(closed)return;closed=true;if(pendingPreview!=null)pendingPreview.recycle();pendingPreview=null;}
        cancelCollection();image.setImageBitmap(null);
        try{avatarPreview.close();}finally{host.closed(this,saved,inputChanged);}
    }
}
