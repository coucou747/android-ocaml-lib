package org.ocaml.android;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;

/** Owns the system-camera result independently of BridgeActivity recreation. */
public final class PhotoCaptureActivity extends Activity {
    private static final String DIRECTORY="ocaml_photos";
    private static final String TOKEN="capture_token";
    private static final int CAPTURE=1;
    private static final ConcurrentHashMap<String,Request> requests=new ConcurrentHashMap<>();
    private Request request;
    private String token;
    private static final class Request {
        final File file;
        final Uri uri;
        final CompletableFuture<String[]> result=new CompletableFuture<>();
        Request(File file,Uri uri){this.file=file;this.uri=uri;}
    }
    private static File directory(Context c) throws IOException {
        File dir=new File(c.getFilesDir(),DIRECTORY);
        if(!dir.isDirectory() && !dir.mkdirs())throw new IOException("Cannot create photo directory");
        return dir;
    }
    private static void validateName(String name) {
        if(name.isEmpty() || name.contains("/") || name.contains("\\") || (!name.endsWith(".jpg") && !name.endsWith(".jpeg")))
            throw new IllegalArgumentException("Expected a JPEG basename ending in .jpg or .jpeg");
    }
    static CompletableFuture<String[]> capture(Activity owner,String name) throws Exception {
        validateName(name);
        if(owner.isFinishing() || owner.isDestroyed())throw new IllegalStateException("Activity is finishing");
        File file=new File(directory(owner),name);
        if(!file.createNewFile())throw new IllegalArgumentException("Photo already exists");
        Uri uri;
        try{uri=FileProvider.getUriForFile(owner,owner.getPackageName()+".ocaml.photos",file);}
        catch(Exception e){Files.deleteIfExists(file.toPath());throw e;}
        String token=UUID.randomUUID().toString();Request request=new Request(file,uri);requests.put(token,request);
        try{owner.startActivity(new Intent(owner,PhotoCaptureActivity.class).putExtra(TOKEN,token));}
        catch(Exception e){requests.remove(token);Files.deleteIfExists(file.toPath());throw e;}
        return request.result;
    }
    private static File ownedFile(Context c,String value) throws Exception {
        Uri uri=Uri.parse(value);
        if(!"content".equals(uri.getScheme()) || !(c.getPackageName()+".ocaml.photos").equals(uri.getAuthority())
           || uri.getPathSegments().size()!=2 || !uri.getPathSegments().get(0).equals("photos"))
            throw new IllegalArgumentException("Not a photo URI owned by this application");
        String name=uri.getPathSegments().get(1);validateName(name);
        return new File(directory(c),name);
    }
    static String open(Context c,String uri) throws Exception {
        File file=ownedFile(c,uri);
        if(!file.isFile())throw new FileNotFoundException("Photo does not exist");
        return FileProvider.getUriForFile(c,c.getPackageName()+".ocaml.photos",file).toString();
    }
    static byte[] read(Context c,String uri) throws Exception {return Files.readAllBytes(ownedFile(c,uri).toPath());}
    static void delete(Context c,String uri) throws Exception {Files.deleteIfExists(ownedFile(c,uri).toPath());}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);token=getIntent().getStringExtra(TOKEN);request=token==null?null:requests.get(token);
        if(request==null){finish();return;}
        if(state==null){
            Intent camera=new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,request.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            camera.setClipData(ClipData.newRawUri("",request.uri));
            try{startActivityForResult(camera,CAPTURE);}
            catch(ActivityNotFoundException e){fail(new UnsupportedOperationException("No camera application available",e));}
            catch(Exception e){fail(e);}
        }
    }
    private void revoke(){revokeUriPermission(request.uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}
    private void fail(Exception error){
        if(request==null)return;
        requests.remove(token);revoke();
        try{Files.deleteIfExists(request.file.toPath());}catch(IOException cleanup){error.addSuppressed(cleanup);}
        request.result.completeExceptionally(error);finish();
    }
    @Override public void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(code!=CAPTURE || request==null)return;
        if(result==RESULT_OK){
            if(request.file.length()==0){fail(new IOException("Camera returned an empty photo"));return;}
            requests.remove(token);revoke();request.result.complete(new String[]{request.uri.toString()});finish();
        }else{
            requests.remove(token);revoke();
            try{Files.deleteIfExists(request.file.toPath());request.result.complete(new String[0]);finish();}
            catch(Exception e){fail(e);}
        }
    }
    @Override public void onDestroy(){
        if(request!=null && !isChangingConfigurations() && isFinishing() && !request.result.isDone())
            fail(new IllegalStateException("Photo capture interrupted"));
        super.onDestroy();
    }
}
