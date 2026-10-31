open Lwt.Infix
let require = function Ok v -> Lwt.return v | Error _ -> Lwt.fail_with "JNI smoke failure"
let binary = "\000\255\042\000"
let () = Android.Runtime.set_entry (fun () ->
  Android.Activity.on_lifecycle (fun activity _ ->
    Android.Ui.text activity "Été 🐫" >>= require >>= fun view ->
    Android.Ui.get_text view >>= require >>= fun text ->
    assert (text = "Été 🐫");
    Lwt_list.iter_p (fun _ ->
      Android.Storage.read_file ~name:"binary" >>= require >|= fun data -> assert (data = binary))
      [(); (); (); (); (); (); (); ()] >>= fun () ->
    Gc.full_major ();
    Android.Storage.write_file ~name:"done" ~contents:binary >>= require) >>= require >|= fun _ -> ())
