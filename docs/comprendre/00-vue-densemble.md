# Séance 0 — Le chemin complet d'un fichier

**Objectif :** savoir dessiner de mémoire, sans notes, ce qui se passe entre le moment où vous déposez un CSV et le moment où le résultat apparaît à l'écran. Tout le reste du projet est un détail de ce chemin.

**Durée :** 1 h 30 · **Prérequis :** l'application tourne (`docker compose --env-file Data_Quality_Wevioo/.env up -d`).

---

## 1. Les cinq acteurs

| Acteur | Dossier | Rôle en une phrase |
| --- | --- | --- |
| Interface | `frontend/` (Angular) | Ce que voit l'utilisateur ; envoie le dépôt et écoute les événements. |
| Serveur | `backend/` (Spring Boot) | Le chef d'orchestre : il reçoit, enregistre, déclenche, reçoit les résultats et les redistribue. |
| Worker | `Data_Quality_Wevioo/` (Python, Airflow) | Fait le travail lourd : lire les CSV, mesurer, interroger le modèle. |
| Neo4j + service de questions | `graphrag/` | Utilisés plus tard (graphe, questions). Pas dans ce chemin. |
| Ollama | sur votre machine | Le modèle de langage, appelé par le worker. |

**À retenir :** l'interface ne parle **jamais** à Airflow. Seul le serveur parle à Airflow, et seul Airflow répond au serveur.

---

## 2. Le chemin, étape par étape

```
 Navigateur        Serveur (Spring Boot)               Airflow / worker
 ──────────        ─────────────────────               ────────────────
 1 dépôt CSV ───►  2 valide, crée la version (PENDING)
                   3 COMMIT en base
                   4 déclenche Airflow ───────────────► 5 exécute les 9 phases
                   ◄──── 6 rappels (start, table-analysis ×2/table,
                         rule-suggestions, global-analysis, complete)
                   7 enregistre chaque résultat
                   8 publie un événement
 9 ◄── SSE ─────── (flux ouvert en permanence)
 10 recharge la version et affiche
```

### Étape 1 — Le dépôt (interface)
`frontend/src/app/core/api.service.ts`, méthode `upload` (ligne ~60) : envoie un formulaire `multipart` vers `POST /api/datasets/upload` avec le jeton de connexion.

### Étapes 2 à 4 — Le serveur reçoit, enregistre, déclenche
Fichier : `backend/src/main/java/wevioo/backend/service/DatasetService.java`.

- `upload(...)` (ligne 89) fait trois choses dans cet ordre :
  1. `prepareUpload` : valide **tous** les fichiers avant d'écrire quoi que ce soit (extension `.csv`, nom réservé, doublon de nom, fichier vide), et calcule le SHA-256 de chacun.
  2. `transactionTemplate.execute(... planUpload ...)` (ligne 110) : dans **une transaction**, crée le jeu de données si besoin, calcule le numéro de version, écrit les fichiers sur le disque, enregistre la version au statut `PENDING` et crée un `PipelineRun` avec un identifiant externe.
  3. `triggerCommittedRun(plan, ...)` (ligne 408) : appelé **après** la transaction (commentaire ligne 114 : *« the version and run are committed before Airflow can call back »*).
- **Pourquoi cet ordre est important :** si on déclenchait Airflow avant le commit, le worker pourrait rappeler le serveur au sujet d'une version que la base ne connaît pas encore. C'est un bug classique de « course » entre deux systèmes, et la séparation transaction / déclenchement l'évite.
- **Cas du doublon** (lignes 304-327) : si le jeu de données existe déjà avec les mêmes fichiers (même nom, même SHA-256), on ne crée pas de version : on renvoie la version existante avec `duplicateVersion = true`.
- Si Airflow est injoignable, `markTriggerFailed` passe l'exécution à `FAILED` avec un message lisible (« Could not start the analysis in Airflow… »).

Fichier : `service/AirflowClient.java`, méthode `triggerPipeline`. Elle appelle l'API REST d'Airflow `POST /api/v1/dags/data_quality_analysis_pipeline/dagRuns` avec :
- `dag_run_id` = l'identifiant d'exécution du serveur → une exécution Airflow et une version de l'application se correspondent toujours ;
- `conf` = `dataset_id`, `version_id`, `pipeline_run_id`, `file_path` (le dossier des CSV tel que le conteneur le voit), `backend_url`.

### Étape 5 — Airflow exécute les neuf phases
Fichier : `Data_Quality_Wevioo/dags/data_quality_pipeline.py`.

