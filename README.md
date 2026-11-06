# android-ocaml-lib

Bibliothèque OCaml native pour Android API 26+, ARM64. Les signatures publiques
sont dans [`lib/android.mli`](lib/android.mli). Les composants Java génériques
sont distribués en AAR ; le runtime OCaml et le code de chaque application sont
liés ensemble dans une bibliothèque native propre à cette application.

Les bindings sont implémentés pour toutes les familles de `lib.md`, avec HTTP(S)
et SQLite. La bibliothèque et son pont JNI compilent pour Android ARM64 ; l’AAR compile
également contre le SDK API 36. L’APK technique est construit et sa signature
et son alignement 16 Kio sont vérifiés. La recette sur appareil reste à effectuer. Consulter
[`notes/VALIDATION.md`](notes/VALIDATION.md) avant toute utilisation en production.

## Organisation

- `lib/` : API OCaml/Lwt, erreurs structurées et transport C/JNI.
- `android/library/` : widgets, services système, receivers et workers Java.
- `test/` : tests OCaml, test JNI sur JVM hôte et application technique Android.
- `config.properties` : versions et identifiants validés pour la bibliothèque.
- `notes/DEPENDENCIES.md` : choix de dépendances et alternatives.

Ce dépôt ne fournit ni serveur Dream, ni modèle de données métier, ni ordonnanceur
de récurrences, ni persistance automatique des alarmes.

## Vérification sur l'hôte

Utiliser le switch opam existant `android` (OCaml 5.4.1). Installer les dépendances
avec `opam install --switch=android ./android-ocaml-lib.opam --deps-only`, puis :

```sh
export OPAMSWITCH=android
OCAMLRUNPARAM=b opam exec -- dune build --profile=dev
OCAMLRUNPARAM=b opam exec -- dune build
OCAMLRUNPARAM=b opam exec -- dune runtest
# Argument obligatoire : délai maximal du test, en secondes.
opam exec -- scripts/check-jni.sh 30
```

Le test JNI nécessite Linux, un JDK et un compilateur C. Il utilise un adaptateur
Java de test, pas les services Android ; il vérifie réellement le runtime embarqué,
les appels JNI depuis les threads Lwt, les callbacks, le GC et les données binaires.
Sur l'hôte, les fonctions publiques utilisant Android renvoient `Unavailable`.

## Configuration Android

