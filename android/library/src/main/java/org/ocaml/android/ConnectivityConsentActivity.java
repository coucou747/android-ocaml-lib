package org.ocaml.android;
import android.app.Activity;
import android.bluetooth.*;
import android.content.*;
import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import java.util.*;
import java.util.concurrent.*;
public final class ConnectivityConsentActivity extends ComponentActivity {
 private static final Map<String,CompletableFuture<Boolean>> requests=new ConcurrentHashMap<>();
 private String token;
 private final ActivityResultLauncher<Intent> launcher=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{CompletableFuture<Boolean> f=requests.remove(token);if(f!=null)f.complete(getSystemService(BluetoothManager.class).getAdapter().isEnabled());finish();});
 static CompletableFuture<Boolean> enable(Activity owner){String token=UUID.randomUUID().toString();CompletableFuture<Boolean> f=new CompletableFuture<>();requests.put(token,f);try{owner.startActivity(new Intent(owner,ConnectivityConsentActivity.class).putExtra("token",token));}catch(Exception e){requests.remove(token);f.completeExceptionally(e);}return f;}
 @Override public void onCreate(Bundle state){super.onCreate(state);token=getIntent().getStringExtra("token");if(token==null || !requests.containsKey(token)){finish();return;}if(state==null)try{launcher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));}catch(Exception e){CompletableFuture<Boolean> f=requests.remove(token);if(f!=null)f.completeExceptionally(e);finish();}}
 @Override public void onDestroy(){if(isFinishing() && !isChangingConfigurations() && token!=null){CompletableFuture<Boolean> f=requests.remove(token);if(f!=null)f.completeExceptionally(new CancellationException("Bluetooth enable interrupted"));}super.onDestroy();}
}
