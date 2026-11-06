package org.ocaml.android;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Simulated permission grant, revocation screen, denial and lifecycle return. */
final class SettingsBackend {
    private static String subscription;
    private static volatile boolean active,granted,pending,complete;
    private static int clicks,requests,opens,acknowledgements;
    private static String str(byte[] value){return new String(value,StandardCharsets.UTF_8);}
    static boolean handles(String op,byte[][] a){
        if(op.equals("settings.create"))return str(a[2]).contains("Status permission");
        if((op.startsWith("settings.") || op.equals("ui.release")) && a.length>0)return str(a[0]).equals("status-page");
        if(op.equals("preferences.get") && str(a[0]).equals("status-test"))return true;
        return active && op.startsWith("health.");
    }
    private static byte[][] ok(String... values){return UiBackend.ok(values);}
    private static void click(){pending=true;clicks++;UiBackend.event(subscription,"0","");}
    static byte[][] dispatch(String op,byte[][] bytes){
        String[] a=Arrays.stream(bytes).map(SettingsBackend::str).toArray(String[]::new);
        switch(op){
        case "settings.create":
            if(!a[2].contains("\"kind\":\"action\"") || !a[2].contains("\"description\":\"Unknown\""))throw new AssertionError("Native action with initial summary");
            subscription=a[0];active=true;return ok("status-page");
        case "settings.update":{
            if(!a[1].contains("\"kind\":\"action\"") || a[1].contains("\"value\""))throw new AssertionError("Status must not be a stored preference value");
            boolean displaysGranted=a[1].contains("\"description\":\"Granted\"");
            boolean displaysDenied=a[1].contains("\"description\":\"Denied\"");
            if(!pending && (clicks==0 && displaysDenied || clicks==1 && displaysGranted || clicks==2 && displaysDenied))click();
            else if(clicks==3 && acknowledgements==3 && displaysDenied)complete=true;
            return ok();
        }
        case "settings.result":
            if(!pending || !a[1].equals("0"))throw new AssertionError("Unexpected action acknowledgement");
            pending=false;acknowledgements++;return ok();
        case "health.granted":return granted?ok("steps"):ok();
        case "health.request":
            if(!a[0].equals("activity") || a.length!=2 || !a[1].equals("steps"))throw new AssertionError("Permission request receives the activity and type");
            if(++requests==1){granted=true;return ok("steps");}
            return ok(); // Health Connect returns the granted subset after refusal.
        case "health.settings":
            if(!a[0].equals("activity") || !granted)throw new AssertionError("Manage an already granted permission");
            opens++;granted=false;UiBackend.event(subscription,"refresh","");return ok();
        case "preferences.get":return ok("bool",Boolean.toString(complete));
        case "ui.release":
            if(!complete || pending || requests!=2 || opens!=1 || acknowledgements!=3)throw new AssertionError("Permission action flow incomplete");
            active=false;return ok();
        default:throw new AssertionError(op);
        }
    }
    static void checkClean(){if(active || !complete)throw new AssertionError("Status settings page leaked or not exercised");}
}
