# Séance 6 — Fiabilité : reprise, surveillance, temps réel

**Objectif :** savoir expliquer ce qui se passe quand quelque chose tombe en panne (Airflow, le serveur, le réseau, un rappel perdu) et pourquoi une version ne reste jamais « en cours » pour toujours.

**Durée :** 1 h 30 · **Prérequis :** séances 0 à 2.

Fichiers (sous `backend/src/main/java/wevioo/backend/`) : `service/PipelineWatchdog.java`, `service/RunRecovery.java`, `service/EventStreamService.java`, `controller/EventController.java`, `service/PipelineIngestionService.java` (`markCompleted`), `config/WebConfig.java`. Côté interface : `frontend/src/app/core/live-events.service.ts`.

---

## 1. Le problème

Une exécution fait intervenir trois systèmes (serveur, Airflow, worker) et des appels réseau. Elle peut rester **bloquée en `PENDING` ou `RUNNING`** de plusieurs façons :

| Panne | Conséquence |
| --- | --- |
| Airflow est arrêté au moment du dépôt | le déclenchement échoue |
| le serveur redémarre juste après le dépôt | Airflow n'a peut-être jamais reçu l'ordre |
| Airflow a été réinitialisé | il ne connaît plus l'exécution |
| un rappel du worker est perdu | le serveur ne sait pas que c'est fini |
| le DAG est en pause dans Airflow | l'exécution reste « en file » |

La plateforme se protège avec **quatre mécanismes** complémentaires.

---

## 2. Les quatre mécanismes

### 2.1 Les rappels réessayés (côté worker, séance 2)
`backend_client._post_with_retry` : 7 tentatives, attente 2, 4, 8… secondes (environ deux minutes). Un redémarrage du serveur n'interrompt pas une exécution en cours.

### 2.2 La tâche finale `ALL_DONE` (séance 0)
Même si une phase échoue, `mark_pipeline_completed` s'exécute et annonce `failed` au serveur.

### 2.3 La reprise au démarrage : `RunRecovery`
Déclenchée par `@EventListener(ApplicationReadyEvent.class)` et `@Async` : elle s'exécute **en arrière-plan, une fois le serveur prêt**, pour qu'un Airflow lent ou injoignable ne retarde jamais le démarrage.

`recover()` parcourt les exécutions en `PENDING` ou `RUNNING` et appelle `recoverRun` pour chacune, après avoir demandé son état à Airflow (`getDagRunState`) :

