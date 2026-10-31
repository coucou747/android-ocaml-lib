open Lwt.Infix
let require = function Ok x -> Lwt.return x | Error _ -> Lwt.fail_with "Android integration operation failed"
let label = ref None
let status message = match !label with None -> Lwt.return_unit | Some view -> Android.Ui.set_text view message >>= require
let () =
  List.iter (fun (name,message) ->
    match Android.Runtime.register ~name (fun _ -> status message >|= fun () -> Android.Background.Success) with
    | Ok () -> () | Error _ -> failwith "Duplicate integration handler")
    ["integration.stop","Alarme arrêtée"; "integration.snooze","Report demandé"];
  let rendered = ref None in
  Android.Runtime.set_entry (fun () ->
    Android.Activity.on_lifecycle (fun activity event ->
      if event = Android.Activity.Destroyed then (rendered := None; label := None; Lwt.return_unit)
      else if event <> Android.Activity.Resumed || !rendered = Some activity then Lwt.return_unit else (
      rendered := Some activity;
      Android.Ui.linear_layout activity Android.Ui.Vertical >>= require >>= fun layout ->
      Android.Ui.text activity "OCaml Android — prêt" >>= require >>= fun text ->
      label := Some text;
      Android.Ui.button activity "Tester" >>= require >>= fun button ->
      Android.Ui.edit_text activity "" >>= require >>= fun input ->
      Android.Ui.button activity "Prendre une photo" >>= require >>= fun photo ->
      Android.Ui.button activity "Écran d’alarme" >>= require >>= fun alarm ->
      Lwt_list.iter_s (fun child -> Android.Ui.add layout child >>= require) [text;button;input;photo;alarm] >>= fun () ->
      Android.Events.on_click button (fun () -> status "Événement reçu ✓") >>= require >>= fun _ ->
      Android.Events.on_click photo (fun () ->
        (* This basename is test data; the application supplies its own names. *)
        let name = Printf.sprintf "photo-%Ld.jpg" (Int64.of_float (Unix.gettimeofday () *. 1000000.)) in
        Android.Camera.capture activity ~name >>= require >>= function
        | Cancelled -> status "Photo annulée"
        | Captured captured -> status (Android.Camera.uri captured)) >>= require >>= fun _ ->
      Android.Events.on_click alarm (fun () ->
        Android.Alarm.Screen.show activity {id="integration";notification_id=41;
          title="Alarme de test";text="Écran réutilisable";stop_label="Arrêter";snooze_label="Reporter";
          stop_handler="integration.stop";snooze_handler="integration.snooze";payload="integration"} >>= require) >>= require >>= fun _ ->
      Android.Ui.show activity layout >>= require)) >>= require >|= fun _ -> ())
