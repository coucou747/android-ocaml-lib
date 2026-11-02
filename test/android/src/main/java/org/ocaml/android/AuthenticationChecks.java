package org.ocaml.android;

import android.content.Context;
import androidx.credentials.exceptions.*;
import org.json.JSONObject;

/** Noninteractive native checks: no real passkey is created or read. */
public final class AuthenticationChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void run(Context context) throws Exception {
        for(String value:new String[]{"http://auth.test","https://user:secret@auth.test","https://auth.test?x=1","https://auth.test#x","https:///missing-host"}){
            boolean rejected=false;try{AuthenticationSupport.base(value);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"Invalid base URL rejected");
        }
        for(String path:new String[]{"//evil.test/path","https://evil.test/path","/path#fragment","/\\evil.test/path","relative"}){
            boolean rejected=false;try{AuthenticationSupport.resolve("https://auth.test",path);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"Authentication origin confinement");
        }
        check(AuthenticationSupport.resolve("https://auth.test:9443/base","/api/auth/me").equals("https://auth.test:9443/api/auth/me"),"Explicit HTTPS origin and port");
        AuthenticationSupport.Request cancelled=new AuthenticationSupport.Request(null);cancelled.close();
        check(cancelled.result.get()[0].equals("cancelled") && cancelled.cancellation.isCanceled(),"Native cancellation signal");
        AuthenticationSupport.Request complete=new AuthenticationSupport.Request(null);complete.result.complete(new String[]{"complete","{}"});complete.close();
        check(complete.result.get()[0].equals("complete"),"Late cancellation preserves completed result");
        AuthenticationSupport.Request noCredential=new AuthenticationSupport.Request(null);noCredential.fail(new NoCredentialException("fixture"));
        check(noCredential.result.get()[0].equals("none"),"No credential is explicit");
        AuthenticationSupport.Request denied=new AuthenticationSupport.Request(null);denied.fail(new GetCredentialCancellationException("fixture"));
        check(denied.result.get()[0].equals("cancelled"),"User cancellation is explicit");
        AuthenticationSupport.Request unsupported=new AuthenticationSupport.Request(null);unsupported.fail(new GetCredentialUnsupportedException("fixture"));
        check(unsupported.result.get()[0].equals("unsupported"),"Provider absence is explicit");
        JSONObject identity=new JSONObject(AuthenticationSupport.identity(context));
        check(identity.getString("package_name").equals(context.getPackageName()),"APK package identity");
        check(identity.getJSONArray("sha256_cert_fingerprints").getString(0).matches("([A-F0-9]{2}:){31}[A-F0-9]{2}"),"SHA256 signing fingerprint");
        check(identity.getJSONArray("origins").getString(0).matches("android:apk-key-hash:[A-Za-z0-9_-]{43}"),"Android WebAuthn origin");
        boolean traversal=false;try{AuthenticationSupport.readSecret(context,"../escape");}catch(IllegalArgumentException expected){traversal=true;}
        check(traversal,"Private secret basename enforced");
    }
}
