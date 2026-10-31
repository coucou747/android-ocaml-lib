package org.ocaml.android;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import android.view.WindowManager;
import android.widget.*;
import java.lang.ref.WeakReference;
import java.util.*;

/** Generic alarm presentation. Sound and rescheduling belong to the OCaml handlers. */
public final class AlarmScreenActivity extends Activity {
    private static final String CONFIG="alarm_screen";
    private static final Map<String,WeakReference<AlarmScreenActivity>> live=new HashMap<>();
    private String[] config;
    private WeakReference<AlarmScreenActivity> reference;
    private boolean handled;
    private static void validate(String[] a){
        if(a.length!=9 || a[0].isEmpty() || a[4].isEmpty() || a[5].isEmpty() || a[6].isEmpty() || a[7].isEmpty())
            throw new IllegalArgumentException("Alarm id, button labels and handlers are required");
        Integer.parseInt(a[1]);
    }
    private static Intent intent(Context c,String[] config,String action){
        validate(config);
        return new Intent(c,AlarmScreenActivity.class).putExtra(CONFIG,config).setAction(action)
            .setData(new Uri.Builder().scheme("ocaml-alarm").authority(c.getPackageName()).appendPath(config[0]).appendPath(action).build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    static void show(Activity owner,String[] config){owner.startActivity(intent(owner,config,"show"));}
    private static PendingIntent pending(Context c,String[] config,String action){
        return PendingIntent.getActivity(c,0,intent(c,config,action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static void notify(Context c,String channel,int icon,boolean fullScreen,String[] config){
        validate(config);NotificationManager manager=c.getSystemService(NotificationManager.class);
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("POST_NOTIFICATIONS is not granted");
        if(!manager.areNotificationsEnabled())throw new SecurityException("Notifications are disabled");
        NotificationChannel nc=manager.getNotificationChannel(channel);
        if(nc==null || nc.getImportance()<NotificationManager.IMPORTANCE_HIGH)
            throw new IllegalArgumentException("An existing HIGH importance alarm channel is required");
        if(fullScreen && ((Build.VERSION.SDK_INT>=29 && c.checkSelfPermission(android.Manifest.permission.USE_FULL_SCREEN_INTENT)!=PackageManager.PERMISSION_GRANTED)
            || (Build.VERSION.SDK_INT>=34 && !manager.canUseFullScreenIntent())))
            throw new SecurityException("Full-screen alarm permission is not granted");
        PendingIntent content=pending(c,config,"show");
        Notification.Builder builder=new Notification.Builder(c,channel).setSmallIcon(icon)
            .setContentTitle(config[2]).setContentText(config[3]).setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).setContentIntent(content)
            .addAction(new Notification.Action.Builder(null,config[4],pending(c,config,"stop")).build())
            .addAction(new Notification.Action.Builder(null,config[5],pending(c,config,"snooze")).build());
        if(fullScreen)builder.setFullScreenIntent(content,true);
        manager.notify(Integer.parseInt(config[1]),builder.build());
    }
    static void dismiss(Context c,String id,int notificationId){
        c.getSystemService(NotificationManager.class).cancel(notificationId);
        WeakReference<AlarmScreenActivity> reference=live.remove(id);
        AlarmScreenActivity screen=reference==null?null:reference.get();
        if(screen!=null)screen.finish();
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(Build.VERSION.SDK_INT>=27){setShowWhenLocked(true);setTurnScreenOn(true);}
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handled=state!=null && state.getBoolean("handled");
        present();
    }
    @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);detach();setIntent(intent);handled=false;present();}
    private void present(){
        config=getIntent().getStringArrayExtra(CONFIG);
        try{validate(config==null?new String[0]:config);}catch(Exception e){Log.e("OCaml","Invalid alarm screen",e);finish();return;}
        reference=new WeakReference<>(this);live.put(config[0],reference);
        String action=getIntent().getAction();
        if("stop".equals(action) || "snooze".equals(action)){choose(action);return;}
        LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        TextView title=new TextView(this);title.setText(config[2]);layout.addView(title);
        TextView text=new TextView(this);text.setText(config[3]);layout.addView(text);
        Button stop=new Button(this);stop.setText(config[4]);stop.setOnClickListener(v->choose("stop"));layout.addView(stop);
        Button snooze=new Button(this);snooze.setText(config[5]);snooze.setOnClickListener(v->choose("snooze"));layout.addView(snooze);
        setContentView(layout);
    }
    private void choose(String action){
        if(handled){finish();return;}
        handled=true;
        getSystemService(NotificationManager.class).cancel(Integer.parseInt(config[1]));
        try{Bridge.deliver(this,config[action.equals("stop")?6:7],config[8]).whenComplete((result,error)->{
            if(error!=null || !"success".equals(result))Log.e("OCaml","Alarm action failed: "+action,error);
        });}catch(Exception e){Log.e("OCaml","Alarm action startup failed",e);}
        finish();
    }
    private void detach(){if(config!=null && reference!=null)live.remove(config[0],reference);}
    @Override public void onDestroy(){detach();super.onDestroy();}
    @Override public void onSaveInstanceState(Bundle state){state.putBoolean("handled",handled);super.onSaveInstanceState(state);}
}