Installer/configurer un SDK avec la plateforme API 36, le NDK r29
`29.0.14206865`, un JDK 21 et le switch `android` équipé de
[opam-cross-android](https://github.com/ocaml-cross/opam-cross-android).
Installer les paquets croisés `ocaml-android`, `lwt-android` et `yojson-android`
dans ce même switch, en complément des dépendances hôte. Le nom du switch
n’implique pas que ces paquets y soient déjà installés. Lors de l’installation de
`lwt-android`, utiliser `LWT_DISCOVER_ARGUMENTS='--use-libev false'` si aucune libev
croisée n’est installée : cela sélectionne le moteur `select` fourni par Lwt et
évite la détection accidentelle de la libev Linux de l’hôte. Ne pas confondre les deux jeux
de bibliothèques. Configurer explicitement `ANDROID_API=26` lors de l'installation
de la toolchain.

Créer `android/local.properties` avec `sdk.dir` et `ndk.dir` pointant sur les
installations choisies, et définir `ANDROID_NDK_ROOT` avec ce même NDK pour opam.
Aucun chemin de machine n'est fourni par défaut. Le script n'installe pas le SDK
et n'accepte aucune licence à votre place.

```sh
export OPAMSWITCH=android
scripts/build-android.sh
# Argument obligatoire : targetSdk explicitement choisi pour l'application de test.
scripts/build-integration.sh <targetSdk>
```

Les sorties Gradle, les caches et les préférences Android utilisées par les scripts
sont dirigés sous `_build/`. L'AAR est produit dans
`_build/gradle/library/outputs/aar/`. L'APK technique est produit dans
`_build/gradle/integration/outputs/apk/`.

Pour publier l'AAR, fournir explicitement `-PlibraryVersion=<version>` à Gradle et
configurer le dépôt Maven de destination. Utiliser la publication Maven avec son
POM : copier seulement l'AAR ne transporte pas les dépendances AndroidX.

## Intégration dans une application

1. Dépendre du paquet OCaml et de la publication Maven de l'AAR correspondant.
2. Lier le code OCaml applicatif et `android-ocaml-lib` en mode Dune
   `(native shared_object)`, dans le contexte Android. Copier le résultat sous
   `jniLibs/arm64-v8a/lib<nom>.so` dans le projet client.
3. Déclarer dans `<application>` la métadonnée `android.ocaml.library`, avec le
   nom de cette bibliothèque sans préfixe `lib` ni suffixe `.so`.
4. Utiliser `org.ocaml.android.BridgeActivity` ou une sous-classe qui appelle les
   méthodes `super` du cycle de vie. Pour exposer directement `BridgeActivity` comme
   activité de lancement, ajouter `tools:replace="android:exported"` avec
   `android:exported="true"` et déclarer l’espace de noms XML `tools` ; le manifeste
   de `test/android` fournit cet exemple. La `NativeActivity` du document initial
   n'est pas le point d'entrée de cette intégration AAR.
5. Déclarer les permissions requises par les fonctionnalités réellement utilisées.
   Le manifeste de la bibliothèque ne demande aucune permission automatiquement.

Enregistrer les callbacks au chargement du module applicatif :

```ocaml
let () =
  ignore (Android.Runtime.register ~name:"sync" (fun payload ->
    (* Effectuer le travail, puis renvoyer Success, Failure ou Retry. *)
    ignore payload;
    Lwt.return Android.Background.Success));
  Android.Runtime.set_entry (fun () ->
    (* Abonnements et initialisation applicative ; ne pas bloquer la boucle Lwt. *)
    Lwt.return_unit)
```

Ne pas lancer une seconde boucle `Lwt_main.run`. Les callbacks OCaml sont exécutés
sur le thread du runtime, les widgets sur le thread principal Android, et les
opérations bloquantes dans le pool `Lwt_preemptive`. Un callback doit suspendre via
Lwt pour les attentes, et ne doit pas effectuer de calcul long bloquant la boucle.

## Contrats et durées de vie

Les textes sont UTF-8. Les corps HTTP, fichiers, BLOB SQLite et textes clairs du
Keystore sont des chaînes OCaml binaires, préservées par JNI. Les payloads de tâches
et d'événements persistants sont des textes UTF-8. WorkManager impose sa limite de
volume de données et sa période minimale ; une valeur non conforme est refusée.
Les identifiants et gestionnaires persistants doivent rester stables entre versions.

Les erreurs attendues sont des `Result`. Les exceptions des callbacks sont transmises
à `Runtime.set_error_handler` (trace complète sur stderr par défaut). HTTP rend les
statuts d'erreur HTTP dans la réponse ; les erreurs réseau sont des erreurs plateforme.
Les redirections ne sont pas suivies automatiquement. Les délais réseau doivent être
fournis explicitement ; la validation TLS reste celle d'Android. Les réponses sont
chargées entièrement en mémoire, sans API de streaming dans cette version.

Annuler la promesse HTTP ferme la connexion, sans garantir qu'un effet distant déjà
envoyé soit annulé. Les autres appels plateforme sont protégés de l'annulation Lwt :
utiliser les opérations explicites `cancel`, `close`, `stop` et `Subscription.cancel`.
L'arrêt d'un worker/job/service annule sa promesse de gestionnaire OCaml ; ce dernier
doit libérer ses ressources via `Lwt.finalize`.

Les vues appartiennent à leur activité et deviennent invalides après destruction.
Libérer les vues retirées de l'arbre et annuler les abonnements inutiles. Les
abonnements liés aux vues sont nettoyés à la destruction de l'activité. L'abonnement
au cycle de vie rejoue l'état courant pour ne pas manquer le démarrage du runtime.
Un seul abonnement clic et un seul abonnement toucher sont actifs par vue ; annuler
l'abonnement existant avant d'en installer un autre.
Fermer les bases SQLite et arrêter les lecteurs audio explicitement. Les transactions
SQLite sont des lots atomiques, sans callback réentrant au milieu d'une transaction.
Les schémas et migrations sont de la responsabilité de l'application.

## Événements système et arrière-plan

Les alarmes utilisent des instants Unix en millisecondes et des `PendingIntent`
identifiés sans hash par une URI stable. `cancel` utilise le même identifiant.
L'application persiste ses alarmes et les reprogramme après redémarrage.

Pour recevoir un événement système depuis un processus arrêté, ajouter son
`intent-filter` au receiver `org.ocaml.android.BridgeReceiver` et associer une
métadonnée applicative `android.ocaml.broadcast.<action Android>` au nom du
handler OCaml enregistré. Déclarer les permissions nécessaires, par exemple
`RECEIVE_BOOT_COMPLETED`. Le payload du handler sera alors l'action Android.
`Broadcast.listen` est un abonnement dynamique lié au processus ; son payload
est l'URI de l'intent, sans sérialisation arbitraire des extras.

Un receiver doit terminer rapidement, dans le délai imposé par Android ; déléguer
les traitements longs à WorkManager. Les handlers doivent être enregistrés avant
la fin de l'initialisation, même lorsque le processus démarre sans activité.

Les notifications utilisent une ressource `small_icon` fournie par l'application et
un canal créé explicitement. L'action déclenche un receiver : ouvrir une activité
depuis un receiver de notification est soumis aux restrictions Android.
Pour un service de premier plan, le client doit déclarer son type de service et
ses permissions dans le manifeste fusionné. Le service reste actif pendant sa
promesse de handler et se termine à sa résolution. Les restrictions de démarrage
en arrière-plan continuent de s'appliquer. `start_service` confirme la demande de
démarrage ; les erreurs survenant ensuite dans le cycle de vie du service sont
journalisées dans Logcat.

## Capture photo

`Android.Camera.capture activity ~name` ouvre l’application appareil photo installée.
Le nom fourni doit être un nouveau basename JPEG (`.jpg` ou `.jpeg`), sans chemin.
Un fichier existant est refusé. Le résultat est `Ok (Captured photo)` ou `Ok Cancelled`
si l’utilisateur revient sans photo ; l’absence d’application caméra donne `Unavailable`.

`Camera.read photo` lit les octets de la photo et `Camera.delete photo` la supprime.
`Camera.of_uri uri` restaure une photo depuis une URI conservée, après vérification
de son appartenance à l’application et de l’existence du fichier.
`Camera.uri photo` donne son URI `content://`, utilisable avec une autorisation URI
explicite pour le partage. Il s’agit d’un fichier privé, pas d’une insertion automatique
dans la galerie. Le stockage dérive de `Context.getFilesDir()` ; le sous-répertoire
interne `ocaml_photos` est le seul exposé par le fournisseur dédié, non exporté.
Les permissions temporaires de l’application caméra sont révoquées à la fin.

La capture produit un fichier pleine résolution via `EXTRA_OUTPUT`, pas la miniature
renvoyée dans les extras. La rotation conserve la demande grâce à l’activité de capture
dédiée. La mort du processus ne conserve pas la continuation OCaml ; les photos privées
éventuellement laissées sur disque doivent être gérées par l’application. La capture
reste protégée de l’annulation Lwt ; l’utilisateur l’annule dans l’application caméra.
Les métadonnées EXIF et l’orientation restent celles de l’appareil photo.

Cette délégation ne demande pas elle-même `CAMERA`. Si le client déclare cette permission,
il doit obtenir l’autorisation avant la capture. Aucun accès général aux fichiers ou
à la galerie n’est demandé par la bibliothèque.

## Scanner un QR code

```ocaml
Android.Qr_scanner.scan activity  (* string Android.response *)
```

Le scanner affiche l’écran de Google Code Scanner, limité au format QR, et renvoie
le texte brut. L’application n’a aucun Java à écrire ni permission `CAMERA` à
déclarer pour ce scanner. Le contenu n’est ni ouvert comme URL, ni interprété,
ni enregistré par la bibliothèque.

Les services Google Play doivent être disponibles et à jour. Leur module de scan
peut nécessiter un téléchargement avant la première utilisation ; le manifeste
de l’AAR demande sa préparation lors de l’installation via Google Play. Sur un
appareil sans ces services ou si le module manque, le résultat est
`Error (Android.Error.Unavailable _)`. Il n’y a pas de scanner alternatif implicite.
Voir la [documentation Google Code Scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner).

Retour depuis le scanner donne `Error Android.Error.Cancelled` ; un accès caméra
refusé au fournisseur donne `Permission_denied`. Un QR sans représentation texte
donne `Unavailable`, tandis qu’un texte vide reste `Ok ""`. Appeler depuis une
activité visible, avec un seul scan en cours. La destruction de cette activité
termine l’attente avec `Invalid_handle`, y compris lors d’une recréation.
Le SDK ne propose pas de fermeture programmatique : `Lwt.cancel` est ignoré pour
cette opération ; l’utilisateur peut revenir en arrière pour l’annuler.
La continuation OCaml ne survit pas à la mort du processus.

La démonstration contient un bouton « Scanner un QR code » qui affiche seulement
la taille du résultat, pour éviter d’exposer son contenu à l’écran.

## Écran d’alarme

`Alarm.Screen.presentation` configure le contenu, le média, la disposition et une
à trois actions. Exemple pour une application de tâches :

```ocaml
module A = Android.Alarm.Screen
let task_alarm ~task_id ~notification_id ~title ~message ~photo_uri ~allow_snooze : A.presentation = {
  id=task_id; notification_id; title; subtitle=None; message;
  media=Option.map (fun uri -> {A.uri; content_description="Photo de la tâche";
                               max_height_dp=Some 240}) photo_uri;
  layout=Standard;
  actions={id="begin"; label="Commencer"; role=Primary;
           handler="tasks.begin"; payload=task_id} ::
    (if allow_snooze then [{id="remind"; label="Rappeler dans 1 h"; role=Secondary;
                           handler="tasks.remind_one_hour"; payload=task_id}] else []);
}
```

Les handlers `tasks.begin` et `tasks.remind_one_hour` appartiennent à l’application
et sont enregistrés à chaque démarrage avec `Runtime.register`. Le second handler
implémente lui-même la politique de report choisie par l’application.

- `Alarm.Screen.validate presentation` vérifie le modèle sans appeler Android.
- `show_presentation activity presentation` ouvre l’écran et valide le modèle.
- `notify_presentation ~channel ~small_icon ~full_screen presentation` publie ses
  actions dans une notification, avec les mêmes exigences d’autorisation décrites plus bas.

Les identifiants et noms de handlers doivent être non vides, sans caractères de
contrôle ; les identifiants d’action sont uniques. Les libellés ne sont pas vides.
L’identifiant de notification est un entier Android signé de 32 bits, zéro compris.
Chaque action possède son propre payload texte UTF-8. Un modèle invalide retourne
`Error (Invalid_argument ...)` avant tout appel Android.

L’activité reprend le thème Material déclaré de l’activité appelante, ou celui de
l’application/activité principale pour une notification à froid. Sans thème
Material, elle utilise Material 3 DayNight. Les couleurs, polices et modes clair/
sombre viennent du thème ; aucun choix de couleur applicatif n’est demandé.
`Primary` est rempli, `Secondary` détouré, `Neutral` textuel, `Destructive` utilise
les couleurs d’erreur du thème. Les notifications conservent le rendu du système.

`Compact` utilise des espacements réduits et des actions horizontales ; `Standard`
et `Expanded` utilisent des actions verticales, une typographie et des espacements
adaptés. Expanded centre le contenu court dans la hauteur disponible. Le contenu
défile si nécessaire. Les insets des barres et découpes sont appliqués aux quatre
côtés, avec cibles tactiles d’au moins 48 dp. Les dimensions `ocaml_alarm_*` sont
des ressources de présentation surchargeables par l’application.

Le média est un aperçu d’image dans l’écran, chargé hors du thread UI, borné à la
fenêtre et à `max_height_dp` lorsqu’il est fourni. L’orientation EXIF est respectée
et `content_description` fournit le texte alternatif. Les URI `content://`,
`file://` et `android.resource://` sont acceptées ; un fichier absent ou illisible
laisse le texte et les actions accessibles. L’application doit conserver le fichier
et les droits de lecture nécessaires après redémarrage. Aucun téléchargement réseau
ni transfert de droit URI temporaire n’est effectué. La notification affiche le
titre, le sous-titre, le texte et les actions ; elle n’embarque pas l’aperçu d’image.

Une présentation active identique partage le même jeton entre écran et notification.
La première action consomme ce jeton dans le stockage privé avant d’appeler son
handler ; les autres clics, les anciennes notifications remplacées et les clics
après `dismiss` ne déclenchent plus de handler. Cette protection survit au redémarrage
du processus et ne conserve que les présentations actives, sans journal croissant
des alarmes déjà traitées. Présenter à nouveau une alarme traitée ou fermée avec
`dismiss` la réarme explicitement ; changer son contenu remplace sa version active.

Le traitement métier n’est pas une transaction durable : si Android tue le
processus entre la consommation du jeton et la fin du handler, il peut rester
incomplet et n’est pas rejoué automatiquement. Les handlers doivent être idempotents
et relire leurs données persistées. Les intents contiennent les noms et payloads,
sans fermeture OCaml en mémoire. Garder les gros contenus dans des fichiers pour
respecter la limite Binder des intents.

L’ancienne API reste compatible et constitue un wrapper vers le modèle :
`Android.Alarm.Screen.t` contient l’identifiant d’alarme, l’identifiant de notification,
le titre, le texte, les deux libellés, les noms de handlers `stop_handler` et
`snooze_handler`, ainsi que leur payload. Tous sont fournis par l’application.
Les handlers sont enregistrés avec `Runtime.register`, y compris au démarrage sans UI.

- `Alarm.Screen.show activity screen` ouvre immédiatement l’écran depuis une activité.
- `Alarm.Screen.notify ~channel ~small_icon ~full_screen screen` publie une notification
  d’alarme avec ouverture directe de l’activité et actions Arrêter/Reporter.
- `Alarm.Screen.dismiss ~id ~notification_id` ferme l’écran et annule sa notification.

Le canal doit exister avec importance `High`. Le client déclare et demande
`POST_NOTIFICATIONS` quand nécessaire. Pour `full_screen:true`, il déclare
`USE_FULL_SCREEN_INTENT` et vérifie `Permissions.check_special Full_screen_intent`.
Un refus est retourné explicitement ; pour demander volontairement une simple notification,
utiliser `full_screen:false`. Android peut afficher une notification prioritaire au lieu
d’ouvrir l’activité lorsque le téléphone est utilisé, même si l’autorisation est accordée.

L’activité affiche au-dessus du verrouillage et allume l’écran, sans déverrouiller le
terminal. Les boutons ferment l’écran, retirent la notification et transmettent le
payload au handler correspondant. Arrêter le son et reprogrammer un report relèvent
respectivement des API `Audio` et `Alarm` dans ces handlers ; aucune durée n’est inventée.
Les échecs asynchrones des handlers sont journalisés dans Logcat. L’action effectuée
n’est pas une transaction durable : l’application doit rendre ses traitements idempotents.

Le wrapper choisit Standard, sans média ni sous-titre, avec `stop` Primary et
`snooze` Secondary et le payload partagé de l’ancien format.

L’application technique contient les boutons « Prendre une photo », « Écran
d’alarme » et « Notification d’alarme ». Elle utilise la photo capturée comme média
facultatif et les actions Commencer/Reporter. Son bouton Reporter ne programme rien : il vérifie la livraison
au handler OCaml, tandis que la politique de report appartient à chaque client.

## Aperçus, défilement et champs texte

`Ui.image activity ~uri ~max_width_px ~max_height_px` crée un aperçu local.
`Ui.set_image_uri` remplace celui d’une vue existante. Les URI acceptées sont
`content://`, `file://` et `android.resource://`, avec les droits d’accès du client.
Le décodage s’exécute hors thread graphique ; l’affichage conserve les proportions
et tient dans les limites explicites, sans agrandir le bitmap source au décodage. L’orientation
EXIF est appliquée. Sur API 26/27, une image sans EXIF exploitable utilise son
orientation de pixels. Une erreur de remplacement conserve l’image précédente.

Pour une photo capturée :

```ocaml
Android.Activity.display_size activity >>= fun result ->
match result with
| Error e -> Lwt.return (Error e)
| Ok size -> Android.Ui.image activity ~uri:(Android.Camera.uri photo)
    ~max_width_px:size.width_px ~max_height_px:size.height_px
```

Les dimensions sont des pixels physiques. Elles peuvent aussi être choisies selon
le budget de présentation propre à l’application. Les aperçus ne sont pas mis en
cache par la bibliothèque ; libérer les vues retirées via `Ui.release`.

`Ui.scroll_view activity` crée un conteneur vertical. Ajouter un seul `LinearLayout`
comme enfant direct, puis les tâches dans ce layout avec `Ui.add`. Un second enfant
direct est refusé. Les lignes sont toutes créées : ce n’est pas une liste virtualisée.

Pour un `EditText`, `Ui.set_input_type` accepte `Text` ou
`Number { signed; decimal }`. Le mode numérique est sur une seule ligne ; le mode
texte préserve son réglage multiligne. `Ui.set_multiline field true` active plusieurs
lignes pour le texte et refuse le mode numérique. `Ui.set_hint field "…"` définit
l’indication. `Ui.set_error field (Some "…")` affiche une erreur et `None` l’efface.
Ces réglages de clavier ne remplacent pas la validation métier des valeurs saisies.
Les dialogues et les autres propriétés de présentation ne sont pas ajoutés ici.

## Ressources, son d’alarme et dates locales

`Resources.resolve ~scope ~kind ~name` renvoie un identifiant Android non nul.
Choisir `Application` pour les ressources du client ou `Android` pour celles du
système, et `Drawable`, `Mipmap`, `Raw`, `String` ou `Color` pour le type.
Le nom est non qualifié, par exemple `ic_lock_idle_alarm` dans le scope `Android`.
Une ressource manquante donne `Error.Unavailable`, jamais l’identifiant zéro.
Un client qui réduit ses ressources à la compilation doit conserver explicitement
les noms résolus dynamiquement (règles `tools:keep` du resource shrinker).

`Android.Resources.get_string ~name:"task_title"` lit une ressource `string` de
l’application selon la langue active, sans nécessiter d’activité. Chaque appel
utilise la configuration courante, y compris les langues par application prises
en charge par AndroidX Core, avec le repli normal d’Android vers les ressources
par défaut. Le nom est non qualifié, sans `@string/` ni préfixe de package.
Une ressource absente donne `Error.Unavailable`, un nom vide/qualifié ou contenant
NUL donne `Error.Invalid_argument` ; une chaîne vide reste `Ok ""`.
La lecture retourne du texte UTF-8 sans mise en forme des paramètres `%1$s`.
Les noms utilisés ici doivent aussi être conservés par le resource shrinker.

`Audio.default_alarm_uri ()` renvoie `Some uri` pour la sonnerie d’alarme configurée,
ou `None` si aucune n’est sélectionnée. La bibliothèque ne substitue aucun autre son.
L’URI peut être passée à `Audio.play ~uri ~loop:true`, sous réserve des droits système.

`Locale.time_zone ()`, `Locale.language_tag ()` et `Locale.uses_24_hour_clock ()`
consultent les réglages courants du téléphone et de l’application.
`Locale.format_date`, `Locale.format_time` et `Locale.format_date_time` prennent
`~at_ms` en millisecondes Unix (`int64`) et produisent les textes locaux. Le fuseau,
la langue et le choix 12/24 heures sont relus à chaque appel ; aucun format de date
ni fuseau d’application n’est fixé. Les dates avant 1970 sont acceptées. Ces API
servent à l’affichage ; les règles de récurrence et leurs politiques DST restent
la responsabilité de l’application.

## Ouvrir l’activité principale après une alarme démarrée à froid

`Android.Activity.launch_main ()` demande l’ouverture du point d’entrée déclaré dans
le manifeste de l’application, sans appeler `Activity.current` ni disposer d’une
poignée d’activité. Le contexte applicatif initialisé par le receiver, le worker ou
l’écran d’alarme suffit. Si aucune activité d’entrée n’est déclarée, la réponse est
`Error (Unavailable ...)`. Les erreurs Android levées sont des erreurs structurées.

```ocaml
let () =
  match Android.Runtime.register ~name:"alarm.return_to_app" (fun _payload ->
    Lwt.map (function
      | Ok () -> Android.Background.Success
      | Error _ -> Android.Background.Failure)
      (Android.Activity.launch_main ())) with
  | Ok () -> ()
  | Error _ -> failwith "Handler déjà enregistré"
```

Ce handler peut être le `stop_handler` d’un `Alarm.Screen.t` ; l’application arrête
son audio avant de demander le retour à son écran principal. La démonstration le fait
avec `integration.stop` (sans lecture audio). Le lancement passe par le thread UI.
Une instance principale existante est réutilisée et les activités au-dessus sont
retirées de la pile ; sinon Android crée l’activité à partir du manifeste du client.
Les vues ne doivent être manipulées qu’après leur callback `Activity.on_lifecycle`.
`launch_main` ne renvoie pas une poignée créée artificiellement.

`Ok ()` signifie que la demande `startActivity` a été effectuée, pas que l’interface
est déjà créée ou visible. Android impose ses restrictions de lancement en arrière-plan
et de verrouillage : depuis un simple receiver sans interaction utilisateur, le système
peut bloquer ou différer l’affichage sans lever d’exception. Une notification destinée
à ouvrir l’UI doit utiliser un PendingIntent d’activité direct, comme `Alarm.Screen.notify` ;
un receiver de notification qui ouvre ensuite l’activité reste soumis aux restrictions
sur les trampolines. Cette API ne contourne pas ces règles et ne déverrouille pas le téléphone.

## Health Connect en lecture seule

`Android.Health_connect` expose les pas, les séries de fréquence cardiaque, les
sessions de sommeil avec leurs phases, et les résumés de séances d’exercice.
Les distances et calories actives/totales sont accessibles par agrégation SDK.
Les identifiants, sources de données, dates de modification et décalages de fuseau
sont conservés. Les types/codes de phase et d’exercice restent ceux du SDK.
Aucune écriture, suppression ou route d’exercice Health Connect n’est exposée.

Commencer par `Health_connect.status ()` : `Available`, `Unavailable` ou
`Update_required`. Le SDK prend en charge le projet API 26, mais le service Health
Connect nécessite Android 9+ ; il est intégré au système à partir d’Android 14 et
fourni par une application séparée sur les versions précédentes prises en charge.
L’absence du fournisseur donne une erreur structurée, jamais des données simulées.

Le client déclare seulement les permissions qu’il utilise :
`android.permission.health.READ_STEPS`, `READ_HEART_RATE`, `READ_SLEEP`,
`READ_EXERCISE`, `READ_DISTANCE`, `READ_ACTIVE_CALORIES_BURNED` et
`READ_TOTAL_CALORIES_BURNED` (toutes dans le même espace `android.permission.health`).
`read_permission` peut obtenir les noms depuis OCaml.
`request_permissions activity [Steps; Heart_rate; Sleep; Exercise; Distance; Active_calories; Total_calories]` utilise le contrat
de consentement du SDK, distinct de `Permissions.request`. Le résultat est le sous-ensemble
accordé, éventuellement vide. `granted_permissions ()` relit l’état courant ; les
opérations suivantes vérifient les droits et peuvent renvoyer `Permission_denied`.
`open_settings activity` ouvre les réglages Health Connect lorsque le SDK est disponible.

L’activité d’explication des accès et les filtres requis par les versions Android
sont fournis dans le manifeste de la bibliothèque. Chaque client doit configurer
ces deux métadonnées applicatives avec ses propres textes, via des ressources :

```xml
<meta-data android:name="android.ocaml.health.rationale_title"
           android:resource="@string/health_policy_title" />
<meta-data android:name="android.ocaml.health.rationale_text"
           android:resource="@string/health_policy_text" />
```

Une configuration manquante est refusée avant d’ouvrir le consentement. La démo
utilise le texte « Démonstration API » fourni pour cet exemple ; chaque application
cliente doit décrire ses propres usages des données.

Une `Health_connect.query` exige `start_ms`, `end_ms`, `page_size` et `page_token`.
Les dates sont des millisecondes Unix 64 bits. `read_steps`, `read_heart_rate`,
`read_sleep` et `read_exercise` lisent une page croissante selon les règles du filtre
SDK. Réutiliser `next_page_token` tant qu’il vaut `Some token`. Les tokens sont opaques ;
une fin de pagination vide renvoyée par le fournisseur est normalisée en `None`.
Aucune taille de page ni période n’est choisie silencieusement.

Les enregistrements bruts conservent leurs sources ; ne pas les additionner pour
calculer un total de pas multi-sources. `aggregate_steps ~start_ms ~end_ms` utilise
l’agrégation du SDK et sa priorité des sources. `aggregate_distance`,
`aggregate_active_calories` et `aggregate_total_calories` suivent le même principe :
aucune somme des enregistrements bruts. La distance est en mètres, l’énergie en
kilocalories. `None` signifie absence de donnée ; un zéro mesuré reste `Some 0.`.
Tous exigent un intervalle non vide `[start_ms, end_ms[`. Les limites d’historique
et d’accès du SDK continuent de s’appliquer ; l’historique étendu n’est pas exposé.

Pour les lectures programmées lorsque l’application est masquée, le client déclare
`android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND`, en plus des permissions
de chaque type de donnée. `background_read_available ()` sonde la fonctionnalité
SDK et retourne `Ok false` si elle manque. `background_read_granted ()` vérifie le
droit courant : `Ok false` si refusé, `Error Unavailable` si non pris en charge.
`request_background_read activity` ouvre explicitement le consentement du SDK :
`Ok true` en cas d’accord, `Error Permission_denied` en cas de refus ou d’annulation,
`Error Unavailable` si la fonctionnalité manque. Le droit est vérifié à nouveau après
le consentement. Appeler cette demande depuis l’écran des autorisations visible ;
aucune lecture ni tâche ne la déclenche automatiquement.

## GPS et suivi écran éteint

`Android.Location` utilise explicitement `Gps` ou `Network`. Le GPS demande la
permission précise ; il n’est pas remplacé automatiquement par une position approximative.
Sur Android 12+, demander `ACCESS_COARSE_LOCATION` et `ACCESS_FINE_LOCATION` ensemble
avec `Permissions.request` pour permettre le choix de précision de l’utilisateur.
Un refus, une révocation ou l’absence de matériel donnent des erreurs structurées.

- `distance_between ~latitude1 ~longitude1 ~latitude2 ~longitude2` délègue le calcul
  géodésique WGS84 à Android et renvoie des mètres, sans acquérir de position.
  Les coordonnées doivent être finies, avec latitude dans [-90, 90] et longitude dans [-180, 180].
- `enabled ~provider` indique si le fournisseur est activé.
- `last_known ~provider` lit un cache optionnel, qui peut être ancien.
- `current ~provider ~timeout_ms` acquiert une position avec le délai explicite
  fourni. Elle peut être en cache ; consulter ses dates. `None` signifie absence
  de position dans le délai ou résultat nul du système. Annuler la promesse Lwt
  annule aussi la requête native et libère son écouteur.
- `watch ~provider ~interval_ms ~min_distance_m callback` fournit les positions et
  changements d’état du fournisseur. Arrêter avec `Subscription.cancel`.

Les positions contiennent latitude, longitude, précision horizontale optionnelle,
altitude/vitesse/cap optionnels, date Unix, date monotone en nanosecondes et indicateur
mock. Les intervalles sont des demandes minimales, pas une garantie de fréquence Android.

Pour un accès GPS démarré en arrière-plan, déclarer aussi
`android.permission.ACCESS_BACKGROUND_LOCATION` sur Android 10+ et obtenir le
consentement distinct de l’utilisateur. Depuis l’écran des autorisations,
`Permissions.open_settings activity Background_location` ouvre les réglages de
l’application ; `check_special Background_location` relit le droit courant.
Obtenir d’abord la localisation de premier plan. Sur API 26–28, la vérification
reflète le droit de localisation existant, sans permission distincte.
L’ouverture des réglages n’accorde aucun droit à elle seule. Les restrictions Android
sur le démarrage de services restent applicables.

Pour continuer lorsque l’application est masquée ou l’écran éteint, enregistrer
un handler typé et stable à chaque démarrage du processus :

```ocaml
let () =
  ignore (Android.Location.register_background_handler ~name:"tracking.position"
    (function
      | Android.Location.Fix position ->
          (* Stocker/traiter la position selon les besoins de l’application. *)
          ignore position;
          Lwt.return Android.Background.Success
      | _ -> Lwt.return Android.Background.Success))
```

Appeler ensuite `start_tracking_service` pendant que l’activité est visible, avec
le fournisseur, l’intervalle et la distance minimale choisis, le nom de handler,
un identifiant de notification positif, un canal créé par `Notification.create_channel`,
une icône obtenue par `Resources.resolve`, et les titre/texte de notification.
Tous ces paramètres sont explicites. Le retour attend que le service ait installé
sa notification et son abonnement ; une erreur de démarrage est renvoyée au client.
`stop_tracking_service ()` retire l’abonnement, annule le handler en cours et la notification.
La notification permet de revenir au point d’entrée du manifeste s’il existe.

Le client déclare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` sur les versions
concernées, et les permissions de localisation. Le service de type `location` est déjà
déclaré. `POST_NOTIFICATIONS` permet son affichage dans le volet de notifications sur
Android récent ; le service reste soumis à l’affichage système des services actifs
si cette permission est refusée. Un service commencé pendant l’utilisation de l’app
peut conserver l’accès en écran éteint sans demander automatiquement
`ACCESS_BACKGROUND_LOCATION`. Un démarrage depuis l’arrière-plan a des restrictions
supplémentaires et peut exiger cette permission ; aucun contournement n’est ajouté.

Il existe un seul service de suivi par application : un nouveau démarrage remplace
sa configuration. Android peut tenter une relivraison de l’intent après destruction
du processus ; les handlers doivent donc être enregistrés sans attendre une activité.
Le redémarrage reste soumis au système et aux permissions actuelles, sans garantie
après arrêt forcé. Les restrictions constructeur, d’économie d’énergie et de localisation
restent applicables.

Un handler lent conserve seulement l’événement le plus récent en attente. Il ne
s’agit pas d’une trace GPS persistante : l’application enregistre les points dont
elle a besoin. Un retour `Failure` ou `Retry` n’entraîne pas le rejeu d’un point.
Aucune position ni donnée de santé n’est enregistrée sur disque ou envoyée sur le
réseau par cette bibliothèque.

La démonstration contient les contrôles d’état/consentement Health Connect et de
permissions/cache GPS. Elle ne démarre pas un suivi avec des valeurs de fréquence
ou de notification inventées ; le client doit choisir ces paramètres explicitement.

Exemple de démarrage du suivi depuis l’UI, après obtention des droits de localisation :

```ocaml
let start_gps ~interval_ms ~min_distance_m ~notification_id
    ~channel ~channel_name ~icon_name ~title ~text =
  let open Lwt_result.Infix in
  Android.Notification.create_channel ~id:channel ~name:channel_name ~importance:Low
  >>= fun () ->
  Android.Resources.resolve ~scope:Application ~kind:Drawable ~name:icon_name
  >>= fun small_icon ->
  Android.Location.start_tracking_service ~provider:Gps
    ~interval_ms ~min_distance_m ~notification_id ~channel ~title ~text ~small_icon
    ~handler:"tracking.position"
```

## URI d’un fichier privé

`Android.Storage.file_uri ~name` construit l’URI locale du fichier portant le même
basename que pour `Storage.read_file` et `Storage.write_file`. Le répertoire vient
de `Context.getFilesDir()` : aucun chemin de machine n’est codé dans la bibliothèque.
Les espaces et caractères Unicode sont échappés par `android.net.Uri.fromFile`.
Les chemins, traversées de répertoires et caractères NUL sont refusés.

Cette fonction ne crée pas le fichier et ne vérifie pas son existence. Après une
écriture réussie, son résultat peut être passé directement à `Ui.image` ou
`Ui.set_image_uri` pour un aperçu. L’URI est `file://` et reste destinée à l’application ;
le partage externe nécessite un fournisseur `content://` configuré pour cet usage,
comme celui déjà fourni pour `Camera.uri`. Les racines de FileProvider ne sont pas
élargies à tous les fichiers privés pour ce simple accès interne.

## Réglages définis par l’application

`Android.Settings` fournit un écran AndroidX Preference depuis une liste OCaml : sections, interrupteurs,
textes, entiers 64 bits, choix, actions à état actualisable et informations en lecture seule. Les valeurs éditables sont stockées avec
`Storage.Preferences`, dans le magasin explicitement fourni par le client.

```ocaml
let settings = Android.Settings.define ~store:"my-app-settings" [
  Toggle {key="enabled"; title="Activer"; description=None; initial=false};
  Integer {key="count"; title="Nombre"; description=None; initial=1L;
    validate=Some (fun n -> if n>0L then Ok () else Error "Entier positif requis")};
  Choice {key="mode"; title="Présentation"; description=None; initial="compact";
    options=["compact","Compacte"; "detail","Détaillée"]};
  Action {title="Autorisation GPS en arrière-plan"; description=None;
    run=(fun activity -> Android.Permissions.open_settings activity Background_location)}
]
```

Cet exemple fournit des valeurs de démonstration : chaque application choisit les
siennes. Après traitement du résultat de `define`, enregistrer le schéma une fois
au démarrage du processus, avant le retour de l’entrée OCaml :

```ocaml
Android.Settings.register ~name:"app-settings" schema ~on_change ~on_error
```

Pour réunir le droit réel et son action dans une seule ligne, ajouter au schéma :

```ocaml
Status_action {key="health-background"; title="Lecture santé en arrière-plan";
  text="Vérification…";
  run=(fun activity ->
    let open Lwt.Infix in
    Android.Health_connect.background_read_granted () >>= function
    | Error e -> Lwt.return (Error e)
    | Ok true -> Android.Health_connect.open_settings activity
    | Ok false -> Android.Health_connect.request_background_read activity
        >|= Result.map (fun _ -> ()))}
```

Un callback optionnel sur `register` (ou `create`) recalcule l’état à la création
de la page, à chaque reprise de `SettingsActivity` et après la fin d’une
`Status_action` (réussite ou erreur) :

```ocaml
let on_resume _activity page =
  let open Lwt.Infix in
  Android.Health_connect.background_read_available () >>= function
  | Error e -> Lwt.return (Error e)
  | Ok false -> Android.Settings.set_summary page ~key:"health-background" "Indisponible"
  | Ok true -> Android.Health_connect.background_read_granted () >>= function
    | Error e -> Lwt.return (Error e)
    | Ok granted -> Android.Settings.set_summary page ~key:"health-background"
        (if granted then "Autorisé" else "Non autorisé")

let register_result =
  Android.Settings.register ~on_resume ~name:"app-settings" schema ~on_change ~on_error
```

Le clic reçoit l’activité des réglages et relit le droit avant de choisir entre
demander le consentement et ouvrir sa gestion. La révocation reste effectuée par
l’utilisateur dans Android ou Health Connect. Le résumé ne représente pas une
préférence booléenne persistée et ne préjuge pas du résultat du consentement.
La ligne reste désactivée pendant son action, même si le résumé change entre-temps.

Le callback `on_resume` observe les droits ; les demandes de consentement restent
des clics explicites. Ses appels sont sérialisés par page, hors du verrou du
schéma, et ses erreurs ou exceptions passent par `on_error`. Un échec ne ferme
pas l’écran. Une page hébergée ailleurs peut être mise à jour via `set_summary` depuis
son propre callback de cycle de vie.

`Settings.set_summary page ~key text` change le résumé d’une `Status_action` ou
d’une `Info` de cette page, sans sauvegarde ni notification `on_change`.
`Info {key; title; text}` reste la variante non cliquable ; `set_info` reste
disponible uniquement pour celle-ci. `Settings.refresh`
préserve ce texte ; une nouvelle page repart du `text` déclaré. Les clés sont
uniques pour toutes les lignes nommées. `Settings.get/set` refusent les clés
`Info` et `Status_action` ; `set_summary` refuse les clés inconnues ou éditables
et les pages libérées. Les anciennes lignes `Action` restent inchangées.

L’application active l’activité fournie par la bibliothèque et sélectionne ce nom
avec ces déclarations sous `<application>` (déclarer l’espace `tools` sur `<manifest>`) :

```xml
<meta-data android:name="android.ocaml.settings" android:value="app-settings" />
<activity android:name="org.ocaml.android.SettingsActivity"
          android:enabled="true" tools:replace="android:enabled" />
```

Cette activité déclare `android.intent.action.APPLICATION_PREFERENCES` ; le système
peut donc proposer un accès aux préférences de cette application. Son emplacement
et sa présence dans l’interface dépendent de la version Android et du constructeur.
Android n’offre pas d’injection libre des champs d’une application dans les pages
internes de l’application système Paramètres.

`Settings.open_ activity` ouvre le même écran depuis l’application. Il fonctionne
aussi via l’intent système lorsque le processus est arrêté : le runtime OCaml
charge le schéma enregistré sans ouvrir l’activité principale. Ne pas enregistrer
le schéma seulement lors de la construction de l’écran principal. Une rotation
reconstruit les abonnements et relit les préférences. Une configuration manquante
est refusée ; une entrée désactivée n’est pas ouverte automatiquement.

Le rendu utilise `PreferenceFragmentCompat`, `PreferenceCategory`,
`SwitchPreferenceCompat`, `EditTextPreference` et `ListPreference`, avec les dialogues
et le thème standard AndroidX ; il ne compose plus de formulaire de boutons maison.
Le thème n’est pas une copie garantie des personnalisations du constructeur.
Tous les types et callbacks sont documentés dans `lib/android.mli`.

Les interrupteurs et choix enregistrent immédiatement ; textes et nombres ouvrent
un dialogue avec confirmation et annulation. Un échec de validation reste affiché
dans le champ ; un échec de stockage passe aussi par `on_error`. `on_change` reçoit
la clé et la valeur après sauvegarde réussie uniquement. Les validateurs sont purs
et retournent `Error message` pour une erreur attendue.

`Settings.get schema ~key` retourne la valeur stockée ou la valeur initiale déclarée,
sans écrire cette dernière. Une valeur persistée incompatible est une erreur
explicite ; modifier le schéma ne constitue pas une migration automatique.
`Settings.set` applique les mêmes validations. Une écriture programmatique ne
notifie pas `on_change` ; appeler `Settings.refresh page` pour actualiser l’écran.
Une action d’autorisation ne stocke aucun booléen représentant le droit Android.

Les opérations `create/view/refresh/release` restent disponibles pour un hôte
personnalisé : il doit être une `FragmentActivity`, avec un thème AppCompat.
`BridgeActivity` prend en charge les fragments. Pour l’entrée système, privilégier
`register/open_` : la bibliothèque gère la page et sa libération.
La destruction de l’activité ferme les dialogues et désabonne les événements ;
une saisie non confirmée est abandonnée. La démo utilise cette entrée dédiée avec
chaque type de préférence et des actions explicites de consentement santé/GPS.
Aucun Java applicatif ni XML listant les champs n’est nécessaire. Ce stockage
n’est pas destiné aux secrets.

## Authentification par passkeys

`Android.Authentication` expose Credential Manager et un client de session écrit
en OCaml : inscription, connexion, réauthentification, ajout/révocation de passkeys,
profil, sessions et déconnexion. Les enveloppes JSON serveur sont adaptées par des
callbacks OCaml ; aucune dépendance à Dream n’est imposée au client Android.

Les sessions Bearer sont chiffrées avec Android Keystore et stockées atomiquement
hors sauvegardes. Les requêtes restent sur l’origine HTTPS configurée, sans suivi
de redirection ; les 401 invalident la session concernée. Les clés privées de
passkeys restent chez le fournisseur et les vérifications WebAuthn restent côté
serveur. La bibliothèque n’implémente pas d’association QR spécifique.

Fournir explicitement URL, délais et noms de stockage, déclarer INTERNET et configurer
Digital Asset Links avec le domaine et le certificat de signature réels. Voir le
[guide Android en OCaml](notes/AUTHENTIFICATION-ANDROID.md) pour les adaptateurs,
les parcours, les erreurs et les exigences des APK distribués hors Play Store.

## Dialogues, formulaires et présentation partagée

`Android.Dialog.alert`, `confirm` et `show` fournissent des dialogues Material avec
textes ou vues OCaml, boutons et fermeture asynchrone. Un callback peut garder un
dialogue ouvert pendant la correction d’un formulaire.

`Android.Form` fournit des champs avec libellé, aide, clavier adapté et erreur sous
le champ. La validation reste une fonction OCaml, appelée à la soumission ou avec
`Form.on_change`. `Form.container`, `Ui.primary_button`, `Ui.secondary_button`,
les espaces et les insets en dp partagent les styles et ressources de la bibliothèque.

Voir le [guide des interfaces OCaml](notes/INTERFACE-ANDROID.md) et la démo native
pour un dialogue de saisie avec validation et les règles de libération des vues.

## Écrans Material et mise en page

`Ui.column`, `row`, `stack`, `nested_scroll_view` et `constraint_layout` complètent
les conteneurs existants. `Ui.layout` et `Ui.add ~layout` règlent les dimensions,
marges, alignements et poids ; les espacements et insets sont en dp, les poids
sans unité. Les propriétés peuvent aussi être mises à jour avec `Ui.set_layout`.

Les modules `Toolbar`, `Card`, `Bottom_navigation`, `Check_box`, `Switch` et
`Divider` exposent les composants Material et leurs événements. La démo montre
un écran de tâches avec cartes, cases et navigation entre deux sections.
Voir le [guide de mise en page Material](notes/MISE-EN-PAGE-MATERIAL.md).
