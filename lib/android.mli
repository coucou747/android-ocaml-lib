(** Android bindings. All calls are made from the library's Lwt runtime thread.
    Binary strings cross JNI as byte arrays; text is UTF-8. *)
module Error : sig
  type t = Unavailable of string | Permission_denied of string
    | Invalid_argument of string | Invalid_handle of string
    | Platform of string | Protocol of string
end
type 'a response = ('a, Error.t) result Lwt.t
module Subscription : sig
  type t
  val cancel : t -> unit response
end
module Activity : sig
  type t
  type event = Created | Started | Resumed | Paused | Stopped | Destroyed
  val current : unit -> t response
  val set_keep_screen_on : t -> bool -> unit response
  val on_lifecycle : (t -> event -> unit Lwt.t) -> Subscription.t response
end
module Ui : sig
  type view
  type orientation = Horizontal | Vertical
  val text : Activity.t -> string -> view response
  val button : Activity.t -> string -> view response
  val edit_text : Activity.t -> string -> view response
  val linear_layout : Activity.t -> orientation -> view response
  val add : view -> view -> unit response
  val show : Activity.t -> view -> unit response
  val set_text : view -> string -> unit response
  val get_text : view -> string response
  val set_enabled : view -> bool -> unit response
  val release : view -> unit response
end
module Events : sig
  type touch = { action : int; x : float; y : float }
  val on_click : Ui.view -> (unit -> unit Lwt.t) -> Subscription.t response
  val on_text_changed : Ui.view -> (string -> unit Lwt.t) -> Subscription.t response
  val on_touch : Ui.view -> consume:bool -> (touch -> unit Lwt.t) -> Subscription.t response
end
module Permissions : sig
  type special = Exact_alarm | Notifications | Full_screen_intent
  val check : string -> bool response
  val request : Activity.t -> string list -> string list response
  val check_special : special -> bool response
  val open_settings : Activity.t -> special -> unit response
end
module Alarm : sig
  type mode = Exact | Alarm_clock
  val can_schedule_exact : unit -> bool response
  val schedule : id:string -> at_ms:int64 -> mode:mode -> handler:string -> payload:string -> unit response
  val cancel : id:string -> unit response
end
module Notification : sig
  type importance = Low | Default | High
  val create_channel : id:string -> name:string -> importance:importance -> unit response
  val show : id:int -> channel:string -> title:string -> text:string -> small_icon:int -> handler:string -> payload:string -> unit response
  val cancel : id:int -> unit response
end
module Audio : sig
  type player
  val play : uri:string -> loop:bool -> player response
  val stop : player -> unit response
end
module Vibrator : sig
  val vibrate : duration_ms:int -> unit response
  val cancel : unit -> unit response
end
module Background : sig
  type outcome = Success | Failure | Retry
  type constraints = { network : bool; charging : bool }
  val enqueue : name:string -> handler:string -> payload:string -> constraints:constraints -> interval_ms:int64 option -> unit response
  val cancel : name:string -> unit response
  val schedule_job : id:int -> handler:string -> payload:string -> constraints:constraints -> unit response
  val cancel_job : id:int -> unit response
  val start_service : handler:string -> payload:string -> notification_id:int -> channel:string -> title:string -> small_icon:int -> unit response
  val stop_service : unit -> unit response
end
module Network : sig
  type state = { connected : bool; metered : bool }
  val state : unit -> state response
  val on_change : (state -> unit Lwt.t) -> Subscription.t response
  module Http : sig
    type response = { status : int; headers : (string * string) list; body : string }
    val request : url:string -> meth:string -> headers:(string * string) list -> body:string -> connect_timeout_ms:int -> read_timeout_ms:int -> (response, Error.t) result Lwt.t
  end
end
module Storage : sig
  val read_file : name:string -> string response
  val write_file : name:string -> contents:string -> unit response
  val remove_file : name:string -> unit response
  module Preferences : sig
    type value = Text of string | Bool of bool | Int of int64
    val set : store:string -> key:string -> value -> unit response
    val get : store:string -> key:string -> value option response
    val remove : store:string -> key:string -> unit response
  end
  module Keystore : sig
    val create : alias:string -> unit response
    (** Ciphertexts contain their IV and are encoded using Android Base64. *)
    val encrypt : alias:string -> plaintext:string -> string response
    val decrypt : alias:string -> ciphertext:string -> string response
    val delete : alias:string -> unit response
  end
  module Sqlite : sig
    type t
    type value = Null | Integer of int64 | Float of float | Text of string | Blob of string
    type statement = { sql : string; params : value list }
    val open_db : name:string -> t response
    val close : t -> unit response
    val execute : t -> statement -> unit response
    (** Execute a batch atomically; rollback on any error. *)
    val transaction : t -> statement list -> unit response
    val query : t -> statement -> value list list response
  end
end
module Broadcast : sig
  type event = { action : string; payload : string }
  val listen : actions:string list -> (event -> unit Lwt.t) -> Subscription.t response
end
module Runtime : sig
  type handler = string -> Background.outcome Lwt.t
  val register : name:string -> handler -> (unit, Error.t) result
  val set_entry : (unit -> unit Lwt.t) -> unit
  val set_error_handler : (exn -> unit) -> unit
end
