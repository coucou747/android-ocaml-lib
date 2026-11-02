open Lwt.Infix
module A=Android.Authentication
let bad = function Error (A.Invalid_argument _) -> () | _ -> failwith "Expected auth configuration error"
let () =
  Printexc.record_backtrace true;
  List.iter (fun name -> bad (A.Token_store.create ~name ~key_alias:"key")) ["";".";"..";"a/b";"a\\b";"a\000b"];
  bad (A.Token_store.create ~name:"session" ~key_alias:"");
  let store=match A.Token_store.create ~name:"session" ~key_alias:"key" with Ok s -> s | Error _ -> assert false in
  Lwt_main.run (
    A.Token_store.save store {access_token="bad\r\ntoken";expires_at_ms=Int64.max_int} >>= (fun r -> bad r;Lwt.return_unit) >>= fun () ->
    A.Client.create ~base_url:"https://unused.invalid" ~token_store:store ~connect_timeout_ms:0 ~read_timeout_ms:1 >|= bad)
