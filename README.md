# Plateforme Data Quality Wevioo

Plateforme d'analyse de qualité de données pour des bases CSV. On dépose des fichiers, on obtient pour chaque table un profil, des scores expliqués, des règles qualité, des corrections applicables sur une copie, un graphe de ses entités dans Neo4j, et un assistant qui répond aux questions sur les données, la qualité et l'usage de l'outil.

| Bloc | Dossier | Rôle |
| --- | --- | --- |
| **frontend** Angular | `frontend` | upload, suivi de l'exécution en temps réel, résultats par onglets |
| **backend** Spring Boot | `backend` | datasets et versions, sécurité, règles, corrections, graphe, questions, déclenchement et surveillance d'Airflow, SSE |
| **worker** Python / Airflow | `Data_Quality_Wevioo` | profiling des tables, rôles de colonnes, métriques, relations entre tables, analyse par le modèle de langage (Ollama), règles suggérées |
| **service de questions** Python | `graphrag` | répond aux questions de l'onglet Ask, avec des garde-fous sur le Cypher généré |
| **Neo4j** | `docker-compose.neo4j.yml` | base de graphe construite par l'onglet Graph |

Chaque dossier a son propre `README.md` (démarrage, configuration, organisation du code, tests).

## Démarrage en une commande

Développement sous Windows : une fenêtre par service (Ollama, Airflow, service de questions, backend, frontend) ; Neo4j démarre en arrière-plan dans Docker. Le navigateur s'ouvre quand tout répond.

```powershell
.\start-dev.ps1
```

Tout dans Docker (Airflow, Neo4j, service de questions, backend, frontend ; Ollama reste sur la machine) :

```powershell
docker compose --env-file Data_Quality_Wevioo/.env up --build
```

Application : `http://localhost:4200` · API : `http://localhost:8081` · Airflow : `http://localhost:8080` · Neo4j Browser : `http://localhost:7474` · Questions : `http://localhost:8090`. Le fichier `Data_Quality_Wevioo\.env` doit exister (copie `.env.example`) : il porte les secrets et le modèle Ollama (`OLLAMA_MODEL`), d'où l'option `--env-file`.

Le `docker-compose.yml` racine inclut ceux du worker et de Neo4j : tous les services partagent un réseau et les mêmes secrets, et le backend appelle Airflow, Neo4j et le service de questions par leur nom de service. Les ports du mode développement (8081, 4200, 8090, 7687) sont les mêmes que ceux de Docker : arrêtez l'un avant de lancer l'autre.

| Conteneur | Rôle | Port |
| --- | --- | --- |
| `frontend` | interface Angular servie par nginx | 4200 |
| `backend` | API Spring Boot, base H2 (volume `backend_data`) | 8081 |
| `airflow-webserver` | interface web d'Airflow | 8080 |
| `airflow-scheduler` | exécute les étapes du pipeline | interne |
| `airflow-init` | migre la base d'Airflow et crée l'utilisateur admin, puis s'arrête | aucun |
| `postgres` | base d'Airflow (pas la base de l'application) | interne |
| `neo4j` | base de graphe (volume `neo4j_data`) | 7474, 7687 |
| `graphrag` | service de questions | 8090 |

Les conteneurs portent le nom du projet Compose défini par `COMPOSE_PROJECT_NAME` dans `.env` (`data-quality-pipeline-*` par défaut). Pour revenir au mode développement : `docker compose --env-file Data_Quality_Wevioo/.env down`, puis `.\start-dev.ps1`. Les graphes Neo4j de Docker et du mode développement sont dans des volumes séparés.

## Démarrage manuel

Sans le script : un terminal PowerShell par service, depuis la racine du projet, dans cet ordre.

### 1. Ollama (optionnel)

```powershell
ollama serve
ollama pull qwen2.5:14b-instruct      # ou tout autre modèle, à indiquer dans OLLAMA_MODEL
```

Sans Ollama, le pipeline fonctionne quand même : les insights sont calculés à partir des métriques et l'interface l'indique (« Deterministic only – Ollama unavailable »). Les corrections écrites en langage naturel, les noms proposés par l'IA pour le graphe et l'onglet Ask ont besoin d'un modèle. Si `bind: Only one usage of each socket address` s'affiche, Ollama tourne déjà.

