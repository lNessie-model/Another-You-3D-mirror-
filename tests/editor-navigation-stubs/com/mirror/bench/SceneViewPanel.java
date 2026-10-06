package com.mirror.bench;

import android.app.Activity;

/** UI boundary exposes the real activity-owned Host; no save/draft/dialog logic is simulated. */
final class SceneViewPanel {
    interface Host {void preview(SceneViewSettings value);void closed();}
    static SceneViewPanel last;
    final Host host;
    SceneViewPanel(Activity activity,SceneViewSettings original,Host host,int views){this.host=host;last=this;}
    void show(){}
    void dismiss(){host.closed();}
}
