package com.mirror.bench;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;

/** Collapsible, screen-plane native controls. Owns no camera, inference or GL resources. */
final class MirrorRuntimeControls {
    interface Host {
        void roles();void scene();void camera();void settings();void home();void recover(MirrorUiState.Action action);
    }
    private final FrameLayout overlay;
    private final View menu;
    private final LinearLayout dock;
    private final TextView title,detail,dockTitle,role;
    private final Button action;
    private MirrorUiState.Status status;
    MirrorRuntimeControls(Activity activity,Host host){
        overlay=new FrameLayout(activity);
        LinearLayout body=MirrorTheme.safeScroll(activity,overlay);menu=(View)body.getParent();
        body.setBackground(MirrorTheme.surface(activity,MirrorTheme.SURFACE));
        role=MirrorTheme.text(activity,"另一个你",22,true);body.addView(role);
        title=MirrorTheme.text(activity,"正在准备魔镜",16,false);body.addView(title);
        detail=MirrorTheme.text(activity,"",13,false);detail.setTextColor(MirrorTheme.MUTED);body.addView(detail);
        action=MirrorTheme.button(activity,"重试",true,()->{if(status!=null)host.recover(status.action());});
        MirrorTheme.addButton(body,action);action.setVisibility(View.GONE);
        MirrorTheme.addButton(body,MirrorTheme.button(activity,"角色",false,()->{hideMenu();host.roles();}));
        MirrorTheme.addButton(body,MirrorTheme.button(activity,"场景与画面",false,()->{hideMenu();host.scene();}));
        MirrorTheme.addButton(body,MirrorTheme.button(activity,"相机与动作校准",false,()->{hideMenu();host.camera();}));
        MirrorTheme.addButton(body,MirrorTheme.button(activity,"设置",false,()->{hideMenu();host.settings();}));
        MirrorTheme.addButton(body,MirrorTheme.button(activity,"返回首页",false,host::home));
        MirrorTheme.addButton(body,MirrorTheme.button(activity,"收起菜单",false,this::hideMenu));
        menu.setVisibility(View.GONE);
        dock=new LinearLayout(activity);dock.setOrientation(LinearLayout.VERTICAL);dock.setGravity(Gravity.CENTER);
        dock.setPadding(MirrorTheme.dp(activity,8),MirrorTheme.dp(activity,4),MirrorTheme.dp(activity,8),MirrorTheme.dp(activity,4));
        dock.setBackground(MirrorTheme.surface(activity,0xdd1b1d22));
        dockTitle=MirrorTheme.text(activity,"正在准备魔镜",13,false);dock.addView(dockTitle);
        MirrorTheme.addButton(dock,MirrorTheme.button(activity,"菜单",false,this::showMenu));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(1,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);overlay.addView(dock,p);
        overlay.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            FrameLayout.LayoutParams d=(FrameLayout.LayoutParams)dock.getLayoutParams();
            int width=(int)((r-l)*.48),margin=(int)((b-t)*.18);
            if(width>0&&(d.width!=width||d.bottomMargin!=margin)){d.width=width;d.bottomMargin=margin;dock.setLayoutParams(d);}
        });
    }
    View view(){return overlay;}
    void updateRole(String name){
        String label=name==null||name.isEmpty()?"另一个你":name;
        if(!label.contentEquals(role.getText()))role.setText(label);
    }
    void showMenu(){menu.setVisibility(View.VISIBLE);dock.setVisibility(View.GONE);title.requestFocus();}
    boolean hideMenu(){
        if(menu.getVisibility()!=View.VISIBLE)return false;
        menu.setVisibility(View.GONE);dock.setVisibility(View.VISIBLE);return true;
    }
    void update(MirrorUiState.Status value){
        if(value.equals(status))return;
        boolean newAction=value.action()!=MirrorUiState.Action.NONE&&(status==null||status.action()==MirrorUiState.Action.NONE);
        status=value;title.setText(value.title());dockTitle.setText(value.title());detail.setText(value.detail());
        detail.setVisibility(value.detail().isEmpty()?View.GONE:View.VISIBLE);
        action.setVisibility(value.action()==MirrorUiState.Action.NONE?View.GONE:View.VISIBLE);
        String label=switch(value.action()){case AUTHORIZE->"授权 / 打开系统设置";case HOME->"返回首页";default->"重试连接";};
        action.setText(label);action.setContentDescription(label);
        if(newAction)showMenu();
    }
}
