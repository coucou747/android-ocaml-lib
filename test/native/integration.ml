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
let local_status () =
  let at_ms = Int64.of_float (Unix.gettimeofday () *. 1000.) in
  Android.Locale.format_date_time ~at_ms >>= require >>= fun date ->
  Android.Locale.time_zone () >>= require >>= fun zone ->
  Android.Audio.default_alarm_uri () >>= require >>= fun sound ->
  Android.Resources.resolve ~scope:Application ~kind:Drawable ~name:"integration_alarm_icon" >>= require >>= fun icon ->
  status (Printf.sprintf "%s (%s)\nIcône : %d\nSon : %s" date zone icon (Option.value sound ~default:"aucun"))
let alarm activity () =
  Android.Alarm.Screen.show activity {id="integration";notification_id=41;
    title="Alarme de test";text="Écran réutilisable";stop_label="Arrêter";snooze_label="Reporter";
    stop_handler="integration.stop";snooze_handler="integration.snooze";payload="integration"} >>= require
let render activity =
  Android.Ui.scroll_view activity >>= require >>= fun scroll ->
  Android.Ui.linear_layout activity Android.Ui.Vertical >>= require >>= fun layout ->
  Android.Ui.add scroll layout >>= require >>= fun () ->
  Android.Ui.text activity !message >>= require >>= fun label ->
  Android.Ui.linear_layout activity Android.Ui.Vertical >>= require >>= fun preview_slot ->
  let ui={activity;layout=preview_slot;label;preview=None} in screen := Some ui;
  Android.Ui.edit_text activity "" >>= require >>= fun number ->
  Android.Ui.set_input_type number (Number {signed=false;decimal=false}) >>= require >>= fun () ->
  Android.Ui.set_hint number "Nombre de tâches" >>= require >>= fun () ->
  Android.Ui.edit_text activity "" >>= require >>= fun notes ->
  Android.Ui.set_multiline notes true >>= require >>= fun () ->
  Android.Ui.set_hint notes "Description sur plusieurs lignes" >>= require >>= fun () ->
  Lwt_list.iter_s (fun child -> Android.Ui.add layout child >>= require) [label;number;notes] >>= fun () ->
  Android.Events.on_text_changed number (fun text ->
    Android.Ui.set_error number (if String.trim text = "" then Some "Champ requis" else None) >>= require) >>= require >>= fun _ ->
  Lwt_list.iter_s (fun (label,callback) ->
    Android.Ui.button activity label >>= require >>= fun button ->
    Android.Ui.add layout button >>= require >>= fun () ->
    Android.Events.on_click button callback >>= require >|= fun _ -> ())
    ["Prendre une photo",capture activity; "Écran d’alarme",alarm activity; "Date, son et ressource",local_status] >>= fun () ->
  Android.Ui.add layout preview_slot >>= require >>= fun () ->
  (* Rows and their count are fixture data, not a library configuration. *)
  Lwt_list.iter_s (fun n -> Android.Ui.text activity (Printf.sprintf "Tâche de démonstration %d" n) >>= require >>= fun row ->
    Android.Ui.add layout row >>= require) (List.init 30 ((+) 1)) >>= fun () ->
  preview_photo () >>= fun () -> Android.Ui.show activity scroll >>= require
let () =
  List.iter (fun (name,text) ->
    match Android.Runtime.register ~name (fun _ -> status text >|= fun () -> Android.Background.Success) with
    | Ok () -> () | Error _ -> failwith "Duplicate integration handler")
    ["integration.stop","Alarme arrêtée";"integration.snooze","Report demandé"];
  Android.Runtime.set_entry (fun () ->
    Android.Activity.on_lifecycle (fun activity event ->
      match event,!screen with
      | Destroyed,Some ui when ui.activity = activity -> screen := None; Lwt.return_unit
      | Resumed,Some ui when ui.activity = activity -> Lwt.return_unit
      | Resumed,_ -> render activity
      | _ -> Lwt.return_unit) >>= require >|= fun _ -> ())
