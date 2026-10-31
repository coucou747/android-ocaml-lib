open Lwt.Infix

module Error = struct
  type t = Unavailable of string | Permission_denied of string
    | Invalid_argument of string | Invalid_handle of string
    | Platform of string | Protocol of string
end

type 'a response = ('a, Error.t) result Lwt.t
external invoke : string -> string array -> string array = "android_invoke"
external take_event : unit -> string array = "android_take_event"

let decode = function
  | [|"error"; "permission"; s|] -> Error (Error.Permission_denied s)
  | [|"error"; "argument"; s|] -> Error (Error.Invalid_argument s)
  | [|"error"; "handle"; s|] -> Error (Error.Invalid_handle s)
  | [|"error"; "unavailable"; s|] -> Error (Error.Unavailable s)
  | [|"error"; _; s|] -> Error (Error.Platform s)
  | a when Array.length a > 0 && a.(0) = "ok" ->
    Ok (Array.sub a 1 (Array.length a - 1))
  | _ -> Error (Error.Protocol "Invalid JNI response")

let call name args =
  (* Cancellation cannot interrupt arbitrary Android platform calls. Do not
     abandon a resource-producing call; specific cancel/close APIs release it. *)
  Lwt.protected (Lwt_preemptive.detach (fun () -> decode (invoke name (Array.of_list args))) ())
let map f p = p >|= Result.map f
let unit_call n a = map (fun _ -> ()) (call n a)
let one n a = call n a >|= function
  | Ok [|s|] -> Ok s | Error e -> Error e
  | Ok _ -> Error (Error.Protocol (n ^ ": expected one value"))
let bool n a = map ((=) "true") (one n a)
let int n a = one n a >|= function
  | Error e -> Error e
  | Ok s -> (match int_of_string_opt s with Some n -> Ok n
    | None -> Error (Error.Protocol (n ^ ": expected integer")))
let invalid s = Lwt.return (Error (Error.Invalid_argument s))
let s = string_of_int
let i64 = Int64.to_string

module Subscription = struct
  type t = { id : string; mutable active : bool }
  let callbacks : (string, string array -> unit Lwt.t) Hashtbl.t = Hashtbl.create 16
  let next = ref 0
  let create callback =
    incr next;
    let t = {id = s !next; active = true} in
    Hashtbl.add callbacks t.id callback; t
  let cancel t =
    if t.active then (t.active <- false; Hashtbl.remove callbacks t.id);
    unit_call "unsubscribe" [t.id]
end

let subscribe command args callback =
  let t = Subscription.create callback in
  unit_call command (t.id :: args) >|= function
  | Ok () -> Ok t
  | Error e -> Hashtbl.remove Subscription.callbacks t.id; Error e

module Activity = struct
  type t = string
  type event = Created | Started | Resumed | Paused | Stopped | Destroyed
  type size = { width_px : int; height_px : int }
  let display_size activity = call "activity.displaySize" [activity] >|= function
    | Error e -> Error e
    | Ok [|w;h|] -> (match int_of_string_opt w,int_of_string_opt h with
      | Some width_px,Some height_px when width_px > 0 && height_px > 0 -> Ok {width_px;height_px}
      | _ -> Error (Error.Protocol "Invalid activity display size"))
    | Ok _ -> Error (Error.Protocol "Activity display size")
  let current () = one "activity.current" []
  let set_keep_screen_on t value = unit_call "activity.screen" [t; string_of_bool value]
  let on_lifecycle f = subscribe "activity.lifecycle" [] (fun a ->
    match a with
    | [|id; ev|] ->
      let ev = match ev with "created" -> Created | "started" -> Started
        | "resumed" -> Resumed | "paused" -> Paused | "stopped" -> Stopped
        | _ -> Destroyed in f id ev
    | _ -> Lwt.return_unit)
end

