open Lwt.Infix
let require = function Ok x -> Lwt.return x | Error _ -> Lwt.fail_with "Android integration operation failed"
type presentation = { activity : Android.Activity.t; layout : Android.Ui.view; label : Android.Ui.view; mutable preview : Android.Ui.view option }
let screen : presentation option ref = ref None
let last_photo = ref None
let message = ref "OCaml Android — prêt"
let status text =
  message := text;
  match !screen with None -> Lwt.return_unit | Some ui -> Android.Ui.set_text ui.label text >>= require
let preview_photo () = match !screen,!last_photo with
  | Some ui,Some photo ->
    Android.Activity.display_size ui.activity >>= require >>= fun size ->
    Android.Ui.image ui.activity ~uri:(Android.Camera.uri photo) ~max_width_px:size.width_px ~max_height_px:size.height_px >>= require >>= fun view ->
    (match !screen with
    | Some current when current == ui ->
      (match ui.preview with None -> Lwt.return_unit | Some old -> Android.Ui.release old >>= require) >>= fun () ->
      ui.preview <- Some view; Android.Ui.add ui.layout view >>= require
    | _ -> Android.Ui.release view >|= ignore)
  | _ -> Lwt.return_unit
let capture activity () =
  let name = Printf.sprintf "photo-%Ld.jpg" (Int64.of_float (Unix.gettimeofday () *. 1000000.)) in
  Android.Camera.capture activity ~name >>= require >>= function
  | Cancelled -> status "Photo annulée"
  | Captured photo -> last_photo := Some photo; preview_photo () >>= fun () -> status "Aperçu de la photo capturée"
let scan_qr activity () =
  Android.Qr_scanner.scan activity >>= function
  | Ok text -> status (Printf.sprintf "QR code lu (%d octets)" (String.length text))
  | Error Android.Error.Cancelled -> status "Scan annulé"
  | Error (Android.Error.Permission_denied _) -> status "Caméra du scanner non autorisée"
  | Error (Android.Error.Unavailable _) -> status "Scanner Google indisponible (services ou module)"
  | Error _ -> status "Échec du scan QR"
let local_status () =
  let at_ms = Int64.of_float (Unix.gettimeofday () *. 1000.) in
  Android.Locale.format_date_time ~at_ms >>= require >>= fun date ->
  Android.Locale.time_zone () >>= require >>= fun zone ->
  Android.Audio.default_alarm_uri () >>= require >>= fun sound ->
  Android.Resources.resolve ~scope:Application ~kind:Drawable ~name:"integration_alarm_icon" >>= require >>= fun icon ->
  status (Printf.sprintf "%s (%s)\nIcône : %d\nSon : %s" date zone icon (Option.value sound ~default:"aucun"))
let health_status () =
  Android.Health_connect.status () >>= require >>= fun availability ->
  match availability with
  | Unavailable -> status "Health Connect indisponible sur cet appareil"
  | Update_required -> status "Health Connect doit être installé ou mis à jour"
  | Available -> Android.Health_connect.granted_permissions () >>= require >>= fun granted ->
    status (Printf.sprintf "Health Connect : %d types autorisés" (List.length granted))
let health_permissions activity () =
  Android.Health_connect.status () >>= require >>= function
  | Available -> Android.Health_connect.request_permissions activity [Steps;Heart_rate;Sleep;Exercise;Distance;Active_calories;Total_calories] >>= require >>= fun granted ->
    status (Printf.sprintf "Health Connect : %d types autorisés en lecture" (List.length granted))
  | _ -> health_status ()
let health_background_permissions activity () =
  Android.Health_connect.background_read_available () >>= require >>= function
  | false -> status "Lecture santé en arrière-plan indisponible"
  | true -> Android.Health_connect.request_background_read activity >>= function
    | Ok true -> status "Lecture santé en arrière-plan autorisée"
    | Error (Android.Error.Permission_denied _) -> status "Lecture santé en arrière-plan refusée"
    | Error (Android.Error.Unavailable _) -> status "Lecture santé en arrière-plan indisponible"
    | _ -> status "Erreur de consentement santé en arrière-plan"
