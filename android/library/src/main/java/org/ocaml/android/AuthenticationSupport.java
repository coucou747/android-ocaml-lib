package org.ocaml.android;

import android.app.Activity;
import android.content.Context;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.security.keystore.*;
import android.util.AtomicFile;
import android.util.Base64;
import androidx.core.content.ContextCompat;
import androidx.credentials.*;
import androidx.credentials.exceptions.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.KeyGenerator;
import org.json.*;

/** Narrow native adapter. No HTTP flow, session policy or WebAuthn verifier. */
final class AuthenticationSupport {
    private AuthenticationSupport(){}
    private static final Set<Request> active=ConcurrentHashMap.newKeySet();
    static final class Request {
        final Activity owner;
        final CancellationSignal cancellation=new CancellationSignal();
        final CompletableFuture<String[]> result=new CompletableFuture<>();
        Request(Activity owner){this.owner=owner;active.add(this);result.whenComplete((r,e)->active.remove(this));}
        void close(){cancellation.cancel();result.complete(new String[]{"cancelled"});active.remove(this);}
        void fail(Exception e){
            if(e instanceof CreateCredentialCancellationException || e instanceof GetCredentialCancellationException)result.complete(new String[]{"cancelled"});
            else if(e instanceof NoCredentialException)result.complete(new String[]{"none"});
            else if(e instanceof CreateCredentialUnsupportedException || e instanceof GetCredentialUnsupportedException
                || e instanceof CreateCredentialProviderConfigurationException || e instanceof GetCredentialProviderConfigurationException)result.complete(new String[]{"unsupported"});
            else if(e instanceof CreateCredentialException)result.complete(new String[]{"provider",((CreateCredentialException)e).getType()});
            else if(e instanceof GetCredentialException)result.complete(new String[]{"provider",((GetCredentialException)e).getType()});
            else result.completeExceptionally(e);
        }
    }
    static void cancelFor(Activity activity){for(Request request:active)if(request.owner==activity)request.close();}
    static Request start(Activity owner,String operation,String options) throws JSONException {
        if(!operation.equals("create") && !operation.equals("get"))throw new IllegalArgumentException("Unknown credential operation");
        new JSONObject(options);
        Request request=new Request(owner);
        if(Build.VERSION.SDK_INT<28){request.result.complete(new String[]{"unsupported"});return request;}
        try{
            CredentialManager manager=CredentialManager.create(owner);
            if(operation.equals("create")){
                manager.createCredentialAsync(owner,new CreatePublicKeyCredentialRequest(options),request.cancellation,ContextCompat.getMainExecutor(owner),
                    new CredentialManagerCallback<CreateCredentialResponse,CreateCredentialException>(){
                        @Override public void onResult(CreateCredentialResponse response){
                            if(response instanceof CreatePublicKeyCredentialResponse)request.result.complete(new String[]{"complete",((CreatePublicKeyCredentialResponse)response).getRegistrationResponseJson()});
                            else request.result.complete(new String[]{"provider","unexpected_credential_type"});
                        }
                        @Override public void onError(CreateCredentialException error){request.fail(error);}
                    });
            }else{
                GetCredentialRequest optionsRequest=new GetCredentialRequest.Builder().addCredentialOption(new GetPublicKeyCredentialOption(options)).build();
                manager.getCredentialAsync(owner,optionsRequest,request.cancellation,ContextCompat.getMainExecutor(owner),
                    new CredentialManagerCallback<GetCredentialResponse,GetCredentialException>(){
                        @Override public void onResult(GetCredentialResponse response){
                            Credential credential=response.getCredential();
                            if(credential instanceof PublicKeyCredential)request.result.complete(new String[]{"complete",((PublicKeyCredential)credential).getAuthenticationResponseJson()});
                            else request.result.complete(new String[]{"provider","unexpected_credential_type"});
                        }
                        @Override public void onError(GetCredentialException error){request.fail(error);}
                    });
            }
        }catch(Exception error){request.fail(error);}return request;
    }
    static String[] clearState(Context context) throws Exception {
        CompletableFuture<String[]> result=new CompletableFuture<>();
        try{CredentialManager.create(context).clearCredentialStateAsync(new ClearCredentialStateRequest(),null,ContextCompat.getMainExecutor(context),
            new CredentialManagerCallback<Void,ClearCredentialException>(){
                @Override public void onResult(Void ignored){result.complete(new String[0]);}
                @Override public void onError(ClearCredentialException error){result.complete(new String[]{"provider",error.getType()});}
            });
        }catch(UnsupportedOperationException e){return new String[]{"unsupported"};}
        return result.get();
    }
    static synchronized void ensureKey(String alias) throws Exception {
        if(alias.isEmpty())throw new IllegalArgumentException("Nonempty key alias required");
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(alias))return;
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();
    }
    private static AtomicFile secret(Context context,String name){
        if(name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/')>=0 || name.indexOf('\\')>=0 || name.indexOf(0)>=0)
            throw new IllegalArgumentException("Expected private token basename");
        return new AtomicFile(new File(context.getNoBackupFilesDir(),name));
    }
    static synchronized String readSecret(Context context,String name) throws IOException {
        try{return new String(secret(context,name).readFully(),StandardCharsets.UTF_8);}
        catch(FileNotFoundException missing){if(secret(context,name).getBaseFile().exists())throw missing;return null;}
    }
    static synchronized void writeSecret(Context context,String name,String ciphertext) throws IOException {
        AtomicFile file=secret(context,name);FileOutputStream output=file.startWrite();
        try{output.write(ciphertext.getBytes(StandardCharsets.UTF_8));file.finishWrite(output);
            if(!Arrays.equals(file.readFully(),ciphertext.getBytes(StandardCharsets.UTF_8)))throw new IOException("Session commit failed");}
        catch(IOException | RuntimeException error){file.failWrite(output);throw error;}
    }
    static synchronized void deleteSecret(Context context,String name) throws IOException {
        AtomicFile file=secret(context,name);file.delete();
        if(readSecret(context,name)!=null)throw new IOException("Could not remove private session file");
    }
    static URI base(String value){
        try{URI uri=new URI(value);
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null)
                throw new IllegalArgumentException("Authentication requires HTTPS base URL without userinfo/query/fragment");
            return uri;
        }catch(java.net.URISyntaxException e){throw new IllegalArgumentException("Invalid authentication URL");}
    }
    static String resolve(String value,String path){
        URI origin=base(value);
        try{
            URI relative=new URI(path);
            if(!path.startsWith("/") || path.startsWith("//") || relative.isAbsolute() || relative.getRawAuthority()!=null || relative.getRawFragment()!=null || path.indexOf('\\')>=0)
                throw new IllegalArgumentException("Expected same-origin absolute path");
            URI target=origin.resolve(relative);
            if(!target.getScheme().equalsIgnoreCase(origin.getScheme()) || !Objects.equals(target.getRawAuthority(),origin.getRawAuthority()))
                throw new IllegalArgumentException("Authentication URL changed origin");
            return target.toASCIIString();
        }catch(java.net.URISyntaxException e){throw new IllegalArgumentException("Invalid authentication path");}
    }
    @SuppressWarnings("deprecation")
    static String identity(Context context) throws Exception {
        PackageManager manager=context.getPackageManager();String name=context.getPackageName();Signature[] signers;
        if(Build.VERSION.SDK_INT>=28){PackageInfo info=manager.getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES);signers=info.signingInfo.getApkContentsSigners();}
        else signers=manager.getPackageInfo(name,PackageManager.GET_SIGNATURES).signatures;
        JSONArray fingerprints=new JSONArray(),origins=new JSONArray();
        for(Signature signer:signers){byte[] hash=MessageDigest.getInstance("SHA-256").digest(signer.toByteArray());
            StringJoiner hex=new StringJoiner(":");for(byte b:hash)hex.add(String.format(Locale.ROOT,"%02X",b&255));fingerprints.put(hex.toString());
            origins.put("android:apk-key-hash:"+Base64.encodeToString(hash,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING));
        }
        return new JSONObject().put("package_name",name).put("sha256_cert_fingerprints",fingerprints).put("origins",origins).toString();
    }
}