### 2. Airflow

```powershell
cd .\Data_Quality_Wevioo
Copy-Item .\.env.example .\.env -ErrorAction SilentlyContinue
docker compose up --build
```

- UI Airflow : `http://localhost:8080`, login `admin` et la valeur de `AIRFLOW_ADMIN_PASSWORD` dans `Data_Quality_Wevioo\.env`.
- `PIPELINE_API_TOKEN` dans `.env` doit être identique côté backend (le script de lancement du backend le lit automatiquement).
- `OLLAMA_MODEL` dans `.env` est aussi lu par le script du backend.

### 3. Neo4j (onglet Graph)

```powershell
docker compose -f docker-compose.neo4j.yml up -d
```

Neo4j Browser : `http://localhost:7474`, utilisateur `neo4j`, mot de passe de développement `dq-neo4j-dev` (à changer via `NEO4J_PASSWORD` avant tout partage).

### 4. Service de questions (onglet Ask)

```powershell
cd .\graphrag
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:OLLAMA_MODEL = 'qwen2.5:14b-instruct'
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8090
```

### 5. Backend

```powershell
cd .\backend
.\run-backend.ps1
```

- API : `http://localhost:8081` ; test : `Invoke-RestMethod http://localhost:8081/health`.
- Java 17 requis : `winget install --id EclipseAdoptium.Temurin.17.JDK -e`.

### 6. Frontend

```powershell
cd .\frontend
npm install
npm start
```

Ouvre `http://localhost:4200` et connecte-toi avec `admin / admin123` (valeurs de développement, voir *Sécurité*).

## Architecture

```text
Navigateur -> Angular (4200) -> Spring Boot (8081, /api)
Spring Boot -> Airflow REST (8080) : déclenche le DAG après l'enregistrement de la version
Airflow -> Spring Boot /api/pipeline/{runId}/... : start, table-analysis, rule-suggestions, global-analysis, complete
Spring Boot -> Angular : SSE /api/events/stream (repli automatique en polling)
Spring Boot -> Neo4j (7687) : construction et suppression du graphe d'une version
Spring Boot -> service de questions (8090) : question + faits qualité ; le service lit Neo4j
Airflow, Spring Boot, service de questions -> Ollama (11434) : modèle de langage local
```

| Élément | Où | Détail |
| --- | --- | --- |
| Base du backend | `backend/data` | fichier H2 (MySQL possible) : datasets, versions, règles, résultats |
| Fichiers envoyés | `Data_Quality_Wevioo/data/uploads` | `datasets/dataset_<id>/v<n>/` : CSV, contexte, corrections, graphe |
| Résultats du pipeline | `Data_Quality_Wevioo/storage` | métriques et analyses JSON, manifestes d'exécution |
| Graphes | Neo4j | un graphe par version, repéré par la propriété `_scope` (`d<dataset>v<version>`) |

## Fonctionnement