| État renvoyé par Airflow | Décision |
| --- | --- |
| `NOT_FOUND` **et** les fichiers existent encore | **relancer** (`triggerPipeline` avec le même identifiant d'exécution), remettre `PENDING`, événement `PIPELINE_STARTED` (« The run was started again after a restart ») |
| `NOT_FOUND` **mais** le dossier a disparu | **échec** : « …its uploaded files are gone. Upload the files again. » |
| `NOT_FOUND` et le relancement échoue | **échec** avec le message d'Airflow |
| `failed` | **échec** : « Airflow reports this run as failed… » |
| `queued`, `running`, `success`, ou Airflow injoignable | **ne rien faire** |

**Pourquoi ne rien faire pour `running` ?** Airflow travaille encore : le worker réessaie ses rappels et finira par prévenir le serveur. **Pour `success` ?** Le rappel final va arriver, ou le gardien (§2.4) s'en chargera. **Pour injoignable ?** On ne sait pas : mieux vaut ne rien casser, le gardien tranchera.

Les événements sont publiés **après** la transaction (`events.forEach(eventStreamService::publish)` hors du `execute`) : on ne diffuse jamais un événement dont l'enregistrement pourrait ensuite être annulé.

### 2.4 Le gardien : `PipelineWatchdog`
`@Scheduled(fixedDelay = 60 000 ms)` : **toutes les minutes**. `reconcile` parcourt les exécutions `PENDING`/`RUNNING` :
1. Calcule leur **âge** depuis `startedAt`, sinon `createdAt`, sinon la date de dépôt de la version.
2. Si l'âge est **inférieur à 10 minutes** (`watchdog-stale-after-minutes`), on passe : elle est jeune.
3. Sinon demande son état à Airflow et décide :

| État Airflow | Décision |
| --- | --- |
| `failed` | `FAILED` : « Airflow reports this run as failed… » |
| `NOT_FOUND` | `FAILED` : « Airflow has no record of this run… » |
| `success` | `FAILED` : « Airflow finished this run but the backend never received the completion callback. Check PIPELINE_API_TOKEN and BACKEND_URL. » (le message **guide le diagnostic**) |
| autre (en file, en cours, injoignable) | `FAILED` **seulement si l'âge dépasse 180 minutes** (`watchdog-max-run-minutes`) : « The run exceeded 180 minutes without finishing… » |

> **Point à retenir :** une exécution simplement *lente* mais saine n'est jamais tuée avant 3 heures ; une exécution que **Airflow dit échouée ou ignore** est tuée dès qu'elle a plus de 10 minutes.

Les réglages sont dans `application.properties` (lignes 49-52) et modifiables par les variables `WATCHDOG_INTERVAL_MS`, `WATCHDOG_STALE_AFTER_MINUTES`, `WATCHDOG_MAX_RUN_MINUTES`. Les annotations `@EnableScheduling` et `@EnableAsync` sont dans `config/WebConfig.java`.

---

## 3. Fin d'exécution : `markCompleted` (PipelineIngestionService, ligne 163)

Reçoit le statut du worker (`success`, `partial`, `failed`) et :
- met à jour l'exécution et la version (`COMPLETED`, `PARTIAL` ou `FAILED`) avec le message et les tables en échec ;
- si l'exécution **n'a pas échoué**, appelle `markRemovedTables` : les tables de la version précédente **absentes** de celle-ci sont enregistrées comme `REMOVED` (c'est ce qui alimente la comparaison de versions) ;
- renvoie `PIPELINE_COMPLETED` (« completed with N failed table(s) » pour `PARTIAL`) ou `PIPELINE_FAILED`.

---

## 4. Le temps réel : SSE

### Côté serveur
`EventController` : `GET /api/events/stream` (`text/event-stream`), réservé aux utilisateurs authentifiés. `EventStreamService` :
- garde la liste des abonnés dans un `CopyOnWriteArrayList<SseEmitter>` (sûr quand on **publie** pendant qu'un autre navigateur **s'abonne**) ;
- `new SseEmitter(0L)` : **aucun délai d'expiration** ; la connexion reste ouverte ;
- `publish` envoie l'événement à chaque abonné ; si l'envoi échoue (navigateur parti), l'abonné est **retiré** ;
- `onCompletion`, `onTimeout`, `onError` retirent aussi l'abonné.

Un événement (`LiveEvent`) contient : `type`, `datasetId`, `versionId`, `versionNumber`, `pipelineRunId`, `status`, `tableName`, `message`, `emittedAt`. Les types vus dans le code : `PIPELINE_STARTED`, `TABLE_READY`, `GLOBAL_READY`, `PIPELINE_COMPLETED`, `PIPELINE_FAILED`, `RULES_SUGGESTED`, `RULES_FAILED`.

**Limite assumée :** les abonnés sont **en mémoire**. Si le serveur redémarre, les événements ne sont pas rejoués : c'est l'interface qui rattrape (§5).

### Côté interface : `live-events.service.ts`
- Ouvre le flux avec `fetch` (et pas `EventSource`) pour pouvoir envoyer l'en-tête `Authorization: Bearer`.
- État `transport` : `connecting`, `sse`, `polling`, `offline`. La pastille **Live** de la barre du haut le reflète.
- À la connexion réussie : arrête le polling et émet un événement synthétique **`RECONNECTED`** pour rattraper ce qui s'est passé pendant la coupure.
- Si le flux tombe : bascule sur **polling** (un événement synthétique `POLL` toutes les 5 s) et retente la connexion toutes les 5 s ; une erreur réseau (`TypeError`) donne `offline`.

Le magasin d'état (`workspace.store.ts`) ne recharge qu'**une fois par rafale** d'événements, et garde l'affichage actuel pendant le rechargement.

---

## 5. Les scénarios, un par un

| Scénario | Qui agit | Résultat |
| --- | --- | --- |
| Airflow arrêté au dépôt | `triggerCommittedRun` → `markTriggerFailed` | `FAILED` avec « Could not start the analysis in Airflow… ». **Retry** quand il revient. |
| Serveur redémarré 6 s après le dépôt (TF5) | Airflow tourne ; `RunRecovery` voit un état « en cours » → ne fait rien ; le worker réessaie ses rappels | l'exécution finit `COMPLETED` |
| Serveur redémarré **avant** l'appel à Airflow | `RunRecovery` voit `NOT_FOUND` | **relance** l'exécution si les fichiers sont là |
| Airflow réinitialisé | état `NOT_FOUND` | relance (au démarrage) ou échec (par le gardien après 10 min) |
| Rappel final perdu | le gardien voit `success` | `FAILED` avec un message qui oriente vers `PIPELINE_API_TOKEN` / `BACKEND_URL` |
| DAG en pause | état « en file » | rien avant 180 min, puis `FAILED` |
| Navigateur qui perd le flux | interface | polling toutes les 5 s, retentative, `RECONNECTED` |

---

## 6. Mots à maîtriser

| Mot | Définition courte |
| --- | --- |
| Gardien (*watchdog*) | Tâche périodique qui surveille et corrige des états anormaux. |
| Réconciliation | Aligner l'état du serveur sur l'état réel d'Airflow. |
| `@Scheduled` / `@Async` | Exécution périodique / en arrière-plan. |
| SSE | Flux d'événements poussés par le serveur sur une connexion ouverte. |
| Repli (*fallback*) | Mode dégradé quand le mécanisme normal tombe (ici : polling). |
| Publier après commit | Diffuser un événement seulement quand son enregistrement est définitif. |

---

## 7. Exercices

**A. Provoquer un échec de déclenchement (10 min).** Arrêtez Airflow (`docker compose --env-file Data_Quality_Wevioo/.env stop airflow-webserver airflow-scheduler`), déposez un CSV, notez le statut et le message ; relancez Airflow et cliquez **Retry**.

**B. Redémarrer le serveur en plein vol (15 min).** Déposez un CSV, puis dans les 5 secondes : `docker restart data-quality-pipeline-backend-1`. Attendez 1 minute : quel statut final ? *(Attendu : `COMPLETED`.)* Dans les journaux du serveur, cherchez ce que `RunRecovery` a fait.

**C. Observer la pastille Live (10 min).** Ouvrez l'application, notez la pastille **Live**. Arrêtez le serveur : que devient-elle ? Relancez-le : combien de temps avant son retour ?
*(Attendu : elle passe en repli puis revient en moins d'environ 10 secondes ; l'interface rejoue un rafraîchissement via `RECONNECTED`.)*

**D. Raccourcir le gardien (20 min).** Dans `docker-compose.yml`, ajoutez au service `backend`, dans `environment`, la ligne `WATCHDOG_STALE_AFTER_MINUTES: "1"` (elle n'y est pas par défaut ; le serveur utilise alors sa valeur de 10 minutes), relancez (`up -d backend`), puis déposez un fichier et **mettez le DAG en pause** dans Airflow. Que fait le gardien après 1 minute ? Pourquoi **pas** d'échec immédiat ? *(Attendu : état « en file » : aucune décision avant 180 min.)* Remettez la valeur d'origine.

**E. Lire un test (15 min).** Ouvrez `backend/src/test/java/wevioo/backend/service/PipelineWatchdogTest.java` : quels états d'Airflow simule-t-il et quelle décision vérifie-t-il pour chacun ? Comparez avec le tableau du §2.4.

---

## 8. Questions de contrôle

1. Citez trois causes pour lesquelles une exécution peut rester bloquée.
2. Quels sont les quatre mécanismes de protection ?
3. Pourquoi `RunRecovery` est-il asynchrone ?
4. Que fait `RunRecovery` d'une exécution que Airflow ne connaît pas ?
5. Pourquoi ne fait-il rien quand Airflow est injoignable ?
6. À quelle fréquence le gardien s'exécute-t-il et quelle exécution regarde-t-il ?
7. Quand le gardien marque-t-il une exécution `FAILED` ? Et quand ne le fait-il pas ?
8. Pourquoi le message pour l'état `success` cite-t-il `PIPELINE_API_TOKEN` ?
9. Pourquoi publier les événements après la transaction ?
10. Que se passe-t-il si le navigateur perd le flux SSE ?
11. À quoi sert l'événement `RECONNECTED` ?
12. Que devient une table de la version précédente qui n'existe plus dans la nouvelle ?

### Corrections
1. Airflow arrêté, serveur redémarré avant d'appeler Airflow, rappel perdu (ou DAG en pause, Airflow réinitialisé).
2. Rappels réessayés par le worker, tâche finale `ALL_DONE`, reprise au démarrage, gardien périodique.
3. Pour qu'un Airflow lent ou injoignable ne retarde pas le démarrage du serveur.
4. Si ses fichiers existent encore, il la relance avec le même identifiant ; sinon il la marque `FAILED` avec « fichiers disparus ».
5. Il ne sait pas : il vaut mieux ne rien casser ; le worker réessaie ses rappels et le gardien tranchera si rien n'arrive.
6. Toutes les minutes ; les exécutions `PENDING`/`RUNNING` de plus de 10 minutes.
7. Si Airflow dit `failed`, ne la connaît pas, ou dit `success` sans rappel reçu ; ou si elle dépasse 180 minutes. Il ne le fait pas pour une exécution simplement en cours ou en file de moins de 180 minutes.
8. Parce que si Airflow a fini mais que le serveur n'a pas reçu le rappel, la cause la plus probable est un jeton ou une adresse de serveur mal configurés.
9. Pour ne jamais annoncer un état que la base pourrait ensuite annuler.
10. L'interface bascule sur un rafraîchissement toutes les 5 s, retente la connexion toutes les 5 s, puis émet `RECONNECTED` pour rattraper.
11. À forcer un rafraîchissement des données affichées après une coupure, car les événements perdus ne sont pas rejoués.
12. Elle est enregistrée comme table `REMOVED` dans la nouvelle version, ce qui alimente la comparaison.

---

**Prochaine séance :** les corrections (opérations, aperçu, version dérivée).
