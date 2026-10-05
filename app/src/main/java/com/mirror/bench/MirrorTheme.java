package com.mirror.bench;

import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.ListAdapter;
import android.widget.BaseAdapter;

/** Small native design system: dark metal, wine red and aged gold; no animated GPU effects. */
final class MirrorTheme {
    static final int BACKGROUND=0xff101216,SURFACE=0xf51b1d22,INK=0xffeee7de,MUTED=0xffbdb6ae;
    static final int WINE=0xff6f293c,METAL=0xff928575,GOLD=0xffd1ba91;
    private MirrorTheme() {}
    static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    static void immersive(Activity activity){
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                |View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    static GradientDrawable surface(Context context,int fill){
        GradientDrawable shape=new GradientDrawable();shape.setColor(fill);
        shape.setCornerRadius(dp(context,12));shape.setStroke(dp(context,1),METAL);return shape;
    }
    static TextView text(Context context,String value,int size,boolean title){
        TextView view=new TextView(context);view.setText(value);view.setTextColor(title?GOLD:INK);view.setTextSize(size);
        view.setGravity(Gravity.CENTER);view.setPadding(dp(context,4),dp(context,4),dp(context,4),dp(context,4));
        if(title)view.setTypeface(Typeface.create("serif",Typeface.NORMAL));
        if(title&&Build.VERSION.SDK_INT>=28)view.setAccessibilityHeading(true);
        return view;
    }
    static Button button(Context context,String label,boolean primary,Runnable action){
        Button button=new Button(context);button.setText(label);button.setContentDescription(label);button.setAllCaps(false);
        button.setTextColor(INK);button.setTextSize(16);button.setMinHeight(dp(context,52));button.setMinimumHeight(dp(context,52));
        button.setPadding(dp(context,12),dp(context,8),dp(context,12),dp(context,8));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44d1ba91),surface(context,primary?WINE:SURFACE),null));
        button.setOnClickListener(v->action.run());return button;
    }
    static void addButton(LinearLayout parent,Button button){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(parent.getContext(),8);parent.addView(button,p);
    }
    static Spinner selectionSpinner(Activity activity,String label){return new SelectionSpinner(activity,label);}
    /** Uses public Dialog APIs: native Spinner exposes no hook to size its internal popup. */
    private static final class SelectionSpinner extends Spinner {
        private final ContextThemeWrapper popupContext;
        private final Activity owner;
        private AlertDialog selection;
        SelectionSpinner(Activity activity,String label){this(activity,label,new ContextThemeWrapper(activity,R.style.MirrorSelectionDialog));}
        private SelectionSpinner(Activity activity,String label,ContextThemeWrapper popup){
            super(activity,null,android.R.attr.spinnerStyle,0,Spinner.MODE_DIALOG,popup.getTheme());
            owner=activity;popupContext=popup;setPrompt(label);setContentDescription(label);setMinimumHeight(dp(activity,48));
        }
        @Override public boolean performClick(){
            if(!isEnabled()||owner.isFinishing()||owner.isDestroyed())return false;
            if(selection!=null)return true;
            if(!isAttachedToWindow()||!isShown()||!hasWindowFocus())return false;
            SpinnerAdapter source=getAdapter();if(source==null||source.getCount()==0)return false;
            BaseAdapter rows=new BaseAdapter(){
                public int getCount(){return source.getCount();}
                public Object getItem(int position){return source.getItem(position);}
                public long getItemId(int position){return source.getItemId(position);}
                @Override public boolean hasStableIds(){return source.hasStableIds();}
                @Override public boolean areAllItemsEnabled(){return !(source instanceof ListAdapter list)||list.areAllItemsEnabled();}
                @Override public boolean isEnabled(int position){return !(source instanceof ListAdapter list)||list.isEnabled(position);}
                public View getView(int position,View recycled,ViewGroup parent){
                    View row=source.getDropDownView(position,recycled,parent);row.setMinimumHeight(dp(getContext(),48));
                    ViewGroup.LayoutParams layout=row.getLayoutParams();
                    if(layout!=null&&layout.height<dp(getContext(),48)){layout.height=dp(getContext(),48);row.setLayoutParams(layout);}
                    if(row instanceof TextView label)label.setTextColor(INK);return row;
                }
            };
            AlertDialog dialog=new AlertDialog.Builder(popupContext).setTitle(getPrompt())
                    .setSingleChoiceItems(rows,getSelectedItemPosition(),(chosen,position)->{
                        setSelection(position);performItemClick(null,position,source.getItemId(position));chosen.dismiss();
                    }).create();
            Application.ActivityLifecycleCallbacks lifetime=new Application.ActivityLifecycleCallbacks(){
                public void onActivityPaused(Activity activity){if(activity==owner)dialog.dismiss();}
                public void onActivityDestroyed(Activity activity){if(activity==owner)dialog.dismiss();}
                public void onActivityCreated(Activity activity,Bundle state){}
                public void onActivityStarted(Activity activity){}
                public void onActivityResumed(Activity activity){}
                public void onActivityStopped(Activity activity){}
                public void onActivitySaveInstanceState(Activity activity,Bundle state){}
            };
            selection=dialog;owner.getApplication().registerActivityLifecycleCallbacks(lifetime);
            dialog.setOnDismissListener(d->{owner.getApplication().unregisterActivityLifecycleCallbacks(lifetime);if(selection==dialog)selection=null;});
            try{dialog.show();}catch(RuntimeException failure){owner.getApplication().unregisterActivityLifecycleCallbacks(lifetime);selection=null;throw failure;}
            if(dialog.getListView()!=null&&getSelectedItemPosition()>=0)dialog.getListView().setSelection(getSelectedItemPosition());
            var metrics=getResources().getDisplayMetrics();
            if(dialog.getWindow()!=null){
                dialog.getWindow().setGravity(Gravity.CENTER);
                dialog.getWindow().setLayout((int)(metrics.widthPixels*.64),(int)(metrics.heightPixels*.66));
                dialog.getWindow().setBackgroundDrawable(surface(getContext(),SURFACE));
            }
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);return true;
        }
        @Override protected void onDetachedFromWindow(){if(selection!=null)selection.dismiss();super.onDetachedFromWindow();}
    }
    static FrameLayout page(Activity activity){
        FrameLayout page=new FrameLayout(activity);page.setBackgroundColor(BACKGROUND);
        page.addView(new Ornament(activity),new FrameLayout.LayoutParams(-1,-1));return page;
    }
    static LinearLayout safeScroll(Activity activity,FrameLayout root){
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.setClipToPadding(true);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(dp(activity,14),dp(activity,12),dp(activity,14),dp(activity,12));scroll.addView(body);
        placeSafe(root,scroll);return body;
    }
    static void placeSafe(FrameLayout root,View content){
        root.addView(content,new FrameLayout.LayoutParams(1,1,Gravity.CENTER));
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(r-l<1||b-t<1)return;
            int[] bounds=MirrorUiState.safeBounds(r-l,b-t);
            FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)content.getLayoutParams();
            if(p.width!=bounds[2]||p.height!=bounds[3]){p.width=bounds[2];p.height=bounds[3];content.setLayoutParams(p);}
        });
    }
    static void safeDialog(Activity activity,Dialog dialog){
        if(dialog.getWindow()==null)return;
        var metrics=activity.getResources().getDisplayMetrics();int[] bounds=MirrorUiState.safeBounds(metrics.widthPixels,metrics.heightPixels);
        dialog.getWindow().setGravity(Gravity.CENTER);dialog.getWindow().setLayout(bounds[2],bounds[3]);
        dialog.getWindow().setBackgroundDrawable(surface(activity,SURFACE));
        styleDialogControls(activity,dialog.getWindow().getDecorView());
    }
    /** Presentation only: keep the existing calibration/draft listeners and persistence intact. */
    private static void styleDialogControls(Context context,View view){
        ColorStateList textColors=new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{0xff8d867e,INK});
        if(view instanceof Button button){
            button.setMinHeight(dp(context,52));button.setMinimumHeight(dp(context,52));button.setAllCaps(false);button.setTextColor(textColors);
            if(button.getContentDescription()==null)button.setContentDescription(button.getText());
            if(button instanceof CompoundButton check){
                check.setButtonTintList(new ColorStateList(new int[][]{{-android.R.attr.state_enabled},{}},new int[]{METAL,GOLD}));
            }else button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44d1ba91),surface(context,SURFACE),null));
        }else if(view instanceof TextView label)label.setTextColor(INK);
        if(view instanceof SeekBar||view instanceof Spinner){
            int minimum=dp(context,48);view.setMinimumHeight(minimum);
            ViewGroup.LayoutParams layout=view.getLayoutParams();
            // AbsSeekBar's wrap-content measurement can ignore View.minimumHeight.
            // An exact child height makes the entire 48dp rectangle touchable.
            if(layout!=null&&layout.height!=ViewGroup.LayoutParams.MATCH_PARENT&&layout.height<minimum){
                layout.height=minimum;view.setLayoutParams(layout);
            }
        }
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)styleDialogControls(context,group.getChildAt(i));
    }
    private static final class Ornament extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Ornament(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){
            float w=getWidth(),h=getHeight(),cx=w/2,cy=h/2;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(getContext(),1));paint.setColor(0x77928575);
            canvas.drawOval(w*.06f,h*.055f,w*.94f,h*.945f,paint);
            paint.setColor(0x447c3548);canvas.drawOval(w*.085f,h*.073f,w*.915f,h*.927f,paint);
            paint.setColor(GOLD);float y=h*.16f,unit=dp(getContext(),9);
            canvas.drawLine(cx-unit*4,y,cx-unit,y,paint);canvas.drawLine(cx+unit,y,cx+unit*4,y,paint);
            canvas.drawCircle(cx,y,unit*.72f,paint);canvas.drawCircle(cx,y,unit*.35f,paint);
            paint.setColor(0x55928575);canvas.drawLine(cx-w*.14f,cy+h*.335f,cx+w*.14f,cy+h*.335f,paint);
        }
    }
}