let gps_background_permissions activity () =
  Android.Permissions.check "android.permission.ACCESS_FINE_LOCATION" >>= require >>= function
  | false -> status "Autoriser d’abord la localisation précise"
  | true -> Android.Permissions.open_settings activity Background_location >>= require
let gps_status activity () =
  Android.Permissions.request activity ["android.permission.ACCESS_COARSE_LOCATION";"android.permission.ACCESS_FINE_LOCATION"] >>= require >>= fun granted ->
  if not (List.mem "android.permission.ACCESS_FINE_LOCATION" granted) then status "Localisation précise non autorisée" else
  Android.Location.enabled ~provider:Gps >>= require >>= fun enabled ->
  Android.Location.last_known ~provider:Gps >>= function
  | Ok (Some fix) -> status (Printf.sprintf "GPS : %.6f, %.6f (position en cache)" fix.latitude fix.longitude)
  | Ok None -> status (if enabled then "GPS actif, aucune position en cache" else "GPS désactivé")
  | Error (Android.Error.Permission_denied _) -> status "Localisation précise non autorisée"
  | Error (Android.Error.Unavailable _) -> status "GPS indisponible"
  | Error _ -> status "Erreur de localisation"
let alarm activity () =
  Android.Alarm.Screen.show activity {id="integration";notification_id=41;
    title="Alarme de test";text="Écran réutilisable";stop_label="Arrêter";snooze_label="Reporter";
    stop_handler="integration.stop";snooze_handler="integration.snooze";payload="integration"} >>= require
let manage_health_background activity =
  Android.Health_connect.background_read_granted () >>= function
  | Error e -> Lwt.return (Error e)
  | Ok true -> Android.Health_connect.open_settings activity
  | Ok false -> Android.Health_connect.request_background_read activity >|= Result.map (fun _ -> ())
let manage_gps_background activity =
  Android.Permissions.check_special Background_location >>= function
  | Error e -> Lwt.return (Error e)
  | Ok true -> Android.Permissions.open_settings activity Background_location
  | Ok false -> Android.Permissions.check "android.permission.ACCESS_FINE_LOCATION" >>= function
    | Error e -> Lwt.return (Error e)
    | Ok true -> Android.Permissions.open_settings activity Background_location
    | Ok false -> Android.Permissions.request activity ["android.permission.ACCESS_COARSE_LOCATION";"android.permission.ACCESS_FINE_LOCATION"] >>= function
      | Error e -> Lwt.return (Error e)
      | Ok granted when List.mem "android.permission.ACCESS_FINE_LOCATION" granted -> Android.Permissions.open_settings activity Background_location
      | Ok _ -> Lwt.return (Error (Android.Error.Permission_denied "Foreground location is required before background consent"))
let refresh_permission_settings page =
  (Android.Health_connect.background_read_available () >>= function
  | Error e -> Lwt.return (Error e)
  | Ok false -> Android.Settings.set_summary page ~key:"health-background" "Indisponible"
  | Ok true -> Android.Health_connect.background_read_granted () >>= function
    | Error e -> Lwt.return (Error e)
    | Ok granted -> Android.Settings.set_summary page ~key:"health-background" (if granted then "Autorisé" else "Non autorisé")) >>= function
  | Error e -> Lwt.return (Error e)
  | Ok () -> Android.Permissions.check_special Background_location >>= function
    | Error e -> Lwt.return (Error e)
    | Ok granted -> Android.Settings.set_summary page ~key:"gps-background" (if granted then "Autorisé" else "Non autorisé")
let settings_schema = match Android.Settings.define ~store:"integration-settings" [
  Section "Démonstration des réglages";
  Info {key="health-state";title="État de Health Connect";text="Vérification…"};
  Toggle {key="enabled";title="Activer la démonstration";description=None;initial=false};
  Text {key="description";title="Description";description=None;initial="Démonstration API";multiline=true;validate=None};
  Integer {key="count";title="Nombre de tâches";description=None;initial=1L;
    validate=Some (fun n -> if n>0L then Ok () else Error "Un entier positif est requis")};
  Choice {key="display";title="Présentation";description=None;initial="compact";options=["compact","Compacte";"detail","Détaillée"]};
  Status_action {key="health-background";title="Santé en arrière-plan";text="Vérification…";run=manage_health_background};
  Status_action {key="gps-background";title="GPS en arrière-plan";text="Vérification…";run=manage_gps_background}]
  with Ok schema -> schema | Error _ -> failwith "Invalid demo settings"
