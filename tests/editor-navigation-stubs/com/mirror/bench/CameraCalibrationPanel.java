package com.mirror.bench;

import android.app.Activity;

/** Panel UI boundary only; exposes the actual activity Host without implementing calibration/save logic. */
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
        final Object token=new Object();final CameraControlSettings effective;final String warning;
        Input(CameraControlSettings controls,String warning){effective=controls;this.warning=warning;}
    }
    static CameraCalibrationPanel last;
    static int opened;
    final Host host;
    CameraCalibrationPanel(Activity a,CameraControlSettings controls,FaceControlCalibration calibration,boolean writable,Host host){
        this.host=host;last=this;opened++;
    }
    void startPreview(){}
    void dismiss(){host.closed(this,false,false);}
    boolean isCurrentReady(Input input,NeutralCalibrationCollector.Session session,NeutralCalibrationCollector.Result result){return false;}
}
