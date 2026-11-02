open Android
let toggle key = Settings.Toggle {key;title=key;description=None;initial=false}
let invalid = function Error (Error.Invalid_argument _) -> () | _ -> failwith "Expected settings argument error"
let () =
  Printexc.record_backtrace true;
  invalid (Settings.define ~store:"" []);
  invalid (Settings.define ~store:"prefs" [toggle ""]);
  invalid (Settings.define ~store:"prefs" [toggle "a";toggle "a"]);
  invalid (Settings.define ~store:"prefs" [Settings.Choice {key="a";title="A";description=None;initial="x";options=[]}]);
  invalid (Settings.define ~store:"prefs" [Settings.Choice {key="a";title="A";description=None;initial="x";options=["x","X";"x","Other"]}]);
  invalid (Settings.define ~store:"prefs" [Settings.Integer {key="a";title="A";description=None;initial=0L;validate=Some (fun n -> if n>0L then Ok () else Error "Positive required")}]);
  invalid (Settings.define ~store:"prefs" [Settings.Choice {key="a";title="A";description=None;initial="missing";options=["x","X"]}]);
  invalid (Settings.define ~store:"prefs" [Settings.Text {key="a";title="A";description=None;initial="";multiline=false;validate=Some (fun _ -> Error "Required")}]);
  let schema = match Settings.define ~store:"prefs" [toggle "a"] with Ok s -> s | Error _ -> failwith "valid schema" in
  Lwt_main.run (Lwt.bind (Settings.set schema ~key:"a" (Storage.Preferences.Text "wrong")) (fun result -> invalid result; Lwt.return_unit))
let () =
  let schema=match Settings.define ~store:"registration" [] with Ok s -> s | Error _ -> assert false in
  let on_change ~key:_ _ = Lwt.return_unit and on_error _ = Lwt.return_unit in
  invalid (Settings.register ~name:"" schema ~on_change ~on_error);
  assert (Settings.register ~name:"test-settings" schema ~on_change ~on_error=Ok ());
  invalid (Settings.register ~name:"test-settings" schema ~on_change ~on_error)
