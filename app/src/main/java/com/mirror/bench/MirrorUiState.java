package com.mirror.bench;

import java.util.Locale;

/** Product wording and oval layout math, independent of camera/GL owners and Android views. */
final class MirrorUiState {
    enum Action { NONE, AUTHORIZE, RETRY, HOME }
    record Status(String title,String detail,Action action) {}
    record Sample(String state,boolean facePresent,boolean frameReady,boolean permissionGranted,
                  boolean needsCamera,boolean renderFault,boolean progressFault,boolean releasing,String fault,String avatarWarning) {}
    private MirrorUiState() {}

    static Status describe(Sample s) {
        Status base=describeState(s);
        String warning=s.avatarWarning()==null?"":s.avatarWarning();
        if(warning.isEmpty())return base;
        return new Status("角色提示 · "+base.title(),warning+(base.detail().isEmpty()?"":"\n"+base.detail()),base.action());
    }
    private static Status describeState(Sample s) {
        String fault=s.fault()==null?"":s.fault();
        if(fault.contains("所选角色无法读取"))
            return new Status("角色无法加载","你的选择已保留。请返回首页，打开角色页重新选择。",Action.HOME);
        if(s.needsCamera()&&!s.permissionGranted())
            return new Status("需要摄像头权限","允许使用摄像头，才能让镜中的角色跟随你。",Action.AUTHORIZE);
        if(s.renderFault())
            return new Status("显示暂时不可用","画面没有准备完成。请重试；你的角色与设置会保留。",Action.RETRY);
        if(!s.frameReady())return new Status("正在准备魔镜","角色就绪后，魔镜会开始寻找你。",Action.NONE);
        if(s.releasing())return new Status("正在重新连接","正在释放上一运行的设备资源，请稍候。",Action.NONE);
        if(s.progressFault())return new Status("暂时无法跟随","处理等待超时。请重试；若仍无响应，请返回首页后重新进入。",Action.RETRY);
        if("ERROR".equals(s.state())) {
            if(fault.contains("停止未确认"))
                return new Status("需要重新打开应用","设备资源尚未释放。请返回首页；若仍无法连接，请在系统设置中强制停止后重开。",Action.HOME);
            String lower=fault.toLowerCase(Locale.ROOT);
            if(lower.contains("camera")||lower.contains("usb")||fault.contains("摄像头")||fault.contains("流配置"))
                return new Status("摄像头暂时不可用","检查 USB 连接，重新插拔摄像头后点击重试。你的设置不会被清除。",Action.RETRY);
            return new Status("暂时无法跟随","请重试。可在设置的维护详情查看原因。",Action.RETRY);
        }
        if("INTERACTIVE".equals(s.state())&&s.facePresent())
            return new Status("正在跟随你","",Action.NONE);
        if("ACQUIRING".equals(s.state()))
            return new Status("正在与你对齐","保持脸部在画面中央，稍候片刻。",Action.NONE);
        if("GRACE".equals(s.state())||"INTERACTIVE".equals(s.state()))
            return new Status("重新寻找你","靠近魔镜，让脸部回到画面中央。",Action.NONE);
        return new Status("请面对魔镜","让脸部位于画面中央，试着微笑或眨眼。",Action.NONE);
    }

    /** Full content rectangle stays within an ellipse with radii 46% of each screen dimension. */
    static int[] safeBounds(int width,int height) {
        if(width<1||height<1)throw new IllegalArgumentException("Positive screen dimensions required");
        int w=Math.max(1,(int)(width*.64)),h=Math.max(1,(int)(height*.62));
        return new int[]{(width-w)/2,(height-h)/2,w,h};
    }
}
