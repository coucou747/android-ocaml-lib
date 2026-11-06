open Lwt.Infix
module S = Android.Settings
module H = Android.Health_connect
let require = function Ok value -> Lwt.return value | Error _ -> Lwt.fail_with "Status settings failure"
let run activity =
  let calls=ref 0 and errors=ref 0 in
  let schema=match S.define ~store:"status-settings" [
    Status_action {key="permission";title="Status permission";text="Unknown";
      run=(fun owner ->
        assert (owner=activity);incr calls;
        H.granted_permissions () >>= function
        | Error e -> Lwt.return (Error e)
        | Ok granted when List.mem H.Steps granted -> H.open_settings owner
        | Ok _ -> H.request_permissions owner [Steps] >|= function
          | Error e -> Error e | Ok granted when List.mem H.Steps granted -> Ok ()
          | Ok _ -> Error (Android.Error.Permission_denied "User denied consent"))}]
    with Ok schema -> schema | Error _ -> failwith "Status settings schema" in
  S.create activity schema
    ~on_resume:(fun owner page -> assert (owner=activity);
      H.granted_permissions () >>= function Error e -> Lwt.return (Error e) | Ok granted ->
        S.set_summary page ~key:"permission" (if List.mem H.Steps granted then "Granted" else "Denied"))
    ~on_change:(fun ~key:_ _ -> Lwt.fail_with "Permission status must not invoke on_change")
    ~on_error:(function Android.Error.Permission_denied _ -> incr errors;Lwt.return_unit | _ -> Lwt.fail_with "Unexpected permission action error") >>= require >>= fun page ->
  let rec finished () = Android.Storage.Preferences.get ~store:"status-test" ~key:"complete" >>= require >>= function
    | Some (Bool true) -> Lwt.return_unit | _ -> Lwt.pause () >>= finished in
  finished () >>= fun () -> assert (!calls=3 && !errors=1);
  Android.Storage.Preferences.get ~store:"status-settings" ~key:"permission" >>= require >>= fun stored -> assert (stored=None);
  S.set_info page ~key:"permission" "invalid" >>= (function Error (Android.Error.Invalid_argument _) -> Lwt.return_unit | _ -> Lwt.fail_with "set_info accepted an action") >>= fun () ->
  S.set_summary page ~key:"missing" "invalid" >>= (function Error (Android.Error.Invalid_argument _) -> Lwt.return_unit | _ -> Lwt.fail_with "Unknown summary key") >>= fun () ->
  S.refresh page >>= require >>= fun () -> S.release page >>= require >>= fun () ->
  S.set_summary page ~key:"permission" "late" >>= function
  | Error (Android.Error.Invalid_argument _) -> Lwt.return_unit | _ -> Lwt.fail_with "Released summary page"