let form_dialog activity () =
  Android.Form.container activity >>= require >>= fun content ->
  Android.Form.text_field activity ~label:"Nom de la tâche" ~help:(Some "Ce nom est obligatoire") ~text:""
    ~keyboard:Text ~multiline:false ~validate:(Some (fun text -> if String.trim text="" then Error "Saisir un nom" else Ok ())) >>= require >>= fun field ->
  Android.Ui.add content (Android.Form.view field) >>= require >>= fun () ->
  let buttons=match Android.Dialog.buttons [{role=Positive;label="Enregistrer"};{role=Negative;label="Annuler"}] with Ok b -> b | Error _ -> assert false in
  Lwt.finalize (fun () ->
    Android.Dialog.show activity ~title:"Nouvelle tâche" ~content ~buttons ~cancelable:true
      ~on_error:(fun _ -> status "Erreur du formulaire")
      ~on_button:(function
        | Positive -> Android.Form.validate field >>= require >|= fun valid -> Ok (if valid then Android.Dialog.Close else Keep_open)
        | _ -> Lwt.return (Ok Android.Dialog.Close)) >>= require >>= fun dialog ->
    Android.Dialog.closed dialog >>= require >>= function
      | Button Positive -> Android.Form.get_text field >>= require >>= fun text -> status ("Tâche enregistrée : "^text)
      | _ -> status "Saisie annulée")
    (fun () -> Android.Form.release field >>= fun _ -> Android.Ui.release content >|= ignore)
let confirm_action activity () =
  Android.Dialog.confirm activity ~title:"Confirmation" ~message:"Confirmer cette action de démonstration ?" ~accept:"Confirmer" ~cancel:"Annuler" >>= require >>= fun accepted ->
  status (if accepted then "Action confirmée" else "Action annulée")
