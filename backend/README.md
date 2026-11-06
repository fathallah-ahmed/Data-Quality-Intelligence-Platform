# Backend Spring Boot

Backend REST de la plateforme Data Quality. Il reçoit les uploads, versionne les datasets, déclenche Airflow, stocke les résultats, évalue les règles qualité, corrige les tables, construit le graphe dans Neo4j, transmet les questions au service de questions, et protège l'accès par un rôle admin.

Pile : Spring Boot 4.1, Java 17, Spring Data JPA (H2 en fichier par défaut, MySQL possible), Spring Security avec jetons JWT, pilote Neo4j (Bolt). Pas de couche asynchrone externe : les événements temps réel passent par SSE.

## Rôle

- Upload d'un ou plusieurs fichiers CSV ; détection d'un upload déjà existant (noms de fichiers + SHA-256 du contenu).
- Création des datasets et des versions ; une version corrigée est une **version dérivée** de celle qu'elle corrige.
- Déclenchement du DAG Airflow `data_quality_analysis_pipeline` et réception de ses callbacks (démarrage, tables, règles suggérées, analyse globale, fin).
- Statuts `COMPLETED` / `PARTIAL` / `FAILED`, tables en échec et raisons exposées.
- Watchdog qui marque `FAILED` les runs bloqués, et reprise au démarrage (`RunRecovery`).
- Règles qualité par dataset (manuelles ou suggérées), évaluées à la fin de chaque run ; dimensions de qualité avec numérateur et dénominateur ; comparaison de deux versions.
- Corrections d'une table (trouvées dans les données ou écrites en langage naturel), appliquées sur une copie.
- Modèle de graphe déduit des données et construction dans Neo4j.
- Questions en langage naturel, relayées au service `graphrag` avec les faits qualité de la version.
- Publication temps réel via SSE ; suppression complète d'un dataset (fichiers, résultats, graphes).

## Prérequis

- Java 17 (`winget install --id EclipseAdoptium.Temurin.17.JDK -e`, puis `java -version`). Maven est fourni par le wrapper (`mvnw`, `mvnw.cmd`).
- Airflow sur `http://localhost:8080` pour lancer le pipeline.
- Optionnel : Neo4j (onglet Graph), le service de questions (onglet Ask), Ollama avec `OLLAMA_MODEL` (corrections en langage naturel, noms du graphe). Chaque fonction dit clairement quand son service manque.
- Frontend autorisé par CORS sur `http://localhost:4200`.

## Lancer en local

Depuis `backend` :

```powershell
.\run-backend.ps1
```

Le script lit `..\Data_Quality_Wevioo\.env` et injecte `AIRFLOW_USERNAME`, `AIRFLOW_PASSWORD` (= `AIRFLOW_ADMIN_PASSWORD`), `PIPELINE_API_TOKEN` et `OLLAMA_MODEL`, ainsi que `JAVA_HOME` si la session ne l'a pas rechargé, puis lance `mvnw.cmd spring-boot:run`. Si `JAVA_HOME` n'est défini nulle part, indiquez-le avant de lancer le script.

Commande manuelle équivalente :

```powershell
$airflowEnv = Get-Content ..\Data_Quality_Wevioo\.env | Where-Object { $_ -match '^AIRFLOW_ADMIN_PASSWORD=' } | Select-Object -First 1
$pipelineEnv = Get-Content ..\Data_Quality_Wevioo\.env | Where-Object { $_ -match '^PIPELINE_API_TOKEN=' } | Select-Object -First 1
$env:AIRFLOW_USERNAME='admin'
$env:AIRFLOW_PASSWORD=$airflowEnv.Substring($airflowEnv.IndexOf('=') + 1)
$env:PIPELINE_API_TOKEN=$pipelineEnv.Substring($pipelineEnv.IndexOf('=') + 1)
.\mvnw.cmd spring-boot:run
```

Le backend écoute sur `http://localhost:8081`. Test : `Invoke-RestMethod http://localhost:8081/health` renvoie `{"status": "ok"}`. Un arrêt de Maven n'arrête pas toujours la JVM de l'application : si le port 8081 reste occupé, arrêtez le processus Java du backend.

Avec Docker, l'image se construit avec `backend/Dockerfile` (build Maven puis JRE 17) et se lance depuis le `docker-compose.yml` racine, qui fournit toute la configuration ci-dessous.

## Configuration

Les valeurs par défaut sont dans `src/main/resources/application.properties` ; chaque propriété se surcharge par une variable d'environnement.