module Ui = struct
  type view = string
  type orientation = Horizontal | Vertical
  let text a label = one "ui.text" [a; label]
  let button a label = one "ui.button" [a; label]
  let edit_text a text = one "ui.edit" [a; text]
  let linear_layout a orientation = one "ui.layout" [a; (match orientation with Horizontal -> "horizontal" | Vertical -> "vertical")]
  let scroll_view a = one "ui.scroll" [a]
  let image a ~uri ~max_width_px ~max_height_px =
    if uri = "" || max_width_px <= 0 || max_height_px <= 0 then invalid "Image URI and positive bounds are required"
    else one "ui.image" [a; uri; s max_width_px; s max_height_px]
  let set_image_uri view ~uri ~max_width_px ~max_height_px =
    if uri = "" || max_width_px <= 0 || max_height_px <= 0 then invalid "Image URI and positive bounds are required"
    else unit_call "ui.imageUri" [view; uri; s max_width_px; s max_height_px]
  type input_type = Text | Number of { signed : bool; decimal : bool }
  let set_input_type view typ =
    let kind, signed, decimal = match typ with Text -> "text",false,false
      | Number {signed;decimal} -> "number",signed,decimal in
    unit_call "ui.inputType" [view; kind; string_of_bool signed; string_of_bool decimal]
  let set_multiline view value = unit_call "ui.multiline" [view; string_of_bool value]
  let set_hint view text = unit_call "ui.hint" [view; text]
  let set_error view error = unit_call "ui.error" [view; (match error with None -> "false" | Some _ -> "true"); Option.value error ~default:""]
  let add parent child = unit_call "ui.add" [parent; child]
  let show a root = unit_call "ui.show" [a; root]
  let set_text v text = unit_call "ui.setText" [v; text]
  let get_text v = one "ui.getText" [v]
  let set_enabled v b = unit_call "ui.enabled" [v; string_of_bool b]
  let release v = unit_call "ui.release" [v]
end

module Resources = struct
  type scope = Application | Android
  type kind = Drawable | Mipmap | Raw | String | Color
  let resolve ~scope ~kind ~name =
    if name = "" || String.contains name ':' || String.contains name '/' then invalid "Expected an unqualified resource name"
    else int "resources.resolve" [(match scope with Application -> "app" | Android -> "android");
      (match kind with Drawable -> "drawable" | Mipmap -> "mipmap" | Raw -> "raw" | String -> "string" | Color -> "color"); name]
end

module Locale = struct
  let time_zone () = one "locale.timeZone" []
  let language_tag () = one "locale.languageTag" []
  let uses_24_hour_clock () = bool "locale.is24Hour" []
  let format_date ~at_ms = one "locale.date" [i64 at_ms]
  let format_time ~at_ms = one "locale.time" [i64 at_ms]
  let format_date_time ~at_ms = one "locale.dateTime" [i64 at_ms]
end

module Events = struct
  type touch = { action : int; x : float; y : float }
  let on_click v f = subscribe "events.click" [v] (fun _ -> f ())
  let on_text_changed v f = subscribe "events.text" [v] (function
    | [|text|] -> f text | _ -> Lwt.return_unit)
  let on_touch v ~consume f = subscribe "events.touch" [v; string_of_bool consume]
    (function
    | [|a; x; y|] -> (match int_of_string_opt a, float_of_string_opt x, float_of_string_opt y with
      | Some action, Some x, Some y -> f {action; x; y} | _ -> Lwt.return_unit)
    | _ -> Lwt.return_unit)
end

module Permissions = struct
  type special = Exact_alarm | Notifications | Full_screen_intent
  let check name = bool "permissions.check" [name]
  let request a names = call "permissions.request" (a :: names) >|= Result.map
    (Array.to_list) (* Returns the permission names actually granted. *)
  let special_name = function Exact_alarm -> "exact" | Notifications -> "notifications" | Full_screen_intent -> "fullscreen"
  let check_special p = bool "permissions.special" [special_name p]
  let open_settings a p = unit_call "permissions.settings" [a; special_name p]
end

