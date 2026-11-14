open Android
let invalid = function Error (Error.Invalid_argument _) -> () | _ -> failwith "Expected invalid settings"
let () =
 Printexc.record_backtrace true;
 let bundle=Connectivity_settings.permissions ~key:"radio" ~title:"Bluetooth" ~unknown:"?" ~granted:"Yes" ~denied:"No" ~operation:Bluetooth.Permissions.Connect in
 assert (match Settings.define ~store:"settings" bundle.items with Ok _ -> true | _ -> false);
 invalid (Settings.define ~store:"settings" (bundle.items@bundle.items));
 Lwt_main.run (Lwt.bind (Bluetooth.available ()) (function Error (Error.Unavailable _) -> Lwt.return_unit | _ -> Lwt.fail_with "Host must report platform unavailable"))
