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
    private final Button collect,confirm;
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
    CameraCalibrationPanel(Activity activity,CameraControlSettings initial,FaceControlCalibration initialCalibration,boolean writable,Host host){
        this.host=host;this.draft=initial;this.calibration=new FaceControlCalibration(initialCalibration.revision(),initial.mirrorInteraction,null,null);this.writable=writable;
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(20,8,20,8);
        identity=text(activity,"等待相机准备完成……");body.addView(identity);
        body.addView(text(activity,"左侧为规范输入（不额外镜像），用字母 F 或文字确认方向；右侧角色用于检查左右眼、张口和转头。"));
        image=new ImageView(activity);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout previews=new LinearLayout(activity);previews.setOrientation(LinearLayout.HORIZONTAL);
        previews.addView(image,new LinearLayout.LayoutParams(0,-1,1));
        avatarStatus=text(activity,"角色正在加载……");
        avatarPreview=new CameraCalibrationAvatarPreview(activity,new java.io.File(activity.getFilesDir(),"avatars"),
                android.os.Build.VERSION.SDK_INT,value->{avatarStatus.setText(avatarLabel(value));if(initialized&&!isClosed())refreshEnabled();});
        try{
        previews.addView(avatarPreview,new LinearLayout.LayoutParams(0,-1,1));
        body.addView(previews,new LinearLayout.LayoutParams(-1,360));body.addView(avatarStatus);
        rotation=MirrorTheme.selectionSpinner(activity,"相机旋转方向");ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,new String[]{"顺时针旋转 0°","顺时针旋转 90°","顺时针旋转 180°","顺时针旋转 270°"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);rotation.setAdapter(adapter);body.addView(rotation);
        reflect=check(activity,"校正摄像头自带的左右颠倒（旋转后）");body.addView(reflect);
        mirror=check(activity,"镜像互动（只改变角色动作）");body.addView(mirror);
        personal=check(activity,"本次同时校准个人眼口与视线偏置");body.addView(personal);
        body.addView(text(activity,"正视、放松、睁眼、闭口，保持约 2 秒。中性阈值尚待真人验证；采集成功后仍需检查动作。"));
        LinearLayout buttons=new LinearLayout(activity);body.addView(buttons);
        collect=new Button(activity);collect.setText("采集中性");buttons.addView(collect,new LinearLayout.LayoutParams(0,-2,1));
        confirm=new Button(activity);confirm.setText("确认本次中性");confirm.setEnabled(false);buttons.addView(confirm,new LinearLayout.LayoutParams(0,-2,1));
        Button clear=new Button(activity);clear.setText("清除本次基准 / 取消采集");body.addView(clear);
        progress=text(activity,"头部与个人基准仅用于本次会话，不写入安装设置。");body.addView(progress);
        actions=text(activity,"等待动作数据");body.addView(actions);
        checked=check(activity,"已核对文字方向、左右眨眼、张口、视线与转头");body.addView(checked);
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
            cancelCollection();checked.setChecked(false);progress.setText("已用于当前预览。请检查动作；退出或更换输入后个人基准失效。");});
        clear.setOnClickListener(v->{clearNeutral();progress.setText("已清除本次基准，采集已取消。");});
        checked.setOnCheckedChangeListener((v,b)->refreshEnabled());
        dialog.setOnDismissListener(d->finish());
        dialog.show();MirrorTheme.safeDialog(activity,dialog);
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
                +(value.modelSha256().isEmpty()?"":" · "+value.modelSha256().substring(0,12))
                +(value.error().isEmpty()?"":"\n"+value.error());
    }
    private long nextRevision(){return Math.addExact(Math.max(draft.revision,calibration.revision()),1);}
    private static TextView text(Activity a,String value){TextView v=new TextView(a);v.setText(value);v.setTextSize(13);return v;}
    private static CheckBox check(Activity a,String value){CheckBox v=new CheckBox(a);v.setText(value);v.setTextSize(13);return v;}
    private void syncControls(){binding=true;rotation.setSelection(draft.rotationDegrees/90);reflect.setChecked(draft.reflectInput);mirror.setChecked(draft.mirrorInteraction);binding=false;}
    private void editInput(){
        if(input==null){syncControls();return;}cancelCollection();checked.setChecked(false);inputChanged=true;
        draft=new CameraControlSettings(input.effective.cameraId,input.effective.fingerprint,640,480,rotation.getSelectedItemPosition()*90,reflect.isChecked(),mirror.isChecked(),nextRevision());
        clearNeutral();host.changeInput(draft);
    }
    private void clearNeutral(){cancelCollection();calibration=new FaceControlCalibration(nextRevision(),draft.mirrorInteraction,null,null);host.changeCalibration(input,calibration);checked.setChecked(false);}
    private void cancelCollection(){synchronized(this){if(collector!=null&&session!=null)collector.cancel(session);collector=null;session=null;ready=null;}confirm.setEnabled(false);}
    private boolean readyForInput(){return input!=null&&draft.matches(input.effective.cameraId,input.effective.fingerprint,640,480)
            &&input.effective.rotationDegrees==draft.rotationDegrees&&input.effective.reflectInput==draft.reflectInput;}
    private boolean avatarReady(){return "READY".equals(avatarPreview.status().state());}
    private void refreshEnabled(){
        boolean ready=readyForInput();collect.setEnabled(ready);rotation.setEnabled(input!=null);reflect.setEnabled(input!=null);mirror.setEnabled(input!=null);
        checked.setEnabled(ready&&avatarReady());
        Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE);if(save!=null)save.setEnabled(ready&&avatarReady()&&writable&&checked.isChecked());
    }
    /** UI-thread update. Identity transitions revoke draft sampling; no obsolete raw frame is relabelled. */
    void tick(Input current,FaceFrame raw,Object rawToken,InteractionController.Snapshot mapped){
        if(isClosed())return;
        if(current!=input){
            boolean hadInput=input!=null;cancelCollection();synchronized(this){input=current;}image.setImageBitmap(null);checked.setChecked(false);
            if(current!=null){
                if(!draft.matches(current.effective.cameraId,current.effective.fingerprint,640,480)){
                    inputChanged=true;draft=current.effective;syncControls();
                }
                if(hadInput)inputChanged=true;
                clearNeutral();
                identity.setText("Camera2 "+current.effective.cameraId+" · 640×480 → "+(current.effective.rotationDegrees%180==0?"640×480":"480×640")
                        +"\n描述指纹 "+current.effective.fingerprint.substring(0,12)+"…（不等同 USB 序列号）\n"+current.warning);
            }else identity.setText("正在释放旧输入 / 重新打开相机……");
        }
        Bitmap preview=takePreview(current==null?null:current.token);if(preview!=null)image.setImageBitmap(preview);
        if(collector!=null){
            NeutralCalibrationCollector.Update update=collector.poll(session,SystemClock::elapsedRealtimeNanos);
            ready=update.result();confirm.setEnabled(collector.isCurrentReady(session,ready));
            progress.setText(collectorStatus(update.status())+" · "+update.samples()+" 个样本 / "+update.stableMillis()+" ms\n"+reason(update.reason())
                    +(ready==null?"":"\n采集完成，点击确认后才用于角色。"));
        }
        if(mapped!=null){
            if(mapped.state()==InteractionController.State.WAITING&&(calibration.hasNeutralPose()||calibration.personalBaseline()!=null)){
                clearNeutral();progress.setText("已离开互动，当前个人基准已清除；可重新采集。");
            }
            float[] w=mapped.blendshapes52();actions.setText(String.format(Locale.ROOT,"角色输入 · 左眨眼 %.2f / 右眨眼 %.2f / 张口 %.2f\n%s",w[9],w[10],w[25],mapped.calibrationError()));}
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
