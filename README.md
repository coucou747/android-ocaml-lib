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

Dans un switch OCaml 5.4.1, installer les dépendances via
`opam install ./android-ocaml-lib.opam --deps-only`, puis :

```sh
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
`29.0.14206865`, un JDK 21 et un switch OCaml 5.4.1 équipé de
[opam-cross-android](https://github.com/ocaml-cross/opam-cross-android).
Installer les paquets croisés nécessaires, notamment `ocaml-android` et
`lwt-android`, en complément des dépendances hôte. Lors de l’installation de
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