| Variable | Défaut | Description |
| --- | --- | --- |
| `SERVER_PORT` | `8081` | Port du backend |
| `DB_URL` | H2 fichier `./data/dq-platform` | Base de métadonnées |
| `DB_USERNAME` / `DB_PASSWORD` | `sa` / vide | Accès à la base |
| `DB_DRIVER` | `org.h2.Driver` | Driver JDBC |
| `JPA_DDL_AUTO` | `update` | Gestion du schéma Hibernate |
| `MAX_UPLOAD_SIZE` | `500MB` | Taille maximale d'un upload |
| `APP_STORAGE_ROOT` | `../Data_Quality_Wevioo/data/uploads` | Où le backend écrit les CSV pour Airflow |
| `AIRFLOW_BASE_URL` | `http://localhost:8080` | API REST d'Airflow |
| `AIRFLOW_USERNAME` / `AIRFLOW_PASSWORD` | `admin` / `admin` | Compte Airflow ; en local, valeur de `Data_Quality_Wevioo\.env` |
| `AIRFLOW_DAG_ID` | `data_quality_analysis_pipeline` | DAG à déclencher |
| `AIRFLOW_DEBUG` | `false` | Logs détaillés du worker |
| `AIRFLOW_CONNECT_TIMEOUT_MS` / `AIRFLOW_READ_TIMEOUT_MS` | `5000` / `15000` | Délais des appels à Airflow |
| `PIPELINE_BACKEND_URL` | `http://host.docker.internal:8081` | Adresse du backend vue depuis Airflow |
| `PIPELINE_API_TOKEN` | `dev-pipeline-token` | Jeton partagé avec Airflow (`X-Pipeline-Token`) |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama (corrections en langage naturel, noms du graphe) |
| `OLLAMA_MODEL` | vide | Modèle à utiliser ; sans lui, ces fonctions répondent 503 |
| `OLLAMA_TIMEOUT_MS` | `120000` | Délai d'une réponse du modèle |
| `NEO4J_URI` | `bolt://localhost:7687` | Neo4j (onglet Graph) |
| `NEO4J_USER` / `NEO4J_PASSWORD` | `neo4j` / `dq-neo4j-dev` | Identifiants de développement |
| `NEO4J_BROWSER_URL` | vide | Adresse de Neo4j Browser pour l'utilisateur ; vide = hôte de `NEO4J_URI`, port 7474. Derrière Docker : `http://localhost:7474` |
| `QA_URL` | `http://localhost:8090` | Service de questions |
| `QA_API_TOKEN` | `dev-qa-token` | Jeton partagé avec le service de questions (`X-QA-Token`) |
| `QA_TIMEOUT_MS` | `180000` | Délai d'une réponse du service de questions |
| `WATCHDOG_INTERVAL_MS` | `60000` | Fréquence du contrôle des runs bloqués |
| `WATCHDOG_STALE_AFTER_MINUTES` | `10` | Âge à partir duquel un run est vérifié auprès d'Airflow |
| `WATCHDOG_MAX_RUN_MINUTES` | `180` | Au-delà, un run toujours en cours passe en `FAILED` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200,http://127.0.0.1:4200` | Origines autorisées |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin` / `admin123` | Compte administrateur (développement) |
| `JWT_SECRET` | valeur de développement | Clé de signature des jetons de connexion |
| `JWT_TTL_MINUTES` | `480` | Durée de validité d'un jeton (8 h) |
| `STRICT_SECRETS` | `false` | `true` : refuse de démarrer avec les secrets par défaut |

Exemple MySQL :

```powershell
$env:DB_URL='jdbc:mysql://localhost:3306/dq_platform?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC'
$env:DB_USERNAME='root'
$env:DB_PASSWORD='your-password'
$env:DB_DRIVER='com.mysql.cj.jdbc.Driver'
.\mvnw.cmd spring-boot:run
```

`SchemaMaintenance` corrige au démarrage les bases créées par d'anciennes versions (colonnes d'énumération devenues `VARCHAR`, contraintes `CHECK` périmées), ce que `ddl-auto=update` ne sait pas faire.

## Sécurité

Connexion par jeton : `POST /api/auth/login` avec `{"username", "password"}` renvoie un JWT signé (HS256, 8 h). Les appels suivants envoient `Authorization: Bearer <jeton>`. Compte par défaut (développement uniquement) : `admin / admin123`.

- Tous les endpoints `/api/**` exigent `ROLE_ADMIN`, sauf `POST /api/auth/login` et `/api/pipeline/**`.
- Les callbacks `/api/pipeline/**` n'ont pas de session : ils exigent l'en-tête `X-Pipeline-Token`, comparé en temps constant.
- Au démarrage, `SecretsGuard` écrit un **WARN** si un secret de développement est utilisé (`ADMIN_PASSWORD`, `PIPELINE_API_TOKEN`, `JWT_SECRET`, `AIRFLOW_PASSWORD`) ; avec `STRICT_SECRETS=true` il refuse de démarrer.
- Le service de questions et Neo4j ne sont atteints que par le backend. `QA_API_TOKEN` et `NEO4J_PASSWORD` valent aussi des valeurs de développement : à changer avant tout partage.

