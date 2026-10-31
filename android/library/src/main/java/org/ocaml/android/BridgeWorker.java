package org.ocaml.android;

import android.content.Context;
import androidx.work.*;
import java.util.concurrent.CompletableFuture;

public final class BridgeWorker extends Worker {
    private volatile CompletableFuture<String> running;
    public BridgeWorker(Context c,WorkerParameters p){super(c,p);}
    @Override public Result doWork(){
        try{
            String handler=getInputData().getString("handler"),payload=getInputData().getString("payload");
            if(handler==null)return Result.failure();
            CompletableFuture<String> task=Bridge.deliver(getApplicationContext(),handler,payload==null?"":payload);running=task;
            if(isStopped())task.cancel(false);
            String result=task.get();
            return result.equals("success")?Result.success():result.equals("retry")?Result.retry():Result.failure();
        }catch(Exception e){return Result.failure();}finally{running=null;}
    }
    @Override public void onStopped(){CompletableFuture<String> task=running;if(task!=null)task.cancel(false);super.onStopped();}
}
