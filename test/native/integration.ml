open Lwt.Infix
let require = function Ok x -> Lwt.return x | Error _ -> Lwt.fail_with "Android integration operation failed"
let () =
  Android.Runtime.set_entry (fun () ->
    Android.Activity.on_lifecycle (fun activity event ->
      if event <> Android.Activity.Resumed then Lwt.return_unit else
      Android.Ui.linear_layout activity Android.Ui.Vertical >>= require >>= fun layout ->
      Android.Ui.text activity "OCaml Android — prêt" >>= require >>= fun label ->
      Android.Ui.button activity "Tester" >>= require >>= fun button ->
      Android.Ui.edit_text activity "" >>= require >>= fun input ->
      Android.Ui.add layout label >>= require >>= fun () ->
      Android.Ui.add layout button >>= require >>= fun () ->
      Android.Ui.add layout input >>= require >>= fun () ->
      Android.Events.on_click button (fun () -> Android.Ui.set_text label "Événement reçu ✓" >>= require) >>= require >>= fun _ ->
      Android.Ui.show activity layout >>= require) >>= require >|= fun _ -> ())