module Camera = struct
  type photo = string
  type capture = Captured of photo | Cancelled
  let uri photo = photo
  let of_uri uri = one "camera.open" [uri]
  let capture activity ~name =
    if name = "" || String.contains name '/' || String.contains name '\\'
      || not (Filename.check_suffix name ".jpg" || Filename.check_suffix name ".jpeg")
    then invalid "Expected a JPEG basename"
    else call "camera.capture" [activity; name] >|= function
      | Ok [||] -> Ok Cancelled
      | Ok [|uri|] -> Ok (Captured uri)
      | Error e -> Error e
      | Ok _ -> Error (Error.Protocol "Camera capture result")
  let read photo = one "camera.read" [photo]
  let delete photo = unit_call "camera.delete" [photo]
end

module Alarm = struct
  type mode = Exact | Alarm_clock
  let can_schedule_exact () = bool "alarm.allowed" []
  let schedule ~id ~at_ms ~mode ~handler ~payload =
    if id = "" || handler = "" || at_ms < 0L then invalid "Invalid alarm id, handler or timestamp"
    else unit_call "alarm.schedule" [id; i64 at_ms; (match mode with Exact -> "exact" | Alarm_clock -> "clock"); handler; payload]
  let cancel ~id = unit_call "alarm.cancel" [id]
  module Screen = struct
    type t = {
      id : string; notification_id : int; title : string; text : string;
      stop_label : string; snooze_label : string;
      stop_handler : string; snooze_handler : string; payload : string;
    }
    let valid t = t.id <> "" && t.stop_label <> "" && t.snooze_label <> ""
      && t.stop_handler <> "" && t.snooze_handler <> ""
    let args t = [t.id; s t.notification_id; t.title; t.text; t.stop_label;
      t.snooze_label; t.stop_handler; t.snooze_handler; t.payload]
    let show activity t = if not (valid t) then invalid "Alarm screen labels, id and handlers are required"
      else unit_call "alarm.screen.show" (activity :: args t)
    let notify ~channel ~small_icon ~full_screen t =
      if not (valid t) || channel = "" || small_icon <= 0 then invalid "Invalid alarm screen notification"
      else unit_call "alarm.screen.notify" ([channel; s small_icon; string_of_bool full_screen] @ args t)
    let dismiss ~id ~notification_id = if id = "" then invalid "Alarm screen id is required"
      else unit_call "alarm.screen.dismiss" [id; s notification_id]
  end
end

module Notification = struct
  type importance = Low | Default | High
  let create_channel ~id ~name ~importance = unit_call "notification.channel"
    [id; name; (match importance with Low -> "2" | Default -> "3" | High -> "4")]
  let show ~id ~channel ~title ~text ~small_icon ~handler ~payload =
    unit_call "notification.show" [s id; channel; title; text; s small_icon; handler; payload]
  let cancel ~id = unit_call "notification.cancel" [s id]
end

module Audio = struct
  type player = string
  let default_alarm_uri () = call "audio.defaultAlarm" [] >|= function
    | Ok [||] -> Ok None | Ok [|uri|] -> Ok (Some uri)
    | Error e -> Error e | Ok _ -> Error (Error.Protocol "Default alarm URI")
  let play ~uri ~loop = one "audio.play" [uri; string_of_bool loop]
  let stop p = unit_call "audio.stop" [p]
end
module Vibrator = struct
  let vibrate ~duration_ms = if duration_ms <= 0 then invalid "duration_ms must be positive"
    else unit_call "vibrator.start" [s duration_ms]
  let cancel () = unit_call "vibrator.cancel" []
end

module Background = struct
  type outcome = Success | Failure | Retry
  type constraints = { network : bool; charging : bool }
  let enqueue ~name ~handler ~payload ~constraints ~interval_ms =
    if name = "" || handler = "" then invalid "name and handler are required"
    else unit_call "background.work" [name; handler; payload; string_of_bool constraints.network;
      string_of_bool constraints.charging; (match interval_ms with None -> "" | Some v -> i64 v)]
  let cancel ~name = unit_call "background.cancel" [name]
  let schedule_job ~id ~handler ~payload ~constraints = unit_call "background.job"
    [s id; handler; payload; string_of_bool constraints.network; string_of_bool constraints.charging]
  let cancel_job ~id = unit_call "background.cancelJob" [s id]
  let start_service ~handler ~payload ~notification_id ~channel ~title ~small_icon =
    unit_call "background.service" [handler; payload; s notification_id; channel; title; s small_icon]
  let stop_service () = unit_call "background.stopService" []
