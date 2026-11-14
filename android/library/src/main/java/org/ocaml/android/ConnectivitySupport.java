package org.ocaml.android;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.*;
import android.media.*;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;

/** Public Android APIs only. No permission declarations, auto-connect or retry policy. */
final class ConnectivitySupport {
 static Context context;
 static final Map<String,Managed> resources=new ConcurrentHashMap<>();
 static final ScheduledExecutorService timers=Executors.newSingleThreadScheduledExecutor();
 static final Set<Consumer<Object[]>> observers=ConcurrentHashMap.newKeySet();
 static final Map<String,Object[]> events=new ConcurrentHashMap<>();
 static final Object[] EMPTY=new Object[0];
 static <T>T ui(Callable<T> f)throws Exception{return Bridge.connectivityUi(f);}
 static BluetoothAdapter adapter(){BluetoothManager m=context.getSystemService(BluetoothManager.class);BluetoothAdapter a=m==null?null:m.getAdapter();if(a==null)throw new UnsupportedOperationException("Bluetooth unavailable");return a;}
 static JSONObject device(BluetoothDevice d)throws JSONException{return new JSONObject().put("address",d.getAddress()).put("name",d.getName()==null?JSONObject.NULL:d.getName()).put("bonded",d.getBondState()==BluetoothDevice.BOND_BONDED);}
 static void emit(String kind,BluetoothDevice d,String handle,byte[] data,int status){try{
  Object[] e={kind,d==null?"":device(d).toString(),handle==null?"":handle,data==null?new byte[0]:data,Integer.toString(status)};
  for(Consumer<Object[]> listener:observers)listener.accept(e);
  Managed r=resources.get(handle);if(r!=null)r.event(e);
 }catch(Exception error){android.util.Log.e("OCamlConnectivity","Event failure",error);}}
 static void receiver(Context c,BroadcastReceiver receiver,IntentFilter filter){if(Build.VERSION.SDK_INT>=33)c.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);else c.registerReceiver(receiver,filter);}
 static abstract class Managed implements AutoCloseable {
  final String id=UUID.randomUUID().toString();Activity owner;volatile boolean retained,closed;volatile ConnectivityService service;
  final Set<Consumer<Object[]>> listeners=ConcurrentHashMap.newKeySet();final Set<String> subscriptionIds=ConcurrentHashMap.newKeySet();
  final CompletableFuture<Object[]> connecting=new CompletableFuture<>();volatile ScheduledFuture<?> connectTimer;final java.util.concurrent.atomic.AtomicBoolean disposing=new java.util.concurrent.atomic.AtomicBoolean();
  Managed(Activity owner){this.owner=owner;resources.put(id,this);}
  void event(Object[] e){for(Consumer<Object[]> l:listeners)l.accept(e);if(service!=null)service.event(e);}
  void remove(){closed=true;if(connectTimer!=null)connectTimer.cancel(false);connecting.completeExceptionally(new IOException("Resource closed"));event(new Object[]{"closed","",id,new byte[0],"0"});resources.remove(id);for(String sub:subscriptionIds)Bridge.connectivityEnded(sub);subscriptionIds.clear();listeners.clear();ConnectivityService foreground=service;if(foreground!=null){foreground.owned.remove(this);new Handler(Looper.getMainLooper()).post(foreground::refreshNotification);}}
  public abstract void close();
 }
 static Managed get(String id){Managed r=resources.get(id);if(r==null || r.closed)throw new NoSuchElementException("Expired connectivity handle");return r;}
 static void closeFor(Activity owner){for(Managed r:resources.values())if(r.owner==owner && !r.retained)r.close();for(Scan s:new ArrayList<>(scans.values()))if(s.owner==owner)s.close();}
 static final Map<String,Scan> scans=new ConcurrentHashMap<>();
 static String[] permissions(String mode){boolean scan=mode.equals("location") || mode.equals("without_location");List<String> p=new ArrayList<>();
  if(Build.VERSION.SDK_INT>=31){p.add(Manifest.permission.BLUETOOTH_CONNECT);if(scan)p.add(Manifest.permission.BLUETOOTH_SCAN);if(mode.equals("location")){p.add(Manifest.permission.ACCESS_COARSE_LOCATION);p.add(Manifest.permission.ACCESS_FINE_LOCATION);}}
  else {p.add(Manifest.permission.BLUETOOTH);if(mode.equals("pair"))p.add(Manifest.permission.BLUETOOTH_ADMIN);if(scan){p.add(Manifest.permission.BLUETOOTH_ADMIN);p.add(Manifest.permission.ACCESS_FINE_LOCATION);}}
  return p.toArray(new String[0]);}
 static PackageInfo manifest()throws Exception{return context.getPackageManager().getPackageInfo(context.getPackageName(),PackageManager.GET_PERMISSIONS);}
 static boolean declared(String mode)throws Exception {PackageInfo info=manifest();List<String> names=info.requestedPermissions==null?Collections.emptyList():Arrays.asList(info.requestedPermissions);return names.containsAll(Arrays.asList(permissions(mode)));}
 static boolean granted(String mode)throws Exception {if(!declared(mode))return false;for(String p:permissions(mode))if(context.checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)return false;return true;}
 static void require(String mode)throws Exception {if(!declared(mode))throw new SecurityException("Declare the requested Bluetooth permissions in the client manifest");if(!granted(mode))throw new SecurityException("Bluetooth consent required");
  if(Build.VERSION.SDK_INT>=31 && !mode.equals("connect") && !mode.equals("pair")){PackageInfo info=manifest();for(int i=0;i<info.requestedPermissions.length;i++)if(info.requestedPermissions[i].equals(Manifest.permission.BLUETOOTH_SCAN) && (((info.requestedPermissionsFlags[i]&PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION)!=0)==mode.equals("location")))throw new IllegalArgumentException("Scan mode conflicts with neverForLocation");}}
 static Activity activity(String id){if(id.startsWith("service:")){if(!ConnectivityService.active.containsKey(id.substring(8)))throw new SecurityException("Start the opted-in foreground service first");return null;}Activity a=Bridge.connectivityActivity(id);if(a!=null && (a.isDestroyed() || a.isFinishing()))throw new NoSuchElementException("Activity destroyed");return a;}
 static void attach(String owner,Managed resource)throws Exception{if(owner.startsWith("service:")){ui(()->{ConnectivityService service=ConnectivityService.active.get(owner.substring(8));if(service==null)throw new SecurityException("Foreground service stopped");service.attach(resource);return null;});}}
 static void visible(Activity a){if(a==null || (!(a instanceof androidx.lifecycle.LifecycleOwner) || !((androidx.lifecycle.LifecycleOwner)a).getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)))throw new IllegalStateException("A visible activity is required");}
 static Runnable listen(String id,Set<Consumer<Object[]>> listeners){Consumer<Object[]> l=e->Bridge.emit(id,e);listeners.add(l);Runnable close=()->listeners.remove(l);Bridge.connectivitySubscription(id,close);return close;}
 static final class StateReceiver extends BroadcastReceiver {
  final Consumer<Object[]> target;
  StateReceiver(Consumer<Object[]> target){this.target=target;}
  public void onReceive(Context context,Intent intent){try{BluetoothDevice d=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);target.accept(new Object[]{intent.getAction(),d==null?"":device(d).toString(),"",new byte[0],Integer.toString(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,0)))});}catch(Exception e){android.util.Log.e("OCamlBluetooth","State event",e);}}
 }
 static final class Scan implements AutoCloseable {
  Activity owner;final String subscription;ScanCallback callback;BroadcastReceiver receiver;ScheduledFuture<?> timeout;boolean closed;
  Scan(Activity a,String id){owner=a;subscription=id;scans.put(id,this);}
  void found(BluetoothDevice d,int rssi,byte[] record){try{Bridge.emit(subscription,"found",device(d).toString(),"",record==null?new byte[0]:record,Integer.toString(rssi));}catch(Exception e){android.util.Log.e("OCamlBluetooth","Scan result",e);}}
  public synchronized void close(){if(closed)return;closed=true;scans.remove(subscription);if(timeout!=null)timeout.cancel(false);try{if(callback!=null)adapter().getBluetoothLeScanner().stopScan(callback);if(receiver!=null){context.unregisterReceiver(receiver);adapter().cancelDiscovery();}}catch(Exception ignored){}Bridge.emit(subscription,"stopped","","",new byte[0],"0");Bridge.connectivityEnded(subscription);}
 }
 static void scan(String op,String[] a)throws Exception {
  Activity owner=activity(a[1]);ui(()->{visible(owner);return null;});require(a[2]);int duration=Integer.parseInt(a[3]);if(duration<=0)throw new IllegalArgumentException("Scan timeout");
  if(!adapter().isEnabled())throw new UnsupportedOperationException("Bluetooth disabled");if(!scans.isEmpty())throw new IllegalStateException("Stop the current discovery first");
  Scan scan=new Scan(owner,a[0]);try{
   if(op.equals("bt.discover")){
    scan.receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(d!=null)scan.found(d,i.getShortExtra(BluetoothDevice.EXTRA_RSSI,(short)0),null);}};
    receiver(context,scan.receiver,new IntentFilter(BluetoothDevice.ACTION_FOUND));if(!adapter().startDiscovery())throw new IOException("Discovery refused");
   }else{
    BluetoothLeScanner scanner=adapter().getBluetoothLeScanner();if(scanner==null)throw new UnsupportedOperationException("BLE scanner unavailable");
    List<ScanFilter> filters=new ArrayList<>();JSONArray specs=new JSONArray(a[4]);for(int n=0;n<specs.length();n++){JSONObject f=specs.getJSONObject(n);ScanFilter.Builder b=new ScanFilter.Builder();if(!f.isNull("uuid"))b.setServiceUuid(new ParcelUuid(UUID.fromString(f.getString("uuid"))));if(!f.isNull("name"))b.setDeviceName(f.getString("name"));if(!f.isNull("manufacturer")){JSONObject m=f.getJSONObject("manufacturer");JSONArray values=m.getJSONArray("data");byte[] data=new byte[values.length()];for(int k=0;k<data.length;k++)data[k]=(byte)values.getInt(k);b.setManufacturerData(m.getInt("id"),data);}filters.add(b.build());}
    scan.callback=new ScanCallback(){public void onScanResult(int type,ScanResult r){scan.found(r.getDevice(),r.getRssi(),r.getScanRecord()==null?null:r.getScanRecord().getBytes());}public void onScanFailed(int status){Bridge.emit(scan.subscription,"error","","",new byte[0],Integer.toString(status));scan.close();}};
    scanner.startScan(filters,new ScanSettings.Builder().build(),scan.callback);
   }
   synchronized(scan){if(!scan.closed){scan.timeout=timers.schedule(scan::close,duration,TimeUnit.MILLISECONDS);Bridge.connectivitySubscription(a[0],scan::close);}}
  }catch(Exception e){scan.close();throw e;}
 }
 static final class Gatt extends Managed {
  volatile BluetoothGatt gatt;final int timeout;final BluetoothDevice device;volatile CompletableFuture<Object[]> pending;final Map<String,Object> attributes=new ConcurrentHashMap<>();
  Gatt(Activity owner,BluetoothDevice device,int timeout){super(owner);this.device=device;this.timeout=timeout;}
  void complete(int status,Object... values){if(closed)return;CompletableFuture<Object[]> p=pending;if(p!=null){if(status==BluetoothGatt.GATT_SUCCESS)p.complete(values);else p.completeExceptionally(new IOException("GATT status "+status));}}
  final BluetoothGattCallback callback=new BluetoothGattCallback(){
   public void onConnectionStateChange(BluetoothGatt g,int status,int state){if(closed)return;if(state==BluetoothProfile.STATE_CONNECTED){emit("connected",device,id,null,status);complete(status);}else if(state==BluetoothProfile.STATE_DISCONNECTED){complete(status==0?-1:status);emit("disconnected",device,id,null,status);close();}}
   public void onServicesDiscovered(BluetoothGatt g,int status){complete(status);}
   public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int status){complete(status==0 && c.getValue()==null?-1:status,(Object)c.getValue());}
   public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){complete(status);}
   public void onDescriptorRead(BluetoothGatt g,BluetoothGattDescriptor d,int status){complete(status==0 && d.getValue()==null?-1:status,(Object)d.getValue());}
   public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){complete(status);}
   public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){if(closed)return;for(Map.Entry<String,Object> e:attributes.entrySet())if(e.getValue()==c){Object[] value={"value","",e.getKey(),c.getValue(),"0"};event(value);}}
   public void onMtuChanged(BluetoothGatt g,int mtu,int status){complete(status,Integer.toString(mtu));}
   public void onReadRemoteRssi(BluetoothGatt g,int rssi,int status){complete(status,Integer.toString(rssi));}
  };
  synchronized Object[] operation(Callable<Boolean> start)throws Exception {if(closed)throw new NoSuchElementException("GATT closed");pending=new CompletableFuture<>();try{if(!start.call())throw new IOException("GATT operation refused");return pending.get(timeout,TimeUnit.MILLISECONDS);}catch(Exception e){close();throw e;}finally{pending=null;}}
  Object attr(String handle){Object value=attributes.get(handle);if(value==null)throw new NoSuchElementException("Attribute belongs to another or expired connection");return value;}
  String add(JSONArray out,Object value,String uuid,String kind,String parent,int properties)throws Exception {String handle=UUID.randomUUID().toString();attributes.put(handle,value);out.put(new JSONObject().put("handle",handle).put("uuid",uuid).put("kind",kind).put("parent",parent).put("properties",properties));return handle;}
  public void close(){if(!disposing.compareAndSet(false,true))return;remove();CompletableFuture<Object[]> p=pending;if(p!=null)p.completeExceptionally(new IOException("GATT closed"));if(gatt!=null){try{gatt.disconnect();}catch(Exception ignored){}try{gatt.close();}catch(Exception ignored){}}attributes.clear();}
 }
 static final class Serial extends Managed {
  final BluetoothSocket socket;final int timeout;final Object reads=new Object(),writes=new Object();
  Serial(Activity owner,BluetoothSocket socket,int timeout){super(owner);this.socket=socket;this.timeout=timeout;}
  <T>T operation(Callable<T> work)throws Exception {ScheduledFuture<?> deadline=timers.schedule(this::close,timeout,TimeUnit.MILLISECONDS);try{return work.call();}catch(Exception error){event(new Object[]{"error","",id,new byte[0],"-1"});close();throw error;}finally{deadline.cancel(false);}}
  public void close(){if(!disposing.compareAndSet(false,true))return;remove();try{socket.close();}catch(IOException ignored){}}
 }
 static final class Player extends Managed {
  final MediaPlayer media=new MediaPlayer();final AudioManager manager=context.getSystemService(AudioManager.class);final Set<String> subscribers=ConcurrentHashMap.newKeySet();final AudioFocusRequest focus;final MediaSession session;final BroadcastReceiver noisy;volatile String state="preparing";boolean interrupted;
  void state(String value){if(closed)return;state=value;for(String s:subscribers)Bridge.emit(s,value);event(new Object[]{value,"",id,new byte[0],"0"});session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_SEEK_TO|PlaybackState.ACTION_STOP).setState(value.equals("playing")?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,safePosition(),1).build());}
  int safePosition(){try{return closed?0:media.getCurrentPosition();}catch(IllegalStateException notPrepared){return 0;}}
  Player(Activity owner,String usage){super(owner);session=new MediaSession(context,"OCaml audio");int u=usage.equals("alarm")?AudioAttributes.USAGE_ALARM:usage.equals("notification")?AudioAttributes.USAGE_NOTIFICATION:AudioAttributes.USAGE_MEDIA;AudioAttributes attrs=new AudioAttributes.Builder().setUsage(u).setContentType(usage.equals("speech")?AudioAttributes.CONTENT_TYPE_SPEECH:AudioAttributes.CONTENT_TYPE_MUSIC).build();media.setAudioAttributes(attrs);
   focus=new AudioFocusRequest.Builder(usage.equals("alarm")?AudioManager.AUDIOFOCUS_GAIN_TRANSIENT:AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(change->{if(closed)return;if(change<0)pause("focus_lost");},new Handler(Looper.getMainLooper())).build();
   noisy=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){if(playing())pause("output_removed");}};receiver(context,noisy,new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
   session.setCallback(new MediaSession.Callback(){public void onPlay(){try{start();}catch(Exception e){state("error");}}public void onPause(){pause("paused");}public void onStop(){close();}public void onSeekTo(long pos){try{if(pos<0)throw new IllegalArgumentException("Negative position");media.seekTo(pos,MediaPlayer.SEEK_CLOSEST);}catch(Exception error){state("error");}}});session.setActive(false);
   media.setOnCompletionListener(m->state("completed"));media.setOnErrorListener((m,w,e)->{state("error");return true;});
  }
  boolean playing(){try{return !closed && media.isPlaying();}catch(IllegalStateException notReady){return false;}}
  void pause(String reason){if(closed)return;interrupted=true;if(playing())media.pause();state(reason);}
  void start()throws IOException {if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IOException("Audio focus denied; a visible activity or mediaPlayback service is required");interrupted=false;media.start();session.setActive(true);state("playing");}
  public void close(){try{ui(()->{if(!closed){remove();context.unregisterReceiver(noisy);media.release();manager.abandonAudioFocusRequest(focus);session.release();for(String s:subscribers){Bridge.emit(s,"closed");Bridge.connectivityEnded(s);}subscribers.clear();}return null;});}catch(Exception e){throw new IllegalStateException(e);}}
 }
 static String[] servicePermissions(String kind){List<String> permissions=new ArrayList<>();if(kind.equals("audio"))permissions.add(Manifest.permission.WAKE_LOCK);if(Build.VERSION.SDK_INT>=28)permissions.add(Manifest.permission.FOREGROUND_SERVICE);if(Build.VERSION.SDK_INT>=34)permissions.add(kind.equals("audio")?Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK:Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE);if(Build.VERSION.SDK_INT>=33)permissions.add(Manifest.permission.POST_NOTIFICATIONS);return permissions.toArray(new String[0]);}
 static Object[] dispatch(Context c,String op,String[] a,byte[][] raw)throws Exception {
  context=c;
  switch(op){
   case "bt.available":return new Object[]{c.getSystemService(BluetoothManager.class)!=null && c.getSystemService(BluetoothManager.class).getAdapter()!=null};
   case "bt.permissions":return permissions(a[0]);
   case "bt.declared":return new Object[]{declared(a[0])};
   case "bt.granted":return new Object[]{granted(a[0])};
   case "bt.request":{if(!declared(a[1]))throw new SecurityException("Permissions missing from client manifest");CompletableFuture<String[]> request=ui(()->{Activity owner=activity(a[0]);visible(owner);return ((BridgeActivity)owner).request(permissions(a[1]));});request.get();return new Object[]{granted(a[1])};}
   case "bt.settings":case "bt.appSettings":return ui(()->{Activity owner=activity(a[0]);owner.startActivity(op.equals("bt.settings")?new Intent(Settings.ACTION_BLUETOOTH_SETTINGS):new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+c.getPackageName())));return EMPTY;});
   case "bt.enable":{require("pair");CompletableFuture<Boolean> result=ui(()->{Activity owner=activity(a[0]);visible(owner);return ConnectivityConsentActivity.enable(owner);});return new Object[]{result.get()};}
   case "bt.enabled":require("connect");return new Object[]{adapter().isEnabled()};
   case "bt.bonded":{require("connect");JSONArray list=new JSONArray();for(BluetoothDevice d:adapter().getBondedDevices())list.put(device(d));return new Object[]{list.toString()};}
   case "bt.pair":require("pair");return ui(()->{visible(activity(a[0]));if(!adapter().getRemoteDevice(a[1]).createBond())throw new IOException("Pairing not started");return EMPTY;});
   case "bt.listen":{require("connect");Consumer<Object[]> listener=e->Bridge.emit(a[0],e);observers.add(listener);StateReceiver r=new StateReceiver(listener);IntentFilter f=new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);try{receiver(c,r,f);}catch(Exception e){observers.remove(listener);throw e;}Bridge.connectivitySubscription(a[0],()->{observers.remove(listener);c.unregisterReceiver(r);});return EMPTY;}
   case "bt.scan":case "bt.discover":scan(op,a);return EMPTY;
   case "bt.close":{Managed value=resources.get(a[0]);if(value!=null)value.close();return EMPTY;}
   case "bt.connectionListen":{Managed r=get(a[1]);r.subscriptionIds.add(a[0]);Runnable unsubscribe=listen(a[0],r.listeners);Bridge.connectivitySubscription(a[0],()->{unsubscribe.run();r.subscriptionIds.remove(a[0]);});return EMPTY;}
   case "bt.gattConnect":{require("connect");Gatt g=new Gatt(activity(a[0]),adapter().getRemoteDevice(a[1]),Integer.parseInt(a[3]));g.pending=g.connecting;try{attach(a[0],g);if(!adapter().isEnabled())throw new UnsupportedOperationException("Bluetooth disabled");g.gatt=g.device.connectGatt(c,false,g.callback,BluetoothDevice.TRANSPORT_LE);if(g.closed){g.gatt.close();throw new IOException("Connection closed during startup");}g.connectTimer=timers.schedule(g::close,Long.parseLong(a[2]),TimeUnit.MILLISECONDS);return new Object[]{g.id};}catch(Exception e){g.close();throw e;}}
   case "bt.await":{Managed r=get(a[0]);try{r.connecting.get();if(r.connectTimer!=null)r.connectTimer.cancel(false);if(r instanceof Gatt)((Gatt)r).pending=null;return new Object[]{r.id};}catch(Exception e){r.close();throw e;}}
   case "bt.gattDiscover":{Gatt g=(Gatt)get(a[0]);g.operation(()->g.gatt.discoverServices());g.attributes.clear();JSONArray list=new JSONArray();for(BluetoothGattService s:g.gatt.getServices()){String sh=g.add(list,s,s.getUuid().toString(),"service","",0);for(BluetoothGattCharacteristic ch:s.getCharacteristics()){String h=g.add(list,ch,ch.getUuid().toString(),"characteristic",sh,ch.getProperties());for(BluetoothGattDescriptor d:ch.getDescriptors())g.add(list,d,d.getUuid().toString(),"descriptor",h,0);}}return new Object[]{list.toString()};}
   case "bt.gattRead":{Gatt g=(Gatt)get(a[0]);Object attr=g.attr(a[1]);if(!(attr instanceof BluetoothGattCharacteristic) && !(attr instanceof BluetoothGattDescriptor))throw new IllegalArgumentException("Read/write requires a characteristic or descriptor");return g.operation(()->attr instanceof BluetoothGattDescriptor?g.gatt.readDescriptor((BluetoothGattDescriptor)attr):g.gatt.readCharacteristic((BluetoothGattCharacteristic)attr));}
   case "bt.gattWrite":{Gatt g=(Gatt)get(a[0]);Object attr=g.attr(a[1]);if(!(attr instanceof BluetoothGattCharacteristic) && !(attr instanceof BluetoothGattDescriptor))throw new IllegalArgumentException("Read/write requires a characteristic or descriptor");return g.operation(()->{if(attr instanceof BluetoothGattDescriptor){((BluetoothGattDescriptor)attr).setValue(raw[3]);return g.gatt.writeDescriptor((BluetoothGattDescriptor)attr);}BluetoothGattCharacteristic ch=(BluetoothGattCharacteristic)attr;ch.setWriteType(Boolean.parseBoolean(a[2])?BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT:BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);ch.setValue(raw[3]);return g.gatt.writeCharacteristic(ch);});}
   case "bt.gattNotify":{Gatt g=(Gatt)get(a[0]);BluetoothGattCharacteristic ch=(BluetoothGattCharacteristic)g.attr(a[1]);boolean enabled=Boolean.parseBoolean(a[3]);BluetoothGattDescriptor d=ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));if(d==null)throw new UnsupportedOperationException("CCCD absent");return g.operation(()->{if(!g.gatt.setCharacteristicNotification(ch,enabled))return false;d.setValue(!enabled?BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE:Boolean.parseBoolean(a[2])?BluetoothGattDescriptor.ENABLE_INDICATION_VALUE:BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);return g.gatt.writeDescriptor(d);});}
   case "bt.gattMtu":{Gatt g=(Gatt)get(a[0]);return g.operation(()->g.gatt.requestMtu(Integer.parseInt(a[1])));}
   case "bt.gattRssi":{Gatt g=(Gatt)get(a[0]);return g.operation(()->g.gatt.readRemoteRssi());}
   case "bt.rfcommConnect":{require("connect");BluetoothDevice d=adapter().getRemoteDevice(a[1]);UUID uuid=UUID.fromString(a[4]);Serial serial=new Serial(activity(a[0]),Boolean.parseBoolean(a[5])?d.createRfcommSocketToServiceRecord(uuid):d.createInsecureRfcommSocketToServiceRecord(uuid),Integer.parseInt(a[3]));attach(a[0],serial);serial.connectTimer=timers.schedule(serial::close,Long.parseLong(a[2]),TimeUnit.MILLISECONDS);CompletableFuture.runAsync(()->{try{serial.socket.connect();serial.connecting.complete(EMPTY);}catch(Exception e){serial.connecting.completeExceptionally(e);}});return new Object[]{serial.id};}
   case "bt.rfcommRead":{Serial s=(Serial)get(a[0]);synchronized(s.reads){return s.operation(()->{byte[] buffer=new byte[Integer.parseInt(a[1])];int n=s.socket.getInputStream().read(buffer);if(n<0){s.close();return EMPTY;}return new Object[]{Arrays.copyOf(buffer,n)};});}}
   case "bt.rfcommWrite":{Serial s=(Serial)get(a[0]);synchronized(s.writes){return s.operation(()->{s.socket.getOutputStream().write(raw[1]);s.socket.getOutputStream().flush();return EMPTY;});}}
   case "audio2.create":{Player p=ui(()->new Player(activity(a[0]),a[1]));try{attach(a[0],p);ui(()->{p.media.setOnPreparedListener(m->p.connecting.complete(EMPTY));p.media.setOnErrorListener((m,w,e)->{p.connecting.completeExceptionally(new IOException("Media error "+w+"/"+e));p.state("error");return true;});p.media.setDataSource(c,Uri.parse(a[2]));p.media.setLooping(Boolean.parseBoolean(a[3]));p.media.prepareAsync();return null;});return new Object[]{p.id};}catch(Exception e){p.close();throw e;}}
   case "audio2.await":{Player p=(Player)get(a[0]);try{p.connecting.get();ui(()->{if(!p.interrupted)p.start();else{p.session.setActive(true);p.state("paused");}return null;});return new Object[]{p.id};}catch(Exception e){p.close();throw e;}}
   case "audio2.close":{Managed p=resources.get(a[0]);if(p!=null)p.close();return EMPTY;}
   case "audio2.pause":case "audio2.resume":case "audio2.seek":case "audio2.position":case "audio2.duration":case "audio2.state":case "audio2.volume":case "audio2.output":case "audio2.routed":return ui(()->{Player p=(Player)get(a[0]);switch(op){case "audio2.pause":p.pause("paused");break;case "audio2.resume":p.start();break;case "audio2.seek":p.media.seekTo(Long.parseLong(a[1]),MediaPlayer.SEEK_CLOSEST);break;case "audio2.position":return new Object[]{p.media.getCurrentPosition()};case "audio2.duration":return new Object[]{p.media.getDuration()};case "audio2.state":return new Object[]{p.state};case "audio2.volume":float v=Float.parseFloat(a[1]);p.media.setVolume(v,v);break;case "audio2.output":if(Build.VERSION.SDK_INT<28)throw new UnsupportedOperationException("Preferred output requires API 28");AudioDeviceInfo output=null;if(!a[1].isEmpty()){for(AudioDeviceInfo d:p.manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(d.getId()==Integer.parseInt(a[1]))output=d;if(output==null)throw new UnsupportedOperationException("Output disconnected");}if(!p.media.setPreferredDevice(output))throw new IOException("Output preference refused");break;case "audio2.routed":if(Build.VERSION.SDK_INT<28)throw new UnsupportedOperationException("Routing requires API 28");AudioDeviceInfo d=p.media.getRoutedDevice();return new Object[]{d==null?0:d.getId()};}return EMPTY;});
   case "audio2.outputs":{JSONArray list=new JSONArray();for(AudioDeviceInfo d:c.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_OUTPUTS))list.put(new JSONObject().put("id",d.getId()).put("name",d.getProductName()).put("kind",d.getType()));return new Object[]{list.toString()};}
   case "audio2.listen":{Player p=(Player)get(a[1]);p.subscribers.add(a[0]);Bridge.emit(a[0],p.state);Bridge.connectivitySubscription(a[0],()->p.subscribers.remove(a[0]));return EMPTY;}
   case "audio2.outputsListen":{AudioManager m=c.getSystemService(AudioManager.class);AudioDeviceCallback cb=new AudioDeviceCallback(){public void onAudioDevicesAdded(AudioDeviceInfo[] d){Bridge.emit(a[0]);}public void onAudioDevicesRemoved(AudioDeviceInfo[] d){Bridge.emit(a[0]);}};m.registerAudioDeviceCallback(cb,new Handler(Looper.getMainLooper()));Bridge.connectivitySubscription(a[0],()->m.unregisterAudioDeviceCallback(cb));return EMPTY;}
   case "audio2.picker":return ui(()->{visible(activity(a[0]));if(Build.VERSION.SDK_INT<34)throw new UnsupportedOperationException("Output picker requires API 34");if(!MediaRouter2.getInstance(activity(a[0])).showSystemOutputSwitcher())throw new IOException("Output picker refused");return EMPTY;});
   case "connectivity.permissions":return servicePermissions(a[0]);
   case "connectivity.start":{CompletableFuture<Void> ready=ui(()->ConnectivityService.start(activity(a[0]),a));ready.get();return EMPTY;}
   case "connectivity.stop":return ui(()->{c.stopService(new Intent(c,a[0].equals("audio")?AudioPlaybackService.class:BluetoothConnectionService.class));return EMPTY;});
   case "connectivity.event":{Object[] e=events.get(a[0]);if(e==null)throw new NoSuchElementException("Expired connectivity event");return e;}
   default:throw new IllegalArgumentException("Unknown connectivity operation: "+op);
  }
 }
}
