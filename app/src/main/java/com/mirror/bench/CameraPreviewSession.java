package com.mirror.bench;

/** Pure bounded preview ownership/deadlines. A late load, GL frame or error cannot revive an owner. */
final class CameraPreviewSession {
    enum State { PAUSED,LOADING,WAITING_FOR_GL,READY,ERROR,CLOSED }
    static final long LOAD_TIMEOUT_NS=30_000_000_000L,GL_TIMEOUT_NS=20_000_000_000L;
    static final class Token {private Token(){}}
    private Token owner;
    private State state=State.PAUSED;
    private long stageStartedNs;
    private String error="";
    synchronized Token begin(long now){
        if(state==State.CLOSED)throw new IllegalStateException("Preview is closed");
        if(now<0)throw new IllegalArgumentException("Negative clock");
        owner=new Token();state=State.LOADING;stageStartedNs=now;error="";return owner;
    }
    synchronized boolean accepts(Token token){return token!=null&&token==owner&&(state==State.LOADING||state==State.WAITING_FOR_GL||state==State.READY);}
    synchronized boolean loaded(Token token,long now){
        if(!accepts(token)||state!=State.LOADING)return false;
        poll(now);if(!accepts(token))return false;
        state=State.WAITING_FOR_GL;stageStartedNs=now;return true;
    }
    synchronized boolean rendered(Token token,long now){
        if(!accepts(token)||state==State.LOADING)return false;
        poll(now);if(!accepts(token))return false;state=State.READY;return true;
    }
    synchronized void contextRecreated(Token token,long now){
        if(accepts(token)&&state==State.READY){state=State.WAITING_FOR_GL;stageStartedNs=now;}
    }
    synchronized void fail(Token token,String reason){
        if(!accepts(token))return;state=State.ERROR;error=reason==null?"Preview failed":reason.substring(0,Math.min(512,reason.length()));
    }
    synchronized void poll(long now){
        if(state!=State.LOADING&&state!=State.WAITING_FOR_GL)return;
        if(now<stageStartedNs){fail(owner,"Preview clock moved backward");return;}
        long limit=state==State.LOADING?LOAD_TIMEOUT_NS:GL_TIMEOUT_NS;
        if(now-stageStartedNs>=limit)fail(owner,state==State.LOADING?"Avatar loading exceeded 30 seconds":"Avatar GL preview exceeded 20 seconds");
    }
    synchronized void pause(){if(state==State.CLOSED)return;owner=null;state=State.PAUSED;error="";}
    synchronized void close(){owner=null;state=State.CLOSED;error="";}
    synchronized State state(){return state;}
    synchronized String error(){return error;}
}
