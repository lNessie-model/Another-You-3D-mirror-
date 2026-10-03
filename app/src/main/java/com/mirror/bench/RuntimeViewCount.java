package com.mirror.bench;

import android.content.Intent;
import android.os.Bundle;

/** Ephemeral debug profile shared by runtime and calibration; never stored in user settings. */
final class RuntimeViewCount {
    static final String EXTRA="test_view_count";
    private RuntimeViewCount() {}
    static int read(Bundle extras,boolean debug) {
        if(extras==null||!extras.containsKey(EXTRA))return 20;
        Object raw=extras.get(EXTRA);
        if(!debug||!(raw instanceof Integer)||((Integer)raw!=16&&(Integer)raw!=20))
            throw new IllegalArgumentException("View count override requires debug Integer 16 or 20");
        return (Integer)raw;
    }
    static Intent forward(Intent intent,int views,boolean debug) {
        if((views!=16&&views!=20)||(!debug&&views!=20))
            throw new IllegalArgumentException("Only debug may forward view count 16 or 20");
        // Release callers do not emit an explicit override that a release receiver must reject.
        if(debug)intent.putExtra(EXTRA,views);
        return intent;
    }
}