end

module Network = struct
  type state = { connected : bool; metered : bool }
  let state () = call "network.state" [] >|= function
    | Ok [|c; m|] -> Ok {connected = c = "true"; metered = m = "true"}
    | Error e -> Error e | Ok _ -> Error (Error.Protocol "network state")
  let on_change f = subscribe "network.listen" [] (function
    | [|c; m|] -> f {connected = c = "true"; metered = m = "true"}
    | _ -> Lwt.return_unit)
  module Http = struct
    type response = { status : int; headers : (string * string) list; body : string }
    let request ~url ~meth ~headers ~body ~connect_timeout_ms ~read_timeout_ms =
      if connect_timeout_ms <= 0 || read_timeout_ms <= 0 then invalid "HTTP timeouts must be positive"
      else Lwt.no_cancel (one "http.create" []) >>= function
      | Error e -> Lwt.return (Error e)
      | Ok id -> Lwt.finalize (fun () -> call "http.request" ([id; url; meth; body; s connect_timeout_ms; s read_timeout_ms] @
        List.concat_map (fun (k,v) -> [k;v]) headers) >|= function
      | Error e -> Error e
      | Ok a when Array.length a >= 2 ->
        (match int_of_string_opt a.(0) with
        | None -> Error (Error.Protocol "HTTP status")
        | Some status ->
          let rec pairs i acc = if i + 1 >= Array.length a then List.rev acc
            else pairs (i+2) ((a.(i),a.(i+1))::acc) in
          Ok {status; body = a.(1); headers = pairs 2 []})
      | Ok _ -> Error (Error.Protocol "HTTP response"))
        (fun () -> unit_call "http.close" [id] >|= ignore)
  end
end

module Storage = struct
  let read_file ~name = one "storage.read" [name]
  let write_file ~name ~contents = unit_call "storage.write" [name; contents]
  let remove_file ~name = unit_call "storage.remove" [name]
  module Preferences = struct
    type value = Text of string | Bool of bool | Int of int64
    let set ~store ~key value = let typ,v = match value with
      | Text v -> "text",v | Bool v -> "bool",string_of_bool v | Int v -> "int",i64 v in
      unit_call "preferences.set" [store; key; typ; v]
    let get ~store ~key = call "preferences.get" [store; key] >|= function
      | Ok [||] -> Ok None
      | Ok [|"text"; v|] -> Ok (Some (Text v))
      | Ok [|"bool"; v|] -> Ok (Some (Bool (v = "true")))
      | Ok [|"int"; v|] -> (match Int64.of_string_opt v with
        | Some n -> Ok (Some (Int n)) | None -> Error (Error.Protocol "preference integer"))
      | Error e -> Error e | Ok _ -> Error (Error.Protocol "preference value")
    let remove ~store ~key = unit_call "preferences.remove" [store; key]
  end
  module Keystore = struct
    let create ~alias = unit_call "keystore.create" [alias]
    let encrypt ~alias ~plaintext = one "keystore.encrypt" [alias; plaintext]
    let decrypt ~alias ~ciphertext = one "keystore.decrypt" [alias; ciphertext]
    let delete ~alias = unit_call "keystore.delete" [alias]
  end
  module Sqlite = struct
    type t = string
    type value = Null | Integer of int64 | Float of float | Text of string | Blob of string
    type statement = { sql : string; params : value list }
    let encode = function Null -> ["null"; ""] | Integer n -> ["int"; i64 n]
      | Float n -> ["float"; string_of_float n] | Text v -> ["text"; v] | Blob v -> ["blob"; v]
    let args q = q.sql :: List.concat_map encode q.params
    let open_db ~name = one "sqlite.open" [name]
    let close db = unit_call "sqlite.close" [db]
    let execute db q = unit_call "sqlite.execute" (db :: args q)
    let transaction db qs = unit_call "sqlite.transaction"
      (db :: List.concat_map (fun q -> s (List.length q.params) :: args q) qs)
    let query db q = call "sqlite.query" (db :: args q) >|= function
      | Error e -> Error e
      | Ok a ->
        let rec rows i acc =
          if i = Array.length a then Ok (List.rev acc)
          else match int_of_string_opt a.(i) with
          | Some n when n >= 0 && n <= (Array.length a - i - 1) / 2 ->
            let rec cells j acc = if j = n then Some (List.rev acc) else
              let typ = a.(i+1+j*2) and v = a.(i+2+j*2) in
              let cell = match typ with
                | "null" -> Some Null | "text" -> Some (Text v) | "blob" -> Some (Blob v)
                | "int" -> Option.map (fun x -> Integer x) (Int64.of_string_opt v)
                | "float" -> Option.map (fun x -> Float x) (float_of_string_opt v)
                | _ -> None in
              match cell with None -> None | Some c -> cells (j+1) (c::acc) in
            (match cells 0 [] with Some row -> rows (i+1+2*n) (row::acc)
             | None -> Error (Error.Protocol "SQLite cell"))
          | _ -> Error (Error.Protocol "SQLite row") in rows 0 []
  end
