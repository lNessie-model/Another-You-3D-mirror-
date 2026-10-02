package com.mirror.bench;

/** Deterministic monotonic-clock tests; no sleep, Android or worker scheduling dependencies. */
public final class AvatarPoseProgressWatchdogTest {
    private static int checks;
    private static final long S=1_000_000_000L;
    public static void main(String[] args) {
        AvatarPoseProgressWatchdog w=new AvatarPoseProgressWatchdog();
        check(!w.check(0),"first check starts initialization grace even at zero clock");
        check(w.currentResultAgeMs(0)==-1,"initial grace is not a successful result");
        check(!w.check(2*S-1),"initial grace before endpoint");check(w.check(2*S),"exact timeout endpoint");
        w.resume(3*S);check(!w.check(3*S),"explicit resume grants one recovery interval");
        check(w.applied(3*S,3*S+500_000_000L),"exact freshness endpoint accepted");
        check(w.currentResultAgeMs(4*S)==1000,"result age grows after publication");
        check(!w.check(5*S),"confirmed progress extends deadline");check(w.check(5*S+500_000_000L),"deadline derives from confirmed apply time");

        w=new AvatarPoseProgressWatchdog();w.resume(0);
        check(!w.check(1_100_000_000L),"slow render first gap");
        check(w.check(2_200_000_000L),"a second >1s render gap cannot masquerade as worker progress");
        check(w.check(100*S),"even a long delayed GL frame cannot reset timeout");
        w.resume(101*S);check(!w.check(101*S),"actual HOME resume explicitly restarts grace");
        check(w.currentResultAgeMs(101*S)==-1,"resume never invents a result");
        check(!w.applied(100*S,101*S+1),"stale result rejected");
        check(w.check(103*S),"stale application does not reset progress");

        w=new AvatarPoseProgressWatchdog();w.resume(10*S);check(w.applied(10*S,10*S),"zero-latency apply");
        w.resume(20*S);check(w.currentResultAgeMs(20*S)==10000,"resume retains truthful old result age");
        check(!w.check(21*S),"idle 10fps / slower polling can share same progress deadline");
        check(w.applied(21*S,21*S+10),"new progress after resumed grace");
        check(w.stalledMs(21*S+10)==0,"successful apply progress clock");
        AvatarPoseProgressWatchdog saved=w;
        rejects(()->saved.applied(23*S,22*S),"future submitted timestamp");
        check(w.stalledMs(22*S)==999,"invalid future timestamp did not mutate confirmed progress");
        rejects(()->saved.check(20*S),"clock regression rejected");
        check(w.currentResultAgeMs(22*S)==1000,"invalid call did not mutate retained source timestamp");

        w=new AvatarPoseProgressWatchdog();w.resume(-S);check(w.applied(-S,-S+5),"nanoTime need not be positive");
        check(!w.check(0),"negative nanoTime interval works");
        check(w.check(S+5),"negative-origin exact deadline works");
        w=new AvatarPoseProgressWatchdog();w.resume(Long.MAX_VALUE-S);
        check(!w.check(Long.MIN_VALUE+100),"signed nanoTime wrap within supported delta");
        check(w.check(Long.MIN_VALUE+S),"deadline across signed nanoTime wrap");
        System.out.println("AvatarPoseProgressWatchdogTest: "+checks+" assertions passed");
    }
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static void rejects(Runnable action,String label){try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("Expected rejection: "+label);}
}
