package org.ocaml.android;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
/** Native layout/widget behavior is tested on Android; this fixture checks wire APIs. */
final class LayoutBackend {
    static final Set<String> operations=new HashSet<>(Arrays.asList("ui.systemBarInsets","ui.container","ui.nestedScroll","ui.constraintLayout","ui.visibility","ui.setLayout","ui.addLayout","ui.constraintConnect","ui.constraintClear","ui.constraintBias","ui.toolbar","ui.toolbarTitle","ui.toolbarSubtitle","ui.toolbarActions","events.toolbarAction","events.toolbarNavigation","ui.card","ui.bottomNavigation","ui.navigationSelected","ui.navigationSelect","events.navigationSelect","ui.checkBox","ui.checkState","ui.checkSet","events.checkState","ui.switch","ui.switchChecked","ui.switchSet","events.switchChecked","ui.divider"));
    static final Map<String,String> states=new ConcurrentHashMap<>();static int ids;
    static boolean handles(String op,byte[][] args){return operations.contains(op) || op.equals("ui.release") && str(args[0]).startsWith("layout-");}
    static String str(byte[] b){return new String(b,StandardCharsets.UTF_8);}
    static byte[][] ok(String... values){return UiBackend.ok(values);}
    static String handle(){return "layout-"+(++ids);}
    static byte[][] dispatch(String op,byte[][] bytes){String[] a=Arrays.stream(bytes).map(LayoutBackend::str).toArray(String[]::new);
        switch(op){
        case "ui.container":if(!a[1].equals("stack") && !a[2].equals("8"))throw new AssertionError("Layout gap transport");return ok(handle());
        case "ui.nestedScroll":case "ui.constraintLayout":case "ui.card":case "ui.divider":return ok(handle());
        case "ui.setLayout":case "ui.addLayout":{
            String json=a[a.length-1];if(!json.startsWith("{"))throw new AssertionError("Layout JSON transport");return ok();}
        case "ui.constraintConnect":if(!a[5].equals("8"))throw new AssertionError("Constraint dp margin");return ok();
        case "ui.constraintClear":case "ui.constraintBias":case "ui.visibility":case "ui.systemBarInsets":return ok();
        case "ui.toolbar":if(!a[5].contains("101"))throw new AssertionError("Toolbar menu IDs");return ok(handle());
        case "ui.toolbarTitle":case "ui.toolbarSubtitle":case "ui.toolbarActions":return ok();
        case "events.toolbarAction":UiBackend.event(a[0],"101");return ok();
        case "events.toolbarNavigation":UiBackend.event(a[0]);return ok();
        case "ui.bottomNavigation":{String id=handle();states.put(id,a[2]);return ok(id);}
        case "ui.navigationSelected":case "ui.checkState":case "ui.switchChecked":return ok(states.get(a[0]));
        case "ui.navigationSelect":case "ui.checkSet":case "ui.switchSet":states.put(a[0],a[1]);return ok();
        case "events.navigationSelect":states.put(a[1],"2");UiBackend.event(a[0],"2");return ok();
        case "ui.checkBox":case "ui.switch":{String id=handle();states.put(id,a[2]);return ok(id);}
        case "events.checkState":states.put(a[1],"1");UiBackend.event(a[0],"1");return ok();
        case "events.switchChecked":states.put(a[1],"true");UiBackend.event(a[0],"true");return ok();
        case "ui.release":states.remove(a[0]);return ok();
        default:throw new AssertionError(op);
        }
    }
}