## Endpoints

| Méthode | Endpoint | Description |
| --- | --- | --- |
| `GET` | `/health` | Le backend répond (sans authentification) |
| `POST` | `/api/auth/login` | Renvoie un jeton JWT |
| `GET` | `/api/auth/me` | Utilisateur courant et rôles |
| `POST` | `/api/datasets/upload` | Upload CSV et déclenchement d'Airflow |
| `GET` | `/api/datasets` | Datasets (avec `versionCount`, dernière version, règles échouées) |
| `GET` | `/api/datasets/{id}/versions` | Versions d'un dataset |
| `GET` | `/api/datasets/{id}/versions/{versionId}` | Détails d'une version : tables, `failedTables`, `qualityScore`, `ruleSummary`, `dimensions` |
| `GET` | `/api/datasets/{id}/versions/{versionId}/tables/{tableVersionId}` | Une table avec ses métriques brutes |
| `POST` | `/api/datasets/{id}/versions/{versionId}/rerun` | Relance une version (409 si elle tourne déjà) |
| `GET` | `/api/datasets/{id}/versions/{versionId}/diff?against=` | Écarts avec la version précédente (ou `against`) |
| `GET` `POST` | `/api/datasets/{id}/rules` | Liste / création des règles |
| `PUT` `DELETE` | `/api/datasets/{id}/rules/{ruleId}` | Modification (pause comprise) / suppression |
| `POST` | `/api/datasets/{id}/rules/{ruleId}/approve` \| `dismiss` | Approuve / écarte une règle suggérée |
| `GET` | `/api/datasets/{id}/versions/{versionId}/rule-results` | Résultat des règles pour une version |
| `POST` | `/api/datasets/{id}/versions/{versionId}/rules/evaluate` | Réévalue les règles |
| `GET` | `/api/datasets/{id}/versions/{versionId}/corrections/suggestions?table=` | Corrections trouvées dans une table |
| `POST` | `/api/datasets/{id}/versions/{versionId}/corrections/interpret` \| `preview` \| `apply` | Lit une demande écrite / aperçu chiffré / crée la version corrigée |
| `GET` | `/api/datasets/{id}/versions/{versionId}/corrections` | Journal d'une version corrigée |
| `GET` | `/api/datasets/{id}/versions/{versionId}/graph/schema?promote=&refine=` | Modèle de graphe proposé |
| `POST` | `/api/datasets/{id}/versions/{versionId}/graph/build` | Construit le graphe dans Neo4j (`promote`, `renames`) |
| `GET` | `/api/datasets/{id}/versions/{versionId}/graph` \| `graph/status` | Dernière construction / état de Neo4j (`otherGraphs`) |
| `DELETE` | `/api/datasets/{id}/versions/{versionId}/graph` | Retire le graphe de cette version de Neo4j |
| `POST` | `/api/datasets/{id}/versions/{versionId}/ask` | Pose une question (`{"question": "..."}`, 1000 caractères au plus) |
| `DELETE` | `/api/datasets/{id}` | Supprime dataset, versions, fichiers et graphes (409 pendant un run) |
| `GET` | `/api/events/stream` | Flux SSE temps réel |
| `POST` | `/api/pipeline/{runId}/start` \| `table-analysis` \| `rule-suggestions` \| `global-analysis` \| `complete` | Callbacks d'Airflow (`X-Pipeline-Token`) |

Codes d'erreur : 400 requête invalide, 401 jeton absent ou invalide, 404 ressource ou route inconnue, 409 conflit, 413 upload trop gros, 503 service externe indisponible (Neo4j, modèle, service de questions ; le message est destiné à l'utilisateur), 500 erreur interne (message générique, détail dans les logs).

