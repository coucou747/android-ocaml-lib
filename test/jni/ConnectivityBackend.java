package org.ocaml.android;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
final class ConnectivityBackend {
 static final Map<String,CompletableFuture<byte[][]>> resources=new ConcurrentHashMap<>();static int ids,reads;static volatile boolean awaiting;static final byte[] binary={0,(byte)255,7};
 static String str(byte[] b){return new String(b,StandardCharsets.UTF_8);}static byte[][] ok(String... values){return UiBackend.ok(values);}
 static byte[][] dispatch(String op,byte[][] args){String[] a=Arrays.stream(args).map(ConnectivityBackend::str).toArray(String[]::new);
  switch(op){
   case "bt.enabled":return ok(Boolean.toString(awaiting));
   case "bt.available":case "bt.declared":case "bt.granted":case "bt.request":return ok("true");
   case "bt.permissions":return ok("android.permission.BLUETOOTH_CONNECT");
   case "bt.bonded":return ok("[{\"address\":\"00:11:22:33:44:55\",\"name\":\"Écouteur 🐫\",\"bonded\":true}]");
   case "bt.gattConnect":case "bt.rfcommConnect":case "audio2.create":{
    String id="connection-"+(++ids);CompletableFuture<byte[][]> ready=new CompletableFuture<>();if(!a[1].equals("wait"))ready.complete(ok(id));resources.put(id,ready);return ok(id);}
   case "bt.await":case "audio2.await":try{CompletableFuture<byte[][]> f=resources.get(a[0]);awaiting=!f.isDone();return f.get();}catch(Exception e){return new byte[][]{Bridge.text("error"),Bridge.text("platform"),Bridge.text("Closed")};}finally{awaiting=false;}
   case "bt.close":case "audio2.close":{CompletableFuture<byte[][]> p=resources.remove(a[0]);if(p!=null)p.complete(ok());return ok();}
   case "bt.gattDiscover":return ok("[{\"handle\":\"characteristic\",\"uuid\":\"00002a37-0000-1000-8000-00805f9b34fb\",\"kind\":\"characteristic\",\"parent\":\"service\",\"properties\":18}]");
   case "bt.gattRead":return Bridge.ok(binary);
   case "bt.gattWrite":if(!Arrays.equals(args[3],binary))throw new AssertionError("GATT binary data");return ok();
   case "bt.gattNotify":return ok();
   case "bt.gattMtu":return ok(a[1]);case "bt.gattRssi":return ok("-45");
   case "bt.rfcommRead":return reads++==0?Bridge.ok(binary):ok();
   case "bt.rfcommWrite":if(!Arrays.equals(args[1],binary))throw new AssertionError("RFCOMM binary data");return ok();
   case "bt.connectionListen":UiBackend.event(a[0],"value","", "characteristic", "data", "0");return ok();
   case "bt.scan":if(!a[4].contains("\"manufacturer\""))throw new AssertionError("Scan filters");UiBackend.event(a[0],"found","{\"address\":\"00:11:22:33:44:55\",\"name\":null,\"bonded\":false}","","", "-60");return ok();
   case "audio2.outputs":return ok("[{\"id\":1,\"name\":\"Bluetooth\",\"kind\":8}]");
   case "audio2.state":return ok("playing");case "audio2.position":return ok("200");case "audio2.duration":return ok("1000");case "audio2.routed":return ok("1");
   case "audio2.listen":UiBackend.event(a[0],"playing");return ok();
   case "audio2.pause":case "audio2.resume":case "audio2.volume":case "audio2.output":case "audio2.seek":return ok();
   case "connectivity.permissions":return ok("android.permission.FOREGROUND_SERVICE");
   case "connectivity.start":if(a.length<12 || a[7].isEmpty())throw new AssertionError("Explicit service configuration");Bridge.events.add(new byte[][]{Bridge.text("handler"),Bridge.text("connection-token"),args[7],Bridge.text("event-token")});return ok();
   case "connectivity.event":return ok("service_started","","","","0");
   case "connectivity.stop":return ok();
   default:throw new AssertionError(op);
  }
 }
 static void checkClean(){if(!resources.isEmpty())throw new AssertionError("Connectivity handles leaked");}
}
