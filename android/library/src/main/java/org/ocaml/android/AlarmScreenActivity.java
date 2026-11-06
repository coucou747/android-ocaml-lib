package org.ocaml.android;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import android.view.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.view.WindowCompat;
import com.google.android.material.color.MaterialColors;
import java.lang.ref.WeakReference;
import java.util.*;

/** Generic alarm UI. Sound, task navigation and rescheduling stay in OCaml handlers. */
public final class AlarmScreenActivity extends AppCompatActivity {
    private static final String PRESENTATION="alarm_presentation",TOKEN="alarm_token",THEME="alarm_theme",ACTION="alarm_action";
    private static final Map<String,WeakReference<AlarmScreenActivity>> live=new HashMap<>();
    private AlarmPresentation presentation;
    private String token;
    private WeakReference<AlarmScreenActivity> reference;
    private AlarmScreenView content;
    private boolean handled;

    private static boolean materialTheme(Context context,int resource){
        if(resource==0)return false;
        try{return AlarmScreenView.material(new ContextThemeWrapper(context,resource),com.google.android.material.R.attr.isMaterialTheme);}
        catch(android.content.res.Resources.NotFoundException missing){return false;}
    }
    static int hostTheme(Context context){
        try{
            if(context instanceof Activity){int theme=context.getPackageManager().getActivityInfo(((Activity)context).getComponentName(),0).getThemeResource();if(materialTheme(context,theme))return theme;}
            int theme=context.getApplicationInfo().theme;if(materialTheme(context,theme))return theme;
            Intent launcher=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if(launcher!=null){ActivityInfo info=context.getPackageManager().getActivityInfo(launcher.getComponent(),0);if(materialTheme(context,info.getThemeResource()))return info.getThemeResource();}
        }catch(PackageManager.NameNotFoundException missing){/* Material fallback below. */}
        return com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar;
    }
    static Intent intent(Context c,AlarmPresentation p,String token,AlarmPresentation.Action action){
        return new Intent(c,AlarmScreenActivity.class).putExtra(PRESENTATION,p.source).putExtra(TOKEN,token).putExtra(THEME,hostTheme(c))
            .setAction(action==null?"org.ocaml.android.ALARM_SHOW":"org.ocaml.android.ALARM_ACTION")
            .putExtra(ACTION,action==null?null:action.id)
            .setData(new Uri.Builder().scheme("ocaml-alarm").authority(c.getPackageName()).appendPath(p.id).appendPath(token)
                .appendPath(action==null?"view":"action").appendPath(action==null?"":action.id).build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
    static void show(Activity owner,String[] config) throws Exception {show(owner,AlarmPresentation.legacy(config));}
    static void show(Activity owner,AlarmPresentation p) throws Exception {
        String token=AlarmDelivery.activate(owner,p);owner.startActivity(intent(owner,p,token,null));
    }
    static PendingIntent pending(Context c,AlarmPresentation p,String token,AlarmPresentation.Action action){
        return PendingIntent.getActivity(c,0,intent(c,p,token,action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static Notification notification(Context c,String channel,int icon,boolean fullScreen,AlarmPresentation p,String token){
        PendingIntent content=pending(c,p,token,null);
        Notification.Builder builder=new Notification.Builder(c,channel).setSmallIcon(icon).setContentTitle(p.title).setContentText(p.message)
            .setStyle(new Notification.BigTextStyle().bigText(p.message)).setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).setContentIntent(content);
        if(p.subtitle!=null)builder.setSubText(p.subtitle);
        for(AlarmPresentation.Action action:p.actions)builder.addAction(new Notification.Action.Builder(null,action.label,pending(c,p,token,action)).build());
        if(fullScreen)builder.setFullScreenIntent(content,true);
        return builder.build();
    }
    static void notify(Context c,String channel,int icon,boolean fullScreen,String[] config) throws Exception {notify(c,channel,icon,fullScreen,AlarmPresentation.legacy(config));}
    static void notify(Context c,String channel,int icon,boolean fullScreen,AlarmPresentation p) throws Exception {
        if(channel.isEmpty() || icon<=0)throw new IllegalArgumentException("Alarm channel and small icon required");
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        if(Build.VERSION.SDK_INT>=33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("POST_NOTIFICATIONS is not granted");
        if(!manager.areNotificationsEnabled())throw new SecurityException("Notifications are disabled");
        NotificationChannel nc=manager.getNotificationChannel(channel);
        if(nc==null || nc.getImportance()<NotificationManager.IMPORTANCE_HIGH)throw new IllegalArgumentException("An existing HIGH importance alarm channel is required");
        if(fullScreen && ((Build.VERSION.SDK_INT>=29 && c.checkSelfPermission(android.Manifest.permission.USE_FULL_SCREEN_INTENT)!=PackageManager.PERMISSION_GRANTED)
            || (Build.VERSION.SDK_INT>=34 && !manager.canUseFullScreenIntent())))throw new SecurityException("Full-screen alarm permission is not granted");
        String token=AlarmDelivery.activate(c,p);manager.notify(p.notificationId,notification(c,channel,icon,fullScreen,p,token));
        WeakReference<AlarmScreenActivity> reference=live.get(p.id);AlarmScreenActivity screen=reference==null?null:reference.get();
        if(screen!=null && !screen.isFinishing()){
            screen.setIntent(intent(screen,p,token,null));screen.handled=false;screen.present();
        }
    }
    static void dismiss(Context c,String id,int notificationId){
        AlarmDelivery.dismiss(c,id);c.getSystemService(NotificationManager.class).cancel(notificationId);
        WeakReference<AlarmScreenActivity> reference=live.remove(id);AlarmScreenActivity screen=reference==null?null:reference.get();if(screen!=null)screen.finish();
    }
    @SuppressWarnings("deprecation")
    @Override public void onCreate(Bundle state){
        int theme=getIntent().getIntExtra(THEME,0);setTheme(materialTheme(this,theme)?theme:hostTheme(this));
        super.onCreate(state);supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
        int surface=MaterialColors.getColor(getWindow().getDecorView(),com.google.android.material.R.attr.colorSurface);
        androidx.core.view.WindowInsetsControllerCompat bars=WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(MaterialColors.isColorLight(surface));bars.setAppearanceLightNavigationBars(MaterialColors.isColorLight(surface));
        if(Build.VERSION.SDK_INT>=27){setShowWhenLocked(true);setTurnScreenOn(true);}
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED|WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handled=state!=null && state.getBoolean("handled");present();
    }
    @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);detach();setIntent(intent);handled=false;present();}
    private void present(){
        if(content!=null){content.close();content=null;}
        try{
            presentation=new AlarmPresentation(getIntent().getStringExtra(PRESENTATION));token=getIntent().getStringExtra(TOKEN);
            if(token==null || !AlarmDelivery.active(this,presentation.id,token)){finish();return;}
            reference=new WeakReference<>(this);live.put(presentation.id,reference);
            String action=getIntent().getStringExtra(ACTION);if(action!=null){choose(action);return;}
            String displayedToken=token;
            content=new AlarmScreenView(this,presentation,id->{
                if(!isFinishing() && !isDestroyed() && displayedToken.equals(token))choose(id);
            });setTitle(presentation.title);setContentView(content.root);
        }catch(Exception error){Log.e("OCaml","Invalid alarm screen",error);finish();}
    }
    private void choose(String id){
        if(handled)return;
        try{
            AlarmPresentation.Action action=presentation.action(id);
            if(!AlarmDelivery.claim(this,presentation.id,token)){finish();return;}
            handled=true;getSystemService(NotificationManager.class).cancel(presentation.notificationId);
            Bridge.deliver(this,action.handler,action.payload).whenComplete((result,error)->{
                if(error!=null || !"success".equals(result))Log.e("OCaml","Alarm action handler failed",error);
            });
            WeakReference<AlarmScreenActivity> other=live.get(presentation.id);AlarmScreenActivity screen=other==null?null:other.get();if(screen!=null && screen!=this)screen.finish();
            finish();
        }catch(Exception error){Log.e("OCaml","Alarm action startup failed",error);if(handled)finish();}
    }
    private void detach(){if(presentation!=null && reference!=null)live.remove(presentation.id,reference);}
    @Override public void onDestroy(){detach();if(content!=null)content.close();super.onDestroy();}
    @Override public void onSaveInstanceState(Bundle state){state.putBoolean("handled",handled);super.onSaveInstanceState(state);}
}
