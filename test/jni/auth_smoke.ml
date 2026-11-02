open Lwt.Infix
module A=Android.Authentication
let require = function Ok v -> Lwt.return v | Error _ -> Lwt.fail_with "Authentication smoke failed"
let expect predicate task = task >>= function Error e when predicate e -> Lwt.return_unit | _ -> Lwt.fail_with "Expected structured authentication error"
let codecs : A.codecs = {
  decode_options=(fun j -> try let open Yojson.Safe.Util in Ok {A.transaction_id=j |> member "tx" |> to_string;
    public_key_json=Yojson.Safe.to_string (member "options" j)} with _ -> Error "Invalid options");
  encode_verification=(fun ~transaction_id ~credential -> `Assoc ["tx",`String transaction_id;"assertion",credential]);
  decode_grant=(fun j -> try let open Yojson.Safe.Util in Ok {A.access_token=j |> member "token" |> to_string;
    token_type=j |> member "scheme" |> to_string; expires_in=j |> member "lifetime" |> to_string |> Int64.of_string;
    recovery_codes=j |> member "codes" |> to_list |> List.map to_string} with _ -> Error "Invalid grant");
}
let run activity =
  let store=match A.Token_store.create ~name:"auth-session" ~key_alias:"auth-key" with Ok s -> s | Error _ -> assert false in
  A.Client.create ~base_url:"https://auth.test" ~token_store:store ~connect_timeout_ms:1000 ~read_timeout_ms:1000 >>= require >>= fun client ->
  A.Token_store.load store >>= require >>= fun saved -> assert (saved=None);
  let expired=match A.Token_store.create ~name:"expired-session" ~key_alias:"auth-key" with Ok s -> s | _ -> assert false in
  A.Token_store.load expired >>= require >>= fun saved -> assert (saved=None);
  let corrupt=match A.Token_store.create ~name:"corrupt-session" ~key_alias:"auth-key" with Ok s -> s | _ -> assert false in
  expect (function A.Android _ -> true | _ -> false) (A.Token_store.load corrupt) >>= fun () ->
  expect (function A.Not_authenticated -> true | _ -> false) (A.Client.me client) >>= fun () ->
  expect (function A.Invalid_argument _ -> true | _ -> false) (A.create_passkey activity ~options_json:"[]") >>= fun () ->
  Lwt_list.iter_s (fun (mode,predicate) -> expect predicate (A.get_passkey activity ~options_json:("{\"mode\":\""^mode^"\"}")))
    ["cancel",(function A.Cancelled -> true | _ -> false);"none",(function A.No_credential -> true | _ -> false);
     "unsupported",(function A.Unsupported -> true | _ -> false);"provider",(function A.Provider_error _ -> true | _ -> false)] >>= fun () ->
  let pending=A.get_passkey activity ~options_json:"{\"mode\":\"wait\"}" in
  let rec ready path = A.Client.request_public client ~path ~meth:"GET" ~body:None >>= require >>= function
    | `Bool true -> Lwt.return_unit | _ -> Lwt.pause () >>= fun () -> ready path in
  ready "/waiting" >>= fun () -> Lwt.cancel pending;
  Lwt.catch (fun () -> pending >>= fun _ -> Lwt.fail_with "Credential cancellation did not cancel promise")
    (function Lwt.Canceled -> Lwt.return_unit | exn -> Lwt.fail exn) >>= fun () ->
  A.app_identity () >>= require >>= fun identity -> assert (identity.package_name="fixture.app" && List.length identity.origins=1);
  A.Client.register client activity ~username:"alice" ~codecs >>= require >>= fun grant -> assert (grant.recovery_codes=["one-use-recovery"]);
  A.Token_store.load store >>= require >>= fun saved -> assert (Option.map (fun (s:A.session) -> s.access_token) saved=Some "session-1");
  A.Client.me client >>= require >>= fun _ ->
  A.Client.sessions client >>= require >>= fun _ ->
  expect (function A.Invalid_argument _ -> true | _ -> false) (A.Client.revoke_session client ~id:"..") >>= fun () ->
  A.Client.revoke_session client ~id:"id/with space" >>= require >>= fun _ ->
  A.Client.credentials client >>= require >>= fun _ ->
  A.Client.revoke_credential client ~id:"credential-1" >>= require >>= fun _ ->
  A.Client.reauthenticate client activity ~payload:(`Assoc ["action",`String "add-passkey"]) ~codecs >>= require >>= fun _ ->
  A.Client.add_passkey client activity ~payload:(`Assoc []) ~codecs >>= require >>= fun _ ->
  expect (function A.Cancelled -> true | _ -> false) (A.Client.login client activity ~username:(Some "cancel") ~codecs) >>= fun () ->
  expect (function A.Invalid_response _ -> true | _ -> false) (A.Client.login client activity ~username:(Some "broken") ~codecs) >>= fun () ->
  A.Client.login client activity ~username:None ~codecs >>= require >>= fun _ ->
  expect (function A.Http_status 302 -> true | _ -> false) (A.Client.request client ~path:"/redirect" ~meth:"GET" ~body:None) >>= fun () ->
  expect (function A.Android (Android.Error.Invalid_argument _) -> true | _ -> false) (A.Client.request client ~path:"//evil.invalid/" ~meth:"GET" ~body:None) >>= fun () ->
  let pending=A.Client.request client ~path:"/race" ~meth:"GET" ~body:None in
  ready "/race-ready" >>= fun () ->
  A.Token_store.save store {access_token="session-new";expires_at_ms=Int64.max_int} >>= require >>= fun () ->
  A.Client.request_public client ~path:"/release-race" ~meth:"GET" ~body:None >>= require >>= fun _ ->
  expect (function A.Http_status 401 -> true | _ -> false) pending >>= fun () ->
  A.Token_store.load store >>= require >>= fun saved -> assert (Option.map (fun (s:A.session) -> s.access_token) saved=Some "session-new");
  expect (function A.Http_status 401 -> true | _ -> false) (A.Client.request client ~path:"/unauthorized" ~meth:"GET" ~body:None) >>= fun () ->
  A.Token_store.load store >>= require >>= fun saved -> assert (saved=None);
  A.Client.login client activity ~username:None ~codecs >>= require >>= fun _ ->
  let excessive={codecs with decode_grant=(fun _ -> Ok {access_token="bad-lifetime";token_type="Bearer";expires_in=Int64.max_int;recovery_codes=[]})} in
  expect (function A.Invalid_response _ -> true | _ -> false) (A.Client.login client activity ~username:None ~codecs:excessive) >>= fun () ->
  let wrong_type={codecs with decode_grant=(fun _ -> Ok {access_token="wrong-type";token_type="Basic";expires_in=3600L;recovery_codes=[]})} in
  expect (function A.Invalid_response _ -> true | _ -> false) (A.Client.login client activity ~username:None ~codecs:wrong_type) >>= fun () ->
  A.Client.request_public client ~path:"/fail-logout" ~meth:"GET" ~body:None >>= require >>= fun _ ->
  expect (function A.Http_status 503 -> true | _ -> false) (A.Client.logout client) >>= fun () ->
  A.Token_store.load store >>= require >>= fun saved -> assert (saved=None);
  A.Client.logout client >>= require
