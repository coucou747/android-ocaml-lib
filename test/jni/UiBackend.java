package org.ocaml.android;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
/** Only verifies OCaml/JNI behavior; the instrumented checks use real widgets. */
final class UiBackend {
    static int ids;
    static final Map<String,String> text=new ConcurrentHashMap<>(),editorRoot=new ConcurrentHashMap<>(),subscriptions=new ConcurrentHashMap<>();
    static final Map<String,String> dialogs=new ConcurrentHashMap<>();
    static volatile boolean waiting;
    static boolean handles(String op,byte[][] args){
        String arg=args.length>0?str(args[0]):"";
        return op.startsWith("dialog.") || op.startsWith("form.") || op.equals("ui.primaryButton") || op.equals("ui.secondaryButton")
          || op.equals("ui.spacer") || op.equals("ui.padding") || op.equals("ui.margins")
          || (op.equals("events.text") && args.length>1 && editorRoot.containsKey(str(args[1])))
          || ((op.equals("ui.getText") || op.equals("ui.setText") || op.equals("ui.release")) && arg.startsWith("component-"))
          || (op.equals("ui.text") && args.length>1 && str(args[1]).startsWith("component:"));
    }
    static String str(byte[] b){return new String(b,StandardCharsets.UTF_8);}
    static byte[][] ok(String... args){byte[][] bytes=new byte[args.length][];for(int i=0;i<args.length;i++)bytes[i]=Bridge.text(args[i]);return Bridge.ok(bytes);}
    static void event(String id,String... args){byte[][] values=new byte[args.length+1][];values[0]=Bridge.text(id);for(int i=0;i<args.length;i++)values[i+1]=Bridge.text(args[i]);Bridge.events.add(values);}
    static byte[][] dispatch(String op,byte[][] bytes){String[] a=Arrays.stream(bytes).map(UiBackend::str).toArray(String[]::new);
        switch(op){
        case "form.field":{
            String root="component-field-"+(++ids),input="component-input-"+ids;
            text.put(input,a[4]);editorRoot.put(input,root);return ok(root,input);
        }
        case "form.container":return ok("component-form-"+(++ids));
        case "form.error":case "form.help":return ok();
        case "ui.primaryButton":case "ui.secondaryButton":case "ui.text":{
            String id="component-text-"+(++ids);text.put(id,a[1]);return ok(id);
        }
        case "ui.getText":return ok(text.get(a[0]).equals("component:ready")?Boolean.toString(waiting):text.get(a[0]));
        case "ui.setText":text.put(a[0],a[1]);if(subscriptions.containsKey(a[0]))event(subscriptions.get(a[0]),a[1]);return ok();
        case "events.text":subscriptions.put(a[1],a[0]);return ok();
        case "ui.padding":case "ui.margins":return ok();
        case "ui.spacer":return ok("component-spacer-"+(++ids));
        case "ui.release":{
            text.remove(a[0]);for(String editor:new ArrayList<>(editorRoot.keySet()))if(editorRoot.get(editor).equals(a[0])){
                text.remove(editor);editorRoot.remove(editor);String sub=subscriptions.remove(editor);if(sub!=null)event("unsubscribe",sub);
            }return ok();
        }
        case "dialog.show":case "dialog.message":{
            String id="component-dialog-"+(++ids);dialogs.put(id,a[0]);
            switch(a[2]){
            case "dismiss":event(a[0],"closed","dismissed");break;
            case "negative":event(a[0],"button","negative");break;
            case "wait":waiting=true;break;
            default:event(a[0],"button","positive");
            }return ok(id);
        }
        case "dialog.resolve":{
            String sub=dialogs.get(a[0]);if(sub!=null){if(a[2].equals("true"))event(sub,"closed",a[1]);else event(sub,"button",a[1]);}return ok();
        }
        case "dialog.close":{String sub=dialogs.remove(a[0]);waiting=false;if(sub!=null)event(sub,"closed","dismissed");return ok();}
        default:throw new AssertionError(op);
        }
    }
    static void checkClean(){if(!dialogs.isEmpty() || !editorRoot.isEmpty())throw new AssertionError("Dialog/field handles leaked");}
}
