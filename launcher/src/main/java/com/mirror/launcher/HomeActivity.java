package com.mirror.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small offline launcher; no background services, permissions or external SDK. */
public class HomeActivity extends Activity {
    @Override public void onCreate(Bundle state) { super.onCreate(state); showApps(); }
    @Override protected void onResume() { super.onResume(); showApps(); }
    private void showApps() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("魔镜设备 · 应用与设置");
        title.setTextSize(24);
        title.setPadding(24,32,24,24);
        layout.addView(title);
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(query, 0);
        Collections.sort(apps, new ResolveInfo.DisplayNameComparator(getPackageManager()));
        ArrayList<String> names = new ArrayList<>();
        names.add("系统设置");
        for (ResolveInfo info : apps) names.add(info.loadLabel(getPackageManager()).toString());
        ListView list = new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,names));
        list.setOnItemClickListener((parent,view,position,id) -> {
            if(position==0) { startActivity(new Intent(Settings.ACTION_SETTINGS)); return; }
            ResolveInfo info=apps.get(position-1);
            Intent launch=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(info.activityInfo.packageName,info.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launch);
        });
        layout.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(layout);
    }
}
