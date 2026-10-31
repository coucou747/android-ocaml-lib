# AGENTS.md

## Scope
Projet OCaml avec dune. Toute modification doit rester locale au workspace.

## Primary goals
- Préserver la correction sémantique.
- Préférer des changements petits et vérifiables.
- Ne pas réécrire l’architecture sans nécessité explicite.
- Éviter les dépendances nouvelles sauf justification.

## Build and test
Avant de conclure qu’une tâche est terminée :
1. exécuter `dune build`
2. exécuter `dune runtest`
3. si un fichier public change d’interface, vérifier aussi les `.mli`
4. si un bug est corrigé, ajouter un test de régression minimal

## OCaml style
- Préférer des types explicites aux conventions implicites quand l’API publique change.
- Préférer `Result` ou erreurs structurées aux exceptions pour les erreurs attendues.
- Garder les fonctions petites et à responsabilité claire.
- Éviter les renommages massifs non demandés.
- Ne pas introduire de foncteurs ou GADT sans nécessité réelle.
- Préserver les signatures publiques autant que possible.

## Editing policy
- Modifier le minimum de fichiers nécessaire.
- Si une transformation de code est répétitive, créer un script local et le réutiliser.
- Ne jamais toucher aux fichiers hors workspace.

## Tests
- Ajouter des tests unitaires pour les fonctions pures.
- Ajouter des tests d’intégration pour les flux `dune exec` si pertinent.
- Après correction d’un bug, écrire d’abord un test qui échoue, puis corriger.
- Ne jamais supprimer un test cassé sans expliquer pourquoi.

## Project structure
- `lib/` : logique métier
- `bin/` : exécutables
- `test/` : tests
- `scripts/` : scripts de transformation ou maintenance
- `notes/` : documentation de workflow

## Extra docs
Pour certaines tâches, lire aussi :
- `notes/GENERATE-MLI.md` pour la génération ou revue d’interfaces
- `notes/COVER-TESTS.md` pour la politique de tests

## Output expectations
Dans le compte-rendu final :
- résumer les fichiers touchés
- indiquer les commandes exécutées
- signaler ce qui reste incertain

## Dependency-first rule

Toute nouvelle fonctionnalité doit suivre ce processus :

- étape 1 : identifier les librairies existantes pertinentes
- étape 2 : lister au moins une alternative externe
- étape 3 : justifier explicitement le choix (librairie ou implémentation)

Si aucune analyse n’est fournie, la tâche est considérée incomplète.

## OCaml ecosystem

Lors de la recherche de solutions existantes, considérer en priorité :

- opam repository
- containers
- stdlib étendue

Préférer des librairies maintenues et utilisées.

## Hard constraint

Interdiction d’implémenter une structure ou un algorithme standard
(hash table, parser, CLI, JSON, serveur web, etc.)
sans justification écrite expliquant pourquoi aucune librairie n’est utilisée.

## Configuration policy

- Ne jamais hardcoder une valeur de configuration (port, host, chemins).
- Ne jamais inventer une valeur arbitraire.

Pour toute configuration requise :
1. vérifier l’existence d’un fichier de configuration
2. si absent, demander explicitement à l’utilisateur de le définir
3. ne pas choisir de valeur par défaut sans instruction explicite

Le port doit être lu depuis :
- un fichier `config.json` ou équivalent

Interdiction :
- `let port = 8080` sans validation utilisateur
- fallback silencieux

Si aucune configuration n’est disponible :
→ arrêter et demander l’entrée utilisateur

## Server execution policy
Pour les serveurs, lire les instructions depuis le fichier : `notes/AGENTS-web-server.md`

Interdiction de lancer directement :
- `dune exec ...`
- `npm run dev`
- ou tout process bloquant

# OPAM file generation policy

Objectif : Maintenir un fichier `.opam` cohérent avec le projet et ses
dépendances réelles.

## Quand agir

-   création d'un nouveau projet
-   ajout d'une dépendance dans `dune`
-   erreur de build liée à une librairie manquante
-   incohérence entre `dune` et `.opam`

## Règles

-   Le fichier `.opam` est la source de vérité pour les dépendances.
-   Toute librairie utilisée dans `dune` doit apparaître dans `depends`.
-   Ne jamais supposer qu'une dépendance est déjà installée.
-   Ne jamais ajouter une dépendance inutile ou non utilisée.

## Procédure

1.  Identifier les librairies dans les fichiers `dune`
2.  Construire la liste unique des dépendances
3.  Générer ou mettre à jour `<project>.opam`
4.  Ajouter les dépendances dans `depends`
5.  Vérifier avec `dune build` et `opam install . --deps-only`

## Contraintes

-   Toujours inclure `ocaml` et `dune`
-   Ne pas surcontraindre les versions

## Résultat attendu

-   fichier `.opam` minimal et cohérent
## OCaml debug policy

Objectif : Rendre les erreurs exploitables immédiatement (stack traces,
contexte, logs).

------------------------------------------------------------------------

### Règle principale

Toute exécution OCaml en phase de debug doit activer les backtraces.

Implémentation obligatoire :

OCAMLRUNPARAM=b dune exec `<executable>`{=html}

ou :

export OCAMLRUNPARAM=b

------------------------------------------------------------------------

### Dune / build

-   Compiler avec debug activé : dune build --profile=dev

-   Ne jamais désactiver les informations de debug en phase de
    développement.

------------------------------------------------------------------------

### Exécution

-   En cas d'exception :
    -   relancer avec OCAMLRUNPARAM=b
    -   capturer la stack trace complète
    -   inclure la trace dans le diagnostic

------------------------------------------------------------------------
