package com.mirror.bench;

/** Status publication ordering, independent of Android and filesystem scheduling. */
public final class RuntimeStatusOrderTest {
    private static int checks;
    public static void main(String[] args) {
        RuntimeStatusOrder order=new RuntimeStatusOrder();
        var first=order.begin(100);var initial=order.capture(first,100);
        check(order.mayPublish(initial),"first initialization can publish");
        check(order.published(initial),"first initialization accepted");
        check(!order.mayPublish(initial)&&!order.published(initial),"same ticket is not published twice");
        var older=order.capture(first,110);var newer=order.capture(first,120);
        check(order.published(newer),"newer result can complete first");
        check(!order.published(older),"late older result cannot roll back state");
        var staleTime=order.capture(first,115);
        check(!order.mayPublish(staleTime),"new sequence with old sample time cannot roll back timestamp");
        var sameTime=order.capture(first,120);check(order.published(sameTime),"equal time is ordered by unique ticket sequence");

        var oldWorkerTicket=order.capture(first,130);var second=order.begin(200);
        check(!first.id().equals(second.id()),"every resume gets a distinct externally visible session id");
        check(second.number()>first.number(),"epochs advance across Activity objects sharing the order");
        check(order.capture(first,210)==null,"old worker cannot obtain a new-epoch ticket");
        check(!order.mayPublish(oldWorkerTicket)&&!order.published(oldWorkerTicket),"old epoch cannot publish after resume");
        var secondInitial=order.capture(second,200);var secondSuccess=order.capture(second,220);
        check(order.published(secondSuccess),"real result can beat asynchronous initialization write");
        check(!order.published(secondInitial),"late initialization cannot replace successful result");
        check(!order.mayPublish(null)&&!order.published(null),"cancelled/old ticket safe no-op");

        var third=order.begin(300);var error=order.capture(third,300);
        check(order.published(error),"initial locked ERROR uses same ordering without a fake success state");
        var retry=order.capture(third,301);check(order.mayPublish(retry)&&order.mayPublish(retry),"preflight checks do not commit disk publication");
        check(order.published(retry),"successful AtomicFile commit advances order");
        rejects(()->order.begin(299),"backward resume timestamp");
        rejects(()->order.capture(third,299),"sample before epoch start");
        rejects(()->order.begin(-1),"elapsed-realtime timestamps are nonnegative");
        check(order.capture(third,302)!=null,"invalid calls did not corrupt active epoch");
        var fourth=order.begin(400);
        var clockNewer=order.capture(fourth,420);var scheduledLater=order.capture(fourth,410);
        check(order.published(scheduledLater),"later scheduling can publish an older clock sample first");
        check(order.published(clockNewer),"newer time wins even if its ticket was allocated first");
        check(!order.published(scheduledLater),"clock regression remains rejected after concurrent sampling order");
        System.out.println("RuntimeStatusOrderTest: "+checks+" assertions passed");
    }
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static void rejects(Runnable action,String label){try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("Expected rejection: "+label);}
}
