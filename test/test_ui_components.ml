module D=Android.Dialog
let () =
  let invalid = function Error (Android.Error.Invalid_argument _) -> () | _ -> failwith "Expected invalid button configuration" in
  invalid (D.buttons [{role=Positive;label=""}]);
  invalid (D.buttons [{role=Positive;label="A"};{role=Positive;label="B"}]);
  assert (Result.is_ok (D.buttons []));
  assert (Result.is_ok (D.buttons [{role=Positive;label="Enregistrer"};{role=Negative;label="Annuler"};{role=Neutral;label="Aide"}]))