Événements SSE : `PIPELINE_STARTED`, `TABLE_READY`, `GLOBAL_READY`, `PIPELINE_COMPLETED`, `PIPELINE_FAILED`, `RULES_FAILED`, `RULES_SUGGESTED` (et `CONNECTED` à l'ouverture).

## Upload multipart

`POST /api/datasets/upload`, paramètres :

- `files` : un ou plusieurs CSV (`file` : ancien mode, un seul fichier). Seuls les `.csv` sont acceptés ; `dataset_config.csv` est un nom réservé ; les fichiers vides sont ignorés (`skippedFiles`) ; un même nom deux fois est refusé.
- `name` : nom du dataset (nom du premier fichier par défaut).
- `rerunExisting` : `true` pour relancer une version dont les fichiers sont identiques.
- `domain`, `objective`, `rowGrain` : le domaine, l'objectif de l'analyse et ce que représente une ligne. Enregistrés dans `dataset_context.json` ; ils orientent l'analyse, les rôles de colonnes et les règles suggérées.
- `contextScope` (`none`, `global`, `per_table`, `both`), `globalDescription`, `globalQuestion`, `tableDescriptionsJson` : description et question libres, écrites dans `dataset_config.csv` (colonnes `dataset_name,description,custom_question`) pour le modèle de chaque table.

Le backend valide tous les fichiers et calcule leur SHA-256 avant d'écrire quoi que ce soit, enregistre la version dans une transaction, puis déclenche Airflow une fois la transaction validée, pour que le callback `/start` trouve toujours son run.

## Stockage

Les fichiers d'une version sont dans `<APP_STORAGE_ROOT>/datasets/dataset_<id>/v<n>/` : les CSV, `dataset_context.json`, `dataset_config.csv`, et, selon les fonctions utilisées, `corrections_log.json` (version corrigée) et `graph_build.json` (dernière construction du graphe). Ce dossier est partagé avec Airflow (monté sous `/opt/airflow`). Le pipeline écrit ses résultats dans `Data_Quality_Wevioo/storage`. La suppression d'un dataset retire ces dossiers.

## Organisation du code (`src/main/java/wevioo/backend`)

| Paquet | Contenu |
| --- | --- |
| `config` | propriétés, sécurité (JWT, CORS), contrôle des secrets, correctifs de schéma |
| `controller` | une classe par ressource : auth, datasets, règles et comparaison, corrections, graphe, questions, événements, callbacks du pipeline |
| `service` | upload et versions (`DatasetService`), ingestion des callbacks, règles (`RuleService`), dimensions de qualité, comparaison, client Airflow, watchdog, reprise, SSE, graphe, corrections |
| `domain`, `repository`, `dto` | entités JPA (dataset, version, run, table, colonne, règle, résultat), accès aux données, objets d'API |
| `correction` | lecture CSV en flux (encodage et séparateur détectés), 12 opérations autorisées, aperçu chiffré, application sur une copie, normalisation de dates, client Ollama |
| `graph` | modèle de graphe déduit des données (`GraphModeler`), construction (`GraphBuilder`), noms proposés par le modèle (`GraphNamer`), accès Neo4j (`Neo4jGraphStore`) |
| `qa` | client du service de questions et rassemblement des faits (`QaService`) |

Quelques règles de conception :

- **Les corrections ne modifient jamais la version d'origine.** Seules 12 opérations existent (espaces, casse, remplacement de valeurs ou de texte, marqueurs `N/A`, valeurs par défaut, dates, chiffres seuls, remplissage à gauche, suppression de lignes vides ou en double) ; une date ambiguë (`01/02/2024`) n'est jamais devinée ; l'en-tête du fichier est conservé tel quel ; une ligne qui aurait des données au-delà de l'en-tête fait refuser la correction plutôt que de les perdre.
- **Le graphe vient des données.** Les rôles des colonnes et ce que font les valeurs décident des entités, de leurs clés et de leurs relations ; le modèle de langage ne peut que renommer et décrire. Une clé peut être une colonne, une combinaison de références, ou la position de la ligne dans le fichier ; une table de deux références devient une relation ; une colonne d'identifiants de la même table relie deux lignes de cette table. Les valeurs contradictoires sont conservées (`<propriété>__candidates`, nœud `_conflict`) et jamais tranchées ; les références vers un nœud absent sont signalées ; `NULL`, `N/A`, `NaN`… comptent comme « pas de valeur » comme dans l'analyse. Chaque nœud porte `_scope` (`d<dataset>v<version>`). Une construction est limitée à 500 000 nœuds.
- **Les questions sont relayées, pas interprétées ici.** `QaService` rassemble règles (avec leur message), dimensions, constats, colonnes, schéma du graphe s'il existe et le guide `src/main/resources/help/app-guide.md`, puis passe la question au service.
- **Le pipeline peut être relancé sans risque.** Les callbacks peuvent être rejoués (le worker les retente pendant environ deux minutes : une table déjà reçue est mise à jour, pas dupliquée) ; `RunRecovery` rapproche au démarrage les runs en cours d'Airflow (relancés s'il les ignore, échoués s'il les donne en échec) ; le watchdog échoue les runs restés sans nouvelles.

## Tests

```powershell
.\mvnw.cmd test
```

110 tests : API (upload, versions, règles, corrections, graphe, questions), sécurité, moteur de corrections, modèle et construction du graphe, watchdog, correctifs de schéma. Neo4j, Airflow, Ollama et le service de questions sont simulés ; `surefire` pointe `NEO4J_URI` vers un port fermé pour que les tests ne touchent jamais au graphe de vos datasets.

## Vérification avant push

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
git status --short
```

Ne pas committer : `target/`, `data/`, secrets ou variables locales.

## Remerciements

je remercie hadhemi sioud omi el ghalia al majhoudetha m3aya malgre que ena rasi msa3sa3
