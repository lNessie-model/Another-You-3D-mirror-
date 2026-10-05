package com.mirror.bench;

/** Real production wording/layout branches; no Android or camera fixture needed. */
public final class MirrorUiStateTest {
    private static int checks;
    private static void require(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static MirrorUiState.Status status(String state,boolean face,boolean ready,boolean permission,String fault){
        return MirrorUiState.describe(new MirrorUiState.Sample(state,face,ready,permission,true,false,false,false,fault,""));
    }
    public static void main(String[] args){
        require(status("INTERACTIVE",true,true,true,"").title().equals("正在跟随你"),"Active face follows");
        require(!status("INTERACTIVE",false,true,true,"").title().equals("正在跟随你"),"Lost face cannot claim success");
        require(status("WAITING",false,true,true,"").title().equals("请面对魔镜"),"No face is not a camera fault");
        require(status("ACQUIRING",true,true,true,"").action()==MirrorUiState.Action.NONE,"Acquiring needs patience");
        require(status("WAITING",false,false,true,"").title().equals("正在准备魔镜"),"No submitted frame is initializing");
        require(status("ERROR",false,true,false,"").action()==MirrorUiState.Action.AUTHORIZE,"Missing permission is actionable");
        require(status("ERROR",false,true,true,"CAMERA_ERROR(3): endConfigure").title().equals("摄像头暂时不可用"),"Camera error has reconnect wording");
        require(status("ERROR",false,true,true,"RKNN inference failed").title().equals("暂时无法跟随"),"Inference error is not mislabeled as USB failure");
        require(status("ERROR",false,true,true,"输入资源停止未确认").action()==MirrorUiState.Action.HOME,"Unreleased native owner must not claim reconnect");
        require(status("ERROR",false,false,true,"所选角色无法读取，请返回角色页重新选择。").title().equals("角色无法加载"),"Bad selection is not an endless GL wait");
        require(status("ERROR",false,false,false,"所选角色无法读取").action()==MirrorUiState.Action.HOME,"Role repair precedes asking camera permission");
        require(MirrorUiState.describe(new MirrorUiState.Sample("INTERACTIVE",true,true,true,true,true,false,false,"","")).title().equals("显示暂时不可用"),"Display failure precedes success");
        require(MirrorUiState.describe(new MirrorUiState.Sample("INTERACTIVE",true,true,true,true,false,true,false,"","")).action()==MirrorUiState.Action.RETRY,"Watchdog failure is actionable");
        String warning="已选角色读取失败，暂用内置角色；原角色包保留。";
        var fallback=MirrorUiState.describe(new MirrorUiState.Sample("INTERACTIVE",true,true,true,true,false,false,false,"",warning));
        require(!fallback.title().equals("正在跟随你")&&fallback.title().contains("角色提示"),"Collapsed dock must expose the fallback even while following");
        require(fallback.detail().contains(warning),"Exact loader warning persists in the menu");
        var waitingFallback=MirrorUiState.describe(new MirrorUiState.Sample("WAITING",false,true,true,true,false,false,false,"",warning));
        require(waitingFallback.title().contains("角色提示")&&waitingFallback.detail().contains(warning),"No-face waiting must not erase the fallback");
        var cameraAndFallback=MirrorUiState.describe(new MirrorUiState.Sample("ERROR",false,true,true,true,false,false,false,"CAMERA_ERROR(3)",warning));
        require(cameraAndFallback.title().contains("摄像头暂时不可用")&&cameraAndFallback.detail().contains(warning)&&cameraAndFallback.action()==MirrorUiState.Action.RETRY,"Camera recovery and role warning remain visible together");
        for(int[] size:new int[][]{{1000,1600},{1200,1920},{320,512},{1080,1920}}){
            int[] r=MirrorUiState.safeBounds(size[0],size[1]);
            double x=r[2]/(2.0*size[0]*.46),y=r[3]/(2.0*size[1]*.46);
            require(x*x+y*y<1,"Entire safe rectangle fits inset ellipse");
            require(r[0]>=0&&r[1]>=0&&r[0]+r[2]<=size[0]&&r[1]+r[3]<=size[1],"Bounds stay on screen");
        }
        try{MirrorUiState.safeBounds(0,1600);throw new AssertionError("Invalid screen accepted");}
        catch(IllegalArgumentException expected){checks++;}
        System.out.println("MirrorUiStateTest: "+checks+" checks passed");
    }
}