- Le DAG s'appelle `data_quality_analysis_pipeline`, avec `schedule=None` : il ne démarre **que** sur appel du serveur.
- La ligne 202 donne l'ordre : `validate_request >> notify_started >> discover_tables >> profile_tables >> analyze_tables >> publish_results >> suggest_rules >> global_analysis`.
- La tâche finale `mark_pipeline_completed` a `trigger_rule=ALL_DONE` (ligne 198) : elle s'exécute **toujours**, même si une phase a échoué, pour que le serveur apprenne la fin et le statut. C'est ce qui évite une exécution « bloquée ».
- Chaque tâche appelle une fonction `phase_*` de `dq_pipeline/runners.py`. On les étudie en séance 2.

### Étape 6 — Les rappels du worker vers le serveur
Fichier : `backend/.../controller/PipelineController.java`. Cinq routes sous `/api/pipeline/{runId}/` :

| Route | Qui l'appelle, quand | Ce que fait le serveur |
| --- | --- | --- |
| `/start` (ligne 38) | phase 2 | `markStarted` : exécution et version → `RUNNING` ; événement `PIPELINE_STARTED` |
| `/table-analysis` (ligne 49) | phase 5, **deux fois par table** : d'abord les métriques, puis l'analyse enrichie par le modèle | `saveTableAnalysis` ; événement `TABLE_READY` |
| `/rule-suggestions` (ligne 72) | phase 7 | enregistre les règles proposées (état `SUGGESTED`) |
| `/global-analysis` (ligne 60) | phase 8 | `saveGlobalAnalysis` ; événement `GLOBAL_READY` |
| `/complete` (ligne 83) | phase 9 | `markCompleted` (statut `COMPLETED`, `PARTIAL` ou `FAILED`), **puis évalue les règles** (`ruleService.evaluateForRun`, ligne 97) ; événements `PIPELINE_COMPLETED` / `RULES_FAILED` |

**Sécurité de ces routes :** elles n'ont pas de session. Chacune appelle `requirePipelineToken` (ligne 105) : le jeton de l'en-tête `X-Pipeline-Token` doit être égal à `PIPELINE_API_TOKEN`, comparé avec `MessageDigest.isEqual`. Cette comparaison prend le même temps que le jeton soit presque juste ou totalement faux, ce qui empêche de le deviner en mesurant les temps de réponse.

### Étape 7 — Ce que fait `saveTableAnalysis`
Fichier : `service/PipelineIngestionService.java`, ligne 50.

1. Retrouve (ou crée) la `TableVersion` de ce nom dans cette version.
2. Cherche la **même table dans la version précédente** du jeu de données (lignes 65-71).
3. Compare les signatures de contenu (`DataSignatures.sameContent`) : `NEW` s'il n'y a pas de version précédente, `UNCHANGED` si le contenu est identique, `MODIFIED` sinon. C'est ce qui alimente l'onglet *Compare*.
4. Enregistre les nombres (lignes, colonnes, doublons), le statut du modèle, l'interprétation en JSON et, pour chaque colonne, ses métriques, son **rôle**, sa confiance et ses **indices**.
5. Calcule le score de qualité de la table et renvoie un événement `TABLE_READY`.

Parce que ce rappel arrive deux fois, la méthode est écrite en « chercher sinon créer » (`orElseGet`) : la deuxième fois, elle **met à jour** au lieu de dupliquer.

### Étapes 8 à 10 — Du serveur à l'écran
- `PipelineController.publish` appelle `EventStreamService.publish`. Ce service garde la liste des navigateurs connectés (`SseEmitter`) et envoie l'événement à chacun.
- Côté interface, `core/live-events.service.ts` ouvre ce flux avec `fetch` (et non `EventSource`, pour pouvoir envoyer le jeton `Bearer`). Si le flux tombe, il **bascule sur une interrogation toutes les 5 s** (`POLL_INTERVAL_MS`) et retente la connexion toutes les 5 s.
- `core/workspace.store.ts` écoute ces événements. Ligne ~51 : plusieurs événements arrivent souvent en rafale (table prête, puis analyse du modèle prête) ; le store ne recharge qu'**une fois par rafale**, et un rechargement « silencieux » garde l'ancien affichage jusqu'à l'arrivée des nouvelles données, pour que la page ne saute pas.

---

## 3. Les mots à maîtriser

| Mot | Définition courte |
| --- | --- |
| Version | Un dépôt daté d'un jeu de données (v1, v2…). Une version corrigée est une version « dérivée ». |
| `PipelineRun` | Une exécution du pipeline, rattachée à une version, avec un identifiant externe partagé avec Airflow. |
| DAG | Le graphe des tâches d'Airflow. Ici : une ligne de 9 tâches. |
| Callback (rappel) | Appel du worker vers le serveur pour lui rendre compte. |
| SSE | *Server-Sent Events* : le serveur pousse des événements au navigateur sur une connexion HTTP qui reste ouverte. |
| Statuts | `PENDING` → `RUNNING` → `COMPLETED` / `PARTIAL` / `FAILED`. |

