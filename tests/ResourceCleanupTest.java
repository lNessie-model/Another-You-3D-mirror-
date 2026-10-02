package com.mirror.bench;

import java.util.ArrayList;
import java.util.List;

/** Fault injection for the actual cleanup collector used by CameraBenchActivity. */
public final class ResourceCleanupTest {
    private static int assertions;

    public static void main(String[] args) {
        successfulAndMissingResources();
        everyFailurePositionStillClosesAllResources();
        originalFailureSurvivesMultipleCloseFailures();
        reusedThrowableDoesNotPreventRemainingCleanup();
        System.out.println("ResourceCleanupTest passed: "+assertions+" assertions");
    }

    private static void successfulAndMissingResources() {
        ResourceCleanup cleanup=new ResourceCleanup(null);
        List<String> closed=new ArrayList<>();
        cleanup.close("missing",null);
        cleanup.close("camera",()->closed.add("camera"));
        cleanup.close("model",()->closed.add("model"));
        check(closed.equals(List.of("camera","model")),"successful resources close");
        check(cleanup.failure()==null,"success has no error");
        check(cleanup.errors().isEmpty(),"success has no cleanup diagnostics");
    }

    private static void everyFailurePositionStillClosesAllResources() {
        for(int failed=0;failed<7;failed++) {
            ResourceCleanup cleanup=new ResourceCleanup(null);
            int[] attempts=new int[7];
            AssertionError injected=new AssertionError("close "+failed);
            for(int index=0;index<7;index++) {
                int current=index,failedIndex=failed;
                cleanup.close("resource-"+index,()->{
                    attempts[current]++;
                    if(current==failedIndex) throw injected;
                });
            }
            for(int count:attempts) check(count==1,"all seven closes attempted exactly once");
            check(cleanup.failure()==injected,"cleanup-only failure becomes primary");
            check(cleanup.errors().size()==1,"one diagnostic for one failure");
            check(cleanup.errors().get(0).resource().equals("resource-"+failed),"diagnostic identifies resource");
            check(cleanup.errors().get(0).error()==injected,"diagnostic retains throwable");
        }
    }

    private static void originalFailureSurvivesMultipleCloseFailures() {
        IllegalStateException original=new IllegalStateException("inference");
        Exception cameraError=new Exception("camera close");
        LinkageError modelError=new LinkageError("model close");
        ResourceCleanup cleanup=new ResourceCleanup(original);
        int[] finalCloses={0};
        cleanup.close("camera",()->{ throw cameraError; });
        cleanup.close("model",()->{ throw modelError; });
        cleanup.close("converter",()->finalCloses[0]++);
        check(cleanup.failure()==original,"original operation failure retained by identity");
        check(original.getSuppressed().length==2,"both cleanup failures suppressed");
        check(original.getSuppressed()[0]==cameraError,"first suppressed error retained");
        check(original.getSuppressed()[1]==modelError,"second suppressed error retained");
        check(cleanup.errors().size()==2,"both named diagnostics retained");
        check(finalCloses[0]==1,"Error from native cleanup does not skip converter");

        ResourceCleanup cleanupOnly=new ResourceCleanup(null);
        cleanupOnly.close("camera",()->{ throw cameraError; });
        cleanupOnly.close("model",()->{ throw modelError; });
        check(cleanupOnly.failure()==cameraError,"first cleanup error remains primary");
        check(cameraError.getSuppressed().length==1&&cameraError.getSuppressed()[0]==modelError,
                "later cleanup error is suppressed onto first");
    }

    private static void reusedThrowableDoesNotPreventRemainingCleanup() {
        RuntimeException reused=new RuntimeException("same exception instance");
        ResourceCleanup cleanup=new ResourceCleanup(reused);
        int[] finalCloses={0};
        cleanup.close("camera",()->{ throw reused; });
        cleanup.close("model",()->{ throw reused; });
        cleanup.close("converter",()->finalCloses[0]++);
        check(cleanup.failure()==reused,"reused error preserves original");
        check(reused.getSuppressed().length==0,"self-suppression avoided");
        check(cleanup.errors().size()==2,"both failing resources still diagnosed");
        check(finalCloses[0]==1,"self-suppression does not abort cleanup");
    }

    private static void check(boolean condition,String message) {
        assertions++;
        if(!condition) throw new AssertionError(message);
    }
}
