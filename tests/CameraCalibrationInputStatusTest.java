package com.mirror.bench;
public final class CameraCalibrationInputStatusTest {
 private static int checks;
 private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
 public static void main(String[] args){
  var waiting=CameraCalibrationInputStatus.describe(false,0,"",InteractionController.State.WAITING,"");
  check(!waiting.usable()&&!waiting.canRetry(),"opening has no fake input or retry error");
  check(waiting.label().contains("准备"),"opening has a preparing message");
  for(var state:InteractionController.State.values()){
   var status=CameraCalibrationInputStatus.describe(true,90,"方向需要核对",state,"");
   check(status.usable()==(state!=InteractionController.State.ERROR),"only healthy opened input is usable: "+state);
   if(state!=InteractionController.State.ERROR)check(status.label().contains("480×640")&&status.label().contains("方向需要核对"),"ready dimensions and warning survive "+state);
   else check(status.canRetry(),"unknown error still offers recovery");
  }
  var failed=CameraCalibrationInputStatus.describe(false,0,"",InteractionController.State.ERROR,"CameraAccessException: CAMERA_ERROR (3): endConfigure: Function not implemented");
  check(!failed.usable()&&failed.canRetry(),"HAL failure cannot allow collection/save");
  check(failed.label().contains("驱动")&&!failed.label().contains("已准备"),"HAL failure shown as a driver failure, not ready");
  check(failed.label().contains("重新连接"),"actionable recovery");
  var permission=CameraCalibrationInputStatus.describe(false,0,"",InteractionController.State.ERROR,"摄像头未授权，请在维护页面重试");
  check(permission.label().contains("权限"),"permission failure has appropriate explanation");
  var stale=CameraCalibrationInputStatus.describe(true,0,"",InteractionController.State.WAITING,"推理中断");
  check(!stale.usable()&&stale.canRetry(),"known runtime fault wins over a retained input identity");
  var recovering=CameraCalibrationInputStatus.describe(false,0,"",InteractionController.State.WAITING,"");
  check(!recovering.canRetry()&&!recovering.label().contains("驱动"),"fault clears on retry opening");
  var noFace=CameraCalibrationInputStatus.describe(true,180,"",InteractionController.State.WAITING,"");
  check(noFace.usable()&&!noFace.canRetry()&&noFace.label().contains("640×480"),"healthy camera with no face remains available");
  System.out.println("CameraCalibrationInputStatusTest: "+checks+" checks passed; presentation and availability only");
 }
}