let alert_action activity () = Android.Dialog.alert activity ~title:"Information" ~message:"Dialogue standard partagé par les applications OCaml." ~button:"Fermer" >>= require
let render activity =
  Android.Ui.nested_scroll_view activity ~fill_viewport:true >>= require >>= fun scroll ->
  Android.Form.container activity >>= require >>= fun layout ->
  Android.Ui.add scroll layout >>= require >>= fun () ->
  Android.Ui.text activity !message >>= require >>= fun label ->
  Android.Ui.column activity ~spacing_dp:8 >>= require >>= fun preview_slot ->
  let ui={activity;layout=preview_slot;label;preview=None} in screen := Some ui;
  Android.Form.text_field activity ~label:"Nombre de tâches" ~help:(Some "Saisir un entier positif") ~text:""
    ~keyboard:(Number {signed=false;decimal=false}) ~multiline:false
    ~validate:(Some (fun text -> match int_of_string_opt text with Some n when n>0 -> Ok () | _ -> Error "Entier positif requis")) >>= require >>= fun number ->
  Android.Form.text_field activity ~label:"Description" ~help:None ~text:"" ~keyboard:Text ~multiline:true ~validate:None >>= require >>= fun notes ->
  Lwt_list.iter_s (fun child -> Android.Ui.add layout child >>= require) [label;Android.Form.view number;Android.Form.view notes] >>= fun () ->
  Android.Form.on_change number ~on_error:(fun _ -> status "Erreur de validation") (fun _ _ -> Lwt.return_unit) >>= require >>= fun _ ->
  Android.Ui.primary_button activity "Nouvelle tâche" >>= require >>= fun create ->
  Android.Ui.add layout create >>= require >>= fun () ->
  Android.Events.on_click create (form_dialog activity) >>= require >>= fun _ ->
  Lwt_list.iter_s (fun (label,callback) ->
    Android.Ui.secondary_button activity label >>= require >>= fun button ->
    Android.Ui.add layout button >>= require >>= fun () ->
    Android.Events.on_click button callback >>= require >|= fun _ -> ())
    ["Dialogue d’information",alert_action activity; "Confirmer une action",confirm_action activity; "Prendre une photo",capture activity; "Scanner un QR code",scan_qr activity; "Écran d’alarme",alarm activity; "Date, son et ressource",local_status;
     "Health Connect : état",health_status; "Health Connect : autorisations",health_permissions activity;
     "Health Connect : accès en arrière-plan",health_background_permissions activity;
     "GPS : autorisation en arrière-plan",gps_background_permissions activity;
     "GPS : autorisations et cache",gps_status activity; "Arrêter le suivi GPS",(fun () -> Android.Location.stop_tracking_service () >>= require)] >>= fun () ->
  Android.Ui.secondary_button activity "Réglages" >>= require >>= fun settings_button ->
  Android.Ui.add layout settings_button >>= require >>= fun () ->
  Android.Events.on_click settings_button (fun () -> Android.Settings.open_ activity >>= require) >>= require >>= fun _ ->
  Android.Ui.add layout preview_slot >>= require >>= fun () ->
  (* Rows and their count are fixture data, not a library configuration. *)
  Lwt_list.iter_s (fun n -> Android.Ui.text activity (Printf.sprintf "Tâche de démonstration %d" n) >>= require >>= fun row ->
    Android.Ui.add layout row >>= require) (List.init 30 ((+) 1)) >>= fun () ->
  (* This screen is fixture data demonstrating native Material/layout bindings. *)
  let padding:Android.Ui.insets={left_dp=16;top_dp=16;right_dp=16;bottom_dp=16} in
  let fill=match Android.Ui.layout ~width:Match_parent ~height:Match_parent () with Ok l -> l | Error _ -> assert false in
  let flexible=match Android.Ui.layout ~width:Match_parent ~height:(Dp 0) ~weight:1. () with Ok l -> l | Error _ -> assert false in
  Android.Ui.column activity >>= require >>= fun root ->
  Android.Resources.resolve ~scope:Android ~kind:Drawable ~name:"ic_menu_agenda" >>= require >>= fun tasks_icon ->
  Android.Resources.resolve ~scope:Android ~kind:Drawable ~name:"ic_menu_preferences" >>= require >>= fun settings_icon ->
  Android.Toolbar.create activity ~title:"Tâches" ~subtitle:(Some "Composants Material et OCaml") ~navigation_icon:None ~navigation_description:None
    ~actions:[{id=101;title="Nouvelle tâche";icon=None;display=If_room};{id=102;title="Réglages";icon=Some settings_icon;display=Never}] >>= require >>= fun toolbar ->
  Android.Toolbar.on_action toolbar (function 101 -> form_dialog activity () | _ -> Android.Settings.open_ activity >>= require) >>= require >>= fun _ ->
  Android.Ui.add root toolbar >>= require >>= fun () ->
  Android.Ui.stack activity >>= require >>= fun pages ->
  Android.Ui.nested_scroll_view activity ~fill_viewport:true >>= require >>= fun tasks_scroll ->
  Android.Ui.column activity ~spacing_dp:12 ~padding >>= require >>= fun tasks ->
  Android.Ui.add tasks_scroll tasks >>= require >>= fun () ->
  Android.Switch.create activity ~label:"Afficher les tâches terminées" ~checked:true >>= require >>= fun show_completed ->
  Android.Ui.add tasks show_completed >>= require >>= fun () ->
  let completed=ref [] in
  Lwt_list.iter_s (fun n ->
    Android.Card.create activity ~content_padding:padding >>= require >>= fun card ->
    Android.Ui.column activity ~spacing_dp:8 >>= require >>= fun body ->
    Android.Check_box.create activity ~label:(Printf.sprintf "Tâche %d" n) ~state:(if n=2 then Checked else Unchecked) >>= require >>= fun checked ->
    Android.Check_box.on_change checked (fun state ->
      status (Printf.sprintf "Tâche %d : %s" n (match state with Checked -> "terminée" | Unchecked -> "à faire" | Indeterminate -> "partielle"))) >>= require >>= fun _ ->
    Android.Ui.text activity "Carte avec présentation partagée" >>= require >>= fun description ->
    Lwt_list.iter_s (fun child -> Android.Ui.add body child >>= require) [checked;description] >>= fun () ->
    Android.Ui.add card body >>= require >>= fun () -> Android.Ui.add tasks card >>= require >>= fun () ->
    if n=2 then completed:=card::!completed;
    Android.Divider.create activity >>= require >>= fun divider -> Android.Ui.add tasks divider >>= require) [1;2;3] >>= fun () ->
  Android.Switch.on_change show_completed (fun visible -> Lwt_list.iter_s (fun card -> Android.Ui.set_visibility card (if visible then Visible else Gone) >>= require) !completed) >>= require >>= fun _ ->
  Android.Ui.add ~layout:fill pages tasks_scroll >>= require >>= fun () ->
  Android.Ui.add ~layout:fill pages scroll >>= require >>= fun () ->
  Android.Ui.set_visibility scroll Gone >>= require >>= fun () ->
  Android.Ui.add ~layout:flexible root pages >>= require >>= fun () ->
  Android.Bottom_navigation.create activity ~items:[{id=1;title="Tâches";icon=tasks_icon};{id=2;title="Composants";icon=settings_icon}] ~selected:1 >>= require >>= fun navigation ->
  Android.Bottom_navigation.on_select navigation (fun id ->
    Android.Ui.set_visibility tasks_scroll (if id=1 then Visible else Gone) >>= require >>= fun () ->
    Android.Ui.set_visibility scroll (if id=2 then Visible else Gone) >>= require >>= fun () ->
    Android.Toolbar.set_title toolbar (if id=1 then "Tâches" else "Composants" ) >>= require) >>= require >>= fun _ ->
  Android.Ui.add root navigation >>= require >>= fun () ->
  Android.Ui.apply_system_bar_insets root >>= require >>= fun () ->
  preview_photo () >>= fun () -> Android.Ui.show activity root >>= require
