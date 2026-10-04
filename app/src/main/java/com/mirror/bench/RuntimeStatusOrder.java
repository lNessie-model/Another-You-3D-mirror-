package com.mirror.bench;

import java.util.UUID;

/** Process-wide status epochs and ordered snapshot tickets. No filesystem work under this lock.
 * A publisher serializes disk writes separately, checks mayPublish before commit, then calls published.
 * Old work always retains its original Epoch; it may never borrow the current one on completion.
 */
public final class RuntimeStatusOrder {
    public static final class Epoch {
        private final String id=UUID.randomUUID().toString();
        private final long number,startedNs;
        private Epoch(long number,long startedNs){this.number=number;this.startedNs=startedNs;}
        public String id(){return id;}
        public long number(){return number;}
        public long startedNs(){return startedNs;}
    }
    public static final class Ticket {
        private final Epoch epoch;
        private final long sequence,capturedNs;
        private Ticket(Epoch epoch,long sequence,long capturedNs){this.epoch=epoch;this.sequence=sequence;this.capturedNs=capturedNs;}
        public Epoch epoch(){return epoch;}
        public long sequence(){return sequence;}
        public long capturedNs(){return capturedNs;}
    }
    private Epoch current;
    private long epochNumber,nextSequence,publishedSequence,publishedNs;
    public synchronized Epoch begin(long nowNs) {
        if(nowNs<0||(current!=null&&nowNs<current.startedNs))throw new IllegalArgumentException("Epoch time must be monotonic elapsed realtime");
        current=new Epoch(++epochNumber,nowNs);nextSequence=0;publishedSequence=0;publishedNs=nowNs;
        return current;
    }
    /** Returns null for obsolete owners; a rejected owner cannot mint a ticket for a later epoch. */
    public synchronized Ticket capture(Epoch owner,long nowNs) {
        if(owner==null||owner!=current)return null;
        if(nowNs<owner.startedNs)throw new IllegalArgumentException("Snapshot precedes its epoch");
        return new Ticket(owner,++nextSequence,nowNs);
    }
    public synchronized boolean mayPublish(Ticket ticket) {
        // A caller can sample its clock, be descheduled, then obtain a ticket after another caller.
        // Time orders snapshots; the ticket sequence only breaks an equal-clock tie.
        return ticket!=null&&ticket.epoch==current&&(ticket.capturedNs>publishedNs
                ||(ticket.capturedNs==publishedNs&&ticket.sequence>publishedSequence));
    }
    /** Marks only a completed disk publication, so a failed write does not discard still-valid work. */
    public synchronized boolean published(Ticket ticket) {
        if(!mayPublish(ticket))return false;
        publishedSequence=ticket.sequence;publishedNs=ticket.capturedNs;return true;
    }
}
