package org.ocaml.android;

import android.app.Activity;
import android.content.DialogInterface;
import android.view.*;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.*;

/** Asynchronous dialog events; no native thread is held while the user responds. */
final class DialogSupport {
    private static final Set<DialogSupport> active=new HashSet<>();
    final Activity owner;
    final View content;
    private final String subscription;
    private final Map<String,Integer> buttons=new LinkedHashMap<>();
    private AlertDialog dialog;
    private boolean closed,pending;
    private String reason="dismissed";
    DialogSupport(Activity owner,View content,String[] args,boolean message){
        this.owner=owner;this.content=content;subscription=args[0];
        if(owner.isFinishing() || owner.isDestroyed())throw new IllegalArgumentException("Dialog activity is not available");
        if(UiSupport.owner(content.getContext())!=owner || content.getParent()!=null)throw new IllegalArgumentException("Dialog content must be unattached and owned by its activity");
        MaterialAlertDialogBuilder builder=new MaterialAlertDialogBuilder(UiComponents.theme(owner)).setTitle(args[2]).setCancelable(Boolean.parseBoolean(args[4]));
        if(message)builder.setMessage(args[3]);else builder.setView(content);
        for(int i=5;i<args.length;i+=2){
            if(i+1>=args.length || args[i+1].isEmpty() || buttons.containsKey(args[i]))throw new IllegalArgumentException("Invalid dialog buttons");
            int id;
            switch(args[i]){
            case "positive":id=DialogInterface.BUTTON_POSITIVE;builder.setPositiveButton(args[i+1],null);break;
            case "negative":id=DialogInterface.BUTTON_NEGATIVE;builder.setNegativeButton(args[i+1],null);break;
            case "neutral":id=DialogInterface.BUTTON_NEUTRAL;builder.setNeutralButton(args[i+1],null);break;
            default:throw new IllegalArgumentException("Unknown dialog button role");
            }
            buttons.put(args[i],id);
        }
        dialog=builder.create();dialog.setOnDismissListener(d->{
            if(closed)return;closed=true;active.remove(this);
            if(content.getParent() instanceof ViewGroup)((ViewGroup)content.getParent()).removeView(content);
            Bridge.emit(subscription,"closed",reason);
        });
        dialog.setOnShowListener(d->{for(Map.Entry<String,Integer> button:buttons.entrySet())dialog.getButton(button.getValue()).setOnClickListener(v->{
            if(closed || pending)return;pending=true;enable(false);Bridge.emit(subscription,"button",button.getKey());
        });});
        try{dialog.show();active.add(this);}catch(RuntimeException error){
            if(content.getParent() instanceof ViewGroup)((ViewGroup)content.getParent()).removeView(content);throw error;
        }
    }
    private void enable(boolean enabled){for(int id:buttons.values())dialog.getButton(id).setEnabled(enabled);}
    void resolve(String role,boolean close){
        if(closed)return;if(!buttons.containsKey(role))throw new IllegalArgumentException("Unknown dialog button");
        pending=false;if(close){reason=role;dialog.dismiss();}else enable(true);
    }
    void close(){if(!closed)dialog.dismiss();}
    static void closeFor(Activity activity){for(DialogSupport dialog:new ArrayList<>(active))if(dialog.owner==activity)dialog.close();}
}