end
module Broadcast = struct
  type event = { action : string; payload : string }
  let listen ~actions f = subscribe "broadcast.listen" actions (function
    | [|action; payload|] -> f {action; payload} | _ -> Lwt.return_unit)
end

module Runtime = struct
  type handler = string -> Background.outcome Lwt.t
  let handlers : (string, handler) Hashtbl.t = Hashtbl.create 16
  let running : (string, Background.outcome Lwt.t) Hashtbl.t = Hashtbl.create 16
  let entry = ref (fun () -> Lwt.return_unit)
  let on_error = ref (fun e -> prerr_endline (Printexc.to_string e ^ "\n" ^ Printexc.get_backtrace ()))
  let register ~name handler =
    if name = "" || Hashtbl.mem handlers name then Error (Error.Invalid_argument "empty or duplicate handler")
    else (Hashtbl.add handlers name handler; Ok ())
  let set_entry f = entry := f
  let set_error_handler f = on_error := f
  let protect f = Lwt.catch f (fun e -> !on_error e; Lwt.return_unit)
  let dispatch = function
    | [|"cancel"; token|] ->
      Option.iter Lwt.cancel (Hashtbl.find_opt running token); Lwt.return_unit
    | [|"unsubscribe"; id|] -> Hashtbl.remove Subscription.callbacks id; Lwt.return_unit
    | [|"handler"; token; name; payload|] ->
      Lwt.async (fun () -> Lwt.catch (fun () ->
        let task = match Hashtbl.find_opt handlers name with
        | None -> Lwt.return Background.Failure | Some f -> f payload in
        Hashtbl.replace running token task;
        task >>= fun result ->
        Hashtbl.remove running token;
        unit_call "runtime.complete" [token; (match result with Background.Success -> "success" | Failure -> "failure" | Retry -> "retry")] >|= ignore)
        (fun e -> Hashtbl.remove running token; (match e with Lwt.Canceled -> () | _ -> !on_error e); unit_call "runtime.complete" [token; "failure"] >|= ignore));
      Lwt.return_unit
    | a when Array.length a > 0 ->
      (match Hashtbl.find_opt Subscription.callbacks a.(0) with
       | None -> Lwt.return_unit
       | Some f -> Lwt.async (fun () -> protect (fun () -> f (Array.sub a 1 (Array.length a - 1)))); Lwt.return_unit)
    | _ -> Lwt.return_unit
  let rec loop () = Lwt_preemptive.detach take_event () >>= fun event ->
    if Array.length event > 0 && event.(0) = "error" then
      Lwt.fail_with "Android event queue unavailable"
    else dispatch event >>= loop
  let start () =
    Printexc.record_backtrace true;
    Lwt_main.run (protect !entry >>= loop)
  let () = Callback.register "android.runtime.start" start
end