---

## 4. Exercices

**Exercice A — Regarder le flux en direct (10 min).**
1. Récupérez un jeton : `curl -s -X POST http://localhost:8081/api/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin123\"}"`.
2. Dans un terminal : `curl -N -H "Authorization: Bearer <jeton>" http://localhost:8081/api/events/stream`.
3. Dans l'interface, déposez un petit CSV.
4. Notez dans l'ordre les événements qui défilent. Vous devez voir `PIPELINE_STARTED`, puis **deux** `TABLE_READY` par table, `GLOBAL_READY`, `PIPELINE_COMPLETED`.

**Exercice B — Suivre la même exécution dans Airflow (10 min).** Ouvrez `http://localhost:8080`, le DAG `data_quality_analysis_pipeline`. Retrouvez l'exécution dont l'identifiant est le même que celui de l'événement. Ouvrez les journaux de la tâche `extract_metrics_and_run_llm`.

**Exercice C — Casser quelque chose (15 min).**
1. Arrêtez Airflow : `docker compose --env-file Data_Quality_Wevioo/.env stop airflow-webserver airflow-scheduler`.
2. Déposez un CSV. Que voyez-vous dans l'interface ? Quel est le statut de la version ? Quel est le message ?
3. Relancez Airflow (`start`), puis utilisez le bouton **Retry**. Qu'est-ce qui change ?

*(Réponse attendue : le dépôt réussit, la version passe à `FAILED` avec « Could not start the analysis in Airflow… ». Après relance d'Airflow, Retry rappelle `rerunVersion`, qui efface les anciens résultats et déclenche une nouvelle exécution.)*

---

## 5. Questions de contrôle — répondez sans regarder

1. Quels sont les cinq acteurs, et lesquels se parlent directement ?
2. Pourquoi Airflow n'est-il déclenché qu'**après** la transaction ?
3. Que se passe-t-il si on dépose deux fois exactement les mêmes fichiers ?
4. Pourquoi le résultat d'une table est-il publié **deux fois** ?
5. Pourquoi la tâche finale du DAG a-t-elle la règle `ALL_DONE` ?
6. Comment le serveur sait-il qu'un appel du worker est légitime ?
7. Pourquoi une comparaison « en temps constant » pour le jeton ?
8. Qu'est-ce qui fait que l'interface se met à jour sans que l'utilisateur recharge la page ? Et si ce mécanisme tombe ?
9. Comment le serveur sait-il qu'une table est `NEW`, `MODIFIED` ou `UNCHANGED` ?
10. Où est évaluée une règle de qualité, et à quel moment ?

### Corrections
1. Interface, serveur, worker (Airflow), Neo4j/service de questions, Ollama. Seuls interface↔serveur, serveur↔Airflow, worker→Ollama se parlent. L'interface ne parle jamais à Airflow.
2. Pour que la version existe déjà en base quand le worker rappelle : sinon il parlerait d'une version inconnue.
3. Aucune nouvelle version : le serveur renvoie la version existante avec `duplicateVersion = true` (sauf demande explicite de relance).
4. Une première fois avec les mesures, dès qu'elles sont prêtes, pour que l'utilisateur voie la table vite ; une seconde fois avec l'analyse du modèle, plus lente.
5. Pour qu'elle s'exécute même si une phase a échoué et que le serveur reçoive toujours le statut de fin ; sans elle, l'exécution resterait bloquée.
6. Par le jeton partagé `X-Pipeline-Token`.
7. Pour qu'on ne puisse pas le deviner caractère par caractère en mesurant le temps de réponse.
8. Le flux SSE ; s'il tombe, l'interface interroge le serveur toutes les 5 s et retente la connexion.
9. En comparant les signatures de contenu de la table avec celles de la même table dans la version précédente.
10. À la fin de l'exécution, dans `/complete` (`ruleService.evaluateForRun`), et à la demande par « Vérifier maintenant ».

---

## 6. Ce que vous devez pouvoir faire à la fin de la séance
- Dessiner le schéma du §2 de mémoire et le raconter en 3 minutes.
- Citer les trois fichiers clés : `DatasetService.java`, `data_quality_pipeline.py`, `PipelineController.java`.
- Répondre aux 10 questions sans la correction.

**Prochaine séance :** le serveur en détail (dépôt, versions, relance), `DatasetService` ligne par ligne.