let () =
  (match Android.Settings.register ~name:"integration" settings_schema
    ~on_resume:(fun _ page ->
      (Android.Health_connect.status () >>= function
      | Error e -> Lwt.return (Error e)
      | Ok Unavailable -> Android.Settings.set_info page ~key:"health-state" "Indisponible"
      | Ok Update_required -> Android.Settings.set_info page ~key:"health-state" "Mise à jour requise"
      | Ok Available -> Android.Health_connect.granted_permissions () >>= function
        | Error e -> Lwt.return (Error e)
        | Ok permissions -> Android.Settings.set_info page ~key:"health-state"
          (Printf.sprintf "Disponible — %d familles autorisées" (List.length permissions))) >>= function
      | Error e -> Lwt.return (Error e) | Ok () -> refresh_permission_settings page)
    ~on_change:(fun ~key _ -> status ("Réglage enregistré : "^key))
    ~on_error:(fun _ -> status "Erreur de réglage") with
    | Ok () -> () | Error _ -> failwith "Duplicate settings schema");
  let register name handler = match Android.Runtime.register ~name handler with
    | Ok () -> () | Error _ -> failwith "Duplicate integration handler" in
  register "integration.stop" (fun _ ->
    Android.Activity.launch_main () >>= require >>= fun () ->
    status "Alarme arrêtée" >|= fun () -> Android.Background.Success);
  register "integration.snooze" (fun _ -> status "Report demandé" >|= fun () -> Android.Background.Success);
  Android.Runtime.set_entry (fun () ->
    Android.Activity.on_lifecycle (fun activity event ->
      match event,!screen with
      | Destroyed,Some ui when ui.activity = activity -> screen := None;Lwt.return_unit
      | Resumed,Some ui when ui.activity = activity -> Lwt.return_unit
      | Resumed,_ -> render activity
      | _ -> Lwt.return_unit) >>= require >|= fun _ -> ())
