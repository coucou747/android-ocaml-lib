package org.ocaml.android;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
/** Protocol fixture only; cryptography, networking and providers are simulated. */
final class AuthBackend {
    static final ConcurrentHashMap<String,String> secrets=new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String,CompletableFuture<byte[][]>> requests=new ConcurrentHashMap<>();
    static final Set<String> http=ConcurrentHashMap.newKeySet();
    static {
        secrets.put("expired-session","cipher:"+Base64.getEncoder().encodeToString(b("{\"access_token\":\"expired\",\"expires_at_ms\":\"0\"}")));
        secrets.put("corrupt-session","broken-ciphertext");
    }
    static volatile boolean waiting,racing,cleared,failLogout;
    static final CountDownLatch releaseRace=new CountDownLatch(1);
    static byte[] b(String s){return Bridge.text(s);}
    static byte[][] ok(String... values){byte[][] bytes=new byte[values.length][];for(int i=0;i<values.length;i++)bytes[i]=b(values[i]);return Bridge.ok(bytes);}
    static byte[][] err(String kind){return new byte[][]{b("error"),b(kind),b("Authentication fixture error")};}
    static String text(byte[] v){return new String(v,StandardCharsets.UTF_8);}
    static byte[][] dispatch(String op,byte[][] bytes){
        String[] a=Arrays.stream(bytes).map(AuthBackend::text).toArray(String[]::new);
        try{switch(op){
        case "auth.baseUrl":if(!new URI(a[0]).getScheme().equals("https"))return err("argument");return ok(a[0]);
        case "auth.resolveUrl":{URI base=new URI(a[0]),target=base.resolve(a[1]);if(!a[1].startsWith("/") || a[1].startsWith("//") || !Objects.equals(base.getHost(),target.getHost()) || !target.getScheme().equals("https"))return err("argument");return ok(target.toString());}
        case "auth.pathSegment":return ok(a[0].equals("id/with space")?"id%2Fwith%20space":a[0]);
        case "auth.passkeyStart":{
            String token=UUID.randomUUID().toString();CompletableFuture<byte[][]> result=new CompletableFuture<>();requests.put(token,result);
            String json=a[2];
            if(json.contains("cancel"))result.complete(ok("cancelled"));else if(json.contains("none"))result.complete(ok("none"));
            else if(json.contains("unsupported"))result.complete(ok("unsupported"));else if(json.contains("provider"))result.complete(ok("provider","fixture.provider.error"));
            else if(!json.contains("wait"))result.complete(ok("complete","{\"id\":\"credential\",\"type\":\"public-key\",\"response\":{}}"));return ok(token);
        }
        case "auth.passkeyAwait":waiting=true;return requests.get(a[0]).get();
        case "auth.passkeyClose":{waiting=false;CompletableFuture<byte[][]> r=requests.remove(a[0]);if(r!=null)r.complete(ok("cancelled"));return ok();}
        case "auth.clearState":cleared=true;return ok();
        case "auth.identity":return ok("{\"package_name\":\"fixture.app\",\"sha256_cert_fingerprints\":[\"AB:CD\"],\"origins\":[\"android:apk-key-hash:fixture\"]}");
        case "auth.keyEnsure":return ok();
        case "auth.secretRead":{String value=secrets.get(a[0]);return value==null?ok():ok(value);}
        case "auth.secretWrite":
            if(a[1].startsWith("cipher:") && text(Base64.getDecoder().decode(a[1].substring(7))).contains("one-use-recovery"))throw new AssertionError("Recovery code persisted");
            secrets.put(a[0],a[1]);return ok();
        case "auth.secretDelete":secrets.remove(a[0]);return ok();
        case "keystore.encrypt":return ok("cipher:"+Base64.getEncoder().encodeToString(bytes[1]));
        case "keystore.decrypt":if(!a[1].startsWith("cipher:"))return err("platform");return ok(text(Base64.getDecoder().decode(a[1].substring(7))));
        case "http.create":{String id=UUID.randomUUID().toString();http.add(id);return ok(id);}
        case "http.close":http.remove(a[0]);return ok();
        case "http.request":return network(a);
        default:return err("argument");}
        }catch(Exception error){return err("platform");}
    }
    static byte[][] network(String[] a) throws Exception {
        if(!http.contains(a[0]))throw new AssertionError("HTTP handle");
        URI url=new URI(a[1]);String path=url.getRawPath();
        if(!url.getScheme().equals("https") || !url.getHost().equals("auth.test"))throw new AssertionError("HTTPS origin confinement");
        String bearer=null;for(int i=6;i<a.length;i+=2)if(a[i].equalsIgnoreCase("Authorization"))bearer=a[i+1];
        if(path.equals("/waiting"))return ok("200",Boolean.toString(waiting));
        if(path.equals("/race-ready"))return ok("200",Boolean.toString(racing));
        if(path.equals("/release-race")){releaseRace.countDown();return ok("200","null");}
        if(path.equals("/race")){racing=true;releaseRace.await();return ok("401","{}");}
        if(path.equals("/fail-logout")){failLogout=true;return ok("200","null");}
        if(path.equals("/unauthorized"))return ok("401","{}");
        if(path.equals("/redirect"))return ok("302","not followed","Location","https://evil.invalid/");
        if(path.endsWith("/options")){
            if(!a[3].contains("\"client_kind\":\"android\""))throw new AssertionError("Android context");
            if(path.contains("credentials") || path.contains("reauth")){if(bearer==null)throw new AssertionError("Authenticated sensitive ceremony");}
            else if(bearer!=null)throw new AssertionError("Public ceremony leaks previous bearer");
            if(a[3].contains("\"username\":\"broken\""))return ok("200","{}");
            String mode=a[3].contains("\"username\":\"cancel\"")?"cancel":"ok";
            return ok("200","{\"tx\":\"transaction-fixture\",\"options\":{\"mode\":\""+mode+"\"}}");
        }
        if(path.endsWith("/verify")){
            if(!a[3].contains("\"tx\":\"transaction-fixture\"") || !a[3].contains("\"assertion\":{") || !a[3].contains("\"client_kind\":\"android\""))throw new AssertionError("Verification codec envelope");
            if(path.contains("reauth") || path.contains("credentials"))return ok("200","{\"verified\":true}");
            return ok("200","{\"token\":\"session-1\",\"scheme\":\"Bearer\",\"lifetime\":\"3600\",\"codes\":[\"one-use-recovery\"]}");
        }
        if(path.startsWith("/api/auth/")){
            if(bearer==null || !bearer.startsWith("Bearer "))throw new AssertionError("Missing bearer");
            if(path.endsWith("logout"))return ok(failLogout?"503":"204","");
            if(path.contains("sessions/") && !path.endsWith("id%2Fwith%20space"))throw new AssertionError("Revocation path encoding");
            return ok("200","{\"ok\":true}");
        }
        return ok("200","null");
    }
    static void checkClean(){if(!requests.isEmpty() || !http.isEmpty())throw new AssertionError("Leaked authentication request handles");
        if(!cleared)throw new AssertionError("Provider selection state not cleared on logout");}
}
