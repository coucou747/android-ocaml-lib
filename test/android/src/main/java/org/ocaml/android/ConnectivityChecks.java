package org.ocaml.android;
import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.*;
import java.nio.charset.StandardCharsets;
/** Baseline must not acquire optional capabilities through the library manifest. */
public final class ConnectivityChecks {
 static byte[][] call(String op,String... args){byte[][] a=new byte[args.length][];for(int i=0;i<a.length;i++)a[i]=args[i].getBytes(StandardCharsets.UTF_8);return Bridge.dispatch(op.getBytes(StandardCharsets.UTF_8),a);}
 static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
 public static void run(Activity owner)throws Exception {
  int[] events={0,0};java.util.function.Consumer<Object[]> first=e->events[0]++,second=e->events[1]++;
  ConnectivitySupport.observers.add(first);ConnectivitySupport.observers.add(second);
  try{
   android.content.Intent changed=new android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE,android.bluetooth.BluetoothAdapter.STATE_ON);
   new ConnectivitySupport.StateReceiver(first).onReceive(owner,changed);
   new ConnectivitySupport.StateReceiver(second).onReceive(owner,changed);
   check(events[0]==1 && events[1]==1,"Two observers must not multiply Bluetooth broadcasts");
  }finally{ConnectivitySupport.observers.remove(first);ConnectivitySupport.observers.remove(second);}
  PackageInfo info=owner.getPackageManager().getPackageInfo(owner.getPackageName(),PackageManager.GET_PERMISSIONS);
  boolean enabled=false;for(String p:info.requestedPermissions)if(p.equals("android.permission.BLUETOOTH_CONNECT") || p.equals("android.permission.BLUETOOTH"))enabled=true;
  if(!enabled){check(new String(call("bt.declared","connect")[1],StandardCharsets.UTF_8).equals("false"),"Missing manifest declaration is distinguishable from refusal");check(new String(call("bt.enabled")[1],StandardCharsets.UTF_8).equals("permission"),"Bluetooth cannot be used without client permissions");}
  check(new String(call("bt.available")[0],StandardCharsets.UTF_8).equals("ok"),"Capability probe needs no Bluetooth consent");
  check(new String(call("audio2.outputs")[0],StandardCharsets.UTF_8).equals("ok"),"Audio outputs require no microphone permission");
  for(String p:info.requestedPermissions)check(!p.equals("android.permission.RECORD_AUDIO"),"No microphone permission introduced");
 }
}
