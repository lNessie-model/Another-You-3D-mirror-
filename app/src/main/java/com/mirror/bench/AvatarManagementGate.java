package com.mirror.bench;

/** Small identity gate; never holds a lock across IO, GL calls, or callbacks. */
final class AvatarManagementGate {
    enum Stage { IDLE, LOADING, GL_INITIALIZING, ACTIVATING }
    static final long LOAD_TIMEOUT_NS=30_000_000_000L,GL_TIMEOUT_NS=20_000_000_000L,ACTIVATE_TIMEOUT_NS=15_000_000_000L;
    static final class Token { private Token(){} }
    static final class Context { private Context(){} }
    static final class Timeout {
        final Token owner;final Stage stage;final long elapsedNs,budgetNs;
        private Timeout(Token owner,Stage stage,long elapsedNs,long budgetNs){this.owner=owner;this.stage=stage;this.elapsedNs=elapsedNs;this.budgetNs=budgetNs;}
    }
    static final class Claim {
        private final Token token;private final Context context;private final Object candidate;
        private Claim(Token t,Context c,Object a){token=t;context=c;candidate=a;}
    }
    private boolean active;
    private Token token;
    private Context context,submittedContext;
    private Object candidate;
    private Claim claim;
    private Stage stage=Stage.IDLE;
    private long startedNs;
    private Timeout pendingTimeout;
    synchronized void resume(){active=true;token=null;context=null;clearPreview();stopDeadline();pendingTimeout=null;}
    synchronized void pause(){active=false;token=null;context=null;clearPreview();stopDeadline();pendingTimeout=null;}
    synchronized Token beginSelection(long nowNs){
        if(!active)return null;
        token=new Token();clearPreview();pendingTimeout=null;startDeadline(Stage.LOADING,nowNs);return token;
    }
    synchronized boolean isCurrent(Token owner){return active&&owner!=null&&owner==token;}
    synchronized Context openContext(long nowNs){
        if(!active||expire(nowNs)||pendingTimeout!=null)return null;
        context=new Context();submittedContext=null;claim=null;
        // An empty GL context must not start a preview budget while CPU loading is pending.
        if(candidate!=null&&stage==Stage.IDLE)startDeadline(Stage.GL_INITIALIZING,nowNs);
        return context;
    }
    synchronized boolean isCurrent(Context owner){return active&&owner!=null&&owner==context;}
    synchronized void contextLost(Context owner,long nowNs){
        if(owner!=null&&owner==context){
            context=null;submittedContext=null;claim=null;
            if(active&&candidate!=null&&stage==Stage.IDLE)startDeadline(Stage.GL_INITIALIZING,nowNs);
        }
    }
    synchronized boolean loaded(Token owner,Object value,long nowNs){
        if(!isCurrent(owner)||value==null||expire(nowNs))return false;
        candidate=value;submittedContext=null;claim=null;startDeadline(Stage.GL_INITIALIZING,nowNs);return true;
    }
    synchronized boolean previewSubmitted(Token owner,Context glContext,Object value,long nowNs){
        if(!isCurrent(owner)||!isCurrent(glContext)||candidate!=value||value==null||expire(nowNs))return false;
        submittedContext=glContext;
        if(stage==Stage.GL_INITIALIZING)stopDeadline();
        return true;
    }
    synchronized void previewFailed(Token owner,Context glContext){
        if(isCurrent(owner)&&isCurrent(glContext)){submittedContext=null;claim=null;}
    }
    synchronized boolean canActivate(Token owner,Object value){
        return isCurrent(owner)&&candidate==value&&value!=null&&context!=null&&submittedContext==context&&claim==null&&stage==Stage.IDLE;
    }
    synchronized Claim claimActivation(Token owner,Object value,long nowNs){
        if(!canActivate(owner,value))return null;
        claim=new Claim(owner,context,value);startDeadline(Stage.ACTIVATING,nowNs);return claim;
    }
    synchronized boolean isValid(Claim value,long nowNs){
        return value!=null&&claim==value&&isCurrent(value.token)&&isCurrent(value.context)
                &&candidate==value.candidate&&submittedContext==value.context&&!expire(nowNs);
    }
    synchronized void activationFailed(Claim value,long nowNs){if(claim==value&&value!=null&&!expire(nowNs)){claim=null;stopDeadline();}}
    synchronized boolean workFailed(Token owner,long nowNs){
        if(!isCurrent(owner)||expire(nowNs))return false;
        stopDeadline();return true;
    }
    /** Also called at successful transitions, so a late callback cannot beat a delayed UI tick. */
    synchronized Timeout pollTimeout(long nowNs){
        expire(nowNs);Timeout result=pendingTimeout;pendingTimeout=null;return result;
    }
    private boolean expire(long nowNs){
        if(!active||stage==Stage.IDLE||token==null||nowNs<startedNs)return false;
        long budget=stage==Stage.LOADING?LOAD_TIMEOUT_NS:stage==Stage.GL_INITIALIZING?GL_TIMEOUT_NS:ACTIVATE_TIMEOUT_NS;
        long elapsed=nowNs-startedNs;
        if(elapsed<budget)return false;
        pendingTimeout=new Timeout(token,stage,elapsed,budget);
        token=null;clearPreview();stopDeadline();return true;
    }
    private void startDeadline(Stage value,long nowNs){stage=value;startedNs=nowNs;}
    private void stopDeadline(){stage=Stage.IDLE;startedNs=0;}
    private void clearPreview(){candidate=null;submittedContext=null;claim=null;}
}
