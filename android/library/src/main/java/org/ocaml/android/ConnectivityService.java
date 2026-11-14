package org.ocaml.android;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
abstract class ConnectivityService extends Service {
 static final class Start {final String[] args;final CompletableFuture<Void> ready=new CompletableFuture<>();Start(String[] a){args=a;}}
 static final Map<String,ConnectivityService> active=new ConcurrentHashMap<>();
 static final Map<String,Start> starts=new ConcurrentHashMap<>();
 String[] config;
 final Set<ConnectivitySupport.Managed> owned=ConcurrentHashMap.newKeySet();String handler;
 static CompletableFuture<Void> start(Activity owner,String[] args)throws Exception {
  ConnectivitySupport.visible(owner);Class<?> cls=args[1].equals("audio")?AudioPlaybackService.class:BluetoothConnectionService.class;
  ServiceInfo info=owner.getPackageManager().getServiceInfo(new ComponentName(owner,cls),PackageManager.GET_DISABLED_COMPONENTS);
  if(!info.enabled)throw new UnsupportedOperationException("Enable the optional connectivity service in the client manifest");
  for(String permission:ConnectivitySupport.servicePermissions(args[1]))if(owner.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Foreground service permission required: "+permission);
  if(!args[1].equals("audio"))ConnectivitySupport.require("connect");
  if(args.length<11 || args[3].isEmpty() || Integer.parseInt(args[4])<=0 || args[7].isEmpty())throw new IllegalArgumentException("Notification, handler and resources required");
  for(int i=11;i<args.length;i++){ConnectivitySupport.Managed r=ConnectivitySupport.get(args[i]);if((r instanceof ConnectivitySupport.Player)!=args[1].equals("audio"))throw new IllegalArgumentException("Resource/service type mismatch");if(r.owner!=null && r.owner!=owner)throw new IllegalArgumentException("Resource owned by another activity");}
  String token=UUID.randomUUID().toString();Start start=new Start(args);starts.put(token,start);
  try{owner.startForegroundService(new Intent(owner,cls).putExtra("start",token));}catch(Exception e){starts.remove(token);throw e;}return start.ready;
 }
 @Override public IBinder onBind(Intent i){return null;}
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent!=null && intent.getAction()!=null){if(owned.isEmpty() || intent.getAction().equals("stop")){stopSelf();return START_NOT_STICKY;}try{for(ConnectivitySupport.Managed resource:new ArrayList<>(owned))if(resource instanceof ConnectivitySupport.Player){ConnectivitySupport.Player p=(ConnectivitySupport.Player)resource;if(intent.getAction().equals("pause")){p.pause("paused");}else if(intent.getAction().equals("resume"))p.start();else p.close();}}catch(Exception e){event(new Object[]{"error","","",new byte[0],"-1"});}return START_NOT_STICKY;}
  Start request=intent==null?null:starts.remove(intent.getStringExtra("start"));if(request==null){stopSelf();return START_NOT_STICKY;}
  try{String[] a=request.args;NotificationManager manager=getSystemService(NotificationManager.class);if(manager.getNotificationChannel(a[3])==null)throw new IllegalArgumentException("Notification channel missing");
   if(android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("Notification consent required");
   Notification.Builder notification=new Notification.Builder(this,a[3]).setSmallIcon(Integer.parseInt(a[4])).setContentTitle(a[5]).setContentText(a[6]).setOngoing(true).setCategory(a[1].equals("audio")?Notification.CATEGORY_TRANSPORT:Notification.CATEGORY_SERVICE);
   Intent entry=getPackageManager().getLaunchIntentForPackage(getPackageName());if(entry!=null)notification.setContentIntent(PendingIntent.getActivity(this,0,entry,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
   ConnectivitySupport.Player player=null;for(int i=11;i<a.length;i++){ConnectivitySupport.Managed r=ConnectivitySupport.get(a[i]);if(r instanceof ConnectivitySupport.Player)player=(ConnectivitySupport.Player)r;}
   if(player!=null){notification.setStyle(new Notification.MediaStyle().setMediaSession(player.session.getSessionToken()));String[] actions={"pause","resume","stop"};for(int i=0;i<actions.length;i++)notification.addAction(new Notification.Action.Builder(null,a[8+i],PendingIntent.getService(this,i,new Intent(this,getClass()).setAction(actions[i]),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build());}
   if(player==null)notification.addAction(new Notification.Action.Builder(null,a[10],PendingIntent.getService(this,0,new Intent(this,getClass()).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build());
   if(Build.VERSION.SDK_INT>=29)startForeground(Integer.parseInt(a[2]),notification.build(),a[1].equals("audio")?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK:ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(Integer.parseInt(a[2]),notification.build());active.put(a[1],this);config=a;handler=a[7];for(int i=11;i<a.length;i++){ConnectivitySupport.Managed r=ConnectivitySupport.get(a[i]);attach(r);}request.ready.complete(null);event(new Object[]{"service_started","","",new byte[0],"0"});return START_NOT_STICKY;
  }catch(Exception e){request.ready.completeExceptionally(e);stopSelf();return START_NOT_STICKY;}
 }
 void attach(ConnectivitySupport.Managed r){if(r instanceof ConnectivitySupport.Player){ConnectivitySupport.Player p=(ConnectivitySupport.Player)r;p.media.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);if(p.playing())p.media.start();}r.retained=true;r.owner=null;r.service=this;owned.add(r);refreshNotification();}
 void refreshNotification(){
  if(config==null || !active.containsValue(this))return;String[] a=config;ConnectivitySupport.Player player=null;
  for(ConnectivitySupport.Managed r:owned)if(r instanceof ConnectivitySupport.Player)player=(ConnectivitySupport.Player)r;
  Notification.Builder n=new Notification.Builder(this,a[3]).setSmallIcon(Integer.parseInt(a[4])).setContentTitle(a[5]).setContentText(a[6]).setOngoing(true).setCategory(a[1].equals("audio")?Notification.CATEGORY_TRANSPORT:Notification.CATEGORY_SERVICE);
  Intent entry=getPackageManager().getLaunchIntentForPackage(getPackageName());if(entry!=null)n.setContentIntent(PendingIntent.getActivity(this,0,entry,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
  String[] actions=player==null?new String[]{"stop"}:new String[]{"pause","resume","stop"};
  if(player!=null){n.setStyle(new Notification.MediaStyle().setMediaSession(player.session.getSessionToken()));player.session.setMetadata(new android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE,a[5]).putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,a[6]).build());}
  for(int i=0;i<actions.length;i++)n.addAction(new Notification.Action.Builder(null,a[actions[i].equals("stop")?10:actions[i].equals("pause")?8:9],PendingIntent.getService(this,i,new Intent(this,getClass()).setAction(actions[i]),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build());
  getSystemService(NotificationManager.class).notify(Integer.parseInt(a[2]),n.build());
 }
 void event(Object[] e){if(handler==null)return;String token=UUID.randomUUID().toString();ConnectivitySupport.events.put(token,e);try{Bridge.deliver(this,handler,token).whenComplete((v,error)->{ConnectivitySupport.events.remove(token);if(error!=null || !"success".equals(v))android.util.Log.e("OCamlConnectivity","Service handler failed",error);});}catch(Exception error){ConnectivitySupport.events.remove(token);android.util.Log.e("OCamlConnectivity","Service event failed",error);}}
 @Override public void onDestroy(){active.values().removeIf(value->value==this);event(new Object[]{"service_stopped","","",new byte[0],"0"});for(ConnectivitySupport.Managed r:new ArrayList<>(owned)){r.service=null;r.retained=false;r.close();}owned.clear();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