1. L'admin se connecte (`POST /api/auth/login`, jeton JWT valable 8 h, gardé dans `sessionStorage`).
2. Il dépose un ou plusieurs CSV dans la fenêtre **New analysis** (une table par fichier), avec en option le domaine, l'objectif et ce que représente une ligne.
3. Le backend valide tous les fichiers avant d'écrire quoi que ce soit, calcule un SHA-256 par fichier, crée la version, enregistre les fichiers, puis déclenche Airflow une fois la transaction validée.
4. Airflow exécute les phases visibles : validation, notification, découverte des tables, profiling, métriques et analyse par le modèle, publication, suggestion de règles, analyse globale, clôture.
5. Chaque table apparaît dès que ses métriques sont prêtes, puis est enrichie par le modèle.
6. Le statut final est `COMPLETED`, `PARTIAL` (au moins une table en échec, avec la raison) ou `FAILED`. Un bouton **Retry** relance une version en échec ou partielle.
7. Les règles qualité du dataset sont évaluées sur la version terminée ; un échec déclenche une alerte (toast en direct, badge dans la liste des datasets, bandeau dans la vue d'ensemble).

### Onglets d'une version

| Onglet | Contenu |
| --- | --- |
| Overview | Statut du run, score et note A–D, dimensions de qualité avec leur calcul, santé de chaque table, colonnes à revoir, insights globaux |
| Tables | Profil d'une table : colonnes et leur rôle (avec les indices), valeurs fréquentes, valeurs manquantes, associations, insights |
| Data model | Diagramme des tables et relations (recouvrement de valeurs) |
| Insights | Analyse sémantique (rôles des colonnes, contrat de données, surveillance, causes, impacts) |
| Compare | Écarts avec une version plus ancienne : score, lignes, colonnes ajoutées/supprimées, changements de type, valeurs manquantes |
| Rules | Règles qualité du dataset et résultat pour cette version ; règles proposées à partir de l'objectif donné à l'envoi (à approuver) |
| Corrections | Corrections trouvées dans les données ou écrites en langage naturel, aperçu chiffré, puis application sur une copie (nouvelle version) |
| Graph | Modèle de graphe proposé (entités, propriétés, relations), conflits, construction et suppression dans Neo4j |
| Ask | Questions en langage naturel : sur les données (via le graphe), sur la qualité (règles, scores, constats) et sur l'usage de l'application ; chaque réponse dit d'où elle vient |

Le bouton **Report** ouvre une page imprimable de la version (Imprimer / Enregistrer en PDF, ou téléchargement Markdown), avec la définition de chaque score.

Raccourcis : `Ctrl/⌘ K` ou `/` (palette de commandes), `N` (nouvelle analyse), `1`–`9` (changer d'onglet).

### Contexte de l'analyse

À l'envoi, on peut donner un **domaine**, un **objectif** et ce que **représente une ligne** (un client, un compte, une opération). Ils sont enregistrés dans `dataset_context.json` à côté des CSV, conservés lors d'un Retry, et lus par le pipeline pour orienter l'analyse (un identifiant répété est normal si une ligne n'est pas l'entité qu'il nomme) et pour proposer des règles. Un contexte libre, global ou par table, peut aussi être donné (`dataset_config.csv`, nom réservé).

### Rôles de colonnes et dimensions de qualité

Chaque colonne reçoit un rôle (identifiant, référence, email, date, montant…) calculé à partir de son nom, de ses valeurs et de leurs formats ; les indices sont conservés. Seules les colonnes restées floues sont tranchées par le modèle. La vue d'ensemble affiche complétude, unicité, validité et cohérence avec leur numérateur et leur dénominateur, ou « not measured » avec la raison.

### Règles qualité

Types : colonne sans valeur manquante, au plus X % de valeurs manquantes, valeurs uniques, au moins N lignes, au plus X % de doublons, score minimal, baisse maximale du nombre de lignes par rapport à la version précédente, **valeurs au bon format** (email, téléphone, URL, date, nombre) et **ordre de deux dates**. Chaque règle est `WARNING` ou `CRITICAL` (une règle échouée déclenche l'alerte dans les deux cas ; les échecs `CRITICAL` sont comptés à part), peut être mise en pause, et vise une table (et une colonne). Elles sont évaluées à la fin de chaque run et à la demande (**Check now**). Le pipeline propose des règles à partir de l'objectif : elles restent des **suggestions** (non vérifiées) jusqu'à approbation, et une suggestion écartée ne revient pas.

### Corrections

Une table peut être corrigée à partir de ce que l'application trouve (espaces, marqueurs `N/A`, dates en plusieurs formats, une valeur écrite de plusieurs façons, doublons) ou d'une demande écrite en langage naturel, lue par le modèle local (`OLLAMA_MODEL` doit être défini pour le backend). Le modèle ne choisit que parmi 12 opérations vérifiées contre les colonnes. L'aperçu donne le nombre de valeurs touchées, des exemples et des avertissements ; une date ambiguë (`01/02/2024`) n'est jamais devinée. L'application crée une **nouvelle version** (analysée comme un envoi) avec un journal ; la version d'origine n'est jamais modifiée. Les fichiers corrigés sont écrits en UTF-8 avec des virgules, en-tête inchangé.

### Graphe (Neo4j)

Le modèle est déduit des rôles et des données : une ligne de comptes est un *Account*, et le *Customer* qu'elle référence devient un nœud à part dont les colonnes constantes pour un client sont les propriétés. Les relations viennent des liens trouvés par l'analyse. Quelques cas courants sont gérés sans identifiant déclaré :

- une table sans colonne identifiant dont les lignes se distinguent par une **combinaison de références** (une commande et un numéro de ligne) est identifiée par cette combinaison ;
- une table dont une colonne désigne les **numéros de ligne** d'un autre fichier sans identifiant est reliée à ces lignes ;
- une **table de deux références** (amis, produit-magasin) devient une relation, sans nœud propre ;
- une colonne d'identifiants de la **même table** (un `manager_id`) relie deux lignes de cette table.

Quand les lignes d'une même clé se contredisent (deux emails pour un client), le graphe garde **toutes** les valeurs (`email__candidates`), marque le nœud `_conflict` et ne choisit rien ; les références vers un nœud inexistant, les tables liées à rien et les liens incertains sont signalés. `NULL`, `N/A`, `NaN`… comptent comme « pas de valeur », comme dans l'analyse.

Chaque nœud porte `_scope` (`d<dataset>v<version>`) : les versions ne se mélangent pas. Dans Neo4j Browser, gardez le filtre `_scope` de la requête proposée par l'onglet, sans quoi les graphes de plusieurs versions s'affichent mélangés. Le bouton **Delete this graph** retire le graphe d'une version, et l'onglet signale quand Neo4j en contient d'autres. Derrière Docker, `NEO4J_BROWSER_URL` (fixé à `http://localhost:7474` dans `docker-compose.yml`) donne l'adresse que le navigateur de l'utilisateur doit ouvrir.

### Questions (onglet Ask)

Le service `graphrag/` commence par **trier la question** : sur le contenu des données (*graph*), sur la qualité (*quality* : règles et pourquoi elles échouent, scores, constats) ou sur l'usage de l'application (*help*). Seule la première utilise Neo4j ; les deux autres répondent avant même que le graphe soit construit. Pour une question *graph*, le modèle local écrit une requête Cypher qui passe par des garde-fous (limitée au `_scope` de la version, lecture seule, `EXPLAIN` avant exécution, délai maximal, deux essais). La réponse est rédigée **uniquement** à partir des lignes obtenues, des faits que le backend joint à la question (règles et leur message, dimensions, constats, rôles des colonnes) et du guide d'utilisation (`backend/src/main/resources/help/app-guide.md`, à tenir à jour quand l'interface change). La requête et les lignes sont renvoyées avec la réponse pour qu'on puisse la vérifier. Détails, API et évaluation : `graphrag/README.md`.

### Reprise après un redémarrage

- Au démarrage, le backend interroge Airflow pour chaque run `PENDING`/`RUNNING` : un run inconnu d'Airflow est relancé (si ses fichiers sont toujours là), un run en échec côté Airflow est marqué `FAILED`, un run en cours est laissé tranquille.
- Les callbacks du worker (tables, analyse globale, fin de run) sont retentés pendant environ deux minutes : un redémarrage du backend n'interrompt pas un run en cours.
- Le watchdog continue de surveiller les runs bloqués.

### Statuts

| Objet | Valeurs |
| --- | --- |
| Run / version | `PENDING` (en file), `RUNNING`, `COMPLETED`, `PARTIAL`, `FAILED` |
| Table par rapport à la version précédente | `NEW`, `MODIFIED`, `UNCHANGED`, `REMOVED` |
| Statut du modèle (`llmStatus`) | `ok`, `unavailable` (Ollama injoignable), `invalid` (réponse rejetée par la validation), `disabled`, `pending` |
| Règle | état `ACTIVE`, `SUGGESTED`, `DISMISSED` ; résultat `PASS`, `FAIL`, `SKIPPED` |

- **Doublons** : un upload identique (mêmes noms de fichiers et même contenu SHA-256) propose d'ouvrir la version existante ou de la relancer. Un fichier modifié de même taille crée bien une nouvelle version.
- **Changements de table** : comparés par empreinte du contenu (`content_hash`) et du schéma ; une valeur modifiée donne `MODIFIED`. Les tables absentes de la nouvelle version sont listées `REMOVED`.
- **Score qualité** : par table, 100 − % moyen de valeurs manquantes − % de lignes dupliquées ; pour la version, moyenne pondérée par le nombre de lignes. Notes : A à partir de 90, B de 75, C de 60, D en dessous.
- **Watchdog** : toutes les minutes, les runs `PENDING`/`RUNNING` depuis plus de 10 min sont vérifiés auprès d'Airflow ; un run inconnu ou en échec dans Airflow passe en `FAILED` avec un message clair, et un run sans fin au bout de 180 min aussi.

## API

Tous les endpoints `/api/**` (sauf login et callbacks du pipeline) exigent `Authorization: Bearer <jeton>` et le rôle `ADMIN`. La liste complète est dans `backend/README.md` ; en résumé :

| Ressource | Endpoints |
| --- | --- |
| Santé et session | `GET /health`, `POST /api/auth/login`, `GET /api/auth/me` |
| Datasets et versions | `POST /api/datasets/upload`, `GET /api/datasets`, `GET .../{id}/versions[/{versionId}[/tables/{tableVersionId}]]`, `POST .../rerun`, `GET .../diff`, `DELETE /api/datasets/{id}` |
| Règles | `GET` `POST` `PUT` `DELETE /api/datasets/{id}/rules[/{ruleId}]`, `.../approve`, `.../dismiss`, `GET .../rule-results`, `POST .../rules/evaluate` |
| Corrections | `GET .../corrections/suggestions`, `POST .../corrections/interpret` `preview` `apply`, `GET .../corrections` |
| Graphe | `GET .../graph/schema`, `POST .../graph/build`, `GET .../graph`, `GET .../graph/status`, `DELETE .../graph` |
| Questions | `POST .../versions/{versionId}/ask` |
| Temps réel | `GET /api/events/stream` (SSE) |
| Callbacks d'Airflow | `POST /api/pipeline/{runId}/start` `table-analysis` `rule-suggestions` `global-analysis` `complete` (`X-Pipeline-Token`) |

Codes d'erreur : 400 requête invalide, 401 jeton absent ou invalide (y compris jeton pipeline), 404 ressource ou route inconnue, 409 conflit, 413 upload trop gros, 503 service externe indisponible (Neo4j, modèle, service de questions), 500 erreur interne (message générique, détail dans les logs).

## Sécurité

- Les valeurs par défaut (`admin123`, `dev-pipeline-token`, `JWT_SECRET` de dev, mot de passe Airflow `admin`) sont réservées au développement : le backend affiche un **WARN** au démarrage s'il les utilise, et `STRICT_SECRETS=true` refuse de démarrer avec elles.
- Variables à définir avant tout partage : `ADMIN_PASSWORD`, `JWT_SECRET`, `PIPELINE_API_TOKEN`, `AIRFLOW_PASSWORD` (backend), `NEO4J_PASSWORD`, `QA_API_TOKEN` et les secrets de `Data_Quality_Wevioo\.env`.
- Le service de questions n'est appelé que par le backend (jeton `X-QA-Token`) ; le navigateur ne l'atteint jamais. Les requêtes Cypher qu'il génère ne peuvent que lire, et seulement le graphe de la version interrogée.
- Le port PostgreSQL d'Airflow n'est pas publié sur l'hôte.
- Ne jamais committer `Data_Quality_Wevioo\.env`.

## Intégration continue

`.github/workflows/ci.yml` exécute, à chaque push et pull request : les tests du backend (`./mvnw test`), le build et les tests du frontend, les tests Python du pipeline (avec les contraintes d'Airflow 2.10.0 / Python 3.12), les tests du service de questions et la validation du `docker-compose.yml`.

## Tests

```powershell
cd .\backend; .\mvnw.cmd test                                                   # 110 tests
cd .\frontend; npm run build; npm test                                         # 48 tests
cd .\Data_Quality_Wevioo; .\venv\Scripts\python.exe -m pip install -r requirements-dev.txt; .\venv\Scripts\python.exe -m pytest   # 67 tests
cd .\graphrag; .\.venv\Scripts\python.exe -m pytest                            # 39 tests
```

Les tests n'ont besoin ni d'Airflow, ni de Neo4j, ni d'Ollama (ils sont simulés). Pour mesurer la qualité des réponses de l'onglet Ask sur de vrais datasets, `graphrag/eval/run_eval.py` rejoue une vingtaine de questions contre les services en marche (voir `graphrag/README.md`).

## Git

Le `.gitignore` racine exclut `.env`, `node_modules`, `dist`, `.angular`, `target`, `backend/data`, `Data_Quality_Wevioo/data`, `storage`, `logs`, `airflow.cfg`, `*.pid`, `*.db`, `graphrag/.venv`. Vérifie `git status --short` avant un push.

READMEs détaillés : `frontend\README.md`, `backend\README.md`, `Data_Quality_Wevioo\README.md` (avec `README_INSTRUCTOR_SETUP.md` et `VERIFY_BEFORE_SHARING.md`), `graphrag\README.md`.

## Dépannage

**`JAVA_HOME` non défini** — installe le JDK 17, rouvre PowerShell, puis si besoin :

```powershell
$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory | Where-Object { $_.Name -like 'jdk-17*' } | Select-Object -First 1 -ExpandProperty FullName
[Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'User')
[Environment]::SetEnvironmentVariable('Path', "$jdk\bin;" + [Environment]::GetEnvironmentVariable('Path', 'User'), 'User')
```

**Le port 8081 reste occupé après l'arrêt de Maven** — arrêter Maven n'arrête pas toujours la JVM du backend : arrêtez le processus Java qui exécute `wevioo.backend.BackendApplication`.

**Airflow : login invalide** — le mot de passe vient de `AIRFLOW_ADMIN_PASSWORD`. Si le volume Postgres garde un ancien mot de passe :

```powershell
cd .\Data_Quality_Wevioo
$line = Get-Content .\.env | Where-Object { $_ -match '^AIRFLOW_ADMIN_PASSWORD=' } | Select-Object -First 1
docker compose exec airflow-webserver airflow users reset-password -u admin -p $line.Substring($line.IndexOf('=') + 1)
```

**Run en échec dès l'upload (« Could not start the analysis in Airflow »)** — Airflow n'est pas joignable ou refuse les identifiants (403). Relance le backend avec `.\run-backend.ps1` (il lit le mot de passe Airflow dans `.env`), puis si besoin `docker compose exec airflow-webserver airflow sync-perm`. Une fois Airflow disponible, clique **Retry**.

**Run qui reste « Queued »** — après 2 minutes l'interface affiche un lien vers Airflow. Vérifie que le DAG n'est pas en pause :

```powershell
docker compose exec airflow-webserver airflow dags unpause data_quality_analysis_pipeline
```

**Onglet Graph : « Neo4j is not running »** — lance `docker compose -f docker-compose.neo4j.yml up -d` (ou `start-dev.ps1`). Le message indique l'adresse tentée ; derrière Docker c'est `bolt://neo4j:7687`.

**Onglet Ask : « service injoignable », 503 ou erreur de jeton** — le service de questions doit tourner sur le port 8090 ; il a besoin de `OLLAMA_MODEL` pour répondre ; `QA_API_TOKEN` doit être identique côté backend et côté service. Une question sur les données sans graphe construit répond de le construire dans l'onglet Graph ; les questions sur la qualité et l'usage n'en ont pas besoin.

**Neo4j Browser montre des clients ou des nœuds « en double »** — plusieurs versions (ou plusieurs datasets) ont un graphe dans la même base et la requête n'a pas de filtre `_scope`. Reprenez la requête de l'onglet Graph, ou supprimez les graphes dont vous n'avez plus besoin.

**`npm ci` échoue sous Linux (Docker ou CI) avec « Missing: @emnapi/core »** — le `package-lock.json` a été régénéré sous Windows sans les dépendances optionnelles des autres plateformes. Régénérez-le dans un conteneur Linux :

```powershell
docker run --rm -v "${PWD}/frontend:/app" -w /app node:22-alpine sh -c "npm install --package-lock-only"
```
