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
  let launch_main () = unit_call "activity.launchMain" []
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
  type special = Exact_alarm | Notifications | Full_screen_intent | Background_location
  let check name = bool "permissions.check" [name]
  let request a names = call "permissions.request" (a :: names) >|= Result.map
    (Array.to_list) (* Returns the permission names actually granted. *)
  let special_name = function Exact_alarm -> "exact" | Notifications -> "notifications" | Full_screen_intent -> "fullscreen" | Background_location -> "background_location"
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
  let file_uri ~name =
    if name = "" || name = "." || name = ".." || String.contains name '/' || String.contains name '\\' || String.contains name '\000'
    then invalid "Expected a private file basename"
    else one "storage.fileUri" [name]
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


(* JSON is confined to the private JNI transport, decoded with Yojson. *)
module Wire_json = struct
  open Yojson.Safe.Util
  let optional f = function `Null -> None | value -> Some (f value)
  let int64 value = match Int64.of_string_opt (to_string value) with
    | Some n -> n | None -> invalid_arg "wire integer"
  let number = function `Int n -> float_of_int n | `Float n -> n | _ -> invalid_arg "wire number"
  let field name f json = f (member name json)
  let decode f text = try Ok (f (Yojson.Safe.from_string text)) with
    | Yojson.Json_error _ | Yojson.Safe.Util.Type_error _ | Invalid_argument _ | Failure _ ->
      Error (Error.Protocol "Invalid structured Android response")
  let request name args f = one name args >|= function Error e -> Error e | Ok text -> decode f text
end

module Health_connect = struct
  type availability = Available | Unavailable | Update_required
  type record_type = Steps | Heart_rate | Sleep | Exercise | Distance | Active_calories | Total_calories
  type interval = { start_ms : int64; end_ms : int64; start_offset_s : int option; end_offset_s : int option }
  type metadata = { id : string; origin : string; last_modified_ms : int64 }
  type steps = { metadata : metadata; interval : interval; count : int64 }
  type sample = { at_ms : int64; bpm : int64 }
  type heart_rate = { metadata : metadata; interval : interval; samples : sample list }
  type stage = { start_ms : int64; end_ms : int64; stage_type : int }
  type sleep = { metadata : metadata; interval : interval; title : string option; notes : string option; stages : stage list }
  type exercise = { metadata : metadata; interval : interval; exercise_type : int; title : string option; notes : string option }
  type query = { start_ms : int64; end_ms : int64; page_size : int; page_token : string option }
  type 'a page = { records : 'a list; next_page_token : string option }
  let name = function Steps -> "steps" | Heart_rate -> "heart" | Sleep -> "sleep" | Exercise -> "exercise" | Distance -> "distance" | Active_calories -> "active_calories" | Total_calories -> "total_calories"
  let status () = one "health.status" [] >|= function
    | Ok "available" -> Ok Available | Ok "unavailable" -> Ok Unavailable
    | Ok "update_required" -> Ok Update_required | Error e -> Error e | Ok _ -> Error (Error.Protocol "Health availability")
  let read_permission typ = one "health.permission" [name typ]
  let decode_types a =
    let rec loop i result = if i = Array.length a then Ok (List.rev result) else
      let typ = match a.(i) with "steps" -> Some Steps | "heart" -> Some Heart_rate | "sleep" -> Some Sleep | "exercise" -> Some Exercise | "distance" -> Some Distance | "active_calories" -> Some Active_calories | "total_calories" -> Some Total_calories | _ -> None in
      match typ with Some t -> loop (i+1) (t::result) | None -> Error (Error.Protocol "Health permission type") in loop 0 []
  let granted_permissions () = call "health.granted" [] >|= function Error e -> Error e | Ok a -> decode_types a
  let request_permissions activity types = call "health.request" (activity :: List.map name types) >|= function Error e -> Error e | Ok a -> decode_types a
  let background_read_available () = bool "health.backgroundAvailable" []
  let background_read_granted () = bool "health.backgroundGranted" []
  let request_background_read activity = bool "health.backgroundRequest" [activity]
  let open_settings activity = unit_call "health.settings" [activity]
  open Yojson.Safe.Util
  open Wire_json
  let metadata json = {id=field "id" to_string json; origin=field "origin" to_string json; last_modified_ms=field "last_modified_ms" int64 json}
  let interval json : interval = {start_ms=field "start_ms" int64 json;end_ms=field "end_ms" int64 json;
    start_offset_s=field "start_offset_s" (optional to_int) json;end_offset_s=field "end_offset_s" (optional to_int) json}
  let decode_steps json : steps = {metadata=metadata json;interval=interval json;count=field "count" int64 json}
  let decode_heart json : heart_rate = {metadata=metadata json;interval=interval json;samples=field "samples" (fun j -> List.map (fun sample ->
    {at_ms=field "at_ms" int64 sample;bpm=field "bpm" int64 sample}) (to_list j)) json}
  let decode_sleep json : sleep = {metadata=metadata json;interval=interval json;title=field "title" (optional to_string) json;notes=field "notes" (optional to_string) json;
    stages=field "stages" (fun j -> List.map (fun stage -> {start_ms=field "start_ms" int64 stage;end_ms=field "end_ms" int64 stage;stage_type=field "stage" to_int stage}) (to_list j)) json}
  let decode_exercise json : exercise = {metadata=metadata json;interval=interval json;title=field "title" (optional to_string) json;notes=field "notes" (optional to_string) json;exercise_type=field "exercise_type" to_int json}
  let read typ decode_record (query : query) =
    if query.start_ms >= query.end_ms || query.page_size <= 0 then invalid "Nonempty health interval and positive page size required"
    else Wire_json.request "health.read" [name typ;i64 query.start_ms;i64 query.end_ms;s query.page_size;Option.value query.page_token ~default:""]
      (fun json -> {records=field "records" (fun j -> List.map decode_record (to_list j)) json;next_page_token=field "next_page_token" (optional to_string) json})
  let read_steps = read Steps decode_steps
  let read_heart_rate = read Heart_rate decode_heart
  let read_sleep = read Sleep decode_sleep
  let read_exercise = read Exercise decode_exercise
  let aggregate_steps ~start_ms ~end_ms =
    if start_ms >= end_ms then invalid "Nonempty health interval required" else
    call "health.aggregateSteps" [i64 start_ms;i64 end_ms] >|= function
    | Error e -> Error e | Ok [||] -> Ok None
    | Ok [|value|] -> (match Int64.of_string_opt value with Some n -> Ok (Some n) | None -> Error (Error.Protocol "Health step aggregate"))
    | Ok _ -> Error (Error.Protocol "Health step aggregate")
  let aggregate_amount kind ~start_ms ~end_ms =
    if start_ms >= end_ms then invalid "Nonempty health interval required" else
    call "health.aggregateAmount" [name kind;i64 start_ms;i64 end_ms] >|= function
    | Error e -> Error e | Ok [||] -> Ok None
    | Ok [|value|] -> (match float_of_string_opt value with
      | Some n when Float.is_finite n && n >= 0. -> Ok (Some n)
      | _ -> Error (Error.Protocol "Health amount aggregate"))
    | Ok _ -> Error (Error.Protocol "Health amount aggregate")
  let aggregate_distance = aggregate_amount Distance
  let aggregate_active_calories = aggregate_amount Active_calories
  let aggregate_total_calories = aggregate_amount Total_calories
end

module Location = struct
  type provider = Gps | Network
  type fix = { provider : string option; latitude : float; longitude : float;
    accuracy_m : float option; altitude_m : float option; speed_mps : float option; bearing_deg : float option;
    at_ms : int64; elapsed_realtime_ns : int64; is_mock : bool }
  type event = Fix of fix | Provider_enabled of string | Provider_disabled of string
  let name = function Gps -> "gps" | Network -> "network"
  open Yojson.Safe.Util
  open Wire_json
  let decode_fix json = {
    provider=field "provider" (optional to_string) json;latitude=field "latitude" number json;longitude=field "longitude" number json;
    accuracy_m=field "accuracy_m" (optional number) json;altitude_m=field "altitude_m" (optional number) json;
    speed_mps=field "speed_mps" (optional number) json;bearing_deg=field "bearing_deg" (optional number) json;
    at_ms=field "at_ms" int64 json;elapsed_realtime_ns=field "elapsed_realtime_ns" int64 json;is_mock=field "is_mock" to_bool json}
  let decode_event json = match field "type" to_string json with
    | "fix" -> Fix (field "fix" decode_fix json)
    | "enabled" -> Provider_enabled (field "provider" to_string json)
    | "disabled" -> Provider_disabled (field "provider" to_string json)
    | _ -> invalid_arg "Location event"
  let distance_between ~latitude1 ~longitude1 ~latitude2 ~longitude2 =
    let valid latitude longitude = Float.is_finite latitude && Float.is_finite longitude
      && abs_float latitude <= 90. && abs_float longitude <= 180. in
    if not (valid latitude1 longitude1 && valid latitude2 longitude2) then invalid "Invalid geographic coordinates"
    else one "location.distanceBetween" (List.map string_of_float [latitude1;longitude1;latitude2;longitude2]) >|= function
      | Error e -> Error e
      | Ok value -> (match float_of_string_opt value with
        | Some n when Float.is_finite n && n >= 0. -> Ok n
        | _ -> Error (Error.Protocol "Geodesic distance"))
  let enabled ~provider = bool "location.enabled" [name provider]
  let decode_optional = function
    | Error e -> Error e | Ok [||] -> Ok None
    | Ok [|value|] -> Result.map Option.some (decode decode_fix value)
    | Ok _ -> Error (Error.Protocol "Location fix")
  let last_known ~provider = call "location.last" [name provider] >|= decode_optional
  let current ~provider ~timeout_ms =
    if timeout_ms <= 0 then invalid "Positive location timeout required" else
    Lwt.no_cancel (one "location.currentStart" [name provider;s timeout_ms]) >>= function
    | Error e -> Lwt.return (Error e)
    | Ok token -> Lwt.finalize (fun () -> call "location.currentAwait" [token] >|= decode_optional)
      (fun () -> unit_call "location.currentClose" [token] >|= ignore)
  let valid interval distance = interval >= 0L && Float.is_finite distance && distance >= 0. && distance <= 3.4028234663852886e38
  let watch ~provider ~interval_ms ~min_distance_m callback =
    if not (valid interval_ms min_distance_m) then invalid "Invalid location interval or distance" else
    subscribe "location.watch" [name provider;i64 interval_ms;string_of_float min_distance_m] (function
      | [|payload|] -> (match decode decode_event payload with Ok event -> callback event | Error _ -> Lwt.fail_with "Invalid location event")
      | _ -> Lwt.fail_with "Invalid location event")
  let register_background_handler ~name:handler callback = Runtime.register ~name:handler (fun token ->
    Wire_json.request "location.event" [token] decode_event >>= function
    | Ok event -> callback event | Error _ -> Lwt.return Background.Failure)
  let start_tracking_service ~provider ~interval_ms ~min_distance_m ~notification_id ~channel ~title ~text ~small_icon ~handler =
    if not (valid interval_ms min_distance_m) || notification_id <= 0 || small_icon <= 0 || channel = "" || handler = "" then invalid "Invalid location service configuration"
    else unit_call "location.serviceStart" [name provider;i64 interval_ms;string_of_float min_distance_m;s notification_id;channel;title;text;s small_icon;handler]
  let stop_tracking_service () = unit_call "location.serviceStop" []
end

module Settings = struct
  type item =
    | Section of string
    | Toggle of { key:string; title:string; description:string option; initial:bool }
    | Text of { key:string; title:string; description:string option; initial:string;
                multiline:bool; validate:(string -> (unit,string) result) option }
    | Integer of { key:string; title:string; description:string option; initial:int64;
                   validate:(int64 -> (unit,string) result) option }
    | Choice of { key:string; title:string; description:string option; initial:string;
                  options:(string * string) list }
    | Action of { title:string; description:string option; run:Activity.t -> unit response }
  type schema = {store:string; items:item list; lock:Lwt_mutex.t}
  type page = {root:Ui.view; subscription:Subscription.t; schema:schema; mutable active:bool}
  let key = function Toggle x -> Some x.key | Text x -> Some x.key | Integer x -> Some x.key | Choice x -> Some x.key | _ -> None
  let initial = function Toggle x -> Storage.Preferences.Bool x.initial | Text x -> Text x.initial
    | Integer x -> Int x.initial | Choice x -> Text x.initial | _ -> assert false
  let check item value =
    let invalid message = Error (Error.Invalid_argument message) in
    let validate f v = match f with None -> Ok () | Some f -> Result.map_error (fun m -> Error.Invalid_argument m) (f v) in
    try match item,value with
    | Toggle _,Storage.Preferences.Bool _ -> Ok ()
    | Text x,Storage.Preferences.Text v -> validate x.validate v
    | Integer x,Storage.Preferences.Int v -> validate x.validate v
    | Choice x,Storage.Preferences.Text v when List.mem_assoc v x.options -> Ok ()
    | _ -> invalid "Settings value does not match its declaration"
    with exn -> Error (Error.Platform (Printexc.to_string exn))
  let define ~store items =
    let seen=Hashtbl.create (List.length items) in
    let rec loop = function
      | [] -> Ok {store;items;lock=Lwt_mutex.create ()}
      | item::rest -> match key item with None -> loop rest | Some k ->
        if k="" || Hashtbl.mem seen k then Error (Error.Invalid_argument "Settings keys must be nonempty and unique") else
        let options_valid = match item with Choice x ->
          x.options<>[] && List.length (List.sort_uniq String.compare (List.map fst x.options))=List.length x.options
          | _ -> true in
        if not options_valid then Error (Error.Invalid_argument "Settings choices must be nonempty and unique") else
        match check item (initial item) with Error e -> Error e | Ok () -> Hashtbl.add seen k ();loop rest in
    if store="" then Error (Error.Invalid_argument "Settings store must be nonempty") else loop items
  let find schema k = match List.find_opt (fun i -> key i=Some k) schema.items with
    | Some i -> Ok i | None -> Error (Error.Invalid_argument "Unknown settings key")
  let get_unlocked schema ~key:k = match find schema k with Error e -> Lwt.return (Error e) | Ok item ->
    Storage.Preferences.get ~store:schema.store ~key:k >|= function
    | Error e -> Error e
    | Ok value -> let value=Option.value value ~default:(initial item) in Result.map (fun () -> value) (check item value)
  let get schema ~key = Lwt_mutex.with_lock schema.lock (fun () -> get_unlocked schema ~key)
  let set_unlocked schema ~key:k value = match find schema k with Error e -> Lwt.return (Error e) | Ok item ->
    match check item value with Error e -> Lwt.return (Error e) | Ok () -> Storage.Preferences.set ~store:schema.store ~key:k value
  let set schema ~key value = Lwt_mutex.with_lock schema.lock (fun () -> set_unlocked schema ~key value)
  let value_string = function Storage.Preferences.Text v -> v | Bool v -> string_of_bool v | Int v -> i64 v
  let rows schema =
    let rec loop acc = function [] -> Lwt.return (Ok (`List (List.rev acc))) | item::rest ->
      let open Yojson.Safe in
      let description x = "description",(match x with None -> `Null | Some s -> `String s) in
      let fields kind title desc = ["kind",`String kind;"title",`String title;description desc] in
      let fields = match item with
        | Section title -> fields "section" title None
        | Toggle x -> fields "toggle" x.title x.description
        | Text x -> ("multiline",`Bool x.multiline)::fields "text" x.title x.description
        | Integer x -> fields "integer" x.title x.description
        | Choice x -> ("options",`List (List.map (fun (v,t) -> `List [`String v;`String t]) x.options))::fields "choice" x.title x.description
        | Action x -> fields "action" x.title x.description in
      (match key item with None -> Lwt.return (Ok fields) | Some k -> get_unlocked schema ~key:k >|= Result.map (fun v -> ("value",`String (value_string v))::fields)) >>= function
      | Error e -> Lwt.return (Error e) | Ok fields -> loop (`Assoc fields::acc) rest in
    loop [] schema.items
  let view page = page.root
  let refresh page = if not page.active then invalid "Settings page released" else
    Lwt_mutex.with_lock page.schema.lock (fun () -> rows page.schema >>= function
      | Error e -> Lwt.return (Error e) | Ok json -> unit_call "settings.update" [page.root;Yojson.Safe.to_string json])
  let error_message = function Error.Unavailable s | Permission_denied s | Invalid_argument s | Invalid_handle s | Platform s | Protocol s -> s
  let create activity schema ~on_change ~on_error =
    let page_ref=ref None in
    let callback args = match !page_ref,args with
      | Some page,[|"closed";""|] -> page.active<-false;Lwt.return_unit
      | Some page,[|index;proposed|] when page.active ->
        (match int_of_string_opt index with
        | Some n when n>=0 && n<List.length schema.items ->
          let item=List.nth schema.items n in
          (match item with
          | Action x -> Lwt.catch (fun () -> x.run activity) (fun exn -> Lwt.return (Error (Error.Platform (Printexc.to_string exn)))) >>= (function Ok () -> Lwt.return_unit | Error e -> on_error e) >>= fun () ->
            (if page.active then unit_call "settings.result" [page.root;index;"true";""] >|= ignore else Lwt.return_unit) >>= fun () ->
            refresh page >>= (function Ok () -> Lwt.return_unit | Error e -> on_error e)
          | Section _ -> Lwt.return_unit
          | _ ->
            let k=Option.get (key item) in
            let value = match item with
              | Toggle _ -> if proposed="true" then Some (Storage.Preferences.Bool true) else if proposed="false" then Some (Bool false) else None
              | Integer _ -> Option.map (fun v -> Storage.Preferences.Int v) (Int64.of_string_opt proposed)
              | _ -> Some (Storage.Preferences.Text proposed) in
            Lwt_mutex.with_lock schema.lock (fun () ->
              (match value with None -> invalid "Expected a 64-bit integer" | Some v -> set_unlocked schema ~key:k v) >>= fun result ->
              if not page.active then Lwt.return result else
              unit_call "settings.result" [page.root;index;(match result with Ok () -> "true" | _ -> "false");
                (match result with Ok () -> proposed | Error e -> error_message e)] >>= function
              | Error e -> Lwt.return (Error e) | Ok () -> Lwt.return result) >>= function
            | Error (Error.Invalid_argument _) -> Lwt.return_unit
            | Error e -> on_error e
            | Ok () -> if page.active then on_change ~key:k (Option.get value) else Lwt.return_unit)
        | _ -> on_error (Error.Protocol "Invalid settings event"))
      | _ -> Lwt.return_unit in
    Lwt.no_cancel (Lwt_mutex.with_lock schema.lock (fun () -> rows schema >>= function
      | Error e -> Lwt.return (Error e)
      | Ok json ->
        let subscription=Subscription.create callback in
        one "settings.create" [subscription.id;activity;Yojson.Safe.to_string json] >>= function
        | Error e -> Subscription.cancel subscription >|= fun _ -> Error e
        | Ok root -> let page={root;subscription;schema;active=true} in page_ref:=Some page;Lwt.return (Ok page)))
  let release page =
    if not page.active then Lwt.return (Ok ()) else (
      page.active<-false;
      Lwt.no_cancel (Subscription.cancel page.subscription >>= fun result -> Ui.release page.root >|= fun released ->
      match result,released with Error e,_ -> Error e | _,Error (Error.Invalid_handle _) -> Ok () | _,r -> r))
end
