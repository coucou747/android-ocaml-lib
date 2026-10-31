package org.ocaml.android;

import android.app.job.*;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.*;

public final class BridgeJobService extends JobService {
    private final ConcurrentHashMap<Integer,CompletableFuture<String>> jobs=new ConcurrentHashMap<>();
    @Override public boolean onStartJob(JobParameters p){
        try {
            CompletableFuture<String> future=Bridge.deliver(this,p.getExtras().getString("handler"),p.getExtras().getString("payload",""));
            jobs.put(p.getJobId(),future);
            future.whenComplete((result,error)->new Handler(Looper.getMainLooper()).post(()->{
                if(jobs.remove(p.getJobId(),future))jobFinished(p,error!=null || "retry".equals(result));
            }));
            return true;
        }catch(Exception e){return false;}
    }
    @Override public boolean onStopJob(JobParameters p){CompletableFuture<String> f=jobs.remove(p.getJobId());if(f!=null)f.cancel(false);return true;}
}
