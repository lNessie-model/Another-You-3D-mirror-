package com.mirror.bench;

/** Actual lifecycle/selection/context interleavings, without Android or EGL stubs. */
public final class AvatarManagementGateTest {
    private static int checks;
    public static void main(String[] args) {
        AvatarManagementGate gate=new AvatarManagementGate();
        check(gate.beginSelection(0)==null,"inactive page cannot create work");
        gate.resume();var first=gate.beginSelection(0);Object a=new Object(),b=new Object();
        var context=gate.openContext(0);
        check(gate.isCurrent(first),"resumed owner valid");
        check(!gate.canActivate(first,a),"loading alone is not preview acceptance");
        check(gate.loaded(first,a,0),"current loaded result accepted");
        check(!gate.canActivate(first,a),"CPU validation does not authorize activation");
        check(!gate.previewSubmitted(first,context,b,0),"different artifact cannot borrow successful GL draw");
        check(gate.previewSubmitted(first,context,a,0),"actual matching GL submission accepted");
        check(gate.canActivate(first,a),"matching submission permits explicit activation");
        var claim=gate.claimActivation(first,a,0);
        check(claim!=null&&gate.isValid(claim,0),"explicit click claims exact candidate");
        check(gate.claimActivation(first,a,0)==null,"double-click cannot queue another activation");
        gate.pause();
        check(!gate.isValid(claim,0),"pause between click and background commit invalidates claim");
        check(!gate.previewSubmitted(first,context,a,0),"late GL callback after HOME ignored");
        check(!gate.loaded(first,a,0),"late IO callback after HOME ignored");

        gate.resume();var second=gate.beginSelection(0);var secondContext=gate.openContext(0);
        check(!gate.isCurrent(first)&&gate.isCurrent(second),"resume creates new owner");
        gate.loaded(second,b,0);
        check(!gate.previewSubmitted(second,context,b,0),"old EGL context cannot validate new owner");
        gate.previewSubmitted(second,secondContext,b,0);
        var replacementContext=gate.openContext(0);
        check(!gate.canActivate(second,b),"context recreation requires a new successful draw");
        check(!gate.previewSubmitted(second,secondContext,b,0),"late old-context result cannot re-enable button");
        gate.previewSubmitted(second,replacementContext,b,0);
        gate.contextLost(secondContext,0);
        check(gate.canActivate(second,b),"stale context cleanup cannot revoke new preview");
        gate.previewFailed(second,replacementContext);
        check(!gate.canActivate(second,b),"GL failure revokes previous proof");
        gate.previewSubmitted(second,replacementContext,b,0);
        var replacement=gate.beginSelection(0);
        check(!gate.canActivate(second,b)&&!gate.isCurrent(second),"selecting another artifact invalidates old button immediately");
        check(!gate.loaded(second,b,0),"old load cannot replace selection");
        gate.loaded(replacement,a,0);gate.previewSubmitted(replacement,replacementContext,a,0);
        var retry=gate.claimActivation(replacement,a,0);gate.activationFailed(retry,0);
        check(gate.canActivate(replacement,a),"failed commit permits retry after same preview");
        gate.contextLost(replacementContext,0);
        check(!gate.canActivate(replacement,a),"lost context invalidates proof");
        check(!gate.isValid(null,0)&&!gate.isCurrent((AvatarManagementGate.Token)null),"missing owners safe false");
        System.out.println("AvatarManagementGateTest: "+checks+" assertions passed");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
