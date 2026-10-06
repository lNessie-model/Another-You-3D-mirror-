package com.mirror.bench;

/** Presentation only: hardware ownership and retry remain with MirrorActivity. */
final class CameraCalibrationInputStatus {
    record Status(String label, boolean usable, boolean canRetry) {}
    static Status describe(boolean opened,int rotation,String warning,InteractionController.State state,String error){
        boolean fault=state==InteractionController.State.ERROR||(error!=null&&!error.isBlank());
        if(fault){
            String reason="当前相机或面捕处理无法运行。";
            String detail=error==null?"":error;
            if(detail.contains("未授权")||detail.contains("permission")||detail.contains("Permission"))
                reason="需要相机权限才能采集画面。";
            else if(detail.contains("CAMERA_ERROR")||detail.contains("configure")||detail.contains("Configure"))
                reason="相机驱动无法启动采集。若持续失败，请在设备旁检查 USB 连接。";
            else if(detail.contains("No Camera2")||detail.contains("disconnected"))
                reason="未找到可采集的相机，请检查 USB 连接。";
            return new Status("相机与面捕暂不可用\n"+reason+"\n点击“重新连接相机”重试；原安装设置保持不变。",false,true);
        }
        if(!opened)return new Status("正在准备相机……\n角色预览可用于查看取景；相机可用后才能检查动作并保存。",false,false);
        String size=rotation%180==0?"640×480":"480×640";
        return new Status("相机已准备 · "+size+(warning==null||warning.isEmpty()?"":"\n"+warning),true,false);
    }
}
